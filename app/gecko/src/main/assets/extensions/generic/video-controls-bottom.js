(() => {
    const updateOriginal = tvbroUpdateVideoControlsUnsafe;
    const MESSAGE = "__mom_visible_frame_2";
    const REQUEST = "__mom_visible_frame_request_2";
    const childSizes = new WeakMap();
    const sentClips = new WeakMap();
    let parentClip = null;
    let pending = false;

    function viewport() {
        const v = window.visualViewport;
        const left = v ? v.offsetLeft : 0;
        const top = v ? v.offsetTop : 0;
        return {
            left,
            top,
            right: left + (v ? v.width : window.innerWidth),
            bottom: top + (v ? v.height : window.innerHeight)
        };
    }

    function intersect(a, b) {
        const left = Math.max(a.left, b.left);
        const top = Math.max(a.top, b.top);
        return {
            left,
            top,
            right: Math.max(left, Math.min(a.right, b.right)),
            bottom: Math.max(top, Math.min(a.bottom, b.bottom))
        };
    }

    function visibleViewport() {
        const own = viewport();
        return window === window.top || !parentClip
            ? own : intersect(own, parentClip);
    }

    function frameFor(source) {
        for (const frame of document.querySelectorAll("iframe, frame")) {
            try {
                if (frame.contentWindow === source) return frame;
            } catch (_) {}
        }
        return null;
    }

    function clippedFrame(frame) {
        let clip = visibleViewport();
        for (
            let node = frame.parentElement;
            node;
            node = node.parentElement
        ) {
            if (node === document.documentElement ||
                node === document.scrollingElement) continue;

            const style = getComputedStyle(node);
            const rect = node.getBoundingClientRect();
            const sx = rect.width / Math.max(1, node.offsetWidth);
            const sy = rect.height / Math.max(1, node.offsetHeight);
            const bounds = {
                left: rect.left + node.clientLeft * sx,
                top: rect.top + node.clientTop * sy,
                right: rect.left +
                    (node.clientLeft + node.clientWidth) * sx,
                bottom: rect.top +
                    (node.clientTop + node.clientHeight) * sy
            };

            if (/^(auto|scroll|hidden|clip)$/.test(style.overflowX)) {
                clip = intersect(clip, {
                    left: bounds.left,
                    right: bounds.right,
                    top: clip.top,
                    bottom: clip.bottom
                });
            }

            if (/^(auto|scroll|hidden|clip)$/.test(style.overflowY)) {
                clip = intersect(clip, {
                    left: clip.left,
                    right: clip.right,
                    top: bounds.top,
                    bottom: bounds.bottom
                });
            }
        }
        return clip;
    }

    function updateChildFrames() {
        for (const frame of document.querySelectorAll("iframe, frame")) {
            const rect = frame.getBoundingClientRect();
            const size = childSizes.get(frame) || {
                width: frame.clientWidth,
                height: frame.clientHeight
            };

            if (!frame.contentWindow || !size.width || !size.height) {
                continue;
            }

            const borderX = rect.width / Math.max(1, frame.offsetWidth);
            const borderY = rect.height / Math.max(1, frame.offsetHeight);
            const width = frame.clientWidth * borderX;
            const height = frame.clientHeight * borderY;
            const left = rect.left + frame.clientLeft * borderX;
            const top = rect.top + frame.clientTop * borderY;

            const area = intersect(clippedFrame(frame), {
                left,
                top,
                right: left + width,
                bottom: top + height
            });

            const sx = width / size.width;
            const sy = height / size.height;
            let clip = {
                left: 0,
                top: 0,
                right: 0,
                bottom: 0
            };

            if (sx > 0 && sy > 0 &&
                getComputedStyle(frame).visibility !== "hidden") {
                clip = {
                    left: Math.max(
                        0,
                        Math.min(size.width, (area.left - left) / sx)
                    ),
                    top: Math.max(
                        0,
                        Math.min(size.height, (area.top - top) / sy)
                    ),
                    right: Math.max(
                        0,
                        Math.min(size.width, (area.right - left) / sx)
                    ),
                    bottom: Math.max(
                        0,
                        Math.min(size.height, (area.bottom - top) / sy)
                    )
                };
            }

            const signature = JSON.stringify(clip);
            if (sentClips.get(frame) === signature) continue;
            sentClips.set(frame, signature);

            try {
                frame.contentWindow.postMessage({
                    marker: MESSAGE,
                    clip
                }, "*");
            } catch (_) {}
        }
    }

    function fitNtvPage() {
        if (window !== window.top ||
            !/(^|\.)ntv\.cx$/i.test(location.hostname) ||
            !document.documentElement) return;

        const root = document.documentElement;
        const fit = !!document.querySelector(".watch-player-wrapper") &&
            !document.fullscreenElement &&
            !document.mozFullScreenElement;

        if (!fit) {
            root.removeAttribute("data-mom-ntv-fit");
            return;
        }

        if (!document.getElementById("mom-ntv-fit-style")) {
            const style = document.createElement("style");
            style.id = "mom-ntv-fit-style";
            style.textContent = `
html[data-mom-ntv-fit] {
    height: auto !important;
    min-height: 100% !important;
    overflow-y: auto !important;
}
html[data-mom-ntv-fit] body {
    height: auto !important;
    max-height: none !important;
    overflow-y: visible !important;
    overscroll-behavior-y: auto !important;
}
html[data-mom-ntv-fit] .main-layout:has(.watch-page-wrapper) {
    height: auto !important;
    min-height: 0 !important;
    overflow: visible !important;
    padding-bottom: calc(var(--mobile-nav-clearance, 0px) + 12px) !important;
}
html[data-mom-ntv-fit] .watch-page-wrapper {
    position: relative !important;
    inset: auto !important;
    height: auto !important;
    max-height: none !important;
    overflow: visible !important;
}
html[data-mom-ntv-fit] .watch-page-container {
    height: auto !important;
    max-height: none !important;
    min-height: 0 !important;
    overflow: visible !important;
}
html[data-mom-ntv-fit] .watch-player-wrapper {
    position: relative !important;
    top: auto !important;
    min-height: 0 !important;
    max-height: var(--mom-ntv-player-height) !important;
}`;
            root.appendChild(style);
        }

        const area = viewport();
        root.style.setProperty(
            "--mom-ntv-player-height",
            Math.max(
                120,
                Math.floor((area.bottom - area.top) * 0.8)
            ) + "px"
        );
        root.setAttribute("data-mom-ntv-fit", "true");
    }

    function positionControls() {
        const host = tvbroVideoControlsHost;
        if (!host || host.style.display === "none") return;

        const candidate = tvbroVideoCandidate();
        if (!candidate) return;

        const area = intersect(visibleViewport(), {
            left: candidate.left,
            top: candidate.top,
            right: candidate.right,
            bottom: candidate.bottom
        });

        const width = host.getBoundingClientRect().width || 124;
        const height = host.getBoundingClientRect().height || 48;

        if (area.right - area.left < width ||
            area.bottom - area.top < height) {
            host.style.display = "none";
            return;
        }

        host.style.left = Math.max(
            area.left,
            Math.min(candidate.left + 8, area.right - width)
        ) + "px";

        host.style.top = Math.max(
            area.top,
            area.bottom - height - 8
        ) + "px";
    }

    function refresh() {
        fitNtvPage();
        updateOriginal();
        positionControls();
        updateChildFrames();

        if (window !== window.top) {
            try {
                window.parent.postMessage({
                    marker: REQUEST,
                    width: window.innerWidth,
                    height: window.innerHeight
                }, "*");
            } catch (_) {}
        }
    }

    function schedule() {
        if (pending) return;
        pending = true;
        requestAnimationFrame(() => {
            pending = false;
            try {
                refresh();
            } catch (_) {}
        });
    }

    tvbroUpdateVideoControlsUnsafe = refresh;

    window.addEventListener("message", event => {
        const data = event.data;
        if (!data) return;

        if (data.marker === REQUEST) {
            const frame = frameFor(event.source);
            if (!frame || !Number.isFinite(data.width) ||
                !Number.isFinite(data.height) ||
                data.width <= 0 || data.height <= 0) return;

            childSizes.set(frame, {
                width: data.width,
                height: data.height
            });

            // A new document needs an unchanged clipping rectangle too.
            sentClips.delete(frame);
            schedule();
        } else if (
            data.marker === MESSAGE &&
            event.source === window.parent &&
            window !== window.top
        ) {
            const clip = data.clip;

            if (!clip ||
                ![clip.left, clip.top, clip.right, clip.bottom]
                    .every(Number.isFinite)) return;

            if (clip.right < clip.left || clip.bottom < clip.top) {
                return;
            }

            if (JSON.stringify(parentClip) === JSON.stringify(clip)) {
                return;
            }

            parentClip = clip;

            // Offscreen frames may have paused animation frames.
            try {
                refresh();
            } catch (_) {}
        }
    });

    ["resize", "scroll", "pageshow"].forEach(type =>
        window.addEventListener(type, schedule, {
            passive: true,
            capture: true
        })
    );

    ["fullscreenchange", "mozfullscreenchange"].forEach(type =>
        document.addEventListener(type, schedule, true)
    );

    if (window.visualViewport) {
        window.visualViewport.addEventListener("resize", schedule);
        window.visualViewport.addEventListener("scroll", schedule);
    }

    function blocked() {
        return !momBrowserInForeground ||
            momNativeInlineActive ||
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
