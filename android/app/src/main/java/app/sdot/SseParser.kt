package app.sdot

data class SseEvent(val type: String, val data: String)

/**
 * Incremental Server-Sent Events parser. Feed decoded text as it arrives; complete events
 * are returned once their terminating blank line has been seen.
 */
class SseParser {
    /** Last valid `retry:` value sent by the server, in milliseconds. */
    var retryMs: Long? = null
        private set

    private val line = StringBuilder()
    private val data = StringBuilder()
    private var type = ""
    private var hasData = false
    private var afterCr = false

    fun feed(chunk: CharSequence): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        for (c in chunk) {
            if (afterCr) {
                afterCr = false
                if (c == '\n') continue
            }
            when (c) {
                '\r' -> {
                    afterCr = true
                    endLine(events)
                }
                '\n' -> endLine(events)
                else -> line.append(c)
            }
        }
        return events
    }

    fun reset() {
        line.setLength(0)
        clearEvent()
        afterCr = false
    }

    private fun endLine(events: MutableList<SseEvent>) {
        val text = line.toString()
        line.setLength(0)
        if (text.isEmpty()) {
            if (hasData) events.add(SseEvent(type.ifEmpty { "message" }, data.toString()))
            clearEvent()
            return
        }
        if (text.startsWith(":")) return
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        var value = if (colon < 0) "" else text.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> type = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) retryMs = value.toLongOrNull()
        }
    }

    private fun clearEvent() {
        data.setLength(0)
        type = ""
        hasData = false
    }
}
