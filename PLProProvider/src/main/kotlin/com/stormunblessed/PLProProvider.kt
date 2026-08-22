package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
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

data class PLProChannelRoot(
    @JsonProperty("channels") val channels: List<PLProChannelItem>? = null,
    @JsonProperty("categories") val categories: List<PLProChannelCategory>? = null
)

data class PLProChannelCategory(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("name") val name: String? = null
)

data class PLProChannelItem(
    @JsonProperty("a") val id: Any? = null,
    @JsonProperty("b") val name: String? = null,
    @JsonProperty("c") val icon: String? = null,
    @JsonProperty("e") val categoryIds: List<Any>? = null,
    @JsonProperty("g") val epg: String? = null
)

data class PLProMovieRoot(
    @JsonProperty("movies") val movies: List<PLProMovieItem>? = null,
    @JsonProperty("categories") val categories: List<PLProMovieCategory>? = null
)

data class PLProMovieCategory(
    @JsonProperty("a") val id: Any? = null,
    @JsonProperty("b") val name: String? = null
)

data class PLProMovieItem(
    @JsonProperty("a") val id: Any? = null,
    @JsonProperty("b") val name: String? = null,
    @JsonProperty("c") val poster: String? = null,
    @JsonProperty("f") val year: String? = null,
    @JsonProperty("g") val categoryIds: List<Any>? = null,
    @JsonProperty("l") val quality: String? = null
)

data class PLProMovieDetails(
    @JsonProperty("a") val id: Any? = null,
    @JsonProperty("b") val name: String? = null,
    @JsonProperty("c") val poster: String? = null,
    @JsonProperty("e") val plot: String? = null,
    @JsonProperty("f") val releaseDate: String? = null
)

data class PLProSeriesRoot(
    @JsonProperty("series") val series: List<PLProSeriesItem>? = null,
    @JsonProperty("categories") val categories: List<PLProMovieCategory>? = null
)

data class PLProSeriesItem(
    @JsonProperty("a") val id: Any? = null,
    @JsonProperty("b") val name: String? = null,
    @JsonProperty("c") val poster: String? = null,
    @JsonProperty("d") val backdrop: String? = null,
    @JsonProperty("g") val categoryIds: List<Any>? = null
)

data class PLProSeriesDetails(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("seasonList") val seasonList: List<PLProSeason>? = null
)

data class PLProSeason(
    @JsonProperty("num") val num: Any? = null,
    @JsonProperty("episodes") val episodes: List<PLProEpisode>? = null
)

data class PLProEpisode(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("season") val season: Any? = null,
    @JsonProperty("episode") val episode: Any? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("num") val num: Any? = null
)

data class PLProLink(
    @JsonProperty("a") val url: String? = null,
    @JsonProperty("b") val language: String? = null,
    @JsonProperty("c") val quality: String? = null
)

class PLProProvider : MainAPI() {
    override var mainUrl = "https://plpro.org"
    override var name = "PLPro"
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

    private val userAgent = "PLPro/8"
    private val magmaUserAgent = "Magma Player/10"
    private val deviceId = "aabbccdd112233"
    private val authQuery = "username=p&password=p"
    private val streamServer = "https://tv.m3uts.xyz"

