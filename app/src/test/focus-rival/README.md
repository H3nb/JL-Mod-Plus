# Optional physical audio-focus qualification

This companion is a separate UID and is never part of the JL-Mod Plus APK. It
requests transient or permanent focus from a short-lived `mediaPlayback`
foreground service while the qualification Activity stays resumed. Launching a
fullscreen competing Activity would instead exercise the host's `onPause`
suspension and could not prove an Android focus-loss callback.

Use the JDK and Android SDK described in `docs/development.md`. The PowerShell
build script reads `JAVA_HOME`/`ANDROID_HOME` (or `ANDROID_SDK_ROOT`), selects the
latest installed stable build-tools, and writes its APK, generated debug key,
and intermediate files to the temporary directory. Keep that debug key when
rebuilding an already-installed companion so its signature remains consistent.
Only the separate qualification package should be installed or updated.

```powershell
./app/src/test/focus-rival/build.ps1
adb install -r "$env:TEMP/jlmodplus-production-focus-rival/focus-rival-debug.apk"
./gradlew.bat -I scripts/audio-qualification.init.gradle :app:assembleEmulatorDebug :app:assembleEmulatorDebugAndroidTest
adb install -r app/build/outputs/apk/emulator/debug/app-emulator-arm64-v8a-debug.apk
adb install -r app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk
adb shell am instrument -w -r -e class io.github.h3nb.jlmodplus.mmapi.synth.SonivoxFocusRuntimeTest -e sonivoxFocusRival true io.github.h3nb.jlmodplus.audioqualification.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The two tests verify actual transient suspension, preserved guest `STARTED`,
held-note recovery alongside sampled PCM, cancellation of a stopped sampled
peer, and permanent-loss revocation of both sources. A fresh MIDI request must
leave the old sampled request suspended without another guest `STARTED` event.
They assert that the host remains resumed and that the rival's UID differs from
the owner. The rival abandons focus and stops itself within ten seconds even if
the instrumented test fails. Default tests skip these optional scenarios unless
`sonivoxFocusRival=true` is provided.

OEM audio policy can suppress focus-loss callbacks despite granting focus. The
tests do not change system settings. A separately authorized controlled run may
temporarily normalize a previously verified OEM setting, but must record its
exact prior value and restore and verify that value in `finally`. Do not add that
setting change or this foreground service to production code. A failure or skip
under an OEM policy must be reported separately from a completed focus test.
