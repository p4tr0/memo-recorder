---
name: run-on-device
description: Build, install, and launch the app on a connected device or emulator, then capture a screenshot and the app's logcat. Use to confirm a change works in the real app, or to debug runtime behavior (recording, playback, permissions, notifications).
---

# Run on device

1. Check for a device: `adb devices`. If none is listed:
   - List emulators with `$ANDROID_HOME/emulator/emulator -list-avds` and start one in the background with `$ANDROID_HOME/emulator/emulator -avd <name> -no-snapshot-save`, then `adb wait-for-device` and poll until `adb shell getprop sys.boot_completed` returns 1.
   - If there are no AVDs, stop and ask the user to create one in Android Studio Device Manager or plug in a phone with USB debugging on.
2. Build and install: `./gradlew installDebug`
3. Clear old logs and launch:
   ```
   adb logcat -c
   adb shell am start -W -n io.github.p4tr0.voicememo/.MainActivity
   ```
4. Interact if needed:
   - Find elements: `adb shell uiautomator dump /sdcard/ui.xml && adb exec-out cat /sdcard/ui.xml`, then read `bounds` and `content-desc`
   - Tap: `adb shell input tap <x> <y>`
   - Grant permissions directly when testing past them: `adb shell pm grant io.github.p4tr0.voicememo android.permission.RECORD_AUDIO`
   - Reset permissions to test the prompt flow: `adb shell pm reset-permissions -p io.github.p4tr0.voicememo` (Android 14+) or clear app data with `adb shell pm clear io.github.p4tr0.voicememo`
5. Screenshot: `adb exec-out screencap -p > <scratchpad>/device.png` (your session scratchpad directory, not the repo), then Read the PNG.
6. Logs for this app only:
   ```
   adb logcat -d --pid=$(adb shell pidof io.github.p4tr0.voicememo) | tail -200
   ```
   For crashes, also run `adb logcat -d -b crash | tail -80`.
7. Recorded files live in app-private storage: `adb shell run-as io.github.p4tr0.voicememo ls -la files/`

Report what you saw on screen and any errors from logcat. Don't paste raw logs beyond the relevant lines.
