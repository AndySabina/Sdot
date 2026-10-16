package app.sdot

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Queued platform text-to-speech that reports when it starts and stops talking. Prefers Google's
 * engine and the best offline voice for the phone language. Main thread only.
 */
class Mouth(context: Context, private val listener: Listener) {
    interface Listener {
        fun onSpeakingChanged(speaking: Boolean)
        fun onUnavailable()
        /** [name] is null when no offline voice matches the language; [lowQuality] suggests installing one. */
        fun onVoiceChosen(name: String?, lowQuality: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = LinkedHashSet<String>()
    private val backlog = ArrayList<String>()
    private var ready = false
    private var closed = false
    private var counter = 0
    private var speaking = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { status ->
        main.post { onInit(status) }
    }, preferredEngine(context))

    val isSpeaking: Boolean get() = speaking

    fun speak(text: String) {
        if (closed) return
        if (!ready) backlog.add(text) else enqueue(text)
        refresh()
    }

    /** Stops the current sentence and drops everything queued. */
    fun stop() {
        backlog.clear()
        if (pending.isNotEmpty()) {
            pending.clear()
            tts.stop()
        }
        refresh()
    }

    fun shutdown() {
        closed = true
        backlog.clear()
        pending.clear()
        tts.stop()
        tts.shutdown()
        refresh()
    }

    private fun onInit(status: Int) {
        if (closed) return
        if (status != TextToSpeech.SUCCESS) {
            backlog.clear()
            refresh()
            listener.onUnavailable()
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setLanguage(Locale.getDefault())
        chooseVoice()
        tts.setSpeechRate(1.0f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
        })
        ready = true
        backlog.forEach(::enqueue)
        backlog.clear()
        refresh()
    }

    private fun chooseVoice() {
        val voices = try {
            tts.voices?.toList().orEmpty()
        } catch (e: RuntimeException) {
            emptyList()
        }
        val options = voices.map {
            VoiceOption(
                name = it.name,
                locale = it.locale,
                quality = it.quality,
                latency = it.latency,
                networkRequired = it.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty(),
            )
        }
        val best = Voices.best(options, Locale.getDefault())
        if (best != null) voices.firstOrNull { it.name == best.name }?.let(tts::setVoice)
        listener.onVoiceChosen(best?.name, Voices.isLowQuality(best))
    }

    private fun enqueue(text: String) {
        val id = "u${counter++}"
        pending.add(id)
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) pending.remove(id)
    }

    private fun finished(id: String) {
        main.post {
            pending.remove(id)
            refresh()
        }
    }

    private fun refresh() {
        val now = pending.isNotEmpty() || backlog.isNotEmpty()
        if (now == speaking) return
        speaking = now
        listener.onSpeakingChanged(now)
    }
}

private const val GOOGLE_TTS = "com.google.android.tts"

/** Google's engine ships natural offline voices; null keeps the user's default engine. */
private fun preferredEngine(context: Context): String? {
    val engines = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
    return GOOGLE_TTS.takeIf { engines.any { it.serviceInfo?.packageName == GOOGLE_TTS } }
}
