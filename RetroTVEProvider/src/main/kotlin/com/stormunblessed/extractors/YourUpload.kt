package com.stormunblessed.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class YourUpload : ExtractorApi() {
    override var name = "YourUpload"
    override var mainUrl = "https://www.yourupload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://retrotve.com/")
        )
        val doc = app.get(url, headers = headers).document
        val videoUrl = doc.selectFirst("meta[property='og:video']")?.attr("content")
            ?: doc.selectFirst("meta[property='og:video:url']")?.attr("content")
            ?: doc.selectFirst("meta[property='og:video:secure_url']")?.attr("content")
            ?: doc.selectFirst("video source")?.attr("src")
            ?: doc.selectFirst("video")?.attr("src")
            ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                videoUrl,
            ) {
                this.referer = "https://www.yourupload.com/"
                this.quality = Qualities.P720.value
                this.headers = mapOf(
                    "Referer" to "https://www.yourupload.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }
        )
    }
}

class YourUploadNoWww : YourUpload() {
    override var mainUrl = "https://yourupload.com"
}
