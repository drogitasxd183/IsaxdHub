package recloudstream

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import java.util.concurrent.ConcurrentHashMap

private const val CACHE_MILLIS = 15 * 60 * 1000L
private const val REQUEST_TIMEOUT_SECONDS = 120L

/**
 * Talks to the Xtream API (`player_api.php`) and keeps the answers in memory for a few minutes.
 *
 * The big catalogues (all channels, all movies, all series) are read as a stream, keeping only the fields the plugin
 * needs, so a panel with tens of thousands of entries does not exhaust the phone's memory.
 */
class XtreamClient(private val store: XtreamStore) {

    private class Cached(val loadedAt: Long, val value: Any)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun clearCache() = cache.clear()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, loader: suspend () -> T): T {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.loadedAt < CACHE_MILLIS) return it.value as T }
        val value = loader()
        cache.entries.removeAll { now - it.value.loadedAt >= CACHE_MILLIS }
        cache[key] = Cached(now, value)
        return value
    }

    private fun invalidResponse(): ErrorLoadingException {
        return ErrorLoadingException(
            tr(
                "El servidor no devolvió datos Xtream válidos (¿dirección o puerto incorrectos?).",
                "The server did not return valid Xtream data (wrong address or port?)."
            )
        )
    }

    private suspend fun request(url: String): NiceResponse {
        val userAgent = store.userAgent()
        val response = app.get(
            url,
            headers = if (userAgent.isEmpty()) emptyMap() else mapOf("User-Agent" to userAgent),
            timeout = REQUEST_TIMEOUT_SECONDS
        )
        if (!response.isSuccessful) {
            response.okhttpResponse.close()
            throw ErrorLoadingException("HTTP ${response.code}")
        }
        return response
    }

    /** For the small answers: the whole body is parsed into a tree. */
    private suspend fun tree(url: String): Any? {
        val text = request(url).textLarge
        return try {
            XtreamJson.parseTree(text)
        } catch (_: IllegalArgumentException) {
            throw invalidResponse()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Account
    // ---------------------------------------------------------------------------------------------

    suspend fun userInfo(account: XtreamAccount): XtreamUserInfo {
        return cached("${account.key}|user") { XtreamParser.userInfo(tree(account.apiUrl())) }
    }

    /** Fails with a readable message when the login is rejected or the account is expired. */
    private suspend fun requireActive(account: XtreamAccount) {
        val info = userInfo(account)
        if (!info.authenticated) {
            throw ErrorLoadingException(
                tr(
                    "Usuario o contraseña incorrectos (${account.name}).",
                    "Wrong username or password (${account.name})."
                )
            )
        }
        if (info.isBlocked) {
            throw ErrorLoadingException(
                tr(
                    "La cuenta ${account.name} no está activa: ${info.status}.",
                    "Account ${account.name} is not active: ${info.status}."
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Catalogues
    // ---------------------------------------------------------------------------------------------

    suspend fun categories(account: XtreamAccount, section: XtreamSection): List<XtreamCategory> {
        return cached("${account.key}|${section.code}|categories") {
            requireActive(account)
            XtreamParser.categories(tree(account.apiUrl(section.categoriesAction)))
        }
    }

    suspend fun items(account: XtreamAccount, section: XtreamSection): List<XtreamItem> {
        return cached("${account.key}|${section.code}|items") {
            requireActive(account)
            loadItems(account, section)
        }
    }

    private suspend fun loadItems(account: XtreamAccount, section: XtreamSection): List<XtreamItem> {
        val response = request(account.apiUrl(section.streamsAction))
        // `body` is nullable in OkHttp 4 and not in OkHttp 5; the safe call works with both.
        val reader = response.okhttpResponse.body?.charStream() ?: throw invalidResponse()
        val items = ArrayList<XtreamItem>()
        try {
            reader.use {
                XtreamJson.forEachTopLevel(reader, XtreamParser.LIST_KEYS) { key, fields ->
                    if (key == "user_info") {
                        if (fields["auth"] == "0") {
                            throw ErrorLoadingException(
                                tr(
                                    "Usuario o contraseña incorrectos (${account.name}).",
                                    "Wrong username or password (${account.name})."
                                )
                            )
                        }
                    } else {
                        XtreamParser.item(section, fields)?.let { items.add(it) }
                    }
                }
            }
        } catch (_: IllegalArgumentException) {
            throw invalidResponse()
        }
        // Movies and series: newest first. The sort is stable, so entries without a date keep the panel's order.
        if (section != XtreamSection.LIVE) items.sortByDescending { it.added }
        return items
    }

    // ---------------------------------------------------------------------------------------------
    // Details
    // ---------------------------------------------------------------------------------------------

    suspend fun movieDetails(account: XtreamAccount, id: String): XtreamDetails {
        return cached("${account.key}|vod|$id") {
            requireActive(account)
            XtreamParser.vodDetails(tree(account.apiUrl("get_vod_info", "vod_id" to id)))
        }
    }

    suspend fun seriesDetails(account: XtreamAccount, id: String): XtreamSeriesDetails {
        return cached("${account.key}|series|$id") {
            requireActive(account)
            XtreamParser.seriesDetails(tree(account.apiUrl("get_series_info", "series_id" to id)))
        }
    }
}
