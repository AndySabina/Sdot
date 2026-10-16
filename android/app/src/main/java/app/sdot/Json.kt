package app.sdot

/**
 * Minimal JSON reader/writer for the small payloads exchanged with the extension.
 * Kept in pure Kotlin so it can be unit-tested on the JVM without Android stubs.
 * Numbers parse to Double, arrays to List, objects to Map. Errors throw IllegalArgumentException.
 */
object Json {
    fun parseObject(text: String): Map<String, Any?> {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        require(reader.atEnd()) { "trailing characters" }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw IllegalArgumentException("expected an object")
    }

    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            skipWhitespace()
            require(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipWhitespace()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "expected key" }
                val key = string()
                skipWhitespace()
                expect(':')
                map[key] = value()
                skipWhitespace()
                if (peek() == ',') i++ else {
                    expect('}')
                    return map
                }
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipWhitespace()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                list.add(value())
                skipWhitespace()
                if (peek() == ',') i++ else {
                    expect(']')
                    return list
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad unicode escape" }
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
        }

        private fun literal(word: String, result: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal" }
            i += word.length
            return result
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun expect(c: Char) {
            require(peek() == c) { "expected $c" }
            i++
        }
    }
}
