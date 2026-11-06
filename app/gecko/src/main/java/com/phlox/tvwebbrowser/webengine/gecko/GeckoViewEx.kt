
package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoDisplay.ScreenshotBuilder
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

open class GeckoViewEx @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GeckoView(context, attrs) {

    private var geckoDisplay: GeckoDisplay? = null

    companion object {
        // Set to false to turn thumbnail screenshots off completely (diagnostic switch).
        private const val THUMBNAILS_ENABLED = true

        // Diagnostic switch: draw pages through a TextureView (normal GPU composition)
        // instead of the default SurfaceView (which some TVs show through a hardware
        // overlay with wrong colors). Set to false to go back to the default.
        private const val USE_TEXTURE_VIEW = true
    }

    init {
        if (USE_TEXTURE_VIEW) {
            try {
                setViewBackend(BACKEND_TEXTURE_VIEW)
            } catch (e: Throwable) {
                android.util.Log.w("GeckoViewEx", "Could not switch to TextureView backend", e)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        screenshotBuilderAccessHack()
    }

    override fun setSession(session: GeckoSession) {
        super.setSession(session)
        screenshotBuilderAccessHack()
    }

    @Suppress("INACCESSIBLE_TYPE")
    private fun screenshotBuilderAccessHack() {
        val display: Any = mDisplay
        // GeckoView internals vary by version. Screenshot support is optional:
        // failure must not crash browsing or interrupt a playing video.
        geckoDisplay = try {
            val field = display.javaClass.getDeclaredField("mDisplay")
            field.isAccessible = true
            field.get(display) as? GeckoDisplay
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun screenshot(): ScreenshotBuilder? {
        geckoDisplay?.let {
            return it.screenshot()
        }
        return null
    }

    suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        // Do not request screenshots from a detached or zero-size video surface.
        if (!THUMBNAILS_ENABLED) return null
        if (!isAttachedToWindow || width <= 0 || height <= 0) return null
        val screenshotBuilder = screenshot() ?: return null
        var thumbnail = bitmap

        if (thumbnail == null) {
            try {
                thumbnail = Bitmap.createBitmap(maxOf(1, width / 2), maxOf(1, height / 2), Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                try {
                    thumbnail = Bitmap.createBitmap(maxOf(1, width / 4), maxOf(1, height / 4), Bitmap.Config.ARGB_8888)
                } catch (_: OutOfMemoryError) {
                    // Thumbnail could not be created.
                }
            }
        }

        if (thumbnail == null) {
            return null
        }

        thumbnail = suspendCoroutine {
            val screenshotResult = try {
                screenshotBuilder.bitmap(thumbnail).capture()
            } catch (_: Throwable) {
                it.resume(null)
                return@suspendCoroutine
            }

            screenshotResult.then({ bitmap ->
                it.resume(thumbnail)
                GeckoResult.fromValue(bitmap)
            }, { _ ->
                it.resume(null)
                GeckoResult.fromValue(null)
            })
        }

        return thumbnail
    }
}
