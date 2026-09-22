package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.net.URLDecoder

class DoramasFlixProvider : MainAPI() {
    override var mainUrl = "https://doramasflix.co"
    override var name = "Doramasflix"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
    )

    override val mainPage = mainPageOf(
        "doramas" to "Doramas",
        "peliculas" to "Películas",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}?page=$page"
        val doc = app.get(url).documentLarge
        val home = doc.select("a[href*='/doramas/'], a[href*='/peliculas/']")
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

    private fun cleanImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("_next/image?url=")) {
            val raw = url.substringAfter("_next/image?url=").substringBefore("&")
            return try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                raw
            }
        }
        return if (url.startsWith("/")) "https://image.tmdb.org/t/p/w1280$url" else url
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val img = this.selectFirst("img")
        val rawImg = img?.attr("src") ?: img?.attr("data-src")
        val posterUrl = cleanImageUrl(rawImg)

        val title = this.selectFirst("h3, h2, span.title, p")?.text()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: href.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }

        val isMovie = href.contains("/peliculas/")
        val type = if (isMovie) TvType.Movie else TvType.AsianDrama

        return if (isMovie) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        val doc = app.get(url).documentLarge
        return doc.select("a[href*='/doramas/'], a[href*='/peliculas/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).documentLarge
        val isMovie = url.contains("/peliculas/")

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" —")?.substringBefore(" |")
            ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }

        val poster = cleanImageUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img[alt*='Banner'], div.aspect-2/3 img, img[alt*='$title']")?.attr("src")
        )
        val backimage = cleanImageUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div[style*='background-image']")?.attr("style")?.substringAfter("url('")?.substringBefore("')")
        )

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("div:contains(Sinopsis) + p, p.text-muted-foreground, p.leading-relaxed")?.text()

        val year = doc.select("span:matchesOwn(\\d{4})").firstOrNull()?.text()?.toIntOrNull()
        val tags = doc.select("a[href*='/generos/'], a[href*='/etiquetas/'], div.flex-wrap span").map { it.text().trim() }.filter { it.isNotBlank() }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val epLinks = doc.select("a[href*='/capitulos/']")
            val episodes = epLinks.mapNotNull { epEl ->
                val epUrl = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null
                val epText = epEl.text()
                val s = Regex("""(\d+)x(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val e = Regex("""(\d+)x(\d+)""").find(epUrl)?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""ep\.\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val epName = "Episodio $e"
                newEpisode(epUrl) {
                    this.name = epName
                    this.season = s
                    this.episode = e
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ShortenerJwtPayload(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("server") val server: String? = null,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val html = response.text

        // Find all JWT tokens from embedshortener.co/e/...
        val tokens = Regex("""embedshortener\.co/e/([A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_\-]+){2})""").findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        var foundAny = false
        for (token in tokens) {
            try {
                val parts = token.split(".")
                if (parts.size < 2) continue
                val payloadJson = String(base64DecodeArray(parts[1]), Charsets.UTF_8)
                val payload = tryParseJson<ShortenerJwtPayload>(payloadJson) ?: continue
                var rawLink = payload.link ?: continue

                var streamUrl = String(base64DecodeArray(rawLink), Charsets.UTF_8)
                if (streamUrl.startsWith("aHR0")) {
                    streamUrl = String(base64DecodeArray(streamUrl), Charsets.UTF_8)
                }

                if (streamUrl.startsWith("http")) {
                    foundAny = true
                    loadExtractor(streamUrl, mainUrl, subtitleCallback) { link ->
                        CoroutineScope(Dispatchers.IO).launch {
                            callback(
                                newExtractorLink(
                                    source = this@DoramasFlixProvider.name,
                                    name = link.name,
                                    url = link.url
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
            } catch (_: Exception) {}
        }
        return foundAny
    }
}
