console.log("TV Bro generic content extension loaded");
window.addEventListener("load", function () {
console.log("window.load executed");
});
const communicatePort = browser.runtime.connectNative("tvbro_content");
// A document keeps this identity across pushState/replaceState URL changes.
// A replacement document gets a new identity and its old port is released.
const momFrameId = Array.from(
crypto.getRandomValues(new Uint32Array(4)),
value => value.toString(16)
).join("-");
let momLastFrameUrl = location.href;
let momNativeReleaseRequested = false;
// Media3-only test: HTML elements may initialize to reveal playlists, but
// after HLS/DASH detection they are never allowed to play as a fallback.
let momMedia3StreamDetected = false;
function momSuppressWebsiteMedia() {
    if (!momMedia3StreamDetected) return;
    document.querySelectorAll("video, audio").forEach(media => {
        try {
            media.muted = true;
            media.pause();
        } catch (_) {}
    });
}
// Streaming pages can create replacement video elements after detection.
["play", "playing"].forEach(type => {
    window.addEventListener(type, event => {
        if (!momMedia3StreamDetected) return;
        const media = event.target;
        if (media && typeof media.pause === "function") {
            try { media.muted = true; media.pause(); } catch (_) {}
        }
    }, true);
});
communicatePort.postMessage({ type: "inlineFrameReady", frameId: momFrameId });

function momReleaseNativeFrame(reason) {
try {
communicatePort.postMessage({
type: "inlineFrameReleased",
frameId: momFrameId,
reason: reason
});
} catch (_) {}
}

function momCheckFrameNavigation() {
if (momLastFrameUrl === location.href) return;
momLastFrameUrl = location.href;
momMedia3StreamDetected = false;
momReleaseNativeFrame("navigation");
}

browser.runtime.onMessage.addListener(message => {
if (!message || message.type !== "nativeStreamDetected") return;
momCheckFrameNavigation();
momMedia3StreamDetected = true;
momSuppressWebsiteMedia();
tvbroUpdateVideoControls();
try {
communicatePort.postMessage({
type: "nativeStreamDetected",
url: message.url,
origin: message.origin,
frameId: momFrameId
});
} catch (_) {}
});

let momBrowserInForeground = true;
let momNativeInlineActive = false;
const momNativeVideoStates = new Map();

function momHandleNativeMessage(message) {
if (message && message.type === "browserForeground") {
momBrowserInForeground = message.foreground === true;
if (!momBrowserInForeground) {
document.querySelectorAll("video, audio").forEach(media => {
try {
media.pause();
} catch (_) {}
});
}
return;
}
if (!message || message.type !== "nativeInlinePlayback") return;
if (message.active) {
momMedia3StreamDetected = true;
momSuppressWebsiteMedia();
if (momNativeInlineActive) return;
momPinnedVideo = momGeometryVideo && momGeometryVideo.isConnected
? momGeometryVideo : tvbroBestVideo();
momNativeInlineActive = true;
momNativeReleaseRequested = false;
document.querySelectorAll("video").forEach(v => {
momNativeVideoStates.set(v, {
paused: v.paused,
muted: v.muted,
source: v.currentSrc,
userPaused: tvbroAutoplayInfo.get(v)?.userPaused,
capturedAt: Date.now()
});
try {
v.pause();
v.muted = true;
} catch (_) {}
});
} else {
momNativeInlineActive = false;
momPinnedVideo = null;
momNativeReleaseRequested = false;
for (const [v, state] of momNativeVideoStates) {
// Strict Media3-only mode: never resume the HTML player when Media3 exits.
if (momMedia3StreamDetected) {
    try { v.muted = true; v.pause(); } catch (_) {}
    continue;
}
v.muted = state.muted;
const info = tvbroAutoplayInfo.get(v);
if (info) info.userPaused = state.userPaused === true;
if (momBrowserInForeground &&
message.restorePlayback !== false &&
v.isConnected &&
!state.replaced &&
!state.paused &&
!v.ended) {
if (v.currentSrc === state.source &&
Date.now() - state.capturedAt > 60_000 &&
!Number.isFinite(v.duration) &&
v.seekable.length > 0) {
try {
const last = v.seekable.length - 1;
const start = v.seekable.start(last);
const end = v.seekable.end(last);
if (Number.isFinite(end) &&
end - v.currentTime > 30) {
v.currentTime = Math.max(start, end - 3);
}
} catch (_) {}
}
try {
const result = v.play();
if (result && result.catch) result.catch(() => {});
} catch (_) {}
}
}
momNativeVideoStates.clear();
momSuppressWebsiteMedia();
}
}

