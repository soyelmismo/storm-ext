package com.stormunblessed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.stormunblessed.extractors.YourUpload

@CloudstreamPlugin
class RetroTVEPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RetroTVEProvider())
        registerExtractorAPI(YourUpload())
    }
}
