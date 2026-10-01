# Voice Memo

A simple, fast, stylish voice recorder and player for Android. **Personal use, sideloaded APK, not published on Google Play.** Don't add Play-specific work (AAB, Play signing, store metadata, Play policy compliance) unless asked.

## Stack

- Kotlin 2.4 with AGP 9 built-in Kotlin (there is no `org.jetbrains.kotlin.android` plugin; that's intentional), Jetpack Compose, Material 3
- Recording: `MediaRecorder` producing AAC in `.m4a`, inside a foreground service (`foregroundServiceType="microphone"`)
- Playback: Media3 ExoPlayer + `MediaSessionService`
- Storage: audio in app-private `filesDir`, metadata in Room (KSP)
- Single `:app` module, MVVM with `StateFlow`, manual DI (no Hilt unless the graph gets painful)
- minSdk 26, compileSdk/targetSdk 37. compileSdk 37 is required by current AndroidX releases.
- All versions live in `gradle/libs.versions.toml`. Use stable releases only.

## Commands

The Gradle daemon runs on JDK 21, which Gradle provisions itself (`gradle/gradle-daemon-jvm.properties`). The launcher still needs JDK 17+ to start, and the system `java` on this Mac is 1.8, so point `JAVA_HOME` at Android Studio's JBR (already set in `.claude/settings.local.json`):
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

| Task | Command |
|---|---|
| Build debug | `./gradlew assembleDebug` |
| Install on device | `./gradlew installDebug` |
| Format | `./gradlew spotlessApply` (the hook runs this automatically) |
| Lint | `./gradlew lintDebug` (warnings are errors) |
| Unit + screenshot tests | `./gradlew testDebugUnitTest` |
| Record screenshot goldens | `./gradlew recordRoborazziDebug` |
| Verify screenshots | `./gradlew verifyRoborazziDebug` |
| Release APK | `./gradlew assembleRelease` (see the `release` skill) |

## Layout

```
app/src/main/kotlin/com/ingeniumtc/voicememo/
  MainActivity.kt
  ui/theme/          Color.kt, Theme.kt (RecordRed stays fixed under dynamic color)
  ui/<feature>/      one package per screen: Screen composable, ViewModel, UI state
  recording/         recorder + foreground service (planned)
  playback/          player + media session service (planned)
  data/              Room DB, DAO, repository (planned)
app/src/test/        Robolectric + Roborazzi tests; goldens in app/src/test/screenshots/
```

## Conventions

- Screens take state and lambdas, and ViewModels own logic. Screen composables stay previewable without a ViewModel.
- Every screen gets Roborazzi screenshot tests in light and dark with `dynamicColor = false`. Agents check visuals by reading those PNGs.
- No hardcoded colors or strings in composables.
- No main-thread I/O. File, Room, and MediaRecorder calls run on `Dispatchers.IO` or a dedicated thread.
- Add libraries via the version catalog only.

## Definition of done

1. `spotlessCheck`, `lintDebug`, and `testDebugUnitTest` pass (use the `verifier` agent)
2. Screenshot goldens are updated and inspected for UI changes
3. `android-reviewer` has reviewed non-trivial changes and its findings are fixed
4. Recording or playback changes are checked on a real device or emulator (`run-on-device` skill)

## Gotchas (learned the hard way)

- **Don't run Gradle builds on Android Studio's JBR 17.0.6.** It has an aarch64 C1 JIT bug ("Field too big for insn") that crashes the daemon during lint. The daemon JVM pin to 21 prevents this. Keep it.
- **Robolectric needs Java 21** for SDK 35+ sandboxes. The test task uses a Gradle-provisioned JDK 21 toolchain (foojay resolver) plus `--add-opens`/`--add-exports` JVM args. Don't remove them.
- **`mipmap-anydpi-v26` must stay versioned.** AAPT auto-versions `<adaptive-icon>` into `-v26` during merging, so an unversioned folder breaks the build. `app/lint.xml` suppresses the resulting ObsoleteSdkInt warning.
- **Release builds are signed with the debug key** on purpose, because the app is sideloaded. A release APK installs over a debug install.
- Android Studio on this machine is an old version (2022.3 "Giraffe") and can't sync AGP 9 projects. The command-line build works. Update Studio before opening the project in the IDE.
