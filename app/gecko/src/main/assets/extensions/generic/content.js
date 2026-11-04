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


// ---- Video full screen toggle (triggered by the side button in the app) ----
function tvbroBestVideo() {
    let best = null;
    let bestScore = 0;
    document.querySelectorAll("video").forEach(v => {
        const r = v.getBoundingClientRect();
        let score = r.width * r.height;
        if (r.width < 160 || r.height < 90) return; // ignore tiny / hidden videos (ad pixels, previews)
        // must be (at least partly) on screen
        if (r.bottom <= 0 || r.right <= 0 || r.top >= window.innerHeight || r.left >= window.innerWidth) return;
        const playing = !v.paused && !v.ended && v.readyState > 2;
        if (!playing) score = score / 10; // prefer the video that is actually playing
        if (score > bestScore) { best = v; bestScore = score; }
    });
    return best;
}

function tvbroToggleVideoFullscreen() {
    if (document.fullscreenElement) {
        document.exitFullscreen().catch(() => {});
        return;
    }
    const video = tvbroBestVideo();
    if (!video) return;
    try {
        const p = video.requestFullscreen();
        if (p && p.catch) {
            p.catch(e => console.log("TV Bro: fullscreen request rejected:", e));
        }
    } catch (e) {
        console.log("TV Bro: fullscreen request failed:", e);
    }
}

window.addEventListener("message", function (e) {
    if (!e.data || e.data.tvbroToggleFullscreen !== true) return;
    tvbroToggleVideoFullscreen();
    // pass it on to frames nested inside this one
    document.querySelectorAll("iframe").forEach(f => {
        try { f.contentWindow.postMessage(e.data, "*"); } catch (_) {}
    });
});


// ---- Tell the app whether this frame has a video (so it can show / hide the full screen button) ----
let tvbroLastVideoState = false;
let tvbroVideoCheckTimer = null;

function tvbroReportVideoState() {
    tvbroVideoCheckTimer = null;
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
["loadedmetadata", "play", "playing", "pause", "emptied", "resize"].forEach(name =>
    document.addEventListener(name, tvbroScheduleVideoCheck, true));
// scrolled / resized (video moved on or off screen)
window.addEventListener("scroll", tvbroScheduleVideoCheck, { passive: true });
window.addEventListener("resize", tvbroScheduleVideoCheck);
// cheap safety net for anything the above misses (players that resize without events)
setInterval(tvbroScheduleVideoCheck, 2000);
