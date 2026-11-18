# Build

## Toolchain

| Component | Version |
| --- | --- |
| JDK | 17 |
| Gradle | 8.7 (wrapper included) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk | 34 |
| minSdk / targetSdk | 27 / 27 |

`compileSdk` is 34 because AndroidX 1.13 / DataStore 1.1 require it; `targetSdk` stays at
27 to match the head unit, so no "newer platform" behaviour changes on install.

## Local setup

```bash
git clone <this repo> h6n-launcher
cd h6n-launcher
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

If the project lives under a path with non-ASCII characters, `gradle.properties` already
sets `android.overridePathCheck=true`. If the build directory itself causes trouble on a
given machine, point it elsewhere:

```bash
H6N_BUILD_DIR=/tmp/h6n-build ./gradlew assembleRelease
```

## Release build and signing

The keystore is never stored in the repository. Supply it through environment variables:

```bash
export H6N_KEYSTORE=/absolute/path/release.jks
export H6N_KEYSTORE_PASSWORD=...
export H6N_KEY_ALIAS=...            # defaults to "release"
export H6N_KEY_PASSWORD=...
./gradlew assembleRelease
```

Without `H6N_KEYSTORE` the release build still runs and produces an **unsigned** APK, which
is useful for checking that R8 succeeds. `H6N_NO_SIGN=1` forces the unsigned path even when
a keystore is configured.

Signing scheme: v1 off, v2 and v3 on. Android 8.1 requires v2; v3 leaves room for key
rotation later.

## R8 notes

`isMinifyEnabled = true`, `isShrinkResources = false`, `android.enableR8.fullMode=false`.

* **`-keepnames com.h6n.launcher.**`** — class names are kept because components are
  referenced from the manifest, from `ComponentName`, and from the accessibility config,
  and because crash reports are read by hand.
* **`isShrinkResources` must stay off.** Lane-guidance icons are resolved at runtime with
  `getIdentifier("lane_pdf_$icon")`; resource shrinking cannot see those references and
  would delete them.
* **`fullMode` must stay off.** The code reflects into `SystemProperties`, `ServiceManager`,
  `MediaController`, `StorageVolume` and the vendor Binder interface. Full mode assumes no
  reflection and is allowed to merge or strip classes.
* A handful of classes are kept whole (`H6NApplication`, `ScaledActivity`,
  `NavOverlayService`, `H6A11yService`, `H6MediaListener`, `BootReceiver`) because the
  framework instantiates them by name.

Keep `app/build/outputs/mapping/release/mapping.txt` for every build you ship, if you plan
to read stack traces later.

## Manual verification

```bash
# version, package, sdk levels
aapt2 dump badging app-release.apk | head -5

# signature scheme and certificate
apksigner verify --print-certs -v app-release.apk

# 4-byte alignment (required for uncompressed .so on 8.1)
zipalign -c -p -v 4 app-release.apk
```

## Versioning

`versionCode` must increase with every build that is installed over an older one.
`versionName` is free-form. Both live in `app/build.gradle.kts`.