communicatePort.onMessage.addListener(momHandleNativeMessage);
window.addEventListener("pagehide", () => {
momReleaseNativeFrame("navigation");
// Also reset suspended BFCache documents; do not restart a departing page.
momHandleNativeMessage({
active: false,
type: "nativeInlinePlayback",
restorePlayback: false
});
});
window.addEventListener("pageshow", () => {
momLastRectSent = 0;
momReportVideoRect();
});
["popstate", "hashchange"].forEach(name =>
window.addEventListener(name, momCheckFrameNavigation)
);

function tvbroVideoCandidate() {
let best = null;
document.querySelectorAll("video").forEach(v => {
const r = v.getBoundingClientRect();
if (r.width < 160 || r.height < 90) return;
const left = Math.max(r.left, 0);
const top = Math.max(r.top, 0);
const right = Math.min(r.right, window.innerWidth);
const bottom = Math.min(r.bottom, window.innerHeight);
if (right - left < 20 || bottom - top < 20) return;
let score = (right - left) * (bottom - top);
const playing = !v.paused && !v.ended && v.readyState > 2;
if (!playing) score = score / 10;
if (!best || score > best.score) {
best = {
video: v,
score: score,
left: left,
top: top,
right: right,
bottom: bottom
};
}
});
return best;
}

function tvbroBestVideo() {
const c = tvbroVideoCandidate();
return c ? c.video : null;
}

function tvbroVideoIsMuted(video) {
return !!video && (video.muted || video.volume === 0);
}

const MOM_INLINE_RECT_TAG = "__mom_browser_inline_video_rect_1";
const MOM_INLINE_REFRESH_TAG = "__mom_browser_inline_video_rect_refresh_1";
const MOM_RECT_INTERVAL_MS = 120;
let momLastRectSent = 0;
let momRectTimer = null;
let momLastChildRefresh = 0;
let momChildRefreshTimer = null;
let momGeometryVideo = null;
let momPinnedVideo = null;
let momLastOwnRect = null;
const momChildRects = new Map();
const momLastTranslatedRects = new WeakMap();

function momDomFullscreen() {
return !!(
document.fullscreenElement ||
document.mozFullScreenElement
);
}

function momIntersectRect(
x, y, width, height,
left, top, right, bottom
) {
const clipX = Math.max(x, left);
const clipY = Math.max(y, top);
return {
clipX: clipX,
clipY: clipY,
clipWidth: Math.max(
0,
Math.min(x + width, right) - clipX
),
clipHeight: Math.max(
0,
Math.min(y + height, bottom) - clipY
)
};
}

function momZeroClip(rect, fresh) {
return Object.assign({}, rect, {
clipX: 0,
clipY: 0,
clipWidth: 0,
clipHeight: 0,
viewportWidth: window.innerWidth,
viewportHeight: window.innerHeight,
measuredAt: fresh ? Date.now() : rect.measuredAt
});
}

function momIsHidden(element) {
const style = window.getComputedStyle(element);
return style.display === "none" ||
style.visibility === "hidden" ||
style.visibility === "collapse" ||
Number(style.opacity) === 0;
}

function momSendRectToNative(rect) {
if (!(rect.width > 0 &&
rect.height > 0 &&
rect.viewportWidth > 0 &&
rect.viewportHeight > 0)) return;
try {
communicatePort.postMessage(
Object.assign({ type: "inlineVideoRect" }, rect)
);
} catch (_) {}
}

function momRelayRect(rect) {
if (!rect || momDomFullscreen()) return;
if (window === window.top) {
momSendRectToNative(rect);
} else {
try {
window.parent.postMessage({
marker: MOM_INLINE_RECT_TAG,
rect: rect
}, "*");
} catch (_) {}
}
}

