package com.phlox.tvwebbrowser.activity.main.view
import android.content.Context
import android.graphics.Canvas
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate

class MenuCursorLayout(
context: Context,
private val activityRoot: ViewGroup,
private val toolbar: View,
private val bottomPanel: View
) : FrameLayout(context) {
val cursorDrawerDelegate = CursorDrawerDelegate(context, this)
private val inputEventsAdapter = DPADNavigationEventsAdapter(
onEmulatedKeyEvent = {
cursorDrawerDelegate.dispatchKeyEvent(it)
},
motionAxesTranslationEnabled = {
!AppContext.provideConfig().disableMotionAxesDpadNavigation
},
isSoftwareKeyboardVisible = {
ViewCompat.getRootWindowInsets(rootView)
?.isVisible(WindowInsetsCompat.Type.ime()) == true
}
)
private var gestureTarget: View? = null
private var closeMenuOnUp = false
private var forwarding = false
var pageTapsEnabled: () -> Boolean = { false }
var onPageTap: () -> Unit = {}

init {
setWillNotDraw(false)
cursorDrawerDelegate.init()
isFocusable = false
isClickable = false
val menuElevation = 16f * resources.displayMetrics.density
toolbar.elevation = maxOf(toolbar.elevation, menuElevation)
bottomPanel.elevation = maxOf(bottomPanel.elevation, menuElevation)
cursorDrawerDelegate.customScrollCallback =
object : CursorDrawerDelegate.CustomScrollCallback {
override fun onScroll(scrollX: Int, scrollY: Int) = true
}
}

fun stopCursor() {
val directions = intArrayOf(
KeyEvent.KEYCODE_DPAD_LEFT,
KeyEvent.KEYCODE_DPAD_RIGHT,
KeyEvent.KEYCODE_DPAD_UP,
KeyEvent.KEYCODE_DPAD_DOWN,
KeyEvent.KEYCODE_DPAD_UP_LEFT,
KeyEvent.KEYCODE_DPAD_UP_RIGHT,
KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
KeyEvent.KEYCODE_DPAD_DOWN_RIGHT
)
for (key in directions) {
cursorDrawerDelegate.dispatchKeyEvent(
KeyEvent(KeyEvent.ACTION_UP, key)
)
}
inputEventsAdapter.resetState()
cancelGesture()
cursorDrawerDelegate.hideCursor()
}

override fun onSizeChanged(
w: Int, h: Int, oldw: Int, oldh: Int
) {
super.onSizeChanged(w, h, oldw, oldh)
cursorDrawerDelegate.onSizeChanged(w, h, oldw, oldh)
}
override fun dispatchKeyEvent(event: KeyEvent): Boolean =
inputEventsAdapter.dispatchKeyEvent(event) ||
super.dispatchKeyEvent(event)

override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
inputEventsAdapter.dispatchGenericMotionEvent(event) ||
super.dispatchGenericMotionEvent(event)

override fun dispatchDraw(canvas: Canvas) {
super.dispatchDraw(canvas)
cursorDrawerDelegate.dispatchDraw(canvas)
}

override fun dispatchTouchEvent(event: MotionEvent): Boolean {
if (event.source != InputDevice.SOURCE_UNKNOWN || forwarding) {
return false
}
if (event.actionMasked == MotionEvent.ACTION_DOWN) {
cancelGesture()
val page = activityRoot.findViewById<View>(
R.id.flWebViewContainer
)
val playerButton = activityRoot.findViewWithTag<View>(
"mom_inline_stream_button"
)
val playerSurface = activityRoot.findViewWithTag<View>(
"mom_inline_stream_surface"
)
gestureTarget = when {
inside(toolbar, event.x, event.y) -> toolbar
interactiveAt(bottomPanel, event.x, event.y) -> bottomPanel
playerButton != null &&
inside(playerButton, event.x, event.y) -> playerButton
playerSurface != null &&
inside(playerSurface, event.x, event.y) -> null
page != null &&
inside(page, event.x, event.y) -> page
else -> null
}
closeMenuOnUp =
gestureTarget === page && page != null && pageTapsEnabled()
}
val target = gestureTarget ?: return true
if (!target.isAttachedToWindow) {
gestureTarget = null
closeMenuOnUp = false
return true
}
val translated = MotionEvent.obtain(event)
val overlayPosition = IntArray(2)
val targetPosition = IntArray(2)
getLocationOnScreen(overlayPosition)
target.getLocationOnScreen(targetPosition)
translated.offsetLocation(
(overlayPosition[0] - targetPosition[0]).toFloat(),
(overlayPosition[1] - targetPosition[1]).toFloat()
)
forwarding = true
val handled = try {
target.dispatchTouchEvent(translated)
} finally {
forwarding = false
translated.recycle()
}
if (event.actionMasked == MotionEvent.ACTION_UP ||
event.actionMasked == MotionEvent.ACTION_CANCEL) {
val close =
event.actionMasked == MotionEvent.ACTION_UP &&
closeMenuOnUp && handled
gestureTarget = null
closeMenuOnUp = false
if (close) post { onPageTap() }
}
return true
}

private fun cancelGesture() {
val target = gestureTarget
gestureTarget = null
closeMenuOnUp = false
if (target == null || !target.isAttachedToWindow) return
val now = android.os.SystemClock.uptimeMillis()
val cancel = MotionEvent.obtain(
now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0
)
try {
target.dispatchTouchEvent(cancel)
} finally {
cancel.recycle()
}
}

private fun interactiveAt(
view: View, x: Float, y: Float
): Boolean {
if (!inside(view, x, y)) return false
if (view.isClickable || view.isLongClickable) return true
if (view is ViewGroup) {
for (i in 0 until view.childCount) {
if (interactiveAt(view.getChildAt(i), x, y)) return true
}
}
return false
}

private fun inside(
view: View, x: Float, y: Float
): Boolean {
if (!view.isShown || view.width <= 0 || view.height <= 0) {
return false
}
val position = IntArray(2)
val cursor = IntArray(2)
view.getLocationOnScreen(position)
getLocationOnScreen(cursor)
val sx = cursor[0] + x
val sy = cursor[1] + y
return sx >= position[0] &&
sx < position[0] + view.width &&
sy >= position[1] &&
sy < position[1] + view.height
}
}
