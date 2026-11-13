(() => {
    const updateOriginal = tvbroUpdateVideoControlsUnsafe;

    tvbroUpdateVideoControlsUnsafe = function () {
        updateOriginal();

        const host = tvbroVideoControlsHost;
        if (!host || host.style.display === "none") return;

        const candidate = tvbroVideoCandidate();
        if (!candidate) return;

        const height = host.getBoundingClientRect().height || 48;

        host.style.top = Math.max(
            candidate.top + 8,
            candidate.bottom - height - 8
        ) + "px";
    };

    function blocked() {
        return !momBrowserInForeground ||
            momNativeInlineActive ||
            momMedia3StreamDetected ||
            document.visibilityState === "hidden";
    }

    function pauseMedia(media) {
        if (media && typeof media.pause === "function") {
            try {
                media.pause();
            } catch (_) {}
        }
    }

    function pauseBlockedMedia() {
        if (blocked()) {
            document.querySelectorAll("video, audio")
                .forEach(pauseMedia);
        }
    }

    ["play", "playing"].forEach(type => {
        window.addEventListener(type, event => {
            if (blocked()) pauseMedia(event.target);
        }, true);
    });

    document.addEventListener(
        "visibilitychange",
        pauseBlockedMedia,
        true
    );

    communicatePort.onMessage.addListener(pauseBlockedMedia);

    window.addEventListener("pagehide", () => {
        document.querySelectorAll("video, audio")
            .forEach(pauseMedia);
    }, true);

    tvbroUpdateVideoControls();
    pauseBlockedMedia();
})();
