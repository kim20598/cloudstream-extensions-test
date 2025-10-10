package com.akwam

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AkwamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AkwamProvider())
        registerExtractorAPI(Megacloud())
    }
}
