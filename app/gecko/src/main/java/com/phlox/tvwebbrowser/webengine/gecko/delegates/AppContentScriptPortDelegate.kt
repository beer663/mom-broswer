
package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.mozilla.geckoview.WebExtension

// This is a leftover from the removal of the abandoned "text selection" feature.
// I'll leave it here for now as a framework for future extension-based features.
class AppContentScriptPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        // No action required.
    }

    override fun onDisconnect(port: WebExtension.Port) {
        webEngine.appContentScriptPortDelegate = null
    }
}
