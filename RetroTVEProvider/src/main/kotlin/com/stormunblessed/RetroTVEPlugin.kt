package com.stormunblessed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.stormunblessed.extractors.VkExtractor
import com.stormunblessed.extractors.VkVideoRuExtractor
import com.stormunblessed.extractors.YourUpload
import com.stormunblessed.extractors.YourUploadNoWww

@CloudstreamPlugin
class RetroTVEPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RetroTVEProvider())
        registerExtractorAPI(YourUpload())
        registerExtractorAPI(YourUploadNoWww())
        registerExtractorAPI(VkExtractor())
        registerExtractorAPI(VkVideoRuExtractor())
    }
}
