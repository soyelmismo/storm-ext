package com.stormunblessed.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class YourUpload : ExtractorApi() {
    override var name = "YourUpload"
    override var mainUrl = "https://www.yourupload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer ?: "https://retrotve.com/").document
        val videoUrl = doc.selectFirst("meta[property='og:video']")?.attr("content")
            ?: doc.selectFirst("meta[property='og:video:url']")?.attr("content")
            ?: doc.selectFirst("video source")?.attr("src")
            ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                videoUrl,
            ) {
                this.referer = "https://www.yourupload.com/"
                this.quality = Qualities.P720.value
                this.headers = mapOf("Referer" to "https://www.yourupload.com/")
            }
        )
    }
}
