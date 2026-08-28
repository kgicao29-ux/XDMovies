package com.xdmovies.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * CloudStream provider for XDMovies (https://top.xdmovies.wtf).
 *
 * Clean-room Kotlin rebuild. Behaviour (endpoints, selectors and the link
 * resolution chain) was recovered from the published XDMovies.cs3 (v12, by
 * phisher98) plus live inspection of the site. See ANALYSIS.md.
 *
 * Site layout:
 *  - lists       /?type=movies|series, /category.php?genre=..&page=N,
 *                /category.php?ott=..&page=N  -> a.movie-link cards
 *  - search      /php/search_api.php?query=..  (JSON, x-auth-token header; the
 *                token is also embedded in every page as window.AUTH_TOKEN)
 *  - detail      /movies/{slug} | /series/{slug}
 *                  movie : div.download-item a  -> link.xdmovies.wtf urls
 *                  series: div.season-section (id season-packs-N /
 *                          season-episodes-N or button.toggle-season-btn)
 *                          -> .episode-card (.episode-title SxxEyy,
 *                             a.movie-download-btn / a.download-button)
 *                          -> .packs-grid .pack-card (season packs appended
 *                             as extra entries)
 *  - link host   link.xdmovies.wtf redirects (/go/, /r/) to the file host
 *                (usually hubcloud.cx); HubCloudExtractor turns those into
 *                direct playable links.
 */
class XDMoviesProvider : MainAPI() {
    override var mainUrl = "https://top.xdmovies.wtf"
    override var name = "XDMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    @Volatile
    private var authToken: String = AUTH_TOKEN_FALLBACK

    // ------------------------------------------------------------------ //
    // Models
    // ------------------------------------------------------------------ //

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchApiItem(
        val id: Int? = null,
        @JsonProperty("tmdb_id") val tmdbId: String? = null,
        val title: String? = null,
        val type: String? = null, // "movie" | "tv"
        val poster: String? = null, // tmdb path
        @JsonProperty("release_year") val releaseYear: String? = null,
        val path: String? = null,
        val qualities: List<String>? = null,
        @JsonProperty("audio_languages") val audioLanguages: String? = null,
    )

    /** Episode/movie payload: a list of link.xdmovies.wtf (or direct) URLs. */
    data class LinkPayload(val links: List<String>)

    data class EpisodeEntry(
        val season: Int,
        val number: Int,
        val rawTitle: String?,
        val links: List<String>,
    ) {
        val displayTitle: String get() = rawTitle?.takeIf { it.isNotBlank() } ?: "Episode $number"
    }

    companion object {
        private const val AUTH_TOKEN_FALLBACK = "7297skkihkajwnsgaklakshuwd"
        private const val LINK_HOST = "link.xdmovies.wtf"

        private val CF_PHRASES = listOf(
            "just a moment", "checking your browser", "attention required",
            "verify you are human", "performing security verification", "ddos-guard",
        )

        private val SEASON_ID_REGEX = Regex("""season-(?:packs|episodes)-(\d+)""")
        private val SEASON_NAME_REGEX = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val EPISODE_TITLE_REGEX = Regex("""S(\d{1,2})E(\d{1,3})""", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
        private val AUTH_TOKEN_REGEX = Regex("""window\.AUTH_TOKEN\s*=\s*['"]([^'"]+)['"]""")
    }

    override val mainPage = mainPageOf(
        "/?type=movies" to "Movies",
        "/?type=series" to "TV Series",
        "/category.php?ott=Netflix" to "Netflix",
        "/category.php?ott=Amazon" to "Amazon Prime Video",
        "/category.php?ott=DisneyPlus" to "Disney+",
        "/category.php?ott=AppleTVPlus" to "Apple TV+",
        "/category.php?ott=HBOMax" to "HBO Max",
        "/category.php?ott=Hulu" to "Hulu",
        "/category.php?ott=JioHotstar" to "Hotstar",
        "/category.php?genre=Action" to "Action",
        "/category.php?genre=Comedy" to "Comedy",
        "/category.php?genre=Thriller" to "Thriller",
        "/category.php?genre=Sci-fi" to "Sci-Fi",
    )

    // ------------------------------------------------------------------ //
    // Networking helpers
    // ------------------------------------------------------------------ //

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        "x-auth-token" to authToken,
        "x-requested-with" to "XMLHttpRequest",
        "User-Agent" to userAgent,
    ) + extra

    private fun refreshToken(html: String) {
        AUTH_TOKEN_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let {
            authToken = it
        }
    }

    private fun isCloudflareBlocked(code: Int, html: String): Boolean {
        if (code == 403 || code == 503) return true
        val lower = html.lowercase()
        return CF_PHRASES.any { lower.contains(it) }
    }

    private suspend fun getDocument(url: String, referer: String? = null): Document {
        val res = app.get(url, headers = headers(referer?.let { mapOf("Referer" to it) } ?: emptyMap()))
        val html = res.text
        if (isCloudflareBlocked(res.code, html)) {
            throw CloudflareBlockedException()
        }
        refreshToken(html)
        return Jsoup.parse(html)
    }

    class CloudflareBlockedException : RuntimeException(
        "XDMovies is protected by Cloudflare on this network. " +
            "Try again, use another network/VPN, or use the original XDMovies extension " +
            "(it has an interactive bypass) if the challenge persists."
    )

    // ------------------------------------------------------------------ //
    // Main page
    // ------------------------------------------------------------------ //

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains('?')) '&' else '?'
        val url = "$mainUrl${request.data}${sep}page=$page"
        val doc = runCatching { getDocument(url) }.getOrNull()
        val results = doc?.let { parseCards(it) }.orEmpty()
        return newHomePageResponse(HomePageList(request.name, results), hasNext = results.isNotEmpty())
    }

    private fun parseCards(doc: Document): List<SearchResponse> =
        doc.select("a.movie-link").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = el.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val year = el.selectFirst("p")?.text()?.trim()?.let { YEAR_REGEX.find(it)?.value?.toIntOrNull() }
            val poster = el.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                ?: el.selectFirst("img")?.attr("data-src")?.takeIf { it.startsWith("http") }
            val quality = el.selectFirst(".quality-badge")?.text()?.trim()
            val isSeries = el.selectFirst(".movie-card")?.attr("data-type") in setOf("series", "tv") ||
                href.startsWith("/series/")
            val link = if (href.startsWith("http")) href else "$mainUrl$href"
            val badge = quality?.let { q ->
                when {
                    q.contains("2160", true) || q.contains("4k", true) -> SearchQuality.FourK
                    q.contains("1080", true) || q.contains("720", true) -> SearchQuality.HD
                    else -> null
                }
            }
            if (isSeries) {
                newTvSeriesSearchResponse(title, link) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = badge
                }
            } else {
                newMovieSearchResponse(title, link) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = badge
                }
            }
        }

    // ------------------------------------------------------------------ //
    // Search
    // ------------------------------------------------------------------ //

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // The PHP endpoint matches literally: "spider man" finds nothing while
        // "spider-man" works, so retry with hyphens when the raw query is empty.
        val items = fetchSearch(q).takeIf { it.isNotEmpty() }
            ?: fetchSearch(q.replace(' ', '-'))
        return items.mapNotNull { item ->
            val title = item.title?.trim().takeIf { !it.isNullOrBlank() } ?: return@mapNotNull null
            val path = item.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = if (path.startsWith("http")) path else "$mainUrl$path"
            val poster = item.poster?.let { p ->
                if (p.startsWith("http")) p else "https://image.tmdb.org/t/p/w500$p"
            }
            val year = item.releaseYear?.toIntOrNull()
            val isSeries = item.type == "tv"
            val badge = item.qualities?.joinToString(" ")?.let { q ->
                when {
                    q.contains("2160", true) || q.contains("4k", true) -> SearchQuality.FourK
                    q.contains("1080", true) || q.contains("720", true) -> SearchQuality.HD
                    else -> null
                }
            }
            if (isSeries) {
                newTvSeriesSearchResponse(title, link) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = badge
                }
            } else {
                newMovieSearchResponse(title, link) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = badge
                }
            }
        }
    }

    private suspend fun fetchSearch(query: String): List<SearchApiItem> {
        val url = "$mainUrl/php/search_api.php?query=" + URLEncoder.encode(query, "UTF-8")
        val body = runCatching { app.get(url, headers = headers()).text }.getOrNull() ?: return emptyList()
        return runCatching { parseJson<List<SearchApiItem>>(body) }.getOrNull() ?: emptyList()
    }

    // ------------------------------------------------------------------ //
    // Detail
    // ------------------------------------------------------------------ //

    private fun Element.linksInCard(): List<String> =
        select("a.movie-download-btn, a.download-button, div.download-item a")
            .mapNotNull { a ->
                a.attr("abs:href").takeIf { it.startsWith("http") }
                    ?: a.attr("href").takeIf { it.startsWith("http") }
            }
            .filter { it.isNotBlank() }
            .distinct()

    private fun episodeNumber(title: String?, card: Element): Int {
        EPISODE_TITLE_REGEX.find(title ?: "")?.groupValues?.get(2)?.toIntOrNull()?.let { return it }
        val parent = card.parent() ?: return 1
        val index = parent.children().indexOf(card)
        return if (index >= 0) index + 1 else 1
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocument(url)

        val title = doc.selectFirst("#movie-header h1, .details-wrapper h1, h1")?.text()?.trim()
            ?.substringBefore(" Download")?.takeIf { it.isNotEmpty() }
            ?: url.substringAfterLast('/').substringBefore("-download")
                .replace('-', ' ').replaceFirstChar { it.uppercase() }

        val poster = doc.selectFirst("#movie-header img, .details-wrapper img, .poster img")?.attr("src")
            ?.takeIf { it.startsWith("http") }
        val background = doc.selectFirst("#movie-header")?.attr("style")
            ?.let { Regex("""url\(['"]?([^'")]+)""").find(it)?.groupValues?.get(1) }
            ?.takeIf { it.startsWith("http") }

        fun infoValue(label: String): String? =
            doc.selectFirst("p:contains($label:)")?.text()?.substringAfter("$label:")?.trim()?.takeIf { it.isNotEmpty() }

        val plot = doc.selectFirst("p.overview")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val ratingText = infoValue("Rating")
        val genres = infoValue("Genres")?.split(',', '•')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val year = infoValue("First Air Date")?.let { YEAR_REGEX.find(it)?.value?.toIntOrNull() }
            ?: infoValue("Release")?.let { YEAR_REGEX.find(it)?.value?.toIntOrNull() }
        val duration = infoValue("Runtime")
        val audio = doc.select("span.neon-audio").eachText().joinToString(", ").takeIf { it.isNotBlank() }
        val tags = genres + listOfNotNull(audio?.let { "Audio: $it" })

        val isSeries = url.contains("/series/")

        if (!isSeries) {
            val links = doc.select("div.download-item a, #download-links a, .download a")
                .mapNotNull { it.attr("abs:href").takeIf { h -> h.startsWith("http") } }
                .distinct()
            return newMovieLoadResponse(title, url, TvType.Movie, LinkPayload(links)) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                runCatching { ratingText?.let { addScore(it) } }
                duration?.let { addDuration(it) }
            }
        }

        // ---- series ----
        val episodes = mutableListOf<EpisodeEntry>()
        doc.select("div.season-section").forEach { section ->
            val seasonNumber = SEASON_ID_REGEX.find(section.outerHtml())?.groupValues?.get(1)?.toIntOrNull()
                ?: section.selectFirst("button.toggle-season-btn")?.text()
                    ?.let { SEASON_NAME_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: 1

            // Individual episode cards -> numbered episodes.
            section.select(".episode-card").forEach { card ->
                val epTitle = card.selectFirst(".episode-title")?.text()
                val links = card.linksInCard()
                if (links.isNotEmpty()) {
                    episodes += EpisodeEntry(seasonNumber, episodeNumber(epTitle, card), epTitle, links)
                }
            }

            // Season packs -> appended as numbered entries after the episodes.
            section.select(".packs-grid .pack-card").forEachIndexed { index, card ->
                val label = card.selectFirst(".pack-title, h4, h3")?.text()?.trim()
                val links = card.linksInCard()
                if (links.isNotEmpty()) {
                    val number = label?.let { EPISODE_TITLE_REGEX.find(it)?.groupValues?.get(2)?.toIntOrNull() }
                        ?: (index + 1)
                    episodes += EpisodeEntry(seasonNumber, number, label, links)
                }
            }
        }

        // If a series page ever uses plain download items, treat them as one entry.
        if (episodes.isEmpty()) {
            val links = doc.select("div.download-item a").mapNotNull { it.attr("abs:href") }.distinct()
            if (links.isNotEmpty()) episodes += EpisodeEntry(1, 1, null, links)
        }

        val sorted = episodes.sortedWith(compareBy({ it.season }, { it.number }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted.map { entry ->
            newEpisode(LinkPayload(entry.links)) {
                this.name = entry.displayTitle
                this.season = entry.season
                this.episode = entry.number
            }
        }) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            runCatching { ratingText?.let { addScore(it) } }
            duration?.let { addDuration(it) }
        }
    }

    // ------------------------------------------------------------------ //
    // Links
    // ------------------------------------------------------------------ //

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = runCatching { parseJson<LinkPayload>(data) }.getOrNull() ?: return false
        val links = payload.links.filter { it.startsWith("http") }.distinct()
        if (links.isEmpty()) return false

        var emitted = false
        for (link in links) {
            runCatching {
                when {
                    // Shortener: follow the /go/ // /r/ redirect chain to the file host.
                    link.contains(LINK_HOST) -> {
                        val resolved = resolveXdLink(link)
                        if (resolved != null && resolved != link) {
                            if (loadExtractor(resolved, "$mainUrl/", subtitleCallback, callback)) emitted = true
                        }
                    }
                    else -> if (loadExtractor(link, "$mainUrl/", subtitleCallback, callback)) emitted = true
                }
            }
        }
        return emitted
    }

    /**
     * link.xdmovies.wtf/{id} -> 302 (/go/, /r/) -> file host url.
     * The site's own web client confirms a Turnstile session is only required
     * when the request looks suspicious; from normal networks the plain
     * redirect chain works, which is what we do here.
     */
    private suspend fun resolveXdLink(url: String): String? {
        var current = url
        repeat(8) {
            val res = runCatching {
                app.get(current, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent))
            }.getOrNull() ?: return null
            val location = res.headers["location"]?.takeIf { it.isNotBlank() }
            when {
                location != null -> current = if (location.startsWith("http")) location else "$mainUrl$location"
                res.url.isNotBlank() && res.url != current -> current = res.url
                else -> return current.takeIf { it != url }
            }
        }
        return current.takeIf { it != url }
    }
}
