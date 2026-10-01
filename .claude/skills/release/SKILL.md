---
name: release
description: Build an optimized release APK for sideloading onto the user's own phone. Use when the user asks for a release build, an installable APK, or a new version.
---

# Release (personal sideload, not Google Play)

This app is for the owner's own use. No AAB, Play signing, or store listing. Release builds are signed with the debug key (see `app/build.gradle.kts`), so a release APK installs over a debug install without uninstalling first.

1. Make sure the tree is clean and checks pass: `git status`, then `./gradlew spotlessCheck lintDebug testDebugUnitTest`
2. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. Use semver: patch for fixes, minor for features.
3. Build: `./gradlew assembleRelease`
4. Output: `app/build/outputs/apk/release/app-release.apk`. Report its size (`ls -lh`). If it grew by more than 20% since the last release, look into why.
5. Smoke test the R8 build on a device, since minification can break reflection-based code that debug builds don't exercise: `adb install -r app/build/outputs/apk/release/app-release.apk`, launch it, record a few seconds, and play it back (see the `run-on-device` skill).
6. Commit the version bump and tag it: `git commit -am "Release vX.Y.Z"` and `git tag vX.Y.Z`. Only when the user confirms.
7. Copy the APK somewhere the user picks if they want one, for example `~/Downloads/VoiceMemo-vX.Y.Z.apk`, to transfer to the phone.
