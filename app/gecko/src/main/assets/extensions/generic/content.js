// This is a leftover from the removal of the abandoned "text selection" feature.
// I'll leave it here for now as a framework for future extension-based features.

console.log("TV Bro generic content extension loaded");

window.addEventListener('load', function () {
    //document.body.style.userSelect = "none";
    console.log("window.load executed");
});

const communicatePort = browser.runtime.connectNative("tvbro_content");

communicatePort.onMessage.addListener(message => {
    console.log("Received message from native app:", message);
});


// ---- GeckoView floating video controls ----
// These controls live in the page so clicks are genuine DOM user gestures.
// Gecko requires user activation for requestFullscreen; an Android-side
// evaluateJavascript call does not reliably preserve that activation.
function tvbroBestVideo() {
    let best = null;
    let bestScore = 0;
    document.querySelectorAll("video").forEach(v => {
        const r = v.getBoundingClientRect();
        let score = r.width * r.height;
        if (r.width < 160 || r.height < 90) return;
        if (r.bottom <= 0 || r.right <= 0 || r.top >= window.innerHeight || r.left >= window.innerWidth) return;
        const playing = !v.paused && !v.ended && v.readyState > 2;
        if (!playing) score = score / 10;
        if (score > bestScore) { best = v; bestScore = score; }
    });
    return best;
}

function tvbroVideoIsMuted(video) {
    return !!video && (video.muted || video.volume === 0);
}

let tvbroVideoControlsHost = null;
let tvbroFullscreenButton = null;
let tvbroUnmuteButton = null;

function tvbroCreateVideoControls() {
    if (tvbroVideoControlsHost || !document.documentElement) return;

    // One full-width overlay keeps the two controls on the same horizontal line.
    // Both are positioned 35% down the current frame's viewport.
    tvbroVideoControlsHost = document.createElement("div");
    tvbroVideoControlsHost.id = "tvbro-video-controls";
    tvbroVideoControlsHost.style.cssText =
        "position:fixed;top:38vh;left:12px;right:12px;z-index:2147483647;" +
        "display:none;pointer-events:none;";

    const shadow = tvbroVideoControlsHost.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = `
        .controls {
            display:flex;align-items:center;justify-content:space-between;
            width:100%;pointer-events:none;
        }
        button {
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
        button svg { width:22px;height:22px;display:block;flex:none;fill:#fff; }
        button[hidden] { display:none !important; }
    `;

    const controls = document.createElement("div");
    controls.className = "controls";

    // Left control: unmute the largest visible video in this document/frame.
    tvbroUnmuteButton = document.createElement("button");
    tvbroUnmuteButton.type = "button";
    tvbroUnmuteButton.setAttribute("aria-label", "Unmute video");
    tvbroUnmuteButton.title = "Unmute video";
    tvbroUnmuteButton.innerHTML =
        '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9v6h4l5 4V5L7 9H3z"/><path d="M15.5 8.5a5 5 0 0 1 0 7l1.4 1.4a7 7 0 0 0 0-9.8l-1.4 1.4zM18.3 5.7a9 9 0 0 1 0 12.6l1.4 1.4a11 11 0 0 0 0-15.4l-1.4 1.4z"/></svg><span>Unmute</span>';
    tvbroUnmuteButton.addEventListener("click", function (event) {
        event.preventDefault();
        event.stopPropagation();
        const video = tvbroBestVideo();
        if (!video) return;
        try {
            // Clear both the HTML attribute and media-element properties; some
            // players set the muted attribute again when they initialize.
            video.removeAttribute("muted");
            video.defaultMuted = false;
            video.muted = false;
            if (video.volume === 0) video.volume = 1;
        } catch (e) {
            console.log("TV Bro: video unmute failed:", e);
        }
        tvbroUpdateVideoControls();
    }, true);

    // Right control: request fullscreen directly from this trusted page click.
    tvbroFullscreenButton = document.createElement("button");
    tvbroFullscreenButton.type = "button";
    tvbroFullscreenButton.setAttribute("aria-label", "Make video full screen");
    tvbroFullscreenButton.title = "Make video full screen";
    tvbroFullscreenButton.innerHTML =
        '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h5v2H7v3H5V5zm9 0h5v5h-2V7h-3V5zM5 14h2v5h5v-2H7v-3H5zm14 0h-2v3h-3v2h5v-5z"/><rect x="9" y="9" width="6" height="6" rx=".5"/></svg><span>Full screen</span>';
    tvbroFullscreenButton.addEventListener("click", function (event) {
        event.preventDefault();
        event.stopPropagation();
        const video = tvbroBestVideo();
        if (!video) return;
        const request = video.requestFullscreen || video.mozRequestFullScreen;
        if (typeof request !== "function") return;
        try {
            const result = request.call(video);
            if (result && typeof result.catch === "function") {
                result.catch(e => console.log("TV Bro: fullscreen request rejected:", e));
            }
        } catch (e) {
            console.log("TV Bro: fullscreen request failed:", e);
        }
    }, true);

    controls.appendChild(tvbroUnmuteButton);
    controls.appendChild(tvbroFullscreenButton);
    shadow.appendChild(style);
    shadow.appendChild(controls);
    document.documentElement.appendChild(tvbroVideoControlsHost);
}

