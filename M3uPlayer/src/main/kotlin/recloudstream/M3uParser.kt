package recloudstream

import java.net.URI
import java.net.URLDecoder

/*
 * Pure-Kotlin M3U / M3U8 parsing.
 *
 * Nothing in this file depends on Android or on CloudStream, so it can be unit-tested
 * on a plain JVM (see README.md).
 */

/** One playable entry (a channel or a VOD item) found in an M3U playlist. */
data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    /** HTTP headers requested by the playlist (User-Agent, Referer, Cookie, ...). */
    val headers: Map<String, String> = emptyMap(),
)

/** One line of the settings box: a display name plus where the playlist is read from. */
data class PlaylistSource(val name: String, val source: String)

/** Season / episode information taken from a title such as `Show Name S01E02 - Episode title`. */
data class EpisodeInfo(
    val series: String,
    val season: Int,
    val episode: Int,
    /** Text after the season/episode marker, when there is any (usually the episode title). */
    val title: String?,
)

object M3uParser {

    private const val BOM = "\uFEFF"

    // key="value" | key='value' | key=value
    private val ATTRIBUTE = Regex("""([^\s=,"']+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"',]+))""")

    // "key": "value" (flat JSON object used by #EXTHTTP)
    private val JSON_PAIR = Regex(""""([^"\\]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    private val VOD_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".webm", ".m4v")

    // ---------------------------------------------------------------------------------------
    // Detection helpers
    // ---------------------------------------------------------------------------------------

    /** True if [text] looks like an M3U playlist (as opposed to an HTML error page, JSON, ...). */
    fun looksLikeM3u(text: String): Boolean {
        val head = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return head.startsWith("#EXTM3U", ignoreCase = true) ||
                head.contains("#EXTINF", ignoreCase = true)
    }

    /**
     * True if [text] is an HLS manifest (a single stream, master or media playlist) rather
     * than a list of channels. IPTV playlists never contain these tags.
     */
    fun isHlsManifest(text: String): Boolean {
        return text.contains("#EXT-X-STREAM-INF") ||
                text.contains("#EXT-X-TARGETDURATION") ||
                text.contains("#EXT-X-MEDIA-SEQUENCE")
    }

    /** True if the URL points to a file that is usually a movie/episode rather than a live channel. */
    fun isVodUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return VOD_EXTENSIONS.any { path.endsWith(it) }
    }

    // ---------------------------------------------------------------------------------------
    // TV shows
    // ---------------------------------------------------------------------------------------

    private val EPISODE_PATTERNS = listOf(
        // S01E02 · S1 E2 · S01.E02 · S01-E02 · S01EP02 · T01E02 (T = "temporada")
        Regex("""(?<![A-Za-z0-9])[ST](\d{1,3})\s*[.\-_ ]?\s*EP?(\d{1,4})(?!\d)""", RegexOption.IGNORE_CASE),
        // 1x02 · 01x02
        Regex("""(?<![A-Za-z0-9])(\d{1,2})x(\d{1,4})(?![0-9A-Za-z])""", RegexOption.IGNORE_CASE),
        // Temporada 1 Episodio 2 · Season 1 Episode 2 · Temp. 1 Cap. 2
        Regex(
            """(?<![A-Za-z])(?:temporada|temp|season)\.?\s*(\d{1,3})\D{0,8}?(?:episodio|episode|ep|cap[ií]tulo|cap)\.?\s*(\d{1,4})(?!\d)""",
            RegexOption.IGNORE_CASE
        ),
    )

    private val TITLE_SEPARATORS = charArrayOf(' ', '\t', '-', '\u2013', '\u2014', ':', '|', '.', '_', ',')

    /** Season / episode info parsed from a title like `Show Name S01E02 - Title`, or null if there is none. */
    fun parseEpisodeTitle(title: String): EpisodeInfo? {
        for (pattern in EPISODE_PATTERNS) {
            val match = pattern.find(title) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull() ?: continue

            val series = title.substring(0, match.range.first).trimEnd(*TITLE_SEPARATORS).trim()
            if (series.isEmpty()) continue

            var rest = title.substring(match.range.last + 1).trimStart(*TITLE_SEPARATORS).trim()
            // "[1080p]" or "(HD)" after the marker is a quality tag, not an episode title.
            if ((rest.startsWith("[") && rest.endsWith("]")) || (rest.startsWith("(") && rest.endsWith(")"))) {
                rest = ""
            }
            return EpisodeInfo(series, season, episode, rest.ifEmpty { null })
        }
        return null
    }

    /**
     * Returns the episode info if [entry] is an episode of a TV show: its title carries a season and
     * episode, and it is a video file, lives under `/series/` or sits in a group called "series".
     */
    fun episodeInfo(entry: M3uEntry): EpisodeInfo? {
        val looksLikeVod = isVodUrl(entry.url) ||
                entry.url.contains("/series/", ignoreCase = true) ||
                entry.group?.contains("serie", ignoreCase = true) == true
        if (!looksLikeVod) return null
        return parseEpisodeTitle(entry.name)
    }

    /** Case and spacing insensitive key used to group the episodes of one show. */
    fun seriesKey(series: String): String {
        return series.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    // ---------------------------------------------------------------------------------------
    // Playlist parsing
    // ---------------------------------------------------------------------------------------

    /**
     * Parses the text of an M3U playlist.
     *
     * Understands `#EXTINF` attributes (tvg-id, tvg-name, tvg-logo, group-title), `#EXTGRP`,
     * `#EXTVLCOPT` (user-agent / referrer), `#EXTHTTP` (JSON headers), `#KODIPROP` stream headers
     * and the Kodi style `url|User-Agent=...&Referer=...` suffix.
     *
     * @param baseUrl where the playlist was downloaded from, used to resolve relative entries.
     */
    fun parse(text: String, baseUrl: String? = null): List<M3uEntry> {
        val result = ArrayList<M3uEntry>()

        var info: ExtInf? = null
        var extGroup: String? = null
        val options = LinkedHashMap<String, String>()

        for (rawLine in text.removePrefix(BOM).lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> info = parseExtInf(line)

                    line.startsWith("#EXTGRP:", ignoreCase = true) ->
                        extGroup = line.substringAfter(':').trim().ifEmpty { null }

                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val option = line.substringAfter(':')
                        val eq = option.indexOf('=')
                        if (eq > 0) {
                            val key = option.substring(0, eq).trim().lowercase()
                            val value = option.substring(eq + 1).trim()
                            val header = when (key) {
                                "http-user-agent" -> "User-Agent"
                                "http-referrer", "http-referer" -> "Referer"
                                "http-origin" -> "Origin"
                                "http-cookie" -> "Cookie"
                                else -> null
                            }
                            if (header != null && value.isNotEmpty()) options[header] = value
                        }
                    }

                    line.startsWith("#EXTHTTP:", ignoreCase = true) ->
                        for (match in JSON_PAIR.findAll(line.substringAfter(':'))) {
                            val key = canonicalHeader(match.groupValues[1])
                            val value = unescapeJson(match.groupValues[2])
                            if (key.isNotEmpty() && value.isNotEmpty()) options[key] = value
                        }

                    line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                        val prop = line.substringAfter(':')
                        val eq = prop.indexOf('=')
                        if (eq > 0) {
                            val key = prop.substring(0, eq).trim().lowercase()
                            if (key.endsWith("stream_headers") || key.endsWith("manifest_headers")) {
                                options.putAll(parseHeaderString(prop.substring(eq + 1)))
                            }
                        }
                    }
                    // Any other "#..." line (#EXTM3U, #PLAYLIST, comments) is ignored.
                }
                continue
            }

            // Anything else is the URL of an entry.
            val (rawUrl, urlHeaders) = splitUrlHeaders(line)
            val url = resolve(rawUrl, baseUrl)
            if (url.isNotEmpty()) {
                val attrs = info?.attrs.orEmpty()
                val headers = LinkedHashMap<String, String>()
                headers.putAll(options)
                headers.putAll(urlHeaders)

                val name = info?.title?.takeIf { it.isNotBlank() }
                    ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                    ?: displayNameFromUrl(url)

                result.add(
                    M3uEntry(
                        name = name,
                        url = url,
                        logo = (attrs["tvg-logo"] ?: attrs["logo"] ?: attrs["tvg_logo"])
                            ?.takeIf { it.isNotBlank() },
                        group = (attrs["group-title"] ?: extGroup)?.trim()?.takeIf { it.isNotEmpty() },
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        headers = headers,
                    )
                )
            }

            // Everything collected so far belonged to this entry only.
            info = null
            extGroup = null
            options.clear()
        }
        return result
    }

    private class ExtInf(val title: String, val attrs: Map<String, String>)

    /** `#EXTINF:-1 tvg-id="x" group-title="News",Channel name` */
    private fun parseExtInf(line: String): ExtInf {
        val body = line.substringAfter(':', "")

        // The display name starts after the first comma that is not inside quotes.
        var commaIndex = -1
        var quote = '\u0000'
        for (i in body.indices) {
            val c = body[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || (c == '\'' && i > 0 && body[i - 1] == '=')) {
                quote = c
            } else if (c == ',') {
                commaIndex = i
                break
            }
        }

        val attributePart = if (commaIndex >= 0) body.substring(0, commaIndex) else body
        val title = if (commaIndex >= 0) body.substring(commaIndex + 1).trim() else ""

        val attrs = HashMap<String, String>()
        for (match in ATTRIBUTE.findAll(attributePart)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
                .ifEmpty { match.groupValues[3] }
                .ifEmpty { match.groupValues[4] }
                .trim()
            if (!attrs.containsKey(key)) attrs[key] = value
        }
        return ExtInf(title, attrs)
    }

    // ---------------------------------------------------------------------------------------
    // URLs and headers
    // ---------------------------------------------------------------------------------------

    /** Splits Kodi style `http://host/stream.m3u8|User-Agent=VLC&Referer=http://site` into url + headers. */
    fun splitUrlHeaders(raw: String): Pair<String, Map<String, String>> {
        val idx = raw.indexOf('|')
        if (idx <= 0) return raw.trim() to emptyMap()
        val tail = raw.substring(idx + 1)
        if (!tail.contains('=')) return raw.trim() to emptyMap()
        return raw.substring(0, idx).trim() to parseHeaderString(tail)
    }

    /** `Header=Value&Header2=Value2` (values may be percent-encoded). */
    private fun parseHeaderString(tail: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        for (pair in tail.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = canonicalHeader(percentDecode(pair.substring(0, eq)).trim())
            val value = percentDecode(pair.substring(eq + 1)).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) headers[key] = value
        }
        return headers
    }

    private fun canonicalHeader(name: String): String {
        return when (name.trim().lowercase()) {
            "user-agent", "useragent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            "origin" -> "Origin"
            "cookie" -> "Cookie"
            "authorization" -> "Authorization"
            else -> name.trim()
        }
    }

    /** Decodes %XX sequences only; a literal '+' is kept as '+'. */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    private fun unescapeJson(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun resolve(url: String, baseUrl: String?): String {
        if (baseUrl == null || url.isEmpty() || url.contains("://")) return url
        return try {
            URI(baseUrl).resolve(url).toString()
        } catch (_: Exception) {
            url
        }
    }

    /** A readable name taken from the last segment of the URL. */
    fun displayNameFromUrl(url: String): String {
        val segment = url.substringBefore('?').substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            segment
        }
        return decoded.ifBlank { "Stream" }
    }

    // ---------------------------------------------------------------------------------------
    // Settings box: list of playlist sources
    // ---------------------------------------------------------------------------------------

    private fun looksLikeSource(text: String): Boolean {
        return text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true) ||
                text.startsWith("file://", ignoreCase = true) ||
                text.startsWith("/")
    }

    /**
     * Parses the text typed in the settings dialog. One playlist per line:
     *
     *     https://example.com/list.m3u
     *     My list | https://example.com/other.m3u8
     *     # lines starting with # are ignored
     */
    fun parseSources(text: String): List<PlaylistSource> {
        val parsed = ArrayList<Pair<String?, String>>()
        for (rawLine in text.removePrefix(BOM).lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val name: String?
            val source: String
            if (looksLikeSource(line)) {
                name = null
                source = line
            } else {
                val idx = line.indexOf('|')
                if (idx <= 0) continue
                name = line.substring(0, idx).trim().ifEmpty { null }
                source = line.substring(idx + 1).trim()
            }
            if (!looksLikeSource(source)) continue
            if (parsed.any { it.second == source }) continue
            parsed.add(name to source)
        }

        // Give every playlist a name and make the names unique.
        val used = HashMap<String, Int>()
        return parsed.mapIndexed { index, (name, source) ->
            val base = name ?: defaultSourceName(source, index + 1)
            val count = (used[base] ?: 0) + 1
            used[base] = count
            PlaylistSource(if (count == 1) base else "$base ($count)", source)
        }
    }

    private fun defaultSourceName(source: String, index: Int): String {
        val clean = source.substringBefore('|')
        val host = try {
            URI(clean).host
        } catch (_: Exception) {
            null
        }
        if (!host.isNullOrBlank()) return host
        val file = displayNameFromUrl(clean)
        return if (file == "Stream") "Playlist $index" else file
    }
}
