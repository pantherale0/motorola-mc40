# Hardware (MC40N0)

This repo targets **MC40N0-SCJ3R01** (Zebra, Android 5.1.1, API 22): Wi-Fi scanner, no cellular, no GPS, no NFC, no magstripe.

## Already used

| Hardware | Role |
|----------|------|
| SE4500 imager + DataWedge 6.7 | Grocery / QR scans. Intent `dev.pantherale0.mc40.SCAN`; keystroke fallback into a hidden field |
| Left / right scan triggers | Decode (DataWedge). Do not remap in the app |
| PTT (above left scan) | Event `mc40_button_pressed` (`button: ptt`) |
| Headset hook (2.5 mm) | Same event, `button: headset` if it reaches the app |
| Battery / charging | Diagnostic sensors |
| Wi-Fi | SSID, IP, RSSI |
| Proximity (ISL29030A) | `sensor.proximity`: `close` or `far` |
| Vibrator | Notify `vibrate` |
| Speaker | Notify `beep` and **TTS** (Pico `com.svox.pico`) |
| Notification LED + on-screen bar | Notify `led` |
| Touchscreen | XML UI sized for 480×800 |

## DataWedge

On launch the app creates profile **MC40HA** (`CREATE_IF_NOT_EXIST` / `SET_CONFIG`), associated with `dev.pantherale0.mc40`:

- Barcode input on
- Intent output: broadcast `dev.pantherale0.mc40.SCAN`
- Keystroke output is configured off in the profile; this unit may still inject keystrokes (hence the hidden capture field)
- Grocery symbologies: EAN-8/13, UPC-A/E, Code 128/39, QR, Data Matrix, PDF417

Do **not** use EMDK Barcode APIs. DataWedge `SET_CONFIG` is sequential with delays (the service does not queue APIs). `onResume` switches to the profile; it does not spam a full apply.

A Bluetooth **ring scanner** plugin is present on the device (`ringscanner_btssi_plugin`). If you pair one, DataWedge may feed the same scan path without app changes.

## Buttons (physical)

From the MC40 user/integrator guides, left side top-to-bottom is typically **PTT** then **left scan**. Right side is **volume** then **right scan**. Front capacitive **Home** / **Back** are used as launcher Home and overlay-dismiss.

Volume keys still control volume; they are not HA events.

## Proximity

Cover the sensor at the top of the device → `close`. Uncover → `far`. If your unit inverts that, say so and the threshold can be flipped.

## Not on this SKU / not used

| Item | Why |
|------|-----|
| GPS | `ro.config.device.gps=0` |
| NFC | Not in `pm list features` |
| Cellular / SIM | Wi-Fi only |
| Magstripe | `ro.symbol.hwconfig.msr=none` |
| Front camera | Rear only; imager is preferred for barcodes |
| RGB sysfs LEDs | Only `keyboard-backlight` / `lcd-backlight` in `/sys/class/leds` |
| EMDK Notification JNI | Avoided; beep/vibrate/LED use Android APIs |

Accelerometer and ambient light exist on the SoC but are not published to HA.
