package app.sdot

/** Formats call duration like a phone dialer: mm:ss, then h:mm:ss from one hour. */
object CallTimer {
    fun format(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val secs = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
    }

    fun elapsedSeconds(startedAtMs: Long, nowMs: Long): Long = ((nowMs - startedAtMs) / 1000).coerceAtLeast(0)
}
