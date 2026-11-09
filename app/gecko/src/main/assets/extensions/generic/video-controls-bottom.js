// Runs after content.js in the same extension frame.
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

    tvbroUpdateVideoControls();
})();
