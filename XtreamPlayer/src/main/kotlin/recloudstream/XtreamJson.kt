package recloudstream

import java.io.Reader

/*
 * Small JSON readers for the Xtream plugin. Pure Kotlin, no dependencies, so they can be tested on a plain JVM.
 *
 *  - parseTree: builds a tree for small responses. Objects become Map<String, Any?>, arrays List<Any?> and every
 *    scalar (string, number, boolean) becomes a String, so "7.5" stays "7.5" and an id never turns into "12.0".
 *    JSON null stays null.
 *  - forEachTopLevel: streams the big lists (tens of thousands of channels/movies) keeping only the wanted keys of
 *    each object, so memory stays flat no matter how large the catalogue is.
 *
 * Both throw IllegalArgumentException on anything that is not JSON (for example an HTML error page).
 */
internal object XtreamJson {

    fun parseTree(text: String): Any? = TreeReader(text).parse()

    /**
     * Reads the top level array (or object of objects) of [reader]. [onObject] gets, for every object found, the key it
     * had inside the top level object (null for array items) and the values of the [wanted] keys as strings.
     * For an array value only its first scalar is kept (e.g. `backdrop_path`); nested objects are skipped.
     */
    fun forEachTopLevel(
        reader: Reader,
        wanted: Set<String>,
        onObject: (key: String?, fields: Map<String, String>) -> Unit,
    ) {
        StreamReader(reader).forEachTopLevel(wanted, onObject)
    }
}

private val NUMBER = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")

private fun isScalarToken(raw: String): Boolean {
    return raw == "null" || raw == "true" || raw == "false" || NUMBER.matches(raw)
}

// -------------------------------------------------------------------------------------------------
// Tree reader (small responses)
// -------------------------------------------------------------------------------------------------

