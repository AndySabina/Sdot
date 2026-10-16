package app.sdot

/** Exponential reconnect delay: initial, doubled each attempt, capped at max. */
class Backoff(private val initialMs: Long = 1000, private val maxMs: Long = 15000) {
    private var current = initialMs

    fun next(): Long {
        val delay = current
        current = (current * 2).coerceAtMost(maxMs)
        return delay
    }

    fun reset() {
        current = initialMs
    }
}
