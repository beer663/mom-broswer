
package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

class AppWebExtensionBackgroundPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {

    companion object {
        // one thread: the ad matcher is not meant to be used from several threads at once
        private val adCheckExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        try {
            val msgJson = message as JSONObject

            when (msgJson.getString("action")) {
                "onBeforeRequest" -> {
                    val data = msgJson.getJSONObject("details")
                    val requestId = data.getInt("requestId")
                    val url = data.getString("url")
                    val originUrl = data.getString("originUrl") ?: ""
                    val type = data.getString("type")
                    val callback = webEngine.callback ?: return
                    val adBlockingEnabled = callback.isAdBlockingEnabled()

                    fun reply(block: Boolean) {
                        val msg = JSONObject()
                        msg.put("action", "onResolveRequest")
                        msg.put("data", JSONObject().put("requestId", requestId).put("block", block))
                        port.postMessage(msg)
                    }

                    if (!adBlockingEnabled) {
                        reply(false)
                        return
                    }

                    // Matching against the ad list can be slow: do it off the UI thread, then answer on it.
                    val uri = Uri.parse(url)
                    val originUri = Uri.parse(originUrl)
                    adCheckExecutor.execute {
                        val block = try {
                            callback.isAd(uri, type, originUri) ?: false
                        } catch (_: Exception) {
                            false
                        }
                        mainHandler.post {
                            try {
                                if (block) callback.onBlockedAd(url)
                                reply(block)
                            } catch (_: Exception) {
                                // port closed meanwhile
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore invalid or unexpected messages.
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        webEngine.appHomeContentScriptPortDelegate = null
    }
}
