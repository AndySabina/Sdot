package app.sdot

/** Turns the pi session name from the `hello` event into the label shown in the call circle. */
object SessionLabel {
    const val MAX_LENGTH = 40
    const val FALLBACK = "pi"

    private val whitespace = Regex("\\s+")

    fun normalize(raw: String?): String {
        val clean = raw?.trim()?.replace(whitespace, " ").orEmpty()
        if (clean.isEmpty()) return FALLBACK
        if (clean.length <= MAX_LENGTH) return clean
        return clean.take(MAX_LENGTH - 1).trimEnd() + "…"
    }
}
