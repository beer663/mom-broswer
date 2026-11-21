package com.phlox.tvwebbrowser.activity.player

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Prevent automatic native-player retries after failure, even if tokens change. */
internal class NativePlaybackGate {
    private companion object {
        val ROTATING_PARAMETERS = setOf(
            "token", "sig", "signature", "expires", "exp", "timestamp", "ts", "_"
        )
    }
    private val blockedFrames = linkedSetOf<String>()
    private val blockedPages = linkedSetOf<String>()

    fun allows(frameId: String, origin: String): Boolean =
        frameId !in blockedFrames && pageKey(origin) !in blockedPages

    fun block(frameId: String, origin: String) {
        blockedFrames.add(frameId)
        pageKey(origin)?.let(blockedPages::add)
    }

    fun releaseFrame(frameId: String) {
        blockedFrames.remove(frameId)
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
            // Keep channel IDs/names: generic player.php?id=... pages can
            // share a path while selecting entirely different channels.
            val query = uri.rawQuery.orEmpty().split('&').filter { part ->
                part.isNotEmpty() && URLDecoder.decode(
                    part.substringBefore('='), "UTF-8"
                ).lowercase(Locale.ROOT) !in ROTATING_PARAMETERS
            }.joinToString("&")
            val suffix = if (query.isEmpty()) "" else "?$query"
            "$scheme://$host$port${uri.rawPath?.ifEmpty { "/" } ?: "/"}$suffix"
        }
    } catch (_: Exception) {
        null
    }
}
