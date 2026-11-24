# Mom Browser

Mom Browser is a web browser for Android TV and Google TV, designed for use with a TV remote. It includes GeckoView browsing, tabs, bookmarks, browsing history, downloads, and an optional native HLS player.

## Download

Open **Releases** in this GitHub repository and download the `.apk` under **Assets**. The GeckoView build requires Android 8.0 or newer. The current build targets ARMv7-compatible devices.

## Ad blocking and DNS

- The built-in ad blocker uses the HaGeZi Ultimate filter list by default.
- GeckoView uses AdGuard DNS over HTTPS for DNS lookups and domain-level filtering.
- AdGuard DNS filtering stays active in GeckoView when you turn off the in-app ad blocker.


## What's new in v2.1.6

- Native HLS playback defaults to Off. The browser keeps your saved preference if you already set it.
- Improved compatibility with an embedded JW Player by allowing its entitlement request through the built-in ad blocker.
- Updated the bundled browser extension so the compatibility change is picked up when upgrading.

## Credits and license

Mom Browser uses source code from [TV Bro](https://github.com/truefedex/tv-bro) by Fedir Tsapana and contributors. See [LICENSE.md](LICENSE.md) for the redistribution terms.





