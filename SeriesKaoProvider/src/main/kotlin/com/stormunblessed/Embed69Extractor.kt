package com.stormunblessed

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@JsonIgnoreProperties(ignoreUnknown = true)
data class Embed69Server(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("type") val type: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Embed69ServersByLang(
    @JsonProperty("file_id") val fileId: Any? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: Array<Embed69Server>? = null,
    @JsonProperty("downloadEmbeds") val downloadEmbeds: Array<Embed69Server>? = null,
)

object Embed69Extractor {
    suspend fun load(
        url: String,
        referer: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val scriptContent = document.select("script")
            .firstOrNull { it.html().contains("dataLink = [") }?.html() ?: return

        val powChallenge = scriptContent.substringAfter("const POW_CHALLENGE = '", "").substringBefore("';")
        val powDifficulty = scriptContent.substringAfter("const POW_DIFFICULTY = ", "").substringBefore(";").trim().toIntOrNull() ?: 3
        val powSalt = scriptContent.substringAfter("const POW_SALT = '", "").substringBefore("';")

        if (powChallenge.isBlank() || powSalt.isBlank()) return

        val aesKey = deriveAesKey(powChallenge, powDifficulty, powSalt)
        val dataLinkJson = scriptContent.substringAfter("dataLink = ", "").substringBefore(";\n").substringBefore(";").trim()
        if (dataLinkJson.isBlank()) return

        val serverLangs = AppUtils.tryParseJson<Array<Embed69ServersByLang>>(dataLinkJson)?.toList() ?: return

        serverLangs.amap { langItem ->
            val lang = langItem.videoLanguage ?: "LAT"
            val embeds = (langItem.sortedEmbeds?.toList().orEmpty() + langItem.downloadEmbeds?.toList().orEmpty())
                .distinctBy { it.link }

            embeds.amap { server ->
                val encLink = server.link ?: return@amap
                val decrypted = decryptAES(encLink, aesKey) ?: return@amap
                val fixedUrl = fixHostsLinks(decrypted)
                loadSourceNameExtractor(
                    providerName = providerName,
                    language = lang,
                    serverName = server.servername ?: "",
                    url = fixedUrl,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }
    }

    fun deriveAesKey(challenge: String, difficulty: Int, salt: String): ByteArray {
        var nonce: Long = 0
        val digest = MessageDigest.getInstance("SHA-256")
        while (true) {
            val input = challenge + nonce
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            var startsWithZeroes = true
            for (i in 0 until difficulty) {
                val byteVal = (hash[i / 2].toInt() and 0xFF)
                val nibble = if (i % 2 == 0) (byteVal ushr 4) else (byteVal and 0x0F)
                if (nibble != 0) {
                    startsWithZeroes = false
                    break
                }
            }
            if (startsWithZeroes) {
                val keyInput = challenge + nonce + salt
                return digest.digest(keyInput.toByteArray(Charsets.UTF_8)).copyOfRange(0, 32)
            }
            nonce++
        }
    }

    fun decryptAES(encryptedBase64: String, aesKey: ByteArray): String? {
        return try {
            val raw = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)
            val secretKey = SecretKeySpec(aesKey, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadSourceNameExtractor(
        providerName: String,
        language: String,
        serverName: String,
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            val langLabel = when (language.uppercase()) {
                "LAT", "LATINO" -> "Latino"
                "ESP", "CASTELLANO" -> "Castellano"
                "SUB", "SUBTITULADO" -> "Subtitulado"
                else -> language
            }
            val hostLabel = when {
                link.name.isNotBlank() -> link.name
                serverName.isNotBlank() -> serverName.replaceFirstChar { it.uppercase() }
                else -> link.source
            }
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(
                    newExtractorLink(
                        source = providerName,
                        name = "$langLabel - $hostLabel",
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
    }

    fun fixHostsLinks(url: String): String {
        return url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com", "https://streamwish.to")
            .replaceFirst("https://cybervynx.com", "https://streamwish.to")
            .replaceFirst("https://dumbalag.com", "https://streamwish.to")
            .replaceFirst("https://morencius.com", "https://vidhidepro.com")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
            .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            .replaceFirst("https://lulu.st", "https://lulustream.com")
            .replaceFirst("https://uqload.io", "https://uqload.com")
            .replaceFirst("https://do7go.com", "https://dood.la")
    }
}
