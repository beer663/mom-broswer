package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.graphics.Canvas
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
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
        onEmulatedKeyEvent = { cursorDrawerDelegate.dispatchKeyEvent(it) },
        motionAxesTranslationEnabled = {
            !AppContext.provideConfig().disableMotionAxesDpadNavigation
        },
        isSoftwareKeyboardVisible = {
            ViewCompat.getRootWindowInsets(rootView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    )
    private var gestureTarget: View? = null
    private var clickTarget: View? = null
    private var closeMenuOnUp = false
    private var closeMenuAfterClick = false
    private var forwarding = false
    private var longPressHandled = false
    private val longPress = Runnable {
        val target = clickTarget
        if (target != null && target.isAttachedToWindow &&
            target.isShown && target.isEnabled && target.isPressed) {
            longPressHandled = target.performLongClick()
        }
    }

    var pageTapsEnabled: () -> Boolean = { false }
    var onPageTap: () -> Unit = {}
    var scrollPageBy: (Int, Int) -> Boolean = { _, _ -> false }

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
                override fun onScroll(scrollX: Int, scrollY: Int): Boolean {
                    scrollPageBy(scrollX, scrollY)
                    return true
                }
            }
    }

    fun stopCursor() {
        // Cancel before hiding the cursor: hideCursor can synthesize an UP.
        cancelGesture()
        val directions = intArrayOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT
        )
        for (key in directions) {
            cursorDrawerDelegate.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
        inputEventsAdapter.resetState()
        cursorDrawerDelegate.hideCursor()
    }

    override fun onDetachedFromWindow() {
        stopCursor()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cursorDrawerDelegate.onSizeChanged(w, h, oldw, oldh)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inputEventsAdapter.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        inputEventsAdapter.dispatchGenericMotionEvent(event) ||
            super.dispatchGenericMotionEvent(event)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        cursorDrawerDelegate.dispatchDraw(canvas)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Real touch/mouse input continues through Android's normal dispatch.
        if (event.source != InputDevice.SOURCE_UNKNOWN || forwarding) return false

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture()
            val bottomControl = controlAt(bottomPanel, event.x, event.y)
            val menuControl = controlAt(toolbar, event.x, event.y) ?: bottomControl
            if (menuControl != null && menuControl !is EditText) {
                // Activate the actual control, without parent touch interception
                // or focus acquisition swallowing the remote's synthetic tap.
                clickTarget = menuControl
                closeMenuAfterClick = menuControl === bottomControl
                menuControl.isPressed = true
                if (menuControl.isLongClickable) {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            val page = activityRoot.findViewById<View>(R.id.flWebViewContainer)
            val playerButton = activityRoot.findViewWithTag<View>("mom_inline_stream_button")
            val playerSurface = activityRoot.findViewWithTag<View>("mom_inline_stream_surface")
            gestureTarget = when {
                menuControl is EditText -> menuControl
                inside(toolbar, event.x, event.y) -> null
                inside(bottomPanel, event.x, event.y) -> null
                playerButton != null && inside(playerButton, event.x, event.y) -> playerButton
                playerSurface != null && inside(playerSurface, event.x, event.y) -> null
                page != null && inside(page, event.x, event.y) -> page
                else -> null
            }
            closeMenuOnUp = gestureTarget === page && page != null && pageTapsEnabled()
        }

        val control = clickTarget
        if (control != null) {
            val valid = control.isAttachedToWindow && control.isEnabled &&
                inside(control, event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    // Once the pointer leaves, cancel this click and long press.
                    if (!valid) cancelGesture()
                }
                MotionEvent.ACTION_UP -> {
                    val activate = valid && !longPressHandled
                    val close = closeMenuAfterClick
                    // Clear first: a click can close the menu or remove the tab.
                    cancelGesture()
                    if (activate) {
                        control.performClick()
                        if (close) post { onPageTap() }
                    }
                }
                MotionEvent.ACTION_CANCEL -> cancelGesture()
            }
            return true
        }

        val target = gestureTarget ?: return true
        if (!target.isAttachedToWindow) {
            cancelGesture()
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
        val ending = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        val close = event.actionMasked == MotionEvent.ACTION_UP && closeMenuOnUp
        if (ending) {
            gestureTarget = null
            closeMenuOnUp = false
        }
        forwarding = true
        val handled = try {
            target.dispatchTouchEvent(translated)
        } finally {
            forwarding = false
            translated.recycle()
        }
        if (close && handled) post { onPageTap() }
        return true
    }

    private fun cancelGesture() {
        removeCallbacks(longPress)
        clickTarget?.isPressed = false
        clickTarget = null
        closeMenuAfterClick = false
        longPressHandled = false
        val target = gestureTarget
        gestureTarget = null
        closeMenuOnUp = false
        if (target == null || !target.isAttachedToWindow) return
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            target.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    private fun controlAt(view: View, x: Float, y: Float): View? {
        if (!inside(view, x, y) || !view.isEnabled) return null
        if (view is ViewGroup) {
            // Search children first so a clickable tab cannot take its X's tap.
            for (i in view.childCount - 1 downTo 0) {
                controlAt(view.getChildAt(i), x, y)?.let { return it }
            }
        }
        return if (view.isClickable || view.isLongClickable) view else null
    }

    private fun inside(view: View, x: Float, y: Float): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return false
        val position = IntArray(2)
        val cursor = IntArray(2)
        view.getLocationOnScreen(position)
        getLocationOnScreen(cursor)
        val sx = cursor[0] + x
        val sy = cursor[1] + y
        return sx >= position[0] && sx < position[0] + view.width &&
            sy >= position[1] && sy < position[1] + view.height
    }
}
