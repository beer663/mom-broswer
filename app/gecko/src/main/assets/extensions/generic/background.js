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

// Capture the headers Gecko actually sent, including HttpOnly/partitioned cookies.
// The origin object is forwarded unchanged by content.js to the app's native port.
const TVBRO_PLAYLIST = /\.(m3u8|mpd)(?:[?#]|$)/i;
const TVBRO_PLAYLIST_FILTER = {
    urls: ["<all_urls>"],
    types: ["xmlhttprequest", "media", "other"]
};
const tvbroPendingPlaylists = new Map();
const tvbroReported = new Map();
const TVBRO_HEADER_NAMES = new Map([
    ["user-agent", "User-Agent"], ["referer", "Referer"],
    ["origin", "Origin"], ["accept", "Accept"],
    ["accept-language", "Accept-Language"], ["authorization", "Authorization"]
]);

async function tvbroRequestContext(details, pageUrl) {
    const headers = {};
    const cookieHeaders = [];
    let valid = true;
    for (const header of details.requestHeaders || []) {
        const lower = header.name.toLowerCase();
        if (lower === "cookie") cookieHeaders.push(header.value || "");
        const name = TVBRO_HEADER_NAMES.get(lower);
        if (name) {
            if (typeof header.value !== "string" || /[\r\n\0]/.test(header.value)) valid = false;
            else headers[name] = header.value;
        }
    }
    const context = {
        version: 1, url: details.url, pageUrl, headers,
        cookies: [], cookiesComplete: valid, capturedAt: Date.now()
    };
    const cookieHeader = cookieHeaders.join("; ");
    if (!cookieHeader) return context;
    if (!valid || cookieHeader.length > 16384 || /[\r\n\0]/.test(cookieHeader)) {
        context.cookiesComplete = false;
        return context;
    }
    let timer;
    try {
        const options = { url: details.url, firstPartyDomain: null, partitionKey: {} };
        if (details.cookieStoreId) options.storeId = details.cookieStoreId;
        const available = await Promise.race([
            browser.cookies.getAll(options),
            new Promise((_, reject) => { timer = setTimeout(() => reject(new Error("Cookie lookup timed out")), 2000); })
        ]);
        const host = new URL(details.url).hostname.toLowerCase();
        const path = new URL(details.url).pathname || "/";
        const pairs = cookieHeader.split(";").map(part => part.trim()).filter(Boolean);
        if (pairs.length > 128) throw new Error("Too many cookies");
        for (const pair of pairs) {
            const equal = pair.indexOf("=");
            if (equal <= 0) throw new Error("Invalid cookie");
            const name = pair.slice(0, equal);
            const value = pair.slice(equal + 1);
            const matches = available.filter(cookie => {
                const domain = cookie.domain.replace(/^\./, "").toLowerCase();
                const domainMatches = host === domain || !cookie.hostOnly && host.endsWith("." + domain);
                const cookiePath = cookie.path || "/";
                const pathMatches = path === cookiePath || path.startsWith(cookiePath) &&
                    (cookiePath.endsWith("/") || path[cookiePath.length] === "/");
                return cookie.name === name && cookie.value === value && domainMatches && pathMatches;
            }).sort((a, b) => b.path.length - a.path.length);
            // Without matching metadata, keep playback in Gecko rather than guess its scope.
            if (!matches.length) throw new Error("Cookie scope unavailable");
            const cookie = matches[0];
            const item = {
                name, value, path: cookie.path || "/", secure: matches.some(candidate => candidate.secure === true)
            };
            const expirations = matches.map(candidate => candidate.expirationDate).filter(Number.isFinite);
            if (expirations.length) item.expiresAt = Math.floor(Math.min(...expirations) * 1000);
            context.cookies.push(item);
        }
    } catch (_) {
        context.cookies = [];
        context.cookiesComplete = false;
    } finally {
        if (timer) clearTimeout(timer);
    }
    return context;
}

browser.webRequest.onBeforeSendHeaders.addListener(details => {
    // Each redirect hop can have different headers and a different destination.
    tvbroPendingPlaylists.delete(details.requestId);
    if (details.method !== "GET" || !TVBRO_PLAYLIST.test(details.url) ||
        details.tabId < 0 || details.frameId < 0) return;
    const origin = details.originUrl || details.documentUrl || "";
    if (!/^https?:\/\//i.test(origin)) return;

    tvbroPendingPlaylists.set(details.requestId, { details, origin });
    while (tvbroPendingPlaylists.size > 128) {
        tvbroPendingPlaylists.delete(tvbroPendingPlaylists.keys().next().value);
    }
}, TVBRO_PLAYLIST_FILTER, ["requestHeaders"]);

browser.webRequest.onErrorOccurred.addListener(details => {
    tvbroPendingPlaylists.delete(details.requestId);
}, TVBRO_PLAYLIST_FILTER);

browser.webRequest.onCompleted.addListener(details => {
    const captured = tvbroPendingPlaylists.get(details.requestId);
    tvbroPendingPlaylists.delete(details.requestId);
    if (!captured || captured.details.url !== details.url ||
        captured.details.tabId !== details.tabId || captured.details.frameId !== details.frameId) return;
    if (!(details.statusCode >= 200 && details.statusCode < 300) ||
        details.statusCode === 204 || details.statusCode === 205) return;

    const now = Date.now();
    const key = `${details.tabId}:${details.frameId}:${details.url}`;
    if (now - (tvbroReported.get(key) || 0) < 30000) return;
    tvbroReported.set(key, now);
    for (const [entry, when] of tvbroReported) {
        if (now - when > 60000) tvbroReported.delete(entry);
    }
    while (tvbroReported.size > 128) tvbroReported.delete(tvbroReported.keys().next().value);
    tvbroRequestContext(captured.details, captured.origin).then(context => {
        if (!context.cookiesComplete) {
            tvbroReported.delete(key);
            return;
        }
        return browser.tabs.sendMessage(details.tabId, {
            type: "nativeStreamDetected", url: details.url, origin: context
        }, { frameId: details.frameId });
    }).catch(() => { tvbroReported.delete(key); });
}, TVBRO_PLAYLIST_FILTER);

const MEDIA_STREAM_URL =
    /\.(ts|m4s|m4v|m4a|aac|mp4|webm|mp3|m3u8|mpd)([?#]|$)/i;

browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        if (details.type === "media" || MEDIA_STREAM_URL.test(details.url)) {
            return {};
        }

        let id = details.requestId;
        let resolverRef = null;

        let promise = new Promise(resolve => {
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

        tvBroPort.postMessage({
            action: "onBeforeRequest",
            details: details
        });

        return promise;
    },
    {
        urls: ["<all_urls>"],
        types: [
            "sub_frame", "script", "image", "imageset", "object",
            "xmlhttprequest", "ping", "beacon", "media",
            "websocket", "other"
        ]
    },
    ["blocking"]
);

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
