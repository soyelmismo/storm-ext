package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.Calendar
import java.util.Collections

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetflixLatamData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbNetflixPageResult(
    @JsonProperty("results") val results: List<TmdbNetflixResult>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbTranslationsContainer(
    @JsonProperty("translations") val translations: Array<TmdbTranslationItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbTranslationItem(
    @JsonProperty("iso_639_1") val iso6391: String? = null,
    @JsonProperty("data") val data: TmdbTranslationData? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbTranslationData(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
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
    @JsonProperty("translations") val translations: TmdbTranslationsContainer? = null,
) {
    val displayTitle get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    val englishTitle: String?
        get() = translations?.translations?.firstOrNull { it.iso6391 == "en" }?.let {
            it.data?.title?.takeIf { t -> t.isNotBlank() } ?: it.data?.name?.takeIf { n -> n.isNotBlank() }
        }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbNetflixGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetflixCrossMetaData(
    @JsonProperty("isSuccess") val isSuccess: Boolean,
    @JsonProperty("netMirrorId") val netMirrorId: String? = null,
    @JsonProperty("sources") val sources: List<NetflixCrossSource>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetflixCrossSource(
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("dataUrl") val dataUrl: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetMirrorSearchItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("t") val t: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetMirrorSearchResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("searchResult") val searchResult: Array<NetMirrorSearchItem>? = null,
    @JsonProperty("error") val error: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetMirrorSeasonItem(
    @JsonProperty("s") val s: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("selected") val selected: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetMirrorEpisodeItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("t") val t: String? = null,
    @JsonProperty("ep") val ep: Any? = null,
    @JsonProperty("ep_desc") val epDesc: String? = null,
    @JsonProperty("info") val info: Array<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetMirrorPostResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("main_id") val mainId: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("season") val season: Array<NetMirrorSeasonItem>? = null,
    @JsonProperty("episodes") val episodes: Array<NetMirrorEpisodeItem?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetMirrorEpisodesResponse(
    @JsonProperty("episodes") val episodes: Array<NetMirrorEpisodeItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetMirrorPlayerResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("video_link") val videoLink: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("ep") val ep: Any? = null,
    @JsonProperty("ep_title") val epTitle: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetMirrorTokenCheckResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("tv_api") val tvApi: String? = null,
    @JsonProperty("token_hash") val tokenHash: String? = null,
)

/**
 * Netflix LATAM: meta provider for discovering Netflix content available in
 * Mexico/LATAM in Spanish. Powered primarily by direct Full HD 1080p multi-audio
 * streams via NetMirror NewTV API, with seamless cross-provider search fallback.
 */
class CatalogoNetflixProvider : MainAPI() {
    override var name = "Catálogo Netflix"
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

    // Many providers append a year to the displayed title (e.g. "Deseo (2026)").
    // Strip a trailing year before comparing so those names still match.
    private fun comparableName(name: String): String {
        return filterName(Regex("""\s*\(?\d{4}\)?\s*$""").replace(name, ""))
    }

    private val validApis
        get() = apis.filter { it.lang == this.lang && it::class != this::class }

    // Resolving a title searches the other installed providers. CloudStream
    // calls load() for the home page preview (hero) row, so a slow load()
    // stalls the whole main page. All providers are therefore searched in
    // parallel and the whole search is capped by a short global deadline: the
    // worst case is a few seconds instead of one round of up to 10s per batch
    // of providers. The slowest providers are cut off individually, and only
    // COMPLETE results are cached per title so a truncated search is not
    // memoized as "no match" (a real match would stay hidden for the whole
    // cache window). A repeat load() is instant.
    private val providerSearchTimeoutMs = 5_000L
    private val findMatchesDeadlineMs = 6_000L
    private val maxMatchesForFallback = 4

    // Short-lived cache for TMDB responses so that reloading the home page
    // (CloudStream refetches every section at once) is instant instead of
    // hammering TMDB with ~36 parallel requests.
    private val tmdbCacheMs = 15L * 60 * 1000

    private data class TmdbCacheEntry(val timestamp: Long, val body: String)

    private val tmdbCache = Collections.synchronizedMap(HashMap<String, TmdbCacheEntry>())

    // Short-lived cache for the provider-search outcome per title, so the search
    // is only paid for the first time a title is opened (a second open of the
    // same title, e.g. from another row or after the app's own load cache
    // evicts it, returns instantly).
    private val searchCacheMs = 10L * 60 * 1000

    private data class SearchCacheEntry(val timestamp: Long, val matches: List<SearchMatch>)

    private val searchCache = Collections.synchronizedMap(HashMap<String, SearchCacheEntry>())

    private class SearchMatch(val providerName: String, val loaded: LoadResponse)

    // A match must be of the same media type (movie vs series) as the item
    // being loaded, otherwise a same-named movie could be returned for a
    // series and vice versa.
    private fun SearchResponse.typeOk(isTv: Boolean): Boolean {
        val t = this.type
        return if (isTv) {
            t?.let { it != TvType.Movie && it != TvType.AnimeMovie } ?: (this is TvSeriesSearchResponse)
        } else {
            t?.let { it == TvType.Movie || it == TvType.AnimeMovie } ?: (this is MovieSearchResponse)
        }
    }

    private fun SearchResponse.yearOk(searchYear: Int?): Boolean {
        val y = when (this) {
            is MovieSearchResponse -> this.year
            is TvSeriesSearchResponse -> this.year
            else -> null
        } ?: return true
        return searchYear == null || y == searchYear
    }

    private suspend fun searchProvider(
        api: MainAPI,
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): SearchMatch? {
        return try {
            withTimeout(providerSearchTimeoutMs) {
                val searchResult = api.search(title, 1) ?: return@withTimeout null
                val candidates = searchResult.items.filter { it.typeOk(isTv) }
                if (candidates.isEmpty()) return@withTimeout null

                // Prefer an exact title + year match; otherwise fall back to a
                // same-title match, since many providers report a different or no
                // year for the same movie (e.g. new releases, regional titles).
                val matched = candidates.firstOrNull {
                    comparableName(it.name).equals(matchName, ignoreCase = true) && it.yearOk(searchYear)
                } ?: candidates.firstOrNull {
                    comparableName(it.name).equals(matchName, ignoreCase = true)
                } ?: return@withTimeout null

                val loaded = api.load(matched.url) ?: return@withTimeout null
                SearchMatch(api.name, loaded)
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private suspend fun findMatches(
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): List<SearchMatch> {
        val key = "$isTv|$matchName|$searchYear"
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.timestamp < searchCacheMs) return it.matches }

        // All providers are hit in parallel so that the wall-clock time is the
        // slowest provider, not the sum of all of them. The global deadline
        // bounds the whole search (e.g. when the title matches nothing and
        // every provider has to answer first).
        var complete = true
        val matches = withTimeoutOrNull(findMatchesDeadlineMs) {
            validApis.amap { api ->
                searchProvider(api, title, matchName, searchYear, isTv)
            }.filterNotNull()
        } ?: run {
            complete = false
            emptyList()
        }

        val result = matches.take(maxMatchesForFallback)

        // Cache only a complete result: a run cut short by the deadline (some
        // slow provider not yet answered) is not memoized as "no match", so a
        // real match is not hidden for the whole cache window.
        if (complete) {
            searchCache[key] = SearchCacheEntry(now, result)
            if (searchCache.size > 250) {
                searchCache.clear()
            }
        }
        return result
    }

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
        mainPage("top10global", "Top 10 Global", false),
        mainPage("top10mexico", "Top 10 México", false),
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
            "top10global" -> top10Section("https://www.netflix.com/tudum/top10")

            "top10mexico" -> top10Section("https://www.netflix.com/tudum/top10/mexico")

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

        val isTop10 = request.data == "top10global" || request.data == "top10mexico"
        val results = if (isTop10) {
            raw.mapNotNull { it.toNetflixSearchResponse(horizontal) }
        } else {
            dedupeMainPage(raw).mapNotNull { it.toNetflixSearchResponse(horizontal) }
        }

        return newHomePageResponse(
            HomePageList(request.name, results, horizontal),
            hasNext = !isTop10 && results.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return null
    }

    private var cachedApiBase = "https://tv.imgcdn.kim"
    private var cachedUserToken = "871f7dc1424bf3bf3da69138f19a5064::9932d537d3094c6923d45e03bab51378::1790101705::ni::99"
    private var lastTokenCheck = 0L
    private val tokenCheckIntervalMs = 60L * 60 * 1000

    private suspend fun getNetMirrorConfig(): Pair<String, String> {
        val now = System.currentTimeMillis()
        if (now - lastTokenCheck < tokenCheckIntervalMs) {
            return cachedApiBase to cachedUserToken
        }
        try {
            val checkRes = withTimeoutOrNull(2500L) {
                app.get("https://mobiledetects.com/checknewtv.php").text.let { tryParseJson<NetMirrorTokenCheckResponse>(it) }
            }
            if (checkRes?.code == 200) {
                checkRes.tvApi?.let {
                    val decoded = String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)).trim()
                    if (decoded.startsWith("http")) cachedApiBase = decoded
                }
                checkRes.tokenHash?.let {
                    val decoded = String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)).trim()
                    if (decoded.isNotBlank()) cachedUserToken = decoded
                }
                lastTokenCheck = now
            }
        } catch (_: Exception) {}
        return cachedApiBase to cachedUserToken
    }

    private fun netMirrorHeaders(token: String): Map<String, String> {
        return mapOf(
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache",
            "Expires" to "0",
            "X-Requested-With" to "NetmirrorNewTV v1.0",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
            "Accept" to "application/json, text/plain, */*",
            "Ott" to "nf",
            "Usertoken" to token
        )
    }

    private fun cleanTitleForMatch(s: String): String {
        return Regex("""[^a-zA-Z0-9]""").replace(s.lowercase(), "")
    }

    private suspend fun findNetMirrorPost(
        titles: List<String>,
        isTv: Boolean
    ): Pair<String, NetMirrorPostResponse>? {
        val (apiBase, token) = getNetMirrorConfig()
        val headers = netMirrorHeaders(token)

        for (title in titles) {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "$apiBase/newtv/search.php?s=$encoded"
            val searchJson = try {
                app.get(searchUrl, headers = headers, timeout = 4_000L).text
            } catch (_: Exception) {
                null
            } ?: continue

            val searchResponse = tryParseJson<NetMirrorSearchResponse>(searchJson) ?: continue
            val items = searchResponse.searchResult ?: continue
            if (items.isEmpty()) continue

            val cleanTarget = cleanTitleForMatch(title)
            val filtered = items.filter { item ->
                val cleanItem = cleanTitleForMatch(item.t ?: "")
                cleanItem == cleanTarget || cleanItem.contains(cleanTarget) || cleanTarget.contains(cleanItem)
            }.sortedBy { item ->
                val cleanItem = cleanTitleForMatch(item.t ?: "")
                if (cleanItem == cleanTarget) 0 else 1
            }

            for (cand in filtered.take(2)) {
                val cId = cand.id ?: continue
                val postUrl = "$apiBase/newtv/post.php?id=$cId"
                val postJson = try {
                    app.get(postUrl, headers = headers, timeout = 4_000L).text
                } catch (_: Exception) {
                    null
                } ?: continue

                val post = tryParseJson<NetMirrorPostResponse>(postJson) ?: continue
                if (post.status == "error") continue

                val hasSeasons = !post.season.isNullOrEmpty()
                val pType = post.type?.lowercase()
                if (isTv) {
                    if (hasSeasons || pType == "t") {
                        return cId to post
                    }
                } else {
                    if (pType == "m" || !hasSeasons) {
                        return cId to post
                    }
                }
            }
        }
        return null
    }

    private data class NetMirrorEpisode(
        val season: Int,
        val episode: Int,
        val name: String,
        val description: String?,
        val id: String?
    )

    private suspend fun fetchNetMirrorEpisodes(post: NetMirrorPostResponse): List<NetMirrorEpisode> {
        val (apiBase, token) = getNetMirrorConfig()
        val headers = netMirrorHeaders(token)
        val episodesList = mutableListOf<NetMirrorEpisode>()
        val seasons = post.season

        if (!seasons.isNullOrEmpty()) {
            val seasonEps = seasons.mapIndexed { idx, sItem ->
                val sNum = Regex("""Season\s*(\d+)""").find(sItem.s ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                val sId = sItem.id ?: return@mapIndexed null
                sNum to sId
            }.filterNotNull().amap { (sNum, sId) ->
                val epJson = try {
                    app.get("$apiBase/newtv/episodes.php?id=$sId", headers = headers, timeout = 5_000L).text
                } catch (_: Exception) { null }
                val eps = epJson?.let { tryParseJson<NetMirrorEpisodesResponse>(it)?.episodes } ?: emptyArray()
                sNum to eps
            }

            for ((sNum, eps) in seasonEps) {
                for ((epIdx, ep) in eps.withIndex()) {
                    val epNum = ep.ep?.toString()?.toIntOrNull() ?: (epIdx + 1)
                    val epTitle = ep.t?.takeIf { it.isNotBlank() } ?: "Episodio $epNum"
                    episodesList.add(
                        NetMirrorEpisode(
                            season = sNum,
                            episode = epNum,
                            name = epTitle,
                            description = ep.epDesc,
                            id = ep.id
                        )
                    )
                }
            }
        } else if (!post.episodes.isNullOrEmpty()) {
            post.episodes.forEachIndexed { epIdx, ep ->
                if (ep != null) {
                    val epNum = ep.ep?.toString()?.toIntOrNull() ?: (epIdx + 1)
                    val epTitle = ep.t?.takeIf { it.isNotBlank() } ?: "Episodio $epNum"
                    episodesList.add(
                        NetMirrorEpisode(
                            season = 1,
                            episode = epNum,
                            name = epTitle,
                            description = ep.epDesc,
                            id = ep.id
                        )
                    )
                }
            }
        }
        return episodesList
    }

    private suspend fun loadNetMirrorStreams(
        id: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (apiBase, token) = getNetMirrorConfig()
        val headers = netMirrorHeaders(token)
        val playerUrl = "$apiBase/newtv/player.php?id=$id"
        val playerJson = try {
            app.get(playerUrl, headers = headers, timeout = 6_000L).text
        } catch (e: Exception) {
            logError(e)
            null
        } ?: return false

        val playerRes = tryParseJson<NetMirrorPlayerResponse>(playerJson) ?: return false
        val videoLink = playerRes.videoLink?.takeIf { it.isNotBlank() } ?: return false
        val referer = playerRes.referer?.takeIf { it.isNotBlank() } ?: "https://net52.cc"

        var emitted = false
        try {
            val m3u8Links = M3u8Helper.generateM3u8(
                this.name,
                videoLink,
                referer,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
                )
            )
            if (m3u8Links.isNotEmpty()) {
                m3u8Links.forEach { link ->
                    emitted = true
                    callback(link)
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (!emitted) {
            callback(
                newExtractorLink(
                    this.name,
                    "${this.name} NetMirror Full HD",
                    videoLink,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
                    )
                }
            )
            emitted = true
        }
        return emitted
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<NetflixLatamData>(url) ?: return null
        val id = data.id ?: return null

        val details = if (data.isTv) {
            parseJson<TmdbNetflixDetails>(tmdbGet("/tv/$id", mapOf("append_to_response" to "translations")))
        } else {
            parseJson<TmdbNetflixDetails>(tmdbGet("/movie/$id", mapOf("append_to_response" to "translations")))
        }

        val title = details.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val type = if (data.isTv) TvType.TvSeries else TvType.Movie
        val year = data.year ?: details.year
        val poster = getImageUrl(details.posterPath)
        val backdrop = getImageUrl(details.backdropPath, "w780")
        val score = Score.from10(details.voteAverage)
        val tags = details.genres?.mapNotNull { it.name }

        // Candidate titles for NetMirror (English title prioritized, then original title, then Spanish title)
        val titleCandidates = listOfNotNull(
            details.englishTitle,
            details.originalTitle ?: details.originalName,
            details.title ?: details.name,
        ).distinct().filter { it.isNotBlank() }

        // Primary: NetMirror NewTV resolution
        val netMirrorPost = findNetMirrorPost(titleCandidates, data.isTv)

        // Fallback: Cross-provider search on other installed providers
        val matchName = filterName(title)
        val searchYear = year
        val matches = findMatches(title, matchName, searchYear, data.isTv)

        if (data.isTv) {
            val seriesMatches = matches.filter { it.loaded is TvSeriesLoadResponse }

            if (netMirrorPost != null) {
                val nmEpisodes = fetchNetMirrorEpisodes(netMirrorPost.second)
                val wrappedEpisodes = nmEpisodes.map { ep ->
                    val sources = mutableListOf<NetflixCrossSource>()
                    seriesMatches.forEach { other ->
                        val otherLoaded = other.loaded as TvSeriesLoadResponse
                        otherLoaded.episodes.firstOrNull {
                            it.season == ep.season && it.episode == ep.episode
                        }?.let { sources.add(NetflixCrossSource(other.providerName, it.data)) }
                    }
                    val wrapped = NetflixCrossMetaData(
                        isSuccess = true,
                        netMirrorId = ep.id,
                        sources = sources.ifEmpty { null }
                    ).toJson()
                    @Suppress("DEPRECATION_ERROR")
                    Episode(
                        data = wrapped,
                        name = ep.name,
                        season = ep.season,
                        episode = ep.episode,
                        posterUrl = poster,
                        score = score,
                        description = ep.description,
                    )
                }
                return newTvSeriesLoadResponse(title, url, type, episodes = wrappedEpisodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                }
            }

            // Fallback to cross-providers if NetMirror didn't have the series
            if (matches.isNotEmpty() && matches.first().loaded is TvSeriesLoadResponse) {
                val first = matches.first()
                val loaded = first.loaded as TvSeriesLoadResponse
                val wrappedEpisodes = loaded.episodes.map { ep ->
                    val sources = mutableListOf(NetflixCrossSource(first.providerName, ep.data))
                    seriesMatches.forEach { other ->
                        if (other.providerName == first.providerName) return@forEach
                        val otherLoaded = other.loaded as TvSeriesLoadResponse
                        otherLoaded.episodes.firstOrNull {
                            it.season == ep.season && it.episode == ep.episode
                        }?.let { sources.add(NetflixCrossSource(other.providerName, it.data)) }
                    }
                    val wrapped = NetflixCrossMetaData(
                        isSuccess = true,
                        netMirrorId = null,
                        sources = sources
                    ).toJson()
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
                return newTvSeriesLoadResponse(title, url, type, episodes = wrappedEpisodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                }
            }

            return newTvSeriesLoadResponse(title, url, type, episodes = emptyList()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = details.overview
                this.score = score
                this.tags = tags
            }
        } else {
            // Movie
            val movieMatches = matches.filter { it.loaded is MovieLoadResponse }
            val fallbackSources = movieMatches.map {
                NetflixCrossSource(it.providerName, (it.loaded as MovieLoadResponse).dataUrl)
            }

            if (netMirrorPost != null) {
                val nmMovieId = netMirrorPost.second.mainId ?: netMirrorPost.first
                val crossData = NetflixCrossMetaData(
                    isSuccess = true,
                    netMirrorId = nmMovieId,
                    sources = fallbackSources.ifEmpty { null }
                ).toJson()
                return newMovieLoadResponse(title, url, type, dataUrl = crossData) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                    this.duration = details.runtime?.let { it * 60 }
                }
            }

            // Fallback to cross-providers if NetMirror didn't have the movie
            if (movieMatches.isNotEmpty()) {
                val crossData = NetflixCrossMetaData(
                    isSuccess = true,
                    netMirrorId = null,
                    sources = fallbackSources
                ).toJson()
                return newMovieLoadResponse(title, url, type, dataUrl = crossData) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                    this.duration = details.runtime?.let { it * 60 }
                }
            }

            return newMovieLoadResponse(title, url, type, dataUrl = "") {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            linksFound = true
            callback(link)
        }

        tryParseJson<NetflixCrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false

            // 1. Primary: NetMirror NewTV direct Full HD stream
            val netMirrorId = metaData.netMirrorId
            if (!netMirrorId.isNullOrBlank()) {
                try {
                    loadNetMirrorStreams(netMirrorId, subtitleCallback, wrappedCallback)
                } catch (e: Exception) {
                    logError(e)
                }
            }

            // 2. Fallback: Query other installed providers
            metaData.sources?.amap { source ->
                getApiFromNameNull(source.apiName)?.let {
                    try {
                        it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            return linksFound
        }

        tryParseJson<NetflixCrossSource>(data)?.let { source ->
            getApiFromNameNull(source.apiName)?.let {
                try {
                    it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return linksFound
        }
        return false
    }

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): String {
        val key = "$path${params.toSortedMap()}"
        val now = System.currentTimeMillis()
        tmdbCache[key]?.let { if (now - it.timestamp < tmdbCacheMs) return it.body }

        val body = app.get(
            url = "$apiBase$path",
            params = buildMap {
                put("api_key", apiKey)
                put("language", "es-MX")
                putAll(params)
            },
        ).text
        tmdbCache[key] = TmdbCacheEntry(now, body)
        return body
    }

    private suspend fun top10Section(url: String): List<TmdbNetflixResult> {
        return fetchTop10Titles(url).mapNotNull { title -> searchTop10Title(title) }
    }

    private suspend fun fetchTop10Titles(url: String): List<String> {
        return try {
            val doc = app.get(
                url,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
                referer = "https://www.netflix.com/",
            ).document
            doc.select("[data-uia=top10-table-row-title] button").mapNotNull {
                it.text().trim().takeIf { t -> t.isNotBlank() }
            }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    private suspend fun searchTop10Title(title: String): TmdbNetflixResult? {
        val movies = parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/search/movie",
                buildMap {
                    put("query", title)
                }
            )
        ).results.orEmpty()
        movies.firstOrNull()?.let { return it }

        val series = parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/search/tv",
                buildMap {
                    put("query", title)
                }
            )
        ).results.orEmpty()
        return series.firstOrNull()
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
