package com.stormunblessed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.movieproviders.RTVCPlayProvider

@CloudstreamPlugin
class RTVCPlayProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RTVCPlayProvider())
    }
}
