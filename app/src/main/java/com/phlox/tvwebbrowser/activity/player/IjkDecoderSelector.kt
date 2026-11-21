package com.phlox.tvwebbrowser.activity.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import java.util.Locale
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/** Retain IJK's known decoder choices, then recognize modern Codec2 hardware. */
internal object IjkDecoderSelector : IjkMediaPlayer.OnMediaCodecSelectListener {
    private const val TAG = "MomBrowserIJK"

    override fun onMediaCodecSelect(
        player: IMediaPlayer?,
        mimeType: String?,
        profile: Int,
        level: Int
    ): String? {
        if (mimeType.isNullOrBlank()) return null

        // Preserve the library's established choices and device workarounds.
        val existing = try {
            IjkMediaPlayer.DefaultMediaCodecSelector.sInstance
                .onMediaCodecSelect(player, mimeType, profile, level)
        } catch (error: Exception) {
            Log.w(TAG, "IJK decoder lookup failed", error)
            null
        }
        if (!existing.isNullOrBlank()) return existing
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val codecs = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (error: Exception) {
            Log.w(TAG, "Android decoder lookup failed; retaining software fallback", error)
            return null
        }

        for (codec in codecs) {
            try {
                val name = codec.name
                val lower = name.lowercase(Locale.ROOT)
                if (codec.isEncoder || !lower.startsWith("c2.") ||
                    lower.endsWith(".secure") || lower.endsWith(".sw") ||
                    lower.contains(".sw.") || lower.startsWith("c2.android.") ||
                    lower.startsWith("c2.google.") ||
                    codec.isSoftwareOnly || !codec.isHardwareAccelerated) continue

                val type = codec.supportedTypes.firstOrNull {
                    it.equals(mimeType, ignoreCase = true)
                } ?: continue
                val capabilities = codec.getCapabilitiesForType(type)
                if (capabilities.isFeatureRequired(
                        MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback
                    ) || capabilities.isFeatureRequired(
                        MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback
                    )) continue

                Log.i(TAG, "Selecting Android hardware decoder: $name for $mimeType")
                return name
            } catch (error: Exception) {
                // A broken vendor entry must not prevent other decoders or HTML.
                Log.w(TAG, "Skipping unavailable Android decoder", error)
            }
        }
        // IJK retains its software decoder and the browser retains HTML recovery.
        return null
    }
}
