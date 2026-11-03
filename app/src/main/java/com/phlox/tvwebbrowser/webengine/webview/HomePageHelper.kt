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
        val cleanHomeUrl = Config.HOME_PAGE_URL.substringBefore('#').substringBefore('?').trimEnd('/')
        val requestCleanUrl = url.substringBefore('#').substringBefore('?').trimEnd('/')

        // 1. Intercept Homepage and its relative sub-resources
        if (requestCleanUrl.startsWith(cleanHomeUrl)) {
            // Determine the specific asset path
            val assetPath = if (requestCleanUrl == cleanHomeUrl) {
                "pages/home/index.html"
            } else {
                val relativePath = requestCleanUrl.removePrefix(cleanHomeUrl).trimStart('/')
                "pages/home/$relativePath"
            }

            return try {
                val inputStream = view.context.assets.open(assetPath)
                val mimeType = getMimeType(assetPath)
                
                WebResourceResponse(
                    mimeType,
                    "utf-8",
                    inputStream
                )
            } catch (_: Exception) {
                if (request.isForMainFrame) {
                    val error = "Mom Browser homepage could not be loaded."
                    WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        500,
                        "Homepage Unavailable",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(error.toByteArray())
                    )
                } else {
                    null
                }
            }
        }

        // 2. Handle bookmark favicons
        if (request.url.scheme == "favicon") {
            val host = request.url.host ?: return null

            val favicon = runBlocking {
                FaviconsPool.get(host)
            }

            return if (favicon != null) {
                val bytes = ByteArrayOutputStream()
                favicon.compress(Bitmap.CompressFormat.PNG, 100, bytes)

                WebResourceResponse(
                    "image/png",
                    "utf-8",
                    bytes.toByteArray().inputStream()
                )
            } else {
                WebResourceResponse(
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

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html", true) -> "text/html"
            path.endsWith(".css", true) -> "text/css"
            path.endsWith(".js", true) -> "application/javascript"
            path.endsWith(".png", true) -> "image/png"
            path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
            path.endsWith(".svg", true) -> "image/svg+xml"
            path.endsWith(".json", true) -> "application/json"
            else -> "text/plain"
        }
    }
}
