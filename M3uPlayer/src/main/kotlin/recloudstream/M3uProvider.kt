package recloudstream

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Placeholder `MainPageData.data` used while no playlist is configured. */
private const val SETUP_DATA = "m3u://setup"

private const val CACHE_MILLIS = 10 * 60 * 1000L
private const val REQUEST_TIMEOUT_SECONDS = 60L
private const val MAX_SEARCH_RESULTS = 200

class M3uProvider(private val store: M3uStore) : MainAPI() {

    override var name = "M3U Player"

    // Not used for scraping: every entry carries its own full URL.
    override var mainUrl = "https://m3u.invalid"
    override var lang = "uni"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    /** One home-page entry per configured playlist (re-read every time the home page loads). */
    override val mainPage: List<MainPageData>
        get() {
            val sources = store.sources()
            return if (sources.isEmpty()) {
                listOf(MainPageData(tr("Configura tu lista M3U", "Set up your M3U playlist"), SETUP_DATA, true))
            } else {
                sources.map { MainPageData(it.name, it.source, true) }
            }
        }

    // ---------------------------------------------------------------------------------------
    // Playlist loading (with a small in-memory cache)
    // ---------------------------------------------------------------------------------------

    private class CachedPlaylist(val loadedAt: Long, val entries: List<M3uEntry>)

    private val cache = ConcurrentHashMap<String, CachedPlaylist>()

    fun clearCache() = cache.clear()

    private suspend fun loadPlaylist(source: String): List<M3uEntry> {
        val now = System.currentTimeMillis()
        cache[source]?.let { if (now - it.loadedAt < CACHE_MILLIS) return it.entries }

        val text = readSource(source)
        if (!M3uParser.looksLikeM3u(text)) {
            throw ErrorLoadingException(
                tr(
                    "La URL no devolvió una lista M3U válida (¿enlace caído o necesita otro User-Agent?).",
                    "The URL did not return a valid M3U playlist (dead link, or it needs another User-Agent?)."
                )
            )
        }

        val location = M3uParser.splitUrlHeaders(source).first
        val entries = if (M3uParser.isHlsManifest(text)) {
            // The "playlist" is really a single HLS stream: play it directly.
            listOf(M3uEntry(name = M3uParser.displayNameFromUrl(location), url = location))
        } else {
            M3uParser.parse(text, baseUrl = location.takeIf { it.startsWith("http", ignoreCase = true) })
        }
        if (entries.isEmpty()) {
            throw ErrorLoadingException(tr("La lista no contiene canales.", "The playlist has no entries."))
        }

        cache[source] = CachedPlaylist(now, entries)
        return entries
    }

    /** Reads a playlist from an http(s) URL (optionally `url|User-Agent=...`) or from a local file. */
    private suspend fun readSource(source: String): String {
        val (location, headers) = M3uParser.splitUrlHeaders(source)

        if (location.startsWith("http://", ignoreCase = true) ||
            location.startsWith("https://", ignoreCase = true)
        ) {
            val response = app.get(location, headers = headers, timeout = REQUEST_TIMEOUT_SECONDS)
            if (!response.isSuccessful) throw ErrorLoadingException("HTTP ${response.code}")
            // `.text` throws for bodies > 5 MB, and big IPTV playlists easily are.
            return response.textLarge
        }

        val file = File(location.removePrefix("file://"))
        if (!file.canRead()) {
            throw ErrorLoadingException(
                tr("No se puede leer el archivo: ${file.path}", "Cannot read file: ${file.path}")
            )
        }
        return file.readText()
    }

