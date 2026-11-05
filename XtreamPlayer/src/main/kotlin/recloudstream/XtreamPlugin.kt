package recloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XtreamPlugin : Plugin() {
    override fun load(context: Context) {
        val store = XtreamStore(context.getSharedPreferences("xtream_player", Context.MODE_PRIVATE))
        val provider = XtreamProvider(store)

        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(provider)

        // Adds the gear icon next to the provider name on the home screen.
        openSettings = { ctx ->
            showXtreamSettings(ctx, store) { provider.clearCache() }
        }
    }
}
