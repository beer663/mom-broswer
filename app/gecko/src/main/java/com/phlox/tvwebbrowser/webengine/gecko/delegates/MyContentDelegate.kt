
package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.view.PointerIcon
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.webengine.gecko.GeckoViewWithVirtualCursor
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import com.phlox.tvwebbrowser.webengine.gecko.R
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.WebResponse

class MyContentDelegate(private val webEngine: GeckoWebEngine) :
    GeckoSession.ContentDelegate {

    private var activeAlert: Boolean = false

    override fun onTitleChange(session: GeckoSession, title: String?) {
        title?.let { webEngine.callback?.onReceivedTitle(it) }
    }

    override fun onPreviewImage(session: GeckoSession, previewImageUrl: String) {
        super.onPreviewImage(session, previewImageUrl)
    }

    override fun onFocusRequest(session: GeckoSession) {
        // No action required.
    }

    override fun onCloseRequest(session: GeckoSession) {
        webEngine.callback?.closeWindow(session)
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        if (fullScreen) {
            webEngine.callback?.onPrepareForFullscreen()
        } else {
            webEngine.callback?.onExitFullscreen()
        }
    }

    override fun onMetaViewportFitChange(session: GeckoSession, viewportFit: String) {
        super.onMetaViewportFitChange(session, viewportFit)
    }

    override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
    ) {
        val webView = webEngine.getView() as? GeckoViewWithVirtualCursor ?: return

        webEngine.callback?.onContextMenu(
            webView.cursorDrawerDelegate,
            element.baseUri,
            element.linkUri,
            element.srcUri,
            element.title,
            element.altText,
            null, // GeckoView 157 no longer exposes ContextElement.textContent
            webView.cursorDrawerDelegate.cursorPosition.x.toInt(),
            webView.cursorDrawerDelegate.cursorPosition.y.toInt()
        )
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        if (response.requestExternalApp) {
            webEngine.callback?.onOpenInExternalAppRequested(response.uri)
            return
        }

        val contentDisposition = response.headers["content-disposition"]
        val mimetype = response.headers["content-type"]
        val contentLength = response.headers["content-length"]?.toLongOrNull() ?: 0

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
        session.open(GeckoWebEngine.runtime)
    }

    override fun onKill(session: GeckoSession) {
        if (webEngine.session != session || !webEngine.tab.selected) {
            return
        }

        webEngine.session.open(GeckoWebEngine.runtime)

        webEngine.progressDelegate.sessionState?.let {
            webEngine.session.restoreState(it)
        } ?: run {
            webEngine.url?.let { webEngine.session.loadUri(it) }
        }
    }

    override fun onFirstComposite(session: GeckoSession) {
        // No action required.
    }

    override fun onFirstContentfulPaint(session: GeckoSession) {
        super.onFirstContentfulPaint(session)
    }

    override fun onPaintStatusReset(session: GeckoSession) {
        super.onPaintStatusReset(session)
    }

    override fun onPointerIconChange(session: GeckoSession, icon: PointerIcon) {
        super.onPointerIconChange(session, icon)
    }

    override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
        // No action required.
    }

    override fun onSlowScript(
        geckoSession: GeckoSession,
        scriptFileName: String
    ): GeckoResult<SlowScriptResponse>? {
        val prompt: MyPromptDelegate? =
            webEngine.session.promptDelegate as? MyPromptDelegate

        val activity = webEngine.callback?.getActivity() ?: return null

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

            return result.then<SlowScriptResponse?> { value: SlowScriptResponse? ->
                activeAlert = false
                GeckoResult.fromValue<SlowScriptResponse?>(value)
            }
        }

        return null
    }

    override fun onShowDynamicToolbar(geckoSession: GeckoSession) {
        super.onShowDynamicToolbar(geckoSession)
    }

}