function momFindChildFrame(source) {
for (const f of document.querySelectorAll("iframe, frame")) {
try {
if (f.contentWindow === source) return f;
} catch (_) {}
}
return null;
}

function momTranslateChildRect(rect, frame) {
const r = frame.getBoundingClientRect();
if (!frame.isConnected ||
momIsHidden(frame) ||
r.width <= 0 ||
r.height <= 0 ||
frame.clientWidth <= 0 ||
frame.clientHeight <= 0) {
const previous = momLastTranslatedRects.get(frame);
return previous ? momZeroClip(previous, true) : null;
}
const borderScaleX =
r.width / Math.max(1, frame.offsetWidth);
const borderScaleY =
r.height / Math.max(1, frame.offsetHeight);
const contentLeft =
r.left + frame.clientLeft * borderScaleX;
const contentTop =
r.top + frame.clientTop * borderScaleY;
const contentWidth = frame.clientWidth * borderScaleX;
const contentHeight = frame.clientHeight * borderScaleY;
const sx = contentWidth / Math.max(1, rect.viewportWidth);
const sy = contentHeight / Math.max(1, rect.viewportHeight);
const raw = {
frameId: rect.frameId,
frameUrl: rect.frameUrl,
x: contentLeft + rect.x * sx,
y: contentTop + rect.y * sy,
width: rect.width * sx,
height: rect.height * sy,
viewportWidth: window.innerWidth,
viewportHeight: window.innerHeight,
measuredAt: rect.measuredAt
};
const childClip = momIntersectRect(
contentLeft + rect.clipX * sx,
contentTop + rect.clipY * sy,
rect.clipWidth * sx,
rect.clipHeight * sy,
Math.max(0, contentLeft),
Math.max(0, contentTop),
Math.min(window.innerWidth, contentLeft + contentWidth),
Math.min(window.innerHeight, contentTop + contentHeight)
);
const translated = Object.assign(raw, childClip);
momLastTranslatedRects.set(frame, translated);
return translated;
}

function momValidRect(rect) {
return rect &&
typeof rect.frameId === "string" &&
rect.frameId.length > 0 && rect.frameId.length <= 128 &&
typeof rect.frameUrl === "string" &&
rect.frameUrl.length <= 2048 &&
[
rect.x,
rect.y,
rect.width,
rect.height,
rect.viewportWidth,
rect.viewportHeight,
rect.clipX,
rect.clipY,
rect.clipWidth,
rect.clipHeight,
rect.measuredAt
].every(value =>
typeof value === "number" && Number.isFinite(value)
) &&
rect.width > 0 &&
rect.height > 0 &&
rect.viewportWidth > 0 &&
rect.viewportHeight > 0 &&
rect.clipWidth >= 0 &&
rect.clipHeight >= 0;
}

window.addEventListener("message", event => {
if (!event.data) return;
if (event.data.marker === MOM_INLINE_REFRESH_TAG &&
event.source === window.parent) {
momLastRectSent = 0;
momReportVideoRect();
momRefreshChildRects();
return;
}
if (event.data.marker !== MOM_INLINE_RECT_TAG ||
!momValidRect(event.data.rect)) return;
const frame = momFindChildFrame(event.source);
if (!frame) return;
momChildRects.set(frame, event.data.rect);
momReportVideoRect();
});

