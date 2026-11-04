package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

// One instance per page frame (the content script runs in every frame).
class AppContentScriptPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {
    /** True while this frame contains a sizeable video. */
    var hasVideo = false

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        try {
            val json = message as? JSONObject ?: return
            if (json.optString("type") == "videoState") {
                hasVideo = json.optBoolean("present", false)
                webEngine.onContentVideoStateChanged()
            }
        } catch (_: Exception) {
            // Ignore invalid or unexpected messages.
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        webEngine.onContentPortDisconnected(this)
    }
}
