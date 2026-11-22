package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineProvider
import com.phlox.tvwebbrowser.webengine.WebEngineProviderCallback
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.webengine.gecko.delegates.*
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import com.phlox.tvwebbrowser.widgets.cursor.CursorLayout
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.SessionState
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtension.MessageDelegate

class GeckoWebEngine(val tab: WebTabState) : WebEngine,
CursorDrawerDelegate.Callback {

companion object {
const val ENGINE_NAME = "GeckoView"
private const val APP_WEB_EXTENSION_VERSION = 64
private const val ADGUARD_DNS_URL =
"https://dns.adguard-dns.com/dns-query"

val TAG: String = GeckoWebEngine::class.java.simpleName
lateinit var runtime: GeckoRuntime
var appWebExtension = ObservableValue<WebExtension?>(null)

@Volatile
var extensionBackgroundReady = false

@Volatile
var activeCallback: WebEngineWindowProviderCallback? = null

var weakRefToSingleGeckoView:
WeakReference<GeckoViewWithVirtualCursor?> = WeakReference(null)

val uiHandler = Handler(Looper.getMainLooper())
private val extensionInitialization = CompletableDeferred<Unit>()

@UiThread
suspend fun initialize(
context: Context,
webViewContainer: CursorLayout
) {
if (!this::runtime.isInitialized) {
val builder = GeckoRuntimeSettings.Builder()

builder.aboutConfigEnabled(true)
.preferredColorScheme(
AppContext.provideConfig()
.theme.value.toGeckoPreferredColorScheme()
)
.forceUserScalableEnabled(true)
.trustedRecursiveResolverUri(ADGUARD_DNS_URL)
.trustedRecursiveResolverMode(
GeckoRuntimeSettings.TRR_MODE_ONLY
)

builder.contentBlocking(
ContentBlocking.Settings.Builder()
.antiTracking(
ContentBlocking.AntiTracking.DEFAULT or
ContentBlocking.AntiTracking.STP
)
.safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
.cookieBehavior(
ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
)
.cookieBehaviorPrivateMode(
ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
)
.enhancedTrackingProtectionLevel(
ContentBlocking.EtpLevel.DEFAULT
)
.build()
)

runtime = GeckoRuntime.create(context, builder.build())
disableSoftwareAv1()

val installResult =
if (APP_WEB_EXTENSION_VERSION ==
AppContext.provideConfig().appWebExtensionVersion
) {
runtime.webExtensionController.ensureBuiltIn(
"resource://android/assets/extensions/generic/",
"tvbro@mock.com"
)
} else {
runtime.webExtensionController.installBuiltIn(
"resource://android/assets/extensions/generic/"
)
}

installResult.accept({ extension ->
appWebExtension.value = extension
AppContext.provideConfig().appWebExtensionVersion =
APP_WEB_EXTENSION_VERSION
extensionInitialization.complete(Unit)
}) { error ->
Log.e(
TAG,
"Unable to initialize bundled browser extension",
error
)
extensionInitialization.complete(Unit)
}
}

if (withTimeoutOrNull(20_000L) {
extensionInitialization.await()
true
} != true
) {
Log.e(TAG, "Bundled extension initialization timed out")
}

val webView = GeckoViewWithVirtualCursor(context)
webViewContainer.addView(webView)
weakRefToSingleGeckoView = WeakReference(webView)
webViewContainer.setWillNotDraw(true)
}

private fun disableSoftwareAv1() {
try {
if (com.phlox.tvwebbrowser.utils.HardwareCodecs
.hasHardwareDecoder(
com.phlox.tvwebbrowser.utils.HardwareCodecs.AV1
)
) {
Log.i(
TAG,
"Hardware AV1 decoder detected; leaving AV1 enabled"
)
return
}

Log.i(
TAG,
"No usable hardware AV1 decoder; attempting to disable software AV1"
)

val controller = Class.forName(
"org.mozilla.geckoview.GeckoPreferenceController"
)

val branch = controller
.getField("PREF_BRANCH_DEFAULT")
.getInt(null)

val method = controller.methods.firstOrNull {
it.name == "setGeckoPref" &&
it.parameterTypes.size == 3 &&
(it.parameterTypes[1] ==
Boolean::class.javaPrimitiveType ||
it.parameterTypes[1] ==
Boolean::class.javaObjectType)
}

if (method == null) {
Log.w(
TAG,
"GeckoPreferenceController.setGeckoPref not found: AV1 stays on"
)
return
}

method.invoke(null, "media.av1.enabled", false, branch)
Log.i(TAG, "AV1 preference update requested")
} catch (e: Throwable) {
Log.w(TAG, "Can not turn AV1 off", e)
}
}

fun onThemeSettingUpdated(theme: Config.Theme) {
runtime.settings.preferredColorScheme =
theme.toGeckoPreferredColorScheme()
}

init {
WebEngineFactory.registerProvider(
WebEngineProvider(
ENGINE_NAME,
object : WebEngineProviderCallback {
override suspend fun initialize(
context: Context,
webViewContainer: CursorLayout
) {
GeckoWebEngine.initialize(
context,
webViewContainer
)
}

override fun createWebEngine(
tab: WebTabState
): WebEngine = GeckoWebEngine(tab)

override suspend fun clearCache(ctx: Context) {
suspendCoroutine { cont ->
runtime.storageController
.clearData(
StorageController.ClearFlags.ALL_CACHES
)
.then({
cont.resume(null)
GeckoResult.fromValue(null)
}, {
cont.resumeWithException(it)
GeckoResult.fromValue(null)
})
}
}

override fun onThemeSettingUpdated(value: Config.Theme) {
GeckoWebEngine.onThemeSettingUpdated(value)
}

override fun getWebEngineVersionString(): String {
return org.mozilla.geckoview.BuildConfig
.LIBRARY_PACKAGE_NAME + ":" +
org.mozilla.geckoview.BuildConfig
.MOZ_APP_VERSION + "." +
org.mozilla.geckoview.BuildConfig
.MOZ_APP_BUILDID + " - " +
org.mozilla.geckoview.BuildConfig
.MOZ_UPDATE_CHANNEL
}
}
)
)
}
}

private var webView: GeckoViewWithVirtualCursor? = null
lateinit var session: GeckoSession
var callback: WebEngineWindowProviderCallback? = null

val navigationDelegate = MyNavigationDelegate(this)
val progressDelegate = MyProgressDelegate(this)
val promptDelegate = MyPromptDelegate(this)
val contentDelegate = MyContentDelegate(this)
val permissionDelegate = MyPermissionDelegate(this)
val historyDelegate = MyHistoryDelegate(this)
val contentBlockingDelegate = MyContentBlockingDelegate(this)
val mediaSessionDelegate = MyMediaSessionDelegate()
val selectionActionDelegate = MySelectionActionDelegate()

var appHomeContentScriptPortDelegate:
AppHomeContentScriptPortDelegate? = null

var appContentScriptPortDelegate:
AppContentScriptPortDelegate? = null

private val contentPortDelegates =
mutableSetOf<AppContentScriptPortDelegate>()

private var lastReportedVideoPresence = false
private var browserInForeground = false
private var pageRecoveryPending = false
private var recoveryTarget: String? = null

fun markRendererUnavailable() {
if (!pageRecoveryPending) {
recoveryTarget = url?.takeIf { it.isNotBlank() && it != "about:blank" }
?: tab.url
}
pageRecoveryPending = true
}

fun restoreCurrentPage(forceReload: Boolean = false) {
val target = recoveryTarget ?: url?.takeIf { it.isNotBlank() } ?: tab.url

try {
if (!session.isOpen) session.open(runtime)
val state = progressDelegate.sessionState

// A killed renderer needs a fresh load; its saved document may no longer paint.
if (!forceReload && !pageRecoveryPending && state != null) {
try {
session.restoreState(state)
} catch (error: Exception) {
Log.w(
TAG,
"Saved page restore failed; loading current URL",
error
)
loadUrl(target)
}
} else {
loadUrl(target)
}

pageRecoveryPending = false
recoveryTarget = null
} catch (error: Exception) {
pageRecoveryPending = true
throw error
}
}

private var activityLifecycle: Lifecycle? = null

private val activityObserver = object : DefaultLifecycleObserver {
override fun onResume(owner: LifecycleOwner) {
this@GeckoWebEngine.onResume()
}

override fun onPause(owner: LifecycleOwner) {
this@GeckoWebEngine.onPause()
}
}

fun onContentVideoStateChanged() {
val present = contentPortDelegates.any { it.hasVideo }

if (present != lastReportedVideoPresence) {
lastReportedVideoPresence = present
callback?.onVideoPresenceChanged(present)
}
}

override fun setNativeInlinePlayback(active: Boolean) {
val canRestore = browserInForeground &&
activityLifecycle?.currentState
?.isAtLeast(Lifecycle.State.RESUMED) == true

val msg = JSONObject()
.put("type", "nativeInlinePlayback")
.put("active", active)
.put("restorePlayback", canRestore)

uiHandler.post {
for (delegate in contentPortDelegates.toList()) {
try {
delegate.port.postMessage(msg)
} catch (e: Exception) {
Log.w(TAG, "Unable to notify HTML video", e)
}
}
}
}

private fun notifyBrowserForeground() {
val msg = JSONObject()
.put("type", "browserForeground")
.put("foreground", browserInForeground)

for (delegate in contentPortDelegates.toList()) {
try {
delegate.port.postMessage(msg)
} catch (e: Exception) {
Log.w(TAG, "Unable to notify browser visibility", e)
}
}
}

fun onContentPortDisconnected(
delegate: AppContentScriptPortDelegate
) {
contentPortDelegates.remove(delegate)

if (appContentScriptPortDelegate === delegate) {
appContentScriptPortDelegate = null
}

onContentVideoStateChanged()
}

var appWebExtensionBackgroundPortDelegate:
AppWebExtensionBackgroundPortDelegate? = null

private lateinit var webExtObserver: (WebExtension?) -> Unit

override val url: String?
get() = navigationDelegate.locationURL

override var userAgentString: String?
get() = session.settings.userAgentOverride
set(value) {
session.settings.userAgentOverride = value
}

init {
session = GeckoSession(
GeckoSessionSettings.Builder()
.usePrivateMode(AppContext.provideConfig().incognitoMode)
.viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
.useTrackingProtection(
tab.adblock ?: AppContext.provideConfig().adBlockEnabled
)
.build()
)

session.navigationDelegate = navigationDelegate
session.progressDelegate = progressDelegate
session.contentDelegate = contentDelegate
session.promptDelegate = promptDelegate
session.permissionDelegate = permissionDelegate
session.historyDelegate = historyDelegate
session.contentBlockingDelegate = contentBlockingDelegate
session.mediaSessionDelegate = mediaSessionDelegate
session.selectionActionDelegate = selectionActionDelegate

webExtObserver = { ext: WebExtension? ->
if (ext != null) {
connectToAppWebExtension(ext)
appWebExtension.unsubscribe(webExtObserver)
}
}

appWebExtension.subscribe(webExtObserver)
}

private fun connectToAppWebExtension(extension: WebExtension) {
session.webExtensionController.setMessageDelegate(
extension,
object : MessageDelegate {
override fun onMessage(
nativeApp: String,
message: Any,
sender: WebExtension.MessageSender
): GeckoResult<Any>? = null

override fun onConnect(port: WebExtension.Port) {
appContentScriptPortDelegate =
AppContentScriptPortDelegate(
port,
this@GeckoWebEngine
).also {
contentPortDelegates.add(it)
port.setDelegate(it)
notifyBrowserForeground()
}
}
},
"tvbro_content"
)

session.webExtensionController.setMessageDelegate(
extension,
object : MessageDelegate {
override fun onMessage(
nativeApp: String,
message: Any,
sender: WebExtension.MessageSender
): GeckoResult<Any>? = null

override fun onConnect(port: WebExtension.Port) {
appHomeContentScriptPortDelegate =
AppHomeContentScriptPortDelegate(
port,
this@GeckoWebEngine
).also {
port.setDelegate(it)
}
}
},
"tvbro"
)

extension.setMessageDelegate(
object : MessageDelegate {
override fun onMessage(
nativeApp: String,
message: Any,
sender: WebExtension.MessageSender
): GeckoResult<Any>? = null

override fun onConnect(port: WebExtension.Port) {
extensionBackgroundReady = true

appWebExtensionBackgroundPortDelegate =
AppWebExtensionBackgroundPortDelegate(
port,
this@GeckoWebEngine
).also {
port.setDelegate(it)
}
}
},
"tvbro_bg"
)
}

override fun getWebEngineName(): String = ENGINE_NAME

override fun saveState(): Any? {
progressDelegate.sessionState?.let { return it }
return null
}

override fun restoreState(savedInstanceState: Any) {
if (savedInstanceState is SessionState) {
progressDelegate.sessionState = savedInstanceState

if (!session.isOpen) session.open(runtime)

session.restoreState(savedInstanceState)
pageRecoveryPending = false
recoveryTarget = null
homeLoadToken++

if (!extensionBackgroundReady) {
reloadHomePageOnceExtensionReady(homeLoadToken)
}
} else {
throw IllegalArgumentException(
"savedInstanceState is not SessionState"
)
}
}

override fun stateFromBytes(bytes: ByteArray): Any? {
return SessionState.fromString(String(bytes, Charsets.UTF_8))
}

private var homeLoadToken = 0

private fun isHomePageUrl(u: String?): Boolean {
return u != null &&
u.substringBefore('#')
.substringBefore('?')
.trimEnd('/') ==
Config.HOME_PAGE_URL.trimEnd('/')
}

private fun loadHomePageWhenExtensionReady(
token: Int,
attempt: Int = 0
) {
if (token != homeLoadToken || !session.isOpen) return
val view = webView

if (extensionBackgroundReady &&
view != null &&
view.isAttachedToWindow &&
view.width > 0 &&
view.height > 0
) {
view.post {
if (token == homeLoadToken &&
session.isOpen &&
extensionBackgroundReady &&
view.isAttachedToWindow &&
view.width > 0 &&
view.height > 0
) {
session.loadUri(Config.HOME_PAGE_URL)
}
}
} else if (attempt < 75) {
uiHandler.postDelayed({
loadHomePageWhenExtensionReady(token, attempt + 1)
}, 200L)
} else {
Log.e(
TAG,
"Homepage not ready after 15 seconds; not forcing premature load"
)
}
}

private fun reloadHomePageOnceExtensionReady(
token: Int,
attempt: Int = 0
) {
if (token != homeLoadToken || !session.isOpen) return
val view = webView

if (extensionBackgroundReady &&
view != null &&
view.isAttachedToWindow &&
view.width > 0 &&
view.height > 0
) {
view.post {
if (token == homeLoadToken &&
session.isOpen &&
isHomePageUrl(url)
) {
session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
}
}
} else if (attempt < 75) {
uiHandler.postDelayed({
reloadHomePageOnceExtensionReady(token, attempt + 1)
}, 200L)
}
}

override fun loadUrl(url: String) {
if (!session.isOpen) session.open(runtime)
homeLoadToken++

if (Config.HOME_URL_ALIAS == url) {
when (AppContext.provideConfig().homePageMode) {
Config.HomePageMode.BLANK -> {}

Config.HomePageMode.CUSTOM,
Config.HomePageMode.SEARCH_ENGINE -> {
session.loadUri(AppContext.provideConfig().homePage)
}

Config.HomePageMode.HOME_PAGE -> {
loadHomePageWhenExtensionReady(homeLoadToken)
}
}
} else {
session.loadUri(url)
}
}

override fun canGoForward(): Boolean =
navigationDelegate.canGoForward

override fun goForward() {
session.goForward()
}

override fun canZoomIn(): Boolean = true

override fun zoomIn() {
webView?.cursorDrawerDelegate?.tryZoomIn()
}

override fun canZoomOut(): Boolean = true

override fun zoomOut() {
webView?.cursorDrawerDelegate?.tryZoomOut()
}

override fun zoomBy(zoomBy: Float) {}

override fun evaluateJavascript(script: String) {
session.loadUri("javascript:$script")
}

override fun setNetworkAvailable(connected: Boolean) {}

override fun getView(): View? = webView

override fun getOrCreateView(activityContext: Context): View {
if (webView == null) {
val geckoView = weakRefToSingleGeckoView.get()

webView = if (geckoView == null) {
val wv = GeckoViewWithVirtualCursor(activityContext)
weakRefToSingleGeckoView = WeakReference(wv)
wv
} else {
geckoView
}.also {
it.cursorDrawerDelegate.callback = this
}
}

return webView!!
}

override fun canGoBack(): Boolean =
navigationDelegate.canGoBack

override fun goBack() {
session.goBack()
}

override fun reload() {
val enabled =
tab.adblock ?: AppContext.provideConfig().adBlockEnabled

if (session.settings.useTrackingProtection != enabled) {
session.settings.useTrackingProtection = enabled
}

if (!session.isOpen || pageRecoveryPending) {
callback?.onPageStarted(url ?: tab.url)

try {
restoreCurrentPage(forceReload = true)
} catch (error: Exception) {
Log.e(TAG, "Could not reload closed page session", error)

callback?.getActivity()?.let { activity ->
android.widget.Toast.makeText(
activity,
"Page could not be restored. Try reopening the tab.",
android.widget.Toast.LENGTH_LONG
).show()
}
}
} else {
session.reload()
}
}

override fun onFilePicked(resultCode: Int, data: Intent?) {
promptDelegate.onFileCallbackResult(resultCode, data)
}

override fun onResume() {
browserInForeground = true
notifyBrowserForeground()

if (pageRecoveryPending) {
try {
restoreCurrentPage()
} catch (error: Exception) {
Log.e(TAG, "Could not restore resumed page", error)
return
}
} else if (!session.isOpen) {
session.open(runtime)

progressDelegate.sessionState?.let {
session.restoreState(it)
}
}

session.setActive(true)
session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
session.setFocused(true)
}

override fun onPause() {
browserInForeground = false
notifyBrowserForeground()
if (!session.isOpen) return
session.setFocused(false)
session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)

mediaSessionDelegate.mediaSession?.let {
if (!mediaSessionDelegate.paused) it.pause()
}
}

