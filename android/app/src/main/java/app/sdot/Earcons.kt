package app.sdot

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock

/** Short tones that let the user hear the agent working without looking at the phone. */
class Earcons {
    private val tones = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 30)
    } catch (e: RuntimeException) {
        null
    }
    private var lastActivity = 0L

    /** Soft tick while tools run, at most once every few seconds. */
    fun activity() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActivity < ACTIVITY_INTERVAL_MS) return
        lastActivity = now
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
    }

    /** Distinct tone when the agent finishes its turn. */
    fun idle() {
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, 180)
    }

    fun release() {
        tones?.release()
    }

    private companion object {
        const val ACTIVITY_INTERVAL_MS = 3000L
    }
}
