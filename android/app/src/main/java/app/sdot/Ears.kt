package app.sdot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Continuous on-device speech recognition. Never falls back to a cloud recognizer: if the
 * offline model is missing it asks the system to download it and tells the user.
 * Segments heard close together are merged into one utterance before [Listener.onHeard].
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
    // Segmented sessions keep one recognition open across pauses; dropped if the engine rejects them.
    private var segmented = true
    private var segmentSeen = false
    private val assembler = UtteranceAssembler(MERGE_GAP_MS) { SystemClock.elapsedRealtime() }

    private val listen = Runnable { listenNow() }
    private val deliver = Runnable { deliverNow() }
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
        // Send what was already heard instead of losing it with the cancelled session.
        main.removeCallbacks(deliver)
        assembler.flush()?.let(listener::onHeard)
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
        main.removeCallbacks(deliver)
        assembler.clear()
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
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Tolerate natural pauses instead of ending the utterance at the first breath.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_COMPLETE_SILENCE_MS)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_LENGTH_MS)
        .apply {
            // API 33 (our minSdk): segments at each pause, session ends after the complete silence.
            if (segmented) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
            }
        }

    private fun scheduleDelivery() {
        main.removeCallbacks(deliver)
        if (assembler.hasPending()) main.postDelayed(deliver, assembler.msUntilDue())
    }

    private fun deliverNow() {
        if (!started) return
        val text = assembler.takeIfDue()
        if (text != null) listener.onHeard(text) else scheduleDelivery()
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun displayName(): String = Locale.forLanguageTag(language).displayName

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {
            assembler.activity()
            listener.onSpeechStart()
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (started && !paused) assembler.partial(firstResult(partialResults))
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            segmentSeen = true
            if (!started || paused) return
            assembler.segment(firstResult(segmentResults))
            scheduleDelivery()
        }

        override fun onEndOfSegmentedSession() {
            listening = false
            if (!started || paused) return
            assembler.endWithoutResult()
            scheduleDelivery()
            // Restart at once: speech in the gap would lose the start of the next sentence.
            listenSoon(0)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            if (!started || paused) return
            assembler.segment(firstResult(results))
            scheduleDelivery()
            listenSoon(0)
        }

        override fun onError(error: Int) {
            listening = false
            if (!started) return
            if (!paused) {
                assembler.endWithoutResult()
                scheduleDelivery()
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenSoon(0)
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // An engine without segmented sessions may reject the request; use plain sessions.
                    if (segmented && !segmentSeen) segmented = false
                    listenSoon(500)
                }
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
        const val COMPLETE_SILENCE_MS = 2000L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 1500L
        const val MINIMUM_LENGTH_MS = 1000L
        const val MERGE_GAP_MS = 1200L
        const val RECHECK_MS = 15_000L
    }
}
