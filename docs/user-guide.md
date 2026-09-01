# User guide

The companion is a foreground scanner for the MC40N0. After pairing it stays on the Use/Shopping screen. Hardware side triggers scan; Home Assistant can pop a product card over that screen.

## Pairing

You need a Home Assistant URL and a **long-lived access token** (HA Profile → Long-lived access tokens). There is no OAuth.

1. Enter the instance URL, e.g. `http://homeassistant.local:8123` (no trailing slash required; the app trims it).
2. Paste the token, **or** tap **Scan token QR** and scan a QR from a laptop.
3. Tap **Connect**. A `mobile_app` device named **MC40N0** should appear in HA.

Accepted QR payloads:

| Format | Example |
|--------|---------|
| Raw JWT | `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...` |
| JSON | `{"url":"http://homeassistant.local:8123","token":"eyJ..."}` |
| Setup URL | `mc40ha://setup?url=http://homeassistant.local:8123&token=eyJ...` |

```bash
qrencode -t ANSIUTF8 -o - "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
qrencode -t ANSIUTF8 -o - '{"url":"http://homeassistant.local:8123","token":"eyJ..."}'
```

Until Connect succeeds, barcodes are treated as setup input, not grocery scans. **Change server** clears the HA webhook and returns to this screen; a saved token stays on device (leave the token field blank to reuse it).

HTTP (cleartext) to a LAN HA instance is allowed. The token is encrypted in app storage (AndroidKeyStore). Create a **dedicated HA user** for this device rather than using your owner account. See [Security](security.md).

## After connect

The main screen shows:

- Connection and scanner status
- **Use** / **Shopping** (selected state is highlighted; persisted across restarts)
- Last barcode and symbology
- **Scan** (soft trigger; hardware side buttons also work)
- **Change server**

The status bar is hidden (fullscreen). A thin **LED bar** at the top of the screen lights when HA sends an LED command.

## Modes

| Mode | Scan behaviour | Confirm on overlay |
|------|----------------|-------------------|
| **Use** | `mc40_barcode_scanned` with `mode: use`. HA typically looks up the product and sends an overlay. | `mc40_stock_adjust` (amount to consume) |
| **Shopping** | Same scan event, **and** immediate `mc40_shopping_add` with quantity `1`. | `mc40_shopping_add` |

HA can switch mode with notify `command: set_mode` (`mode: use` or `mode: shopping`). The device also exposes `sensor.scanner_mode`.

## Product overlay

When HA sends `command: overlay`, a modal card covers the scanner (dimmed background):

- Product name
- Image (~180dp; placeholder text if the URL fails)
- Editable quantity + unit (type on the keypad, or ±)
- **Confirm** / **Dismiss**
- Tap the dim area or Back to dismiss

After ± or Done on the quantity field, focus returns to the hidden scan-capture field so the imager still works. Optional `timeout` (seconds) auto-dismisses the card.

Quantity:

- `measure: count` — step defaults to `1`, unit defaults to `pcs`
- `measure: weight` — step defaults to `50`, unit defaults to `g`

## Home launcher

The app registers as an Android **Home** app. After install, press the hardware **Home** key, choose **MC40 Companion**, and **Always**.

- Home returns to this activity.
- Back dismisses the overlay if it is open; otherwise it stays in the app (does not leave to the stock launcher).

To restore the stock launcher: Settings → Home, or clear MC40 Companion’s defaults.

## Scanning notes

- Prefer the **side scan triggers**. The on-screen Scan button is a soft DataWedge trigger.
- DataWedge on this unit often keystroke-injects into the focused field. The app keeps a hidden capture field focused on the main screen for that reason.
- Token QR during setup needs that same imager; grocery scans only go to HA after Connect.

## Feedback from Home Assistant

Notify can beep, vibrate, light the LED bar, and **speak** (Pico TTS on the speaker). Overlay notifies may include `tts_text` so a lookup can announce the product. See [Home Assistant](home-assistant.md#notify-commands).
