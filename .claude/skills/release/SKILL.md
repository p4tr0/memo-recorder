---
name: release
description: Build an optimized release APK for sideloading onto the user's own phone. Use when the user asks for a release build, an installable APK, or a new version.
---

# Release (personal sideload, not Google Play)

This app is for the owner's own use. No AAB, Play signing, or store listing. Release builds are signed with the release key at `~/.android/voicememo-release.p12` (credentials in `~/.gradle/gradle.properties`, never in the repo); see `app/build.gradle.kts`. If the build fails with "No release key", stop and ask the user: never generate a new key, since updates signed with a different key can't install over the existing app.

1. Make sure the tree is clean and checks pass: `git status`, then `./gradlew spotlessCheck lintDebug testDebugUnitTest`
2. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. Use semver: patch for fixes, minor for features.
3. Build: `./gradlew assembleRelease`
4. Output: `app/build/outputs/apk/release/app-release.apk`. Report its size (`ls -lh`). If it grew by more than 20% since the last release, look into why.
5. Smoke test the R8 build on a device, since minification can break reflection-based code that debug builds don't exercise: `adb install -r --user 20 app/build/outputs/apk/release/app-release.apk` (the phone has several profiles; Voice Memo lives in Private, user 20), launch it, record a few seconds, and play it back (see the `run-on-device` skill).
6. Commit the version bump and tag it: `git commit -am "Release vX.Y.Z"` and `git tag vX.Y.Z`. Only when the user confirms.
7. If the user wants it on GitHub: `gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk#VoiceMemo-vX.Y.Z.apk --title "Voice Memo X.Y.Z" --notes "..."` after the tag is pushed (the user pushes; Claude can't).
8. Copy the APK somewhere the user picks if they want one, for example `~/Downloads/VoiceMemo-vX.Y.Z.apk`, to transfer to the phone.
