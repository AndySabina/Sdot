package app.sdot

import android.os.Handler
import android.os.Looper

enum class CallStatus { IDLE, CONNECTING, LISTENING, WORKING, SPEAKING, RECONNECTING }

data class CallSnapshot(
    val inCall: Boolean = false,
    val status: CallStatus = CallStatus.IDLE,
    val notice: String? = null,
    val transcript: List<String> = emptyList(),
)

/** Main-thread registry that carries call state from [CallService] to the UI. */
object CallHub {
    private const val MAX_LINES = 50

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(CallSnapshot) -> Unit>()

    var snapshot = CallSnapshot()
        private set

    fun addListener(listener: (CallSnapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun removeListener(listener: (CallSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun update(transform: (CallSnapshot) -> CallSnapshot) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { update(transform) }
            return
        }
        val next = transform(snapshot)
        if (next == snapshot) return
        snapshot = next
        for (listener in listeners.toList()) listener(next)
    }

    fun addLine(line: String) = update { it.copy(transcript = (it.transcript + line).takeLast(MAX_LINES)) }
}
