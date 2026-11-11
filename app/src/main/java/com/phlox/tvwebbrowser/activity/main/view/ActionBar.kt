package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View.OnFocusChangeListener
import android.view.View.OnKeyListener
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.downloads.ActiveDownloadsModel
import com.phlox.tvwebbrowser.databinding.ViewActionbarBinding
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository

class ActionBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val vb = ViewActionbarBinding.inflate( LayoutInflater.from(context),this)
    var callback: Callback? = null
    private var downloadAnimation: Animation? = null
    private var downloadsModel = ActiveModelsRepository.get(ActiveDownloadsModel::class, context)
    private var extendedAddressBarMode = false

    interface Callback {
        fun closeWindow()
        fun showDownloads()
        fun showFavorites()
        fun showHistory()
        fun showSettings()
        fun initiateVoiceSearch()
        fun search(text: String)
        fun onExtendedAddressBarMode()
        fun onUrlInputDone()
        fun toggleIncognitoMode()
    }

    private val etUrlFocusChangeListener = OnFocusChangeListener { _, focused ->
        if (focused) {
            enterExtendedAddressBarMode()

            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            imm.showSoftInput(vb.etUrl, InputMethodManager.SHOW_IMPLICIT)
            postDelayed(//workaround an android TV bug
                {
                    vb.etUrl.selectAll()
                }, 500)
        }
    }

    private var lastUrlSubmitAt = 0L

    // Both a remote Enter key and the Android TV software keyboard's Search/Go
    // action must navigate. The IME often delivers an editor action without a
    // physical KeyEvent, so a key listener alone is not sufficient.
    private fun submitAddress(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastUrlSubmitAt < 350L) return true // ignore duplicate IME + key events
        lastUrlSubmitAt = now
        val urlOrSearch = vb.etUrl.text.toString().trim()
        if (urlOrSearch.isEmpty()) return true
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
        callback?.search(urlOrSearch)
        dismissExtendedAddressBarMode()
        callback?.onUrlInputDone()
        return true
    }

    private val etUrlKeyListener = OnKeyListener { _, _, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SEARCH -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) submitAddress()
                true
            }
            else -> false
        }
    }

    private val etUrlEditorActionListener = android.widget.TextView.OnEditorActionListener { _, actionId, event ->
        val isSubmitAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
            actionId == EditorInfo.IME_ACTION_GO ||
            actionId == EditorInfo.IME_ACTION_DONE ||
            (actionId == EditorInfo.IME_ACTION_UNSPECIFIED && event == null)
        val isSubmitKey = event?.keyCode == KeyEvent.KEYCODE_ENTER ||
            event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            event?.keyCode == KeyEvent.KEYCODE_SEARCH
        when {
            isSubmitAction && (event == null || event.action == KeyEvent.ACTION_UP) -> submitAddress()
            isSubmitKey && event?.action == KeyEvent.ACTION_UP -> submitAddress()
            isSubmitAction || isSubmitKey -> true // swallow matching DOWN without submitting twice
            else -> false
        }
    }

    init {
        init()
    }

    fun init() {
        orientation = HORIZONTAL

        if (isInEditMode) return

        val incognitoMode = AppContext.provideConfig().incognitoMode

        vb.ibMenu.setOnClickListener { callback?.closeWindow() }
        vb.ibDownloads.setOnClickListener { callback?.showDownloads() }
        vb.ibFavorites.setOnClickListener { callback?.showFavorites() }
        vb.ibHistory.setOnClickListener { callback?.showHistory() }
        vb.ibIncognito.setOnClickListener { callback?.toggleIncognitoMode() }
        vb.ibSettings.setOnClickListener { callback?.showSettings() }

        if (Utils.isFireTV(context)) {
            vb.ibMenu.nextFocusRightId = R.id.ibHistory
            removeView(vb.ibVoiceSearch)
        } else {
            vb.ibVoiceSearch.setOnClickListener { callback?.initiateVoiceSearch() }
        }

        vb.ibIncognito.isChecked = incognitoMode

        vb.etUrl.onFocusChangeListener = etUrlFocusChangeListener

        // A single-line field exposes Search on Android TV IMEs instead of a
        // newline; keep both the editor-action and hardware key listeners.
        vb.etUrl.setSingleLine(true)
        vb.etUrl.imeOptions = EditorInfo.IME_ACTION_SEARCH
        vb.etUrl.setOnKeyListener(etUrlKeyListener)
        vb.etUrl.setOnEditorActionListener(etUrlEditorActionListener)


        downloadsModel.activeDownloads.subscribe(context as AppCompatActivity) {
            if (it.isNotEmpty()) {
                if (downloadAnimation == null) {
                    downloadAnimation = AnimationUtils.loadAnimation(context, R.anim.infinite_fadeinout_anim)
                    vb.ibDownloads.startAnimation(downloadAnimation)
                }
            } else {
                downloadAnimation?.apply {
                    this.reset()
                    vb.ibDownloads.clearAnimation()
                    downloadAnimation = null
                }
            }
        }
    }

    /** Let native input handle its own navigation and IME Search key. */
    fun isAddressInputFocused(): Boolean = vb.etUrl.hasFocus()

    // Android TV keyboards sometimes temporarily move focus away from EditText
    // while completing their Search/Go action. The address editor remains the
    // active input until its expanded mode is dismissed.
    fun isAddressInputEditing(): Boolean = vb.etUrl.hasFocus() || extendedAddressBarMode

    /** Backup for TV keyboard Search/Enter events that bypass EditText's listener. */
    fun submitAddressFromKeyboard(): Boolean = submitAddress()

    // Some TV IMEs send a trailing Enter key-up after the editor action has
    // already navigated. It must not turn into a click on the menu cursor.
    fun wasAddressJustSubmitted(): Boolean =
        android.os.SystemClock.elapsedRealtime() - lastUrlSubmitAt < 500L

    fun setAddressBoxText(text: String) {
        if (text == Config.HOME_PAGE_URL) {
            vb.etUrl.setText("")
        } else {
            vb.etUrl.setText(text)
        }
    }

    fun setAddressBoxTextColor(color: Int) {
        vb.etUrl.setTextColor(color)
    }

    private fun enterExtendedAddressBarMode() {
        if (extendedAddressBarMode) return
        extendedAddressBarMode = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ImageButton) {
                child.visibility = GONE
            }
        }
        TransitionManager.beginDelayedTransition(this)
        callback?.onExtendedAddressBarMode()
    }

    fun dismissExtendedAddressBarMode() {
        if (!extendedAddressBarMode) return
        extendedAddressBarMode = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ImageButton) {
                child.visibility = VISIBLE
            }
        }
    }

    fun catchFocus() {
        vb.ibMenu.requestFocus()
    }
}
