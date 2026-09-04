# MC40 Home Assistant Companion

Native Android companion for the **Motorola/Zebra MC40N0** (Android 5.1.1, API 22). It registers with Home Assistant’s core `mobile_app` integration, exposes diagnostic sensors, and forwards hardware barcode scans.

## Constraints

- `minSdk` **22**, `targetSdk` **22**, `compileSdk` 33. Do not raise targetSdk.
- No Jetpack Compose, no Lovelace/WebView dashboards, no Play Services, no libsodium encryption.
- Barcode capture is **DataWedge 6.7 intents only** (`com.symbol.datawedge`). Never use EMDK Barcode APIs.
- Auth is a **long-lived access token** (paste or QR via DataWedge). No OAuth/IndieAuth.
- UI is XML, sized for 480×800 WVGA, 1 GB RAM. Keep the app lean.
- Pin AGP 7.4.2, Gradle 7.6.x, Kotlin 1.8.x, Java 8, OkHttp 4.x with TLS 1.2 enabled.

## Layout

- `app/` — Android application (`dev.pantherale0.mc40`)
- `homeassistant/blueprints/` — HA blueprint YAML (not auto-installed)
- `.agents/` — AG Kit (keep indexed; do not gitignore)
- `.cursor/rules/` and `.cursor/skills/` — Cursor-native guidance

## DataWedge

Profile is created at runtime with `SET_CONFIG` / `CREATE_IF_NOT_EXIST`. Intent action: `dev.pantherale0.mc40.SCAN`. Dual-mode receiver: setup fills URL/token; registered scans go to Home Assistant.

## Home Assistant

- Validate with `GET /api/config` (Bearer token).
- Register with `POST /api/mobile_app/registrations`.
- Sensors and events via webhook `/api/webhook/{webhook_id}`.
- Notifications via WebSocket `mobile_app/push_notification_channel` (`push_websocket_channel: true`). Overlay, form, list, search, toast, mode, dismiss, and feedback (`beep` / `vibrate` / `led`) are handled in-app.
