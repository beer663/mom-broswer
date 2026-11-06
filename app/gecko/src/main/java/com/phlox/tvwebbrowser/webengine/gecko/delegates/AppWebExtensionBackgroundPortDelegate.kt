package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * The port of the extension's background script. It asks about every request of every tab and each request
 * WAITS for the answer (or a 1.5 s timeout in the script). So every request must be answered, always: a missing
 * answer makes the page wait. The tab this delegate was created by may be closed or in the background (no
 * callback), so the answer never depends on it.
 */
class AppWebExtensionBackgroundPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {

    companion object {
        private const val TAG = "AdCheckPort"
        // if the ad matcher is further behind than this, let requests through instead of piling them up
        private const val MAX_PENDING = 150
        // one thread: the ad matcher is not meant to be used from several threads at once
        private val adCheckExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val pending = AtomicInteger(0)
    }

    private fun reply(requestId: Any, block: Boolean) {
        try {
            val data = JSONObject().put("requestId", requestId).put("block", block)
            port.postMessage(JSONObject().put("action", "onResolveRequest").put("data", data))
        } catch (e: Exception) {
            Log.w(TAG, "Can not answer a request (port closed?)", e)
        }
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val msgJson = message as? JSONObject ?: return
        if (msgJson.optString("action") == "onNativeStreamDetected") {
            val url = msgJson.optString("url")
            val origin = msgJson.optString("origin")
            if (url.isNotBlank() && origin.isNotBlank()) {
                mainHandler.post {
                    try { (GeckoWebEngine.activeCallback ?: webEngine.callback)?.onNativeStreamDetected(url, origin) }
                    catch (e: Exception) { Log.w(TAG, "Native stream handoff failed", e) }
                }
            }
            return
        }
        if (msgJson.optString("action") != "onBeforeRequest") return
        val details = msgJson.optJSONObject("details") ?: return
        // the script keys the request by this id; without it there is nothing to answer
        val requestId = details.opt("requestId") ?: return

        try {
            val url = details.optString("url")
            // whichever tab is on screen: the decision is the same for every tab
            val callback = GeckoWebEngine.activeCallback ?: webEngine.callback
            if (callback == null || url.isEmpty() || pending.get() >= MAX_PENDING) {
                reply(requestId, false)
                return
            }
            if (!callback.isAdBlockingEnabled()) {
                reply(requestId, false)
                return
            }
            val type = details.optString("type")
            val uri = Uri.parse(url)
            val originUri = Uri.parse(details.optString("originUrl"))

            // Matching against the ad list can be slow: do it off the UI thread, then answer on it.
            pending.incrementAndGet()
            try {
                adCheckExecutor.execute {
                    var block = false
                    try {
                        block = callback.isAd(uri, type, originUri) ?: false
                    } catch (e: Throwable) {
                        Log.w(TAG, "ad check failed", e)
                    } finally {
                        pending.decrementAndGet()
                    }
                    mainHandler.post {
                        try {
                            if (block) callback.onBlockedAd(url)
                        } catch (e: Throwable) {
                            Log.w(TAG, "onBlockedAd failed", e)
                        }
                        reply(requestId, block)
                    }
                }
            } catch (e: Exception) {
                pending.decrementAndGet()
                reply(requestId, false)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "onBeforeRequest failed", e)
            reply(requestId, false)
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "background script disconnected")
    }
}
