package com.xdmovies.cloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class XDMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(XDMoviesProvider())
        registerExtractorAPI(HubCloudExtractor())
    }
}