function tvbroUpdateVideoControls() {
    if (!tvbroVideoControlsHost) tvbroCreateVideoControls();
    if (!tvbroVideoControlsHost) return;

    const fullscreen = !!(document.fullscreenElement || document.mozFullScreenElement);
    const video = tvbroBestVideo();
    const hasVideo = video !== null;

    // Hide both controls while the selected video is in fullscreen.
    tvbroVideoControlsHost.style.display = hasVideo && !fullscreen ? "block" : "none";
    if (tvbroUnmuteButton) {
        tvbroUnmuteButton.hidden = !hasVideo || !tvbroVideoIsMuted(video);
    }
    if (tvbroFullscreenButton) {
        tvbroFullscreenButton.hidden = !hasVideo || fullscreen;
    }
}

// Each frame gets its own controls, so this also works for cross-origin players
// embedded in iframes. The host is isolated in a shadow root from page styling.
if (document.documentElement) {
    tvbroCreateVideoControls();
} else {
    document.addEventListener("DOMContentLoaded", tvbroCreateVideoControls, { once: true });
}
document.addEventListener("fullscreenchange", tvbroUpdateVideoControls, true);
document.addEventListener("mozfullscreenchange", tvbroUpdateVideoControls, true);

// ---- Tell the app whether this frame has a video ----
let tvbroLastVideoState = false;
let tvbroVideoCheckTimer = null;

function tvbroReportVideoState() {
    tvbroVideoCheckTimer = null;
    tvbroUpdateVideoControls();
    const present = tvbroBestVideo() !== null;
    if (present === tvbroLastVideoState) return;
    tvbroLastVideoState = present;
    try {
        communicatePort.postMessage({ type: "videoState", present: present });
    } catch (e) {
        console.log("TV Bro: failed to report video state:", e);
    }
}

function tvbroScheduleVideoCheck() {
    if (tvbroVideoCheckTimer === null) {
        tvbroVideoCheckTimer = setTimeout(tvbroReportVideoState, 300);
    }
}

// video appeared / was removed / changed
new MutationObserver(tvbroScheduleVideoCheck).observe(document, { childList: true, subtree: true });
// media events don't bubble, so listen in the capture phase
["loadedmetadata", "play", "playing", "pause", "emptied", "resize", "volumechange"].forEach(name =>
    document.addEventListener(name, tvbroScheduleVideoCheck, true));
// scrolled / resized (video moved on or off screen)
window.addEventListener("scroll", tvbroScheduleVideoCheck, { passive: true });
window.addEventListener("resize", tvbroScheduleVideoCheck);
// cheap safety net for anything the above misses (players that resize without events)
setInterval(tvbroScheduleVideoCheck, 2000);
