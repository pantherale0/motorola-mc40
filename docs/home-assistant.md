# Home Assistant integration

The app uses core `mobile_app`. It does not talk to Grocy (or any other pantry API) directly. Map events in automations.

The notify target is typically `notify.mobile_app_mc40n0`. The suffix follows the device name shown in HA.

## Registration

1. `GET {url}/api/config` with `Authorization: Bearer <token>`
2. `POST {url}/api/mobile_app/registrations` with `app_id: dev.pantherale0.mc40`, `supports_encryption: false`, `app_data.push_websocket_channel: true`
3. Later traffic is `POST {url}/api/webhook/{webhook_id}` (`register_sensor`, `update_sensor_states`, `fire_event`)
4. Notifications arrive on WebSocket `mobile_app/push_notification_channel`

`mobile_app` must be loaded (`default_config:` is enough).

## Required home-screen configuration

Import [`mc40_configuration.yaml`](../homeassistant/blueprints/mc40_configuration.yaml), create one automation from it, and select the MC40 `mobile_app` device. Inputs are grouped into collapsible sections (Device, Mode slots, Pages, Widgets, Legacy actions, Search, Home & hardware, Forms & lists, Scan & inventory). The app intentionally stays on its welcome/progress screen until this automation returns at least one mode slot.

The device fires `mc40_boot` after its local-push WebSocket subscribes (advertising `schema: 3`). The blueprint responds with `command: ui_config` only for `step: start` or `step: timeout` (not `complete`). The last valid configuration is persisted on the device and restored on process restart so the home UI works offline from HA; a soft `mc40_boot` `start` still refreshes it when notify reconnects. `command: reinit` (or unregister) clears the cache and runs a full handshake again. Without a cache, the device retries the boot event every 10 seconds while waiting.

Up to four **mode slots** can be configured. Each has:

- **ID** — value sent as `event_data.mode` and exposed by `sensor.scanner_mode`
- **Label** — text shown on the home-screen button
- **Behavior** — `use`, `shopping`, or `custom` (schema 2)

| Behavior | On scan | Overlay confirm |
|----------|---------|-----------------|
| `use` | `mc40_barcode_scanned` | `mc40_mode_confirm` |
| `shopping` | `mc40_barcode_scanned` | `mc40_mode_confirm` |
| `custom` | `mc40_barcode_scanned` | `mc40_mode_confirm` |

Schema 2 also supports up to four **home actions** (id + label + kind). Kind `event` (default) fires `mc40_home_action`; kind `search` opens the on-device search UI.

Schema 3 adds up to three **pages** with per-page **widgets** (`text`, `button`, `nav`). Mode slots stay sticky above the current page. When pages are present, top-level actions are ignored. Navigate with `nav` widgets or notify `set_page`. Example: [`homeassistant/examples/pages.yaml`](../homeassistant/examples/pages.yaml).

The configuration blueprint can run an optional **On search** action chain on `mc40_search`. Those actions must set a variable named `items` (list of `{id, label, subtitle?}`); the blueprint then notifies `search_results`. **On list show** (`mc40_list_show`) works the same way for an open list when `items` is non-empty. It also accepts optional **action** sequences for home/page/button, form/list, and scan/inventory events (`mc40_barcode_scanned`, `mc40_mode_confirm`) — leave empty to ignore. Schema 1 payloads are still accepted (actions ignored; `custom` coerced to `use`).

## Sensors

Entities appear on the `mobile_app` device. Unique IDs:

| Unique ID | Type | State / notes |
|-----------|------|----------------|
| `battery_level` | sensor | 0–100 %, diagnostic |
| `battery_state` | sensor | `charging` / `full` / `discharging` / `not_charging` |
| `battery_temperature` | sensor | °C |
| `is_charging` | binary_sensor | Cradle or USB |
| `wifi_connection` | sensor | SSID or `not connected` |
| `wifi_ip_address` | sensor | IPv4 |
| `wifi_signal_strength` | sensor | dBm |
| `last_update_trigger` | sensor | e.g. `registration`, `periodic`, `screen_on`, `sleep`, `barcode_scan`, `service_start` |
| `last_reboot` | sensor | ISO-8601 of last boot |
| `last_barcode` | sensor | Payload; attributes `symbology`, `source`, `scanned_at` |
| `scanner_ready` | binary_sensor | DataWedge profile applied |
| `scanner_mode` | sensor | Configured mode slot ID, e.g. `use` or `shopping` |
| `proximity` | sensor | `close` or `far` (covers the top sensor) |
| `tts_ready` | binary_sensor | Pico TTS engine initialized |

