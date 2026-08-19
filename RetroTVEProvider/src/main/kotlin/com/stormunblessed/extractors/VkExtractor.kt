package com.stormunblessed.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class VkExtractor : ExtractorApi() {
    override var name = "VK"
    override var mainUrl = "https://vk.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.replace("vkvideo.ru", "vk.com")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://retrotve.com/")
        )
        val html = app.get(fixedUrl, headers = headers).text
        val cacheMatch = Regex(""""apiPrefetchCache":\s*(\[\{.*?\}\])\s*\}""").find(html)
        if (cacheMatch != null) {
            val json = cacheMatch.groupValues[1]
            val items = tryParseJson<List<VkCacheItem>>(json)
            items?.forEach { item ->
                item.response?.items?.forEach { video ->
                    video.files?.forEach { (qualityKey, videoUrl) ->
                        if (qualityKey.startsWith("mp4_")) {
                            val qualNum = qualityKey.substringAfter("mp4_").toIntOrNull() ?: Qualities.Unknown.value
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name $qualityKey",
                                    videoUrl,
                                ) {
                                    this.referer = fixedUrl
                                    this.quality = qualNum
                                    this.headers = mapOf("Referer" to fixedUrl)
                                }
                            )
                        } else if (qualityKey == "hls_ondemand" || qualityKey == "hls") {
                            M3u8Helper.generateM3u8(
                                name,
                                videoUrl,
                                fixedUrl,
                                headers = mapOf("Referer" to fixedUrl)
                            ).forEach(callback)
                        }
                    }
                }
            }
        }
    }
}

class VkVideoRuExtractor : VkExtractor() {
    override var mainUrl = "https://vkvideo.ru"
}

data class VkCacheItem(
    @JsonProperty("response") val response: VkResponse? = null
)

data class VkResponse(
    @JsonProperty("items") val items: List<VkVideoItem>? = null
)

data class VkVideoItem(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("files") val files: Map<String, String>? = null
)
