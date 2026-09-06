// use an integer for version numbers
version = 1

cloudstream {
    language = "es"
    // All of these properties are optional, you can safely remove them

    description = "Películas, series y animes en español latino y castellano de SeriesKao"
    authors = listOf("soyelmismo")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
    )

    iconUrl = "https://serieskao.top/themes/serieskao/alphabet-k.png"
}
