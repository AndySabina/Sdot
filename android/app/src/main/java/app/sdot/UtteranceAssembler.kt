package app.sdot

/**
 * Joins recognizer segments that arrive close together into one utterance, so a sentence with
 * short pauses becomes one message. Keeps the latest partial hypothesis so speech is not dropped
 * when a session ends with an error or an empty result.
 */
class UtteranceAssembler(private val gapMs: Long = 1200, private val clock: () -> Long) {
    private val segments = ArrayList<String>()
    private var partial = ""
    private var lastActivity = 0L

    /** Latest in-progress hypothesis for the current segment; also counts as ongoing speech. */
    fun partial(text: String?) {
        val clean = text?.trim().orEmpty()
        if (clean.isNotEmpty()) partial = clean
        activity()
    }

    /** The user is still talking (speech detected); delays delivery. */
    fun activity() {
        lastActivity = clock()
    }

    /** A final segment; a blank one falls back to the latest partial. */
    fun segment(text: String?) {
        val clean = text?.trim().orEmpty().ifEmpty { partial }
        partial = ""
        if (clean.isNotEmpty()) segments.add(clean)
        activity()
    }

    /** The session ended without a final result; keeps what was partially heard. */
    fun endWithoutResult() {
        if (partial.isNotEmpty()) segment(partial)
    }

    fun hasPending(): Boolean = segments.isNotEmpty()

    fun msUntilDue(): Long = (lastActivity + gapMs - clock()).coerceAtLeast(0)

    /** The merged utterance once the user has been silent for the gap, else null. */
    fun takeIfDue(): String? = if (hasPending() && msUntilDue() == 0L) take() else null

    /** Everything heard so far, including the partial, regardless of timing. */
    fun flush(): String? {
        endWithoutResult()
        return if (hasPending()) take() else null
    }

    fun clear() {
        segments.clear()
        partial = ""
    }

    private fun take(): String {
        val text = segments.joinToString(" ")
        clear()
        return text
    }
}
