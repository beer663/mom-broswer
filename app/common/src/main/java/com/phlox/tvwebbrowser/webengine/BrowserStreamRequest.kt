package com.phlox.tvwebbrowser.webengine

import java.net.URI
import org.json.JSONObject

/** Credentials captured from one actual Gecko request, never from document.cookie. */
class BrowserStreamRequest private constructor(
    val url: String,
    val pageUrl: String,
    val frameId: String,
    val headers: Map<String, String>,
    val cookies: List<Cookie>
) {
    class Cookie(
        val name: String,
        val value: String,
        val path: String,
        val secure: Boolean,
        val expiresAt: Long
    ) {
        override fun toString() = "Cookie(redacted)"
    }

    val requiresProxy: Boolean get() = cookies.isNotEmpty() || headers.containsKey("Authorization")
    override fun toString() = "BrowserStreamRequest(redacted)"

    companion object {
        private const val MAX_HEADER_BYTES = 24 * 1024
        private val headerNames = setOf("User-Agent", "Referer", "Origin", "Accept", "Accept-Language", "Authorization")
        private val scopedRequest = ThreadLocal<BrowserStreamRequest>()

        /** The existing callback is synchronous. Clear credentials even if it rejects the tab. */
        fun <T> withRequest(request: BrowserStreamRequest, block: () -> T): T {
            val previous = scopedRequest.get()
            scopedRequest.set(request)
            return try { block() } finally {
                if (previous == null) scopedRequest.remove() else scopedRequest.set(previous)
            }
        }

        fun current(url: String, pageUrl: String, frameId: String): BrowserStreamRequest? =
            scopedRequest.get()?.takeIf {
                it.url == url && it.pageUrl == pageUrl && it.frameId == frameId
            }

        fun httpUri(value: String): URI {
            require(value.length <= 16_384 && value.none { it <= ' ' || it == '\u007f' })
            val uri = URI(value)
            require(uri.scheme == "http" || uri.scheme == "https")
            require(!uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null)
            return uri
        }

        fun safeHeader(value: String): Boolean =
            value.length <= 16_384 && value.all { it == '\t' || it >= ' ' && it != '\u007f' }

        fun pathMatches(path: String, cookiePath: String): Boolean =
            path == cookiePath || path.startsWith(cookiePath) &&
                (cookiePath.endsWith('/') || path.getOrNull(cookiePath.length) == '/')

        fun sameOrigin(a: URI, b: URI): Boolean {
            fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
            return a.scheme == b.scheme && a.host.equals(b.host, true) && port(a) == port(b)
        }

        fun fromJson(url: String, pageUrl: String, frameId: String, json: JSONObject): BrowserStreamRequest {
            val uri = httpUri(url)
            httpUri(pageUrl.substringBefore('#'))
            require(json.optInt("version") == 1 && json.optString("url") == url)
            require(json.optBoolean("cookiesComplete", false))
            require(frameId.isNotBlank() && frameId.length <= 128)
            val age = System.currentTimeMillis() - json.optLong("capturedAt")
            require(age in -5_000L..60_000L)
            val incoming = json.optJSONObject("headers") ?: error("Missing request headers")
            val headers = linkedMapOf<String, String>()
            var total = 0
            for (name in headerNames) {
                if (!incoming.has(name)) continue
                val value = incoming.getString(name)
                require(safeHeader(value))
                total += name.length + value.length
                headers[name] = value
            }
            val incomingCookies = json.optJSONArray("cookies") ?: error("Missing cookie metadata")
            require(incomingCookies.length() <= 128)
            val cookies = ArrayList<Cookie>()
            for (i in 0 until incomingCookies.length()) {
                val item = incomingCookies.getJSONObject(i)
                val name = item.getString("name")
                val value = item.getString("value")
                val path = item.getString("path")
                require(name.isNotEmpty() && name.all { it.isLetterOrDigit() && it.code < 128 || it in "!#$%&'*+-.^_`|~" })
                require(safeHeader(value) && value.none { it == ';' || it.code > 126 || it < ' ' })
                require(path.startsWith('/') && path.length <= 4096 && pathMatches(uri.rawPath.ifEmpty { "/" }, path))
                val secure = item.getBoolean("secure")
                require(!secure || uri.scheme == "https")
                val expiresAt = item.optLong("expiresAt", Long.MAX_VALUE)
                total += name.length + value.length + path.length
                if (expiresAt > System.currentTimeMillis()) cookies.add(Cookie(name, value, path, secure, expiresAt))
            }
            require(total <= MAX_HEADER_BYTES)
            return BrowserStreamRequest(url, pageUrl, frameId, headers.toMap(), cookies.toList())
        }
    }
}
