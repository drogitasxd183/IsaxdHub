package recloudstream

import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.roundToInt

/*
 * Core of the Xtream plugin. Pure Kotlin: nothing here depends on Android or CloudStream, so all of it is tested on a
 * plain JVM (tests/XtreamTest.kt).
 *
 * Xtream panels expose three separate catalogues (live channels, movies and series), which is why the plugin always
 * knows what each entry is, without guessing from names or URLs.
 */

/** The three catalogues of an Xtream panel. */
enum class XtreamSection(val code: String, val categoriesAction: String, val streamsAction: String) {
    LIVE("live", "get_live_categories", "get_live_streams"),
    VOD("vod", "get_vod_categories", "get_vod_streams"),
    SERIES("series", "get_series_categories", "get_series");

    companion object {
        fun fromCode(code: String?): XtreamSection? = entries.firstOrNull { it.code == code }
    }
}

// -------------------------------------------------------------------------------------------------
// Accounts
// -------------------------------------------------------------------------------------------------

data class XtreamAccount(
    val name: String,
    /** Server address without trailing slash, e.g. `http://host:8080`. */
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    /** Stable identity of the account (the password is deliberately not part of it). */
    val key: String get() = "$baseUrl|$username"

    fun apiUrl(action: String? = null, vararg params: Pair<String, String>): String {
        val sb = StringBuilder(baseUrl)
            .append("/player_api.php?username=").append(queryEncode(username))
            .append("&password=").append(queryEncode(password))
        if (action != null) sb.append("&action=").append(action)
        for ((key, value) in params) sb.append('&').append(key).append('=').append(queryEncode(value))
        return sb.toString()
    }

    fun liveUrl(streamId: String, extension: String): String = streamUrl("live", streamId, extension)
    fun movieUrl(streamId: String, extension: String): String = streamUrl("movie", streamId, extension)
    fun seriesUrl(episodeId: String, extension: String): String = streamUrl("series", episodeId, extension)

    private fun streamUrl(kind: String, id: String, extension: String): String {
        return "$baseUrl/$kind/${pathEncode(username)}/${pathEncode(password)}/${pathEncode(id)}.$extension"
    }
}

private fun queryEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
private fun pathEncode(value: String): String = queryEncode(value).replace("+", "%20")

private fun queryDecode(value: String): String {
    return try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}

