console.log("TV Bro generic content extension loaded");

window.addEventListener("load", function () {
    console.log("window.load executed");
});

const communicatePort = browser.runtime.connectNative("tvbro_content");

// The background script supplies the exact requesting tab and frame.
browser.runtime.onMessage.addListener(message => {
    if (!message || message.type !== "nativeStreamDetected") return;

    try {
        communicatePort.postMessage({
            type: "nativeStreamDetected",
            url: message.url,
            origin: message.origin
        });
    } catch (_) {
        // Frame navigated; keep website playback.
    }
});

let momNativeInlineActive = false;
const momNativeVideoStates = new Map();

communicatePort.onMessage.addListener(message => {
    if (!message || message.type !== "nativeInlinePlayback") return;

    if (message.active) {
        if (momNativeInlineActive) return;
        momNativeInlineActive = true;

        document.querySelectorAll("video").forEach(v => {
            momNativeVideoStates.set(v, {
                paused: v.paused,
                muted: v.muted,
                source: v.currentSrc,
                userPaused: tvbroAutoplayInfo.get(v)?.userPaused
            });

            try {
                v.pause();
                v.muted = true;
            } catch (_) {}
        });
    } else {
        momNativeInlineActive = false;

        for (const [v, state] of momNativeVideoStates) {
            if (!v.isConnected || v.currentSrc !== state.source) continue;

            v.muted = state.muted;

            const info = tvbroAutoplayInfo.get(v);
            if (info) info.userPaused = state.userPaused === true;

            if (!state.paused && !v.ended) {
                try {
                    const result = v.play();
                    if (result && result.catch) result.catch(() => {});
                } catch (_) {
                    // Website controls remain available if autoplay is denied.
                }
            }
        }

        momNativeVideoStates.clear();
    }
});

// Find the biggest visible video, preferring one that is already playing.
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

// Rectangles use CSS pixels in the top document viewport.
const MOM_INLINE_RECT_TAG = "__mom_browser_inline_video_rect_1";
let momLastRectSent = 0;
const momChildRects = new Map();

function momSendRectToNative(rect) {
    if (!(rect.width > 50 && rect.height > 40 &&
          rect.viewportWidth > 0 && rect.viewportHeight > 0)) return;

    try {
        communicatePort.postMessage(
            Object.assign({ type: "inlineVideoRect" }, rect)
        );
    } catch (_) {
        // The extension port may be gone during navigation.
    }
}

function momRelayRect(rect, force) {
    if (!rect) return;

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
    const frames = document.querySelectorAll("iframe, frame");

    for (const f of frames) {
        try {
            if (f.contentWindow === source) return f;
        } catch (_) {}
    }

    return null;
}

function momTranslateChildRect(rect, frame) {
    const r = frame.getBoundingClientRect();
    const sx = r.width / Math.max(1, rect.viewportWidth);
    const sy = r.height / Math.max(1, rect.viewportHeight);

    const left = r.left + rect.x * sx;
    const top = r.top + rect.y * sy;
    const right = left + rect.width * sx;
    const bottom = top + rect.height * sy;
    const x = Math.max(0, left);
    const y = Math.max(0, top);

    return {
        frameUrl: rect.frameUrl,
        x: x,
        y: y,
        width: Math.max(0, Math.min(window.innerWidth, right) - x),
        height: Math.max(0, Math.min(window.innerHeight, bottom) - y),
        viewportWidth: window.innerWidth,
        viewportHeight: window.innerHeight
    };
}

window.addEventListener("message", event => {
    if (!event.data || event.data.marker !== MOM_INLINE_RECT_TAG) return;

    const frame = momFindChildFrame(event.source);
    if (!frame || !event.data.rect) return;

    momChildRects.set(frame, event.data.rect);
    momRelayRect(momTranslateChildRect(event.data.rect, frame), true);
});

function momReportVideoRect() {
    const now = Date.now();
    if (now - momLastRectSent < 450) return;
    momLastRectSent = now;

    const candidate = tvbroVideoCandidate();

    if (candidate) {
        momRelayRect({
            frameUrl: location.href,
            x: candidate.left,
            y: candidate.top,
            width: candidate.right - candidate.left,
            height: candidate.bottom - candidate.top,
            viewportWidth: window.innerWidth,
            viewportHeight: window.innerHeight
        }, false);
    }

    for (const [frame, rect] of momChildRects) {
        if (!frame.isConnected) {
            momChildRects.delete(frame);
            continue;
        }

        momRelayRect(momTranslateChildRect(rect, frame), false);
    }
}

