# Build and validation

Use the Gradle wrapper from the repository root. For current build, toolchain, dependency, and CI configuration, inspect
[app/build.gradle.kts](../app/build.gradle.kts),
[build.gradle.kts](../build.gradle.kts),
[the version catalog](../gradle/libs.versions.toml), and
[Android CI](../.github/workflows/android.yml).

Commands, versions, workflow names, and artifact layouts below describe the
current repository and may evolve. Source configuration and workflows are
authoritative for those operational details; the testing and validation
principles in this document define how to choose proportionate evidence when
implementation changes.

## Local setup

- Use JDK 21, matching CI. Java source/target compatibility is 17.
- Configure the Android SDK through `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir`. The project compiles against SDK 37, targets 36, supports API 23+, and selects NDK `30.0.16248370` from the version catalog. CI installs platform 37 and provisions that NDK for native-enabled validation.
- Native synthesis sources are vendored; no native source submodule initialization is required. Sampled audio dependencies are built from pinned archives by the Gradle native tasks; install PowerShell 7, Git and the POSIX shell/make prerequisites in [the native audio recipe](../tools/audio/README.md). Compiled native dependency outputs are staged under `app/build/audio-deps`; pinned source working material is prepared under `app/build/audio-sources`. Gradle up-to-date checks and Build Cache govern compiled-output reuse; these directories are build state, not an independent compiled-output cache.
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

See [audio runtime qualification](audio-runtime.md) for isolated synthesis,
sampled-audio lifecycle, and whole-MIDlet checks.

## Localization inventory

Run the report-only [localization auditor](../scripts/localization_audit.py) from
the repository root with Python 3.10+ and no third-party dependencies:

```powershell
python -B scripts/localization_audit.py
python -B -m unittest discover -s scripts/tests -p test_localization_audit.py -v
```

The command prints a summary and writes deterministic schema-versioned
`build/reports/localization/inventory.json` and `inventory.md`. These generated
reports are ignored and must not be committed. `-B` avoids Python bytecode cache
files. The optional `--repo-root` selects another repository root; output remains
under that root's `build/` directory.