override fun onUpdateAdblockSetting(newState: Boolean) {}

override fun hideFullscreenView() {
session.exitFullScreen()
}

override fun togglePlayback() {
mediaSessionDelegate.mediaSession?.let {
if (mediaSessionDelegate.paused) it.play() else it.pause()
}
}

override fun stopPlayback() {
mediaSessionDelegate.mediaSession?.stop()
}

override fun rewind() {
mediaSessionDelegate.mediaSession?.seekBackward()
}

override fun fastForward() {
mediaSessionDelegate.mediaSession?.seekForward()
}

override suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
return webView?.renderThumbnail(bitmap)
}

override fun onAttachToWindow(
callback: WebEngineWindowProviderCallback,
parent: ViewGroup
) {
this.callback = callback
activeCallback = callback

activityLifecycle?.removeObserver(activityObserver)

activityLifecycle =
(callback.getActivity() as? LifecycleOwner)?.lifecycle

browserInForeground = activityLifecycle?.currentState
?.isAtLeast(Lifecycle.State.RESUMED) == true

activityLifecycle?.addObserver(activityObserver)
notifyBrowserForeground()

val webView = this.webView
?: throw IllegalStateException("WebView is null")

val previousSession = webView.session

if (previousSession != null && previousSession != session) {
previousSession.setActive(false)
webView.releaseSession()
}

val isNightMode =
(callback.getActivity().resources.configuration.uiMode and
Configuration.UI_MODE_NIGHT_MASK) ==
Configuration.UI_MODE_NIGHT_YES

webView.coverUntilFirstPaint(
if (isNightMode) 0xFF1B1B1B.toInt() else 0xFFE4E2DD.toInt()
)

webView.setSession(session)

if (session.isOpen) {
session.setActive(true)
session.setPriorityHint(
if (browserInForeground) GeckoSession.PRIORITY_HIGH
else GeckoSession.PRIORITY_DEFAULT
)
}

if (session.isOpen &&
previousSession != null &&
previousSession != session
) {
session.setActive(true)
session.reload()
}
}