window.addEventListener("scroll", momReportVideoRect, { passive: true });
window.addEventListener("resize", momReportVideoRect);

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

    const request = video.requestFullscreen || video.mozRequestFullScreen;
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
    if (tvbroVideoControlsHost || !document.documentElement) return;

    tvbroVideoControlsHost = document.createElement("div");
    tvbroVideoControlsHost.id = "tvbro-video-controls";
    tvbroVideoControlsHost.style.cssText =
        "position:fixed;top:0;left:0;width:124px;z-index:2147483647;" +
        "display:none;pointer-events:none;";

    const shadow = tvbroVideoControlsHost.attachShadow({ mode: "closed" });
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
        "click", () => tvbroActivate("unmute"), true
    );

    tvbroFullscreenButton = document.createElement("button");
    tvbroFullscreenButton.type = "button";
    tvbroFullscreenButton.setAttribute(
        "aria-label", "Make video full screen"
    );
    tvbroFullscreenButton.title = "Make video full screen";
    tvbroFullscreenButton.innerHTML =
        '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h5v2H7v3H5V5zm9 0h5v5h-2V7h-3V5zM5 14h2v5h5v-2H7v-3H5zm14 0h-2v3h-3v2h5v-5z"/><rect x="9" y="9" width="6" height="6" rx=".5"/></svg><span>Full screen</span>';

    tvbroFullscreenButton.addEventListener(
        "click", () => tvbroActivate("fullscreen"), true
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

    const fullscreen = !!(
        document.fullscreenElement || document.mozFullScreenElement
    );
    const candidate = tvbroVideoCandidate();
    const video = candidate ? candidate.video : null;
    const hasVideo = video !== null;

    tvbroVideoControlsHost.style.display =
        hasVideo && !fullscreen ? "block" : "none";

    if (hasVideo) {
        tvbroVideoControlsHost.style.top = (candidate.top + 8) + "px";
        tvbroVideoControlsHost.style.left = (candidate.left + 8) + "px";
        tvbroVideoControlsHost.style.width = "124px";
    }

    if (tvbroUnmuteButton) {
        tvbroUnmuteButton.hidden = !hasVideo || !tvbroVideoIsMuted(video);
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
        "DOMContentLoaded", tvbroCreateVideoControls, { once: true }
    );
}

document.addEventListener(
    "fullscreenchange", tvbroUpdateVideoControls, true
);
document.addEventListener(
    "mozfullscreenchange", tvbroUpdateVideoControls, true
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

    tvbroVideoCheckTimer = setTimeout(tvbroReportVideoState, 300);
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

    new MutationObserver(tvbroScheduleVideoCheck).observe(document, {
        childList: true,
        subtree: true
    });

    [
        "loadedmetadata", "play", "playing", "pause",
        "emptied", "resize", "volumechange"
    ].forEach(name =>
        document.addEventListener(name, tvbroScheduleVideoCheckNow, true)
    );

    window.addEventListener(
        "scroll", tvbroScheduleReposition, { passive: true }
    );
    window.addEventListener("resize", tvbroScheduleReposition);

    setInterval(tvbroScheduleVideoCheck, 2000);
    tvbroScheduleVideoCheckNow();
}

momActivateVideoWatchers();

if (!momVideoWatchersStarted) {
    window.addEventListener("resize", momActivateVideoWatchers);
    window.addEventListener(
        "load", momActivateVideoWatchers, { once: true }
    );

    if (document.readyState === "loading") {
        document.addEventListener(
            "DOMContentLoaded", momActivateVideoWatchers, { once: true }
        );
    }

    momSmallFrameProbe = setInterval(momActivateVideoWatchers, 5000);
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
    const start = () => {
        const p = video.play();
        return (p && p.then) ? p : Promise.resolve();
    };

    start().catch(() => {
        try {
            video.muted = true;
            start().catch(() => {});
        } catch (_) {}
    });
}

const TVBRO_PLAY_OVERLAY_SELECTORS = [
    ".player-poster", ".play-wrapper", ".poster-icon",
    ".vjs-big-play-button",
    ".jw-display-icon-container", ".jw-icon-display",
    ".plyr__control--overlaid",
    ".ytp-large-play-button",
    "[class*='big-play']", "[class*='bigplay']",
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
    if (momNativeInlineActive ||
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

            if (momNativeInlineActive ||
                !video.isConnected ||
                info.userPaused ||
                video.ended ||
                !video.paused) return;

            info.overlayClicks++;

            if (tvbroClickPlayOverlay(video)) {
                setTimeout(() => {
                    if (!momNativeInlineActive &&
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

const tvbroVideoObserver = ("IntersectionObserver" in window)
    ? new IntersectionObserver(entries => {
        if (entries.some(en => en.isIntersecting)) {
            tvbroAutoplayScan();
        }
    }, { threshold: 0 })
    : null;

function tvbroAutoplayScan() {
    if (momNativeInlineActive || !momVideoWatchersStarted) return;

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
        if (info && (info.userPaused || info.attempts >= 6)) return;

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

    if (!momNativeInlineActive && info && info.played && !v.ended) {
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
    "loadedmetadata", "loadeddata", "durationchange", "canplay"
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
                (current ? current + "; " : "") + missing.join("; ")
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
}).observe(document, { childList: true, subtree: true });
