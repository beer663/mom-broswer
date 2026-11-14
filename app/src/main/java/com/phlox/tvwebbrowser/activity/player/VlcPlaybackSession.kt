package com.phlox.tvwebbrowser.activity.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** One native session. It never loops or starts a replacement stream itself. */
internal class VlcPlaybackSession(
    context: Context,
    layout: VLCVideoLayout,
    private val onReady: () -> Unit,
    private val onBufferingChanged: (Boolean) -> Unit,
    private val onError: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lib: LibVLC? = null
    private var player: MediaPlayer? = null
    private var released = false
    private var failed = false
    private var hasVideo = false
    private var notifiedReady = false

    var playWhenReady = false
        private set

    var isBuffering = false
        private set

    var volume = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            player?.setVolume((field * 100).toInt())
        }

    init {
        try {
            val engine = LibVLC(context.applicationContext, arrayListOf(
                "--no-video-title-show",
                "--no-loop",
                "--no-repeat",
                "--network-caching=1500"
            ))
            lib = engine
            val native = MediaPlayer(engine)
            player = native
            native.setUseOrientationFromBounds(true)
            native.attachViews(layout, null, false, true)
            native.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
            native.setEventListener { event ->
                handler.post {
                    if (!released && !failed) handleEvent(event)
                }
            }
        } catch (error: Exception) {
            release()
            throw error
        } catch (error: LinkageError) {
            release()
            throw error
        }
    }

    fun start(url: String, origin: String?, userAgent: String?) {
        check(!released)
        val engine = checkNotNull(lib)
        val native = checkNotNull(player)
        val media = Media(engine, Uri.parse(url))
        try {
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=1500")
            userAgent?.takeIf { it.isNotBlank() }?.let {
                media.addOption(":http-user-agent=${httpValue(it)}")
            }
            if (origin?.startsWith("https://") == true ||
                origin?.startsWith("http://") == true) {
                media.addOption(":http-referrer=${httpValue(origin)}")
            }
            native.media = media
        } finally {
            media.release()
        }
        native.setVolume((volume * 100).toInt())
        playWhenReady = true
        native.play()
    }

    private fun httpValue(value: String) =
        value.replace("\r", "").replace("\n", "")

    private fun handleEvent(event: MediaPlayer.Event) {
        val native = player ?: return
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                native.setVolume((volume * 100).toInt())
                setBuffering(notifiedReady && !hasVideo)
                notifyReady()
            }
            MediaPlayer.Event.Vout -> {
                hasVideo = event.voutCount > 0
                if (hasVideo) {
                    setBuffering(false)
                    notifyReady()
                } else if (notifiedReady && playWhenReady) {
                    setBuffering(true)
                }
            }
            MediaPlayer.Event.Buffering ->
                setBuffering(
                    event.buffering < 100f ||
                        (notifiedReady && !hasVideo)
                )
            MediaPlayer.Event.Paused,
            MediaPlayer.Event.EndReached -> {
                playWhenReady = false
                setBuffering(false)
            }
            MediaPlayer.Event.EncounteredError -> {
                failed = true
                onError()
            }
        }
    }

    private fun notifyReady() {
        if (!notifiedReady && hasVideo && player?.isPlaying == true) {
            notifiedReady = true
            onReady()
        }
    }

    private fun setBuffering(buffering: Boolean) {
        if (isBuffering == buffering) return
        isBuffering = buffering
        onBufferingChanged(buffering)
    }

    fun play() {
        if (released || failed) return
        val native = player ?: return
        if (native.length > 0 &&
            native.time >= native.length - 250L &&
            native.isSeekable) {
            native.setTime(0L)
        }
        playWhenReady = true
        native.play()
    }

    fun pause() {
        if (released) return
        playWhenReady = false
        player?.pause()
        setBuffering(false)
    }

    fun seekBy(offsetMs: Long) {
        val native = player ?: return
        if (released || failed || !native.isSeekable) return
        val upper = native.length.takeIf { it > 0 } ?: Long.MAX_VALUE
        native.setTime(
            (native.time.coerceAtLeast(0L) + offsetMs).coerceIn(0L, upper)
        )
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        val native = player
        player = null
        try {
            native?.setEventListener(null)
            native?.stop()
        } finally {
            try {
                native?.detachViews()
                native?.release()
            } finally {
                lib?.release()
                lib = null
            }
        }
    }
}
