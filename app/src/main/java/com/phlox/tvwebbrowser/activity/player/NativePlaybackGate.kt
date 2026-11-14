package com.phlox.tvwebbrowser.activity.player

import java.net.URI
import java.util.Locale

/** Prevent automatic native-player retries after failure, even if tokens change. */
internal class NativePlaybackGate {
    private val blockedFrames = linkedSetOf<String>()
    private val blockedPages = linkedSetOf<String>()

    fun allows(frameId: String, origin: String): Boolean =
        frameId !in blockedFrames && pageKey(origin) !in blockedPages

    fun block(frameId: String, origin: String) {
        blockedFrames.add(frameId)
        pageKey(origin)?.let(blockedPages::add)
    }

    private fun pageKey(origin: String): String? = try {
        val uri = URI(origin)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
            null
        } else {
            val port = when {
                uri.port < 0 -> ""
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                else -> ":${uri.port}"
            }
            "$scheme://$host$port${uri.rawPath?.ifEmpty { "/" } ?: "/"}"
        }
    } catch (_: Exception) {
        null
    }
}