Diagnostics update about every 60 seconds while the screen is on, and every 10 minutes while it is off (`last_update_trigger` is `sleep`). Proximity and mode update on change. Scans update `last_barcode` immediately.

## Events the device fires

### `mc40_boot`

Requests home-screen configuration after local push connects.

| Field | Example |
|-------|---------|
| `device_id` | `MC40N0` |
| `app_version` | `1.0.0` |
| `schema` | `3` (max schema the app supports) |
| `step` | `start` / `timeout` request config; `complete` acknowledges first successful apply (does not re-trigger the blueprint) |

### `mc40_barcode_scanned`

Every grocery scan (not setup QR).

| Field | Example |
|-------|---------|
| `barcode` | `"5012345678900"` |
| `symbology` | `"LABEL-TYPE-EAN13"` or `"keystroke"` |
| `source` | `"scanner"` / `"datawedge"` |
| `device_id` | `MC40N0` |
| `scanned_at` | ISO-8601 UTC |
| `mode` | Configured slot ID |

### `mc40_mode_confirm`

Confirm on the product overlay (any mode). Branch automations on `mode` (configured slot ID).

| Field | Example |
|-------|---------|
| `barcode` | `"200012570"` |
| `product_id` | `"42"` (optional; echoed from overlay when set) |
| `name` | `Plain flour` |
| `quantity` | `250` |
| `measure` | `weight` or `count` |
| `unit` | `g` |
| `mode` | Configured slot ID, e.g. `use` or `shopping` |
| `device_id` | `MC40N0` |
| `scanned_at` | ISO-8601 UTC |

### `mc40_home_action`

Tap a configured home action button with kind `event` (schema 2). Does not change mode.

| Field | Example |
|-------|---------|
| `action_id` | `lists` |
| `label` | `Lists` |
| `mode` | Current scanner mode |
| `device_id` | `MC40N0` |
| `pressed_at` | ISO-8601 UTC |

Example wiring: [`homeassistant/examples/home_actions.yaml`](../homeassistant/examples/home_actions.yaml).

### `mc40_search`

User submitted a query in the on-device search UI (home action kind `search`, or notify `command: search`).

| Field | Example |
|-------|---------|
| `search_id` | `products` |
| `query` | `flour` |
| `mode` | Current scanner mode |
| `device_id` | `MC40N0` |
| `searched_at` | ISO-8601 UTC |

The configuration blueprint can run an optional **On search** action chain on `mc40_search`. Those actions must set a variable named `items` (list of `{id, label, subtitle?}`); the blueprint then notifies `search_results` with `items: "{{ items | default([]) | to_json }}"`. Example script + how to wire it: [`homeassistant/examples/search_script.yaml`](../homeassistant/examples/search_script.yaml).

### `mc40_page_changed`

Fired when the user navigates via a `nav` widget or HA sends `set_page`.

| Field | Example |
|-------|---------|
| `page_id` | `lists` |
| `label` | `Lists` |
| `mode` | Current scanner mode |
| `device_id` | `MC40N0` |
| `changed_at` | ISO-8601 UTC |

### `mc40_button_pressed`

Hardware **PTT** (above the left scan trigger). Headset hook may arrive as `button: headset`.

| Field | Example |
|-------|---------|
| `button` | `ptt` |
| `keycode` | Android keycode |
| `scancode` | Linux scan code |
| `mode` | `use` or `shopping` |
| `device_id` | `MC40N0` |
| `pressed_at` | ISO-8601 UTC |

### `mc40_form_submit` / `mc40_form_cancel`

Fired when the user confirms or dismisses a notify `form` card.

| Field | Submit | Cancel |
|-------|--------|--------|
| `form_id` | yes | yes |
| `values` | map of field id → string | — |
| `reason` | — | `dismiss` or `timeout` |
| `mode` / `device_id` | yes | yes |

### `mc40_list_show`

Fired when a notify `list` / `picker` opens, or when the on-device **search** panel opens (home action kind `search` or notify `command: search`). A follow-up `list` / `search_results` notify with the same `id` refreshes items and does **not** fire again.

| Field | Example |
|-------|---------|
| `list_id` | `shopping` (or the search id) |
| `title` | `Shopping list` |
| `mode` | Current scanner mode |
| `device_id` | `MC40N0` |
| `shown_at` | ISO-8601 UTC |

