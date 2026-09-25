# Build and validation

Use the Gradle wrapper from the repository root. The source of truth is
[app/build.gradle.kts](../app/build.gradle.kts),
[build.gradle.kts](../build.gradle.kts),
[the version catalog](../gradle/libs.versions.toml), and
[Android CI](../.github/workflows/android.yml).

## Local setup

- Use JDK 21, matching CI. Java source/target compatibility is 17.
- Configure the Android SDK through `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir`. The project compiles against SDK 37, targets 36, supports API 23+, and selects NDK `28.2.13676358` in the root build file. CI installs `platforms;android-37.0`.
- Initialize the native source submodule with `git submodule update --init --recursive`.
- Normal debug builds target `arm64-v8a`. Use `-PjlmodRuntimeTestAbi=x86_64` when testing on an x86_64 emulator; the supported override values are `arm64-v8a` and `x86_64`.
- Debug builds use `debug.keystore` when present, otherwise the normal local debug signing configuration. Release signing is configured separately; do not copy credentials into documentation.

The examples use PowerShell. On POSIX shells, replace `./gradlew.bat` with `./gradlew`.

## Select the relevant check

| Scope | Command |
| --- | --- |
| Documentation | `git diff --check`, check local links, and compare claims with source/configuration |
| JVM unit tests | `./gradlew.bat :app:testEmulatorDebugUnitTest` |
| Library migration tests | `./gradlew.bat :app:testEmulatorDebugUnitTest --tests io.github.h3nb.jlmodplus.librarydb.LibraryMigrationTest` |
| Lint | `./gradlew.bat :app:lintEmulatorDebug :dexlib:lintDebug` |
| Compose screenshot comparison | `./gradlew.bat :app:validateEmulatorDebugScreenshotTest` |
| Debug APK | `./gradlew.bat :app:assembleEmulatorDebug` |
| Instrumentation APK compilation | `./gradlew.bat :app:assembleEmulatorDebugAndroidTest` |
| Instrumentation on a connected x86_64 emulator | `./gradlew.bat -PjlmodRuntimeTestAbi=x86_64 :app:connectedEmulatorDebugAndroidTest` |

For an arm64 device, omit the ABI override in the connected command. Assembling
the instrumentation APK does not run its tests. Use the smallest relevant
selection while iterating; full CI tasks are listed in the workflow.

## Testing strategy

The goal is useful regression confidence, not test count or coverage percentage.

- Add or strengthen a test when it protects behavior or an invariant that is important, easy to regress, difficult to verify manually, or costly to get wrong. A code change does not require a new test merely because code changed.
- Test at the lowest effective layer. Add a higher-layer test only when Android, process/lifecycle, filesystem, database/SQLite, OS locking, rendering/input, or another integration boundary introduces a failure mode the lower layer cannot exercise.
- Avoid repeating the same invariant at the same boundary. Prefer a smaller set of tests with distinct failure modes over large scenario matrices that provide equivalent coverage.
- Test observable contracts rather than private implementation structure. Reflection, fault injection, or implementation-specific hooks are appropriate when they are the practical way to reproduce otherwise unreachable crash-recovery, persistence, concurrency, lifecycle, or compatibility failures; keep such tests focused on the invariant being protected.
- Treat broad test rewrites during a behavior-preserving refactor as a coupling signal. If externally relevant contracts did not change, first check whether the tests are tied to replaceable internal structure.
- Use coverage as diagnostic evidence for unexercised code, not as a target. Do not add trivial assertions, one-test-per-class symmetry, or redundant cases solely to increase a metric.

## Existing test structure

- `app/src/test/`: JUnit tests, including Library migration files reconstructed from `app/schemas/` using bundled SQLite.
- `app/src/androidTest/`: AndroidJUnitRunner tests, including Compose interaction, file-picker intents, database, and runtime boundary checks on Android.
- `app/src/screenshotTest/`: Compose Preview Screenshot Testing cases. Committed references are under `app/src/screenshotTestEmulatorDebug/reference/`; inspect rendered images before accepting reference changes.

Android CI runs lint, unit tests, screenshot validation, and app/instrumentation
assembly. Markdown and `docs/**` changes are excluded from automatic PR runs.
Connected instrumentation and manual runtime checks are separate from that job.
For UI or runtime changes, use the affected checks in [UI ownership](ui-ownership-map.md)
and [Runtime UI](runtime-ui.md), including rendering geometry, key/touch dispatch,
Back, rotation, IME, and guest transitions where relevant.

These commands document the configured workflow; they are not evidence that a
particular checkout passed. Report commands actually run and any missing device,
SDK, or visual verification in the change handoff.
