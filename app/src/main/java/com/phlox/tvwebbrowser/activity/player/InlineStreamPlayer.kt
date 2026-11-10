package com.phlox.tvwebbrowser.activity.player

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import java.util.Locale
import kotlin.math.roundToInt

class InlineStreamPlayer(
private val activity: AppCompatActivity,
private val root: RelativeLayout,
private val webView: () -> View?,
private val onActiveChanged: (Boolean) -> Unit,
private val onFailed: (String) -> Unit,
private val onFullscreenRequested: () -> Unit = {}
) {
private companion object {
const val TAG = "MomBrowserMedia3"
const val STARTUP_TIMEOUT_MS = 120_000L
const val REBUFFER_TIMEOUT_MS = 90_000L
}
private data class Box(
val frameUrl: String,
val x: Double,
val y: Double,
val width: Double,
val height: Double,
val viewportWidth: Double,
val viewportHeight: Double,
val updated: Long
)
private data class Request(
val url: String,
val origin: String,
val userAgent: String?
)
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
private var lastInlineBox: Box? = null
private var muted = true
private var menuVisible = false
private var ready = false
private var oldUiFlags: Int? = null
private var timeout: Runnable? = null
private var bufferingTimeout: Runnable? = null
private var attemptedBufferingRecovery = false
private val startPending = Runnable { startIfPositioned() }
private val lifecycleObserver = object : DefaultLifecycleObserver {
override fun onResume(owner: LifecycleOwner) {
if (pending != null) handler.post(startPending)
}
override fun onPause(owner: LifecycleOwner) {
reset()
}
override fun onDestroy(owner: LifecycleOwner) {
reset()
owner.lifecycle.removeObserver(this)
}
}
init {
activity.lifecycle.addObserver(lifecycleObserver)
}
private val playerLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
current?.let { request ->
val box = bestBox(request) ?: if (fullscreen) {
Box(request.origin, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0L)
} else lastInlineBox
box?.let(::position)
}
}
private var consumingCursorTouch = false
private var cursorDelegate: CursorDrawerDelegate? = null
private var lastCursorX = Float.NaN
private var lastCursorY = Float.NaN
private var lastCursorVisible = false
private val cursorRedrawHook:
(CursorDrawerDelegate) -> Unit = { delegate ->
val host = holder
if (host != null &&
host.isShown &&
ready &&
!menuVisible &&
delegate.surface === webView()
) {
cursorDelegate = delegate
val x = delegate.cursorPosition.x
val y = delegate.cursorPosition.y
val visible = delegate.isCursorVisibleForOverlay()
if (x != lastCursorX ||
y != lastCursorY ||
visible != lastCursorVisible
) {
lastCursorX = x
lastCursorY = y
lastCursorVisible = visible
host.postInvalidateOnAnimation()
}
}
}
private fun drawCursorOnPlayer(host: View, canvas: Canvas) {
val delegate = cursorDelegate ?: return
val hLoc = IntArray(2)
val wLoc = IntArray(2)
host.getLocationOnScreen(hLoc)
delegate.surface.getLocationOnScreen(wLoc)
val cx = wLoc[0] + delegate.cursorPosition.x - hLoc[0]
val cy = wLoc[1] + delegate.cursorPosition.y - hLoc[1]
if (cx < 0f ||
cy < 0f ||
cx > host.width ||
cy > host.height
) return
delegate.drawCursorAt(canvas, cx, cy)
}
private fun cursorOverButton(
web: View,
x: Float,
y: Float
): Boolean {
val b = button ?: return false
val host = holder ?: return false
if (web !== webView() ||
!ready ||
menuVisible ||
!host.isShown ||
fullscreen ||
b.visibility != View.VISIBLE ||
b.width <= 0 ||
b.height <= 0
) return false
val bLoc = IntArray(2)
val wLoc = IntArray(2)
b.getLocationOnScreen(bLoc)
web.getLocationOnScreen(wLoc)
val sx = wLoc[0] + x
val sy = wLoc[1] + y
return sx >= bLoc[0] &&
sx <= bLoc[0] + b.width &&
sy >= bLoc[1] &&
sy <= bLoc[1] + b.height
}
private val cursorInterceptor:
(View, Float, Float, Int) -> Boolean = { web, x, y, action ->
if (web !== webView() ||
!ready ||
menuVisible ||
holder?.isShown != true
) {
consumingCursorTouch = false
false
} else when (action) {
MotionEvent.ACTION_DOWN -> {
consumingCursorTouch = cursorOverButton(web, x, y)
consumingCursorTouch
}
MotionEvent.ACTION_MOVE -> consumingCursorTouch
MotionEvent.ACTION_UP -> {
val consumed = consumingCursorTouch
consumingCursorTouch = false
if (consumed && cursorOverButton(web, x, y)) {
button?.let { b ->
b.post { b.performClick() }
}
}
consumed
}
MotionEvent.ACTION_CANCEL -> {
val consumed = consumingCursorTouch
consumingCursorTouch = false
consumed
}
else -> false
}
}
private fun dp(value: Int) =
(value * activity.resources.displayMetrics.density + .5f).toInt()
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
handler.postDelayed(startPending, 750L)
}
fun updateRect(
frameUrl: String,
x: Double,
y: Double,
width: Double,
height: Double,
viewportWidth: Double,
viewportHeight: Double
) {
val values = doubleArrayOf(
x, y, width, height, viewportWidth, viewportHeight
)
if (values.any { it.isNaN() || it.isInfinite() } ||
viewportWidth < 100 ||
viewportHeight < 80 ||
width < 50 ||
height < 40 ||
frameUrl.length > 2048
) return
boxes[frameUrl] = Box(
frameUrl,
x,
y,
width,
height,
viewportWidth,
viewportHeight,
SystemClock.uptimeMillis()
)
if (current != null && !fullscreen) {
bestBox(current!!)?.let { position(it) }
}
if (pending != null &&
SystemClock.uptimeMillis() >= pendingReadyAt
) {
handler.removeCallbacks(startPending)
handler.post(startPending)
}
}
private fun bestBox(request: Request): Box? {
val now = SystemClock.uptimeMillis()
val originUri = runCatching {
Uri.parse(request.origin)
}.getOrNull()
val match = boxes.values.filter {
now - it.updated < 6500L
}
if (match.isEmpty()) return null
fun score(b: Box): Double {
val frameUri = runCatching {
Uri.parse(b.frameUrl)
}.getOrNull()
val exact = request.origin.isNotBlank() &&
b.frameUrl.substringBefore('#') ==
request.origin.substringBefore('#')
val sameHost = originUri?.host != null &&
originUri.host == frameUri?.host
val area =
(b.width * b.height) /
(b.viewportWidth * b.viewportHeight)
return area +
if (exact) 10.0 else if (sameHost) 5.0 else 0.0
}
return match.maxByOrNull { score(it) }
}
private fun startIfPositioned() {
if (!activity.lifecycle.currentState
.isAtLeast(Lifecycle.State.RESUMED)
) return
val req = pending ?: return
val box = bestBox(req) ?: return
if (current?.url == req.url) {
pending = null
return
}
pending = null
close(suppressCurrent = false)
current = req
fullscreen = false
muted = true
ready = false
attemptedBufferingRecovery = false
cancelBufferingTimeout()
val host = object : FrameLayout(activity) {
override fun dispatchDraw(canvas: Canvas) {
super.dispatchDraw(canvas)
drawCursorOnPlayer(this, canvas)
}
}.apply {
setBackgroundColor(Color.BLACK)
tag = "mom_inline_stream_surface"
elevation = dp(12).toFloat()
isFocusable = false
}
holder = host
root.addOnLayoutChangeListener(playerLayoutListener)
val video = LayoutInflater.from(activity).inflate(
R.layout.view_inline_stream_player,
host,
false
) as PlayerView
playerView = video
host.addView(video, FrameLayout.LayoutParams(-1, -1))
val control = Button(activity).apply {
text = "Unmute"
isAllCaps = false
setTextColor(Color.WHITE)
textSize = 13f
typeface = Typeface.create(
"sans-serif-medium",
Typeface.NORMAL
)
background = GradientDrawable().apply {
setColor(Color.rgb(48, 54, 61))
cornerRadius = dp(26).toFloat()
setStroke(dp(1), Color.rgb(216, 222, 232))
}
isFocusable = false
isFocusableInTouchMode = false
tag = "mom_inline_stream_button"
setOnClickListener { handleControlClick(box) }
}
button = control
host.addView(
control,
FrameLayout.LayoutParams(
dp(124),
dp(48),
Gravity.BOTTOM or Gravity.LEFT
).apply {
leftMargin = dp(8)
bottomMargin = dp(8)
}
)
root.addView(
host,
RelativeLayout.LayoutParams(dp(200), dp(120))
)
host.visibility = View.INVISIBLE
position(box)
val headers = mutableMapOf<String, String>()
if (req.origin.startsWith("http://") ||
req.origin.startsWith("https://")
) {
headers["Referer"] = req.origin
}
val http = DefaultHttpDataSource.Factory()
.setDefaultRequestProperties(headers)
.setAllowCrossProtocolRedirects(true)
.setConnectTimeoutMs(60000)
.setReadTimeoutMs(60000)
req.userAgent?.takeIf {
it.isNotBlank()
}?.let {
http.setUserAgent(it)
}
val factory = DefaultMediaSourceFactory(
DefaultDataSource.Factory(activity, http)
)
val renderers = DefaultRenderersFactory(activity)
.setEnableDecoderFallback(true)
val p = ExoPlayer.Builder(activity, renderers)
.setMediaSourceFactory(factory)
.build()
exo = p
video.player = p
val mime = if (
req.url.substringBefore('?')
.substringBefore('#')
.lowercase(Locale.ROOT)
.endsWith(".mpd")
) {
MimeTypes.APPLICATION_MPD
} else {
MimeTypes.APPLICATION_M3U8
}
p.addListener(object : Player.Listener {
override fun onPlaybackStateChanged(state: Int) {
if (current?.url != req.url || exo !== p) return
Log.d(
TAG,
"Media3 state=$state host=${Uri.parse(req.url).host}"
)
when (state) {
Player.STATE_READY -> {
cancelBufferingTimeout()
if (!ready) {
ready = true
cancelTimeout()
host.visibility = View.VISIBLE
CursorDrawerDelegate.touchInterceptor =
cursorInterceptor
CursorDrawerDelegate.overlayRedrawHook =
cursorRedrawHook
activity.window.addFlags(
WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
)
onActiveChanged(true)
p.playWhenReady = activity.lifecycle.currentState
.isAtLeast(Lifecycle.State.RESUMED)
}
}
Player.STATE_BUFFERING -> {
if (ready) {
scheduleBufferingRecovery(p, req.url)
}
}
else -> cancelBufferingTimeout()
}
}
override fun onIsPlayingChanged(isPlaying: Boolean) {
if (current?.url != req.url || exo !== p) return
if (isPlaying) {
cancelBufferingTimeout()
} else if (
ready &&
p.playbackState == Player.STATE_BUFFERING
) {
scheduleBufferingRecovery(p, req.url)
}
}
override fun onPlayerError(error: PlaybackException) {
if (current?.url != req.url || exo !== p) return
Log.e(
TAG,
"Media3 error code=${error.errorCode} host=${Uri.parse(req.url).host}",
error
)
onFailed(req.url)
close(suppressCurrent = true)
}
})
p.volume = 0f
p.setMediaItem(
MediaItem.Builder()
.setUri(req.url)
.setMimeType(mime)
.build()
)
p.prepare()
p.playWhenReady = true
val timeoutTask = Runnable {
if (current?.url == req.url && exo === p && !ready) {
Log.w(
TAG,
"Initial stream did not reach READY within ${STARTUP_TIMEOUT_MS / 1000}s"
)
onFailed(req.url)
close(suppressCurrent = true)
}
}
timeout = timeoutTask
handler.postDelayed(timeoutTask, STARTUP_TIMEOUT_MS)
Log.d(
TAG,
"Waiting up to ${STARTUP_TIMEOUT_MS / 1000}s for initial stream readiness"
)
}
private fun handleControlClick(box: Box) {
if (muted) {
muted = false
exo?.volume = 1f
button?.text = "Full screen"
return
}
if (fullscreen) return
fullscreen = true
oldUiFlags = activity.window.decorView.systemUiVisibility
@Suppress("DEPRECATION")
activity.window.decorView.systemUiVisibility =
(View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
View.SYSTEM_UI_FLAG_FULLSCREEN or
View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
position(box)
root.post { if (fullscreen) position(box) }
button?.visibility = View.GONE
onFullscreenRequested()
}
private fun scheduleBufferingRecovery(
player: ExoPlayer,
url: String
) {
if (bufferingTimeout != null ||
attemptedBufferingRecovery ||
!ready ||
!player.playWhenReady ||
player.playbackState != Player.STATE_BUFFERING
) return
val task = Runnable {
bufferingTimeout = null
if (exo !== player ||
current?.url != url ||
!ready ||
!player.playWhenReady ||
player.playbackState != Player.STATE_BUFFERING ||
attemptedBufferingRecovery
) return@Runnable
attemptedBufferingRecovery = true
Log.w(
TAG,
"Stream stalled after playback; attempting one Media3 reconnect"
)
player.stop()
player.prepare()
player.playWhenReady = true
}
bufferingTimeout = task
handler.postDelayed(task, REBUFFER_TIMEOUT_MS)
}
private fun cancelBufferingTimeout() {
bufferingTimeout?.let { handler.removeCallbacks(it) }
bufferingTimeout = null
}
private fun cancelTimeout() {
timeout?.let { handler.removeCallbacks(it) }
timeout = null
}
private fun position(box: Box) {
if (!fullscreen) lastInlineBox = box
val host = holder ?: return
val web = webView() ?: return
if (root.width <= 0 ||
root.height <= 0 ||
web.width <= 0 ||
web.height <= 0
) return
val webLocation = IntArray(2)
val rootLocation = IntArray(2)
web.getLocationOnScreen(webLocation)
root.getLocationOnScreen(rootLocation)
val w = root.width
val h = root.height
val left: Int
val top: Int
val width: Int
val height: Int
if (fullscreen) {
left = 0
top = 0
width = w
height = h
} else {
val scaleX = web.width / box.viewportWidth
val scaleY = web.height / box.viewportHeight
left = (
webLocation[0] - rootLocation[0] + box.x * scaleX
).roundToInt().coerceIn(0, w)
top = (
webLocation[1] - rootLocation[1] + box.y * scaleY
).roundToInt().coerceIn(0, h)
width = (box.width * scaleX)
.roundToInt()
.coerceAtMost(w - left)
.coerceAtLeast(1)
height = (box.height * scaleY)
.roundToInt()
.coerceAtMost(h - top)
.coerceAtLeast(1)
}
val existing =
host.layoutParams as? RelativeLayout.LayoutParams
if (existing != null &&
existing.width == width &&
existing.height == height &&
existing.leftMargin == left &&
existing.topMargin == top
) return
val lp = RelativeLayout.LayoutParams(width, height)
lp.leftMargin = left
lp.topMargin = top
host.layoutParams = lp
}
fun isActive() = current != null
fun handleBack(): Boolean {
if (current == null) return false
if (fullscreen) {
fullscreen = false
oldUiFlags?.let {
activity.window.decorView.systemUiVisibility = it
}
oldUiFlags = null
button?.visibility = View.VISIBLE
current?.let {
(bestBox(it) ?: lastInlineBox)?.let(::position)
}
root.post {
current?.let {
(bestBox(it) ?: lastInlineBox)?.let(::position)
}
}
} else {
close(suppressCurrent = true)
}
return true
}
fun setMenuVisible(visible: Boolean) {
menuVisible = visible
holder?.visibility = if (ready) View.VISIBLE else View.INVISIBLE
consumingCursorTouch = false
lastCursorX = Float.NaN
lastCursorY = Float.NaN
}
fun close(suppressCurrent: Boolean = false) {
root.removeOnLayoutChangeListener(playerLayoutListener)
handler.removeCallbacks(startPending)
cancelTimeout()
cancelBufferingTimeout()
attemptedBufferingRecovery = false
if (suppressCurrent) {
current?.url?.let {
cooldown[it] =
SystemClock.uptimeMillis() + 120000L
}
}
if (fullscreen) {
oldUiFlags?.let {
activity.window.decorView.systemUiVisibility = it
}
}
oldUiFlags = null
fullscreen = false
val wasReady = ready
ready = false
consumingCursorTouch = false
cursorDelegate = null
lastCursorX = Float.NaN
lastCursorY = Float.NaN
lastCursorVisible = false
if (CursorDrawerDelegate.touchInterceptor === cursorInterceptor) {
CursorDrawerDelegate.touchInterceptor = null
}
if (CursorDrawerDelegate.overlayRedrawHook === cursorRedrawHook) {
CursorDrawerDelegate.overlayRedrawHook = null
}
playerView?.player = null
exo?.release()
exo = null
holder?.let { root.removeView(it) }
holder = null
playerView = null
button = null
current = null
pending = null
lastInlineBox = null
if (wasReady) onActiveChanged(false)
}
fun reset() {
close(suppressCurrent = false)
boxes.clear()
}
}
