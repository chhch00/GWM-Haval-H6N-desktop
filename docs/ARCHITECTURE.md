# Architecture

## Design constraints

The target is a car head unit: Android 8.1, 1280 × 480 px, roughly 480 dp tall, always
landscape, driven by a rotary knob _and_ touch. Four constraints shape every file here:

1. **No XML UI.** Layouts, drawables and animations are built in Kotlin. The only XML is
   the manifest, a few vector drawables for app icons, the accessibility service config
   and `themes.xml` (window background / no action bar).
2. **No `setShadowLayer`, no `LAYER_TYPE_SOFTWARE`.** Both force software rendering on
   this GPU and visibly drop frames. "Shadows" are drawn as offset rounded rectangles
   with a gradient fill.
3. **480 dp of height.** Panels never grow vertically; they widen or scroll instead.
   Anything that would push the home screen past 480 dp is collapsed with `GONE`.
4. **Everything can be missing.** The vehicle bus, the navigation app, the media session,
   the USB stick — each one is optional. A missing source yields an empty signal frame,
   never an exception.

## Process wiring

`H6NApplication.onCreate()` is the single assembly point. There is no DI framework: the
dependency graph is small, and an annotation processor plus a runtime proxy costs more
memory than the forty lines it would replace.

```
H6Log.init            → file logger (2 MB × 3 rotating)
PerfStats             → uptime / frame stats shown in settings
ConfigRepository      → configuration tree (DataStore), defaults from assets/ui_config.json
SignalHub             → the one and only signal source
SimSignals            → optional override layer on top of the bus
SceneAnnouncer        → door / gear / ignition announcements
TireAlert, DoorAvm    → tyre pressure warnings, 360 camera on door open
AutoClose             → optional: close windows on ignition off
Storage, UpdateManager→ APK cache cleanup, update client
AmapNavi              → navigation broadcast receiver
FloatButton, EdgeGesture → automation windows
HangWatchdog          → main-thread stall detector
```

## Signal pipeline

```
CarBinderSource ──┐
MockSource      ──┼──▶ SignalHub (StateFlow<CarSignal>) ──▶ SceneStore (derived state)
SimSignals      ──┘                                   ├──▶ ViewModel → views
                                                       └──▶ SceneAnnouncer / TireAlert
```

`CarSignal` is an immutable data class of already-scaled values (km/h, kPa, °C, percent).
Scaling happens once, at the edge, inside `CarBinderSource`; nothing downstream deals with
raw CAN units. Unknown values are `null`, never a magic `-1`.

`CarBinderSource` owns a small reflective Binder client for the vendor's car-info service.
It polls at a configurable interval (default 200 ms) and falls back through a list of
access paths until one answers. If none answers, `SignalHub` keeps publishing the last
frame and marks the source as unavailable; the UI shows dashes.

`MockSource` generates a full, plausible frame when the bus is absent, so the entire UI can
be developed on a phone.

## Configuration tree

`ConfigTree` is a typed, path-addressed map (`"sidebar.coverW"`, `"media.coverRadius"`, …).
Defaults are compiled into the APK as `assets/ui_config.json`; user overrides live in
DataStore. `ConfigRepository` merges the two and publishes an immutable `ConfigSnapshot`.

* Read path: `snap.int("auto.floatSize", 112)`, `snap.bool(...)`, `snap.str(...)`.
* Write path: `ConfigRepository.set(path, value)` from the settings screen only.
* Export/import: the diff against defaults is serialised to JSON, so a backup only
  contains what the user actually changed.

Underscore-prefixed keys (`"_说明"`, `"_dockSlots"`) are comments for humans; the loader
skips them.

## UI layer

* `ScaledActivity` overrides `attachBaseContext` and rewrites `densityDpi` so that a
  single design grid (1280 × 480) maps onto the real screen. All sizes in the code are
  design pixels, converted with `dp()`.
* `MainActivity` builds a `PageHost` with two pages: the dashboard and the all-apps grid.
  Pages are `FrameLayout`s that are detached, not destroyed, when swiped away.
* Cards are plain `View` subclasses drawing into a `Canvas` in `onDraw`. Text is measured
  with `Paint.measureText` and never ellipsised: if it does not fit, the layout is wrong.
* Every list in the app is a hand-built `LinearLayout` inside a `ScrollView`.
  `AlertDialog.setItems` / `setAdapter` render as an empty list on this ROM, so they are
  banned outright.

## Window layering

Overlays need a window token before they can be attached:

| Layer | Type | When |
| --- | --- | --- |
| Preferred | `TYPE_ACCESSIBILITY_OVERLAY` (2032) | accessibility service connected — no extra permission, sits above the factory bar |
| Fallback | `TYPE_APPLICATION_OVERLAY` (2038) | only `SYSTEM_ALERT_WINDOW` granted |
| Last resort | in-activity `FrameLayout` | neither available; gestures degrade to an in-window strip |

`OverlayHost` owns the `WindowManager` instance for the accessibility layer, so overlay
windows are added and removed through it rather than through a `Context`.

A window belonging to the app itself still produces foreground events in the
accessibility service. Any "am I in the foreground?" check must therefore treat the
launcher's own package as "not switched away" — otherwise an overlay will detach itself
one frame after it appears.

## Automation

* `FloatButton` — a draggable ball (`112 dp` by default) that opens a quick panel.
* `EdgeGesture` — narrow strips at the screen edges. A drag draws a progress capsule;
  crossing the threshold turns it green and fires the action, then it fades out over
  200 ms. Horizontal swipes are Back, the bottom edge is Home, the left-edge downward
  swipe pulls the notification shade.
* `H6A11yService` — reports foreground package changes and, optionally, learns one
  steering-wheel key. It never reads window content.

## Media

`H6MediaListener` is a `NotificationListenerService` used purely as an identity: holding
notification access is the only non-signature way to call
`MediaSessionManager.getActiveSessions()`. The service does not override
`onNotificationPosted` and never inspects notification text.

`MediaWatcher` polls the active session for metadata (title / artist / art / duration),
`AudioRoute` decides how loud external audio is while the launcher speaks, and
`LocalPlayer` plays USB audio through `MediaPlayer`, feeding it a file descriptor
(`FileInputStream.fd`) so FUSE-backed sticks work.

`CoverArt` resolves artwork in four stages and caches the result — except for failures,
which are retried, because a USB stick that is still being indexed returns "no artwork"
for the first second or two.

## Navigation

`AmapNavi` registers one receiver for the navigation app's standard broadcast. Guidance
and cruise modes use different field names and different traffic-light encodings, so the
parser probes a small set of candidate keys instead of hard-coding one schema. A distance
gate suppresses the light widget when the light belongs to the next junction; the gate
only applies in guidance mode, because cruise mode never reports a remaining distance.

## Logging

`H6Log` mirrors `android.util.Log` but also appends to
`getExternalFilesDir("logs")/h6n.log` (2 MB, three files, rotating). It is always on:
diagnosing a head-unit problem without logcat is otherwise impossible. `LogExport` bundles
the log, the current signal frame and the configuration snapshot into one text file that
the user can copy to a USB stick.