object XtreamAccounts {
    private val MARKERS = listOf("/player_api.php", "/get.php", "/panel_api.php", "/xmltv.php", "/live/", "/movie/", "/series/", "/c/")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Parses the text of the settings box. One account per line, in any of these forms:
     *
     *     My provider | http://host:8080 | user | password
     *     http://host:8080 | user | password
     *     http://host:8080/get.php?username=USER&password=PASS&type=m3u_plus     (the M3U link of the provider)
     *     http://user:password@host:8080
     *     # lines starting with # are ignored
     */
    fun parse(text: String): List<XtreamAccount> {
        val result = ArrayList<XtreamAccount>()
        val used = HashMap<String, Int>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val account = parseLine(line) ?: continue
            if (result.any { it.key == account.key }) continue
            val count = (used[account.name] ?: 0) + 1
            used[account.name] = count
            result.add(if (count == 1) account else account.copy(name = "${account.name} ($count)"))
        }
        return result
    }

    private fun parseLine(line: String): XtreamAccount? {
        val parts = line.split('|').map { it.trim() }
        when {
            // "http://host | user | pa|ss": the first part is the server, the rest of the line is user and password
            parts.size >= 4 && hasScheme(parts[0]) -> return build(null, parts[0], parts[1], parts.drop(2).joinToString("|"))
            parts.size >= 4 -> return build(parts[0], parts[1], parts[2], parts.drop(3).joinToString("|"))
            parts.size == 3 -> return build(null, parts[0], parts[1], parts[2])
            parts.size == 2 -> return fromUrl(parts[1], parts[0].ifEmpty { null })
        }
        fromUrl(line, null)?.let { return it }
        val tokens = line.split(WHITESPACE)
        if (tokens.size == 3) return build(null, tokens[0], tokens[1], tokens[2])
        return null
    }

    private fun hasScheme(text: String): Boolean {
        return text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
    }

    private fun build(name: String?, urlText: String, user: String, pass: String): XtreamAccount? {
        if (user.isEmpty() || pass.isEmpty()) return null
        val base = baseUrl(urlText) ?: return null
        return XtreamAccount(name?.takeIf { it.isNotBlank() } ?: hostOf(base), base, user, pass)
    }

    /** Credentials taken from a provider link (`...?username=U&password=P` or `http://U:P@host`); null for a plain address. */
    fun fromLink(text: String): XtreamAccount? = fromUrl(text, null)

    /** Credentials taken from a provider link: `...?username=U&password=P` or `http://U:P@host`. */
    private fun fromUrl(urlText: String, name: String?): XtreamAccount? {
        var text = urlText.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        if (!text.contains("://")) text = "http://$text"

        val query = text.substringAfter('?', "").substringBefore('#')
        if (query.isNotEmpty()) {
            val params = HashMap<String, String>()
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) params[pair.substring(0, eq).lowercase()] = queryDecode(pair.substring(eq + 1))
            }
            val user = params["username"]
            val pass = params["password"]
            if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) return build(name, text, user, pass)
        }

        val afterScheme = text.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        if (authority.contains('@')) {
            val userInfo = authority.substringBeforeLast('@')
            val user = queryDecode(userInfo.substringBefore(':'))
            val pass = queryDecode(userInfo.substringAfter(':', ""))
            val rebuilt = text.substringBefore("://") + "://" + afterScheme.replaceFirst("$userInfo@", "")
            return build(name, rebuilt, user, pass)
        }
        return null
    }

    /** `http://host:8080/get.php?x=y` -> `http://host:8080`; adds `http://` when the scheme is missing. */
    fun baseUrl(text: String): String? {
        var url = text.trim()
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null
        if (!url.contains("://")) url = "http://$url"
        url = url.substringBefore('#').substringBefore('?')

        val lower = url.lowercase()
        val schemeEnd = lower.indexOf("://") + 3
        var cut = url.length
        for (marker in MARKERS) {
            val index = lower.indexOf(marker, schemeEnd)
            if (index in 0 until cut) cut = index
        }
        url = url.substring(0, cut).trimEnd('/')
        return if (hostOf(url).isBlank()) null else url
    }

    fun hostOf(url: String): String {
        val authority = url.substringAfter("://", url).substringBefore('/')
        return authority.substringAfterLast('@').substringBefore(':')
    }
}

// -------------------------------------------------------------------------------------------------
// Account forms: what the settings screen edits
// -------------------------------------------------------------------------------------------------

/**
 * One list as typed in the settings screen: three boxes. [name] is not shown anywhere: it only keeps the name given
 * to an account by the first version of the plugin (one account per text line), so nothing is lost when upgrading.
 */
data class XtreamAccountForm(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val name: String = "",
) {
    val isBlank: Boolean get() = server.isBlank() && username.isBlank() && password.isBlank()
}

/** The accounts that can be used, and how many filled-in forms were ignored for lacking a server, user or password. */
class XtreamConversion(val accounts: List<XtreamAccount>, val ignored: Int)

object XtreamAccountForms {

    /** Compact JSON array, written and read by our own code (no reflection involved). */
    fun encode(forms: List<XtreamAccountForm>): String {
        val sb = StringBuilder("[")
        forms.forEachIndexed { index, form ->
            if (index > 0) sb.append(',')
            sb.append("{\"server\":")
            appendString(sb, form.server)
            field(sb, "user", form.username)
            field(sb, "pass", form.password)
            if (form.name.isNotEmpty()) field(sb, "name", form.name)
            sb.append('}')
        }
        return sb.append(']').toString()
    }

    /** Reads what [encode] wrote, or the one-account-per-line text saved by the first version of the plugin. */
    fun decode(stored: String): List<XtreamAccountForm> {
        val text = stored.trim()
        if (text.startsWith("[")) {
            try {
                val list = XtreamJson.parseTree(text) as? List<*>
                if (list != null) {
                    return list.mapNotNull { entry ->
                        val map = entry as? Map<*, *> ?: return@mapNotNull null
                        XtreamAccountForm(
                            server = map["server"] as? String ?: "",
                            username = map["user"] as? String ?: "",
                            password = map["pass"] as? String ?: "",
                            name = map["name"] as? String ?: "",
                        )
                    }
                }
            } catch (_: Exception) {
                // not JSON (an old line such as "[ES] Provider | http://..."): read it as text below
            }
        }
        return XtreamAccounts.parse(stored).map { XtreamAccountForm(it.baseUrl, it.username, it.password, it.name) }
    }

