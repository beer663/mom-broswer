package com.phlox.tvwebbrowser.webengine.gecko.delegates
import android.view.PointerIcon
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.webengine.gecko.GeckoViewWithVirtualCursor
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import com.phlox.tvwebbrowser.webengine.gecko.R
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.WebResponse

class MyContentDelegate(
private val webEngine: GeckoWebEngine
) : GeckoSession.ContentDelegate {
private var activeAlert: Boolean = false
private var lastRecoveryAt: Long? = null
private var recoveryDialogVisible = false

private fun recoverRenderer(
session: GeckoSession,
reason: String,
manual: Boolean = false
) {
if (session !== webEngine.session) return
Log.e("MomBrowserRenderer", "Renderer $reason")
webEngine.markRendererUnavailable()
activeAlert = false

if (!webEngine.tab.selected) return
val callback = webEngine.callback ?: return
val activity = callback.getActivity()
val lifecycle =
(activity as? LifecycleOwner)?.lifecycle ?: return
if (!lifecycle.currentState.isAtLeast(
Lifecycle.State.RESUMED
)) return

val url =
webEngine.url?.takeIf { it.isNotBlank() } ?: webEngine.tab.url
val now = SystemClock.elapsedRealtime()

callback.onPageStarted(url)
callback.onExitFullscreen()

val last = lastRecoveryAt
if (!manual && last != null && now - last < 60_000L) {
if (!recoveryDialogVisible && !activity.isFinishing) {
recoveryDialogVisible = true
AlertDialog.Builder(activity)
.setTitle("Page stopped")
.setMessage(
"This page stopped repeatedly. Reload it to try again."
)
.setPositiveButton("Reload") { _, _ ->
recoverRenderer(
session,
"manual reload",
manual = true
)
}
.setNegativeButton("Cancel", null)
.setOnDismissListener {
recoveryDialogVisible = false
}
.show()
}
return
}

lastRecoveryAt = now
try {
webEngine.restoreCurrentPage(forceReload = manual)
session.setActive(true)
session.setFocused(true)
Toast.makeText(
activity,
"Restoring page…",
Toast.LENGTH_SHORT
).show()
} catch (error: Exception) {
Log.e("MomBrowserRenderer", "Page recovery failed", error)
Toast.makeText(
activity,
"Page could not be restored. Try Reload.",
Toast.LENGTH_LONG
).show()
}
}

override fun onTitleChange(
session: GeckoSession,
title: String?
) {
title?.let {
webEngine.callback?.onReceivedTitle(it)
}
}

override fun onPreviewImage(
session: GeckoSession,
previewImageUrl: String
) {
super.onPreviewImage(session, previewImageUrl)
}

override fun onFocusRequest(session: GeckoSession) {}

override fun onCloseRequest(session: GeckoSession) {
webEngine.callback?.closeWindow(session)
}

override fun onFullScreen(
session: GeckoSession,
fullScreen: Boolean
) {
if (fullScreen) {
webEngine.callback?.onPrepareForFullscreen()
} else {
webEngine.callback?.onExitFullscreen()
}
}

override fun onMetaViewportFitChange(
session: GeckoSession,
viewportFit: String
) {
super.onMetaViewportFitChange(session, viewportFit)
}

override fun onContextMenu(
session: GeckoSession,
screenX: Int,
screenY: Int,
element: GeckoSession.ContentDelegate.ContextElement
) {
val webView =
webEngine.getView() as? GeckoViewWithVirtualCursor ?: return
webEngine.callback?.onContextMenu(
webView.cursorDrawerDelegate,
element.baseUri,
element.linkUri,
element.srcUri,
element.title,
element.altText,
element.textContent,
webView.cursorDrawerDelegate.cursorPosition.x.toInt(),
webView.cursorDrawerDelegate.cursorPosition.y.toInt()
)
}

override fun onExternalResponse(
session: GeckoSession,
response: WebResponse
) {
if (response.requestExternalApp) {
webEngine.callback
?.onOpenInExternalAppRequested(response.uri)
return
}
val contentDisposition =
response.headers["content-disposition"]
val mimetype = response.headers["content-type"]
val contentLength =
response.headers["content-length"]?.toLongOrNull() ?: 0
webEngine.callback?.onDownloadRequested(
response.uri,
webEngine.url ?: "",
null,
webEngine.userAgentString,
mimetype,
Download.OperationAfterDownload.NOP,
null,
response.body,
contentLength,
contentDisposition
)
}

override fun onCrash(session: GeckoSession) {
recoverRenderer(session, "crashed")
}

override fun onKill(session: GeckoSession) {
recoverRenderer(session, "was terminated")
}

override fun onFirstComposite(session: GeckoSession) {
Log.i(
"MomBrowserRenderer",
"First composite after surface creation"
)
}

override fun onFirstContentfulPaint(session: GeckoSession) {
super.onFirstContentfulPaint(session)
Log.i("MomBrowserRenderer", "Page content painted")
}

override fun onPaintStatusReset(session: GeckoSession) {
super.onPaintStatusReset(session)
Log.d("MomBrowserRenderer", "Paint status reset")
}

override fun onPointerIconChange(
session: GeckoSession,
icon: PointerIcon
) {
super.onPointerIconChange(session, icon)
}

override fun onWebAppManifest(
session: GeckoSession,
manifest: JSONObject
) {}

override fun onSlowScript(
geckoSession: GeckoSession,
scriptFileName: String
): GeckoResult<SlowScriptResponse>? {
val prompt: MyPromptDelegate? =
webEngine.session.promptDelegate as? MyPromptDelegate
val activity =
webEngine.callback?.getActivity() ?: return null
if (prompt != null) {
val result = GeckoResult<SlowScriptResponse?>()
if (!activeAlert) {
activeAlert = true
prompt.onSlowScriptPrompt(
geckoSession,
activity.getString(R.string.slow_script),
result
)
}
return result.then<SlowScriptResponse?> {
value: SlowScriptResponse? ->
activeAlert = false
GeckoResult.fromValue<SlowScriptResponse?>(value)
}
}
return null
}

override fun onShowDynamicToolbar(
geckoSession: GeckoSession
) {
super.onShowDynamicToolbar(geckoSession)
}

override fun onCookieBannerDetected(session: GeckoSession) {
super.onCookieBannerDetected(session)
}

override fun onCookieBannerHandled(session: GeckoSession) {
super.onCookieBannerHandled(session)
}
}
