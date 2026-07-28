package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class SeriesflixProvider : MainAPI() {
    override var mainUrl = "https://seriesflixhd.ink"
    override var name = "Seriesflix"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/series-online/", "Series"),
            Pair("$mainUrl/genero/accion/", "Acción"),
            Pair("$mainUrl/genero/ciencia-ficcion/", "Ciencia ficción"),
        )
        urls.amap { (url, name) ->
            val soup = app.get(url).document
            val home = soup.select("article.TPost.B").map {
                val title = it.selectFirst("h2.Title")!!.text()
                val link = it.selectFirst("a")!!.attr("href")
                val img = it.selectFirst("img")!!.attr("data-src").let { src ->
                    if (src.startsWith("//")) "https:$src" else src
                }
                newTvSeriesSearchResponse(
                    title,
                    link,
                    TvType.TvSeries,
                ){
                    this.posterUrl = img
                }
            }

            items.add(HomePageList(name, home))
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("article.TPost.B").mapNotNull { item ->
            val href = item.selectFirst("a")!!.attr("href")
            val img = item.selectFirst("figure img")
            val poster = img?.attr("data-src")?.let { src ->
                if (src.startsWith("//")) "https:$src" else src
            }
            val name = item.selectFirst("h2.Title")!!.text()
            val parent = item.parent()?.parent()
            val isMovie = parent?.hasClass("type-movies") == true || href.contains("/pelicula/")
            if (isMovie) {
                newMovieSearchResponse(name, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val type = if (url.contains("/movies/")) TvType.Movie else TvType.TvSeries

        val document = app.get(url).document

        val title = document.selectFirst("h1.Title")!!.text()
        val descRegex = Regex("(Recuerda.*Seriesflix.)")
        val descipt = document.selectFirst("div.Description > p")!!.text().replace(descRegex, "")
        val year = document.selectFirst("span.Date")?.text()
        // ?: does not work
        val duration = try {
            document.selectFirst("span.Time")!!.text()
        } catch (e: Exception) {
            null
        }
        val poster = document.selectFirst("img.TPostBg")?.attr("src")?.let { src ->
            if (src.startsWith("//")) "https:$src" else src
        }

        if (type == TvType.TvSeries) {
            val list = ArrayList<Pair<Int, String>>()

            document.select("main > section.SeasonBx > div > div.Title > a").forEach { element ->
                val season = element.selectFirst("> span")?.text()?.toIntOrNull()
                val href = element.attr("href")
                if (season != null && season > 0 && !href.isNullOrBlank()) {
                    list.add(Pair(season, fixUrl(href)))
                }
            }
            if (list.isEmpty()) throw ErrorLoadingException("No Seasons Found")

            val episodeList = ArrayList<Episode>()

            list.amap { (seasonInt, seasonUrl) ->
                val seasonDocument = app.get(seasonUrl).document
                val episodes = seasonDocument.select("table > tbody > tr")
                if (episodes.isNotEmpty()) {
                    episodes.forEach { episode ->
                        val epNum = episode.selectFirst("> td > span.Num")?.text()?.toIntOrNull()
                        val epthumb = episode.selectFirst("img")?.attr("data-src")?.let { src ->
                            if (src.startsWith("//")) "https:$src" else src
                        }
                        val aName = episode.selectFirst("> td.MvTbTtl > a")
                        val name = aName!!.text()
                        val href = aName.attr("href")
                        //val date = episode.selectFirst("> td.MvTbTtl > span")?.text()
                        episodeList.add(
                            newEpisode(href) {
                                this.name = name
                                this.season = seasonInt
                                this.episode = epNum
                                this.posterUrl = fixUrlNull(epthumb)
                                //addDate(date)
                            }
                        )
                    }
                }
            }
        return newTvSeriesLoadResponse(
                title,
                url,
                type,
                episodeList
            ){
                this.posterUrl = fixUrlNull(poster)
                this.year = year?.toIntOrNull()
                this.plot = descipt
//                this.rating = rating
            }
        } else {
            return newMovieLoadResponse(
                title,
                url,
                type,
                url
            ) {
                posterUrl = fixUrlNull(poster)
                this.year = year?.toIntOrNull()
                this.plot = descipt
//                this.rating = rating
                addDuration(duration)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("li div.Button.sgty").amap {
            val encodedlink = it.attr("data-url")
            var decodelink = base64Decode(encodedlink)
            val lang = it.selectFirst("span:not(.nmopt)")?.ownText()?.trim() ?: "Server"

            if (decodelink.contains("nupload.top")) {
                val uploadDoc = app.get(decodelink).document
                val iframeSrc = uploadDoc.selectFirst("iframe")?.attr("src")?.let { src ->
                    if (src.startsWith("//")) "https:$src" else src
                }
                if (iframeSrc != null) decodelink = iframeSrc
            }

            loadExtractor(decodelink, data, subtitleCallback) { link ->
                CoroutineScope(Dispatchers.IO).launch {
                    callback.invoke(newExtractorLink(link.source, "$lang - ${link.name}", link.url) {
                        this.quality = link.quality
                        this.type = link.type
                        this.referer = link.referer
                        this.headers = link.headers
                    })
                }
            }
        }

        return true
    }
}