The configuration blueprint **On list show** can set variable `items` (same shape as search); when non-empty it notifies `command: list` to fill the open picker (or search panel when the same id is open). Optionally set variables `filter`, `multiselect`, `confirm_label`, and `buttons` in that action chain (branch on `mode` / `list_id`) — defaults are filter on, multiselect off, confirm label `Confirm`, buttons `[]`.

### `mc40_list_select` / `mc40_list_cancel`

Fired when the user confirms a selection or dismisses a notify `list` picker (or search row).

| Field | Select | Cancel |
|-------|--------|--------|
| `list_id` | yes | yes |
| `item_id` / `label` | first selected | — |
| `item_ids` / `items` | all selected (`[{id,label},…]`) | — |
| `reason` | — | `dismiss` or `timeout` |
| `mode` / `device_id` | yes | yes |

### `mc40_list_action`

Custom list bottom button (`buttons` on the list notify). Includes any current multiselect selection.

| Field | Example |
|-------|---------|
| `list_id` | `pick_items` |
| `button_id` | `clear_cart` |
| `label` | `Clear cart` |
| `item_ids` / `items` | Current selection (may be empty) |
| `mode` / `device_id` | yes |

## Notify commands

HA sends `notify.mobile_app_mc40n0`. The app keys off **`data.command`** (the message string can be anything). Notifications with no recognised command show as a Toast.

The companion subscribes to `mobile_app/push_notification_channel` while the screen is on. There is no Firebase fallback. After initialization, **screen off closes the socket** so the radio can sleep; HA will then show the device as not connected to local push until the display wakes (or a scan/PTT briefly reopens the channel). During required first initialization, the socket remains connected until configuration arrives. Check logcat for `Notify websocket subscribed for local push`.

```yaml
action: notify.mobile_app_mc40n0
data:
  message: overlay
  data:
    command: overlay
    # fields…
```

### `overlay`

Show the product card.

| Field | Required | Default | Notes |
|-------|----------|---------|--------|
| `name` | no | barcode or `Product` | Title |
| `barcode` | no | last scanned | Sent back on confirm |
| `product_id` | no | — | Internal product key; alias `item_id`; echoed on confirm (independent of barcode) |
| `image_url` | no | — | HTTP(S); loaded with OkHttp + BitmapFactory |
| `measure` | no | `count` | `weight` or `count` (`mass` → weight) |
| `unit` | no | `pcs` / `g` | Shown next to quantity |
| `quantity` | no | `1` or step | Starting amount |
| `step` | no | `1` / `50` | ± increment |
| `timeout` | no | — | Seconds until auto-dismiss |
| `mode` | no | — | Switch Use/Shopping before showing |
| `beep` / `vibrate` / `led` | no | — | Same as feedback |

```yaml
action: notify.mobile_app_mc40n0
data:
  message: overlay
  data:
    command: overlay
    measure: weight
    name: Plain flour
    barcode: "200012570"
    product_id: "42"
    image_url: http://homeassistant.local:8123/local/flour.jpg
    unit: g
    quantity: 250
    step: 50
    timeout: 30
    beep: ok
    led: green
    led_duration: 2
```

### `toast`

Structured status toast (preferred over relying on unrecognized notifies).

| Field | Required | Notes |
|-------|----------|--------|
| `message` / `text` | yes | Max 120 characters |
| `level` | no | `info` (default), `ok`, `error` |
| `duration` | no | `short` (default) or `long` |
| `beep` / `led` / … | no | Same as feedback |

```yaml
action: notify.mobile_app_mc40n0
data:
  message: toast
  data:
    command: toast
    message: Lookup failed
    level: error
    duration: short
    beep: error
```

### `form`

Modal with up to four fields. Confirm fires `mc40_form_submit` (`values` is a string map); dismiss/timeout fires `mc40_form_cancel`.

| Field | Required | Notes |
|-------|----------|--------|
| `id` | yes | Correlation id (`form_id` in events) |
| `title` | no | Defaults to `id` |
| `fields` | yes | See field types below |
| `confirm_label` / `cancel_label` | no | Button text |
| `timeout` | no | Seconds until auto-cancel |