    /**
     * Turns the forms into usable accounts. Spaces around the boxes are ignored, empty forms are skipped, forms with a
     * missing part are counted in [XtreamConversion.ignored], and the same server + user twice is kept once.
     *
     * A provider link pasted in the server box (`http://host:8080/get.php?username=U&password=P`) fills the user and
     * password boxes when they are empty, so people can paste the link they were given and be done.
     */
    fun convert(forms: List<XtreamAccountForm>): XtreamConversion {
        val accounts = ArrayList<XtreamAccount>()
        var ignored = 0
        for (form in forms) {
            if (form.isBlank) continue
            val account = toAccount(form)
            if (account == null) {
                ignored++
            } else if (accounts.none { it.key == account.key }) {
                accounts.add(account)
            }
        }
        return XtreamConversion(uniqueNames(accounts), ignored)
    }

    private fun toAccount(form: XtreamAccountForm): XtreamAccount? {
        val server = form.server.trim()
        var user = form.username.trim()
        var pass = form.password.trim()

        val fromLink = XtreamAccounts.fromLink(server)
        if (fromLink != null) {
            if (user.isEmpty()) user = fromLink.username
            if (pass.isEmpty()) pass = fromLink.password
        }
        if (user.isEmpty() || pass.isEmpty()) return null

        val base = fromLink?.baseUrl ?: XtreamAccounts.baseUrl(server) ?: return null
        return XtreamAccount(form.name.trim().ifEmpty { XtreamAccounts.hostOf(base) }, base, user, pass)
    }

