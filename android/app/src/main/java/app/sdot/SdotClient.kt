package app.sdot

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * HTTPS client for the pi extension: a reconnecting SSE reader thread plus off-main-thread POSTs.
 * Every callback is delivered on the main thread. The token is only ever sent as a header.
 */
class SdotClient(private val pairing: Pairing, private val listener: Listener) {
    interface Listener {
        fun onConnecting(reconnecting: Boolean)
        fun onEvent(type: String, data: Map<String, Any?>)
        fun onUnauthorized()
    }

    enum class Result { OK, UNAUTHORIZED, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val posts: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var running = false
    @Volatile private var stream: HttpsURLConnection? = null
    private var reader: Thread? = null

    fun start() {
        if (running) return
        running = true
        reader = Thread(::readLoop, "sdot-events").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        // Closing a TLS socket may write, which is not allowed on the main thread.
        stream?.let { conn -> Thread { conn.disconnect() }.start() }
        reader?.interrupt()
        reader = null
        posts.shutdown()
    }

    fun say(text: String, done: (Result) -> Unit) =
        post("/v1/say", "{\"text\":${Json.quote(text.take(MAX_TEXT_CHARS))}}", done)

    fun abort(done: (Result) -> Unit) = post("/v1/abort", null, done)

    private fun post(path: String, body: String?, done: (Result) -> Unit) {
        if (posts.isShutdown) return
        posts.execute {
            val result = try {
                val conn = open(path)
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    if (body != null) {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        conn.setFixedLengthStreamingMode(bytes.size)
                        conn.outputStream.use { it.write(bytes) }
                    }
                    val code = conn.responseCode
                    drain(conn, code)
                    when (code) {
                        in 200..299 -> Result.OK
                        401 -> Result.UNAUTHORIZED
                        else -> Result.FAILED
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                Result.FAILED
            }
            main.post { done(result) }
        }
    }

    private fun readLoop() {
        val backoff = Backoff()
        var attempt = 0
        while (running) {
            val reconnecting = attempt > 0
            main.post { if (running) listener.onConnecting(reconnecting) }
            var unauthorized = false
            try {
                val conn = open("/v1/events")
                stream = conn
                try {
                    conn.connectTimeout = TIMEOUT_MS
                    // The extension sends a keepalive comment every 15 s.
                    conn.readTimeout = STREAM_READ_TIMEOUT_MS
                    conn.setRequestProperty("Accept", "text/event-stream")
                    val code = conn.responseCode
                    if (code == 401) {
                        unauthorized = true
                    } else if (code == 200) {
                        val parser = SseParser()
                        InputStreamReader(conn.inputStream, Charsets.UTF_8).use { input ->
                            val buffer = CharArray(4096)
                            while (running) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                for (event in parser.feed(String(buffer, 0, n))) {
                                    if (event.type == "hello") backoff.reset()
                                    dispatch(event)
                                }
                            }
                        }
                    } else {
                        drain(conn, code)
                    }
                } finally {
                    stream = null
                    conn.disconnect()
                }
            } catch (e: IOException) {
                // Network drop or timeout: fall through to the backoff below.
            }
            if (unauthorized) {
                running = false
                main.post { listener.onUnauthorized() }
                return
            }
            if (!running) return
            attempt++
            try {
                Thread.sleep(backoff.next())
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun dispatch(event: SseEvent) {
        val data = try {
            Json.parseObject(event.data)
        } catch (e: IllegalArgumentException) {
            return
        }
        main.post { if (running) listener.onEvent(event.type, data) }
    }

    private fun open(path: String): HttpsURLConnection {
        // Pairing guarantees an https URL, so this cast never sees a plain HTTP connection.
        val conn = URL(pairing.url + path).openConnection() as HttpsURLConnection
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Authorization", "Bearer ${pairing.token}")
        return conn
    }

    private fun drain(conn: HttpsURLConnection, code: Int) {
        try {
            (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() }
        } catch (e: IOException) {
            // Body is informational only.
        }
    }

    private companion object {
        const val MAX_TEXT_CHARS = 4000
        const val TIMEOUT_MS = 15_000
        const val STREAM_READ_TIMEOUT_MS = 45_000
    }
}
