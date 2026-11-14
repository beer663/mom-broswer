package com.phlox.tvwebbrowser.activity.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import org.videolan.libvlc.util.VLCVideoLayout

/** Direct stream playback. Website video normally uses InlineStreamPlayer. */
class NativeStreamActivity : Activity() {
    companion object {
        const val EXTRA_URL = "native_stream_url"
        const val EXTRA_REFERER = "native_stream_referer"
        const val EXTRA_FAILED = "native_stream_failed"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: VlcPlaybackSession? = null
    private lateinit var overlayButton: Button
    private var unmuted = false
    private var ready = false
    private var finished = false
    private val startupTimeout = Runnable { fail() }
    private val bufferingTimeout = Runnable { fail() }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density + .5f).toInt()

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        val video = VLCVideoLayout(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1))

        overlayButton = Button(this).apply {
            text = "Unmute"
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
        }

        root.addView(
            overlayButton,
            FrameLayout.LayoutParams(
                dp(124),
                dp(48),
                Gravity.TOP or Gravity.LEFT
            ).apply {
                leftMargin = dp(8)
                topMargin = dp(8)
            }
        )

        overlayButton.setOnClickListener {
            if (!unmuted) {
                session?.volume = 1f
                unmuted = true
                overlayButton.text = "Full screen"
            } else {
                immersive()
                overlayButton.visibility = View.GONE
            }
        }

        setContentView(root)
        val url = intent.getStringExtra(EXTRA_URL)
        val uri = url?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        if (uri?.scheme != "http" && uri?.scheme != "https") {
            fail()
            return
        }

        try {
            val playback = VlcPlaybackSession(
                this,
                video,
                onReady = {
                    ready = true
                    handler.removeCallbacks(startupTimeout)
                    overlayButton.requestFocus()
                },
                onBufferingChanged = { buffering ->
                    if (buffering && ready) {
                        handler.removeCallbacks(bufferingTimeout)
                        handler.postDelayed(bufferingTimeout, 20_000L)
                    } else {
                        handler.removeCallbacks(bufferingTimeout)
                    }
                },
                onError = { fail() }
            )
            session = playback
            playback.volume = 0f
            handler.postDelayed(startupTimeout, 30_000L)
            playback.start(
                checkNotNull(url),
                intent.getStringExtra(EXTRA_REFERER),
                null
            )
        } catch (_: Exception) {
            fail()
        } catch (_: LinkageError) {
            fail()
        }
    }

    private fun fail() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        setResult(
            RESULT_CANCELED,
            Intent()
                .putExtra(EXTRA_FAILED, true)
                .putExtra(EXTRA_URL, intent.getStringExtra(EXTRA_URL))
        )
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                overlayButton.visibility = View.VISIBLE
                overlayButton.requestFocus()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                session?.let {
                    if (it.playWhenReady) it.pause() else it.play()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                session?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                session?.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                session?.seekBy(-10_000L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                session?.seekBy(10_000L)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
        if (!isFinishing) finish()
    }
}
