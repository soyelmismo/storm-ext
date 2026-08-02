package com.stormunblessed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NetflixCatalogoLatamProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NetflixCatalogoLatamProvider())
    }
}
