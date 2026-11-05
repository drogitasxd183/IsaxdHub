package recloudstream

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import kotlin.coroutines.cancellation.CancellationException

/** `MainPageData.data` used while nothing is configured. */
private const val SETUP_DATA = "xtream://setup"

private const val MAX_ROWS = 60
private const val MAX_ITEMS_PER_ROW = 120
private const val SEARCH_LIMIT_PER_SECTION = 60

class XtreamProvider(private val store: XtreamStore) : MainAPI() {

    private val client = XtreamClient(store)

    override var name = "Xtream Player"

    // Not used for scraping: every entry carries its own account and id.
    override var mainUrl = "https://xtream.invalid"
    override var lang = "uni"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    fun clearCache() = client.clearCache()

    private fun sectionLabel(section: XtreamSection): String {
        return when (section) {
            XtreamSection.LIVE -> tr("TV en vivo", "Live TV")
            XtreamSection.VOD -> tr("Películas", "Movies")
            XtreamSection.SERIES -> tr("Series", "Series")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Home page: one entry per account and enabled section, one row per category
    // ---------------------------------------------------------------------------------------------

    override val mainPage: List<MainPageData>
        get() {
            val accounts = store.accounts()
            val sections = store.enabledSections()
            if (accounts.isEmpty() || sections.isEmpty()) {
                return listOf(MainPageData(tr("Configura tu cuenta Xtream", "Set up your Xtream account"), SETUP_DATA, true))
            }
            val result = ArrayList<MainPageData>()
            for (account in accounts) {
                for (section in sections) {
                    val title = sectionLabel(section)
                    result.add(
                        MainPageData(
                            if (accounts.size > 1) "${account.name} · $title" else title,
                            "${section.code}|${account.key}",
                            section == XtreamSection.LIVE, // channel logos are wide, posters are tall
                        )
                    )
                }
            }
            return result
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == SETUP_DATA) {
            return newHomePageResponse(
                infoList(
                    request.name,
                    tr("Agrega tu cuenta en los ajustes del plugin", "Add your account in the plugin settings"),
                    setupHelp()
                ),
                hasNext = false
            )
        }

        val section = XtreamSection.fromCode(request.data.substringBefore('|'))
        val account = store.accounts().firstOrNull { it.key == request.data.substringAfter('|') }
        if (section == null || account == null) {
            return newHomePageResponse(
                infoList(
                    request.name,
                    tr("Cuenta no encontrada", "Account not found"),
                    tr("Vuelve a abrir el proveedor para actualizar la lista.", "Reopen the provider to refresh the list.")
                ),
                hasNext = false
            )
        }

        val rows = try {
            buildRows(account, section, request.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Show the problem on the home page instead of failing the other entries too.
            listOf(
                infoList(
                    request.name,
                    tr("No se pudo cargar \"${request.name}\"", "Could not load \"${request.name}\""),
                    e.message ?: e.javaClass.simpleName
                )
            )
        }
        return newHomePageResponse(rows, hasNext = false)
    }

    private suspend fun buildRows(account: XtreamAccount, section: XtreamSection, entryName: String): List<HomePageList> {
        val categories = client.categories(account, section)
        val items = client.items(account, section)
        if (items.isEmpty()) {
            return listOf(
                infoList(
                    entryName,
                    tr("No hay contenido en esta sección", "There is no content in this section"),
                    tr(
                        "El servidor no devolvió ningún elemento. Comprueba la cuenta con «Probar conexión» en los ajustes.",
                        "The server returned nothing. Check the account with \"Test connection\" in the settings."
                    )
                )
            )
        }

        val plan = XtreamRows.plan(
            categories, items, CategoryFilter(store.categoryFilter()), tr("Otros", "Other"), MAX_ROWS, MAX_ITEMS_PER_ROW
        )
        val rows = ArrayList<HomePageList>()
        val usedNames = HashSet<String>()
        for (row in plan.rows) {
            var rowName = "$entryName · ${row.categoryName}"
            var copy = 2
            while (!usedNames.add(rowName)) rowName = "$entryName · ${row.categoryName} (${copy++})"
            rows.add(
                HomePageList(
                    rowName,
                    row.items.map { toSearchResponse(it, section, account, row.categoryName) },
                    isHorizontalImages = section == XtreamSection.LIVE
                )
            )
        }

        if (rows.isEmpty()) {
            return listOf(
                infoList(
                    entryName,
                    tr("Ninguna categoría coincide con el filtro", "No category matches the filter"),
                    tr(
                        "Revisa el «Filtro de categorías» en los ajustes del plugin.",
                        "Check the \"Category filter\" in the plugin settings."
                    )
                )
            )
        }
        if (plan.matching > rows.size) {
            rows.add(
                infoList(
                    "$entryName · …",
                    tr("Se muestran ${rows.size} de ${plan.matching} categorías", "Showing ${rows.size} of ${plan.matching} categories"),
                    tr(
                        "Usa el «Filtro de categorías» en los ajustes para elegir cuáles ver, o busca por nombre.",
                        "Use the \"Category filter\" in the settings to choose which ones to see, or search by name."
                    )
                )
            )
        }
        return rows
    }

    // ---------------------------------------------------------------------------------------------
    // Search: channels, movies and series of every account, each with its own type
    // ---------------------------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        val needle = normalizeText(query)
        if (needle.length < 2) return emptyList()

        val results = ArrayList<SearchResponse>()
        for (account in store.accounts()) {
            for (section in store.enabledSections()) {
                val items = try {
                    client.items(account, section)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue // a broken account must not hide the results of the others
                }
                var found = 0
                for (item in items) {
                    if (!normalizeText(item.name).contains(needle)) continue
                    results.add(toSearchResponse(item, section, account, null))
                    if (++found >= SEARCH_LIMIT_PER_SECTION) break
                }
            }
        }
        return results
    }

    // ---------------------------------------------------------------------------------------------
    // Details pages
    // ---------------------------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val ref = XtreamRef.decode(url) ?: throw ErrorLoadingException(tr("Entrada no válida", "Invalid entry"))
        return when (ref.kind) {
            // Informational item: shown but not playable (empty dataUrl).
            REF_INFO -> newLiveStreamLoadResponse(ref.name, url, "") {
                plot = ref.info
            }

            REF_LIVE -> newLiveStreamLoadResponse(ref.name, url, url) {
                posterUrl = ref.logo
                plot = ref.cat?.let { tr("Categoría: $it", "Category: $it") }
                tags = listOfNotNull(ref.cat)
            }

            REF_VOD -> loadMovie(ref, url)
            REF_SERIES -> loadSeries(ref, url)
            else -> throw ErrorLoadingException(tr("Entrada no válida", "Invalid entry"))
        }
    }

