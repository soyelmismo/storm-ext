package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.Calendar
import java.util.Collections

data class NetflixLatamData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

data class TmdbNetflixResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
) {
    val isTv get() = name != null || mediaType == "tv"
    val displayTitle get() = title ?: originalTitle ?: name ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbNetflixPageResult(
    @JsonProperty("results") val results: List<TmdbNetflixResult>? = null,
)

data class TmdbNetflixDetails(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("genres") val genres: List<TmdbNetflixGenre>? = null,
) {
    val displayTitle get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbNetflixGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class NetflixCrossMetaData(
    @JsonProperty("isSuccess") val isSuccess: Boolean,
    @JsonProperty("sources") val sources: List<NetflixCrossSource>? = null,
)

data class NetflixCrossSource(
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("dataUrl") val dataUrl: String,
)

/**
 * Netflix LATAM: meta provider for discovering Netflix content available in
 * Mexico/LATAM in Spanish. On load, it searches other installed providers
 * (same language) for the title and, if found, delegates playback to them.
 * Movies get a Play button via CrossMetaData; TV series get the matched
 * provider's episodes list. If no match is found, the detail shows metadata
 * only (comingSoon) with a search button via QuickSearch.
 */
class NetflixCatalogoLatamProvider : MainAPI() {
    override var name = "Netflix LATAM"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "mx"
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val hasMainPage = true

    private fun filterName(name: String): String {
        return Regex("""[^a-zA-Z0-9-]""").replace(name, "")
    }

    private val validApis
        get() = apis.filter { it.lang == this.lang && it::class != this::class }

    private val apiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val apiBase = "https://api.themoviedb.org/3"

    // Netflix provider ID on TMDB, Mexico watch region
    private val netflixProviderId = "8"
    private val watchRegion = "MX"

    // Netflix network ID for originals
    private val netflixNetworkId = "213"

    // Minimum votes to filter junk
    private val minVotes = 50

    // Per-genre section configuration
    private data class GenreSection(val id: Int, val sortBy: String, val minVotes: Boolean)

    private val genreSections = mapOf(
        "accion" to GenreSection(28, "popularity.desc", false),
        "drama" to GenreSection(18, "vote_average.desc", true),
        "comedia" to GenreSection(35, "popularity.desc", false),
        "thriller" to GenreSection(53, "vote_average.desc", true),
        "misterio" to GenreSection(9648, "vote_average.desc", true),
        "scifi" to GenreSection(878, "popularity.desc", false),
        "fantasia" to GenreSection(14, "popularity.desc", false),
        "horror" to GenreSection(27, "popularity.desc", false),
        "documental" to GenreSection(99, "popularity.desc", false),
        "animacion" to GenreSection(16, "popularity.desc", false),
        "aventura" to GenreSection(12, "popularity.desc", false),
        "romance" to GenreSection(10749, "vote_average.desc", true),
    )

    // Deduplication across main page sections
    private val minSectionResults = 12
    private val seenWindowMs = 30L * 60 * 1000
    private val seenMainPageKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private var seenWindowStart = System.currentTimeMillis()

    private fun mainPageKey(result: TmdbNetflixResult): String =
        "${result.isTv}-${result.id ?: result.displayTitle}"

    private fun dedupeMainPage(raw: List<TmdbNetflixResult>): List<TmdbNetflixResult> {
        val now = System.currentTimeMillis()
        if (now - seenWindowStart > seenWindowMs) {
            seenMainPageKeys.clear()
            seenWindowStart = now
        }
        val fresh = mutableListOf<TmdbNetflixResult>()
        val repeats = mutableListOf<TmdbNetflixResult>()
        for (result in raw) {
            if (mainPageKey(result) in seenMainPageKeys) {
                repeats.add(result)
            } else {
                seenMainPageKeys.add(mainPageKey(result))
                fresh.add(result)
            }
        }
        val fill = (minSectionResults - fresh.size).coerceAtLeast(0)
        return fresh + repeats.take(fill)
    }

    override val mainPage = listOf(
        mainPage("popular", "Netflix Popular", false),
        mainPage("top", "Mejor Calificadas", false),
        mainPage("nuevos", "Estrenos", false),
        mainPage("originales", "Netflix Originales", false),
        mainPage("kdrama", "K-Drama", false),
        mainPage("anime", "Anime", false),
        mainPage("accion", "Acción", false),
        mainPage("drama", "Drama", false),
        mainPage("comedia", "Comedia", false),
        mainPage("thriller", "Thriller", false),
        mainPage("misterio", "Misterio", false),
        mainPage("scifi", "Ciencia Ficción", false),
        mainPage("fantasia", "Fantasía", false),
        mainPage("horror", "Horror", false),
        mainPage("documental", "Documentales", false),
        mainPage("animacion", "Animación", false),
        mainPage("aventura", "Aventura", false),
        mainPage("romance", "Romance", false),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val horizontal = false

        val raw = when (request.data) {
            "popular" ->
                discoverMovies(page, "popularity.desc") +
                    discoverSeries(page, "popularity.desc")

            "top" ->
                discoverMovies(page, "vote_average.desc", minVotes = true) +
                    discoverSeries(page, "vote_average.desc", minVotes = true)

            "nuevos" ->
                discoverMovies(page, "primary_release_date.desc", recent = true) +
                    discoverSeries(page, "first_air_date.desc", recent = true)

            "originales" ->
                discoverSeries(page, "popularity.desc", originals = true)

            "accion", "drama", "comedia", "thriller", "misterio",
            "scifi", "fantasia", "horror", "documental", "animacion",
            "aventura", "romance" -> {
                val config = genreSections[request.data] ?: return newHomePageResponse(
                    HomePageList(request.name, emptyList(), true),
                    hasNext = false
                )
                genreMovies(page, config) + genreSeries(page, config)
            }

            "kdrama" -> discoverKoreanSeries(page, "popularity.desc")

            "anime" -> discoverAnime(page, "popularity.desc")

            else -> emptyList()
        }

        val results = dedupeMainPage(raw).mapNotNull { it.toNetflixSearchResponse(horizontal) }

        return newHomePageResponse(
            HomePageList(request.name, results, horizontal),
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<NetflixLatamData>(url) ?: return null
        val id = data.id ?: return null

        val details = if (data.isTv) {
            parseJson<TmdbNetflixDetails>(tmdbGet("/tv/$id"))
        } else {
            parseJson<TmdbNetflixDetails>(tmdbGet("/movie/$id"))
        }

        val title = details.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val type = if (data.isTv) TvType.TvSeries else TvType.Movie
        val year = data.year ?: details.year
        val poster = getImageUrl(details.posterPath)
        val backdrop = getImageUrl(details.backdropPath, "w780")
        val score = Score.from10(details.voteAverage)
        val tags = details.genres?.mapNotNull { it.name }

        // Search other providers for this title
        val matchName = filterName(title)
        val searchYear = year

        data class SearchMatch(val providerName: String, val loaded: LoadResponse)

        val matches = validApis.amap { api ->
            try {
                val searchResult = api.search(title, 1) ?: return@amap null
                val matched = searchResult.items.firstOrNull {
                    filterName(it.name).equals(matchName, ignoreCase = true)
                            && (it as? MovieSearchResponse)?.year?.let { y -> searchYear == null || y == searchYear }
                        ?: (it as? TvSeriesSearchResponse)?.year?.let { y -> searchYear == null || y == searchYear }
                        ?: true
                } ?: return@amap null
                val loaded = api.load(matched.url) ?: return@amap null
                SearchMatch(api.name, loaded)
            } catch (e: Exception) {
                logError(e)
                null
            }
        }.filterNotNull()

        if (matches.isEmpty()) {
            return if (data.isTv) {
                newTvSeriesLoadResponse(title, url, type, episodes = emptyList()) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                }
            } else {
                newMovieLoadResponse(title, url, type, dataUrl = "") {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                    this.duration = details.runtime?.let { it * 60 }
                }
            }
        }

        val first = matches.first()

        return when (val loaded = first.loaded) {
            is MovieLoadResponse -> {
                val movieMatches = matches.filter { it.loaded is MovieLoadResponse }
                val crossData = NetflixCrossMetaData(
                    true,
                    movieMatches.map { NetflixCrossSource(it.providerName, (it.loaded as MovieLoadResponse).dataUrl) }
                ).toJson()
                newMovieLoadResponse(title, url, type, dataUrl = crossData) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                    this.duration = details.runtime?.let { it * 60 }
                }
            }

            is TvSeriesLoadResponse -> {
                val providerName = first.providerName
                val wrappedEpisodes = loaded.episodes.map { ep ->
                    val wrapped = NetflixCrossSource(providerName, ep.data).toJson()
                    @Suppress("DEPRECATION_ERROR")
                    Episode(
                        data = wrapped,
                        name = ep.name,
                        season = ep.season,
                        episode = ep.episode,
                        posterUrl = ep.posterUrl,
                        score = ep.score,
                        description = ep.description,
                        date = ep.date,
                        runTime = ep.runTime,
                    )
                }
                newTvSeriesLoadResponse(title, url, type, episodes = wrappedEpisodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                }
            }

            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        tryParseJson<NetflixCrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false
            metaData.sources?.amap { source ->
                getApiFromNameNull(source.apiName)?.let {
                    try {
                        it.loadLinks(source.dataUrl, isCasting, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            return true
        }
        tryParseJson<NetflixCrossSource>(data)?.let { source ->
            getApiFromNameNull(source.apiName)?.let {
                try {
                    it.loadLinks(source.dataUrl, isCasting, subtitleCallback, callback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return true
        }
        return false
    }

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): String {
        return app.get(
            url = "$apiBase$path",
            params = buildMap {
                put("api_key", apiKey)
                put("language", "es-MX")
                putAll(params)
            },
        ).text
    }

    private suspend fun discoverMovies(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
        originals: Boolean = false,
    ): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/movie",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    if (recent) put("primary_release_date.gte", "${currentYear()}-01-01")
                    put("vote_count.gte", if (minVotes) "200" else "$minVotes")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun discoverSeries(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
        originals: Boolean = false,
    ): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    if (originals) put("with_networks", netflixNetworkId)
                    if (recent) put("first_air_date.gte", "${currentYear()}-01-01")
                    put("vote_count.gte", if (minVotes) "100" else "20")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun discoverKoreanSeries(
        page: Int,
        sortBy: String,
    ): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    put("with_original_language", "ko")
                    put("vote_count.gte", "20")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun discoverAnime(
        page: Int,
        sortBy: String,
    ): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    put("with_genres", "16")
                    put("with_original_language", "ja")
                    put("vote_count.gte", "20")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun genreMovies(page: Int, config: GenreSection): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/movie",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    put("with_genres", "${config.id}")
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    put("vote_count.gte", if (config.minVotes) "200" else "$minVotes")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun genreSeries(page: Int, config: GenreSection): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    put("with_genres", "${config.id}")
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    put("vote_count.gte", if (config.minVotes) "100" else "20")
                }
            )
        ).results ?: emptyList()
    }

    private fun TmdbNetflixResult.toNetflixSearchResponse(
        preferLandscape: Boolean = false
    ): SearchResponse? {
        val display = displayTitle
        if (display.isBlank()) return null
        val id = this.id ?: return null

        val data = NetflixLatamData(id, isTv, display, year).toJson()

        val poster = if (preferLandscape) {
            getImageUrl(backdropPath ?: posterPath, "w780")
        } else {
            getImageUrl(posterPath)
        }

        return if (isTv) {
            newTvSeriesSearchResponse(display, data, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = this@toNetflixSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        } else {
            newMovieSearchResponse(display, data, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = this@toNetflixSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        }
    }

    private fun getImageUrl(link: String?, size: String = "w500"): String? {
        link ?: return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$size$link" else link
    }

    private fun currentYear(): Int {
        return Calendar.getInstance().get(Calendar.YEAR)
    }
}
