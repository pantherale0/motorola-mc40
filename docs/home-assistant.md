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

Import [`mc40_configuration.yaml`](../homeassistant/blueprints/mc40_configuration.yaml), create one automation from it, and select the MC40 `mobile_app` device. The app intentionally stays on its welcome/progress screen until this automation returns at least one mode slot.

The device fires `mc40_boot` after its local-push WebSocket subscribes. The blueprint responds with `command: ui_config` only for `step: start` or `step: timeout` (not `complete`). The configuration is cached only for the life of the app process; an app restart or `command: reinit` requests it again. The device retries the boot event every 10 seconds while waiting.

Up to four slots can be configured. Each has:

- **ID** — value sent as `event_data.mode` and exposed by `sensor.scanner_mode`
- **Label** — text shown on the home-screen button
- **Behavior** — `use` or `shopping`; controls immediate shopping events and overlay confirmation

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
| `schema` | `1` |
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
| `mode` | `use` or `shopping` |

### `mc40_stock_adjust`

Confirm on the overlay while in **Use**. Quantity is the amount to consume/remove.

| Field | Example |
|-------|---------|
| `barcode` | `"200012570"` |
| `name` | `Plain flour` |
| `quantity` | `250` |
| `measure` | `weight` or `count` |
| `unit` | `g` |
| `mode` | Configured slot ID, e.g. `use` |
| `device_id` | `MC40N0` |
| `scanned_at` | ISO-8601 UTC |

### `mc40_shopping_add`

- Confirm on the overlay while in **Shopping**, or
- A scan while already in Shopping (quantity `1`, name often empty)

Same field shape as stock adjust; `mode` is the configured slot ID, e.g. `shopping`.

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
    image_url: http://homeassistant.local:8123/local/flour.jpg
    unit: g
    quantity: 250
    step: 50
    timeout: 30
    beep: ok
    led: green
    led_duration: 2
```

### `set_mode`

Set `mode` to any configured slot ID. The aliases `consume`, `shop`, and `list` map to `use`, `shopping`, and `shopping`.

### `ui_config` / `reinit`

`ui_config` schema 1 defines one to four home-screen mode slots. The configuration blueprint sends it automatically in response to `mc40_boot`.

```yaml
message: ui_config
data:
  command: ui_config
  schema: 1
  default: use
  slots:
    - id: use
      label: Use
      behavior: use
    - id: shopping
      label: Shopping
      behavior: shopping
```

Send `command: reinit` to discard the in-process configuration, return to the welcome screen, and request it again.

### `dismiss`

Hide the product card.

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

**Open Food Facts overlay:** import [`mc40_openfoodfacts_overlay.yaml`](../homeassistant/blueprints/mc40_openfoodfacts_overlay.yaml). Requires [ha-openfoodfacts](https://github.com/pantherale0/ha-openfoodfacts) (`open_food_facts.get_product`). Use-mode scans look up the barcode and send `command: overlay` with the product name, a small front image, and optional TTS. Confirm still fires `mc40_stock_adjust` (map that to Grocy or another pantry). Unknown barcodes open the overlay with the raw code, `beep: error`, and a red LED.

Stub YAML without a lookup: [`homeassistant/examples/overlay_after_scan.yaml`](../homeassistant/examples/overlay_after_scan.yaml).

**Use mode:** `mc40_barcode_scanned` (mode=use) → Open Food Facts (or Grocy) lookup → `notify` overlay → `mc40_stock_adjust` → pantry consume.

**Shopping mode:** device already fires `mc40_shopping_add` on scan. Optionally send an overlay afterward if you want a quantity edit before adding.

**PTT:** `mc40_button_pressed` → whatever you want (cancel overlay, toggle a light, announce).

**Errors:** if lookup fails, notify `command: feedback` with `beep: error` and `led: red` (the Open Food Facts blueprint does this on the overlay notify).

## Entity naming

HA prefixes unique IDs with the `mobile_app` device. You will typically see names like `sensor.mc40n0_last_barcode` and `sensor.mc40n0_proximity`. Confirm in Developer Tools → States.
