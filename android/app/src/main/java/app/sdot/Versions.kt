package app.sdot

/**
 * Release version handling for in-app updates. Versions are `MAJOR[.MINOR[.PATCH]]` with an
 * optional leading `v`; missing parts count as 0. Anything after the first `-` or `+`
 * (pre-release or build metadata) is ignored, so `1.2.0-beta` equals `1.2.0`: the updater only
 * looks at GitHub's latest stable release, and the version code cannot express pre-releases.
 * MINOR and PATCH must be 0..99 so [code] stays monotonic; it mirrors the Gradle formula.
 */
object Versions {
    private const val MAX_MAJOR = 20_000
    private const val MAX_PART = 99

    fun parse(version: String): IntArray? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size > 3) return null
        val numbers = IntArray(3)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 5 || !part.all { it in '0'..'9' }) return null
            val n = part.toInt()
            if (n > (if (index == 0) MAX_MAJOR else MAX_PART)) return null
            numbers[index] = n
        }
        return numbers
    }

    /** Negative, zero or positive as [a] is older than, equal to or newer than [b]. */
    fun compare(a: String, b: String): Int {
        val left = parse(a) ?: throw IllegalArgumentException("bad version")
        val right = parse(b) ?: throw IllegalArgumentException("bad version")
        for (i in 0 until 3) {
            if (left[i] != right[i]) return left[i].compareTo(right[i])
        }
        return 0
    }

    /** Android versionCode for [version]: major * 10000 + minor * 100 + patch. */
    fun code(version: String): Int {
        val v = parse(version) ?: throw IllegalArgumentException("bad version")
        return v[0] * 10_000 + v[1] * 100 + v[2]
    }

    /** Version without prefix or suffix, for display: `v1.2-rc` becomes `1.2.0`. */
    fun normalize(version: String): String {
        val v = parse(version) ?: throw IllegalArgumentException("bad version")
        return v.joinToString(".")
    }
}
