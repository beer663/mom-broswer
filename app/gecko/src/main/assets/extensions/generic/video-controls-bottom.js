(() => {
    let pageActive = true;
    let suspended = false;

    const workBlocked = () =>
        !pageActive ||
        !momBrowserInForeground ||
        document.visibilityState === "hidden";

    const guard = fn => function (...args) {
        if (workBlocked()) return false;
        return fn.apply(this, args);
    };

    momReportVideoRect = guard(momReportVideoRect);
    momRefreshChildRects = guard(momRefreshChildRects);
    tvbroAutoplayScan = guard(tvbroAutoplayScan);
    tvbroTryAutoplay = guard(tvbroTryAutoplay);
    tvbroStartPlayback = guard(tvbroStartPlayback);
    tvbroClickPlayOverlay = guard(tvbroClickPlayOverlay);
    tvbroScheduleVideoCheck = guard(tvbroScheduleVideoCheck);
    tvbroScheduleVideoCheckNow = guard(tvbroScheduleVideoCheckNow);

    const reportOriginal = tvbroReportVideoState;
    tvbroReportVideoState = function () {
        if (workBlocked()) {
            tvbroVideoCheckTimer = -1;
            return;
        }
        reportOriginal();
    };

    // One candidate measurement per controls update.
    tvbroUpdateVideoControlsUnsafe = function () {
        if (workBlocked()) {
            if (tvbroVideoControlsHost) {
                tvbroVideoControlsHost.style.display = "none";
            }
            return;
        }

        if (!tvbroVideoControlsHost) tvbroCreateVideoControls();
        const host = tvbroVideoControlsHost;
        if (!host) return;

        const fullscreen = momDomFullscreen();
        const candidate = tvbroVideoCandidate();
        const video = candidate ? candidate.video : null;
        const show =
            video !== null && !fullscreen && !momNativeInlineActive;

        host.style.display = show ? "block" : "none";

        if (show) {
            host.style.top = Math.max(
                candidate.top + 8,
                candidate.bottom - 56
            ) + "px";
            host.style.left = (candidate.left + 8) + "px";
            host.style.width = "124px";
        }

        const muted = tvbroVideoIsMuted(video);
        if (tvbroUnmuteButton) {
            tvbroUnmuteButton.hidden = !show || !muted;
        }
        if (tvbroFullscreenButton) {
            tvbroFullscreenButton.hidden = !show || muted;
        }
    };

    function pauseMedia(media) {
        if (media && typeof media.pause === "function") {
            try {
                media.pause();
            } catch (_) {}
        }
    }

    function pauseBlockedMedia() {
        if (!workBlocked() && !momNativeInlineActive) return;

        document.querySelectorAll("video, audio").forEach(media => {
            pauseMedia(media);
            const info = tvbroAutoplayInfo.get(media);
            if (info && info.overlayTimer !== null) {
                clearTimeout(info.overlayTimer);
                info.overlayTimer = null;
            }
        });
    }

    function updateWorkState() {
        if (workBlocked()) {
            if (!suspended) {
                suspended = true;

                if (tvbroVideoCheckTimer !== null) {
                    clearTimeout(tvbroVideoCheckTimer);
                }
                if (momRectTimer !== null) {
                    clearTimeout(momRectTimer);
                }
                if (momChildRefreshTimer !== null) {
                    clearTimeout(momChildRefreshTimer);
                }

                // The old polling callback sees a pending check and does no work.
                tvbroVideoCheckTimer = -1;
                momRectTimer = null;
                momChildRefreshTimer = null;

                if (tvbroVideoControlsHost) {
                    tvbroVideoControlsHost.style.display = "none";
                }
            }

            pauseBlockedMedia();
            return;
        }

        if (suspended) {
            suspended = false;
            tvbroVideoCheckTimer = null;
            momLastRectSent = 0;
            momLastChildRefresh = 0;
            momActivateVideoWatchers();
            tvbroScheduleVideoCheckNow();
            momReportVideoRect();
            momRefreshChildRects();
        }

        pauseBlockedMedia();
        tvbroUpdateVideoControls();
    }

    ["play", "playing"].forEach(type => {
        window.addEventListener(type, event => {
            if (workBlocked() || momNativeInlineActive) {
                pauseMedia(event.target);
            }
        }, true);
    });

    document.addEventListener(
        "visibilitychange",
        updateWorkState,
        true
    );

    communicatePort.onMessage.addListener(updateWorkState);

    window.addEventListener("pagehide", () => {
        pageActive = false;
        updateWorkState();
    }, true);

    window.addEventListener("pageshow", () => {
        pageActive = true;
        updateWorkState();
    });

    updateWorkState();
})();
