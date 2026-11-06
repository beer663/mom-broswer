package com.phlox.tvwebbrowser.activity.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Direct HLS/DASH player. Mirrors the GeckoView overlay controls for TV remote use. */
class NativeStreamActivity : Activity() {
    companion object {
        const val EXTRA_URL = "native_stream_url"
        const val EXTRA_REFERER = "native_stream_referer"
        const val EXTRA_FAILED = "native_stream_failed"
    }
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlayButton: Button
    private var isUnmuted = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun button(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply {
            setColor(Color.rgb(48, 54, 61))
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), Color.rgb(216, 222, 232))
        }
        isFocusable = true
        isFocusableInTouchMode = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        val root = FrameLayout(this)
        root.addView(playerView)
        // Keep one button at the top-left of the video surface. The Fullscreen
        // action replaces Unmute in exactly the same location after unmuting.
        overlayButton = button("Unmute")
        root.addView(overlayButton, FrameLayout.LayoutParams(dp(124), dp(48),
            Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(8)
            topMargin = dp(8)
        })
        overlayButton.setOnClickListener {
            if (!isUnmuted) {
                player?.volume = 1f
                isUnmuted = true
                overlayButton.text = "Full screen"
            } else {
                immersive()
                playerView.showController()
                overlayButton.visibility = View.GONE
            }
        }
        // DPAD center and Back work even if the native controller is currently hidden.
        setContentView(root)
        val uri = intent.getStringExtra(EXTRA_URL)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val scheme = uri?.scheme?.lowercase()
        val path = uri?.path?.lowercase() ?: ""
        if ((scheme != "http" && scheme != "https") ||
            (!path.endsWith(".m3u8") && !path.endsWith(".mpd"))) {
            finish()
            return
        }
        val mime = if (path.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val headers = if (referer?.startsWith("http") == true) mapOf("Referer" to referer) else emptyMap()
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@NativeStreamActivity, "Native playback failed. Returning to browser.", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED, android.content.Intent().putExtra(EXTRA_FAILED, true)
                    .putExtra(EXTRA_URL, intent.getStringExtra(EXTRA_URL)))
                finish()
            }
        })
        exo.setMediaItem(MediaItem.Builder().setUri(uri!!).setMimeType(mime).build())
        // Start muted so Unmute is a meaningful first action, like GeckoView.
        exo.volume = 0f
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            overlayButton.visibility = View.VISIBLE
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
