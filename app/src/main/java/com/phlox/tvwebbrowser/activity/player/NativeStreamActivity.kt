package com.phlox.tvwebbrowser.activity.player

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Optional player for direct HLS and DASH URLs; it does not intercept embedded site playback. */
class NativeStreamActivity : Activity() {
    companion object { const val EXTRA_URL = "native_stream_url" }
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (4 or 1024 or 4096)
        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        setContentView(playerView)
        val url = intent.getStringExtra(EXTRA_URL)
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val scheme = uri?.scheme?.lowercase()
        val path = uri?.path?.lowercase() ?: ""
        if ((scheme != "http" && scheme != "https") ||
            (!path.endsWith(".m3u8") && !path.endsWith(".mpd"))) {
            finish()
            return
        }
        val mime = if (path.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@NativeStreamActivity, "Native player couldn't play this stream. Returning to browser.", Toast.LENGTH_LONG).show()
                finish()
            }
        })
        exo.setMediaItem(MediaItem.Builder().setUri(uri!!).setMimeType(mime).build())
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
