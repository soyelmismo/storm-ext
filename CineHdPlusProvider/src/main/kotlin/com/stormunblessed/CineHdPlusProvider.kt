package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element

class CineHdPlusProvider : MainAPI() {
    override var mainUrl = "https://cinehdplus.org"
    override var name = "CineHdPlus"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "peliculas/" to "Películas",
        "series/" to "Series",
        "populares" to "Populares",
        "peliculas/?sort=popular" to "Películas: Populares",
        "series/?sort=popular" to "Series: Populares",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (base.contains("?")) {
            "$mainUrl/$base&page=$page"
        } else if (base == "populares") {
            if (page == 1) "$mainUrl/$base" else "$mainUrl/$base/page/$page/"
        } else {
            "$mainUrl/$base/page/$page/"
        }
        val document = app.get(url).documentLarge
        val home = document.select("a[href*='/pelicula-'], a[href*='/series-tv-']")
            .filter { !it.hasClass("group/btn") }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val img = this.selectFirst("img") ?: return null
        val rawTitle = img.attr("alt").takeIf { it.isNotBlank() } ?: this.attr("title")
        if (rawTitle.isBlank()) return null
        val title = rawTitle.replace(Regex("""\s*-\s*(?:Película|Serie|Series).*$""", RegexOption.IGNORE_CASE), "").trim()
        val posterUrl = fixUrlNull(img.attr("src"))
        val type = if (href.contains("/pelicula-")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=$query").documentLarge
        return document.select("a[href*='/pelicula-'], a[href*='/series-tv-']")
            .filter { !it.hasClass("group/btn") }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).documentLarge
        val tvType = if (url.contains("/pelicula-")) TvType.Movie else TvType.TvSeries
        val rawTitle = doc.selectFirst("head meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: doc.selectFirst(".sm\\:text-2xl")?.text()
            ?: return null
        val title = rawTitle.substringBefore(" (").substringBefore(" |")
            .replace(Regex("""\s*-\s*(?:Película|Serie).*$""", RegexOption.IGNORE_CASE), "").trim()
        val plot = doc.selectFirst("head meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("p.leading-relaxed, p.line-clamp-3")?.text()
        val year = doc.selectFirst(".sub-meta span[itemprop=dateCreated], span:matchesOwn(\\d{4})")
            ?.text()?.filter { it.isDigit() }?.takeLast(4)?.toIntOrNull()
        val poster = fixUrlNull(
            doc.selectFirst("head meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.absolute, div.aspect-2/3 img")?.attr("src")
        )
        val backimage = fixUrlNull(doc.selectFirst(".opacity-20, div[style*='background-image']")?.attr("src"))
        val tags = doc.select(".details__list li, a[href*='/genero/']").map { it.text().trim() }.filter { it.isNotBlank() }
        val trailer = doc.selectFirst("#OptYt iframe, iframe[src*='youtube']")?.attr("src")
            ?.replaceFirst("https://www.youtube.com/embed/", "https://www.youtube.com/watch?v=")
        val recommendations = doc.select("a[href*='/pelicula-'], a[href*='/series-tv-']")
            .filter { it.attr("href") != url && !it.hasClass("group/btn") }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            val episodeElements = doc.select("a[href*='/episodio-']")
            val episodes = episodeElements.mapNotNull { epEl ->
                val epUrl = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null
                val season = Regex("""(\d+)x(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                val episode = Regex("""(\d+)x(\d+)""").find(epUrl)?.groupValues?.get(2)?.toIntOrNull()
                val epName = epEl.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() } ?: "Episodio $episode"
                val epImg = fixUrlNull(epEl.selectFirst("img")?.attr("src"))
                newEpisode(epUrl) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                    this.posterUrl = epImg
                }
            }.distinctBy { it.data }

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).documentLarge
        val apiHost = mainUrl.replaceFirst("https://", "https://api.")

        val buttons = doc.select("button.player-tab")
        for (it in buttons) {
            val lang = it.attr("data-lang")
            val frame = it.attr("data-url")
                .substringAfter("player.php?h=").substringBefore("&")
            if (frame.isEmpty()) continue

            try {
                val gotoDoc = app.get("$apiHost/ir/goto.php?h=$frame").document
                val form = gotoDoc.selectFirst("form")
                val url = form?.selectFirst("input#url")?.attr("value")
                if (url != null) {
                    val rdDoc = app.post(
                        "$apiHost/ir/rd.php",
                        data = mapOf("url" to url)
                    ).document
                    val form2 = rdDoc.selectFirst("form")
                    val url2 = form2?.selectFirst("input#url")?.attr("value")
                    if (url2 != null) {
                        val redirDoc = app.post(
                            "$apiHost/ir/redir_ddh.php",
                            data = mapOf("url" to url2, "dl" to "0")
                        ).document
                        val form3 = redirDoc.selectFirst("form")
                        val postUrl = form3?.attr("action")
                        val vid = form3?.selectFirst("input#vid")?.attr("value")
                        val hash = form3?.selectFirst("input#hash")?.attr("value")

                        if (postUrl != null && vid != null && hash != null) {
                            val finalDoc = app.post(postUrl, data = mapOf("vid" to vid, "hash" to hash)).document
                            val encoded = finalDoc.selectFirst("script:containsData(link =)")?.html()
                                ?.substringAfter("link = '")?.substringBefore("';")
                            if (!encoded.isNullOrEmpty()) {
                                val link = base64Decode(encoded)
                                loadSourceNameExtractor(
                                    lang,
                                    fixHostsLinks(link),
                                    "$mainUrl/",
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return true
    }

    private suspend fun loadSourceNameExtractor(
        source: String,
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(
                    newExtractorLink(
                        source = this@CineHdPlusProvider.name,
                        name = "$source [${link.name}]",
                        url = link.url,
                    ) {
                        this.quality = link.quality
                        this.type = link.type
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com", "https://streamwish.to")
            .replaceFirst("https://cybervynx.com", "https://streamwish.to")
            .replaceFirst("https://dumbalag.com", "https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
            .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            .replaceFirst("https://lulu.st", "https://lulustream.com")
            .replaceFirst("https://uqload.io", "https://uqload.com")
            .replaceFirst("https://do7go.com", "https://dood.la")
    }
}