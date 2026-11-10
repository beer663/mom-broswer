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
const val MAX_VIDEO_VIEW_SIZE = 16_384
const val MAX_VIDEO_VIEW_AREA = 16_777_216.0
}

private data class Box(
val frameUrl: String,
val x: Double,
val y: Double,
val width: Double,
val height: Double,
val viewportWidth: Double,
val viewportHeight: Double,
val updated: Long,
val clipX: Double = x,
val clipY: Double = y,
val clipWidth: Double = width,
val clipHeight: Double = height,
val measuredAt: Long = 0L
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
private var selectedFrameUrl: String? = null
private var geometryCutoff = 0L
private var positionVisible = false
private var muted = true
private var menuVisible = false
private var ready = false
private var nativePlaybackActive = false
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
if (current != null) lastInlineBox?.let(::position)
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
val active = current
if (active != null && !sameDocument(active.origin, origin)) return
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
viewportHeight: Double,
clipX: Double = x,
clipY: Double = y,
clipWidth: Double = width,
clipHeight: Double = height,
measuredAt: Long = 0L
) {
val values = doubleArrayOf(
x, y, width, height, viewportWidth, viewportHeight,
clipX, clipY, clipWidth, clipHeight
)
if (values.any { it.isNaN() || it.isInfinite() } ||
viewportWidth < 100 || viewportHeight < 80 ||
width < 50 || height < 40 || clipWidth < 0 || clipHeight < 0 ||
width / viewportWidth > 8 || height / viewportHeight > 8 ||
clipWidth > width + 1.0 || clipHeight > height + 1.0 ||
frameUrl.length > 2048 || measuredAt < 0
) return

val web = webView()
if (web != null && web.width > 0 && web.height > 0 && root.width > 0 && root.height > 0) {
val mappedWidth = width * web.width / viewportWidth
val mappedHeight = height * web.height / viewportHeight
val maxArea = minOf(MAX_VIDEO_VIEW_AREA, root.width.toDouble() * root.height * 4)
if (!mappedWidth.isFinite() || !mappedHeight.isFinite() ||
mappedWidth > MAX_VIDEO_VIEW_SIZE || mappedHeight > MAX_VIDEO_VIEW_SIZE ||
mappedWidth * mappedHeight > maxArea) return
}

val frameKey = frameUrl.substringBefore('#')
if (fullscreen) return
if (measuredAt > 0 && measuredAt < geometryCutoff) return
val pinnedFrame = selectedFrameUrl
if (current != null && pinnedFrame != null && frameKey != pinnedFrame) return
val previous = boxes[frameKey]
if (measuredAt > 0 && previous != null && previous.measuredAt > measuredAt) return

val box = Box(frameKey, x, y, width, height, viewportWidth, viewportHeight,
SystemClock.uptimeMillis(), clipX, clipY, clipWidth, clipHeight, measuredAt)
boxes[frameKey] = box
if (current != null) position(box)
if (pending != null && SystemClock.uptimeMillis() >= pendingReadyAt) {
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
now - it.updated < 6500L && it.clipWidth > 0 && it.clipHeight > 0
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

private fun sameDocument(first: String, second: String): Boolean =
first.substringBefore('#') == second.substringBefore('#')

private fun adoptRequest(req: Request, box: Box) {
val replacing = current?.let { sameDocument(it.origin, req.origin) } == true
val restoreFullscreen = replacing && fullscreen
val restoreMuted = if (replacing) muted else true
val restoreOldFlags = if (restoreFullscreen) oldUiFlags else null
val restoreDecorFlags = activity.window.decorView.systemUiVisibility
close(suppressCurrent = false, preserveNativePlayback = replacing)
current = req
selectedFrameUrl = box.frameUrl
lastInlineBox = box
fullscreen = restoreFullscreen
muted = restoreMuted
oldUiFlags = restoreOldFlags
if (restoreFullscreen) activity.window.decorView.systemUiVisibility = restoreDecorFlags
ready = false
attemptedBufferingRecovery = false
cancelBufferingTimeout()
}

private fun startIfPositioned() {
if (!activity.lifecycle.currentState
.isAtLeast(Lifecycle.State.RESUMED)
) return
val req = pending ?: return
val active = current
if (active != null && !sameDocument(active.origin, req.origin)) {
pending = null
return
}
val box = if (active != null) lastInlineBox ?: bestBox(req) else bestBox(req)
if (box == null) return
if (active?.url == req.url) {
pending = null
return
}
adoptRequest(req, box)

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
clipChildren = true
clipToPadding = true
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
text = if (muted) "Unmute" else "Full screen"
visibility = if (fullscreen) View.GONE else View.VISIBLE
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
updateHolderVisibility()
CursorDrawerDelegate.touchInterceptor =
cursorInterceptor
CursorDrawerDelegate.overlayRedrawHook =
cursorRedrawHook
activity.window.addFlags(
WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
)
if (!nativePlaybackActive) {
nativePlaybackActive = true
onActiveChanged(true)
}
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

p.volume = if (muted) 0f else 1f
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

private fun updateHolderVisibility() {
holder?.visibility = if (ready && positionVisible) View.VISIBLE else View.INVISIBLE
}

private fun position(box: Box) {
if (!fullscreen) lastInlineBox = box
val host = holder ?: return
val web = webView() ?: return
if (root.width <= 0 || root.height <= 0 || web.width <= 0 || web.height <= 0) {
positionVisible = false
updateHolderVisibility()
return
}

val webLocation = IntArray(2)
val rootLocation = IntArray(2)
web.getLocationOnScreen(webLocation)
root.getLocationOnScreen(rootLocation)
val offsetX = webLocation[0] - rootLocation[0]
val offsetY = webLocation[1] - rootLocation[1]
val scaleX = web.width / box.viewportWidth
val scaleY = web.height / box.viewportHeight
val rawLeft = if (fullscreen) 0.0 else offsetX + box.x * scaleX
val rawTop = if (fullscreen) 0.0 else offsetY + box.y * scaleY
val rawWidth = if (fullscreen) root.width.toDouble() else box.width * scaleX
val rawHeight = if (fullscreen) root.height.toDouble() else box.height * scaleY
val maxArea = minOf(MAX_VIDEO_VIEW_AREA, root.width.toDouble() * root.height * 4)
if (!rawWidth.isFinite() || !rawHeight.isFinite() || rawWidth > MAX_VIDEO_VIEW_SIZE ||
rawHeight > MAX_VIDEO_VIEW_SIZE || rawWidth * rawHeight > maxArea ||
rawWidth < 1 || rawHeight < 1) {
positionVisible = false
updateHolderVisibility()
return
}

val left: Double
val top: Double
val right: Double
val bottom: Double
if (fullscreen) {
left = 0.0
top = 0.0
right = root.width.toDouble()
bottom = root.height.toDouble()
} else {
left = maxOf(0.0, offsetX.toDouble(), rawLeft, offsetX + box.clipX * scaleX)
top = maxOf(0.0, offsetY.toDouble(), rawTop, offsetY + box.clipY * scaleY)
right = minOf(root.width.toDouble(), (offsetX + web.width).toDouble(), rawLeft + rawWidth,
offsetX + (box.clipX + box.clipWidth) * scaleX)
bottom = minOf(root.height.toDouble(), (offsetY + web.height).toDouble(), rawTop + rawHeight,
offsetY + (box.clipY + box.clipHeight) * scaleY)
}

val visibleWidth = (right - left).coerceAtLeast(0.0).roundToInt()
val visibleHeight = (bottom - top).coerceAtLeast(0.0).roundToInt()
positionVisible = visibleWidth > 0 && visibleHeight > 0
val hostLeft = left.coerceIn(0.0, root.width.toDouble()).roundToInt()
val hostTop = top.coerceIn(0.0, root.height.toDouble()).roundToInt()
val existing = host.layoutParams as? RelativeLayout.LayoutParams
if (existing == null || existing.width != visibleWidth || existing.height != visibleHeight ||
existing.leftMargin != hostLeft || existing.topMargin != hostTop) {
host.layoutParams = RelativeLayout.LayoutParams(visibleWidth, visibleHeight).apply {
leftMargin = hostLeft
topMargin = hostTop
}
}

if (positionVisible) playerView?.let { video ->
val videoWidth = rawWidth.roundToInt()
val videoHeight = rawHeight.roundToInt()
val videoLeft = (rawLeft - hostLeft).roundToInt()
val videoTop = (rawTop - hostTop).roundToInt()
val child = video.layoutParams as? FrameLayout.LayoutParams
if (child == null || child.width != videoWidth || child.height != videoHeight ||
child.leftMargin != videoLeft || child.topMargin != videoTop) {
video.layoutParams = FrameLayout.LayoutParams(videoWidth, videoHeight, Gravity.TOP or Gravity.LEFT).apply {
leftMargin = videoLeft
topMargin = videoTop
}
}
}
updateHolderVisibility()
}

fun isActive() = current != null

fun handleBack(): Boolean {
if (current == null) return false
if (fullscreen) {
fullscreen = false
geometryCutoff = System.currentTimeMillis()
oldUiFlags?.let {
activity.window.decorView.systemUiVisibility = it
}
oldUiFlags = null
button?.visibility = View.VISIBLE
lastInlineBox?.let(::position)
root.post {
if (current != null && !fullscreen) lastInlineBox?.let(::position)
}
} else {
close(suppressCurrent = true)
}
return true
}

fun setMenuVisible(visible: Boolean) {
menuVisible = visible
updateHolderVisibility()
consumingCursorTouch = false
lastCursorX = Float.NaN
lastCursorY = Float.NaN
}

fun close(suppressCurrent: Boolean = false, preserveNativePlayback: Boolean = false) {
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
val hadNativePlayback = nativePlaybackActive
if (!preserveNativePlayback) nativePlaybackActive = false
ready = false
positionVisible = false
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
selectedFrameUrl = null
geometryCutoff = 0L
if (hadNativePlayback && !preserveNativePlayback) onActiveChanged(false)
}

fun reset() {
close(suppressCurrent = false)
boxes.clear()
}
}
