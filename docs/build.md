# Build and install

## Requirements

- **JDK 17** (Temurin is fine). AGP 7.4.2 does not run on newer default macOS JDKs without setting `JAVA_HOME`.
- Android SDK with `platforms;android-33` and `build-tools;33.0.2`
- `local.properties` with `sdk.dir` (do not commit secrets)
- MC40N0 on ADB (`adb devices`)

Pinned toolchain (do not bump for “latest AGP”):

| Piece | Version |
|-------|---------|
| AGP | 7.4.2 |
| Gradle | 7.6.x |
| Kotlin | 1.8.x |
| Java bytecode | 8 |
| `minSdk` / `targetSdk` | **22** |
| `compileSdk` | 33 |
| OkHttp | 4.x with TLS 1.2 enabled |

## Build

```bash
export JAVA_HOME="$HOME/.local/jdk-17/Contents/Home"   # or your JDK 17
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`  
Application id: `dev.pantherale0.mc40`

## GitHub Actions

- Pull requests run [Build](../.github/workflows/build.yml) (`assembleDebug`) and upload the APK as an artifact.
- Pushes to `master` (and manual **Run workflow**) run [Release](../.github/workflows/release.yml): signed `assembleRelease`, then a GitHub Release tagged `v1.0.<run_number>`.

`VERSION_CODE` / `VERSION_NAME` come from the workflow run number so each release can be installed over the previous one. Signing uses repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Keep a local backup of `keystore/` (gitignored). Local builds stay at `1.0.0` and use the debug keystore unless those env vars are set.

Download sideload APKs from [Releases](https://github.com/pantherale0/motorola-mc40/releases).

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.pantherale0.mc40/.ui.MainActivity
```

Confirm the device:

```bash
adb devices -l
adb shell getprop ro.build.version.release   # expect 5.1.1
adb shell pm list packages | grep datawedge
```

After install, press **Home** once if you want to set this app as the default launcher.

## Logs

```bash
adb logcat -s Mc40Ha:D DataWedge:D OkHttp:D
```

Useful lines: DataWedge profile apply, webhook failures, notify commands, `Hardware ptt`, unmapped hardware keys (`key=` / `scan=`).

## Re-pairing

On the device: **Change server** (clears webhook + token). Or uninstall/reinstall. HA may keep the old `mobile_app` device; you can remove it under Settings → Devices if you re-register.
