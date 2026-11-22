package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import com.phlox.tvwebbrowser.webengine.BrowserStreamRequest
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

class AppContentScriptPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {

    var hasVideo = false
    private var frameId: String? = null
    private val homeNavigationToken = webEngine.homeNavigationToken

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        try {
            val json = message as? JSONObject ?: return
            val type = json.optString("type")
            // A late stream/geometry message from the page we left cannot take over Home.
            if (type != "inlineFrameReleased" && homeNavigationToken != webEngine.homeNavigationToken) return

            when (type) {
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
                    val currentUrl = webEngine.url?.substringBefore('#')?.substringBefore('?')?.trimEnd('/')
                    if (currentUrl == Config.HOME_PAGE_URL.trimEnd('/') || currentUrl == Config.HOME_URL_ALIAS) return
                    val url = json.optString("url")
                    val context = json.optJSONObject("origin")
                    val origin = context?.optString("pageUrl") ?: json.optString("origin")
                    val id = frameId ?: return
                    if (json.optString("frameId") != id) return

                    if (url.isNotBlank() && origin.isNotBlank()) {
                        val callback = webEngine.callback ?: return
                        if (context == null) {
                            // Compatibility with an older installed extension.
                            callback.onNativeStreamDetected(url, origin, id)
                        } else {
                            val request = try {
                                BrowserStreamRequest.fromJson(url, origin, id, context)
                            } catch (_: Exception) {
                                // Skip this handoff while preserving any working native session.
                                Log.i("MomBrowserHeaders", "No native handoff: browser request context unavailable")
                                return
                            }
                            BrowserStreamRequest.withRequest(request) {
                                callback.onNativeStreamDetected(url, origin, id)
                            }
                        }
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
