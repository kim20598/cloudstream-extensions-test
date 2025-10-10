package com.aksv

import com.lagradost.cloudstream3.extractors.Okrulink
import com.lagradost.cloudstream3.extractors.Vidguardto2
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AkSvPlugin : BasePlugin() {
    override fun load() {
        // register the new provider
        registerMainAPI(AkSvProvider())

        // keep every extractor the exact same
        registerExtractorAPI(swiftplayers())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(FilemoonV2())
        registerExtractorAPI(Vidguardto2())
        registerExtractorAPI(Okrulink())
    }
}
