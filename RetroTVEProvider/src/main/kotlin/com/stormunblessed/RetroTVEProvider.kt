package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.stormunblessed.extractors.VkExtractor
import com.stormunblessed.extractors.YourUpload
import org.jsoup.nodes.Element



class RetroTVEProvider : MainAPI() {
    override var mainUrl = "https://retrotve.com"
    override var name = "RetroTVE"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        "lista-de-series" to "Series",
        "peliculas" to "Películas",
        "category/animacion" to "Animación",
        "category/liveaction" to "Live Action"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url, headers = headers).document
        val home = document.select("article, .item, .movies-list article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = document.selectFirst("a.next, .pagination a.next, a[rel=next], .nav-links a.next") != null
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isEmpty() || href == mainUrl) return null
        val title = this.selectFirst("h2, h3, .Title, .entry-title")?.text()?.trim()
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val img = this.selectFirst("img")
        val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
        val fixedPoster = poster?.let { if (it.startsWith("//")) "https:$it" else fixUrl(it) }
        val type = if (href.contains("/pelicula/")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixedPoster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixedPoster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url, headers = headers).document
        return document.select("article, .item, .movies-list article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1.Title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - Retro")?.trim()
            ?: return null

        val poster = document.selectFirst(".Image img, .poster img, img[src*=uploads]")?.let { img ->
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            if (src.startsWith("//")) "https:$src" else fixUrl(src)
        } ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val plot = document.selectFirst(".Description, .entry-content, .sinopsis, div.wp-content p")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val tags = document.select("a[href*='/category/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val isSeries = url.contains("/serie/") || url.contains("/seriestv/")

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seenUrls = mutableSetOf<String>()
            val episodeRows = document.select("table tr, div.TPTblCont tr, tr, ul.episodes li")

            if (episodeRows.isNotEmpty()) {
                episodeRows.forEach { row ->
                    val a = row.selectFirst("a[href*='/seriestv/']") ?: return@forEach
                    val epHref = fixUrl(a.attr("href"))
                    if (!seenUrls.add(epHref)) return@forEach

                    val epName = a.text().trim().takeIf { it.isNotEmpty() }
                        ?: row.select("td").getOrNull(2)?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "Episodio"

                    val seasonEpisodeMatch = Regex("-(\\d+)x(\\d+)").find(epHref)
                    val seasonNum = seasonEpisodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = seasonEpisodeMatch?.groupValues?.get(2)?.toIntOrNull()
                        ?: row.select("td").firstOrNull()?.text()?.trim()?.toIntOrNull()

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                document.select("a[href*='/seriestv/']").forEach { a ->
                    val epHref = fixUrl(a.attr("href"))
                    if (!seenUrls.add(epHref)) return@forEach
                    val seasonEpisodeMatch = Regex("-(\\d+)x(\\d+)").find(epHref)
                    val seasonNum = seasonEpisodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = seasonEpisodeMatch?.groupValues?.get(2)?.toIntOrNull()
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = a.text().trim().ifEmpty { "Episodio ${epNum ?: 1}" }
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = headers)
        val rawHtml = response.text
        val unescapedHtml = rawHtml
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")

        val trembedRegex = Regex("""https?://retrotve\.com/\?trembed=\d+&[^\s"'\<\>]+""")
        val trembedLinks = trembedRegex.findAll(unescapedHtml).map { match ->
            match.value
                .substringBefore("\"")
                .substringBefore("'")
                .trimEnd('&')
                .trim()
        }.filter { it.isNotBlank() }.distinct().toList()

        val document = response.document
        val iframeLinks = document.select("iframe, div.TPlayerTb iframe, div[id*=Opt] iframe")
            .mapNotNull { it.attr("src").takeIf { s -> s.isNotBlank() } }

        val allSources = (trembedLinks + iframeLinks).distinct()

        allSources.amap { sourceUrl ->
            var cleanSource = sourceUrl.trim()
            if (cleanSource.startsWith("//")) cleanSource = "https:$cleanSource"
            if (cleanSource.contains("trembed=")) {
                try {
                    val trembedDoc = app.get(cleanSource, referer = data, headers = headers).document
                    trembedDoc.select("iframe").forEach { iframe ->
                        var iframeSrc = iframe.attr("src").trim()
                        if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"
                        if (iframeSrc.isNotBlank()) {
                            when {
                                iframeSrc.contains("yourupload.com") -> {
                                    YourUpload().getUrl(iframeSrc, cleanSource, subtitleCallback, callback)
                                }
                                iframeSrc.contains("vk.com") || iframeSrc.contains("vkvideo.ru") -> {
                                    VkExtractor().getUrl(iframeSrc, cleanSource, subtitleCallback, callback)
                                }
                                else -> {
                                    loadExtractor(iframeSrc, cleanSource, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            } else {
                when {
                    cleanSource.contains("yourupload.com") -> {
                        YourUpload().getUrl(cleanSource, data, subtitleCallback, callback)
                    }
                    cleanSource.contains("vk.com") || cleanSource.contains("vkvideo.ru") -> {
                        VkExtractor().getUrl(cleanSource, data, subtitleCallback, callback)
                    }
                    else -> {
                        loadExtractor(cleanSource, data, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }
}
