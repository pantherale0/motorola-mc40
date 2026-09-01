# MC40 Home Assistant Companion

Native Android app for the **Motorola/Zebra MC40N0** (Android 5.1.1, 4.3" 480×800). It is a Home Assistant `mobile_app` scanner: pair with a long-lived token, scan barcodes, confirm quantities, and drive Grocy (or any pantry) from HA automations.

This device cannot render Lovelace. There is no OAuth, Play Services, or WebView dashboard.

## Features

- Pair with Home Assistant using URL + long-lived access token (paste or QR)
- Hardware imager via **DataWedge 6.7** (side scan triggers)
- **Use** / **Shopping** modes, overridable from HA
- Product overlay from `notify.mobile_app_*` (image, quantity, confirm)
- Events back to HA: scan, stock adjust, shopping add, PTT press
- Diagnostic sensors (battery, Wi-Fi, proximity, scanner mode)
- Beep / vibrate / LED / **TTS** feedback from notify
- Optional Home launcher, fullscreen (status bar hidden)

## Quick start

1. Build and sideload (see [docs/build.md](docs/build.md)), or install an existing debug APK over ADB.
2. In Home Assistant: Profile → **Long-lived access tokens** → create `MC40`.
3. On the device: enter `http://homeassistant.local:8123`, paste or scan the token, tap **Connect**.
4. Press **Home** and set **MC40 Companion** as the default launcher if you want kiosk mode.

```bash
export JAVA_HOME="$HOME/.local/jdk-17/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.pantherale0.mc40/.ui.MainActivity
```

## Documentation

| Doc | Contents |
|-----|----------|
| [User guide](docs/user-guide.md) | Pairing, on-device UI, modes, overlay, launcher |
| [Home Assistant](docs/home-assistant.md) | Sensors, events, notify commands, automations |
| [Hardware](docs/hardware.md) | Buttons, imager, proximity, LED, what this SKU lacks |
| [Build and ADB](docs/build.md) | JDK, Gradle, install, logcat |
| [Security](docs/security.md) | Token storage, URL rules, HA user advice |
| [Architecture](docs/architecture.md) | How scan → HA → overlay hangs together |

Blueprints: [`homeassistant/blueprints/`](homeassistant/blueprints/). Example automations: [`homeassistant/examples/overlay_after_scan.yaml`](homeassistant/examples/overlay_after_scan.yaml). Grocy mapping stays in Home Assistant, not in the APK.

## Typical grocery flow

```text
Scan (Use) → mc40_barcode_scanned
          → HA looks up Grocy
          → notify overlay (name, image, qty)
          → Confirm → mc40_stock_adjust
```

Shopping-mode scans fire `mc40_shopping_add` with quantity `1` immediately (and still `mc40_barcode_scanned`).