function momOwnVideoRect() {
momCheckFrameNavigation();
const candidate = tvbroVideoCandidate();
const pinned = momPinnedVideo;
const pinnedRect = pinned?.getBoundingClientRect();
const replacedWhileHidden = pinned && candidate && candidate.video !== pinned &&
(momIsHidden(pinned) || pinnedRect.width <= 0 || pinnedRect.height <= 0);
if (momNativeInlineActive &&
((!pinned && momLastOwnRect) ||
(pinned && (!pinned.isConnected || replacedWhileHidden ||
pinned.currentSrc !== momNativeVideoStates.get(pinned)?.source)))) {
if (replacedWhileHidden) {
const state = momNativeVideoStates.get(pinned);
if (state) state.replaced = true;
}
if (!momNativeReleaseRequested) {
momNativeReleaseRequested = true;
momReleaseNativeFrame("replacement");
}
return momLastOwnRect ? momZeroClip(momLastOwnRect, true) : null;
}
if (momNativeInlineActive && momPinnedVideo) {
momGeometryVideo = momPinnedVideo;
} else {
momGeometryVideo = candidate ? candidate.video : null;
}
const video = momGeometryVideo;
if (!video ||
!video.isConnected ||
momIsHidden(video)) {
return momLastOwnRect
? momZeroClip(momLastOwnRect, true)
: null;
}
const r = video.getBoundingClientRect();
if (r.width <= 0 || r.height <= 0) {
return momLastOwnRect
? momZeroClip(momLastOwnRect, true)
: null;
}
const rect = Object.assign({
frameId: momFrameId,
frameUrl: location.href,
x: r.left,
y: r.top,
width: r.width,
height: r.height,
viewportWidth: window.innerWidth,
viewportHeight: window.innerHeight,
measuredAt: Date.now()
}, momIntersectRect(
r.left,
r.top,
r.width,
r.height,
0,
0,
window.innerWidth,
window.innerHeight
));
momLastOwnRect = rect;
return rect;
}

function momReportVideoRect() {
const now = Date.now();
const wait = MOM_RECT_INTERVAL_MS - (now - momLastRectSent);
if (wait > 0) {
if (momRectTimer === null) {
momRectTimer = setTimeout(() => {
momRectTimer = null;
momReportVideoRect();
}, wait);
}
return;
}
if (momRectTimer !== null) {
clearTimeout(momRectTimer);
momRectTimer = null;
}
momLastRectSent = now;
const ownRect = momOwnVideoRect();
if (momDomFullscreen()) return;
momRelayRect(ownRect);
for (const [frame, rect] of momChildRects) {
if (!frame.isConnected) {
const previous = momLastTranslatedRects.get(frame);
if (previous) momRelayRect(momZeroClip(previous, true));
momChildRects.delete(frame);
continue;
}
momRelayRect(momTranslateChildRect(rect, frame));
}
}

function momRefreshChildRects() {
const wait =
MOM_RECT_INTERVAL_MS - (Date.now() - momLastChildRefresh);
if (wait > 0) {
if (momChildRefreshTimer === null) {
momChildRefreshTimer = setTimeout(() => {
momChildRefreshTimer = null;
momRefreshChildRects();
}, wait);
}
return;
}
if (momChildRefreshTimer !== null) {
clearTimeout(momChildRefreshTimer);
momChildRefreshTimer = null;
}
momLastChildRefresh = Date.now();
for (const frame of document.querySelectorAll("iframe, frame")) {
try {
frame.contentWindow.postMessage({
marker: MOM_INLINE_REFRESH_TAG
}, "*");
} catch (_) {}
}
}

window.addEventListener("scroll", () => {
momReportVideoRect();
momRefreshChildRects();
}, { passive: true });

window.addEventListener("resize", () => {
momReportVideoRect();
momRefreshChildRects();
});

["fullscreenchange", "mozfullscreenchange"].forEach(name => {
document.addEventListener(name, () => {
if (!momDomFullscreen()) {
momLastRectSent = 0;
momReportVideoRect();
momRefreshChildRects();
}
}, true);
});

let tvbroLastActivation = 0;

function tvbroActivate(which) {
const now = Date.now();
if (now - tvbroLastActivation < 600) return;
tvbroLastActivation = now;
if (which === "unmute") {
tvbroDoUnmute();
} else {
tvbroDoFullscreen();
}
}

function tvbroDoUnmute() {
const video = tvbroBestVideo();
if (!video) return;
try {
video.removeAttribute("muted");
video.defaultMuted = false;
video.muted = false;
if (video.volume === 0) video.volume = 1;
} catch (e) {
console.log("TV Bro: video unmute failed:", e);
}
tvbroUpdateVideoControls();
}

function tvbroDoFullscreen() {
const video = tvbroBestVideo();
if (!video) return;
const request =
video.requestFullscreen || video.mozRequestFullScreen;
if (typeof request !== "function") return;
try {
const result = request.call(video);
if (result && typeof result.catch === "function") {
result.catch(e =>
console.log("TV Bro: fullscreen request rejected:", e)
);
}
} catch (e) {
console.log("TV Bro: fullscreen request failed:", e);
}
}