    private fun fixPoster(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        return if (poster.startsWith("http")) {
            poster
        } else {
            val clean = poster.removePrefix("/").removeSuffix(".jpg")
            "https://image.tmdb.org/t/p/w500/$clean.jpg"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()

        // 1. Live Channels
        try {
            val chRoot = app.get(
                "$mainUrl/channels?$authQuery",
                headers = mapOf("User-Agent" to userAgent)
            ).parsedSafe<PLProChannelRoot>()

            val channels = chRoot?.channels ?: emptyList()
            val categories = chRoot?.categories ?: emptyList()

            val topCats = listOf("Populares", "Nacionales", "Deportes", "Cine 24/7", "HD", "Infantiles", "Entretenimiento")
            for (catName in topCats) {
                val catObj = categories.find { it.name?.equals(catName, ignoreCase = true) == true }
                val catIdStr = catObj?.id?.toString()
                val filtered = if (catIdStr != null) {
                    channels.filter { ch -> ch.categoryIds?.any { it.toString() == catIdStr } == true }
                } else emptyList()

                if (filtered.isNotEmpty()) {
                    val searchResponses = filtered.map { ch ->
                        val id = ch.id?.toString() ?: ""
                        newLiveSearchResponse(
                            ch.name ?: "Canal",
                            "$mainUrl/live/$id",
                            TvType.Live
                        ) {
                            this.posterUrl = ch.icon
                        }
                    }
                    homePages.add(HomePageList("En Vivo: $catName", searchResponses, isHorizontalImages = true))
                }
            }
        } catch (_: Exception) {
        }

        // 2. Movies
        try {
            val movieRoot = app.get(
                "$mainUrl/movies?$authQuery",
                headers = mapOf("User-Agent" to userAgent)
            ).parsedSafe<PLProMovieRoot>()

            val movies = movieRoot?.movies ?: emptyList()
            if (movies.isNotEmpty()) {
                val recentMovies = movies.take(30).map { m ->
                    val id = m.id?.toString() ?: ""
                    newMovieSearchResponse(
                        m.name ?: "Película",
                        "$mainUrl/movie/$id",
                        TvType.Movie
                    ) {
                        this.posterUrl = fixPoster(m.poster)
                    }
                }
                homePages.add(HomePageList("Películas", recentMovies))
            }
        } catch (_: Exception) {
        }

        // 3. Series
        try {
            val seriesRoot = app.get(
                "$mainUrl/series?$authQuery",
                headers = mapOf("User-Agent" to userAgent)
            ).parsedSafe<PLProSeriesRoot>()

            val series = seriesRoot?.series ?: emptyList()
            if (series.isNotEmpty()) {
                val recentSeries = series.take(30).map { s ->
                    val id = s.id?.toString() ?: ""
                    newTvSeriesSearchResponse(
                        s.name ?: "Serie",
                        "$mainUrl/series/$id",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = fixPoster(s.poster)
                    }
                }
                homePages.add(HomePageList("Series", recentSeries))
            }
        } catch (_: Exception) {
        }

        if (homePages.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(homePages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()

        // Search live channels
        try {
            val chRoot = app.get(
                "$mainUrl/channels?$authQuery",
                headers = mapOf("User-Agent" to userAgent)
            ).parsedSafe<PLProChannelRoot>()

            val channels = chRoot?.channels ?: emptyList()
            results.addAll(
                channels.filter { it.name?.lowercase()?.contains(cleanQuery) == true }.map { ch ->
                    val id = ch.id?.toString() ?: ""
                    newLiveSearchResponse(
                        ch.name ?: "Canal",
                        "$mainUrl/live/$id",
                        TvType.Live
                    ) {
                        this.posterUrl = ch.icon
                    }
                }
            )
        } catch (_: Exception) {
        }

        // Search movies
        try {
            val movieRoot = app.get(
                "$mainUrl/movies?$authQuery",
                headers = mapOf("User-Agent" to userAgent)
            ).parsedSafe<PLProMovieRoot>()

            val movies = movieRoot?.movies ?: emptyList()
            results.addAll(
                movies.filter { it.name?.lowercase()?.contains(cleanQuery) == true }.take(25).map { m ->
                    val id = m.id?.toString() ?: ""
                    newMovieSearchResponse(
                        m.name ?: "Película",
                        "$mainUrl/movie/$id",
                        TvType.Movie
                    ) {
                        this.posterUrl = fixPoster(m.poster)
                    }
                }
            )
        } catch (_: Exception) {
        }

        // Search series
        try {
            val seriesRoot = app.get(
                "$mainUrl/series?$authQuery",
                headers = mapOf("User-Agent" to userAgent)
            ).parsedSafe<PLProSeriesRoot>()

            val series = seriesRoot?.series ?: emptyList()
            results.addAll(
                series.filter { it.name?.lowercase()?.contains(cleanQuery) == true }.take(25).map { s ->
                    val id = s.id?.toString() ?: ""
                    newTvSeriesSearchResponse(
                        s.name ?: "Serie",
                        "$mainUrl/series/$id",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = fixPoster(s.poster)
                    }
                }
            )
        } catch (_: Exception) {
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix("https://plpro.org").removePrefix("http://plpro.org").removePrefix(mainUrl).trimStart('/')

        when {
            path.startsWith("live/") -> {
                val streamId = path.removePrefix("live/").trim()
                return newMovieLoadResponse("Canal en Vivo", url, TvType.Live, "live:$streamId") {
                    this.plot = "Transmisión en vivo por PLPro"
                }
            }
            path.startsWith("movie/") -> {
                val movieId = path.removePrefix("movie/").trim()
                val details = app.get(
                    "$mainUrl/movies/$movieId?$authQuery",
                    headers = mapOf("User-Agent" to userAgent)
                ).parsedSafe<PLProMovieDetails>()

                val title = details?.name ?: "Película"
                val poster = fixPoster(details?.poster)

                return newMovieLoadResponse(title, url, TvType.Movie, "movie:$movieId") {
                    this.posterUrl = poster
                    this.plot = details?.plot
                }
            }
            path.startsWith("series/") -> {
                val seriesId = path.removePrefix("series/").trim()
                val details = app.get(
                    "$mainUrl/series/$seriesId?$authQuery",
                    headers = mapOf("User-Agent" to userAgent)
                ).parsedSafe<PLProSeriesDetails>() ?: return null

                val title = details.name ?: "Serie"
                val poster = fixPoster(details.poster)
                val backdrop = fixPoster(details.backdrop)
                val seasonList = details.seasonList ?: emptyList()
                val episodeList = mutableListOf<Episode>()

                seasonList.forEach { seasonObj ->
                    val seasonNum = seasonObj.num?.toString()?.toIntOrNull() ?: 1
                    val episodes = seasonObj.episodes ?: emptyList()
                    episodes.forEachIndexed { idx, ep ->
                        val epNum = ep.episode?.toString()?.toIntOrNull() ?: ep.num?.toString()?.toIntOrNull() ?: (idx + 1)
                        val epName = ep.name ?: "Episodio $epNum"
                        val epPlot = ep.overview
                        val epCover = fixPoster(ep.backdrop)
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
                    this.plot = details.overview
                }
            }
            else -> return null
        }
    }

    private val magmaGenUserAgent = "Dalvik/2.1.0 (Linux; U; Android 11; Mi A2 Lite Build/RD2A.211001.002)"

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
            data.startsWith("live:") -> {
                val streamId = data.substringAfter(":")
                val genResponse = app.post(
                    "$streamServer/stream/gen/$streamId",
                    data = mapOf(
                        "id" to streamId,
                        "cast" to "false",
                        "device" to deviceId,
                        "code" to ""
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "User-Agent" to magmaGenUserAgent
                    )
                ).text.trim()

                if (genResponse.isNotBlank()) {
                    val fullM3u8Url = if (genResponse.startsWith("http")) genResponse else "$streamServer$genResponse"
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
                            "$streamServer/",
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
                                this.referer = "$streamServer/"
                                this.headers = secureHeaders
                            }
                        )
                    }
                    return true
                }
            }
            data.startsWith("movie:") -> {
                val movieId = data.substringAfter(":")
                val links = app.get(
                    "$mainUrl/movies/$movieId/links?$authQuery",
                    headers = mapOf("User-Agent" to userAgent)
                ).parsedSafe<Array<PLProLink>>()?.toList() ?: emptyList()

                links.amap { linkObj ->
                    val linkUrl = linkObj.url ?: return@amap
                    resolveEmbedLink(linkUrl, linkObj.quality, linkObj.language, subtitleCallback, callback)
                }
                return true
            }
            data.startsWith("episode:") -> {
                val parts = data.split(":")
                if (parts.size >= 4) {
                    val seriesId = parts[1]
                    val season = parts[2]
                    val epNum = parts[3]

                    val links = app.get(
                        "$mainUrl/series/$seriesId/links/$season/$epNum?$authQuery",
                        headers = mapOf("User-Agent" to userAgent)
                    ).parsedSafe<Array<PLProLink>>()?.toList() ?: emptyList()

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