    /** Two lists on the same host get "host (user)"; any name still repeated gets a number. */
    private fun uniqueNames(accounts: List<XtreamAccount>): List<XtreamAccount> {
        val counts = accounts.groupingBy { it.name }.eachCount()
        val used = HashSet<String>()
        return accounts.map { account ->
            val base = if ((counts[account.name] ?: 0) > 1) "${account.name} (${account.username})" else account.name
            var candidate = base
            var number = 2
            while (!used.add(candidate)) candidate = "$base (${number++})"
            if (candidate == account.name) account else account.copy(name = candidate)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Category filter
// -------------------------------------------------------------------------------------------------

internal fun normalizeText(text: String): String {
    return Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
}

/**
 * "LATINO, ES, -ADULTOS": keeps the categories whose name contains any plain word (if there is none, keeps all) and
 * drops the ones that contain a word starting with `-`. Ignores case and accents.
 */
class CategoryFilter(text: String) {
    private val include: List<String>
    private val exclude: List<String>

    init {
        val tokens = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        include = tokens.filter { !it.startsWith("-") }.map { normalizeText(it) }.filter { it.isNotEmpty() }
        exclude = tokens.filter { it.startsWith("-") }.map { normalizeText(it.drop(1)) }.filter { it.isNotEmpty() }
    }

    fun accepts(categoryName: String): Boolean {
        val name = normalizeText(categoryName)
        if (exclude.any { name.contains(it) }) return false
        return include.isEmpty() || include.any { name.contains(it) }
    }
}

// -------------------------------------------------------------------------------------------------
// Models
// -------------------------------------------------------------------------------------------------

data class XtreamCategory(val id: String, val name: String)

/** One entry of a catalogue (channel, movie or series). Details are requested only when it is opened. */
class XtreamItem(
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    /** Container of movies and episodes (mkv, mp4...). */
    val extension: String?,
    val rating: String?,
    /** Unix time the entry was added or last modified, 0 when unknown. Used to show the newest first. */
    val added: Long,
    val year: Int?,
)

data class XtreamUserInfo(
    val authenticated: Boolean,
    val status: String?,
    /** Unix time, null when the account never expires. */
    val expiry: Long?,
    val maxConnections: String?,
    val activeConnections: String?,
    val formats: List<String>,
    val message: String?,
) {
    /** True for statuses that mean the account cannot be used (expired, banned...). */
    val isBlocked: Boolean
        get() {
            val s = status?.lowercase() ?: return false
            return s.contains("expired") || s.contains("banned") || s.contains("disabled") || s.contains("inactive")
        }
}

class XtreamDetails(
    val name: String?,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val year: Int?,
    val genres: List<String>,
    val actors: List<String>,
    val director: String?,
    val rating: String?,
    val durationMinutes: Int?,
    val trailer: String?,
    val tmdbId: String?,
    val extension: String?,
)

class XtreamEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String?,
    val extension: String?,
    val poster: String?,
    val plot: String?,
    val durationMinutes: Int?,
)

class XtreamSeriesDetails(val details: XtreamDetails, val episodes: List<XtreamEpisode>)

// -------------------------------------------------------------------------------------------------
// Home page planning
// -------------------------------------------------------------------------------------------------

class XtreamRow(val categoryName: String, val items: List<XtreamItem>)

/** The rows to show, and how many categories matched the filter before the row limit was applied. */
class XtreamRowPlan(val rows: List<XtreamRow>, val matching: Int)

object XtreamRows {

    /**
     * One row per category, in the panel's order. Entries whose category is missing or unknown go to a last row called
     * [otherLabel]. Categories rejected by [filter] are dropped; at most [maxRows] rows of [maxItems] entries are kept.
     */
    fun plan(
        categories: List<XtreamCategory>,
        items: List<XtreamItem>,
        filter: CategoryFilter,
        otherLabel: String,
        maxRows: Int,
        maxItems: Int,
    ): XtreamRowPlan {
        val byCategory = items.groupBy { it.categoryId.orEmpty() }
        val known = categories.map { it.id }.toHashSet()

        val ordered = ArrayList<Pair<String, List<XtreamItem>>>()
        for (category in categories) {
            byCategory[category.id]?.let { ordered.add(category.name to it) }
        }
        val others = items.filter { it.categoryId == null || it.categoryId !in known }
        if (others.isNotEmpty()) ordered.add(otherLabel to others)

        val rows = ArrayList<XtreamRow>()
        var matching = 0
        for ((name, list) in ordered) {
            if (!filter.accepts(name)) continue
            matching++
            if (rows.size < maxRows) rows.add(XtreamRow(name, list.take(maxItems)))
        }
        return XtreamRowPlan(rows, matching)
    }
}

/** HLS when the panel allows it (unless TS is preferred), plain TS otherwise. */
fun chooseLiveExtension(preferTs: Boolean, allowedFormats: List<String>): String {
    return if (!preferTs && "m3u8" in allowedFormats) "m3u8" else "ts"
}

// -------------------------------------------------------------------------------------------------
// Turning API responses into models
// -------------------------------------------------------------------------------------------------

object XtreamParser {

    /** Keys read from the big lists (live, movies, series); everything else is skipped while streaming. */
    val LIST_KEYS = setOf(
        "stream_id", "series_id", "name", "stream_icon", "cover", "category_id", "container_extension",
        "rating", "added", "last_modified", "year", "releaseDate", "release_date",
        // only used to detect a rejected login (`{"user_info":{"auth":0}}`)
        "auth", "status",
    )

    private fun text(map: Map<*, *>?, key: String): String? {
        val value = map?.get(key) as? String ?: return null
        return value.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun firstOf(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

    /** `backdrop_path` is a list of URLs on most panels and a plain string on a few. */
    private fun firstText(value: Any?): String? {
        return when (value) {
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is List<*> -> value.firstNotNullOfOrNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            else -> null
        }
    }

    fun userInfo(tree: Any?): XtreamUserInfo {
        val root = tree as? Map<*, *>
        val info = root?.get("user_info") as? Map<*, *>
            ?: return XtreamUserInfo(false, null, null, null, null, emptyList(), null)
        val auth = text(info, "auth")
        val status = text(info, "status")
        val authenticated = when (auth) {
            "1", "true" -> true
            "0", "false" -> false
            else -> status.equals("active", ignoreCase = true)
        }
        return XtreamUserInfo(
            authenticated = authenticated,
            status = status,
            expiry = text(info, "exp_date")?.toLongOrNull(),
            maxConnections = text(info, "max_connections"),
            activeConnections = text(info, "active_cons"),
            formats = (info["allowed_output_formats"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.lowercase()?.takeIf { f -> f.isNotEmpty() } }
                ?: emptyList(),
            message = text(info, "message"),
        )
    }

    fun categories(tree: Any?): List<XtreamCategory> {
        val entries: Collection<*> = when (tree) {
            is List<*> -> tree
            is Map<*, *> -> tree.values
            else -> return emptyList()
        }
        return entries.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = text(map, "category_id") ?: return@mapNotNull null
            val name = text(map, "category_name") ?: return@mapNotNull null
            XtreamCategory(id, name)
        }
    }

    /** Builds an item from the wanted keys of one object of a list. */
    fun item(section: XtreamSection, fields: Map<String, String>): XtreamItem? {
        val id = (if (section == XtreamSection.SERIES) fields["series_id"] else fields["stream_id"])?.trim()
        val name = fields["name"]?.trim()
        if (id.isNullOrEmpty() || name.isNullOrEmpty()) return null

        val icon = (if (section == XtreamSection.SERIES) fields["cover"] else fields["stream_icon"])
            ?.trim()?.takeIf { it.isNotEmpty() }
        return XtreamItem(
            id = id,
            name = name,
            icon = icon,
            categoryId = fields["category_id"]?.trim()?.takeIf { it.isNotEmpty() },
            extension = fields["container_extension"]?.trim()?.trimStart('.')?.takeIf { it.isNotEmpty() },
            rating = fields["rating"],
            added = (fields["added"] ?: fields["last_modified"])?.toLongOrNull() ?: 0L,
            year = yearFrom(fields["year"]) ?: yearFrom(fields["releaseDate"]) ?: yearFrom(fields["release_date"]) ?: yearFrom(name),
        )
    }

    fun vodDetails(tree: Any?): XtreamDetails {
        val root = tree as? Map<*, *>
        val info = root?.get("info") as? Map<*, *>
        val movie = root?.get("movie_data") as? Map<*, *>
        return details(info, text(movie, "name"), text(movie, "container_extension"))
    }

    fun seriesDetails(tree: Any?): XtreamSeriesDetails {
        val root = tree as? Map<*, *>
        val info = root?.get("info") as? Map<*, *>
        return XtreamSeriesDetails(details(info, null, null), episodes(root?.get("episodes")))
    }

    private fun details(info: Map<*, *>?, fallbackName: String?, extension: String?): XtreamDetails {
        return XtreamDetails(
            name = firstOf(text(info, "name"), fallbackName),
            poster = firstOf(text(info, "movie_image"), text(info, "cover_big"), text(info, "cover")),
            backdrop = firstText(info?.get("backdrop_path")),
            plot = firstOf(text(info, "plot"), text(info, "description")),
            year = yearFrom(firstOf(text(info, "releasedate"), text(info, "release_date"), text(info, "releaseDate"), text(info, "year"))),
            genres = splitList(text(info, "genre")),
            actors = splitList(firstOf(text(info, "cast"), text(info, "actors"))),
            director = text(info, "director"),
            rating = text(info, "rating"),
            durationMinutes = durationMinutes(text(info, "duration_secs"), text(info, "duration"), text(info, "episode_run_time")),
            trailer = trailerUrl(text(info, "youtube_trailer")),
            tmdbId = firstOf(text(info, "tmdb_id"), text(info, "tmdb")),
            extension = extension,
        )
    }

    /** `episodes` is `{"1":[...], "2":[...]}` on most panels, but some send a list of lists or a flat list. */
    private fun episodes(raw: Any?): List<XtreamEpisode> {
        val groups: List<Pair<String?, List<*>>> = when (raw) {
            is Map<*, *> -> raw.entries.map { it.key.toString() to (it.value as? List<*> ?: emptyList<Any?>()) }
            is List<*> ->
                if (raw.isNotEmpty() && raw.all { it is List<*> }) {
                    raw.mapIndexed { index, value -> (index + 1).toString() to (value as List<*>) }
                } else {
                    listOf(null to raw)
                }
            else -> emptyList()
        }

        val result = ArrayList<XtreamEpisode>()
        for ((groupKey, list) in groups) {
            list.forEachIndexed { index, entry ->
                val map = entry as? Map<*, *> ?: return@forEachIndexed
                val id = text(map, "id") ?: return@forEachIndexed
                val info = map["info"] as? Map<*, *>
                result.add(
                    XtreamEpisode(
                        id = id,
                        season = text(map, "season")?.toIntOrNull() ?: groupKey?.toIntOrNull() ?: 1,
                        number = text(map, "episode_num")?.toIntOrNull() ?: (index + 1),
                        title = text(map, "title"),
                        extension = text(map, "container_extension")?.trimStart('.'),
                        poster = firstOf(text(info, "movie_image"), text(info, "cover_big")),
                        plot = firstOf(text(info, "plot"), text(info, "description")),
                        durationMinutes = durationMinutes(text(info, "duration_secs"), text(info, "duration"), null),
                    )
                )
            }
        }
        return result.sortedWith(compareBy({ it.season }, { it.number }))
    }

    // --- small helpers ---------------------------------------------------------------------------

    private val YEAR = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)")

    fun yearFrom(text: String?): Int? {
        val year = YEAR.find(text ?: return null)?.value?.toIntOrNull() ?: return null
        return year.takeIf { it in 1900..2100 }
    }

    /** From seconds ("5700"), a clock ("01:35:00") or minutes ("95"), in that order of preference. */
    fun durationMinutes(seconds: String?, clock: String?, minutes: String?): Int? {
        seconds?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return maxOf(1, (it / 60.0).roundToInt()) }
        clock?.trim()?.takeIf { it.count { c -> c == ':' } == 2 }?.split(':')?.mapNotNull { it.toIntOrNull() }?.let { parts ->
            if (parts.size == 3) {
                val total = parts[0] * 60 + parts[1] + if (parts[2] >= 30) 1 else 0
                if (total > 0) return total
            }
        }
        minutes?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return it.roundToInt() }
        return null
    }

    /** `youtube_trailer` holds a YouTube video id on most panels and a full URL on a few. */
    fun trailerUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) return value
        return if (Regex("[A-Za-z0-9_-]{11}").matches(value)) "https://www.youtube.com/watch?v=$value" else null
    }

    /** A rating between 0 and 10, or null when it is missing, zero or out of range. */
    fun ratingValue(raw: String?): Double? {
        return raw?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 }
    }

    fun splitList(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(',', '/', '|', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(40)
    }
}

