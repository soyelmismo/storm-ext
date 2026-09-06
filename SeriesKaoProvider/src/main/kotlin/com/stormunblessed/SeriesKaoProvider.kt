package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SeriesKaoProvider : MainAPI() {
    override var mainUrl = "https://serieskao.top"
    override var name = "SeriesKao"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "peliculas" to "Películas",
        "series" to "Series",
        "animes" to "Animes",
        "peliculas/populares" to "Películas Populares",
        "series/populares" to "Series Populares",
        "animes/populares" to "Animes Populares",
        "generos/dorama" to "Doramas",
        "generos/accion" to "Acción",
        "generos/animacion" to "Animación",
        "generos/comedia" to "Comedia",
        "generos/terror" to "Terror",
        "generos/ciencia-ficcion" to "Ciencia Ficción",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "$mainUrl/${request.data}${sep}page=$page"
        }
        val doc = app.get(url).document
        val items = doc.select("article.card").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a[aria-label='Siguiente']") != null ||
                doc.selectFirst("a.pagination__btn[href*='page=${page + 1}']") != null
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a.card__link") ?: selectFirst("a") ?: return null
        val href = linkEl.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fullUrl = fixUrl(href)
        val title = selectFirst(".card__title")?.text()?.trim()
            ?: selectFirst("figure.card__poster img")?.attr("alt")?.trim()
            ?: return null
        val poster = selectFirst("figure.card__poster img")?.attr("src")
        val type = when {
            href.contains("/pelicula/") -> TvType.Movie
            href.contains("/anime") -> TvType.Anime
            else -> TvType.TvSeries
        }
        val year = selectFirst(".card__badge--year")?.text()?.trim()?.toIntOrNull()
        return newMovieSearchResponse(title, fullUrl, type) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?s=$query").document
        return doc.select("article.card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = url.contains("/pelicula/")
        val title = doc.selectFirst("h1.detail-hero__title")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.removeSuffix(" - SeriesKao")?.trim()
            ?: "Sin título"
        val poster = doc.selectFirst("figure.detail-hero__poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val bgPoster = doc.selectFirst("div.detail-hero__bg img")?.attr("src")
        val description = doc.selectFirst("h2.detail-hero__desc")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year = doc.selectFirst("div.detail-hero__meta span:matches(^\\d{4}$)")?.text()?.toIntOrNull()
            ?: doc.selectFirst(".card__badge--year")?.text()?.toIntOrNull()
        val tags = doc.select("div.detail-hero__genres a.detail-hero__genre").map { it.text().trim() }
        val recommendations = doc.select("div.grid.grid--cards article.card").mapNotNull { it.toSearchResult() }

        val type = when {
            isMovie -> TvType.Movie
            url.contains("/anime") -> TvType.Anime
            else -> TvType.TvSeries
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val episodes = doc.select("div.seasons-section__content div.episodes-list").flatMap { seasonDiv ->
                val seasonNum = seasonDiv.id().removePrefix("season-").toIntOrNull() ?: 1
                seasonDiv.select("a.episode-item").mapNotNull { epLink ->
                    val epHref = epLink.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val epUrl = fixUrl(epHref)
                    val epNum = epLink.selectFirst(".episode-item__number")?.text()?.trim()?.toIntOrNull()
                    val epTitle = epLink.selectFirst(".episode-item__title")?.text()?.trim() ?: "Capítulo $epNum"
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    }
                }
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val serverUrls = doc.select("div.player-box__servers button.server-btn")
            .mapNotNull { it.attr("data-url").takeIf { u -> u.isNotBlank() } }
            .toMutableList()

        doc.selectFirst("iframe#player-iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let {
            if (!serverUrls.contains(it)) serverUrls.add(it)
        }

        serverUrls.amap { rawUrl ->
            val fullUrl = if (rawUrl.startsWith("/")) "$mainUrl$rawUrl" else rawUrl
            if (fullUrl.contains("/vidurl/")) {
                Embed69Extractor.load(
                    url = fullUrl,
                    referer = data,
                    providerName = this.name,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            } else {
                val fixed = Embed69Extractor.fixHostsLinks(fullUrl)
                loadExtractor(fixed, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
