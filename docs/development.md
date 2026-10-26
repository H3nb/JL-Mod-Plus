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
| Compose screenshot comparison | `./gradlew.bat :app:testEmulatorDebugScreenshotTestDefaultTestSuite` |
| Generate candidate screenshot references | `./gradlew.bat :app:updateEmulatorDebugScreenshotTestDefaultTestSuite` (inspect changes before accepting) |
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
python -B -m unittest discover -s scripts/tests -p 'test_localization_*.py' -v
```

The auditor prints a summary and writes deterministic schema-versioned
`build/reports/localization/inventory.json` and `inventory.md`. These generated
reports are ignored and must not be committed. `-B` avoids Python bytecode cache
files. The optional `--repo-root` selects another repository root; output remains
under that root's `build/` directory.

It reads repository-owned values XML without modifying resources. Missing
translations, fragmentation, and other findings still return success; unreadable
input, malformed XML, unsupported locale forms, and execution/usage errors return
non-zero. It performs no semantic review, repairs, translation, or CI enforcement.
See the [inventory scope and limitations](localization.md#deterministic-inventory)
before interpreting coverage or placeholder findings. Android builds are not
required for changes confined to this tool and its documentation/tests.

### Translation matrix export

Generate a read-only side-by-side JSON workspace from the existing inventory:

```powershell
python -B -m scripts.localization_matrix --prefix memory_editor_ --locale id --locale ru
```

The default output is `build/reports/localization/matrix.json` (ignored by
Git). The exporter uses the inventory parser, does not modify Android resource
files, and does not attest linguistic quality. The shared localization test
command above covers the inventory, matrix exporter, and batch applier.
See the [localization workflow](localization-workflow.md) for grouped key
selection, plural preservation, and applying reviewed translations.

## Testing strategy

[Testing strategy and quality standards](testing.md) is the owning policy for test selection, quality, retention, efficiency, and Android/Java ME-specific verification. This document owns the concrete commands and CI/reporting workflow below.

## Visual verification and screenshot references

For deciding which previews deserve golden references and evaluating visual mismatches, follow the [visual verification policy](testing.md#visual-verification-and-screenshot-references). For renderer-produced screenshot candidates and update commands, use the operational instructions on this page. Do not interpret a screenshot match as proof of interaction or device rendering.

## Existing test structure

- `app/src/test/`: JUnit tests, including Library migration files reconstructed from `app/schemas/` using bundled SQLite.
- `dexlib/src/test/`: JUnit tests for bytecode transformation and related dexlib contracts.
- `app/src/androidTest/`: AndroidJUnitRunner tests, including Compose interaction, file-picker intents, database, and runtime boundary checks on Android.
- `app/src/screenshotTest/`: Compose Preview Screenshot Testing cases. Committed references are under `app/src/screenshotTestDefaultEmulatorDebug/reference/`; inspect rendered images before accepting reference changes.

The default Android CI mode runs lint, app and dexlib JVM unit tests, screenshot
validation, and app/instrumentation assembly. The broader connected runtime suite runs
in the separate manual `runtime-smoke` mode described below. See the workflow for
current tasks rather than assuming all source sets execute.
Automatic PR runs exclude PRs whose entire diff consists of Markdown and `docs/**`.
The `LibraryUiContractSuite` remains a **targeted, opt-in** Android instrumentation
suite for pager gestures, viewport/workdir isolation, Collection actions and icon
refresh. Its former PR-triggered emulator workflow has been removed to avoid
recurring emulator builds. When these boundaries change, run the suite on a
disposable API 35 x86_64 emulator with animations enabled:

```sh
./gradlew --daemon --stacktrace -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=io.github.h3nb.jlmodplus.applist.LibraryUiContractSuite :app:connectedEmulatorDebugAndroidTest
```

Review the executed test reports; compilation by default Android CI does not
substitute for the connected run. This suite validates host UI behavior, not
native game execution or physical-device frame pacing.
Java ME LCDUI and graphics instrumentation suites remain in
`app/src/androidTest/` for targeted validation. Use a temporary PR-scoped
emulator workflow on API levels relevant to a compatibility change, inspect
the executed test reports, and remove that workflow before merging. Keep
Android CI and Release as the permanent CI/publishing workflows.
Bitmap contracts can use `jlmodNativeBuild=false`: they do not exercise
native audio, game execution, or final OpenGL presentation.
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

Android CI has four modes. Select the intended branch under **Run workflow**;
the run records the exact checked-out commit. Normal PR validation checks the
temporary merge commit with the base branch, so its build identity can differ
from the PR head. Manual dispatch checks the selected branch revision instead.

| Mode | Result |
| --- | --- |
| `validate` (default; automatic on PRs) | Parallel lint and build/test jobs, followed by the required `build` status; comparison failures also produce reviewable candidates from the same render |
| `update-screenshots` (manual) | Select the open PR branch and supply `pr_number`; packages changed references with a versioned manifest and binary patch for review |
| `runtime-smoke` (manual) | Selected existing instrumentation tests on one fresh API 35 x86_64 emulator; does not run the full Android suite or replace normal PR validation |
| `release-check` (manual) | Builds unsigned release APKs and checks packaging/startup on a 16 KB emulator; does not publish a release |

Maintenance modes use distinct check names and concurrency groups. Generating
candidates or passing the runtime subset does not satisfy the PR `build` check.
Dispatch options require the workflow to be available through GitHub's default
branch; do not add a temporary push-triggered updater when dispatch is unavailable.

The debug APK artifact is uploaded immediately after successful assembly and
staging, before validation finishes. Its `BUILD-INFO.txt` records the source commit
and run URL and labels it unverified. The final `build` check requires `Build and tests`, `Screenshot validation`,
and `Lint` to succeed. `JL-Mod-Plus-ci-diagnostics` contains
`ci-artifacts/validation.txt`, `unit-results.json`/`.md`, app/dexlib test reports, and
connected-test reports/logcat when run. `JL-Mod-Plus-lint-diagnostics` contains lint
reports and `lint-results.json`/`.md`. Screenshot reports and
`screenshot-results.json`/`.md` are in `JL-Mod-Plus-screenshot-diagnostics`.
The same compact counts and missing-report diagnostics appear in job logs and
step summaries, allowing connector-only review without downloading every report.
Required missing/empty test reports fail the job; skipped screenshot cases also
fail. Summaries count actual XML testcases and do not claim fresh execution when
Gradle may have restored cached results. The validation file records its own job's step outcomes, not the separate
lint result; consult the final `build` check for overall status. The early APK
artifact is not rewritten. Artifact availability does not mean validation passed.
Inspect the individual reports when a combined step fails.

### Historical Library performance evidence

The PR-specific Library profiler workflow, its five diagnostic scripts, and two
profiling-only instrumentation probes were retired after qualification. They are
not active tests or supported utilities in the current repository. The separate
Library UI contract suite remains available for opt-in interaction testing.

The [interaction performance trials](interaction-performance-trials-2026-10-08.md)
and [app UI performance investigation](app-ui-performance-2026-10-08.md)
preserve measured results, methodology and limitations. The former emulator A/B
workload compared fixed baseline `925d9b29e14c05013b86d9bec810bdc738440fbc`
to its then-current candidate on an isolated API 35 emulator. Historical runs
are not evidence of current HEAD performance or a release FPS guarantee.

For a future profiling investigation, independently choose and record relevant
baseline/candidate revisions, equal isolated fixtures, device and tool versions,
and trace/source identity. Use official Android/Perfetto tooling rather than
assuming the removed PR-specific harness still exists. Profiling is an
investigation activity, not a routine merge check.

### Screenshot suite configuration

Screenshot testing uses AGP 9.5 test suites and engine `0.0.1-alpha16`, without the
standalone `com.android.compose.screenshot` plugin. Sources remain under
`app/src/screenshotTest/kotlin`; the `screenshotTest` suite targets only
`emulatorDebug`. References live under
`app/src/screenshotTestDefaultEmulatorDebug/reference`. The test task is
`:app:testEmulatorDebugScreenshotTestDefaultTestSuite`; the update task is
`:app:updateEmulatorDebugScreenshotTestDefaultTestSuite`. HTML reports are under
`app/build/reports/tests/testEmulatorDebugScreenshotTestDefaultTestSuite/`, with
JUnit XML and original renderer output under
`app/build/intermediates/emulatorDebug/testEmulatorDebugScreenshotTestDefaultTestSuite/results/`.
This experimental AGP API is pinned; recheck task/report contracts on upgrades.

### Screenshot updates without a local Android toolchain

1. Diagnose the mismatch from the `validate` run's screenshot report and authorized UI change.
2. For supported comparison failures, the run automatically uploads `JL-Mod-Plus-screenshot-candidates`. It contains `before/`, `after/`, `diff/` where available, `changes.txt`, `source-commit.txt`, `source-context.json`, and `baselines.patch`. These are the original PNG bytes from validation; there is no second render and no automatic baseline acceptance.
3. Compare the images and confirm the recorded build/head/base commits match the source being reviewed. PR renders include the temporary merge with the base branch. If either branch has changed in a relevant way, regenerate before accepting the images.
4. From the repository root, use `git apply --check path/to/baselines.patch`, then apply the reviewed patch. If only some changes are justified, transfer only those renderer-produced files. Automatic candidates cover supported mismatches only, not obsolete-reference cleanup or every rendering failure.
5. For deliberate full regeneration or cases without automatic candidates, dispatch `mode=update-screenshots` on the exact HEAD branch of an open same-repository PR and supply its `pr_number`. This runs the official update task and packages only changed references with the same manifest and binary patch as automatic candidates. No artifact is produced when nothing changed. Inspect additions, replacements and deletions individually; an obsolete reference must be deliberately removed, not assumed deleted by the renderer.
6. Commit accepted references and run normal validation again. Candidate packaging/generation does not change the failed validation result. Never accept unrelated visual changes just to make CI green.

The automatic packager reads the pinned screenshot engine's XML reference/actual/diff mapping,
accepts only recognized image-comparison failures, and excludes other failures.
Missing or invalid output remains a diagnostic problem, not an acceptable baseline.
Its temporary Git index leaves both committed references and the real index intact.
Review this mapping when updating AGP or the screenshot engine. Scripts and GitHub tools
may transfer images unchanged; they must not redraw expected pixels. No patch is
automatically committed.

If rendering or inspection is unavailable, preserve the references, complete other
scoped work, and report the precise gap and next required check. Missing checks
remain verification gaps; they do not authorize bypassing required merge checks.

### GitHub-only reviewed screenshot promotion

For agents limited to a GitHub connector, this is the supported path for updating binary screenshot references. Inspect the actual renderer-produced candidate images, diff and provenance, then use the existing owner-gated PR comment mechanism below; GitHub connector access alone is not proof of owner authorization. A Git tree edit, screenshot-test failure, or green CI badge is not permission to generate or replace expected PNGs without review.

Use the owner-only screenshot promotion path when the expected screenshot change
has been reviewed but the working environment cannot safely write or commit binary
PNG references, such as an agent that has GitHub PR/workflow access without a
local Android checkout. This is a convenience path, not a review bypass: promote
only renderer output that was already inspected and judged to be the intended
design.

For a failed PR `validate` run, or a successful PR-bound manual
`update-screenshots` run, that produced `JL-Mod-Plus-screenshot-candidates`:

1. Inspect the candidate artifact. Review `before/`, `after/`, and `diff/`
   for every candidate and confirm `changes.txt` and `source-context.json`
   describe the exact PR state being reviewed. Do not promote a candidate merely
   because screenshot comparison failed.
2. Collect the immutable provenance from that same reviewed state:
   - `head_sha`: the PR head SHA and the workflow run's PR head;
   - `base_sha`: the PR base SHA recorded for that reviewed state;
   - `build_sha`: `build_commit` from `source-context.json`, which must be
     the synthetic merge commit whose parents are `base_sha` then `head_sha` for
     PR validation, or exactly `head_sha` for manual generation;
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
{"head_sha":"<40-hex PR head>","base_sha":"<40-hex PR base>","build_sha":"<40-hex reviewed build commit>","run_id":123456789,"artifact_id":123456789,"artifact_digest":"sha256:<64-hex digest>"}
```

The first line must be exactly `/jlmod-promote-screenshots`. The JSON object must
contain exactly the six fields above. The promotion workflow accepts only an open
same-repository PR and only a comment authored by the repository owner.

The trusted workflow validates the PR head/base, Android CI run, artifact ID and
downloaded archive digest, run attempt, PR identity, build provenance, manifest
and `before`/current-golden equality. Manifest version 2 lists each operation
(`add`, `replace`, `delete`) and SHA-256 of each present image. Automatic comparison
candidates may only replace references; additions/deletions require successful
manual generation and explicit image review. It rejects artifacts containing
non-comparison failures or skipped cases. On success it applies only the reviewed
operations, verifies that no other paths are staged, commits them with the
provenance recorded in the commit message, pushes with a force-with-lease against
the exact reviewed HEAD, and explicitly dispatches final normal Android CI
validation. It does not re-render or synthesize the references during promotion.

If promotion is rejected, keep the guardrails intact. Refresh PR/run/artifact
state, identify which provenance or candidate assumption changed, review the
replacement evidence, and submit a fresh request. Do not weaken the workflow,
edit the artifact, guess missing identifiers, or bypass the final validation to
make a stale request succeed.

Promotion tooling is loaded from the default branch, never from the PR being
promoted. A PR changing the manifest/tooling cannot use that new protocol until
the trusted version is integrated; use a local reviewed binary patch for the
migration itself. Old manifests must be regenerated after this migration.

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

`runtime-smoke-results.py` pairs each class/method start with its terminal success.
Ignored/assumption-failed tests, unmatched completions, duplicate results, incomplete
batches and missing selectors fail verification. `selectors.txt` records PASS only
after the whole batch verifies; `*.verified.txt` lists the actual successful methods.
The suite remains on JUnit 4/AndroidJUnitRunner; JUnit 5 migration is deferred.

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
Cross-run Configuration Cache storage uses the optional repository secret
`GRADLE_ENCRYPTION_KEY` supported by `setup-gradle`. Without it, builds still work
but encrypted configuration entries are not persisted across runners. Fork PRs
have no secret and remain cache read-only. Supplying the secret is an administrator
operation; workflow configuration alone is not evidence of cache reuse.
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

Android CI owns PR validation: debug assembly, app/dexlib JVM tests, instrumentation
compilation, lint, and screenshot checks. Merge only the latest executable/build
state with passing relevant checks; a new squash SHA or documentation-only change
does not by itself invalidate that evidence. The manual release workflow builds and
verifies the release artifacts without repeating the debug validation suite.

For changes to shrinking, native packaging, release configuration, or relevant
dependencies, run **Android CI** with mode **release-check** on the PR branch.
It builds the release variant with R8 and all four ABIs, checks APK contents, and
stages the same assets as publishing. It installs and launches the x86_64 and
universal APKs on an API 35 emulator with 16 KB pages using a disposable test key.
This checks installation, native extraction, and startup; it does not qualify all
MIDlet/audio/3D behavior or physical ARM devices. Artifacts are unsigned and use
`0.0.0-ci`; they are not production updates. No release signing secrets, tags, or
published releases are used. Normal PR validation does not run this mode.

Release APKs compress native libraries to reduce download size. Installation
extracts those libraries and can consume more device storage than loading them
directly from the APK. Native code and 16 KB ELF alignment are unchanged. Debug
packaging retains the Android plugin defaults. R8 mappings are published as gzip
files; decompress them before Retrace. Download notes and asset labels distinguish
installable APKs from developer files, while metadata/checksums remain available.

Published APK identities are allocated by the release workflow rather than edited in
`gradle.properties`. On GitHub Actions, use **Release JL-Mod Plus** with the full
SemVer value (for example, `0.2.3-alpha`, `0.2.3-beta`, `0.2.3-rc`, or `0.2.3`),
without a leading `v`. New pre-releases use no numeric suffix. Use a new base version
for another publication in the same stage; never overwrite an existing tag or APK.
Historical numbered pre-releases remain readable and participate in version ordering.
For example, `0.2.2-alpha` is older than the published `0.2.2-alpha.1`; migrate with
a newer version such as `0.2.3-alpha`. Only
`alpha` permits pre-release publication. Stable publication requires a separately
prepared `stable` branch based on the JL-Mod Plus codebase; the legacy `main`
branch is not a release source.

The workflow serializes publication across branches, computes the next Android
`versionCode` from existing releases, checks the version and signing identity,
and publishes APK assets together with `release-info.json` and checksums. Existing
`0.1.0` pre-releases are handled through their tagged source metadata. A failure
after draft creation requires investigation; do not reuse an existing tag or
overwrite published assets. The working branch does not publish automatically.
`gradle.properties` remains the default identity for non-publishing builds.
The publishing and manual release-check jobs can populate Gradle Build Cache on
`alpha` (and publishing on `stable`); PR-scoped cache entries cannot be read back
by the base branch. Use the existing cacheable native dependency tasks instead of
adding a second cache of compiled native outputs.