override fun onDetachFromWindow(
completely: Boolean,
destroyTab: Boolean
) {
browserInForeground = false
notifyBrowserForeground()

activityLifecycle?.removeObserver(activityObserver)
activityLifecycle = null

val detachingCallback = callback
if (activeCallback === detachingCallback) {
activeCallback = null
}

callback = null
val view = webView

if (session.isOpen) {
session.setFocused(false)
session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
}

if (view != null && view.session == session) {
view.session?.setActive(false)
view.releaseSession()
}

if (completely) webView = null

if (destroyTab) {
homeLoadToken++
mediaSessionDelegate.mediaSession?.stop()
session.close()
} else if (!tab.selected) {
// On low-RAM TVs, retain tab history rather than a whole background page process.
trimMemory()
}
}

override fun trimMemory() {
// Keep the selected page and its video alive. Discard only inactive sessions.
if (tab.selected || browserInForeground) return
val currentView = webView

if (currentView?.session === session) {
session.setActive(false)
currentView.releaseSession()
}
if (session.isOpen) {
homeLoadToken++
session.close()
Log.i(TAG, "Released an inactive tab session under memory pressure")
}
// progressDelegate retains the lightweight history state for reopening the tab.
// The shared view may belong to another tab; never detach that tab's session.
webView = null
}

