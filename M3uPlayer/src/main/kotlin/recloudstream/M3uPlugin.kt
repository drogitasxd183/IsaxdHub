package recloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class M3uPlugin : Plugin() {
    override fun load(context: Context) {
        val store = M3uStore(context.getSharedPreferences("m3u_player", Context.MODE_PRIVATE))
        val provider = M3uProvider(store)

        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(provider)

        // Adds the gear icon next to the provider name on the home screen.
        openSettings = { ctx ->
            showM3uSettings(ctx, store) { provider.clearCache() }
        }
    }
}
