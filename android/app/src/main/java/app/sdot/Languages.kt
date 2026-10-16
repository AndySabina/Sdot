package app.sdot

object Languages {
    /** Picks the candidate matching the device tag exactly, else the first with the same language. */
    fun match(deviceTag: String, candidates: List<String>): String? {
        candidates.firstOrNull { it.equals(deviceTag, ignoreCase = true) }?.let { return it }
        val language = deviceTag.substringBefore('-').lowercase()
        return candidates.firstOrNull { it.substringBefore('-').lowercase() == language }
    }
}
