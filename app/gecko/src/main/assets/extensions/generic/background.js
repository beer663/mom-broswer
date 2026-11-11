console.log("TV Bro background extension loaded");

const AD_CHECK_TIMEOUT_MS = 200;
const MAX_PENDING_CHECKS = 100;
const MAX_MEDIA_URLS = 512;
const MEDIA_URL_LIFETIME_MS = 60000;
const requests = new Map();
const mediaUrls = new Map();
let tvBroPort = null;

function finishRequest(id, block) {
    const key = String(id);
    const request = requests.get(key);
    if (!request) return;
    requests.delete(key);
    clearTimeout(request.timer);
    request.resolve({ cancel: block === true });
}

function connectPort() {
    if (tvBroPort) return tvBroPort;
    try {
        const port = browser.runtime.connectNative("tvbro_bg");
        tvBroPort = port;

        port.onMessage.addListener(response => {
            if (response && response.action === "onResolveRequest" && response.data) {
                finishRequest(response.data.requestId, response.data.block);
            }
        });

        port.onDisconnect.addListener(() => {
            if (tvBroPort !== port) return;
            tvBroPort = null;
            for (const id of Array.from(requests.keys())) {
                finishRequest(id, false);
            }
        });

        return port;
    } catch (_) {
        return null;
    }
}
connectPort();

const TVBRO_PLAYLIST = /\.(m3u8|mpd)(?:[?#]|$)/i;
const MEDIA_STREAM_URL =
    /\.(ts|m4s|m4v|m4a|aac|mp4|webm|mp3|m3u8|mpd)([?#]|$)/i;
const tvbroReported = new Map();

function isMediaMime(value) {
    const mime = String(value || "").split(";")[0].trim().toLowerCase();
    return mime.startsWith("video/") || mime.startsWith("audio/") ||
        [
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/dash+xml",
            "application/mp4"
        ].includes(mime);
}

function header(headers, name) {
    const item = (headers || []).find(h => h.name.toLowerCase() === name);
    return item ? item.value || "" : "";
}

function mediaKey(details) {
    return `${details.tabId}:${details.frameId}:${details.url}`;
}

function rememberMedia(details) {
    const key = mediaKey(details);
    mediaUrls.delete(key);
    mediaUrls.set(key, Date.now() + MEDIA_URL_LIFETIME_MS);

    while (mediaUrls.size > MAX_MEDIA_URLS) {
        mediaUrls.delete(mediaUrls.keys().next().value);
    }
}

function knownMedia(details) {
    if (details.type === "media" || MEDIA_STREAM_URL.test(details.url)) {
        return true;
    }
    if (header(details.requestHeaders, "accept").split(",").some(isMediaMime)) {
        return true;
    }

    const key = mediaKey(details);
    const expires = mediaUrls.get(key);
    if (expires && expires > Date.now()) return true;
    mediaUrls.delete(key);
    return false;
}

browser.webRequest.onHeadersReceived.addListener(details => {
    if (details.statusCode >= 200 && details.statusCode < 300 &&
        isMediaMime(header(details.responseHeaders, "content-type"))) {
        rememberMedia(details);
    }
}, {
    urls: ["<all_urls>"],
    types: ["xmlhttprequest", "media", "other"]
}, ["responseHeaders"]);

browser.tabs.onRemoved.addListener(tabId => {
    const prefix = `${tabId}:`;

    for (const key of mediaUrls.keys()) {
        if (key.startsWith(prefix)) mediaUrls.delete(key);
    }
    for (const key of tvbroReported.keys()) {
        if (key.startsWith(prefix)) tvbroReported.delete(key);
    }
    for (const [id, request] of requests) {
        if (request.tabId === tabId) finishRequest(id, false);
    }
});

browser.webRequest.onBeforeSendHeaders.addListener(details => {
    if (!TVBRO_PLAYLIST.test(details.url) ||
        details.tabId < 0 ||
        details.frameId < 0) return;

    const origin = details.originUrl || details.documentUrl || "";
    if (!/^https?:\/\//i.test(origin)) return;

    const now = Date.now();
    const key = mediaKey(details);

    if (now - (tvbroReported.get(key) || 0) < 30000) return;
    tvbroReported.set(key, now);

    for (const [entry, when] of tvbroReported) {
        if (now - when > 60000) tvbroReported.delete(entry);
    }

    browser.tabs.sendMessage(details.tabId, {
        type: "nativeStreamDetected",
        url: details.url,
        origin: origin
    }, { frameId: details.frameId }).catch(() => tvbroReported.delete(key));
}, {
    urls: ["<all_urls>"],
    types: ["xmlhttprequest", "media", "other"]
}, ["requestHeaders"]);

// Header inspection also recognizes media URLs that have no file extension.
// Unclassified requests still get an ad check, with a short, bounded wait.
browser.webRequest.onBeforeSendHeaders.addListener(details => {
    if (knownMedia(details)) return {};

    const port = connectPort();
    if (!port || requests.size >= MAX_PENDING_CHECKS) return {};

    const id = String(details.requestId);
    return new Promise(resolve => {
        const timer = setTimeout(
            () => finishRequest(id, false),
            AD_CHECK_TIMEOUT_MS
        );

        requests.set(id, {
            resolve,
            timer,
            tabId: details.tabId
        });

        try {
            port.postMessage({
                action: "onBeforeRequest",
                details: {
                    requestId: details.requestId,
                    url: details.url,
                    type: details.type,
                    originUrl: details.originUrl || details.documentUrl || "",
                    documentUrl: details.documentUrl || "",
                    tabId: details.tabId,
                    frameId: details.frameId
                }
            });
        } catch (_) {
            finishRequest(id, false);
        }
    });
}, {
    urls: ["<all_urls>"],
    types: [
        "sub_frame", "script", "image", "imageset", "object",
        "xmlhttprequest", "ping", "beacon", "media", "websocket", "other"
    ]
}, ["blocking", "requestHeaders"]);

browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        if (details.type !== "main_frame") return {};

        const filter =
            browser.webRequest.filterResponseData(details.requestId);
        const encoder = new TextEncoder();
        const pageReady = fetch(
            browser.runtime.getURL("homepage.html")
        ).then(r => r.text());

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
