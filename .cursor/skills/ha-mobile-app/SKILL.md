---
name: ha-mobile-app
description: Home Assistant mobile_app webhook and sensor contracts used by the MC40 companion. Use when changing registration, sensors, barcode events, or notify handling.
---

# Home Assistant mobile_app (MC40)

Auth is a long-lived access token, not OAuth.

1. `GET {url}/api/config` with `Authorization: Bearer {token}`.
2. `POST {url}/api/mobile_app/registrations` with `app_id=dev.pantherale0.mc40`, `supports_encryption=false`, `app_data.push_websocket_channel=true`.
3. Store `webhook_id` (and optional cloudhook/remote UI). All later POSTs go to `{url}/api/webhook/{webhook_id}`.

Scan path after registration:

- `register_sensor` then `update_sensor_states` for `last_barcode` (attributes: symbology, source, scanned_at).
- `fire_event` type `mc40_barcode_scanned`.

Keep `binary_sensor.scanner_ready` in sync with DataWedge profile status.

Home UI initialization is required. After local push subscribes, fire `mc40_boot` (`device_id`, `app_version`, `schema: 1`, `step`) every 10 seconds until the MC40 Configuration blueprint replies with notify `command: ui_config`. Schema 1 has `default` and 1–4 `slots` (`id`, `label`, `behavior: use|shopping`). Cache only in-process. `command: reinit` clears it and repeats the handshake. Gate grocery scans and overlays until ready.

Notify `data.command` values: `ui_config`, `reinit`, `overlay`, `set_mode`, `dismiss`, `feedback`, `beep`, `vibrate`, `led`, `tts`, `tts_stop`. `set_mode` accepts a configured slot ID. Feedback fields: `beep` (`ok`/`error`/`scan`), `vibrate` (ms), `led` (color name or `off`), `led_duration` (seconds). TTS: `tts_text`, optional `volume` / `stream` / `language`. Uses on-device Pico TTS.

The notify WebSocket is open while the screen is on. `SCREEN_OFF` disconnects it (10-minute diagnostic webhook updates continue). A scan or PTT briefly reconnects so overlay/feedback can arrive.

Sensor `proximity` state is `close` or `far`. `binary_sensor.tts_ready` is true after Pico initializes.
