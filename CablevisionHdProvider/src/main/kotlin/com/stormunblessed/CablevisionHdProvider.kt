package com.stormunblessed

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URL


class CablevisionHdProvider : MainAPI() {

    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "CablevisionHd"
    override var lang = "mx"

    override val hasQuickSearch = true
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Live,
    )

    private fun decodeBase64UntilUnchanged(encodedString: String): String {
        var decodedString = encodedString
        var previousDecodedString = ""
        while (decodedString != previousDecodedString) {
            previousDecodedString = decodedString
            decodedString = try {
                val decodedBytes = Base64.decode(decodedString, Base64.DEFAULT)
                String(decodedBytes)
            } catch (e: IllegalArgumentException) {
                break
            }
        }
        return decodedString
    }

    val nowAllowed = setOf(
        "Únete al chat",
        "Donar con Paypal",
        "Lizard Premium",
        "Vuelvete Premium (No ADS)",
        "Únete a Whatsapp",
        "Únete a Telegram",
        "¿Nos invitas el cafe?",
        "Mundo Latam",
    )

    val deportesCat = setOf(
        "TUDN",
        "WWE",
        "Afizzionados",
        "Gol Perú",
        "Gol TV",
        "TNT SPORTS",
        "Fox Sports Premium",
        "TYC Sports",
        "Movistar Deportes (Perú)",
        "Movistar La Liga",
        "Movistar Liga De Campeones",
        "Dazn F1",
        "Dazn La Liga",
        "Bein La Liga",
        "Bein Sports Extra",
        "Directv Sports",
        "Directv Sports 2",
        "Directv Sports Plus",
        "Espn Deportes",
        "Espn Extra",
        "Espn Premium",
        "Espn",
        "Espn 2",
        "Espn 3",
        "Espn 4",
        "Espn Mexico",
        "Espn 2 Mexico",
        "Espn 3 Mexico",
        "Fox Deportes",
        "Fox Sports",
        "Fox Sports 2",
        "Fox Sports 3",
        "Fox Sports Mexico",
        "Fox Sports 2 Mexico",
        "Fox Sports 3 Mexico",
    )

    val entretenimientoCat = setOf(
        "Telefe",
        "El Trece",
        "Televisión Pública",
        "Telemundo Puerto rico",
        "Univisión",
        "Univisión Tlnovelas",
        "Pasiones",
        "Caracol",
        "RCN",
        "Latina",
        "America TV",
        "Willax TV",
        "ATV",
        "Las Estrellas",
        "Tl Novelas",
        "Galavision",
        "Azteca 7",
        "Azteca Uno",
        "Canal 5",
        "Distrito Comedia",
    )

    val noticiasCat = setOf(
        "Telemundo 51",
    )

    val peliculasCat = setOf(
        "Movistar Accion",
        "Movistar Drama",
        "Universal Channel",
        "TNT",
        "TNT Series",
        "Star Channel",
        "Star Action",
        "Star Series",
        "Cinemax",
        "Space",
        "Syfy",
        "Warner Channel",
        "Warner Channel (México)",
        "Cinecanal",
        "FX",
        "AXN",
        "AMC",
        "Studio Universal",
        "Multipremier",
        "Golden",
        "Golden Plus",
        "Golden Edge",
        "Golden Premier",
        "Golden Premier 2",
        "Sony",
        "DHE",
        "NEXT HD",
    )

    val infantilCat = setOf(
        "Cartoon Network",
        "Tooncast",
        "Cartoonito",
        "Disney Channel",
        "Disney JR",
        "Nick",
    )

    val educacionCat = setOf(
        "Discovery Channel",
        "Discovery World",
        "Discovery Theater",
        "Discovery Science",
        "Discovery Familia",
        "History",
        "History 2",
        "Animal Planet",
        "Nat Geo",
        "Nat Geo Mundo",
    )

    val dos47Cat = setOf(
        "24/7",
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")

    private fun channelText(el: org.jsoup.nodes.Element): String {
        val pText = el.selectFirst("p")?.text()?.trim() ?: el.text().trim()
        return pText.replace("EN VIVO", "", ignoreCase = true).trim()
    }

    private fun buildChannel(el: org.jsoup.nodes.Element): LiveSearchResponse? {
        val title = channelText(el)
        if (title.isBlank() || nowAllowed.any { title.contains(it, ignoreCase = true) }) return null
        val img = el.selectFirst("img")?.attr("src") ?: ""
        val link = fixUrl(el.attr("href"))
        if (link.isBlank() || link == mainUrl || link.endsWith("/redes/")) return null
        return newLiveSearchResponse(title, link, TvType.Live) {
            this.posterUrl = fixUrl(img)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/parrilla-directo.php", headers = headers).document
        val allChannels = doc.select("a.channel-card").mapNotNull { el ->
            buildChannel(el)
        }.distinctBy { it.url }

        val categoryMap = mapOf(
            "Deportes" to deportesCat,
            "Entretenimiento" to entretenimientoCat,
            "Noticias" to noticiasCat,
            "Películas" to peliculasCat,
            "Infantil" to infantilCat,
            "Educación" to educacionCat,
            "24/7" to dos47Cat,
        )

        val items = ArrayList<HomePageList>()
        for ((catName, catSet) in categoryMap) {
            val filtered = allChannels.filter { ch ->
                catSet.any { ch.name.contains(it, ignoreCase = true) }
            }
            if (filtered.isNotEmpty()) {
                items.add(HomePageList(catName, filtered, isHorizontalImages = true))
            }
        }

        items.add(HomePageList("Todos los Canales", allChannels, isHorizontalImages = true))

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/parrilla-directo.php", headers = headers).document
        return doc.select("a.channel-card").mapNotNull { el ->
            val title = channelText(el)
            if (title.isBlank()) return@mapNotNull null
            if (!title.contains(query, ignoreCase = true)) return@mapNotNull null
            if (nowAllowed.any { title.contains(it, ignoreCase = true) }) return@mapNotNull null
            buildChannel(el)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val poster = doc.selectFirst("div.info-logo img, .channel-logo img, img[src*=imge]")?.attr("src") ?: ""
        val title = doc.selectFirst("head meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - CABLEVISION")?.replace("▷", "")?.trim()
            ?: "Canal en Vivo"
        val desc = doc.selectFirst("head meta[property='og:description']")?.attr("content")
            ?: "Transmisión en vivo por Cablevision HD"

        return newMovieLoadResponse(
            title,
            url, TvType.Live, url
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        val optionLinks = doc.select("a.option, a[href*=\"/stream\"], a[href*=\"stream\"]").mapNotNull {
            val link = it.attr("href").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
            val name = it.text().ifBlank { "Opción" }
            fixUrl(link) to name
        }.toMutableList()

        if (optionLinks.isEmpty()) {
            doc.select("iframe[src*=\"stream\"], iframe[src*=\"core.php\"]").forEachIndexed { idx, ifr ->
                val src = ifr.attr("src").takeIf { s -> s.isNotBlank() } ?: return@forEachIndexed
                optionLinks.add(fixUrl(src) to "Opción ${idx + 1}")
            }
        }

        optionLinks.distinctBy { it.first }.amap { (streamLink, name) ->
            try {
                val streamPage = app.get(
                    streamLink,
                    referer = data,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Referer" to data
                    )
                ).document
                val iframeSrc = streamPage.selectFirst("iframe")?.attr("src") ?: return@amap
                val fixedIframe = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else fixUrl(iframeSrc)

                val finalPage = app.get(
                    fixedIframe,
                    referer = streamLink,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Referer" to streamLink
                    )
                ).document

                val finalHtml = finalPage.html()
                val extractedUrls = mutableSetOf<String>()

                // 1. Universal regex for playlist.php or .m3u8 URLs across scripts and HTML
                val urlRegex = """(https?:\\?/\\?/[^"'<>\s]+\.(?:m3u8|php\?id=[^"'<>\s]+))""".toRegex()
                urlRegex.findAll(finalHtml).forEach { match ->
                    val clean = match.groupValues[1].replace("\\/", "/").replace("\\:", ":")
                        .substringBefore("\"").substringBefore("'").substringBefore(";").trim()
                    if (clean.isNotBlank()) {
                        extractedUrls.add(clean)
                    }
                }

                // 2. Specific pattern fallback (Unpacker / JWPlayer / Base64)
                finalPage.select("script").forEach { scriptTag ->
                    val script = scriptTag.html()
                    when {
                        script.contains("function(p,a,c,k,e,d)") -> {
                            val jsUnpacker = JsUnpacker(script)
                            if (jsUnpacker.detect()) {
                                val unpacked = jsUnpacker.unpack() ?: ""
                                val regex = """MARIOCSCryptOld\("(.*?)"\)""".toRegex()
                                val match = regex.find(unpacked)
                                val hash = match?.groupValues?.get(1) ?: ""
                                val extractedurl = decodeBase64UntilUnchanged(hash)
                                if (extractedurl.isNotBlank()) {
                                    extractedUrls.add(extractedurl)
                                }
                            }
                        }
                        script.contains("jwplayer.key = '") || script.contains("setupPlayer(\"") -> {
                            val url = script.substringAfter("setupPlayer(\"").substringBefore("\");").trim()
                            if (url.isNotBlank()) {
                                extractedUrls.add(url)
                            }
                        }
                        script.contains("var playbackURL = ") -> {
                            script.substringAfter("atob(\"").substringBefore("\")").let {
                                val extractedurl = decodeBase64UntilUnchanged(it)
                                if (extractedurl.isNotBlank()) {
                                    extractedUrls.add(extractedurl)
                                }
                            }
                        }
                    }
                }

                // 3. Emit links for all found stream URLs
                extractedUrls.forEach { streamUrl ->
                    if (streamUrl.contains(".m3u8") || streamUrl.contains("playlist.php")) {
                        val m3u8Links = try {
                            M3u8Helper.generateM3u8(
                                this.name,
                                streamUrl,
                                fixedIframe,
                                headers = mapOf("Referer" to fixedIframe, "User-Agent" to userAgent)
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
                                    name,
                                    streamUrl,
                                    referer = fixedIframe,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                    } else {
                        callback(newExtractorLink(this.name, name, streamUrl))
                    }
                }
            } catch (_: Exception) {
            }
        }
        return true
    }

    fun getBaseUrl(urlString: String): String {
        val url = URL(urlString)
        return "${url.protocol}://${url.host}"
    }

    fun getHostUrl(urlString: String): String {
        val url = URL(urlString)
        return url.host
    }
}
