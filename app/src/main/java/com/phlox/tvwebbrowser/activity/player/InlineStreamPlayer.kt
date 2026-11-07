package com.phlox.tvwebbrowser.activity.player

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.phlox.tvwebbrowser.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders HLS/DASH in an Android overlay positioned over a GeckoView HTML video.
 * This does NOT embed ExoPlayer in the DOM. Back hides the overlay so users can
 * pick another streaming server. DRM/cookie-protected streams may fail.
 */
class InlineStreamPlayer(
    private val activity: AppCompatActivity,
    private val root: RelativeLayout,
    private val webView: () -> View?,
    private val onActiveChanged: (Boolean) -> Unit,
    private val onFailed: (String) -> Unit
) {
    private data class Box(
        val frameUrl: String, val x: Double, val y: Double,
        val width: Double, val height: Double,
        val viewportWidth: Double, val viewportHeight: Double,
        val updated: Long
    )
    private data class Request(val url: String, val origin: String, val userAgent: String?)

    private val handler = Handler(Looper.getMainLooper())
    private val boxes = mutableMapOf<String, Box>()
    private val cooldown = mutableMapOf<String, Long>()
    private var pending: Request? = null
    private var pendingReadyAt = 0L
    private var current: Request? = null
    private var exo: ExoPlayer? = null
    private var holder: FrameLayout? = null
    private var playerView: PlayerView? = null
    private var button: Button? = null
    private var fullscreen = false
    private var muted = true
    private var menuVisible = false
    private var ready = false
    private var oldUiFlags: Int? = null
    private var timeout: Runnable? = null
    private val startPending = Runnable { startIfPositioned() }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
    private fun isPlaylist(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val path = uri.path?.lowercase(Locale.ROOT) ?: return false
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    fun detect(url: String, origin: String, userAgent: String?) {
        if (!isPlaylist(url)) return
        val now = SystemClock.uptimeMillis()
        if ((cooldown[url] ?: 0L) > now) return
        if (current?.url == url || pending?.url == url) return
        pending = Request(url, origin, userAgent)
        pendingReadyAt = now + 750L
        handler.removeCallbacks(startPending)
        // A site can fetch several alternative playlists in succession.
        // Use the most recent, and wait briefly for its video position.
        handler.postDelayed(startPending, 750L)
    }

    fun updateRect(frameUrl: String, x: Double, y: Double, width: Double, height: Double,
                   viewportWidth: Double, viewportHeight: Double) {
        val values = doubleArrayOf(x, y, width, height, viewportWidth, viewportHeight)
        if (values.any { it.isNaN() || it.isInfinite() } ||
            viewportWidth < 100 || viewportHeight < 80 || width < 50 || height < 40 ||
            frameUrl.length > 2048) return
        boxes[frameUrl] = Box(frameUrl, x, y, width, height, viewportWidth, viewportHeight,
                              SystemClock.uptimeMillis())
        if (current != null && !fullscreen) bestBox(current!!)?.let { position(it) }
        // Never postpone the start timer on every rectangle report: moving
        // banners can otherwise keep rescheduling playback indefinitely.
        if (pending != null && SystemClock.uptimeMillis() >= pendingReadyAt) {
            handler.removeCallbacks(startPending)
            handler.post(startPending)
        }
    }

    private fun bestBox(request: Request): Box? {
        val now = SystemClock.uptimeMillis()
        val originUri = runCatching { Uri.parse(request.origin) }.getOrNull()
        val match = boxes.values.filter { now - it.updated < 6500L }
        if (match.isEmpty()) return null
        fun score(b: Box): Double {
            val frameUri = runCatching { Uri.parse(b.frameUrl) }.getOrNull()
            val exact = request.origin.isNotBlank() &&
                b.frameUrl.substringBefore('#') == request.origin.substringBefore('#')
            val sameHost = originUri?.host != null && originUri.host == frameUri?.host
            val area = (b.width * b.height) / (b.viewportWidth * b.viewportHeight)
            return area + if (exact) 10.0 else if (sameHost) 5.0 else 0.0
        }
        return match.maxByOrNull { score(it) }
    }

    private fun startIfPositioned() {
        val req = pending ?: return
        val box = bestBox(req) ?: return // no DOM video; do not force fullscreen
        if (current?.url == req.url) { pending = null; return }
        pending = null
        close(suppressCurrent = false)
        current = req
        fullscreen = false
        muted = true
        ready = false

        val host = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            elevation = dp(12).toFloat()
            isFocusable = false
        }
        holder = host
        val video = LayoutInflater.from(activity).inflate(R.layout.view_inline_stream_player, host, false)
            as PlayerView
        playerView = video
        host.addView(video, FrameLayout.LayoutParams(-1, -1))

        val control = Button(activity).apply {
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
            setOnClickListener {
                if (muted) {
                    muted = false
                    exo?.volume = 1f
                    text = "Full screen"
                } else {
                    fullscreen = true
                    oldUiFlags = activity.window.decorView.systemUiVisibility
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                        (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                         or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                         or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    position(box)
                    root.post { if (fullscreen) position(box) }
                    visibility = View.GONE
                }
            }
        }
        button = control
        host.addView(control, FrameLayout.LayoutParams(dp(124), dp(48), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(8); topMargin = dp(8)
        })
        root.addView(host, RelativeLayout.LayoutParams(dp(200), dp(120)))
        host.visibility = View.INVISIBLE // website remains visible while native player buffers
        position(box)

        val headers = mutableMapOf<String, String>()
        if (req.origin.startsWith("http://") || req.origin.startsWith("https://")) {
            headers["Referer"] = req.origin
        }
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true)
        req.userAgent?.takeIf { it.isNotBlank() }?.let { http.setUserAgent(it) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(activity, http))
        val p = ExoPlayer.Builder(activity).setMediaSourceFactory(factory).build()
        exo = p
        video.player = p
        val mime = if (req.url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT).endsWith(".mpd"))
            MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (current?.url != req.url) return
                if (state == Player.STATE_READY) {
                    ready = true
                    cancelTimeout()
                    host.visibility = if (menuVisible) View.INVISIBLE else View.VISIBLE
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onActiveChanged(true)
                    if (!menuVisible) control.requestFocus()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (current?.url != req.url) return
                onFailed(req.url)
                close(suppressCurrent = true)
            }
        })
        p.volume = 0f
        p.setMediaItem(MediaItem.Builder().setUri(req.url).setMimeType(mime).build())
        p.prepare()
        p.playWhenReady = true
        val timeoutTask = Runnable {
            if (current?.url == req.url && !ready) {
                onFailed(req.url)
                close(suppressCurrent = true)
            }
        }
        timeout = timeoutTask
        handler.postDelayed(timeoutTask, 15000)
    }

    private fun cancelTimeout() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }

    private fun position(box: Box) {
        val host = holder ?: return
        val web = webView() ?: return
        if (root.width <= 0 || root.height <= 0 || web.width <= 0 || web.height <= 0) return
        val webLocation = IntArray(2)
        val rootLocation = IntArray(2)
        web.getLocationOnScreen(webLocation)
        root.getLocationOnScreen(rootLocation)
        val w = root.width; val h = root.height
        val left: Int; val top: Int; val width: Int; val height: Int
        if (fullscreen) {
            left = 0; top = 0; width = w; height = h
        } else {
            val scaleX = web.width / box.viewportWidth
            val scaleY = web.height / box.viewportHeight
            left = (webLocation[0] - rootLocation[0] + box.x * scaleX).roundToInt().coerceIn(0, w)
            top = (webLocation[1] - rootLocation[1] + box.y * scaleY).roundToInt().coerceIn(0, h)
            width = (box.width * scaleX).roundToInt().coerceAtMost(w - left).coerceAtLeast(1)
            height = (box.height * scaleY).roundToInt().coerceAtMost(h - top).coerceAtLeast(1)
        }
        val lp = RelativeLayout.LayoutParams(width, height)
        lp.leftMargin = left; lp.topMargin = top
        host.layoutParams = lp
    }

    fun isActive() = current != null

    /** Back exits fullscreen first; the next Back restores clickable site server buttons. */
    fun handleBack(): Boolean {
        if (current == null) return false
        if (fullscreen) {
            fullscreen = false
            oldUiFlags?.let { activity.window.decorView.systemUiVisibility = it }
            oldUiFlags = null
            button?.visibility = View.VISIBLE
            current?.let { bestBox(it)?.let(::position) }
            root.post { current?.let { bestBox(it)?.let(::position) } }
        } else {
            close(suppressCurrent = true)
        }
        return true
    }

    fun setMenuVisible(visible: Boolean) {
        menuVisible = visible
        holder?.visibility = if (visible || !ready) View.INVISIBLE else View.VISIBLE
        if (ready) exo?.playWhenReady = !visible
    }

    fun close(suppressCurrent: Boolean = false) {
        handler.removeCallbacks(startPending)
        cancelTimeout()
        if (suppressCurrent) current?.url?.let { cooldown[it] = SystemClock.uptimeMillis() + 120000L }
        if (fullscreen) oldUiFlags?.let { activity.window.decorView.systemUiVisibility = it }
        oldUiFlags = null
        fullscreen = false
        val wasReady = ready
        ready = false
        val video = playerView
        video?.player = null
        exo?.release()
        exo = null
        holder?.let { root.removeView(it) }
        holder = null
        playerView = null
        button = null
        current = null
        pending = null
        if (wasReady) onActiveChanged(false)
    }

    fun reset() {
        close(suppressCurrent = false)
        boxes.clear()
    }
}