| Field `type` | Notes | Submit value |
|--------------|-------|--------------|
| `text` | `value?`, `placeholder?` | trimmed string |
| `number` | same | trimmed string |
| `toggle` | aliases `boolean` / `switch` / `checkbox`; `value` true/false | `"true"` / `"false"` |
| `select` | aliases `dropdown` / `choice`; requires `options` (up to 20 `{id,label}` or plain strings); empty options drop the field | selected option `id` |
| `barcode` | aliases `scan` / `code`; hardware scans fill the focused (or first) barcode field and are **not** published as grocery events while the form is open | scanned / typed string |

### `list` / `picker`

Scrollable picker with optional on-device filter. Opening fires `mc40_list_show` (once per new `id`). Single-select: tap a row → `mc40_list_select`. Multiselect: long-press toggles highlight → Confirm → `mc40_list_select` with all selected. Custom bottom buttons → `mc40_list_action`. Dismiss/timeout → `mc40_list_cancel`.

| Field | Required | Notes |
|-------|----------|--------|
| `id` | yes | Correlation id (`list_id` in events) |
| `title` | no | Defaults to `id` |
| `items` | no | Up to 40 `{ id, label, subtitle? }`; omit/empty to open then fill via `mc40_list_show` |
| `filter` | no | Default `true` — local filter EditText |
| `multiselect` | no | Default `false`. Aliases `multi_select` / `multi`. Long-press toggles; shows Confirm |
| `confirm_label` | no | Confirm button text when `multiselect` (default `Confirm`) |
| `buttons` | no | Up to 3 `{ id, label }` extras above Dismiss → `mc40_list_action` (JSON array string if device notify drops nested lists) |
| `timeout` | no | Seconds until auto-cancel |

```yaml
action: notify.mobile_app_mc40n0
data:
  message: list
  data:
    command: list
    id: pick_items
    title: Pick products
    multiselect: true
    confirm_label: Add selected
    buttons:
      - id: clear_cart
        label: Clear cart
    items: "{{ items | to_json }}"
```

A second `list` notify with the same `id` while the picker is open refreshes rows without another `mc40_list_show`. See events `mc40_list_select`, `mc40_list_action`, and `mc40_list_cancel` above.

Examples: [`homeassistant/examples/dynamic_ui.yaml`](../homeassistant/examples/dynamic_ui.yaml).

### `search` / `search_results`

`search` opens the on-device search panel. Submit fires `mc40_search`. `search_results` refreshes items when `id` matches the open panel (empty list allowed). Selecting a row fires `mc40_list_select` with `list_id` = `search_id`.

| Field | `search` | `search_results` |
|-------|----------|------------------|
| `id` | required | required (must match open search) |
| `title` | optional | optional refresh |
| `placeholder` | query hint | — |
| `query` | optional prefill | — |
| `items` | — | up to 40 `{ id, label, subtitle? }` (JSON array, or a JSON array **string** — needed for device notify) |

```yaml
action: notify.mobile_app_mc40n0
data:
  message: search_results
  data:
    command: search_results
    id: products
    items:
      - id: "200012570"
        label: Plain flour
        subtitle: 1 kg
```

The configuration blueprint sends `items: "{{ … | to_json }}"` because nested lists are dropped on mobile_app device notify. If a `list` notify arrives while a search with the same `id` is open, it is treated as `search_results`.

### `set_page`

Switch the active home page (schema 3). `page` / `id` must match a configured page.

```yaml
action: notify.mobile_app_mc40n0
data:
  message: set_page
  data:
    command: set_page
    page: lists
```

### `set_mode`

Set `mode` to any configured slot ID. The aliases `consume`, `shop`, and `list` map to `use`, `shopping`, and `shopping`.

### `ui_config` / `reinit`

`ui_config` schema 1–3 defines home-screen mode slots. Schema 2 may include home actions. Schema 3 may include pages and widgets. The configuration blueprint sends schema 3 with **flat** `slot_*` / `page_*` / `widget_*` / `action_*` keys because nested lists are unreliable on `mobile_app` device notify. Nested `slots` / `pages` / `actions` arrays are still accepted when present.

```yaml
message: ui_config
data:
  command: ui_config
  schema: 3
  default: use
  default_page: home
  slot_1_id: use
  slot_1_label: Use
  slot_1_behavior: use
  slot_2_id: shopping
  slot_2_label: Shopping
  slot_2_behavior: shopping
  page_1_id: home
  page_1_label: Home
  widget_1_page: home
  widget_1_type: text
  widget_1_id: hint
  widget_1_label: Scan a product barcode
  widget_2_page: home
  widget_2_type: button
  widget_2_id: products
  widget_2_label: Search
  widget_2_kind: search
```

