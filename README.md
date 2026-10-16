# SpeedBond (MVP)

One feature: **paste a file link, download it over Wi-Fi + mobile data at the
same time — roughly double the speed.**

Inspired by [Plexo](https://github.com/anmolkapil/plexo) (MIT), which does
multi-connection bonding on desktop. Plexo has no mobile version, so SpeedBond
owns the phone: it bonds the two connections a phone always carries.

## How it works

1. `NetworkBondManager` holds **both** networks at once via
   `ConnectivityManager.requestNetwork()` — one for `TRANSPORT_WIFI`, one for
   `TRANSPORT_CELLULAR`. Requesting cellular explicitly keeps mobile data alive
   while Wi-Fi is connected. Each network gets its own OkHttp client whose
   socket factory is pinned to that network (Android's equivalent of Plexo's
   `SO_BINDTODEVICE` trick — no root, no VPN).
2. `DownloadEngine` probes the URL with `Range: bytes=0-0`. If the server
   honors ranges, the file is split into 4 MB chunks downloaded in parallel
   (3 workers per network), each writing at its file offset. When the queue
   drains, idle workers **race** in-flight slow chunks — first finisher wins.
3. `DownloadService` runs it all as a foreground service (with live
   notification) so Doze can't kill long downloads.
4. One Compose screen: URL field, Wi-Fi / mobile-data toggles, live total +
   per-network speeds, progress, ETA.

If the server ignores range requests, the engine falls back to a single
connection automatically. If a network drops mid-download, its chunks requeue
onto the other one.

## Build

Gradle can't run in this dev VM, so compilation happens on CI (same pattern
as CricZen):

1. Push this repo to GitHub (new repo, e.g. `karanraj-ux/speedbond`).
2. The `build-apk` workflow compiles and uploads `app-debug.apk` as an
   artifact. Or open the project in Android Studio and Run.

Requires: JDK 17, Android SDK 34.

## Device test plan (the only test that matters)

Emulators can't do this — needs a real phone with Wi-Fi + mobile data.

1. Install the debug APK, grant notifications, tap "Allow background
   downloads".
2. Connect to Wi-Fi. Keep mobile data ON.
3. Download `https://speed.hetzner.de/1GB.bin` (public 1 GB test file).
4. Watch the per-network bars: both Wi-Fi and Mobile data should show
   throughput, and total should beat either connection alone.
5. Kill Wi-Fi mid-download → download should continue on mobile data alone.
6. Repeat with a server that ignores ranges (any plain HTTP file without
   `Accept-Ranges`) → should fall back to single connection, not crash.

## Permissions — why each one

- `INTERNET`, `ACCESS_NETWORK_STATE` — obvious.
- `CHANGE_NETWORK_STATE` — holding the cellular network alongside Wi-Fi.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` — downloads survive
  Doze via a data-sync foreground service with a persistent notification.
- `POST_NOTIFICATIONS` — progress notification (Android 13+).
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — optional whitelist prompt for
  long downloads.

## Deliberately out of scope for the MVP

Torrents, download queue, browser integration, scheduling, speed limits, data
allowances, pause/resume across restarts. One download, double speed, done.
