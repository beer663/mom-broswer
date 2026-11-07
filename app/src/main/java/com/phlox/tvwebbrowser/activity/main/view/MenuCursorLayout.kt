package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter
import android.view.View
import android.view.ViewGroup
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate

/**
 * Remote-controlled pointer for TV Bro's Android menu.
 *
 * Normal GeckoView cursor touches are injected into the webpage, which cannot
 * receive a click on an Android button displayed above it. This view uses the
 * same cursor physics/drawing, but forwards only synthetic taps to the menu
 * controls beneath it. Real mouse/touchpad events continue through normally.
 */
class MenuCursorLayout(
    context: Context,
    private val activityRoot: ViewGroup,
    private val toolbar: View,
    private val bottomPanel: View
) : FrameLayout(context) {
    val cursorDrawerDelegate = CursorDrawerDelegate(context, this)
    private val inputEventsAdapter = DPADNavigationEventsAdapter(
        onEmulatedKeyEvent = { event -> cursorDrawerDelegate.dispatchKeyEvent(event) },
        motionAxesTranslationEnabled = { !AppContext.provideConfig().disableMotionAxesDpadNavigation },
        isSoftwareKeyboardVisible = {
            ViewCompat.getRootWindowInsets(rootView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    )
    private var forwarding = false
    private var forwardingTouchSequence = false

    init {
        setWillNotDraw(false)
        cursorDrawerDelegate.init()
        isFocusable = false
        isClickable = false
        // Never scroll or drag the website when the menu cursor reaches an edge.
        cursorDrawerDelegate.customScrollCallback = object : CursorDrawerDelegate.CustomScrollCallback {
            override fun onScroll(scrollX: Int, scrollY: Int) = true
        }
    }

    /** Stop any held direction before this view is removed from the hierarchy. */
    fun stopCursor() {
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cursorDrawerDelegate.onSizeChanged(w, h, oldw, oldh)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return inputEventsAdapter.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return inputEventsAdapter.dispatchGenericMotionEvent(event) ||
            super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        cursorDrawerDelegate.dispatchDraw(canvas)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // CursorDrawerDelegate creates synthetic MotionEvents with source=0.
        // A physical pointer should go directly to the underlying Android UI.
        if (event.source != InputDevice.SOURCE_UNKNOWN || forwarding) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> forwardingTouchSequence =
                inside(toolbar, event.x, event.y) || inside(bottomPanel, event.x, event.y)
            MotionEvent.ACTION_CANCEL -> {
                if (!forwardingTouchSequence) return true
            }
        }
        if (!forwardingTouchSequence) return true

        // Temporarily let the root skip this transparent overlay, and hit-test
        // real buttons, tabs, and the address bar using normal Android touch.
        forwarding = true
        return try {
            activityRoot.dispatchTouchEvent(event)
        } finally {
            forwarding = false
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                forwardingTouchSequence = false
            }
        }
    }

    private fun inside(view: View, x: Float, y: Float): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return false
        val viewPos = IntArray(2)
        val cursorPos = IntArray(2)
        view.getLocationOnScreen(viewPos)
        getLocationOnScreen(cursorPos)
        val screenX = cursorPos[0] + x
        val screenY = cursorPos[1] + y
        return screenX >= viewPos[0] && screenX < viewPos[0] + view.width &&
            screenY >= viewPos[1] && screenY < viewPos[1] + view.height
    }
}
