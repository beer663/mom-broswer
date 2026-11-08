package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

// One instance per page frame.
class AppContentScriptPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {
    var hasVideo = false

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        try {
            val json = message as? JSONObject ?: return

            when (json.optString("type")) {
                "nativeStreamDetected" -> {
                    val url = json.optString("url")
                    val origin = json.optString("origin")

                    if (url.isNotBlank() && origin.isNotBlank()) {
                        webEngine.callback?.onNativeStreamDetected(url, origin)
                    }
                }

                "videoState" -> {
                    hasVideo = json.optBoolean("present", false)
                    webEngine.onContentVideoStateChanged()
                }

                "inlineVideoRect" -> {
                    val frameUrl = json.optString("frameUrl")
                    if (frameUrl.length > 2048) return

                    webEngine.callback?.onInlineVideoRect(
                        frameUrl,
                        json.optDouble("x"),
                        json.optDouble("y"),
                        json.optDouble("width"),
                        json.optDouble("height"),
                        json.optDouble("viewportWidth"),
                        json.optDouble("viewportHeight")
                    )
                }
            }
        } catch (_: Exception) {
            // Ignore invalid or unexpected messages.
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        webEngine.onContentPortDisconnected(this)
    }
}