function tvbroButtonAt(x, y) {
if (!tvbroVideoControlsHost ||
tvbroVideoControlsHost.style.display === "none") return null;
const MARGIN = 6;
const candidates = [
[tvbroUnmuteButton, "unmute"],
[tvbroFullscreenButton, "fullscreen"]
];
for (const [button, name] of candidates) {
if (!button || button.hidden) continue;
const r = button.getBoundingClientRect();
if (r.width === 0 || r.height === 0) continue;
if (x >= r.left - MARGIN &&
x <= r.right + MARGIN &&
y >= r.top - MARGIN &&
y <= r.bottom + MARGIN) {
return name;
}
}
return null;
}

function tvbroEventPoint(e) {
if (typeof e.clientX === "number") {
return { x: e.clientX, y: e.clientY };
}
const t = e.changedTouches && e.changedTouches[0];
return t ? { x: t.clientX, y: t.clientY } : null;
}

function tvbroOnTapCapture(e) {
if (!e.isTrusted) return;
const p = tvbroEventPoint(e);
if (!p) return;
const which = tvbroButtonAt(p.x, p.y);
if (!which) return;
e.preventDefault();
e.stopImmediatePropagation();
if (e.type === "pointerup" ||
e.type === "touchend" ||
e.type === "mouseup" ||
e.type === "click") {
tvbroActivate(which);
}
}

[
"pointerdown", "mousedown", "touchstart",
"pointerup", "mouseup", "touchend", "click"
].forEach(type =>
window.addEventListener(type, tvbroOnTapCapture, {
capture: true,
passive: false
})
);

let tvbroVideoControlsHost = null;
let tvbroFullscreenButton = null;
let tvbroUnmuteButton = null;

function tvbroCreateVideoControls() {
if (tvbroVideoControlsHost ||
!document.documentElement) return;
tvbroVideoControlsHost = document.createElement("div");
tvbroVideoControlsHost.id = "tvbro-video-controls";
tvbroVideoControlsHost.style.cssText =
"position:fixed;top:0;left:0;width:124px;z-index:2147483647;" +
"display:none;pointer-events:none;";
const shadow =
tvbroVideoControlsHost.attachShadow({ mode: "closed" });
const style = document.createElement("style");
style.textContent = `
.controls {
display:flex;align-items:center;justify-content:flex-start;
width:100%;pointer-events:none;
}
button {
position:relative;
box-sizing:border-box;min-width:112px;height:48px;padding:0 13px;
border-radius:26px;border:1px solid #d8dee8;
background:#30363d;color:#fff;
box-shadow:0 2px 9px rgba(0,0,0,.58);
cursor:pointer;display:inline-flex;align-items:center;
justify-content:center;gap:8px;
font:600 13px/1 system-ui,-apple-system,Roboto,sans-serif;
letter-spacing:.1px;white-space:nowrap;
pointer-events:auto;touch-action:manipulation;
-webkit-tap-highlight-color:transparent;
}
button:focus,button:active {
outline:2px solid #fff;outline-offset:2px;background:#454b52;
}
button svg {
width:22px;height:22px;display:block;flex:none;fill:#fff;
}
button::before {
content:"";position:absolute;
top:-6px;right:-6px;bottom:-6px;left:-6px;
}
button * { pointer-events:none; }
button[hidden] { display:none !important; }
`;
const controls = document.createElement("div");
controls.className = "controls";
tvbroUnmuteButton = document.createElement("button");
tvbroUnmuteButton.type = "button";
tvbroUnmuteButton.setAttribute("aria-label", "Unmute video");
tvbroUnmuteButton.title = "Unmute video";
tvbroUnmuteButton.innerHTML =
'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9v6h4l5 4V5L7 9H3z"/><path d="M15.5 8.5a5 5 0 0 1 0 7l1.4 1.4a7 7 0 0 0 0-9.8l-1.4 1.4zM18.3 5.7a9 9 0 0 1 0 12.6l1.4 1.4a11 11 0 0 0 0-15.4l-1.4 1.4z"/></svg><span>Unmute</span>';
tvbroUnmuteButton.addEventListener(
"click",
() => tvbroActivate("unmute"),
true
);
tvbroFullscreenButton = document.createElement("button");
tvbroFullscreenButton.type = "button";
tvbroFullscreenButton.setAttribute(
"aria-label",
"Make video full screen"
);
tvbroFullscreenButton.title = "Make video full screen";
tvbroFullscreenButton.innerHTML =
'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h5v2H7v3H5V5zm9 0h5v5h-2V7h-3V5zM5 14h2v5h5v-2H7v-3H5zm14 0h-2v3h-3v2h5v-5z"/><rect x="9" y="9" width="6" height="6" rx=".5"/></svg><span>Full screen</span>';
tvbroFullscreenButton.addEventListener(
"click",
() => tvbroActivate("fullscreen"),
true
);
controls.appendChild(tvbroUnmuteButton);
controls.appendChild(tvbroFullscreenButton);
shadow.appendChild(style);
shadow.appendChild(controls);
document.documentElement.appendChild(tvbroVideoControlsHost);
}

