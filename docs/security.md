# Security

This is a kiosk scanner on Android 5.1.1 with a **long-lived Home Assistant token**. The device should be treated as having the same power as that HA user.

## What the app does

- **Token, webhook id, and cloudhook** are encrypted at rest with AES; the AES key is wrapped by **AndroidKeyStore** RSA. Existing plaintext prefs are migrated on first read. If Keystore is broken on a given build, the app logs a warning and falls back to `MODE_PRIVATE` prefs.
- The token is **never shown** in the setup field after capture. Leave the field blank to keep the saved token. Setup uses `FLAG_SECURE` (no screenshots / recents preview of the token).
- HA URL must be `http://` or `https://` with no embedded credentials (`user:pass@host` is rejected).
- Overlay images must be `http`/`https` and are capped at 1 MB.
- Scan logs redact JWT-looking payloads (`eyJ…`).
- `allowBackup` is false. The companion service is not exported.

## What you should do in Home Assistant

1. Create a **dedicated HA user** for the MC40 (not your owner account). Long-lived tokens inherit that user’s rights.
2. Prefer **HTTPS** if the scanner leaves your LAN (Nabu Casa / VPN / TLS reverse proxy). Cleartext HTTP is allowed because `homeassistant.local` on LAN is the usual setup.
3. Treat a stolen MC40 like a stolen token: revoke the long-lived token and delete the `mobile_app` device.

## Residual risk (API 22 / DataWedge)

- DataWedge must send an exported broadcast (`dev.pantherale0.mc40.SCAN`). Another app on the device can spoof a scan extra. This unit is meant to run as the Home launcher with few other apps.
- HTTP to LAN HA is unencrypted on the Wi-Fi hop.
- Certificate pinning is not used (home TLS certs change). Default system trust + TLS 1.2 is used for HTTPS.
- Debug APKs are debuggable; don’t leave a debug build on a device that leaves the house if you can avoid it.
