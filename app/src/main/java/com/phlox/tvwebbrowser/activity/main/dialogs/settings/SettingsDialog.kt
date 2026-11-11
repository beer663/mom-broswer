package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.app.Dialog
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.fedir.segmentedbutton.SegmentedButton
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.widgets.SegmentedButtonTabsAdapter
import java.util.Locale

class SettingsDialog(context: Context, val model: SettingsModel) :
    Dialog(context, R.style.SettingsDialog),
    DialogInterface.OnDismissListener {
    private var mainView: MainSettingsView? = null
    private var sbTabs: SegmentedButton

    init {
        setTitle(R.string.settings)
        setContentView(R.layout.dialog_settings)

        sbTabs = findViewById(R.id.sbTabs)

        val videoOptions = Button(context).apply {
            id = View.generateViewId()
            text = "Video playback / AdGuard DNS"
            isAllCaps = false
            setOnClickListener { StreamingOptions.show(context) { dismiss() } }
        }
        (sbTabs.parent as ViewGroup).addView(
            videoOptions, 1,
            ViewGroup.LayoutParams(-1, -2)
        )
        findViewById<View>(R.id.btnMainTab).nextFocusDownId = videoOptions.id
        findViewById<View>(R.id.btnShortcutsTab).nextFocusDownId = videoOptions.id
        videoOptions.nextFocusUpId = R.id.btnMainTab

        val tabContentAdapter = object : SegmentedButtonTabsAdapter(
            sbTabs,
            findViewById(R.id.flTabsContent)
        ) {
            override fun createContentViewForSegmentButtonId(id: Int): View {
                return when (id) {
                    R.id.btnMainTab -> {
                        mainView = MainSettingsView(context)
                        mainView!!
                    }
                    else -> ShortcutsSettingsView(context)
                }
            }
        }

        setOnDismissListener(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    override fun onDismiss(dialog: DialogInterface?) {
        mainView?.save()
    }
}

/** Website playback is the default. Native playback is an explicit per-site choice. */
object StreamingOptions {
    private const val PREFS = "mom_streaming_options"
    private const val NATIVE_SITES = "native_sites"

    internal fun siteKey(url: String?): String? {
        val uri = runCatching { Uri.parse(url ?: "") }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) &&
            !uri.scheme.equals("http", true)) return null

        return uri.host?.trimEnd('.')?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
    }

    internal fun isNativeEnabled(context: Context, url: String?): Boolean {
        val host = siteKey(url) ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(NATIVE_SITES, emptySet()).orEmpty().contains(host)
    }

    internal fun setNativeEnabled(context: Context, url: String, enabled: Boolean) {
        val host = siteKey(url) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hosts = prefs.getStringSet(NATIVE_SITES, emptySet())
            .orEmpty().toMutableSet()

        if (enabled) hosts.add(host) else hosts.remove(host)
        prefs.edit().putStringSet(NATIVE_SITES, hosts).apply()
    }

    fun nativePlaybackEnabled(activity: FragmentActivity): Boolean {
        val tab = ActiveModelsRepository.get(TabsModel::class, activity)
            .currentTab.value ?: return false

        return AppContext.provideConfig().isWebEngineGecko() &&
            isNativeEnabled(activity, tab.webEngine.url ?: tab.url)
    }

    fun show(context: Context, beforeReload: () -> Unit) {
        var candidate: Context = context
        while (candidate is ContextWrapper && candidate !is FragmentActivity) {
            val base = candidate.baseContext
            if (base === candidate) return
            candidate = base
        }

        val activity = candidate as? FragmentActivity ?: return
        val tabs = ActiveModelsRepository.get(TabsModel::class, activity)
        val tab = tabs.currentTab.value
        val url = tab?.webEngine?.url ?: tab?.url
        val host = siteKey(url)
        val gecko = AppContext.provideConfig().isWebEngineGecko()
        val enabled = isNativeEnabled(context, url)
        val padding = (20 * context.resources.displayMetrics.density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(context).apply {
            text = "Website: ${host ?: "Open a website to choose its player"}\n\n" +
                "Website playback is the default. Native playback is optional and may not " +
                "work with sites that require login cookies or DRM.\n\n" +
                "AdGuard encrypted DNS is configured for GeckoView. WebView and native " +
                "playback do not inherit that DNS setting. A connection error does not " +
                "automatically turn AdGuard off."
            textSize = 16f
        })

        val native = CheckBox(context).apply {
            text = "Use native player on this website"
            isChecked = enabled
            isEnabled = host != null && gecko
        }
        content.addView(native)

        if (!gecko) content.addView(TextView(context).apply {
            text = "Native playback is available when the browser engine is GeckoView."
        })

        AlertDialog.Builder(context)
            .setTitle("Video playback and DNS")
            .setView(content)
            .setPositiveButton("Save and reload") { _, _ ->
                if (url != null && host != null && gecko &&
                    native.isChecked != enabled) {
                    setNativeEnabled(context, url, native.isChecked)
                    if (tabs.currentTab.value === tab) {
                        beforeReload()
                        tab?.webEngine?.reload()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
