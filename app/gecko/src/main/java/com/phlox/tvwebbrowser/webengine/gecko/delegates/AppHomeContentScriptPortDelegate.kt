
package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.graphics.Bitmap
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.io.ByteArrayOutputStream

class AppHomeContentScriptPortDelegate(
    val port: WebExtension.Port,
    val webEngine: GeckoWebEngine
) : WebExtension.PortDelegate {

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        try {
            val msgJson = message as JSONObject

            when (msgJson.getString("action")) {
                "startVoiceSearch" -> {
                    webEngine.callback?.getActivity()?.runOnUiThread {
                        webEngine.callback?.initiateVoiceSearch()
                    }
                }

                "onHomePageLoaded" -> {
                    val callback = webEngine.callback ?: return
                    val cfg = AppContext.provideConfig()
                    val jsArr = JSONArray()

                    for (item in callback.getHomePageLinks()) {
                        jsArr.put(item.toJsonObj())
                    }

                    var links = jsArr.toString()
                    links = links.replace("'", "\\'")

                    if (Utils.isFireTV(AppContext.get())) {
                        webEngine.evaluateJavascript("hideVoiceSearchUI()")
                    }

                    webEngine.evaluateJavascript(
                        "renderLinks('${cfg.homePageLinksMode.name}', $links)"
                    )

                    webEngine.evaluateJavascript(
                        "applySearchEngine(\"${cfg.guessSearchEngineName()}\", \"${cfg.searchEngineURL.value}\")"
                    )
                }

                "setSearchEngine" -> {
                    val data = msgJson.getJSONObject("data")
                    val customSearchEngineURL = data.getString("customSearchEngineURL")
                    AppContext.provideConfig().searchEngineURL.value = customSearchEngineURL
                }

                "onEditBookmark" -> {
                    val index = msgJson.getInt("data")
                    val callback = webEngine.callback ?: return

                    callback.getActivity().runOnUiThread {
                        callback.onEditHomePageBookmarkSelected(index)
                    }
                }

                "requestFavicon" -> {
                    val url = msgJson.getString("data")

                    (webEngine.callback?.getActivity() as? AppCompatActivity)
                        ?.lifecycleScope?.launch(Dispatchers.IO) {
                            val favicon = FaviconsPool.get(url)

                            if (favicon != null) {
                                val msg = JSONObject()
                                msg.put("action", "favicon")

                                val faviconBytes = ByteArrayOutputStream()
                                favicon.compress(
                                    Bitmap.CompressFormat.PNG,
                                    100,
                                    faviconBytes
                                )

                                val faviconBase64 = "data:image/png;base64," +
                                    Base64.encodeToString(
                                        faviconBytes.toByteArray(),
                                        Base64.DEFAULT
                                    )

                                msg.put(
                                    "data",
                                    JSONObject().put("url", url).put("data", faviconBase64)
                                )

                                port.postMessage(msg)
                            }
                        }
                }

                "markBookmarkRecommendationAsUseful" -> {
                    val bookmarkOrder = msgJson.getInt("data")
                    val callback = webEngine.callback ?: return
                    callback.markBookmarkRecommendationAsUseful(bookmarkOrder)
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
