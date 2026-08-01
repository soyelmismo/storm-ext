package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.util.Calendar
import java.util.Collections

/**
 * Datos serializados que se guardan en cada [SearchResponse] del proveedor.
 * El titulo es el original (en ingles), que es el termino que usa el boton de
 * busqueda de la barra superior del detalle (QuickSearch) para buscar fuentes
 * en el resto de proveedores instalados.
 */
data class CatalogoData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

data class TmdbCatalogoResult(
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

data class TmdbCatalogoPageResult(
    @JsonProperty("results") val results: List<TmdbCatalogoResult>? = null,
)

data class TmdbCatalogoDetails(
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
    @JsonProperty("genres") val genres: List<TmdbCatalogoGenre>? = null,
) {
    val displayTitle get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbCatalogoGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

/**
 * Catalogo Infantil: proveedor meta de descubrimiento de contenido infantil y
 * familiar (clasificacion mexicana AA). No aloja enlaces propios: al abrir un item
 * se muestra un detalle con metadata (poster, sinopsis, rating) y sin boton Play;
 * la busqueda de fuentes se hace desde la lupa de la barra superior (QuickSearch),
 * que usa el titulo localizado de la tarjeta.
 */
class CatalogoInfantilProvider : MainAPI() {
    override var name = "Catálogo Infantil"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "mx"
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
    )
    override val hasMainPage = true

    private val apiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val apiBase = "https://api.themoviedb.org/3"

    // Solo clasificacion mexicana AA (menores de 7 anos): el filtro exacto
    // `certification` evita los titulos sin certificacion MX registrada y, al
    // no incluir la "A", excluye contenido no infantil que si la tiene
    // (ej. La Momia, Friends, Jesus 1979).
    private val kidsCertifications = listOf("AA")
    private val certificationCountry = "MX"

    // Generos aptos para series infantiles: Familia, Ninos y Animacion.
    // Coma (,) en with_genres de TMDB significa AND: sirve para series, donde
    // casi todo el contenido infantil combina los tres generos.
    private val tvKidsGenres = "10751,10762,16"

    // Para peliculas se usa pipe (|) = OR: casi ninguna pelicula tiene los tres
    // generos a la vez (AND devuelve 0 resultados). Con OR + AA se descartan
    // titulos AA no infantiles (ej. documentales, dramas, conciertos).
    private val movieKidsGenres = "10751|10762|16"

    // Votos minimos para peliculas: descarta titulos AA con 0-1 votos que son
    // basura o estrenos regionales desconocidos (ej. "Maruchan con Huevo").
    // El contenido infantil real (Toy Story, Inside Out) acumula miles de votos.
    private val movieMinVotes = 10

    // Configuracion por seccion de genero: se diversifica el orden para que cada
    // fila muestre contenido distinto en vez de repetir la misma popularidad.
    private data class GenreSection(val id: Int, val sortBy: String, val minVotes: Boolean)

    private val genreSections = mapOf(
        "animacion" to GenreSection(16, "popularity.desc", false),
        "familia" to GenreSection(10751, "vote_average.desc", true),
        "ninos" to GenreSection(10762, "primary_release_date.desc", false),
        "aventura" to GenreSection(12, "popularity.desc", false),
        "fantasia" to GenreSection(14, "vote_average.desc", true),
        "comedia" to GenreSection(35, "primary_release_date.desc", false),
    )

    // Deduplicacion entre secciones del main page: los titulos ya mostrados no se
    // repiten en las siguientes filas. Se reinicia pasada una ventana de tiempo.
    private val minSectionResults = 12
    private val seenWindowMs = 30L * 60 * 1000
    private val seenMainPageKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private var seenWindowStart = System.currentTimeMillis()

    private fun mainPageKey(result: TmdbCatalogoResult): String =
        "${result.isTv}-${result.id ?: result.displayTitle}"

    private fun dedupeMainPage(raw: List<TmdbCatalogoResult>): List<TmdbCatalogoResult> {
        val now = System.currentTimeMillis()
        if (now - seenWindowStart > seenWindowMs) {
            seenMainPageKeys.clear()
            seenWindowStart = now
        }
        val fresh = mutableListOf<TmdbCatalogoResult>()
        val repeats = mutableListOf<TmdbCatalogoResult>()
        for (result in raw) {
            if (mainPageKey(result) in seenMainPageKeys) {
                repeats.add(result)
            } else {
                seenMainPageKeys.add(mainPageKey(result))
                fresh.add(result)
            }
        }
        // nunca dejar una fila casi vacia: se rellenan los huecos con repetidos
        val fill = (minSectionResults - fresh.size).coerceAtLeast(0)
        return fresh + repeats.take(fill)
    }

    override val mainPage = listOf(
        mainPage("recomendados", "Recomendados", true),
        mainPage("peliculas", "Películas Populares", true),
        mainPage("series", "Series Populares", true),
        mainPage("tendencias", "Tendencias", true),
        mainPage("estrenos", "Estrenos", false),
        mainPage("mejor", "Mejor Calificadas", false),
        mainPage("animacion", "Animación", true),
        mainPage("familia", "Familia", true),
        mainPage("ninos", "Niños", true),
        mainPage("aventura", "Aventura", true),
        mainPage("fantasia", "Fantasía", true),
        mainPage("comedia", "Comedia", true),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Siempre se muestran tarjetas horizontales con imagen apaisada (backdrop).
        val horizontal = true

        val raw = when (request.data) {
            "recomendados" ->
                discoverMovies(page, "popularity.desc") +
                    discoverSeries(page, "popularity.desc")

            "peliculas" -> discoverMovies(page, "vote_average.desc", minVotes = true)
            "series" -> discoverSeries(page, "vote_average.desc", minVotes = true)

            "tendencias" ->
                discoverMovies(page, "popularity.desc", recent = true) +
                    discoverSeries(page, "popularity.desc", recent = true)

            "estrenos" ->
                discoverMovies(page, "primary_release_date.desc") +
                    discoverSeries(page, "first_air_date.desc")

            "mejor" ->
                discoverMovies(page, "popularity.desc") +
                    discoverSeries(page, "popularity.desc")

            "animacion", "familia", "ninos", "aventura", "fantasia", "comedia" -> {
                val config = genreSections[request.data] ?: return newHomePageResponse(
                    HomePageList(request.name, emptyList(), true),
                    hasNext = false
                )
                genreMovies(page, config) + genreSeries(page, config)
            }

            else -> emptyList()
        }

        val results = dedupeMainPage(raw).mapNotNull { it.toCatalogoSearchResponse(horizontal) }

        return newHomePageResponse(
            HomePageList(request.name, results, horizontal),
            hasNext = results.isNotEmpty()
        )
    }

    // La busqueda propia del proveedor esta deshabilitada deliberadamente:
    // El catalogo solo descubre contenido en el main page. La busqueda de fuentes se
    // hace desde el detalle con la lupa (QuickSearch de la app).
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<CatalogoData>(url) ?: return null
        val id = data.id ?: return null

        val details = if (data.isTv) {
            parseJson<TmdbCatalogoDetails>(tmdbGet("/tv/$id"))
        } else {
            parseJson<TmdbCatalogoDetails>(tmdbGet("/movie/$id"))
        }

        val title = details.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val type = if (data.isTv) TvType.TvSeries else TvType.Movie
        val year = data.year ?: details.year
        val poster = getImageUrl(details.posterPath)
        val backdrop = getImageUrl(details.backdropPath, "w780")
        val score = Score.from10(details.voteAverage)
        val tags = details.genres?.mapNotNull { it.name }

        // dataUrl vacio / sin episodios => comingSoon = true: la app oculta el boton
        // Play y muestra solo la metadata. Las fuentes se buscan con la lupa del top-bar.
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
    ): List<TmdbCatalogoResult> {
        return kidsCertifications.flatMap { cert ->
            parseJson<TmdbCatalogoPageResult>(
                tmdbGet(
                    "/discover/movie",
                    buildMap {
                        put("page", "$page")
                        put("sort_by", sortBy)
                        put("with_genres", movieKidsGenres)
                        put("certification_country", certificationCountry)
                        put("certification", cert)
                        if (recent) put("primary_release_date.gte", "${currentYear()}-01-01")
                        put("vote_count.gte", if (minVotes) "150" else "$movieMinVotes")
                    }
                )
            ).results ?: emptyList()
        }
    }

    private suspend fun discoverSeries(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
    ): List<TmdbCatalogoResult> {
        return kidsCertifications.flatMap { cert ->
            parseJson<TmdbCatalogoPageResult>(
                tmdbGet(
                    "/discover/tv",
                    buildMap {
                        put("page", "$page")
                        put("sort_by", sortBy)
                        put("with_genres", tvKidsGenres)
                        put("certification_country", certificationCountry)
                        put("certification", cert)
                        if (recent) put("first_air_date.gte", "${currentYear()}-01-01")
                        if (minVotes) put("vote_count.gte", "100")
                    }
                )
            ).results ?: emptyList()
        }
    }

    private suspend fun genreMovies(page: Int, config: GenreSection): List<TmdbCatalogoResult> {
        return kidsCertifications.flatMap { cert ->
            parseJson<TmdbCatalogoPageResult>(
                tmdbGet(
                    "/discover/movie",
                    buildMap {
                        put("page", "$page")
                        put("sort_by", config.sortBy)
                        put("with_genres", "${config.id}")
                        put("certification_country", certificationCountry)
                        put("certification", cert)
                        put("vote_count.gte", if (config.minVotes) "150" else "$movieMinVotes")
                    }
                )
            ).results ?: emptyList()
        }
    }

    private suspend fun genreSeries(page: Int, config: GenreSection): List<TmdbCatalogoResult> {
        return kidsCertifications.flatMap { cert ->
            parseJson<TmdbCatalogoPageResult>(
                tmdbGet(
                    "/discover/tv",
                    buildMap {
                        put("page", "$page")
                        put("sort_by", config.sortBy)
                        // El filtro de certificacion de TMDB no aplica para TV, asi
                        // que la seccion exige el genero de la fila ADEMAS de los
                        // generos infantiles (AND) para excluir sitcoms no aptas
                        // (ej. Friends, Dos Hombres y Medio).
                        put("with_genres", "${config.id},10751,10762,16")
                        put("certification_country", certificationCountry)
                        put("certification", cert)
                        if (config.minVotes) put("vote_count.gte", "100")
                    }
                )
            ).results ?: emptyList()
        }
    }

    private fun TmdbCatalogoResult.toCatalogoSearchResponse(
        preferLandscape: Boolean = false
    ): SearchResponse? {
        val display = displayTitle
        if (display.isBlank()) return null
        val id = this.id ?: return null

        // El nombre de la tarjeta es el titulo localizado (es-MX): evita mostrar
        // titulos en su idioma original (ej. anime en japones/chino) en el main
        // page y coincide con lo que muestra el detalle. Es tambien el termino que
        // usa QuickSearch en los proveedores mx/es instalados.
        val data = CatalogoData(id, isTv, display, year).toJson()

        // En filas horizontales se usa la imagen apaisada (backdrop) si existe;
        // en caso contrario se mantiene el poster vertical.
        val poster = if (preferLandscape) {
            getImageUrl(backdropPath ?: posterPath, "w780")
        } else {
            getImageUrl(posterPath)
        }

        return if (isTv) {
            newTvSeriesSearchResponse(display, data, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = this@toCatalogoSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        } else {
            newMovieSearchResponse(display, data, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = this@toCatalogoSearchResponse.year
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
