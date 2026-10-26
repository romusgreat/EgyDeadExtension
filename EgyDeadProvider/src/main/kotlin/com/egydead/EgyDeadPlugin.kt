package com.egydead

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class EgyDeadPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the EgyDead provider with CloudStream's core registry
        registerMainAPI(EgyDeadProvider())
    }
}