Send `command: reinit` to discard the persisted configuration, return to the welcome screen, and request it again.

### `dismiss`

Hide the product card, form, list, or search panel.

### `feedback` / `beep` / `vibrate` / `led`

Haptic, tone, and LED. Can be combined on `command: feedback`, or sent as a dedicated command.

| Field | Values |
|-------|--------|
| `beep` | `ok`, `error`, `scan` (also `ack` / `nack` / `fail`) |
| `vibrate` | milliseconds (or `true` → 250 ms). Alias `haptic`. |
| `led` | `red`, `green`, `blue`, `amber`, `white`, `off`, or `#RRGGBB` |
| `led_duration` | seconds; default `3`; `0` stays on until `led: off` |

```yaml
action: notify.mobile_app_mc40n0
data:
  message: feedback
  data:
    command: feedback
    beep: error
    vibrate: 300
    led: red
    led_duration: 3
```

The LED lights a bar at the top of the app (useful with the status bar hidden). A silent notification with `FLAG_SHOW_LIGHTS` is also posted; on this hardware the physical LED often only shows with the screen off.

### `tts` / `speak`

Speaks through the MC40 speaker using the on-device **Pico TTS** engine (`com.svox.pico`). A new utterance replaces the previous one. Also accepted as `tts_text` on an overlay notify (announce the product while the card shows).

| Field | Values |
|-------|--------|
| `tts_text` | Text to speak (max ~400 characters). Aliases: `tts`, `speak`, or `text` when `command: tts` |
| `volume` | 0–1, or 0–100 |
| `stream` | `music` (default), `alarm`, `notification` |
| `language` | e.g. `en-US` (Pico languages only; falls back to US English) |

```yaml
action: notify.mobile_app_mc40n0
data:
  message: tts
  data:
    command: tts
    tts_text: "Plain flour, 250 grams"
    volume: 0.9
    stream: music
```

Official companion shape also works: `message: TTS` plus `data.tts_text`.

Stop speech: `command: tts_stop` (aliases `stop_tts`, `silence`).

## Suggested automations

Copy [`homeassistant/blueprints/`](../homeassistant/blueprints/) into HA `config/blueprints/automation/`.

**Required configuration:** import [`mc40_configuration.yaml`](../homeassistant/blueprints/mc40_configuration.yaml) and create one automation per MC40 device.

**Open Food Facts overlay:** import [`mc40_openfoodfacts_overlay.yaml`](../homeassistant/blueprints/mc40_openfoodfacts_overlay.yaml). Requires [ha-openfoodfacts](https://github.com/pantherale0/ha-openfoodfacts) (`open_food_facts.get_product`). Use-mode scans look up the barcode and send `command: overlay` with the product name, a small front image, and optional TTS. Confirm fires `mc40_mode_confirm` (map that to Grocy or another pantry using `mode`). Unknown barcodes open the overlay with the raw code, `beep: error`, and a red LED.

Stub YAML without a lookup: [`homeassistant/examples/overlay_after_scan.yaml`](../homeassistant/examples/overlay_after_scan.yaml).

Toast / form / list examples: [`homeassistant/examples/dynamic_ui.yaml`](../homeassistant/examples/dynamic_ui.yaml).

Home actions / custom mode: [`homeassistant/examples/home_actions.yaml`](../homeassistant/examples/home_actions.yaml).

Search (blueprint On search → set `items`): [`homeassistant/examples/search_script.yaml`](../homeassistant/examples/search_script.yaml).

Multi-page home: [`homeassistant/examples/pages.yaml`](../homeassistant/examples/pages.yaml).

**Use mode:** `mc40_barcode_scanned` (mode=use) → Open Food Facts (or Grocy) lookup → `notify` overlay → `mc40_mode_confirm` → pantry consume (branch on `mode`).

**Shopping mode:** `mc40_barcode_scanned` only. Show an overlay from HA if you want a quantity card; Confirm fires `mc40_mode_confirm` — map that however you like using `mode`.

**PTT:** `mc40_button_pressed` → whatever you want (cancel overlay, toggle a light, announce, open a list picker).

**Errors:** if lookup fails, notify `command: toast` or `command: feedback` with `beep: error` and `led: red` (the Open Food Facts blueprint does this on the overlay notify).

## Entity naming

HA prefixes unique IDs with the `mobile_app` device. You will typically see names like `sensor.mc40n0_last_barcode` and `sensor.mc40n0_proximity`. Confirm in Developer Tools → States.