private class TreeReader(private val text: String) {
    private var pos = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = readValue(0)
        skipWhitespace()
        require(pos == text.length) { "Unexpected data after the JSON value" }
        return value
    }

    private fun readValue(depth: Int): Any? {
        require(depth <= MAX_DEPTH) { "JSON nested too deeply" }
        skipWhitespace()
        require(pos < text.length) { "Unexpected end of JSON" }
        return when (text[pos]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> readString()
            else -> readScalar()
        }
    }

    private fun readObject(depth: Int): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        pos++ // {
        while (true) {
            skipWhitespace()
            when (peek()) {
                '}' -> {
                    pos++
                    return result
                }
                ',' -> pos++
                '"' -> {
                    val key = readString()
                    skipWhitespace()
                    require(peek() == ':') { "Expected ':'" }
                    pos++
                    result[key] = readValue(depth + 1)
                }
                else -> throw IllegalArgumentException("Unexpected character in object")
            }
        }
    }

    private fun readArray(depth: Int): List<Any?> {
        val result = ArrayList<Any?>()
        pos++ // [
        while (true) {
            skipWhitespace()
            when (peek()) {
                ']' -> {
                    pos++
                    return result
                }
                ',' -> pos++
                '\u0000' -> throw IllegalArgumentException("Unexpected end of JSON")
                else -> result.add(readValue(depth + 1))
            }
        }
    }

    private fun readString(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            require(pos < text.length) { "Unterminated string" }
            val c = text[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    require(pos < text.length) { "Unterminated escape" }
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(pos + 4 <= text.length) { "Bad \\u escape" }
                            sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape \\$e")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readScalar(): String? {
        val start = pos
        while (pos < text.length && text[pos] !in DELIMITERS) {
            pos++
            require(pos - start <= 64) { "Unexpected token" }
        }
        require(pos > start) { "Unexpected token" }
        val raw = text.substring(start, pos)
        require(isScalarToken(raw)) { "Unexpected token" }
        return if (raw == "null") null else raw
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun skipWhitespace() {
        while (pos < text.length && (text[pos].isWhitespace() || text[pos] == '\uFEFF')) pos++
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val DELIMITERS = ",}] \t\r\n"
    }
}

// -------------------------------------------------------------------------------------------------
// Streaming reader (big lists)
// -------------------------------------------------------------------------------------------------

private class StreamReader(private val reader: Reader) {
    private val buffer = CharArray(16 * 1024)
    private var limit = 0
    private var index = 0

    private fun peek(): Int {
        if (index >= limit) {
            val n = reader.read(buffer, 0, buffer.size)
            if (n <= 0) return -1
            limit = n
            index = 0
        }
        return buffer[index].code
    }

    private fun next(): Int {
        val c = peek()
        if (c >= 0) index++
        return c
    }

    private fun skipWhitespace() {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0xFEFF) index++ else return
        }
    }

    private fun expect(ch: Char) {
        skipWhitespace()
        require(next() == ch.code) { "Expected '$ch'" }
    }

    fun forEachTopLevel(wanted: Set<String>, onObject: (String?, Map<String, String>) -> Unit) {
        skipWhitespace()
        when (peek()) {
            -1 -> throw IllegalArgumentException("Empty response")
            '['.code -> {
                next()
                while (true) {
                    skipWhitespace()
                    when (peek()) {
                        ']'.code -> {
                            next()
                            return
                        }
                        ','.code -> next()
                        '{'.code -> onObject(null, readObject(wanted))
                        -1 -> throw IllegalArgumentException("Unexpected end of JSON")
                        else -> skipValue()
                    }
                }
            }
            '{'.code -> {
                next()
                while (true) {
                    skipWhitespace()
                    when (peek()) {
                        '}'.code -> {
                            next()
                            return
                        }
                        ','.code -> next()
                        '"'.code -> {
                            val key = readString()
                            expect(':')
                            skipWhitespace()
                            if (peek() == '{'.code) onObject(key, readObject(wanted)) else skipValue()
                        }
                        else -> throw IllegalArgumentException("Unexpected character in object")
                    }
                }
            }
            'n'.code, 'f'.code -> {
                readScalar() // null / false: the server has nothing to list
                return
            }
            else -> throw IllegalArgumentException("The response is not JSON")
        }
    }

    private fun readObject(wanted: Set<String>): Map<String, String> {
        expect('{')
        val result = HashMap<String, String>(16)
        while (true) {
            skipWhitespace()
            when (peek()) {
                '}'.code -> {
                    next()
                    return result
                }
                ','.code -> next()
                '"'.code -> {
                    val key = readString()
                    expect(':')
                    if (key in wanted) readWantedValue()?.let { result[key] = it } else skipValue()
                }
                else -> throw IllegalArgumentException("Unexpected character in object")
            }
        }
    }

    private fun readWantedValue(): String? {
        skipWhitespace()
        return when (peek()) {
            '"'.code -> readString()
            '['.code -> firstScalarOfArray()
            '{'.code -> {
                skipValue()
                null
            }
            else -> readScalar()
        }
    }

    private fun firstScalarOfArray(): String? {
        next() // [
        var result: String? = null
        var first = true
        while (true) {
            skipWhitespace()
            val c = peek()
            when {
                c < 0 -> throw IllegalArgumentException("Unexpected end of JSON")
                c == ']'.code -> {
                    next()
                    return result
                }
                c == ','.code -> next()
                first -> {
                    first = false
                    result = when (c) {
                        '"'.code -> readString()
                        '{'.code, '['.code -> {
                            skipValue()
                            null
                        }
                        else -> readScalar()
                    }
                }
                else -> skipValue()
            }
        }
    }

    private fun readString(): String {
        require(next() == '"'.code) { "Expected a string" }
        val sb = StringBuilder()
        while (true) {
            val c = next()
            require(c >= 0) { "Unterminated string" }
            when (c) {
                '"'.code -> return sb.toString()
                '\\'.code -> {
                    val e = next()
                    require(e >= 0) { "Unterminated escape" }
                    when (e.toChar()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            var code = 0
                            repeat(4) {
                                val digit = Character.digit(next(), 16)
                                require(digit >= 0) { "Bad \\u escape" }
                                code = code * 16 + digit
                            }
                            sb.append(code.toChar())
                        }
                        else -> throw IllegalArgumentException("Bad escape")
                    }
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun skipStringBody() {
        while (true) {
            val c = next()
            require(c >= 0) { "Unterminated string" }
            if (c == '"'.code) return
            if (c == '\\'.code) require(next() >= 0) { "Unterminated escape" }
        }
    }

    private fun skipValue() {
        skipWhitespace()
        when (peek()) {
            -1 -> throw IllegalArgumentException("Unexpected end of JSON")
            '"'.code -> {
                next()
                skipStringBody()
            }
            '{'.code, '['.code -> {
                var depth = 0
                do {
                    val c = next()
                    require(c >= 0) { "Unexpected end of JSON" }
                    when (c) {
                        '"'.code -> skipStringBody()
                        '{'.code, '['.code -> depth++
                        '}'.code, ']'.code -> depth--
                    }
                } while (depth > 0)
            }
            else -> readScalar()
        }
    }

    private fun readScalar(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code ||
                c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code
            ) break
            sb.append(c.toChar())
            index++
            require(sb.length <= 64) { "Unexpected token" }
        }
        require(sb.isNotEmpty()) { "Unexpected token" }
        val raw = sb.toString()
        require(isScalarToken(raw)) { "Unexpected token" }
        return if (raw == "null") null else raw
    }
}
