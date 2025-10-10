package com.aksv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AkSvPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AkSvProvider())
        // register more extractors here after you copy them
    }
}
