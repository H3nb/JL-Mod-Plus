# Build and validation

Use the Gradle wrapper from the repository root. For current build, toolchain, dependency, and CI configuration, inspect
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
| App JVM unit tests | `./gradlew.bat :app:testEmulatorDebugUnitTest` |
| dexlib JVM unit tests | `./gradlew.bat :dexlib:testDebugUnitTest` |
| Library migration tests | `./gradlew.bat :app:testEmulatorDebugUnitTest --tests io.github.h3nb.jlmodplus.librarydb.LibraryMigrationTest` |
| Lint | `./gradlew.bat :app:lintEmulatorDebug :dexlib:lintDebug` |
| Compose screenshot comparison | `./gradlew.bat :app:validateEmulatorDebugScreenshotTest` |
| Generate candidate screenshot references | `./gradlew.bat :app:updateEmulatorDebugScreenshotTest` (inspect changes before accepting) |
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
- When a flow changes, review its affected tests even if they still pass: check that fixtures, assertions, and exercised production paths remain relevant. Retain stable contract tests, adapt changed contracts, and consolidate or remove redundant or obsolete tests. This does not require a repository-wide test audit for each edit.
- Testability changes must justify their production cost. Prefer existing boundaries and focused fixtures; do not add DI frameworks, interface/Default pairs, or production hooks merely to follow a testing recipe.

## Visual verification and screenshot references

- Use rendered previews to review appearance. Keep golden comparisons for selected, stable visual contracts where an unnoticed regression matters; a distinct screen or state alone does not require a permanent baseline. Choose cases with different failure modes rather than every size/theme/font combination.
- A screenshot mismatch requires diagnosis: distinguish an intended design change, a regression, and rendering-environment drift. Keep the renderer, dependencies, and environment consistent when generating and comparing references.
- Generate candidates with the screenshot task, inspect reference/actual/diff, then accept only changes justified by the authorized design. Existing authorization covers the corresponding baseline update after inspection; ask only about unresolved product decisions. A mismatch alone does not justify replacing the expected image.
- References must be actual renderer output. Scripts may transfer or package images unchanged; do not synthesize or edit expected pixels to make a comparison pass.
- For Back, scrolling, dismissal, and action reachability, use existing behavioral coverage or a targeted interaction/manual check. A static render is not proof of interaction correctness. Add automated coverage when an important failure mode is not already protected.
- Removing or reclassifying existing golden tests requires an explicit assessment of their remaining regression value; this guidance does not disable current CI checks.

## Existing test structure

- `app/src/test/`: JUnit tests, including Library migration files reconstructed from `app/schemas/` using bundled SQLite.
- `dexlib/src/test/`: JUnit tests for bytecode transformation and related dexlib contracts.
- `app/src/androidTest/`: AndroidJUnitRunner tests, including Compose interaction, file-picker intents, database, and runtime boundary checks on Android.
- `app/src/screenshotTest/`: Compose Preview Screenshot Testing cases. Committed references are under `app/src/screenshotTestEmulatorDebug/reference/`; inspect rendered images before accepting reference changes.

Android CI runs lint, app JVM unit tests, screenshot validation, and app/instrumentation
assembly. It currently does not invoke dexlib JVM tests or connected instrumentation.
Markdown and `docs/**` changes are excluded from automatic PR runs. See the workflow
for current tasks rather than assuming all source sets execute.
For UI or runtime changes, use the affected checks in [UI ownership](ui-ownership-map.md)
and [Runtime UI](runtime-ui.md), including rendering geometry, key/touch dispatch,
Back, rotation, IME, and guest transitions where relevant.

## Validation through CI

Use existing CI when the local JDK, Android SDK/NDK, or dependency access is unavailable.
Inspect the run for the relevant source revision and the specific task results;
a green workflow proves only the checks it executed. Report the run/commit,
checks passed, and any compiled-only, skipped, or unverified behavior.

The current Android CI supports manual dispatch and uploads diagnostic reports,
including screenshot reports, but has no dedicated baseline-update mode. Its
combined validation/assembly step can prevent the later APK upload after a
screenshot failure. Do not claim baseline generation or APK availability without
checking the run and artifacts.

Use actual renderer output from an available compatible local/CI run for baseline
updates. If rendering or inspection is unavailable, preserve the references,
complete other scoped work, and report the precise gap and next required check.
A reusable CI update path is a workflow change to implement when in scope, not
a temporary workflow to add repeatedly for individual UI edits. Missing checks
remain verification gaps; they do not authorize bypassing required merge checks.