function tvbroUpdateVideoControls() {
try {
tvbroUpdateVideoControlsUnsafe();
} catch (e) {
console.log("TV Bro: controls update failed:", e);
}
}

function tvbroUpdateVideoControlsUnsafe() {
if (!tvbroVideoControlsHost) tvbroCreateVideoControls();
if (!tvbroVideoControlsHost) return;
if (momMedia3StreamDetected) {
    tvbroVideoControlsHost.style.display = "none";
    return;
}
const fullscreen = !!(
document.fullscreenElement ||
document.mozFullScreenElement
);
const candidate = tvbroVideoCandidate();
const video = candidate ? candidate.video : null;
const hasVideo = video !== null;
tvbroVideoControlsHost.style.display =
hasVideo && !fullscreen ? "block" : "none";
if (hasVideo) {
tvbroVideoControlsHost.style.top =
(candidate.top + 8) + "px";
tvbroVideoControlsHost.style.left =
(candidate.left + 8) + "px";
tvbroVideoControlsHost.style.width = "124px";
}
if (tvbroUnmuteButton) {
tvbroUnmuteButton.hidden =
!hasVideo || !tvbroVideoIsMuted(video);
}
if (tvbroFullscreenButton) {
tvbroFullscreenButton.hidden =
!hasVideo || fullscreen || tvbroVideoIsMuted(video);
}
}

if (document.documentElement) {
tvbroCreateVideoControls();
} else {
document.addEventListener(
"DOMContentLoaded",
tvbroCreateVideoControls,
{ once: true }
);
}

document.addEventListener(
"fullscreenchange",
tvbroUpdateVideoControls,
true
);
document.addEventListener(
"mozfullscreenchange",
tvbroUpdateVideoControls,
true
);

let tvbroLastVideoState = false;
let tvbroVideoCheckTimer = null;
let tvbroSlowChecks = false;

function tvbroReportVideoState() {
tvbroVideoCheckTimer = null;
tvbroSlowChecks = Array.prototype.some.call(
document.querySelectorAll("video"),
v => !v.paused && !v.ended
);
tvbroUpdateVideoControls();
momReportVideoRect();
tvbroAutoplayScan();
const present = tvbroBestVideo() !== null;
if (present === tvbroLastVideoState) return;
tvbroLastVideoState = present;
try {
communicatePort.postMessage({
type: "videoState",
present: present
});
} catch (e) {
console.log("TV Bro: failed to report video state:", e);
}
}

function tvbroScheduleVideoCheck() {
if (tvbroVideoCheckTimer === null) {
tvbroVideoCheckTimer = setTimeout(
tvbroReportVideoState,
tvbroSlowChecks ? 1500 : 300
);
}
}

function tvbroScheduleVideoCheckNow() {
if (tvbroVideoCheckTimer !== null) {
clearTimeout(tvbroVideoCheckTimer);
}
tvbroVideoCheckTimer =
setTimeout(tvbroReportVideoState, 300);
}

let tvbroRepositionPending = false;

function tvbroScheduleReposition() {
if (tvbroRepositionPending) return;
tvbroRepositionPending = true;
requestAnimationFrame(() => {
tvbroRepositionPending = false;
tvbroUpdateVideoControls();
momReportVideoRect();
});
}