    private fun accountFor(ref: XtreamRef): XtreamAccount {
        return store.accounts().firstOrNull { it.key == ref.acc }
            ?: throw ErrorLoadingException(
                tr(
                    "La cuenta ya no está configurada. Vuelve a agregarla en los ajustes del plugin.",
                    "The account is no longer configured. Add it again in the plugin settings."
                )
            )
    }

    private suspend fun loadMovie(ref: XtreamRef, url: String): LoadResponse {
        val account = accountFor(ref)
        // If the extra information cannot be fetched the movie can still be played from what the list gave us.
        val details = try {
            client.movieDetails(account, ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val playRef = ref.copy(ext = ref.ext ?: details?.extension).encode()

        return newMovieLoadResponse(details?.name ?: ref.name, url, TvType.Movie, dataUrl = playRef) {
            posterUrl = details?.poster ?: ref.logo
            backgroundPosterUrl = details?.backdrop
            plot = plotOf(details)
            year = details?.year
            tags = details?.genres?.takeIf { it.isNotEmpty() } ?: listOfNotNull(ref.cat)
            duration = details?.durationMinutes
            applyDetails(details)
        }
    }

    private suspend fun loadSeries(ref: XtreamRef, url: String): LoadResponse {
        val account = accountFor(ref)
        val series = client.seriesDetails(account, ref.id)
        if (series.episodes.isEmpty()) {
            throw ErrorLoadingException(tr("Esta serie no tiene episodios.", "This series has no episodes."))
        }
        val details = series.details
        val seriesName = details.name ?: ref.name

        val episodes = series.episodes.map { episode ->
            val data = XtreamRef(
                kind = REF_EPISODE,
                name = "$seriesName S${episode.season}E${episode.number}",
                acc = account.key,
                id = episode.id,
                logo = episode.poster,
                ext = episode.extension,
            ).encode()
            val episodeName = episode.title ?: tr("Episodio ${episode.number}", "Episode ${episode.number}")
            newEpisode(data) {
                this.name = episodeName
                this.season = episode.season
                this.episode = episode.number
                this.posterUrl = episode.poster
                this.description = episode.plot
                this.runTime = episode.durationMinutes
            }
        }

        return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, episodes) {
            posterUrl = details.poster ?: ref.logo
            backgroundPosterUrl = details.backdrop
            plot = plotOf(details)
            year = details.year
            tags = details.genres.takeIf { it.isNotEmpty() } ?: listOfNotNull(ref.cat)
            duration = details.durationMinutes
            applyDetails(details)
        }
    }

    private fun plotOf(details: XtreamDetails?): String? {
        if (details == null) return null
        val parts = listOfNotNull(details.plot, details.director?.let { "Director: $it" })
        return parts.joinToString("\n\n").ifEmpty { null }
    }

    /** Cast, score, trailer and TMDb id when the panel provides them (no TMDb key is needed for this). */
    private suspend fun LoadResponse.applyDetails(details: XtreamDetails?) {
        if (details == null) return
        addActors(details.actors.takeIf { it.isNotEmpty() })
        addScore(XtreamParser.ratingValue(details.rating)?.toString())
        addTrailer(details.trailer)
        addTMDbId(details.tmdbId)
    }

    // ---------------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = XtreamRef.decode(data) ?: return false
        if (ref.kind == REF_INFO || ref.id.isBlank()) return false
        val account = store.accounts().firstOrNull { it.key == ref.acc } ?: return false

        val streamUrl: String
        val type: ExtractorLinkType
        when (ref.kind) {
            REF_LIVE -> {
                val extension = liveExtension(account)
                streamUrl = account.liveUrl(ref.id, extension)
                type = if (extension == "m3u8") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            }
            REF_VOD -> {
                streamUrl = account.movieUrl(ref.id, ref.ext ?: "mp4")
                type = ExtractorLinkType.VIDEO
            }
            REF_EPISODE -> {
                streamUrl = account.seriesUrl(ref.id, ref.ext ?: "mp4")
                type = ExtractorLinkType.VIDEO
            }
            else -> return false
        }

        val userAgent = store.userAgent()
        val linkHeaders: Map<String, String> = if (userAgent.isEmpty()) emptyMap() else mapOf("User-Agent" to userAgent)
        callback(
            newExtractorLink(this.name, ref.name, streamUrl, type) {
                this.referer = ""
                this.headers = linkHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /** HLS when the panel allows it (unless TS is preferred in the settings), plain TS otherwise. */
    private suspend fun liveExtension(account: XtreamAccount): String {
        if (store.preferTs()) return chooseLiveExtension(true, emptyList())
        val formats = try {
            client.userInfo(account).formats
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return chooseLiveExtension(false, formats)
    }

    // ---------------------------------------------------------------------------------------------
    // Result cards
    // ---------------------------------------------------------------------------------------------

    private fun toSearchResponse(
        item: XtreamItem,
        section: XtreamSection,
        account: XtreamAccount,
        category: String?
    ): SearchResponse {
        val kind = when (section) {
            XtreamSection.LIVE -> REF_LIVE
            XtreamSection.VOD -> REF_VOD
            XtreamSection.SERIES -> REF_SERIES
        }
        val data = XtreamRef(
            kind = kind,
            name = item.name,
            acc = account.key,
            id = item.id,
            logo = item.icon,
            ext = item.extension,
            cat = category,
        ).encode()
        val poster = item.icon
        val itemYear = item.year
        val itemScore = XtreamParser.ratingValue(item.rating)?.let { Score.from(it, 10) }

        // fix = false: `data` is JSON, not a URL relative to mainUrl.
        return when (section) {
            XtreamSection.LIVE -> newLiveSearchResponse(item.name, data, TvType.Live, fix = false) {
                posterUrl = poster
            }
            XtreamSection.VOD -> newMovieSearchResponse(item.name, data, TvType.Movie, fix = false) {
                posterUrl = poster
                this.year = itemYear
                this.score = itemScore
            }
            XtreamSection.SERIES -> newTvSeriesSearchResponse(item.name, data, TvType.TvSeries, fix = false) {
                posterUrl = poster
                this.year = itemYear
                this.score = itemScore
            }
        }
    }

    private fun infoItem(title: String, details: String): SearchResponse {
        val data = XtreamRef(kind = REF_INFO, name = title, info = details).encode()
        return newLiveSearchResponse(title, data, TvType.Live, fix = false)
    }

    private fun infoList(name: String, title: String, details: String): HomePageList {
        return HomePageList(name, listOf(infoItem(title, details)), isHorizontalImages = false)
    }

    private fun setupHelp(): String = tr(
        "Abre los ajustes de este plugin y rellena el servidor, el usuario y la contraseña de tu lista Xtream (con «Añadir otra lista» puedes agregar más).\n\n" +
            "• En el inicio: toca el icono de engranaje junto al nombre del proveedor.\n" +
            "• O bien: Ajustes → Extensiones → Xtream Player → Ajustes.\n\n" +
            "Revisa también que al menos una sección (TV en vivo, Películas o Series) esté activada. " +
            "Después vuelve a abrir este proveedor.",
        "Open this plugin's settings and fill in the server, username and password of your Xtream list (use \"Add another list\" for more).\n\n" +
            "• On the home screen: tap the gear icon next to the provider name.\n" +
            "• Or: Settings → Extensions → Xtream Player → Settings.\n\n" +
            "Also make sure at least one section (Live TV, Movies or Series) is enabled. " +
            "Then reopen this provider."
    )
}
