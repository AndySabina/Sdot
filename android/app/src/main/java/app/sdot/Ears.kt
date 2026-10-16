package app.sdot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Continuous on-device speech recognition. Never falls back to a cloud recognizer: if the
 * offline model is missing it asks the system to download it and tells the user.
 * Must be used from the main thread.
 */
class Ears(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onHeard(text: String)
        fun onSpeechStart()
        fun onNotice(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var language = Locale.getDefault().toLanguageTag()
    private var started = false
    private var ready = false
    private var paused = false
    private var listening = false

    private val listen = Runnable { listenNow() }
    private val recheck = Runnable { checkSupport() }

    fun start() {
        if (started) return
        started = true
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            listener.onNotice(context.getString(R.string.notice_no_on_device))
            return
        }
        createRecognizer()
        checkSupport()
    }

    /** Stops listening until [resume]; used while the agent talks through the loudspeaker. */
    fun pause() {
        paused = true
        main.removeCallbacks(listen)
        if (listening) recognizer?.cancel()
        listening = false
    }

    fun resume(delayMs: Long = 300) {
        if (!paused) return
        paused = false
        listenSoon(delayMs)
    }

    fun stop() {
        started = false
        main.removeCallbacks(listen)
        main.removeCallbacks(recheck)
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
            setRecognitionListener(callbacks)
        }
    }

    private fun checkSupport() {
        val r = recognizer ?: return
        r.checkRecognitionSupport(intent(), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) = onSupport(support)

            override fun onError(error: Int) {
                // Support could not be checked; try to listen and let errors guide the user.
                ready = true
                listenSoon(0)
            }
        })
    }

    private fun onSupport(support: RecognitionSupport) {
        if (!started) return
        val device = Locale.getDefault().toLanguageTag()
        val installed = Languages.match(device, support.installedOnDeviceLanguages)
        val pending = Languages.match(device, support.pendingOnDeviceLanguages)
        val supported = Languages.match(device, support.supportedOnDeviceLanguages)
        when {
            installed != null -> {
                language = installed
                ready = true
                listenSoon(0)
            }
            pending != null -> {
                language = pending
                waitForDownload()
            }
            supported != null -> {
                language = supported
                recognizer?.triggerModelDownload(intent())
                waitForDownload()
            }
            support.installedOnDeviceLanguages.isEmpty() && support.supportedOnDeviceLanguages.isEmpty() -> {
                // The engine reports nothing; try anyway rather than refusing to listen.
                ready = true
                listenSoon(0)
            }
            else -> listener.onNotice(context.getString(R.string.notice_language_unsupported, displayName()))
        }
    }

    private fun waitForDownload() {
        ready = false
        listener.onNotice(context.getString(R.string.notice_downloading, displayName()))
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, RECHECK_MS)
    }

    private fun listenSoon(delayMs: Long) {
        main.removeCallbacks(listen)
        main.postDelayed(listen, delayMs)
    }

    private fun listenNow() {
        if (!started || paused || !ready || listening) return
        val r = recognizer ?: return
        listening = true
        r.startListening(intent())
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private fun displayName(): String = Locale.forLanguageTag(language).displayName

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() = listener.onSpeechStart()
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (started && !paused && !text.isNullOrEmpty()) listener.onHeard(text)
            listenSoon(RESTART_MS)
        }

        override fun onError(error: Int) {
            listening = false
            if (!started) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenSoon(RESTART_MS)
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> listenSoon(500)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stop()
                    listener.onNotice(context.getString(R.string.notice_mic_permission))
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> waitForDownload()
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    createRecognizer()
                    listenSoon(1000)
                }
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> listenSoon(3000)
                else -> listenSoon(1000)
            }
        }
    }

    private companion object {
        const val RESTART_MS = 150L
        const val RECHECK_MS = 15_000L
    }
}
