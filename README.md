# H6N Launcher

A from-scratch Android launcher for an 8.1 automotive head unit (1280 × 480, landscape).
Every pixel is drawn in Kotlin — the project contains **no XML layouts, no XML drawables
and no image-based UI kit**. Vehicle data is read from the head unit's own Binder service,
and the whole UI degrades gracefully to placeholder signals when that service is missing.

![platform](https://img.shields.io/badge/platform-Android%208.1-3DDC84)
![kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF)
![minSdk](https://img.shields.io/badge/minSdk-27-blue)

---

## What it does

| Area | Behaviour |
| --- | --- |
| Home screen | Speed gauge, gear, drive mode, coolant / transmission temperature, fuel range, 12 V battery, mileage — refreshed from a single signal pipeline |
| Car model | A vector-ish car drawn in code; doors and windows animate from live position data |
| Media card | Reads the active `MediaSession` through a `NotificationListenerService`; cover art, title/artist, playback keys |
| USB audio | Built-in player for USB sticks with a four-stage cover-art resolver (platform decoder → ID3 APIC → FLAC PICTURE → folder image) |
| Navigation | Parses the navigation app's broadcast: turn icon, lane guidance, ETA and a traffic-light countdown with rudimentary distance gating |
| Floating nav card | The same info as an overlay on top of other apps, using `TYPE_ACCESSIBILITY_OVERLAY` when an accessibility service is available and `TYPE_APPLICATION_OVERLAY` otherwise |
| Sidebar / dock | Covers the factory vertical bar, hosts a dock of user-chosen apps, swipeable pages |
| Gestures | Edge swipes with a progress indicator (back / home / notification shade) and a draggable floating ball |
| Steering wheel | Learns a single steering-wheel key and maps it to a launcher action |
| All-apps page | Third-party apps first, long-press for launch / pin to dock / uninstall / hide |
| Settings | Fully hand-drawn, live-applied configuration tree with JSON export/import |

## Repository layout

```
app/src/main/java/com/h6n/launcher/
├── H6NApplication.kt        process-wide wiring (no DI framework, on purpose)
├── core/                    vehicle bus, media, navigation, automation, storage, logging
├── data/                    configuration tree + repositories (DataStore backed)
├── domain/                  immutable signal / config models
└── ui/
    ├── common/              density scaling, custom widgets, shared paint helpers
    ├── main/                home screen, sidebar, dock, overlays, dialogs
    └── settings/            settings activity
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the signal pipeline, window layering
and UI conventions, and [`docs/BUILD.md`](docs/BUILD.md) for the build and signing flow.

## Build

Requirements: JDK 17, Android SDK 34, Gradle 8.7 (wrapper included).

```bash
# debug
./gradlew assembleDebug

# release (unsigned unless a keystore is supplied through env vars)
export H6N_KEYSTORE=/path/to/release.jks
export H6N_KEYSTORE_PASSWORD=...
export H6N_KEY_ALIAS=...
export H6N_KEY_PASSWORD=...
./gradlew assembleRelease
```

`local.properties` must point at your SDK (`sdk.dir=...`). Nothing else is required —
there is no API key, no analytics token and no private service in this repository.

## Configuration

The launcher ships with **placeholder endpoints** (`https://example.com/...`). Three
features expect a server of your own:

| Feature | File | What to change |
| --- | --- | --- |
| Online update | `core/UpdateManager.kt` (`DEF_URL`) or *Settings → Update* | URL of a `version.json` |
| In-app store | `core/AppStore.kt` (`DEF_URL`) or *Settings → Store* | Share-directory URL of an OpenList/AList instance |
| Online wallpaper | `core/WallpaperOnline.kt` (`BASE`, `SHARE_PATH`, `SD_PATH`) | Base URL and share path |

All three speak the same plain-HTTP conventions described in their KDoc headers, so any
static file host works for the update channel. Nothing is called unless the user opens
that screen.

## What is intentionally not in this repository

This is a source snapshot of a product, published for reading and learning. The
following parts are deliberately excluded:

* **Online services** — update server, app-store backend and wallpaper backend are reduced
  to placeholder URLs; no deployment, no tokens, no credentials.
* **Analytics / APM** — the third-party crash and analytics SDK and its build-time
  instrumentation plugin are removed, together with their Gradle wiring.
* **Activation and donation system** — the per-vehicle activation-code algorithm, the
  donation prompt and the community QR assets are not included. `StartStop.unlocked()`
  is stubbed to `true`.
* **Packaging tooling** — the DEX packer/shell used for the shipped binary, its keys and
  the signing material are not part of the source release.
* **Internal notes** — the per-version forensic logs that documented the original bring-up
  are not published.

Because of the above, the code here compiles and runs, but it is not the binary that is
distributed to end users.

## Notes on the target device

The launcher targets a specific head unit (GWM Haval H6N, Android 8.1, 1280 × 480). It is
installed as the `HOME` app and talks to the vendor's `ICarInfo` Binder service through a
small reflection-based client (`core/CarBinderSource.kt`). On any other device that service
does not exist and the signal layer falls back to `MockSource`/`SimSignals`, which is
enough to explore the UI.

Vehicle write paths (climate, windows, start-stop) are opt-in and off by default. Only
use them on hardware you own and understand.

## Disclaimer

This project is not affiliated with, endorsed by or supported by any vehicle manufacturer,
Tier-1 supplier or navigation vendor. Names of third-party apps appear only as launch
targets. Vehicle control is inherently risky: you are responsible for anything you send
to a real car.

## License / 开源协议

This project is licensed under the [MIT License](LICENSE).  
本项目基于 [MIT 协议](LICENSE) 开源，版权所有 © 2026 chhch00。
