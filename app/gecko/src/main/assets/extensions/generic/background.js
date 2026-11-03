
console.log("TV Bro background extension loaded");

let requests = new Map();
let tvBroPort = browser.runtime.connectNative("tvbro_bg");

tvBroPort.onMessage.addListener(response => {
    //console.log("Received: " + JSON.stringify(response));
    if (response.action === "onResolveRequest") {
        let id = response.data.requestId;
        let block = response.data.block;
        //console.log("Requests contents: " + JSON.stringify(Array.from(requests.keys())));
        if (requests.has(id.toString())) {
            let request = requests.get(id.toString());
            //console.log("Request resolved id: " + id);
            requests.delete(id.toString());
            //console.log("Requests size: " + requests.size);
            request.resolverRef({ cancel: block });
        }
    }
});
//tvBroPort.postMessage("Hello from WebExtension!");

browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        let id = details.requestId;
        let resolverRef = null;
        let promise = new Promise((resolve, reject) => {
            resolverRef = resolve;
            setTimeout(() => {
                if (requests.has(id)) {
                    let request = requests.get(id);
                    let time = new Date() - request.time;
                    //console.log("Request block timeout id: " + id);
                    requests.delete(id);
                    resolve({ cancel: false });
                } else {
                    //console.log("Request processed by blocking rules id: " + id);
                }
            }, 1500);
        });
        requests.set(id, {
            details: details,
            time: new Date(),
            promise: promise,
            resolverRef: resolverRef
        });

        //console.log('onBeforeRequest url: ' + details.url);
        tvBroPort.postMessage({ action: "onBeforeRequest", details: details });
        return promise;
    },
    { urls: ["<all_urls>"] },
    ["blocking"]
);

// Replace the remote TV Bro home page with the bundled one (homepage.html).
// NOTE: homepage.html must be kept identical to app/src/main/assets/pages/home/index.html
browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        if (details.type !== "main_frame") return {};
        const filter = browser.webRequest.filterResponseData(details.requestId);
        const encoder = new TextEncoder();
        const pageReady = fetch(browser.runtime.getURL("homepage.html")).then(r => r.text());
        filter.ondata = () => { /* drop the remote content */ };
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


// The remote page may send a Content-Security-Policy that would block the bundled page's inline script.
browser.webRequest.onHeadersReceived.addListener(
    function (details) {
        if (details.type !== "main_frame") return {};
        return {
            responseHeaders: details.responseHeaders.filter(
                h => h.name.toLowerCase() !== "content-security-policy" &&
                     h.name.toLowerCase() !== "content-security-policy-report-only")
        };
    },
    { urls: ["https://tvbro.phlox.dev/appcontent/home/*"] },
    ["blocking", "responseHeaders"]
);
