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

Notify `data.command` values: `overlay`, `set_mode`, `dismiss`, `feedback`, `beep`, `vibrate`, `led`, `tts`, `tts_stop`. Feedback fields: `beep` (`ok`/`error`/`scan`), `vibrate` (ms), `led` (color name or `off`), `led_duration` (seconds). TTS: `tts_text`, optional `volume` / `stream` / `language`. Uses on-device Pico TTS.

Sensor `proximity` state is `close` or `far`. `binary_sensor.tts_ready` is true after Pico initializes.
