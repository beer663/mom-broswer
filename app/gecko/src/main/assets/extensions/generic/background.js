console.log("TV Bro background extension loaded");

let requests = new Map();
let tvBroPort = browser.runtime.connectNative("tvbro_bg");

tvBroPort.onMessage.addListener(response => {
    if (response.action === "onResolveRequest") {
        let id = response.data.requestId;
        let block = response.data.block;
        if (requests.has(id.toString())) {
            let request = requests.get(id.toString());
            requests.delete(id.toString());
            request.resolverRef({ cancel: block });
        }
    }
});

// Route playlist handoff through the requesting frame's session-bound content port.
// Authenticated requests stay in Gecko: Media3 does not share Gecko's cookie jar.
const TVBRO_PLAYLIST = /\.(m3u8|mpd)(?:[?#]|$)/i;
const tvbroReported = new Map();

browser.webRequest.onBeforeSendHeaders.addListener(details => {
    if (!TVBRO_PLAYLIST.test(details.url) || details.tabId < 0 || details.frameId < 0) return;

    const origin = details.originUrl || details.documentUrl || "";
    if (!/^https?:\/\//i.test(origin)) return;

    if ((details.requestHeaders || []).some(header =>
        /^(cookie|authorization)$/i.test(header.name) && header.value)) return;

    const now = Date.now();
    const key = `${details.tabId}:${details.frameId}:${details.url}`;
    if (now - (tvbroReported.get(key) || 0) < 30000) return;

    tvbroReported.set(key, now);
    for (const [entry, when] of tvbroReported) {
        if (now - when > 60000) tvbroReported.delete(entry);
    }

    browser.tabs.sendMessage(details.tabId, {
        type: "nativeStreamDetected",
        url: details.url,
        origin: origin
    }, { frameId: details.frameId }).catch(() => {
        // A missing/navigating frame keeps its normal website playback.
        tvbroReported.delete(key);
    });
}, {
    urls: ["<all_urls>"],
    types: ["xmlhttprequest", "media", "other"]
}, ["requestHeaders"]);

const MEDIA_STREAM_URL = /\.(ts|m4s|m4v|m4a|aac|mp4|webm|mp3|m3u8|mpd)([?#]|$)/i;

browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        // Avoid an app round-trip for media segments.
        if (details.type === "media" || MEDIA_STREAM_URL.test(details.url)) {
            return {};
        }

        let id = details.requestId;
        let resolverRef = null;
        let promise = new Promise((resolve, reject) => {
            resolverRef = resolve;
            setTimeout(() => {
                if (requests.has(id)) {
                    requests.delete(id);
                    resolve({ cancel: false });
                }
            }, 1500);
        });

        requests.set(id, {
            details: details,
            time: new Date(),
            promise: promise,
            resolverRef: resolverRef
        });

        tvBroPort.postMessage({ action: "onBeforeRequest", details: details });
        return promise;
    },
    {
        urls: ["<all_urls>"],
        types: [
            "sub_frame", "script", "image", "imageset", "object",
            "xmlhttprequest", "ping", "beacon", "media", "websocket", "other"
        ]
    },
    ["blocking"]
);

// Replace the remote home page with the bundled one.
browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        if (details.type !== "main_frame") return {};

        const filter = browser.webRequest.filterResponseData(details.requestId);
        const encoder = new TextEncoder();
        const pageReady = fetch(browser.runtime.getURL("homepage.html"))
            .then(r => r.text());

        filter.ondata = () => {};
        filter.onstop = async () => {
            try {
                filter.write(encoder.encode(await pageReady));
            } finally {
                filter.close();
            }
        };

        return {};
    },
    { urls: ["https://tvbro.phlox.dev/appcontent/home/*"] },
    ["blocking"]
);

// Allow the bundled home page's inline script.
browser.webRequest.onHeadersReceived.addListener(
    function (details) {
        if (details.type !== "main_frame") return {};

        return {
            responseHeaders: details.responseHeaders.filter(
                h => h.name.toLowerCase() !== "content-security-policy" &&
                    h.name.toLowerCase() !== "content-security-policy-report-only"
            )
        };
    },
    { urls: ["https://tvbro.phlox.dev/appcontent/home/*"] },
    ["blocking", "responseHeaders"]
);
