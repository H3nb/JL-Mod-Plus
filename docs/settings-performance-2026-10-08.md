# Settings performance qualification

This is the first implementation stage explicitly approved after the
[app-wide proposal](app-performance-proposal-2026-10-08.md). It covers the
application-owned Settings screen, including preferences about the emulator;
it does not change MIDlet execution. Work is local on
`fix/library-navigation-smoothness`, based on `19b5ffd4c`.

## Cause and design

The original LazyColumn treats each complete section as one lazy item. Entering
the runtime section therefore constructs and measures six switches together.
Physical attribution found a 40.466 ms main-thread Running interval, with
33.244 ms inclusive measure/layout and an App Deadline Missed classification.
Class verification accounts for only 0.129 ms exclusive CPU in that interval.

The candidate makes headings and individual controls separate lazy items,
with keys namespaced by section and preference identity. Content types separate
headings, choices, switches and actions. Conditional Library controls can change
without changing the identity of a visible Storage row. Dialog selection remains
screen-owned; persistence and activity results remain SettingsActivity-owned.

Rows retain the joined section background, 16 dp outer corners, spacing,
localized text, click/toggle semantics and existing callback behavior. Only the
first/last row clips the corresponding outer corners. The refined candidate
uses a decorative Box with clipping, background and traversal grouping rather
than a Material Surface per row. Scaffold supplies onSurface once for the
screen, preserving title and ripple colors without a provider per row;
summary and disabled colors retain their existing definitions.

## First experiment and reason for refinement

Three traced baseline/candidate pairs reduce maximum main-frame Running from
35.39–38.74 ms to 13.13–24.05 ms and maximum measure/layout from 29.08–31.48 ms
to 8.54–14.78 ms. However, P95 Running increases from 5.98–6.29 ms to
7.45–7.90 ms; App Deadline Missed counts are 24/1502 versus 27/1506.
This establishes smaller construction spikes, not an overall jank improvement.

An untraced APK comparison confirms that row granularity, rather than extra
composition tracing alone, reduces the maximum. Two usable pairs show
33.91→12.67 ms and 39.51→12.81 ms maximum Running. P95 is mixed: one increases
19.6%, the other decreases. Main CPU per second of the touch interval rises
approximately 3.4–4.9%. This modest recurring cost motivates testing the simpler
row decoration before accepting the change.

These early recordings use six host-issued ADB swipes. One candidate recording
has a 10.425 s touch interval versus 4.790 s for its baseline, and is excluded
from matched-duration conclusions. Another pair differs by about 15%; its raw
total CPU must not be interpreted as a saving. Follow-up input is paced on the
device in one shell invocation, with trace-side timing checks.

## Evidence controls

The physical device is a POCO onyx, Android 16 / SDK 36, 1280×2772, density 3.25,
120 Hz. Only the isolated `io.github.h3nb.jlmodplus.perf158.debug` installation is
used. Settings measurements use Indonesian; interaction tests use English.
No guest is launched and the ordinary application installation is not replaced.

Measurements disable native builds and use emulatorDebug. A request to compile
with `speed` returns Success, but dumpsys reports actual ART status `verify`
for both APKs. These are debug/verification measurements, not release AOT or
production FPS proof. CPU scheduling/frequency and external load remain sources
of variance. Inclusive trace costs overlap and must not be added together.

Raw traces, pre/post foreground and hierarchy guards, APK/source hashes, SQL,
renderer images and test logs are retained under ignored `build/perf-settings`.
The baseline APK SHA-256 is
`c120e18d13eb33f2f89e142697a3db67e3e6a6a821dd4be03a1d3cbc1dad389b`;
its Settings Git blob at `19b5ffd4c` is
`d2219c417cdb949702f58c37effed39b539d3936`.

## Final controlled measurements

The final two baseline/candidate pairs use USB and a single device shell call
for all six swipes. Each swipe lasts 300 ms with a device-side 300 ms pause;
traces last 12 seconds. One complete warmup cycle precedes recording. Guards
check foreground SettingsActivity, package, Indonesian text, portrait geometry
and return to the top both before and after each trace. Installed APK hashes
match the intended files.

| Metric | Baseline repeats | Final candidate repeats | Interpretation |
| --- | --- | --- | --- |
| Touch span | 3.765 / 3.748 s | 3.714 / 3.748 s | Within 1.4%; host Wi-Fi pacing confound removed. |
| Maximum main-frame Running | 43.904 / 29.488 ms | 14.105 / 11.878 ms | 67.9% / 59.7% smaller worst stalls. |
| P95 main-frame Running | 6.106 / 6.122 ms | 7.955 / 7.192 ms | 30.3% / 17.5% more routine-frame work. |
| Main CPU per second during touch span | 570.70 / 548.56 ms/s | 603.30 / 579.71 ms/s | Approximately 5.7% more CPU; this is not a power measurement. |
| App Deadline Missed / actual frames | 9/470 / 5/472 | 7/466 / 2/480 | Aggregate 14/942 versus 9/946, descriptive rather than a significance claim. |

The reason to retain row granularity is the substantial reduction in construction
stalls and bounded work per lazy unit. It trades some recurring lazy/prefetch
work for smaller peaks; it is **not** a universal efficiency, P95 or FPS win.
The decorative Box is simpler, but these recordings do not establish that it
alone improves CPU over the Surface experiment. Release/AOT qualification and
other device classes remain unverified. Do not extrapolate these numbers into
a whole-application speedup.

Final candidate APK SHA-256:
`5534632824ce78b3aedd57a1e2198036d590739022fdc1a0bed262a8a62d7fd5`.
Final Settings file SHA-256:
`04d6fd84de07e6515111500862b6b51516e01603d7109d6f2b87ac828a8d506c`.
Candidate trace SHA-256 values are
`66c08e3a3c461920cacf52b3cb010a2b601076a4a37f1212429c9199f169b59b`
and `45ecf9945425fb7c4d64c68aaac31c26469dea7c28fdcd26b21bc43dc36cf659`.
Final traces have no unfinished main frames or conflicting composition track
descriptors; each reports 36 unsupported ftrace setup errors.

## Validation

Main and test APK assembly, `lintEmulatorDebug` and all ten selected Settings
behavioral checks pass. The final full device run passes nine checks; the
stable-anchor check passes on a targeted rerun after allowing integer rounding.
Two checks are real SettingsActivity smoke tests, including preference writes
after recreation. The compact test adaptation targets the localized Manage profiles
action instead of an old merged section heading, and positions touch targets
below the pinned app bar. Assertions and physical click dispatch remain in the
tests. The stable-anchor check allows one physical pixel of integer scroll
rounding while retaining visibility and callback assertions.

Validation uses the existing `SettingsComposeTest` and
`SettingsActivityComposeSmokeTest` classes through AndroidJUnitRunner, and
`validateEmulatorDebugScreenshotTest --tests '*settings.SettingsScreenshotTestKt*'`.
The final lint/screenshot invocation ends with BUILD SUCCESSFUL. The isolated
locale is restored to Indonesian afterward; the ordinary debug app retains
its 2026-10-08 13:22:09 installation timestamp.

All three final screenshot comparisons pass after byte-for-byte promotion of
inspected native renderer output. Compared with the old references, light,
dark/large-font and landscape differ at 205, 225 and 121 pixels respectively;
all are rounded-corner antialiasing with maximum one channel-level difference.
Geometry, content and section appearance are preserved. No pixels were edited
or synthesized. The earlier Surface experiment kept landscape identical and
changed only two light/dark corner pixels.

Config lazy form construction and background file/decode work remain separate
proposal stages. No remote push or merge has been performed.
