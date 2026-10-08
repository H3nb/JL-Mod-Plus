# App UI performance investigation, 2026-10-08

This follow-up to PR 158 measures app-owned UI on the USB-connected arm64
Android 16 device (model `25053PC47G`, 1280 x 2772, 520 dpi). MIDlet execution
and Java ME rendering are excluded. The panel supports 120 Hz; that is not a
claim that every recorded frame was scheduled at 120 Hz.

## Workload and evidence

The baseline is PR head `3a212f2092473dc29ac7fa2cf1eefc4b803cd6a8`.
The candidate changes only dominant-color bitmap reads in production.
Both use `emulatorDebug`, native compilation disabled, and the separate
`io.github.h3nb.jlmodplus.perf158.debug` installation. The ordinary installed
packages and user-selected workdir are not modified. The opt-in fixture uses
an app-private READY catalog with 1,000 entries, real 128-pixel PNGs and a
500-member Collection; its entries cannot run as MIDlets.

Local raw evidence is retained under `build/perf-device/`: APKs, recording
configs and scripts, raw Perfetto traces, SQL and results, hierarchy checks,
CPU samples, output fingerprints and test logs. The official recorder is
pinned to Perfetto commit `b642b0016614a50d0174299473feb691566aaa00`; analysis
uses the existing official Windows Trace Processor v58.2 (`add693d8b`).

The recorded routes include three process-cold launches against the existing
catalog, list scroll, Apps/Collections/More transitions, Collection opening,
Settings opening/scroll and File Picker opening. They do not measure first
indexing, import, installer execution, release/R8 performance, every app-owned
screen or cold filesystem caches. Background music and the normal device
environment remain present. Thermal service reports status 0, but continuous
thermal and GPU-frequency evidence is unavailable.

Two original candidate scroll recordings also contain Config navigation and
are excluded from the scroll comparison. Three controlled candidate recordings
repeat the gestures with hierarchy and foreground checks outside the recording
window. Do not treat the earlier mixed recordings as pure Library scrolling.

The vendor tracing service cannot open `/dev/socket/logdr` (permission denied).
BEGIN/END actions are present in ADB logcat, but are unavailable in the trace's
`android_logs` table. These physical traces therefore do not establish the
trace-side action-marker/readiness contract of the emulator CI workflow.

## Recommended change implemented

`Library/iconNormalize` consumes 333.916 ms of thread CPU across 30 calls in
the first baseline scroll; decode takes 41.920 ms of wall time. Baseline cold
launches spend 324.594-540.930 ms of CPU across ten normalization calls, with
substantial debug first-use/JIT variability. This identifies appreciable
background artwork work, without proving it causes every main-thread stall.

`findDominantVisibleColor()` previously used a bitmap JNI read for each sample
in both passes. It now reads each sampled row once into a reusable `IntArray`.
Coordinates, sample stride, alpha threshold, bin selection/tie breaking and
color arithmetic remain identical. For a 128-pixel bitmap this reduces native
pixel-read calls from 8,192 to 128. The extra temporary memory is one row
(512 bytes for that bitmap), rather than a full bitmap copy or a new cache.

The opt-in `LibraryArtworkPerformanceProbeTest` measures normalization alone
after 100 warm-up calls, with 20 thread-CPU samples for each case. Bitmap
creation, hashing and property inspection are outside the timed interval.
Every normalized bitmap's dimensions and ARGB SHA-256, together with all
stored presentation properties, match exactly between baseline and candidate.

| Artwork | Size | Baseline median CPU ms | Candidate median CPU ms | Change |
| --- | ---: | ---: | ---: | ---: |
| Transparent pixel art | 48 | 1.674 | 0.652 | -61.1% |
| Framed | 48 | 2.656 | 1.320 | -50.3% |
| Checkerboard | 48 | 2.552 | 1.226 | -51.9% |
| Gradient | 48 | 2.353 | 1.026 | -56.4% |
| Transparent pixel art | 256 | 4.506 | 2.136 | -52.6% |
| Framed | 256 | 9.302 | 6.873 | -26.1% |
| Checkerboard | 256 | 7.916 | 5.624 | -29.0% |
| Gradient | 256 | 4.823 | 2.486 | -48.5% |

These are ordered debug CPU observations on this device, not whole-app FPS or
a release benchmark. No styling, sampling quality, animation, artwork
presentation, dependency, persistence or MIDlet implementation is changed.

A second baseline/candidate installation pair retains exact output equality
in all eight cases and observes 24.8-62.1% lower median normalization CPU.
Controlled scroll repeats with equal normalization call counts observe
27.4% less CPU for 30 calls and 47.9% less for four calls. Whole-frame results
remain mixed: baseline scroll App Deadline Missed counts are 35/591, 29/678
and 38/985; controlled candidate repeats are 42/575, 31/675 and 28/696.
Different frame counts, ordered execution and debug runtime state prevent a
causal overall smoothness claim.

## Other findings and decisions

- A representative baseline scroll frame spends 59.841 ms of CPU within a
  60.429 ms `doFrame`, including 43.438 ms of `measureAndLayout`. It occurs
  6.65 seconds after that trace's only icon normalization call. Its immediate
  cause is separate main-thread layout work, not concurrent icon normalization.
  The exact layout leaf remains unidentified; investigate it before proposing
  a layout redesign or changing Compose dependencies.
- First-use startup has a main-thread render wait overlapping a 71.860 ms
  RenderThread draw, including Vulkan `finish frame`, `CircleOp` and
  `CreateGraphicsPipeline`. This is evidence of first-use rendering work,
  not sustained GPU saturation. Retain visual effects rather than remove them
  based on this startup observation.
- Settings and File Picker first-use traces include deadline misses, while
  normal tab transitions are mostly on time. Their narrow samples do not
  justify another production fix yet. Per-item grid constraints and redundant
  startup diagnostic writes remain source candidates, not measured leaf causes.
- Keep App Deadline Missed separate from Buffer Stuffing, SurfaceFlinger and
  Unknown classifications. A long frame duration alone does not identify an
  application bottleneck; see the [official FrameTimeline guide](https://perfetto.dev/docs/data-sources/frametimeline).

## Validation

- Candidate APK and instrumentation assembly: `BUILD SUCCESSFUL`.
- All 30 selected JVM tests in `LibraryIconPresentationTest` and
  `LibraryListProjectionTest` passed.
- Baseline/candidate physical artwork characterization passed, with exact
  pixel and presentation equality in all eight cases.
- The final physical `LibraryUiContractSuite` run passed all 59 tests across
  viewport/workdir restoration, icon refresh, Collections, Library interaction
  and Config tab navigation. The final instrumentation rebuild succeeded.
- Physical Library UI contract results and repeat-trace summaries are retained
  in the local evidence directory. The initial UI run used Indonesian; several
  existing tests assume English labels. The isolated package locale is set to
  English for the subsequent contract run, matching the CI test environment.
- The English run reproduced one search-gap assertion failure on both baseline
  and candidate. The fixture has no icon paths, so artwork normalization is not
  involved. Its 100-pixel threshold allows only 30.8 dp on this device. The
  assertion now converts the 100 dp limit to pixels, consistent with the nearby
  quick-filter test, and also rejects overlapping search/result bounds.

This investigation does not establish a general startup or FPS improvement.
PR publication and merge remain separate from local implementation/validation.
