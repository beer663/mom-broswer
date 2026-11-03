
package com.phlox.tvwebbrowser.webengine.webview

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object HomePageHelper {

    fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {

        val url = request.url.toString()

        // Serve the bundled homepage instead of contacting the TV Bro server.
        if (request.isForMainFrame &&
            url.substringBefore('#').substringBefore('?').trimEnd('/') ==
            Config.HOME_PAGE_URL.trimEnd('/')
        ) {
            return try {
                val html = view.context.assets
                    .open("pages/home/index.html")
                    .use { it.readBytes() }

                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    ByteArrayInputStream(html)
                )
            } catch (_: Exception) {
                val error = "Mom Browser homepage could not be loaded."
                WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    500,
                    "Homepage Unavailable",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(error.toByteArray())
                )
            }
        }

        // Handle bookmark favicons.
        if (request.url.scheme == "favicon") {
            val host = request.url.host ?: return null

            val favicon = runBlocking {
                FaviconsPool.get(host)
            }

            if (favicon != null) {
                val bytes = ByteArrayOutputStream()
                favicon.compress(Bitmap.CompressFormat.PNG, 100, bytes)

                return WebResourceResponse(
                    "image/png",
                    "utf-8",
                    bytes.toByteArray().inputStream()
                )
            } else {
                return WebResourceResponse(
                    null,
                    null,
                    404,
                    "Not Found",
                    null,
                    null
                )
            }
        }

        return null
    }
}