    // ---------------------------------------------------------------------------------------
    // Home page
    // ---------------------------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == SETUP_DATA) {
            val item = infoItem(
                tr("Agrega tu lista M3U en los ajustes del plugin", "Add your M3U playlist in the plugin settings"),
                setupHelp()
            )
            return newHomePageResponse(
                HomePageList(request.name, listOf(item), isHorizontalImages = false),
                hasNext = false
            )
        }

        val prefixWithPlaylist = store.sources().size > 1
        val lists = try {
            loadPlaylist(request.data)
                .groupBy { it.group ?: tr("Sin grupo", "No group") }
                .map { (group, items) ->
                    val seenSeries = HashSet<String>()
                    HomePageList(
                        if (prefixWithPlaylist) "${request.name} · $group" else group,
                        items.mapNotNull { toSearchResponse(it, request.data, seenSeries) },
                        isHorizontalImages = true
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Show the problem on the home page instead of failing the other playlists too.
            val item = infoItem(
                tr("No se pudo cargar \"${request.name}\"", "Could not load \"${request.name}\""),
                e.message ?: e.javaClass.simpleName
            )
            listOf(HomePageList(request.name, listOf(item), isHorizontalImages = false))
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // ---------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        val results = ArrayList<SearchResponse>()
        val seenSeries = HashSet<String>()
        for (source in store.sources()) {
            val entries = try {
                loadPlaylist(source.source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue // a broken playlist should not hide results from the others
            }
            for (entry in entries) {
                if (entry.name.contains(needle, ignoreCase = true) ||
                    entry.group?.contains(needle, ignoreCase = true) == true
                ) {
                    toSearchResponse(entry, source.source, seenSeries)?.let { results.add(it) }
                    if (results.size >= MAX_SEARCH_RESULTS) return results
                }
            }
        }
        return results
    }

    // ---------------------------------------------------------------------------------------
    // Details page and playback
    // ---------------------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val ref = ItemRef.decode(url)
            ?: throw ErrorLoadingException(tr("Entrada no válida", "Invalid entry"))

        return when (ref.kind) {
            // Informational item: shown but not playable (empty dataUrl).
            KIND_INFO -> newLiveStreamLoadResponse(ref.name, url, "") {
                plot = ref.info
            }

            KIND_SERIES -> loadSeries(ref, url)

            KIND_VOD -> newMovieLoadResponse(ref.name, url, TvType.Movie, dataUrl = url) {
                posterUrl = ref.logo
                plot = plotOf(ref)
                tags = listOfNotNull(ref.group)
            }

            else -> newLiveStreamLoadResponse(ref.name, url, url) {
                posterUrl = ref.logo
                plot = plotOf(ref)
                tags = listOfNotNull(ref.group)
            }
        }
    }

    /** Collects every episode of the show from its playlist and builds the seasons list. */
    private suspend fun loadSeries(ref: ItemRef, url: String): LoadResponse {
        val source = ref.source
            ?: throw ErrorLoadingException(tr("Falta la lista de origen", "Missing source playlist"))
        val key = M3uParser.seriesKey(ref.name)

        val found = loadPlaylist(source)
            .mapNotNull { entry ->
                val info = M3uParser.episodeInfo(entry)
                if (info != null && M3uParser.seriesKey(info.series) == key) entry to info else null
            }
            .distinctBy { it.first.url }
            .sortedWith(compareBy<Pair<M3uEntry, EpisodeInfo>>({ it.second.season }, { it.second.episode }))

        if (found.isEmpty()) {
            throw ErrorLoadingException(tr("No se encontraron episodios.", "No episodes found."))
        }

        val episodes = found.map { (entry, info) ->
            val data = ItemRef(
                name = entry.name,
                url = entry.url,
                logo = entry.logo,
                group = entry.group,
                headers = entry.headers,
                kind = KIND_VOD,
            ).encode()
            val episodeTitle = info.title ?: tr("Episodio ${info.episode}", "Episode ${info.episode}")
            val poster = entry.logo
            newEpisode(data) {
                this.name = episodeTitle
                this.season = info.season
                this.episode = info.episode
                this.posterUrl = poster
            }
        }

        return newTvSeriesLoadResponse(ref.name, url, TvType.TvSeries, episodes) {
            posterUrl = ref.logo
            plot = plotOf(ref)
            tags = listOfNotNull(ref.group)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = ItemRef.decode(data) ?: return false
        if (ref.kind == KIND_INFO || ref.url.isBlank()) return false

        val streamReferer = ref.headers.entries
            .firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value.orEmpty()
        val streamHeaders = ref.headers.filterKeys { !it.equals("referer", ignoreCase = true) }

        callback(
            newExtractorLink(this.name, ref.name, ref.url, guessType(ref.url)) {
                this.referer = streamReferer
                this.headers = streamHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun guessType(url: String): ExtractorLinkType {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> ExtractorLinkType.M3U8
            lower.contains(".mpd") -> ExtractorLinkType.DASH
            // .ts, .mp4, .mkv and extension-less IPTV URLs are played as a plain byte stream.
            else -> ExtractorLinkType.VIDEO
        }
    }

    /**
     * Turns a playlist entry into a result card. All episodes of one show collapse into a single
     * "series" card: later episodes of a show already in [seenSeries] return null.
     */
    private fun toSearchResponse(entry: M3uEntry, source: String, seenSeries: MutableSet<String>): SearchResponse? {
        val episode = M3uParser.episodeInfo(entry)
        if (episode != null) {
            if (!seenSeries.add(source + "\u0000" + M3uParser.seriesKey(episode.series))) return null
            val seriesData = ItemRef(
                name = episode.series,
                logo = entry.logo,
                group = entry.group,
                kind = KIND_SERIES,
                source = source,
            ).encode()
            val seriesPoster = entry.logo
            return newTvSeriesSearchResponse(episode.series, seriesData, TvType.TvSeries, fix = false) {
                posterUrl = seriesPoster
            }
        }

        val isVod = M3uParser.isVodUrl(entry.url)
        val data = ItemRef(
            name = entry.name,
            url = entry.url,
            logo = entry.logo,
            group = entry.group,
            headers = entry.headers,
            kind = if (isVod) KIND_VOD else KIND_LIVE,
        ).encode()
        val poster = entry.logo

        // fix = false: `data` is JSON, not a URL relative to mainUrl.
        return if (isVod) {
            newMovieSearchResponse(entry.name, data, TvType.Movie, fix = false) { posterUrl = poster }
        } else {
            newLiveSearchResponse(entry.name, data, TvType.Live, fix = false) { posterUrl = poster }
        }
    }

    private fun infoItem(title: String, details: String): SearchResponse {
        val data = ItemRef(name = title, kind = KIND_INFO, info = details).encode()
        return newLiveSearchResponse(title, data, TvType.Live, fix = false)
    }

    private fun plotOf(ref: ItemRef): String? {
        return ref.group?.let { tr("Grupo: $it", "Group: $it") }
    }

    private fun setupHelp(): String = tr(
        "Abre los ajustes de este plugin y escribe la URL de tu lista M3U (una por línea).\n\n" +
            "• En el inicio: toca el icono de engranaje junto al nombre del proveedor.\n" +
            "• O bien: Ajustes → Extensiones → M3U Player → Ajustes.\n\n" +
            "Después vuelve a abrir este proveedor para cargar los canales.",
        "Open this plugin's settings and enter the URL of your M3U playlist (one per line).\n\n" +
            "• On the home screen: tap the gear icon next to the provider name.\n" +
            "• Or: Settings → Extensions → M3U Player → Settings.\n\n" +
            "Then reopen this provider to load the channels."
    )
}
