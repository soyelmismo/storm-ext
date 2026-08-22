package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getAndUnpack
import java.net.URI

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamCategory(
    @JsonProperty("category_id") val categoryId: Any? = null,
    @JsonProperty("category_name") val categoryName: String? = null,
    @JsonProperty("parent_id") val parentId: Any? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("stream_type") val streamType: String? = null,
    @JsonProperty("stream_id") val streamId: Any? = null,
    @JsonProperty("series_id") val seriesId: Any? = null,
    @JsonProperty("stream_icon") val streamIcon: String? = null,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("rating_5based") val rating: Any? = null,
    @JsonProperty("category_id") val categoryId: Any? = null,
    @JsonProperty("categories_ids") val categoriesIds: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamSeriesDetails(
    @JsonProperty("info") val info: XtreamSeriesInfo? = null,
    @JsonProperty("episodes") val episodes: Map<String, List<XtreamEpisode>>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamSeriesInfo(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamEpisode(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("episode_num") val episodeNum: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("season") val season: Any? = null,
    @JsonProperty("info") val info: XtreamEpisodeInfo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamEpisodeInfo(
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("movie_image") val movieImage: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamEpisodeLink(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("language") val language: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamVodDetails(
    @JsonProperty("info") val info: XtreamVodInfo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XtreamVodInfo(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("cover_big") val coverBig: String? = null,
    @JsonProperty("movie_image") val movieImage: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null
)

class TvClubProvider : MainAPI() {
    override var mainUrl = "https://tv.m3uts.xyz"
    override var name = "TvClub"
    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Live,
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent = "Dalvik/2.1.0 (Linux; U; Android 11; Mi A2 Lite Build/RD2A.211001.002)"
    private val magmaUserAgent = "Magma Player/10"
    private val deviceId = "aabbccdd112233"
    private val authQuery = "username=m&password=m"

    private fun fixPoster(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        return if (poster.startsWith("/")) "https://image.tmdb.org/t/p/w500$poster" else poster
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()

        val liveCategories = app.get(
            "$mainUrl/player_api.php?$authQuery&action=get_live_categories",
            headers = mapOf("User-Agent" to userAgent)
        ).parsedSafe<Array<XtreamCategory>>()?.toList() ?: emptyList()

        val liveStreams = app.get(
            "$mainUrl/player_api.php?$authQuery&action=get_live_streams",
            headers = mapOf("User-Agent" to userAgent)
        ).parsedSafe<Array<XtreamStream>>()?.toList() ?: emptyList()

        val categoryOrder = listOf(
            "Mas vistos",
            "Nacionales",
            "HD",
            "Cine 24/7",
            "24/7",
            "Deportes",
            "Peliculas",
            "Infantiles",
            "Entretenimiento",
            "Musica",
            "Noticias",
            "Documentales"
        )

        for (catName in categoryOrder) {
            val catObj = liveCategories.find { it.categoryName?.equals(catName, ignoreCase = true) == true }
            val catIdStr = catObj?.categoryId?.toString()
            val filtered = if (catIdStr != null) {
                liveStreams.filter { it.categoryId?.toString() == catIdStr }
            } else emptyList()

            if (filtered.isNotEmpty()) {
                val searchResponses = filtered.map { stream ->
                    val id = stream.streamId?.toString() ?: ""
                    newLiveSearchResponse(
                        stream.name ?: "Canal",
                        "$mainUrl/live/$id",
                        TvType.Live
                    ) {
                        this.posterUrl = fixPoster(stream.streamIcon)
                    }
                }
                homePages.add(HomePageList("En Vivo: $catName", searchResponses, isHorizontalImages = true))
            }
        }

        // Add remaining live categories
        liveCategories.forEach { cat ->
            val cName = cat.categoryName ?: return@forEach
            if (!categoryOrder.any { it.equals(cName, ignoreCase = true) }) {
                val cId = cat.categoryId?.toString()
                val filtered = liveStreams.filter { it.categoryId?.toString() == cId }
                if (filtered.isNotEmpty()) {
                    val searchResponses = filtered.map { stream ->
                        val id = stream.streamId?.toString() ?: ""
                        newLiveSearchResponse(
                            stream.name ?: "Canal",
                            "$mainUrl/live/$id",
                            TvType.Live
                        ) {
                            this.posterUrl = fixPoster(stream.streamIcon)
                        }
                    }
                    homePages.add(HomePageList("En Vivo: $cName", searchResponses, isHorizontalImages = true))
                }
            }
        }

        if (homePages.isEmpty() && liveStreams.isNotEmpty()) {
            val allLive = liveStreams.map { stream ->
                val id = stream.streamId?.toString() ?: ""
                newLiveSearchResponse(
                    stream.name ?: "Canal",
                    "$mainUrl/live/$id",
                    TvType.Live
                ) {
                    this.posterUrl = fixPoster(stream.streamIcon)
                }
            }
            homePages.add(HomePageList("Todos los Canales", allLive, isHorizontalImages = true))
        }

        if (homePages.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(homePages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()

        // Search live
        try {
            val liveStreams = app.get(
                "$mainUrl/player_api.php?$authQuery&action=get_live_streams",
                headers = mapOf("User-Agent" to userAgent)
            ).parsedSafe<Array<XtreamStream>>()?.toList() ?: emptyList()

            results.addAll(
                liveStreams.filter { it.name?.lowercase()?.contains(cleanQuery) == true }.map { stream ->
                    val id = stream.streamId?.toString() ?: ""
                    newLiveSearchResponse(
                        stream.name ?: "Canal",
                        "$mainUrl/live/$id",
                        TvType.Live
                    ) {
                        this.posterUrl = fixPoster(stream.streamIcon)
                    }
                }
            )
        } catch (_: Exception) {
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(mainUrl).trimStart('/')

        when {
            path.startsWith("live/") -> {
                val streamId = path.removePrefix("live/").trim()
                return newMovieLoadResponse("Canal en Vivo", url, TvType.Live, "live:$streamId") {
                    this.plot = "Transmisión en vivo por TvClub"
                }
            }
            path.startsWith("movie/") -> {
                val vodId = path.removePrefix("movie/").trim()
                val vodDetails = app.get(
                    "$mainUrl/player_api.php?$authQuery&action=get_vod_info&vod_id=$vodId",
                    headers = mapOf("User-Agent" to userAgent)
                ).parsedSafe<XtreamVodDetails>()
                val info = vodDetails?.info
                val title = info?.name ?: "Película"
                val poster = fixPoster(info?.coverBig ?: info?.movieImage)
                val backdrop = fixPoster(info?.backdropPath)

                return newMovieLoadResponse(title, url, TvType.Movie, "movie:$vodId") {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = info?.plot
                }
            }
            path.startsWith("series/") -> {
                val seriesId = path.removePrefix("series/").trim()
                val seriesDetails = app.get(
                    "$mainUrl/player_api.php?$authQuery&action=get_series_info&series_id=$seriesId",
                    headers = mapOf("User-Agent" to userAgent)
                ).parsedSafe<XtreamSeriesDetails>() ?: return null

                val info = seriesDetails.info
                val title = info?.name ?: "Serie"
                val poster = fixPoster(info?.cover)
                val backdrop = fixPoster(info?.backdrop)
                val episodesMap = seriesDetails.episodes ?: emptyMap()
                val episodeList = mutableListOf<Episode>()

                episodesMap.forEach { (seasonStr, epList) ->
                    val seasonNum = seasonStr.toIntOrNull() ?: 1
                    epList.forEachIndexed { idx, ep ->
                        val epNum = ep.episodeNum?.toString()?.toIntOrNull() ?: (idx + 1)
                        val epName = ep.title ?: "Episodio $epNum"
                        val epPlot = ep.info?.plot
                        val epCover = fixPoster(ep.info?.movieImage)
                        val epData = "episode:$seriesId:$seasonNum:$epNum"

                        episodeList.add(
                            newEpisode(epData) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = epCover
                                this.description = epPlot
                            }
                        )
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = info?.plot
                }
            }
            else -> return null
        }
    }

    private suspend fun resolveEmbedLink(
        linkUrl: String,
        quality: String?,
        language: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var linksEmitted = false
        val interceptCallback: (ExtractorLink) -> Unit = { link ->
            linksEmitted = true
            callback(link)
        }

        try {
            loadExtractor(linkUrl, subtitleCallback, interceptCallback)
        } catch (_: Exception) {
        }

        if (linksEmitted) return

        val embedCode = linkUrl.substringAfterLast("/").substringBefore("?").substringBefore("&")
        val urlsToTry = mutableListOf<String>()

        when {
            linkUrl.contains("streamwish") || linkUrl.contains("hlsflex") || linkUrl.contains("hgplaycdn") || linkUrl.contains("do7go") -> {
                urlsToTry.add("https://streamwish.top/e/$embedCode")
                urlsToTry.add("https://flaswish.com/e/$embedCode")
                urlsToTry.add("https://hlswish.com/e/$embedCode")
                urlsToTry.add("https://embedwish.com/e/$embedCode")
            }
            linkUrl.contains("vidhide") -> {
                urlsToTry.add("https://vidhidefast.com/v/$embedCode")
                urlsToTry.add("https://vidhidepre.com/v/$embedCode")
                urlsToTry.add("https://vidhidepro.com/v/$embedCode")
            }
            linkUrl.contains("voe") -> {
                urlsToTry.add("https://voe.sx/e/$embedCode")
            }
            else -> {
                urlsToTry.add(linkUrl)
            }
        }

        for (u in urlsToTry) {
            if (u != linkUrl) {
                try {
                    loadExtractor(u, subtitleCallback, interceptCallback)
                    if (linksEmitted) return
                } catch (_: Exception) {
                }
            }
        }

        for (u in (urlsToTry + linkUrl).distinct()) {
            try {
                val response = app.get(
                    u,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer" to "$mainUrl/"
                    )
                )

                val text = response.text
                if (text.length < 900 && (text.contains("Page is loading") || text.contains("Redirecting"))) {
                    val redir = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""").find(text)?.groupValues?.get(1)
                    if (redir != null) {
                        try {
                            loadExtractor(redir, subtitleCallback, interceptCallback)
                            if (linksEmitted) return
                        } catch (_: Exception) {
                        }
                    }
                    continue
                }

                val unpacked = if (!getPacked(text).isNullOrEmpty()) {
                    getAndUnpack(text)
                } else {
                    text
                }

                val m3u8Url = Regex("""https?:\\?/\\?/[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(unpacked)?.value?.replace("\\/", "/")
                val mp4Url = if (m3u8Url == null) Regex("""https?:\\?/\\?/[^"'\s<>]+\.mp4[^"'\s<>]*""").find(unpacked)?.value?.replace("\\/", "/") else null

                val hostName = try { URI(linkUrl).host?.removePrefix("www.") ?: "Servidor" } catch (_: Exception) { "Servidor" }
                val label = "$hostName ${language ?: ""} ${quality ?: ""}".trim()

                if (m3u8Url != null) {
                    val m3u8Links = try {
                        M3u8Helper.generateM3u8(
                            this.name,
                            m3u8Url,
                            u,
                            headers = mapOf(
                                "Referer" to u,
                                "User-Agent" to USER_AGENT
                            )
                        )
                    } catch (_: Exception) {
                        emptyList()
                    }

                    if (m3u8Links.isNotEmpty()) {
                        m3u8Links.forEach(callback)
                        linksEmitted = true
                    } else {
                        callback(
                            newExtractorLink(
                                this.name,
                                label,
                                m3u8Url
                            ) {
                                this.type = ExtractorLinkType.M3U8
                                this.referer = u
                                this.headers = mapOf(
                                    "Referer" to u,
                                    "User-Agent" to USER_AGENT
                                )
                            }
                        )
                        linksEmitted = true
                    }
                    if (linksEmitted) return
                } else if (mp4Url != null) {
                    callback(
                        newExtractorLink(
                            this.name,
                            label,
                            mp4Url
                        ) {
                            this.referer = u
                            this.headers = mapOf(
                                "Referer" to u,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                    linksEmitted = true
                    return
                }
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        when {
            data.startsWith("live:") || data.startsWith("movie:") -> {
                val streamId = data.substringAfter(":")
                val genResponse = app.post(
                    "$mainUrl/stream/gen/$streamId",
                    data = mapOf(
                        "id" to streamId,
                        "cast" to "false",
                        "device" to deviceId,
                        "code" to ""
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "User-Agent" to userAgent
                    )
                ).text.trim()

                if (genResponse.isNotBlank()) {
                    val fullM3u8Url = if (genResponse.startsWith("http")) genResponse else "$mainUrl$genResponse"
                    val secureHeaders = mapOf(
                        "X-App" to "ps",
                        "X-Version" to "10/1.0.9",
                        "X-Did" to deviceId,
                        "User-Agent" to magmaUserAgent
                    )

                    val m3u8Links = try {
                        M3u8Helper.generateM3u8(
                            this.name,
                            fullM3u8Url,
                            "$mainUrl/",
                            headers = secureHeaders
                        )
                    } catch (_: Exception) {
                        emptyList()
                    }

                    if (m3u8Links.isNotEmpty()) {
                        m3u8Links.forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(
                                this.name,
                                this.name,
                                fullM3u8Url
                            ) {
                                this.type = ExtractorLinkType.M3U8
                                this.referer = "$mainUrl/"
                                this.headers = secureHeaders
                            }
                        )
                    }
                    return true
                }
            }
            data.startsWith("episode:") -> {
                val parts = data.split(":")
                if (parts.size >= 4) {
                    val seriesId = parts[1]
                    val season = parts[2]
                    val epNum = parts[3]

                    val links = app.get(
                        "$mainUrl/player_api.php?$authQuery&action=get_episode_links&serie=$seriesId&season=$season&episode=$epNum",
                        headers = mapOf("User-Agent" to userAgent)
                    ).parsedSafe<Array<XtreamEpisodeLink>>()?.toList() ?: emptyList()

                    links.amap { linkObj ->
                        val linkUrl = linkObj.url ?: return@amap
                        resolveEmbedLink(linkUrl, linkObj.quality, linkObj.language, subtitleCallback, callback)
                    }
                    return true
                }
            }
        }
        return false
    }
}
