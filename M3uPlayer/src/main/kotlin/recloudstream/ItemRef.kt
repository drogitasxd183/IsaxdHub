package recloudstream

/*
 * ItemRef: everything needed to show and play one entry.
 *
 * It is stored, as a small JSON string, in the `url` / `data` strings that CloudStream passes around,
 * so favourites, history and "continue watching" keep working after the app restarts or the playlist
 * changes. The JSON is written and read by the tiny codec below instead of a reflection based library:
 * it has no dependencies, nothing that can break with Kotlin metadata, and it is tested on a plain JVM.
 */

internal const val KIND_LIVE = "live"
internal const val KIND_VOD = "vod"
internal const val KIND_SERIES = "series"
internal const val KIND_INFO = "info"

data class ItemRef(
    val name: String = "",
    val url: String = "",
    val logo: String? = null,
    val group: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val kind: String = KIND_LIVE,
    val info: String? = null,
    /** Playlist the entry comes from (used to find all episodes of a series). */
    val source: String? = null,
) {
    /**
     * Compact JSON. It always starts with `{"name":`, which CloudStream's `fixUrl` leaves untouched
     * (it never prefixes strings that begin with `{"`).
     */
    fun encode(): String {
        val sb = StringBuilder(64 + name.length + url.length)
        sb.append("{\"name\":")
        appendJsonString(sb, name)
        if (url.isNotEmpty()) field(sb, "url", url)
        if (logo != null) field(sb, "logo", logo)
        if (group != null) field(sb, "group", group)
        if (headers.isNotEmpty()) {
            sb.append(",\"headers\":{")
            var first = true
            for ((key, value) in headers) {
                if (!first) sb.append(',')
                first = false
                appendJsonString(sb, key)
                sb.append(':')
                appendJsonString(sb, value)
            }
            sb.append('}')
        }
        field(sb, "kind", kind)
        if (info != null) field(sb, "info", info)
        if (source != null) field(sb, "source", source)
        sb.append('}')
        return sb.toString()
    }

    companion object {
        /** Parses the output of [encode]; returns null for anything that is not a valid ItemRef. */
        fun decode(text: String?): ItemRef? {
            if (text.isNullOrBlank()) return null
            return try {
                val map = MiniJson(text).parse() as? Map<*, *> ?: return null
                val headers = LinkedHashMap<String, String>()
                (map["headers"] as? Map<*, *>)?.forEach { (key, value) ->
                    if (key is String && value is String) headers[key] = value
                }
                ItemRef(
                    name = map["name"] as? String ?: "",
                    url = map["url"] as? String ?: "",
                    logo = map["logo"] as? String,
                    group = map["group"] as? String,
                    headers = headers,
                    kind = map["kind"] as? String ?: KIND_LIVE,
                    info = map["info"] as? String,
                    source = map["source"] as? String,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun field(sb: StringBuilder, key: String, value: String) {
    sb.append(",\"").append(key).append("\":")
    appendJsonString(sb, value)
}

private fun appendJsonString(sb: StringBuilder, value: String) {
    sb.append('"')
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
}

/** Minimal JSON reader: objects, arrays, strings, numbers, booleans and null. Throws on malformed input. */
private class MiniJson(private val text: String) {
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
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> readNumber()
        }
    }

    private fun readObject(depth: Int): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        pos++ // {
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            skipWhitespace()
            require(peek() == '"') { "Expected a string key" }
            val key = readString()
            skipWhitespace()
            require(peek() == ':') { "Expected ':'" }
            pos++
            result[key] = readValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return result
                }
                else -> throw IllegalArgumentException("Expected ',' or '}'")
            }
        }
    }

    private fun readArray(depth: Int): List<Any?> {
        val result = ArrayList<Any?>()
        pos++ // [
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return result
        }
        while (true) {
            result.add(readValue(depth + 1))
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return result
                }
                else -> throw IllegalArgumentException("Expected ',' or ']'")
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

    private fun readNumber(): Double {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        require(pos > start) { "Unexpected character '${text[start]}'" }
        return text.substring(start, pos).toDouble()
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, pos)) { "Unexpected token" }
        pos += word.length
        return value
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}
