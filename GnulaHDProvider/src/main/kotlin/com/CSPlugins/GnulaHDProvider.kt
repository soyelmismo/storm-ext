package com.CSPlugins

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

class GnulaHDProvider : MainAPI() {

    override var mainUrl = "https://ww3.gnulahd.nu"
    override var name = "GnulaHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    companion object {
        fun getType(t: String): TvType = when {
            t.contains("Serie", ignoreCase = true) -> TvType.TvSeries
            t.contains("Pelicula", ignoreCase = true) -> TvType.Movie
            t.contains("Anime", ignoreCase = true) -> TvType.Anime
            else -> TvType.Movie
        }
    }

    override val mainPage = mainPageOf(
        "ver/?type=Pelicula&order=latest" to "Películas",
        "ver/?status=&type=Serie&order=latest" to "Series",
        "ver/?status=&type=Anime&order=latest" to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").documentLarge
        val home = document.select("a.gnrd-card, div.postbody article.bs").mapNotNull { it.toSearchResult() }
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
        val href = fixUrlNull(this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") }) ?: return null
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("src"))
        val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst(".gnrd-card-title, a")?.attr("title")
            ?: return null

        val langs = this.select(".gnrd-lang, div.caratula-flags-badge img")
            .mapNotNull { it.text().ifEmpty { it.attr("title") }.take(3) }
            .joinToString("/")
        val displayTitle = if (langs.isNotEmpty()) "$title [$langs]" else title

        val type = if (href.contains("/serie") || this.selectFirst(".gnrd-card-genres")?.text()?.contains("Serie", true) == true) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return newMovieSearchResponse(displayTitle, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").documentLarge
        return doc.select("a.gnrd-card, div.postbody article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).documentLarge
        val title = doc.selectFirst("h1 span.gnrd-sr")?.text()
            ?: doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" |")
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.thumb img, div.gnrd-card-art img")?.attr("src")
        )
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.select("div.mindesc p").text()

        val year = doc.selectFirst(".gnrd-card-metaline span:not(.gnrd-cm-rating)")?.text()?.trim()?.toIntOrNull()
            ?: doc.select("span.split:has(b:matchesOwn(^Estreno:))").first()?.ownText()?.trim()?.takeLast(4)?.toIntOrNull()

        val genres = doc.select(".gnrd-card-genres, div.genxed a").flatMap { it.text().split("·", ",").map { g -> g.trim() } }

        val episodeElements = doc.select("a.gnrd-epc, div.postbody div.eplister a")
        val isSeries = episodeElements.isNotEmpty() || url.contains("/series/")

        if (isSeries) {
            val episodes = episodeElements.mapNotNull { epEl ->
                val epHref = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null
                val s = epEl.attr("data-s").toIntOrNull()
                    ?: Regex("""(\d+)x(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val e = epEl.attr("data-e").toIntOrNull()
                    ?: Regex("""(\d+)x(\d+)""").find(epHref)?.groupValues?.get(2)?.toIntOrNull()
                val name = epEl.selectFirst(".gnrd-epc-title, div.epl-title")?.text() ?: "Episodio $e"
                val epThumb = epEl.selectFirst(".gnrd-epc-thumb")?.attr("style")?.substringAfter("url('")?.substringBefore("')")
                    ?: epEl.selectFirst("img")?.attr("src")

                newEpisode(epHref) {
                    this.name = name
                    this.season = s
                    this.episode = e
                    this.posterUrl = fixUrlNull(epThumb)
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GnrdPlayerResponse(
        @JsonProperty("p") val p: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GnrdData(
        @JsonProperty("t") val t: String? = null,
        @JsonProperty("langs") val langs: List<GnrdLang>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GnrdLang(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("servers") val servers: List<GnrdServer>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GnrdServer(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("src") val src: String? = null
    )

    private fun gnrdUnpack(packed: String): String {
        return try {
            val raw = base64DecodeArray(packed)
            val key = byteArrayOf(103, 78, 55, 100)
            val decrypted = ByteArray(raw.size)
            for (i in raw.indices) {
                decrypted[i] = (raw[i].toInt() xor key[i and 3].toInt()).toByte()
            }
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).documentLarge

        // Intentar flujo moderno GNPV (XOR decrypt)
        var pid: String? = null
        var tok: String? = null

        val scripts = doc.select("script").map { it.data() }
        for (s in scripts) {
            if (s.contains("_gnrdPid") && s.contains("_gnrdTok")) {
                pid = Regex("""_gnrdPid\s*=\s*(\d+)""").find(s)?.groupValues?.get(1)
                tok = Regex("""_gnrdTok\s*=\s*"([^"]+)"""").find(s)?.groupValues?.get(1)
                if (pid != null && tok != null) break
            }
        }

        if (pid == null) {
            val btn = doc.selectFirst("[data-id][data-t]")
            pid = btn?.attr("data-id")
            tok = btn?.attr("data-t")
        }

        if (pid != null && tok != null) {
            try {
                val playerResp = app.get(
                    "$mainUrl/wp-json/gnrd/v1/player?id=$pid&t=$tok",
                    referer = data
                ).parsedSafe<GnrdPlayerResponse>()

                val packed = playerResp?.p
                if (!packed.isNullOrEmpty()) {
                    val unpackedJson = gnrdUnpack(packed)
                    val gnrdData = tryParseJson<GnrdData>(unpackedJson)
                    for (langGroup in gnrdData?.langs.orEmpty()) {
                        val langLabel = langGroup.label ?: "Multi"
                        for (srv in langGroup.servers.orEmpty()) {
                            val srvUrl = srv.src ?: continue
                            loadExtractor(srvUrl, mainUrl, subtitleCallback) { link ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    callback(
                                        newExtractorLink(
                                            source = this@GnulaHDProvider.name,
                                            name = "$langLabel [${srv.title ?: link.name}]",
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
                    }
                    return true
                }
            } catch (e: Exception) {
                // Continuar a fallback
            }
        }

        // Fallback legado a iframe
        val embedUrl = doc.selectFirst("div.player-embed > iframe")?.attr("src")
        if (!embedUrl.isNullOrEmpty()) {
            val embedDoc = app.get(embedUrl).document
            val script = embedDoc.select("script").firstOrNull { it.data().contains("var videosOriginal") }?.data()
            if (script != null) {
                val urlsWithLang = mapOf(
                    "videosOriginal" to "VO",
                    "videosLatino" to "Lat",
                    "videosCastellano" to "Cas",
                    "videosSubtitulado" to "Sub"
                ).flatMap { (varName, langCode) ->
                    Regex("""var $varName = (\[.*?\]);""").find(script)?.groupValues?.get(1)?.let { arrayContent ->
                        Regex("""\?id=([^"]+)""").findAll(arrayContent).map { match ->
                            val decoded = String(base64DecodeArray(match.groupValues[1]), Charsets.UTF_8)
                            decoded to langCode
                        }.toList()
                    } ?: emptyList()
                }

                for ((decodedUrl, langCode) in urlsWithLang) {
                    try {
                        loadExtractor(decodedUrl, mainUrl, subtitleCallback) { link ->
                            CoroutineScope(Dispatchers.IO).launch {
                                callback(
                                    newExtractorLink(
                                        source = this@GnulaHDProvider.name,
                                        name = "$langCode [${link.name}]",
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
                    } catch (_: Exception) {}
                }
                return true
            }
        }

        return false
    }
}