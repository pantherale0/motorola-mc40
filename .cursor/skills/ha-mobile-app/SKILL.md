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

Home UI initialization is required. After local push subscribes, fire `mc40_boot` (`device_id`, `app_version`, `schema: 3`, `step`) every 10 seconds until the MC40 Configuration blueprint replies with notify `command: ui_config`. Accept schema `1`–`3` (higher schemas clamp to max). Schema 1: slots only. Schema 2: + actions (`kind: event|search`). Schema 3: + pages/widgets (`text`, `button`, `nav`); top-level actions ignored when pages are present. Prefer flat `slot_*` / `page_*` / `widget_*` keys on device notify (nested lists often arrive empty). Persist the last valid config to prefs and restore it on process start (READY immediately); then soft-fire one `mc40_boot` `start` when notify subscribes so HA can refresh. `command: reinit` or unregister clears the cache and repeats the full handshake. Gate grocery scans and overlays until ready (cache or HA).

The configuration blueprint groups inputs into sections and optionally handles `mc40_search` via an **On search** action chain (must set variable `items`; blueprint sends `search_results`) and `mc40_list_show` via **On list show** (set `items` when non-empty; optional `filter` / `multiselect` / `confirm_label` / `buttons` variables — branch on mode/list_id; blueprint sends `list`), plus action sequences for home/page/button, form/list, and scan/inventory (`mc40_barcode_scanned`, `mc40_mode_confirm`).

Behaviors (`use` / `shopping` / `custom`) label slots; scans always fire `mc40_barcode_scanned`, overlay confirm always fires `mc40_mode_confirm` with slot ID as `mode`. Home action / page button `kind: event` → `mc40_home_action`; `kind: search` opens on-device search. Page `nav` switches locally; `set_page` notify also switches; both fire `mc40_page_changed`.

Notify `data.command` values: `ui_config`, `reinit`, `overlay`, `set_mode`, `set_page`, `dismiss`, `feedback`, `beep`, `vibrate`, `led`, `tts`, `tts_stop`, `toast`, `form`, `list`, `search`, `search_results`.

Runtime UI (gated until ready):

- `toast` — `message`/`text` (max 120), `level` (`info`/`ok`/`error`), `duration` (`short`/`long`); optional feedback fields.
- `form` — `id` required, `title`, up to 4 `fields` (`id`, `label`, `type: text|number|toggle|select|barcode`, `value`, `placeholder`, `options` for select), `confirm_label`/`cancel_label`, `timeout`. Barcode fields consume hardware scans (no grocery publish). Submit → `mc40_form_submit` (string `values`). Cancel/timeout → `mc40_form_cancel`.
- `list` / `picker` — `id` required, items optional. `filter` (default true), `multiselect` (long-press toggle + Confirm), up to 3 `buttons` → `mc40_list_action`. Open → `mc40_list_show`; select → `mc40_list_select` (`item_ids` / `items`).
- `search` / `search_results` — opening search also fires `mc40_list_show` (`list_id` = search id) so HA can pre-fill via On list show; query submit → `mc40_search`; blueprint may send `items` as JSON array string (`to_json`).
- `set_page` — `page` / `id` must match a configured page.
- `overlay` — optional `product_id` (alias `item_id`) stored on the card and echoed on confirm (`mc40_mode_confirm`); independent of `barcode`.

`dismiss` clears product overlay, form, list, and search. One modal at a time.

The notify WebSocket is open while the screen is on. `SCREEN_OFF` disconnects it (10-minute diagnostic webhook updates continue). A scan or PTT briefly reconnects so overlay/feedback can arrive.

Sensor `proximity` state is `close` or `far`. `binary_sensor.tts_ready` is true after Pico initializes.
