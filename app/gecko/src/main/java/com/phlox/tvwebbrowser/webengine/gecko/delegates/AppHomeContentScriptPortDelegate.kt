package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.graphics.Bitmap
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

class AppHomeContentScriptPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {

    private var disconnected = false
    private var homeReadyRetry: Runnable? = null

    private fun isHomeUrl(value: String?): Boolean = try {
        val uri = URI(value ?: "")
        val home = URI(Config.HOME_PAGE_URL)
        uri.scheme.equals(home.scheme, true) &&
            uri.host.equals(home.host, true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawPath == home.rawPath
    } catch (_: Exception) {
        false
    }

    private fun isTrustedSender(candidate: WebExtension.Port): Boolean {
        if (disconnected || candidate !== port || port.name != "tvbro") return false
        val sender = port.sender ?: return false
        return sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT &&
            sender.session === webEngine.session && sender.isTopLevel && isHomeUrl(sender.url)
    }

    private fun canUseHome(): Boolean =
        isTrustedSender(port) && webEngine.appHomeContentScriptPortDelegate === this &&
            isHomeUrl(webEngine.url) && webEngine.callback != null

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value.replace("[query]", "search").replace("%s", "search"))
        value.length <= 16_384 && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            value.none { it < ' ' || it == '\u007f' }
    } catch (_: Exception) {
        false
    }

    private fun hasLinkOrder(order: Int): Boolean =
        webEngine.callback?.getHomePageLinks()?.any { it.order == order } == true

    private fun hasLinkUrl(url: String): Boolean =
        webEngine.callback?.getHomePageLinks()?.any { it.url == url } == true

    private fun sendHomePage(attempt: Int = 0) {
        if (!isTrustedSender(port) || webEngine.callback == null) return
        if (webEngine.appHomeContentScriptPortDelegate !== this || !isHomeUrl(webEngine.url)) {
            // DOM ready can arrive just before Gecko's location callback.
            // Recheck the same trusted port before sending any bookmark data.
            if (attempt < 20 && homeReadyRetry == null) {
                val task = Runnable {
                    homeReadyRetry = null
                    try {
                        sendHomePage(attempt + 1)
                    } catch (_: Exception) {
                        // The page may have closed while its initial state was pending.
                    }
                }
                homeReadyRetry = task
                GeckoWebEngine.uiHandler.postDelayed(task, 100L)
            }
            return
        }
        homeReadyRetry?.let { GeckoWebEngine.uiHandler.removeCallbacks(it) }
        homeReadyRetry = null
        val callback = webEngine.callback ?: return
        val cfg = AppContext.provideConfig()
        val links = JSONArray()
        for (item in callback.getHomePageLinks()) links.put(item.toJsonObj())
        if (Utils.isFireTV(AppContext.get())) {
            webEngine.evaluateJavascript("hideVoiceSearchUI()")
        }
        webEngine.evaluateJavascript(
            "renderLinks(${JSONObject.quote(cfg.homePageLinksMode.name)}, $links)"
        )
        webEngine.evaluateJavascript(
            "applySearchEngine(${JSONObject.quote(cfg.guessSearchEngineName())}, " +
                "${JSONObject.quote(cfg.searchEngineURL.value)})"
        )
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        if (!isTrustedSender(port)) return
        try {
            val json = message as? JSONObject ?: return
            val action = json.optString("action")
            if (action == "onHomePageLoaded") {
                sendHomePage()
                return
            }
            if (!canUseHome()) return
            when (action) {
                "startVoiceSearch" -> {
                    webEngine.callback?.getActivity()?.runOnUiThread {
                        if (canUseHome()) webEngine.callback?.initiateVoiceSearch()
                    }
                }
                "setSearchEngine" -> {
                    val url = json.getJSONObject("data").getString("customSearchEngineURL")
                    if (isHttpUrl(url)) AppContext.provideConfig().searchEngineURL.value = url
                }
                "onEditBookmark" -> {
                    val order = json.getInt("data")
                    if (!hasLinkOrder(order)) return
                    webEngine.onHomeBookmarkHoldHandled()
                    webEngine.callback?.getActivity()?.runOnUiThread {
                        if (canUseHome() && hasLinkOrder(order)) {
                            webEngine.callback?.onEditHomePageBookmarkSelected(order)
                        }
                    }
                }
                "requestFavicon" -> {
                    val url = json.getString("data")
                    if (!isHttpUrl(url) || !hasLinkUrl(url)) return
                    val activity = webEngine.callback?.getActivity() as? AppCompatActivity ?: return
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val favicon = FaviconsPool.get(url) ?: return@launch
                            val bytes = ByteArrayOutputStream()
                            favicon.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                            val data = "data:image/png;base64," +
                                Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                            withContext(Dispatchers.Main) {
                                if (canUseHome() && hasLinkUrl(url)) {
                                    this@AppHomeContentScriptPortDelegate.port.postMessage(
                                        JSONObject().put("action", "favicon").put(
                                            "data", JSONObject().put("url", url).put("data", data)
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                            // A missing icon or a disconnected page keeps its default icon.
                        }
                    }
                }
                "markBookmarkRecommendationAsUseful" -> {
                    val order = json.getInt("data")
                    if (hasLinkOrder(order)) {
                        webEngine.callback?.markBookmarkRecommendationAsUseful(order)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore malformed homepage commands.
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        if (port !== this.port) return
        disconnected = true
        homeReadyRetry?.let { GeckoWebEngine.uiHandler.removeCallbacks(it) }
        homeReadyRetry = null
        if (webEngine.appHomeContentScriptPortDelegate === this) {
            webEngine.appHomeContentScriptPortDelegate = null
        }
    }
}