const MOM_MIN_VIDEO_FRAME_WIDTH = 160;
const MOM_MIN_VIDEO_FRAME_HEIGHT = 90;
let momVideoWatchersStarted = false;
let momSmallFrameProbe = null;

function momFrameLargeEnoughForVideoWatchers() {
return window === window.top ||
(window.innerWidth >= MOM_MIN_VIDEO_FRAME_WIDTH &&
window.innerHeight >= MOM_MIN_VIDEO_FRAME_HEIGHT);
}

function momActivateVideoWatchers() {
if (momVideoWatchersStarted ||
!momFrameLargeEnoughForVideoWatchers()) return;
momVideoWatchersStarted = true;
if (momSmallFrameProbe !== null) {
clearInterval(momSmallFrameProbe);
momSmallFrameProbe = null;
}
new MutationObserver(() => {
if (momNativeInlineActive) momReportVideoRect();
tvbroScheduleVideoCheck();
}).observe(
document,
{
childList: true,
subtree: true
}
);
[
"loadedmetadata", "play", "playing", "pause",
"emptied", "resize", "volumechange"
].forEach(name =>
document.addEventListener(
name,
tvbroScheduleVideoCheckNow,
true
)
);
window.addEventListener(
"scroll",
tvbroScheduleReposition,
{ passive: true }
);
window.addEventListener("resize", tvbroScheduleReposition);
setInterval(tvbroScheduleVideoCheck, 2000);
tvbroScheduleVideoCheckNow();
}

momActivateVideoWatchers();

if (!momVideoWatchersStarted) {
window.addEventListener("resize", momActivateVideoWatchers);
window.addEventListener(
"load",
momActivateVideoWatchers,
{ once: true }
);
if (document.readyState === "loading") {
document.addEventListener(
"DOMContentLoaded",
momActivateVideoWatchers,
{ once: true }
);
}
momSmallFrameProbe =
setInterval(momActivateVideoWatchers, 5000);
}

const tvbroAutoplayEnabled = true;
const tvbroAutoplayInfo = new WeakMap();
const tvbroObservedVideos = new WeakSet();

function tvbroVideoOnScreen(v) {
const r = v.getBoundingClientRect();
return r.width > 0 &&
r.height > 0 &&
r.bottom > 0 &&
r.right > 0 &&
r.top < window.innerHeight &&
r.left < window.innerWidth;
}

function tvbroStartPlayback(video) {
if (!momBrowserInForeground || momNativeInlineActive ||
    momMedia3StreamDetected) return;
const start = () => {
const p = video.play();
return (p && p.then) ? p : Promise.resolve();
};
start().catch(() => {
if (!momBrowserInForeground || momNativeInlineActive ||
    momMedia3StreamDetected) return;
try {
video.muted = true;
start().catch(() => {});
} catch (_) {}
});
}

const TVBRO_PLAY_OVERLAY_SELECTORS = [
".player-poster",
".play-wrapper",
".poster-icon",
".vjs-big-play-button",
".jw-display-icon-container",
".jw-icon-display",
".plyr__control--overlaid",
".ytp-large-play-button",
"[class*='big-play']",
"[class*='bigplay']",
"button[class*='play-button']",
"div[class*='play-button']",
"button[class*='play-btn']",
"button[aria-label='Play']",
"button[title='Play']"
].join(",");

let tvbroOverlayClicksTotal = 0;

function tvbroClickPlayOverlay(video) {
if (tvbroOverlayClicksTotal >= 4) return false;
let container = video.parentElement;
for (
let depth = 0;
container && depth < 6;
depth++, container = container.parentElement
) {
let found;
try {
found = container.querySelectorAll(
TVBRO_PLAY_OVERLAY_SELECTORS
);
} catch (_) {
return false;
}
for (const el of found) {
const r = el.getBoundingClientRect();
if (r.width === 0 || r.height === 0) continue;
if (el.closest("a[href]")) continue;
tvbroOverlayClicksTotal++;
el.dispatchEvent(new MouseEvent("click", {
bubbles: true,
cancelable: true,
view: window
}));
return true;
}
if (container === document.body) break;
}
return false;
}