override fun onPermissionsResult(
requestCode: Int,
permissions: Array<String>,
grantResults: IntArray
): Boolean {
return permissionDelegate.onRequestPermissionsResult(
requestCode,
permissions,
grantResults
)
}

override fun isSameSession(
internalRepresentation: Any
): Boolean {
return internalRepresentation is GeckoSession &&
internalRepresentation == session
}

private var homeBookmarkHoldHandledAt: Long? = null

fun onHomeBookmarkHoldHandled() {
homeBookmarkHoldHandledAt = SystemClock.elapsedRealtime()
}

override fun onLongPress(x: Int, y: Int) {
val homeUrl = url ?: tab.url

val isHome = homeUrl == Config.HOME_URL_ALIAS ||
homeUrl.substringBefore('#').substringBefore('?').trimEnd('/') ==
Config.HOME_PAGE_URL.trimEnd('/')

val view = webView

if (isHome &&
view != null &&
view.width > 0 &&
view.height > 0) {
if (homeBookmarkHoldHandledAt?.let {
SystemClock.elapsedRealtime() - it < 1500L
} == true) return

evaluateJavascript(
"if (typeof editHomeBookmarkAt === 'function') " +
"editHomeBookmarkAt($x * window.innerWidth / ${view.width}, " +
"$y * window.innerHeight / ${view.height});"
)

return
}

uiHandler.postDelayed({
callback?.onContextMenu(
webView!!.cursorDrawerDelegate,
navigationDelegate.locationURL,
null,
null,
null,
null,
null,
x,
y
)
}, 100)
}

override fun isVirtualCursorMode(): Boolean =
webView?.virtualCursorMode ?: true

override fun setVirtualCursorMode(enabled: Boolean) {
if (enabled) {
webView?.cursorDrawerDelegate?.animateAppearing()
}
webView?.virtualCursorMode = enabled
}

override fun getCursorDrawerDelegate(): CursorDrawerDelegate? =
webView?.cursorDrawerDelegate
}