It reads repository-owned values XML without modifying resources. Missing
translations, fragmentation, and other findings still return success; unreadable
input, malformed XML, unsupported locale forms, and execution/usage errors return
non-zero. It performs no semantic review, repairs, AI calls, or CI enforcement.
See the [inventory scope and limitations](localization.md#deterministic-inventory)
before interpreting coverage or placeholder findings. Android builds are not
required for changes confined to this tool and its documentation/tests.

## Testing strategy

The goal is useful regression confidence, not test count or coverage percentage.

- Add or strengthen a test when it protects behavior or an invariant that is important, easy to regress, difficult to verify manually, or costly to get wrong. A code change does not require a new test merely because code changed.
- Test at the lowest effective layer. Add a higher-layer test only when Android, process/lifecycle, filesystem, database/SQLite, OS locking, rendering/input, or another integration boundary introduces a failure mode the lower layer cannot exercise.
- Avoid repeating the same invariant at the same boundary. Prefer a smaller set of tests with distinct failure modes over large scenario matrices that provide equivalent coverage.
- Test observable contracts rather than private implementation structure. Reflection, fault injection, or implementation-specific hooks are appropriate when they are the practical way to reproduce otherwise unreachable crash-recovery, persistence, concurrency, lifecycle, or compatibility failures; keep such tests focused on the invariant being protected.
- Treat broad test rewrites during a behavior-preserving refactor as a coupling signal. If externally relevant contracts did not change, first check whether the tests are tied to replaceable internal structure.
- When simplification removes an implementation mechanism or failure mode, consolidate or remove tests that only encode that obsolete structure while retaining coverage of the behavior and invariants that still matter.
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

The default Android CI mode runs lint, app and dexlib JVM unit tests, screenshot
validation, and app/instrumentation assembly. Connected instrumentation runs only
in the separate manual `runtime-smoke` mode described below. See the workflow for
current tasks rather than assuming all source sets execute.
Automatic PR runs exclude PRs whose entire diff consists of Markdown and `docs/**`.
A separate [Java ME graphics workflow](../.github/workflows/java-me-graphics.yml)
runs Nokia polygon, MIDP triangle and core Graphics color, primitive, clip,
lifecycle and bitmap-rebind instrumentation on API 23 and 36 when the
corresponding renderer, Image/DirectUtils bridge, tests, or workflow change. It
uses `jlmodNativeBuild=false`: these bitmap contracts do not exercise native
audio, game execution, or the final OpenGL presentation stage.
A documentation-only commit on a PR that also changes code can still trigger CI
and cancel an earlier PR run, because path filtering uses the whole PR diff.
For UI or runtime changes, use the affected checks in [UI ownership](ui-ownership-map.md)
and [Runtime UI](runtime-ui.md), including rendering geometry, key/touch dispatch,
Back, rotation, IME, and guest transitions where relevant.

## Validation through CI

Use existing CI when the local JDK, Android SDK/NDK, or dependency access is unavailable.
Inspect the run for the relevant source revision and the specific task results;
a green workflow proves only the checks it executed. Report the run/commit,
checks passed, and any compiled-only, skipped, or unverified behavior.

Android CI has three modes. Select the intended branch under **Run workflow**;
the run records the exact checked-out commit. Normal PR validation checks the
temporary merge commit with the base branch, so its build identity can differ
from the PR head. Manual dispatch checks the selected branch revision instead.

| Mode | Result |
| --- | --- |
| `validate` (default; automatic on PRs) | Parallel lint and build/test jobs, followed by the required `build` status; comparison failures also produce reviewable candidates from the same render |
| `update-screenshots` (manual) | Renderer-generated candidate references and a binary patch for review; does not validate or publish the app |
| `runtime-smoke` (manual) | Selected existing instrumentation tests on one fresh API 35 x86_64 emulator; does not run the full Android suite or replace normal PR validation |

Maintenance modes use distinct check names and concurrency groups. Generating
candidates or passing the runtime subset does not satisfy the PR `build` check.
Dispatch options require the workflow to be available through GitHub's default
branch; do not add a temporary push-triggered updater when dispatch is unavailable.

The debug APK artifact is uploaded immediately after successful assembly and
staging, before validation finishes. Its `BUILD-INFO.txt` records the source commit
and run URL and labels it unverified. The final `build` check requires `Build and tests`, `Screenshot validation`,
and `Lint` to succeed. `JL-Mod-Plus-ci-diagnostics` contains
`ci-artifacts/validation.txt`, app/dexlib test reports, screenshot reports, and
connected-test reports/logcat when run. `JL-Mod-Plus-lint-diagnostics` contains lint
reports. The validation file records its own job's step outcomes, not the separate
lint result; consult the final `build` check for overall status. The early APK
artifact is not rewritten. Artifact availability does not mean validation passed.
Inspect the individual reports when a combined step fails.

### Screenshot updates without a local Android toolchain

1. Diagnose the mismatch from the `validate` run's screenshot report and authorized UI change.
2. For supported comparison failures, the run automatically uploads `JL-Mod-Plus-screenshot-candidates`. It contains `before/`, `after/`, `diff/` where available, `changes.txt`, `source-commit.txt`, `source-context.json`, and `baselines.patch`. These are the original PNG bytes from validation; there is no second render and no automatic baseline acceptance.
3. Compare the images and confirm the recorded build/head/base commits match the source being reviewed. PR renders include the temporary merge with the base branch. If either branch has changed in a relevant way, regenerate before accepting the images.
4. From the repository root, use `git apply --check path/to/baselines.patch`, then apply the reviewed patch. If only some changes are justified, transfer only those renderer-produced files. Automatic candidates cover supported mismatches only, not obsolete-reference cleanup or every rendering failure.
5. For deliberate full regeneration or cases without automatic candidates, dispatch `mode=update-screenshots` on the intended branch. This runs the official update task and packages all references as `before/` and `after/`, with `changes.txt`, `source-commit.txt`, and a binary patch. Inspect added, renamed, and obsolete references. Skip patch application when the patch is empty.
6. Commit accepted references and run normal validation again. Candidate packaging/generation does not change the failed validation result. Never accept unrelated visual changes just to make CI green.

The automatic packager reads the pinned plugin's XML reference/actual/diff mapping,
accepts only recognized image-comparison failures, and excludes other failures.
Missing or invalid output remains a diagnostic problem, not an acceptable baseline.
Its temporary Git index leaves both committed references and the real index intact.
Review this mapping when updating the screenshot plugin. Scripts and GitHub tools
may transfer images unchanged; they must not redraw expected pixels. No patch is
automatically committed.

If rendering or inspection is unavailable, preserve the references, complete other
scoped work, and report the precise gap and next required check. Missing checks
remain verification gaps; they do not authorize bypassing required merge checks.

### GitHub-only reviewed screenshot promotion

Use the owner-only screenshot promotion path when the expected screenshot change
has been reviewed but the working environment cannot safely write or commit binary
PNG references, such as an agent that has GitHub PR/workflow access without a
local Android checkout. This is a convenience path, not a review bypass: promote
only renderer output that was already inspected and judged to be the intended
design.

For a failed normal `validate` run that produced
`JL-Mod-Plus-screenshot-candidates`:

1. Inspect the candidate artifact. Review `before/`, `after/`, and `diff/`
   for every candidate and confirm `changes.txt` and `source-context.json`
   describe the exact PR state being reviewed. Do not promote a candidate merely
   because screenshot comparison failed.
2. Collect the immutable provenance from that same reviewed state:
   - `head_sha`: the PR head SHA and the workflow run's PR head;
   - `base_sha`: the PR base SHA recorded for that reviewed state;
   - `build_sha`: `build_commit` from `source-context.json`, which must be
     the synthetic merge commit whose parents are `base_sha` then `head_sha`;
   - `run_id`: the Android CI workflow run ID;
   - `artifact_id`: the ID of the
     `JL-Mod-Plus-screenshot-candidates` artifact from that run;
   - `artifact_digest`: that artifact's GitHub SHA-256 digest, including the
     `sha256:` prefix.
3. Re-read the PR immediately before promotion. If its head or base moved after
   review, do not reuse the old request; obtain and review candidates for the new
   state.
4. As the repository owner, create one top-level PR Conversation comment with
   exactly this structure and no additional JSON keys:

```text
/jlmod-promote-screenshots
{"head_sha":"<40-hex PR head>","base_sha":"<40-hex PR base>","build_sha":"<40-hex reviewed merge commit>","run_id":123456789,"artifact_id":123456789,"artifact_digest":"sha256:<64-hex digest>"}
```

The first line must be exactly `/jlmod-promote-screenshots`. The JSON object must
contain exactly the six fields above. The promotion workflow accepts only an open
same-repository PR and only a comment authored by the repository owner.

The trusted workflow validates the PR head/base, Android CI run, artifact ID and
digest, synthetic merge parents, artifact `source-context.json`, candidate list,
and `before`/current-golden equality. It rejects artifacts containing
non-comparison screenshot failures. On success it writes only the reviewed
`after/` PNGs, verifies that no other paths are staged, commits them with the
provenance recorded in the commit message, pushes with a force-with-lease against
the exact reviewed HEAD, and explicitly dispatches final normal Android CI
validation. It does not re-render or synthesize the references during promotion.

If promotion is rejected, keep the guardrails intact. Refresh PR/run/artifact
state, identify which provenance or candidate assumption changed, review the
replacement evidence, and submit a fresh request. Do not weaken the workflow,
edit the artifact, guess missing identifiers, or bypass the final validation to
make a stale request succeed.

A local checkout may still use the reviewed `baselines.patch` route above. The
promotion path exists so GitHub-only agents can complete the same reviewed update
without needing direct filesystem access to binary screenshot references.

### Runtime smoke checks

Use `runtime-smoke` for relevant UI/rendering/database/IPC/lifecycle changes and
before a release involving those boundaries. The workflow selects existing tests
for Android SQLite, ambient bitmap/sampler semantics, crash-report interactions,
stale cross-process preset identity, and repeated remote crashes that must leave
the main process alive. Generated media checks also cover the guest cache bound,
legacy unknown duration, multi-audio video rejection, and
synthesis/sampled/video playback and seek on one output. Media checks use a
private temporary workdir and restore the prior preference; they require no
commercial assets or custom soundbanks. It uses the
emulator's default English locale, disables animations, and captures logcat before
the emulator shuts down. Tests share one fresh installation and run without sharding.

Keep this mode opt-in and use it when its selected runtime boundaries are relevant.
Record which selected tests actually ran; the rest of `androidTest`
remains unverified unless separately executed. Physical arm64/native behavior and
other Android versions still require appropriate device or targeted checks.

For full-emulator Recents/Exit shutdown, install both debug APKs on an isolated
emulator and run `./scripts/runtime-shutdown-smoke.ps1 -Serial emulator-5556`
(pass `-Adb` if it is not on PATH). This opt-in host check prepares a live MIDlet,
removes its task or requests emulator Exit, and verifies that all package processes
and services stop without restarting. A subsequent fixture invocation verifies
intentional session completion, no diagnostic report, and launcher return to
Library. A separate guarded cleanup runs even when setup or observation fails,
restoring the previous workdir only when the fixture owns a committed backup.
Failed restoration retains that backup for retry. The external observer is required
because successful emulator shutdown kills the main-process instrumentation too.
For an explicitly authorized physical-device check, use its ADB serial with
`-AllowPhysicalDevice`. The script targets the debug package and never clears app data.
Add `-IncludeDispatchFailure` to also exercise emergency shutdown from the MIDlet
process when the debug fixture denies dispatch to the main coordinator.
Run `./scripts/tests/runtime-shutdown-smoke-test.ps1` for device-free regressions
of setup/observer failure, cleanup ordering, and preservation of the original error.

### CI performance

CI reuses a Gradle daemon across steps in the same job and preserves supported
Gradle User Home and Build Cache state. Configuration Cache is enabled project-wide,
and CI keeps compatibility strict with `--configuration-cache-problems=fail`.
Lint runs independently with the normal native-enabled Android model so it validates
production-equivalent configuration. Screenshot-only validation may use
`-PjlmodNativeBuild=false` because it does not consume native outputs and can avoid
NDK provisioning without weakening app lint coverage.

Each invocation writes an HTML timing profile under
`build/reports/profile/`, included in diagnostic artifacts. Compare equivalent task
sets and cache conditions before attributing timing differences to an optimization;
measure time to APK separately from time to completed validation.

Do not run `clean` routinely, discard relevant checks to improve timings, or
suppress Configuration Cache problems as warnings.

### Release checks

The release workflow excludes documentation-only pushes to `alpha`, validates app
and dexlib JVM tests, and preserves diagnostic reports on failure. Publishing still
requires successful validation, release assembly, and signature verification.
PR debug validation does not exercise R8/release-only behavior; validate the release
variant before merging changes to shrinking, signing, release configuration, or
dependencies that can affect it. Manual release dispatch publishes an APK and is
not a substitute for a non-publishing release build check.

`jlmod.versionCode` remains source-controlled. The workflow checks that it is a
positive integer, not that it exceeds every previously published APK. Follow the
[versioning policy](agent-workflow.md#versioning) before publishing; a unique alpha
version name or tag does not establish monotonic version codes.
