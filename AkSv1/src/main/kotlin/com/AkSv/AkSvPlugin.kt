package com.aksv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AkSvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AkSvProvider())
        registerExtractorAPI(Megacloud())
    }
}
