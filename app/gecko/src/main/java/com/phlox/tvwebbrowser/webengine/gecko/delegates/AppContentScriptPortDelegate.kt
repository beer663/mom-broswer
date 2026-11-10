package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

class AppContentScriptPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {

    var hasVideo = false
    private var frameId: String? = null

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        try {
            val json = message as? JSONObject ?: return

            when (json.optString("type")) {
                "inlineFrameReady" -> {
                    val id = json.optString("frameId")
                    if (frameId == null && id.isNotBlank() && id.length <= 128) {
                        frameId = id
                    }
                }

                "inlineFrameReleased" -> {
                    val id = frameId ?: return
                    if (json.optString("frameId") != id) return
                    webEngine.callback?.onInlineFrameReleased(
                        id,
                        json.optString("reason") == "replacement"
                    )
                }

                "nativeStreamDetected" -> {
                    val url = json.optString("url")
                    val origin = json.optString("origin")
                    val id = frameId ?: return
                    if (json.optString("frameId") != id) return

                    if (url.isNotBlank() && origin.isNotBlank()) {
                        webEngine.callback?.onNativeStreamDetected(
                            url,
                            origin,
                            id
                        )
                    }
                }

                "videoState" -> {
                    hasVideo = json.optBoolean("present", false)
                    webEngine.onContentVideoStateChanged()
                }

                "inlineVideoRect" -> {
                    val frameUrl = json.optString("frameUrl")
                    val id = json.optString("frameId")
                    if (frameUrl.length > 2048 || id.isBlank() || id.length > 128) return

                    val x = json.optDouble("x")
                    val y = json.optDouble("y")
                    val width = json.optDouble("width")
                    val height = json.optDouble("height")

                    webEngine.callback?.onInlineVideoRect(
                        frameUrl,
                        x,
                        y,
                        width,
                        height,
                        json.optDouble("viewportWidth"),
                        json.optDouble("viewportHeight"),
                        json.optDouble("clipX", x),
                        json.optDouble("clipY", y),
                        json.optDouble("clipWidth", width),
                        json.optDouble("clipHeight", height),
                        json.optLong("measuredAt", 0L),
                        id
                    )
                }
            }
        } catch (_: Exception) {
            // Ignore invalid or unexpected messages.
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        frameId?.let { webEngine.callback?.onInlineFrameReleased(it, false) }
        webEngine.onContentPortDisconnected(this)
    }
}
