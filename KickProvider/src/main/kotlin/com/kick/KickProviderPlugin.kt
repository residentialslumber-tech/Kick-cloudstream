package com.kick

import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KickProviderPlugin : BasePlugin() {
    override fun load(context: Context) {
        registerMainAPI(KickProvider())
    }
}
