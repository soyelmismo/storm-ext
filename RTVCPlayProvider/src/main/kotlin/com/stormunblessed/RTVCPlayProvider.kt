package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

data class RtvcState(
    @JsonProperty("content") val content: RtvcContent? = null
)

data class RtvcContent(
    @JsonProperty("currentContent") val currentContent: RtvcCurrentContent? = null
)

data class RtvcCurrentContent(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("longDescription") val longDescription: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("unitary") val unitary: Boolean? = null,
    @JsonProperty("widgets") val widgets: List<RtvcWidget>? = null,
    @JsonProperty("video") val video: RtvcVideo? = null,
    @JsonProperty("resource") val resource: RtvcResource? = null,
    @JsonProperty("base_url_hls") val baseUrlHls: String? = null
)

data class RtvcResource(
    @JsonProperty("image") val image: RtvcImageContainer? = null
)

data class RtvcImageContainer(
    @JsonProperty("cover_desktop") val coverDesktop: RtvcImagePath? = null,
    @JsonProperty("cover_mobile") val coverMobile: RtvcImagePath? = null,
    @JsonProperty("poster") val poster: RtvcImagePath? = null,
    @JsonProperty("cover") val cover: RtvcImagePath? = null,
    @JsonProperty("banner") val banner: RtvcImagePath? = null,
    @JsonProperty("banner_mobile") val bannerMobile: RtvcImagePath? = null
) {
    fun getUrl(): String? = (poster?.path ?: coverDesktop?.path ?: cover?.path ?: banner?.path ?: coverMobile?.path ?: bannerMobile?.path)?.let {
        if (it.startsWith("//")) "https:$it" else it
    }
}

data class RtvcImagePath(
    @JsonProperty("path") val path: String? = null
)

data class RtvcVideo(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("assetid") val assetid: String? = null,
    @JsonProperty("duration") val duration: String? = null
)

data class RtvcWidget(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("title_admin") val titleAdmin: String? = null,
    @JsonProperty("contents") val contents: List<RtvcWidgetItem>? = null
)

data class RtvcWidgetItem(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("subtitle") val subtitle: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("subtitle_slug") val subtitleSlug: String? = null,
    @JsonProperty("content_type") val contentType: String? = null,
    @JsonProperty("is_unitary") val isUnitary: Boolean? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("chapter_number") val chapterNumber: Int? = null,
    @JsonProperty("duration") val duration: String? = null,
    @JsonProperty("image") val image: RtvcImageContainer? = null,
    @JsonProperty("contents") val contents: List<RtvcWidgetItem>? = null
)

class RTVCPlayProvider : MainAPI() {
    override var mainUrl = "https://www.rtvcplay.co"
    override var name = "RTVCPlay"
    override var lang = "co"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Documentary,
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "" to "Inicio",
        "series-ficcion" to "Series de Ficción",
        "series-documentales" to "Series Documentales",
        "peliculas-ficcion" to "Películas de Ficción",
        "peliculas-documentales" to "Películas Documentales",
        "cortometrajes-ficcion" to "Cortometrajes",
        "ninos" to "Infantil"
    )

    private fun extractState(html: String): RtvcState? {
        val startStr = "window.__RTVCPLAY_STATE__ = "
        val idx = html.indexOf(startStr)
        if (idx == -1) return null
        val after = html.substring(idx + startStr.length)
        val jsonStr = after.substringBefore("</script>").trim().removeSuffix(";")
        return try {
            parseJson<RtvcState>(jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    private fun RtvcWidgetItem.toSearchResponse(): SearchResponse? {
        val itemTitle = title?.takeIf { it.isNotBlank() } ?: subtitle?.takeIf { it.isNotBlank() } ?: return null
        val itemSlug = slug ?: subtitleSlug ?: return null
        val poster = image?.getUrl()
        val url = if (itemSlug.startsWith("http")) itemSlug else "$mainUrl$itemSlug"
        val unitary = isUnitary ?: (contentType == "video" || contentType == "movie")

        return if (unitary) {
            newMovieSearchResponse(itemTitle, url, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(itemTitle, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        val html = app.get(url).text
        val state = extractState(html) ?: return newHomePageResponse(request.name, emptyList())
        val widgets = state.content?.currentContent?.widgets ?: emptyList()

        if (request.data.isEmpty()) {
            val homePages = widgets.mapNotNull { widget ->
                val sectionTitle = widget.title?.takeIf { it.isNotBlank() } ?: widget.titleAdmin?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val contents = widget.contents ?: return@mapNotNull null
                if (contents.isEmpty()) return@mapNotNull null

                val items = contents.mapNotNull { it.toSearchResponse() }
                if (items.isEmpty()) return@mapNotNull null
                HomePageList(sectionTitle, items, isHorizontalImages = widget.type == "slider")
            }
            return newHomePageResponse(homePages, hasNext = false)
        } else {
            val allItems = widgets.flatMap { it.contents ?: emptyList() }.mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
            return newHomePageResponse(
                HomePageList(request.name, allItems, isHorizontalImages = false),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val sections = listOf("series-ficcion", "series-documentales", "peliculas-ficcion", "peliculas-documentales")
        val cleanQuery = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()

        for (section in sections) {
            try {
                val html = app.get("$mainUrl/$section").text
                val state = extractState(html) ?: continue
                val widgets = state.content?.currentContent?.widgets ?: emptyList()
                val matched = widgets.flatMap { it.contents ?: emptyList() }
                    .filter { (it.title?.lowercase()?.contains(cleanQuery) == true) || (it.subtitle?.lowercase()?.contains(cleanQuery) == true) }
                    .mapNotNull { it.toSearchResponse() }
                results.addAll(matched)
            } catch (_: Exception) {
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val state = extractState(html) ?: return null
        val current = state.content?.currentContent ?: return null

        val title = current.title ?: "RTVCPlay"
        val plot = current.description ?: current.longDescription
        val isUnitary = current.unitary ?: (current.video?.assetid != null && current.widgets?.none { it.type == "seasonList" } == true)
        val poster = current.resource?.image?.getUrl()

        if (isUnitary) {
            val assetId = current.video?.assetid ?: ""
            return newMovieLoadResponse(title, url, TvType.Movie, assetId) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val widgets = current.widgets ?: emptyList()
            val episodes = mutableListOf<Episode>()

            widgets.filter { it.type == "seasonList" }.forEach { seasonWidget ->
                val seasons = seasonWidget.contents ?: emptyList()
                seasons.forEachIndexed { sIdx, sMap ->
                    val seasonNum = sMap.season ?: (sIdx + 1)
                    val chapters = sMap.contents ?: emptyList()
                    chapters.forEachIndexed { eIdx, chMap ->
                        val epTitle = chMap.title ?: "Episodio ${eIdx + 1}"
                        val epSlug = chMap.slug ?: chMap.subtitleSlug ?: return@forEachIndexed
                        val epCover = chMap.image?.getUrl()
                        val epUrl = if (epSlug.startsWith("http")) epSlug else "$mainUrl$epSlug"

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = chMap.chapterNumber ?: (eIdx + 1)
                                this.posterUrl = epCover
                            }
                        )
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val assetId = if (data.startsWith("http")) {
            val html = app.get(data).text
            val state = extractState(html)
            state?.content?.currentContent?.video?.assetid ?: return false
        } else {
            data
        }

        if (assetId.isBlank()) return false
        val streamUrl = "https://streaming.rtvc.gov.co/RTVCPlay-vod/smil:$assetId.smil/playlist.m3u8"

        generateM3u8(
            name,
            streamUrl,
            "$mainUrl/"
        ).forEach(callback)

        return true
    }
}
