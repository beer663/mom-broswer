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

// HTML-only test: do not intercept playlist headers or dispatch Media3 takeover.
// HTML video requests continue to the website normally.

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
