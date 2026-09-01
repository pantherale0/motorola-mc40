---
name: adb-deploy
description: Build and sideload the MC40 companion APK over ADB. Use when installing, deploying, or logcatting on the Motorola MC40N0.
---

# ADB deploy (MC40)

The device is typically `MC40N0` / serial from `adb devices`.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.pantherale0.mc40/.ui.MainActivity
adb logcat -s Mc40Ha:D DataWedge:D OkHttp:D
```

Confirm OS with `adb shell getprop ro.build.version.release` (expect 5.1.1) and DataWedge with `adb shell pm list packages | grep datawedge`.
