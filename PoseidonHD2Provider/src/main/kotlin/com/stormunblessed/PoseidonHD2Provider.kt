package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

class PoseidonHD2Provider : MainAPI() {
    override var mainUrl = "https://www.poseidonhd2.co"
    override var name = "PoseidonHD2"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "" to "Inicio",
        "peliculas/estrenos" to "Películas: Estrenos",
        "peliculas/tendencias/dia" to "Películas: Tendencias Hoy",
        "peliculas/tendencias/semana" to "Películas: Tendencias Semana",
        "peliculas" to "Películas: Últimas publicadas",
        "series/estrenos" to "Series: Estrenos",
        "series/tendencias/dia" to "Series: Tendencias Hoy",
        "series/tendencias/semana" to "Series: Tendencias Semana",
        "series" to "Series: Últimas publicadas",
        "genero/accion" to "Acción",
        "genero/animacion" to "Animación",
        "genero/ciencia-ficcion" to "Ciencia Ficción",
        "genero/comedia" to "Comedia",
        "genero/drama" to "Drama",
        "genero/terror" to "Terror",
        "genero/suspense" to "Suspenso",
        "genero/fantasia" to "Fantasía",
        "genero/misterio" to "Misterio",
        "genero/romance" to "Romance",
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private inline fun <reified T> extractNextData(html: String): T? {
        val jsonString = html.substringAfter("<script id=\"__NEXT_DATA__\"", "")
            .substringAfter(">", "")
            .substringBefore("</script>", "")
            .trim()
            .removeSuffix(";")
        if (jsonString.isBlank()) return null
        return try {
            parseJson<NextData<T>>(jsonString).props?.pageProps
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<HomePageList>()
        val requestData = request.data.trim().removePrefix("/").removeSuffix("/")

        if (requestData.isEmpty()) {
            if (page > 1) return newHomePageResponse(homeItems, hasNext = false)
            val html = app.get(mainUrl, headers = mapOf("User-Agent" to userAgent)).text
            val data = extractNextData<PoseidonHomeProps>(html)

            data?.tabLastReleasedMovies?.let { list ->
                val items = list.mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) homeItems.add(HomePageList("Estrenos Películas", items))
            }
            data?.topMoviesDay?.let { list ->
                val items = list.mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) homeItems.add(HomePageList("Películas del Día", items))
            }
            data?.topMoviesWeek?.let { list ->
                val items = list.mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) homeItems.add(HomePageList("Tendencias de la Semana", items))
            }
            data?.series?.let { list ->
                val items = list.mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) homeItems.add(HomePageList("Series Destacadas", items))
            }
            data?.tabLastMovies?.let { list ->
                val items = list.mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) homeItems.add(HomePageList("Últimas Películas", items))
            }
            return newHomePageResponse(homeItems, hasNext = false)
        }

        val url = if (page <= 1) {
            "$mainUrl/$requestData"
        } else {
            "$mainUrl/$requestData/page/$page"
        }

        val html = app.get(url, headers = mapOf("User-Agent" to userAgent)).text
        val data = extractNextData<PoseidonListingProps>(html)
        val movies = data?.movies?.toList() ?: emptyList()
        val items = movies.mapNotNull { it.toSearchResponse() }
        val maxPages = data?.pages?.toString()?.toIntOrNull() ?: 1
        val hasNext = page < maxPages

        homeItems.add(HomePageList(request.name, items))
        return newHomePageResponse(homeItems, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val html = app.get(url, headers = mapOf("User-Agent" to userAgent)).text
        val data = extractNextData<PoseidonListingProps>(html)
        val movies = data?.movies?.toList() ?: return emptyList()
        return movies.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val html = app.get(cleanUrl, headers = mapOf("User-Agent" to userAgent)).text

        if (cleanUrl.contains("/serie/")) {
            val data = extractNextData<PoseidonSerieProps>(html) ?: return null
            val serie = data.thisSerie ?: return null
            val title = serie.titles?.name?.takeIf { it.isNotBlank() } ?: "Serie"
            val poster = serie.images?.poster
            val backdrop = serie.images?.backdrop
            val plot = serie.overview
            val year = serie.releaseDate?.substringBefore("-")?.toIntOrNull()
            val tags = serie.genres?.mapNotNull { it.name } ?: emptyList()
            val actors = serie.cast?.acting?.mapNotNull { it.name } ?: emptyList()

            val episodes = serie.seasons?.flatMap { season ->
                val sNum = season.number?.toString()?.toIntOrNull() ?: 1
                season.episodes?.mapNotNull { ep ->
                    val epNum = ep.number?.toString()?.toIntOrNull() ?: 1
                    val epTitle = ep.title?.takeIf { it.isNotBlank() } ?: "Episodio $epNum"
                    val slug = ep.url?.slug ?: ""
                    val epUrl = if (slug.startsWith("series/")) {
                        val parts = slug.removePrefix("series/").split("/")
                        if (parts.size >= 6) {
                            "$mainUrl/serie/${parts[0]}/${parts[1]}/temporada/${parts[3]}/episodio/${parts[5]}"
                        } else {
                            "$cleanUrl/temporada/$sNum/episodio/$epNum"
                        }
                    } else {
                        "$cleanUrl/temporada/$sNum/episodio/$epNum"
                    }
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = sNum
                        this.episode = epNum
                        this.posterUrl = ep.image
                    }
                } ?: emptyList()
            } ?: emptyList()

            return newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            val data = extractNextData<PoseidonMovieProps>(html) ?: return null
            val movie = data.thisMovie ?: return null
            val title = movie.titles?.name?.takeIf { it.isNotBlank() } ?: "Película"
            val poster = movie.images?.poster
            val backdrop = movie.images?.backdrop
            val plot = movie.overview
            val year = movie.releaseDate?.substringBefore("-")?.toIntOrNull()
            val runtime = movie.runtime?.toString()?.toIntOrNull()
            val tags = movie.genres?.mapNotNull { it.name } ?: emptyList()
            val actors = movie.cast?.acting?.mapNotNull { it.name } ?: emptyList()

            return newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.duration = runtime
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
        val cleanUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        val html = app.get(cleanUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text

        val videos: Map<String, Array<PoseidonServerItem>>? = if (cleanUrl.contains("/serie/")) {
            extractNextData<PoseidonEpisodeProps>(html)?.episode?.videos
        } else {
            extractNextData<PoseidonMovieProps>(html)?.thisMovie?.videos
        }

        if (videos == null || videos.isEmpty()) return false

        val linkRegex = Regex("""(?:var\s+url\s*=\s*['"]|window\.location\.href\s*=\s*['"]|<iframe[^>]+src=['"])(https?://[^'"]+)""")

        videos.forEach { (langKey, serverArray) ->
            val langLabel = when (langKey.lowercase()) {
                "latino" -> "Latino"
                "spanish" -> "Castellano"
                "english" -> "Subtitulado"
                else -> langKey.replaceFirstChar { it.uppercase() }
            }

            val servers = serverArray.toList()
            servers.amap { server ->
                val playerUrl = server.result ?: return@amap
                try {
                    val playerHtml = app.get(
                        playerUrl,
                        headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                    ).text

                    val destUrl = linkRegex.find(playerHtml)?.groupValues?.get(1) ?: return@amap
                    val fixedUrl = destUrl
                        .replace("streamwish.to", "hgplaycdn.com")
                        .replace("vidhidepro.com", "callistanise.com")
                        .replace("filelions.to", "callistanise.com")
                        .replace("voe.sx", "eugenemakedraw.com")
                        .replace("doodstream.com", "playmogo.com")

                    loadExtractor(fixedUrl, playerUrl, subtitleCallback) { link ->
                        CoroutineScope(Dispatchers.IO).launch {
                            callback(
                                newExtractorLink(
                                    source = this@PoseidonHD2Provider.name,
                                    name = "${link.name} [$langLabel]",
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
                } catch (_: Exception) {
                }
            }
        }

        return true
    }

    // ─── Modelos Jackson ───────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NextData<T>(
        @JsonProperty("props") val props: NextProps<T>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NextProps<T>(
        @JsonProperty("pageProps") val pageProps: T? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonHomeProps(
        @JsonProperty("tabLastMovies") val tabLastMovies: Array<PoseidonItem>? = null,
        @JsonProperty("tabTopMovies") val tabTopMovies: Array<PoseidonItem>? = null,
        @JsonProperty("tabLastReleasedMovies") val tabLastReleasedMovies: Array<PoseidonItem>? = null,
        @JsonProperty("topMoviesDay") val topMoviesDay: Array<PoseidonItem>? = null,
        @JsonProperty("topMoviesWeek") val topMoviesWeek: Array<PoseidonItem>? = null,
        @JsonProperty("series") val series: Array<PoseidonItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonListingProps(
        @JsonProperty("movies") val movies: Array<PoseidonItem>? = null,
        @JsonProperty("pages") val pages: Any? = null,
        @JsonProperty("page") val page: Any? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonMovieProps(
        @JsonProperty("thisMovie") val thisMovie: PoseidonMovieDetail? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonSerieProps(
        @JsonProperty("thisSerie") val thisSerie: PoseidonSerieDetail? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonEpisodeProps(
        @JsonProperty("episode") val episode: PoseidonEpisodeDetail? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonItem(
        @JsonProperty("titles") val titles: PoseidonTitles? = null,
        @JsonProperty("images") val images: PoseidonImages? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("TMDbId") val tmdbId: Any? = null,
        @JsonProperty("runtime") val runtime: Any? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("url") val url: PoseidonUrl? = null,
    )

    private fun PoseidonItem.toSearchResponse(): SearchResponse? {
        val title = titles?.name?.takeIf { it.isNotBlank() } ?: return null
        val slug = url?.slug?.trim() ?: return null
        val poster = images?.poster
        val isSeries = slug.startsWith("series/")
        val fullUrl = if (isSeries) {
            "$mainUrl/serie/${slug.removePrefix("series/")}"
        } else {
            "$mainUrl/pelicula/${slug.removePrefix("movies/")}"
        }
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie
        val year = releaseDate?.substringBefore("-")?.toIntOrNull()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fullUrl, tvType) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fullUrl, tvType) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonTitles(
        @JsonProperty("name") val name: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonImages(
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonUrl(
        @JsonProperty("slug") val slug: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonGenre(
        @JsonProperty("name") val name: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonCast(
        @JsonProperty("acting") val acting: Array<PoseidonActor>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonActor(
        @JsonProperty("name") val name: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonServerItem(
        @JsonProperty("cyberlocker") val cyberlocker: String? = null,
        @JsonProperty("result") val result: String? = null,
        @JsonProperty("quality") val quality: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonMovieDetail(
        @JsonProperty("TMDbId") val tmdbId: Any? = null,
        @JsonProperty("titles") val titles: PoseidonTitles? = null,
        @JsonProperty("images") val images: PoseidonImages? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Any? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("genres") val genres: Array<PoseidonGenre>? = null,
        @JsonProperty("cast") val cast: PoseidonCast? = null,
        @JsonProperty("videos") val videos: Map<String, Array<PoseidonServerItem>>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonSerieDetail(
        @JsonProperty("TMDbId") val tmdbId: Any? = null,
        @JsonProperty("titles") val titles: PoseidonTitles? = null,
        @JsonProperty("images") val images: PoseidonImages? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("genres") val genres: Array<PoseidonGenre>? = null,
        @JsonProperty("cast") val cast: PoseidonCast? = null,
        @JsonProperty("seasons") val seasons: Array<PoseidonSeason>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonSeason(
        @JsonProperty("number") val number: Any? = null,
        @JsonProperty("episodes") val episodes: Array<PoseidonEpisodeInSeason>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonEpisodeInSeason(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("TMDbId") val tmdbId: Any? = null,
        @JsonProperty("number") val number: Any? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("url") val url: PoseidonUrl? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PoseidonEpisodeDetail(
        @JsonProperty("TMDbId") val tmdbId: Any? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("number") val number: Any? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("videos") val videos: Map<String, Array<PoseidonServerItem>>? = null,
    )
}
