package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MonoschinosProvider : MainAPI() {
    override var mainUrl = "https://monoschinos.st"
    override var name = "Monoschinos"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "" to "Últimos capítulos",
        "animes" to "Catálogo",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "") {
            val doc = app.get(mainUrl, timeout = 120).document
            val latest = doc.select("article").mapNotNull { article ->
                val title = article.selectFirst("h2, h3, .card-title")?.text() ?: return@mapNotNull null
                val href = fixUrl(article.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val img = article.selectFirst("img")
                val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
                val ep = Regex("episodio-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: article.selectFirst("span.episode, span[class*=episode]")?.text()?.toIntOrNull()
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                    addDubStatus(DubStatus.Subbed, ep)
                }
            }
            return newHomePageResponse(
                list = HomePageList(request.name, latest, isHorizontalImages = true),
                hasNext = false
            )
        }
        val url = if (page <= 1) "$mainUrl/animes" else "$mainUrl/animes?p=$page"
        val doc = app.get(url, timeout = 120).document
        val home = doc.select("article").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a.page-link[rel=next], a[rel=next]") != null
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: (if (this.tagName() == "a") this else null) ?: return null
        val title = this.selectFirst("h2, h3, .card-title")?.text() ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrl(a.attr("href"))
        val img = this.selectFirst("img")
        val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/buscar?q=$query", timeout = 120).document
        return doc.select("article").mapNotNull { it.toSearchResult() }
    }

    data class CapList(
        @JsonProperty("eps") val eps: List<Ep>,
    )

    data class Ep(
        @JsonProperty("num") val num: Int? = null,
    )

    override suspend fun load(url: String): LoadResponse? {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val resp = app.get(url, timeout = 120, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
        val doc = resp.document
        val cookies = resp.cookies

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - MonosChinos")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[data-src]")?.attr("data-src")
        val backimage = doc.selectFirst("img[style*='blur']")?.attr("data-src") ?: poster
        val plot = doc.selectFirst("div#tab-info p, #profile-tab-pane p, div.sinopsis p, div.description p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val typeText = doc.select("div.col-12.col-md-3 dl dd, span.badge, .anime-type").text()
        val tvType = when {
            typeText.contains("Pelicula", ignoreCase = true) -> TvType.AnimeMovie
            typeText.contains("OVA", ignoreCase = true) || typeText.contains("Especial", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        val tags = doc.select("a[href*='/genero/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val status = when {
            doc.text().contains("Estreno") || doc.text().contains("En emisión") -> ShowStatus.Ongoing
            doc.text().contains("Finalizado") -> ShowStatus.Completed
            else -> null
        }

        val caplistUrl = doc.selectFirst("section.caplist, .caplist, [data-ajax]")?.attr("data-ajax") ?: ""

        val episodes = if (caplistUrl.isNotBlank()) {
            try {
                val capJson = app.get(
                    caplistUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    cookies = cookies
                ).parsedSafe<CapList>()
                val eps = capJson?.eps ?: emptyList()
                eps.mapNotNull { ep ->
                    val epNum = ep.num ?: return@mapNotNull null
                    val cleanBase = url.substringBefore("?").replace("-sub-espanol", "").replace("/anime/", "/ver/")
                    val epUrl = "$cleanBase-episodio-$epNum"
                    newEpisode(epUrl) {
                        this.episode = epNum
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backimage
            this.plot = plot
            this.tags = tags
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val doc = app.get(data, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).document
        doc.select("button.play-video, button[data-player]").amap {
            val encoded = it.attr("data-player").takeIf { p -> p.isNotBlank() } ?: return@amap
            val url = base64Decode(encoded)
            loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
}