// -------------------------------------------------------------------------------------------------
// XtreamRef: what a result card carries
// -------------------------------------------------------------------------------------------------

internal const val REF_LIVE = "live"
internal const val REF_VOD = "vod"
internal const val REF_SERIES = "series"
internal const val REF_EPISODE = "episode"
internal const val REF_INFO = "info"

/**
 * Everything needed to open and play an entry, stored as a small JSON string in the `url` / `data` fields that
 * CloudStream passes around (so favourites and history survive restarts). The account is referenced by its key; the
 * password is never stored in the reference.
 */
data class XtreamRef(
    val kind: String,
    val name: String = "",
    val acc: String = "",
    val id: String = "",
    val logo: String? = null,
    val ext: String? = null,
    val cat: String? = null,
    val info: String? = null,
) {
    /** Always starts with `{"name":`, which CloudStream's `fixUrl` leaves untouched. */
    fun encode(): String {
        val sb = StringBuilder(96 + name.length + acc.length)
        sb.append("{\"name\":")
        appendString(sb, name)
        sb.append(",\"kind\":")
        appendString(sb, kind)
        if (acc.isNotEmpty()) field(sb, "acc", acc)
        if (id.isNotEmpty()) field(sb, "id", id)
        if (logo != null) field(sb, "logo", logo)
        if (ext != null) field(sb, "ext", ext)
        if (cat != null) field(sb, "cat", cat)
        if (info != null) field(sb, "info", info)
        sb.append('}')
        return sb.toString()
    }

    companion object {
        fun decode(text: String?): XtreamRef? {
            if (text.isNullOrBlank()) return null
            return try {
                val map = XtreamJson.parseTree(text) as? Map<*, *> ?: return null
                XtreamRef(
                    kind = map["kind"] as? String ?: return null,
                    name = map["name"] as? String ?: "",
                    acc = map["acc"] as? String ?: "",
                    id = map["id"] as? String ?: "",
                    logo = map["logo"] as? String,
                    ext = map["ext"] as? String,
                    cat = map["cat"] as? String,
                    info = map["info"] as? String,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun field(sb: StringBuilder, key: String, value: String) {
    sb.append(",\"").append(key).append("\":")
    appendString(sb, value)
}

private fun appendString(sb: StringBuilder, value: String) {
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