function tvbroTryAutoplay(video) {
if (!momBrowserInForeground ||
momNativeInlineActive ||
momMedia3StreamDetected ||
!tvbroAutoplayEnabled ||
!video.isConnected) return;
let info = tvbroAutoplayInfo.get(video);
if (!info) {
info = {
attempts: 0,
overlayClicks: 0,
overlayTimer: null,
played: false,
userPaused: false
};
tvbroAutoplayInfo.set(video, info);
}
if (info.userPaused || video.ended || !video.paused) return;
if (info.attempts < 6) {
info.attempts++;
tvbroStartPlayback(video);
}
if (info.overlayTimer === null && info.overlayClicks < 3) {
info.overlayTimer = setTimeout(() => {
info.overlayTimer = null;
if (!momBrowserInForeground ||
momNativeInlineActive ||
momMedia3StreamDetected ||
!video.isConnected ||
info.userPaused ||
video.ended ||
!video.paused) return;
info.overlayClicks++;
if (tvbroClickPlayOverlay(video)) {
setTimeout(() => {
if (momBrowserInForeground &&
!momNativeInlineActive &&
!momMedia3StreamDetected &&
video.isConnected &&
video.paused &&
!video.ended &&
!info.userPaused) {
tvbroStartPlayback(video);
}
}, 1200);
}
}, 1500);
}
}

const tvbroVideoObserver =
("IntersectionObserver" in window)
? new IntersectionObserver(entries => {
if (entries.some(en => en.isIntersecting)) {
tvbroAutoplayScan();
}
}, { threshold: 0 })
: null;

function tvbroAutoplayScan() {
if (!momBrowserInForeground ||
momNativeInlineActive ||
momMedia3StreamDetected ||
!momVideoWatchersStarted) return;
const videos = document.querySelectorAll("video");
let playing = false;
let best = null;
let bestArea = 0;
videos.forEach(v => {
if (!tvbroObservedVideos.has(v)) {
tvbroObservedVideos.add(v);
if (tvbroVideoObserver) tvbroVideoObserver.observe(v);
}
if (!v.paused && !v.ended) {
playing = true;
return;
}
if (!tvbroVideoOnScreen(v)) return;
const info = tvbroAutoplayInfo.get(v);
if (info &&
(info.userPaused || info.attempts >= 6)) return;
const r = v.getBoundingClientRect();
const area = r.width * r.height;
if (area > bestArea) {
best = v;
bestArea = area;
}
});
if (tvbroAutoplayEnabled && !playing && best) {
tvbroTryAutoplay(best);
}
}

document.addEventListener("playing", e => {
const info = tvbroAutoplayInfo.get(e.target);
if (info) info.played = true;
}, true);

document.addEventListener("pause", e => {
const v = e.target;
const info = tvbroAutoplayInfo.get(v);
if (!momNativeInlineActive &&
!momMedia3StreamDetected &&
info &&
info.played &&
!v.ended) {
info.userPaused = true;
}
}, true);

document.addEventListener("loadstart", e => {
const info = tvbroAutoplayInfo.get(e.target);
if (info) {
info.attempts = 0;
info.overlayClicks = 0;
info.played = false;
info.userPaused = false;
}
}, true);

[
"loadedmetadata",
"loadeddata",
"durationchange",
"canplay"
].forEach(name =>
document.addEventListener(name, e => {
if (e.target && e.target.tagName === "VIDEO") {
tvbroAutoplayScan();
}
}, true)
);

function tvbroAllowIframe(f) {
try {
const current = f.getAttribute("allow") || "";
const lower = current.toLowerCase();
const missing = ["autoplay", "fullscreen"].filter(
name => !lower.includes(name)
);
if (missing.length > 0) {
f.setAttribute(
"allow",
(current ? current + "; " : "") +
missing.join("; ")
);
}
} catch (_) {}
}

document.querySelectorAll("iframe").forEach(tvbroAllowIframe);

new MutationObserver(mutations => {
mutations.forEach(m =>
m.addedNodes.forEach(n => {
if (n.nodeType !== 1) return;
if (n.tagName === "IFRAME") {
tvbroAllowIframe(n);
} else if (n.getElementsByTagName) {
Array.prototype.forEach.call(
n.getElementsByTagName("iframe"),
tvbroAllowIframe
);
}
})
);
}).observe(document, {
childList: true,
subtree: true
});
