# Interaction performance trials, 2026-10-08

This records the local implementation and qualification of the designs in
[interaction-performance-design-2026-10-08.md](interaction-performance-design-2026-10-08.md).
The scope is app-owned UI and Library projection. No guest MIDlet was launched or
changed. Results describe debug APKs on one physical device, not release FPS.

## Implemented candidates

- Home keeps the three existing pager destinations composed. Lists remain lazy;
  inactive pages relinquish focus, semantics, dialogs and user mutations while
  retaining viewport/query state and necessary identity/membership reconciliation.
- The Config row-lazy trial registers ordinary preferences as keyed lazy rows. One typed active
  form editor belongs to ConfigScreen, outside disposable rows and pager pages.
  Confirmation merges the edited field into the latest authoritative form.
  Existing color, encoding, gamepad and native editor owners are preserved. This
  trial is rejected below; the accepted UI retains its original section layout
  and editors rather than adding an unused disposable-row editor model.
- Config's effective canonical comparison is shared within one UI projection.
  Gson round-trip canonicalization and SystemProperties normalization remain;
  there is no persistent comparison cache or change to preset ownership.
- Library retains one completed immutable ordering and full available IDs. Query
  changes scan into six relevance buckets on Default; collection-only updates
  forward their matching new envelope/revision without sorting unchanged apps.
  Generation/workdir, app content, effective order and locale invalidate it.
  Latest-only publication and cooperative scan cancellation remain in force.
- A separate chrome candidate retained navigation/FAB composition and moved
  animation reads into graphics layers. It was measured separately before the
  combined candidate; acceptance is recorded below.

Metadata discovery/resource I/O changes are deferred. They need their own
measured invalidation/lifecycle decision; this trial does not claim that file or
decode work has moved off the main thread.

## Method and provenance

The USB device is POCO 25053PC47G (onyx), Android 16, portrait 1280 x 2772. The
isolated package `io.github.h3nb.jlmodplus.perf158.debug` uses 1,000 app records,
500 collection memberships and fixture icons. The ordinary installed app is
untouched. Each APK was installed with `-r`, requested ART `speed` compilation,
and checked against its on-device SHA256 before input. Native compilation was
disabled for the app-owned trial; APK outputs were verified after successful
builds.

There are three captures per measured scenario. Phases are sequential, not
randomized/interleaved, and do not establish statistical significance. Startup
is process-cold, not filesystem/ART-cache cold. Navigation is warmed; scroll uses
the same forward/reverse coordinates. Search types `0`, `09`, `099`, `0999` and
then clears a verified nonempty query. Config enters Controls/System and scrolls
forward/reverse without changing settings. Pre/post checks enforce foreground
activity, orientation, fixture content and query. UI dumps run outside captures.

Perfetto records scheduler Running, CPU frequency/idle, framework/Compose
scopes, exact activity FrameTimeline and process memory at 500 ms. Plain APKs
do not include compiler-generated project composable tracing. Input windows
span first delivered input through last delivered input plus 500 ms. Callback
wall duration, callback Running, main work outside callbacks, process CPU and
actual frame duration are distinct. Nested closed callbacks are not counted
twice; unclosed callbacks are excluded from duration statistics. Startup's 2 ms
Unspecified Present records cannot represent its long callbacks. Sampled RSS
and the separate warm PSS/heap snapshot are not allocation/retained-heap proof.

Local traces, guards, hashes, source snapshots, build logs and analysis are under
`build/perf-trials/`. The pinned native trace processor is Perfetto v58.2. Raw
tables and definitions are in `analysis/metrics.sql`, `analysis/README.md` and
per-phase JSON/CSV. No trace-loss counter was reported; unsupported ftrace setup
notices remain in the raw health output.

| APK | SHA256 |
| --- | --- |
| Baseline, accepted Settings APK; relevant interaction source unchanged through ed09f8af9 | `5534632824ce78b3aedd57a1e2198036d590739022fdc1a0bed262a8a62d7fd5` |
| Retention only | `5f4e20794f568b86d5ab5ed7556d23bbbd33dfcecce354d4bdb41f3157f16b86` |
| Chrome only | `e93ebc6c8cc5e780db1a50e432f7e0f95a151e13dc92603f4fd6ae64abcc712f` |
| Combined trial | `3f2eac4f68e4f0c13bc82b2607db59a98c9e305e79786c4b348a8949a1b7f0e5` |
| Corrected lazy Config trial, original chrome and vertically grouped gamepad rows | `960f40e929e096203fd260b50afcf6618d27b863463a611bf4c81604484bba63` |
| Accepted final candidate, original Config sections and chrome | `ba9c924572f467e8b73672f3c47b91c23ef87927e79c12c45ef7629254532aaf` |

The combined trial is superseded: user visual QA exposed overlapping analog and
calibration preferences in Controls. Its Controls timings cannot qualify a
correct UI and are excluded from acceptance. The lazy row's Box incorrectly
overlaid the two controls emitted by the bounded gamepad group. Lazy row content
now has an explicit ColumnScope and stacks vertically. Controls geometry tests
and Indonesian/large-font screenshot cases were added. The fixed APK was installed
and physical UI bounds verified: analog title `[91,1217,366,1295]`, calibration
title `[91,1660,589,1738]`. The screenshot also confirms their separate vertical
placement. The broken diagnostic APK was temporarily replaced with baseline,
then replaced with the verified fixed APK before corrected-trial measurements.
After those measurements exposed a displaced layout stall, the row-lazy Config
trial was rolled back. The accepted section layout already stacks these controls;
the new physical-bounds and Controls screenshot regressions remain.

Snapshot builds initially exposed Kotlin source relocation/incremental-output
issues. Retention APK dex verification confirms the retained pager count and
old AnimatedVisibility call sites; GlassSystemBarScrim dex is byte-for-byte equal
to baseline. An unused ScrollChrome class remained in that APK, with no Library
call site. Chrome and combined source were compiled nonincrementally. Failed
builds are preserved as harness/compiler feedback, not validation successes.
One malformed warmup swipe was rejected before a scroll trace was accepted;
the corrected baseline scroll phase was rerun.

## Independent home/chrome results

Numbers below are medians of three captures, except the one idle/memory snapshot.
Ranges and exact frame classifications remain in the local reports.

| Metric | Baseline | Retention only |
| --- | ---: | ---: |
| Navigation callback maximum, ms | 125.615 | 36.437 |
| Navigation callback P95, ms | 12.612 | 13.430 |
| Navigation actual-frame maximum, ms | 129.379 | 39.334 |
| Navigation actual-frame P95, ms | 15.400 | 16.717 |
| Navigation main Running, ms | 2280.751 | 2322.742 |
| Navigation process Running, ms | 5200.690 | 5069.249 |
| Startup `am start -W` TotalTime, ms | 635 | 710 |
| Bind to first recorded Main draw, ms | 496.557 | 568.865 |
| Main Running through first draw, ms | 456.355 | 520.529 |
| Idle main Running over about 12 s, ms | 0 | 0 |
| Warm total PSS, KiB | 141321 | 128306 |

Retention removes the recurring approximately 126 ms navigation construction
stall, at the cost of approximately 75 ms startup latency and 1.8% more navigation
main CPU in this phase. P95/frame-health metrics are mixed; dropped records rise
from 65–66 to 77–108 with different frame denominators. This is a targeted stall
reduction, not a universal frame-health or memory improvement. The single warm
snapshot does not show an increase, but cannot prove lower retained heap.

| Scroll metric | Baseline | Chrome only |
| --- | ---: | ---: |
| Callback maximum, ms | 31.401 | 29.339 |
| Callback P95, ms | 12.082 | 12.189 |
| Main Running, ms | 3042.132 | 3052.639 |
| Process Running, ms | 5071.762 | 5056.920 |

The chrome-only trial does not demonstrate a material gain in the representative
scroll workload. Its retained subtree and hidden-placement/focus policy add
complexity without a consistent P95/CPU benefit. It is not accepted on this
evidence. Original chrome animation is retained in the final candidate.

## Worker diagnostic

An ignored host JVM diagnostic calls the actual compiled prepare/project API and
compares exact results with the baseline filter-then-sort rules. It alternates
timed order after four warmups and reports nine rounds; shuffled title-order
fixtures use 1,000/10,000 rows. The six-query sequence is the physical search
sequence including blank start/clear. This is host CPU evidence, not Android
input latency or an allocation benchmark.

| Host median, ms | 1,000 rows | 10,000 rows |
| --- | ---: | ---: |
| Prepare once | 25.742 | 340.380 |
| Baseline six-query sequence | 78.208 | 774.750 |
| Prepared six-query sequence, preparation excluded | 1.074 | 5.838 |
| Baseline cold selective query | 0.201 | 2.088 |
| New cold selective query, preparation included | 25.966 | 325.813 |

The prepared sequence avoids recurring Collator sorts. A cold very selective
query after source invalidation pays full ordering and can be substantially
slower than filter-first sorting; this trade-off is preserved, not hidden by a
multi-query cache. Physical host presentation remains independently measured.

## Corrected trial and acceptance

The corrected trial completed all 16 guarded traces: three navigation, Controls,
System, search and clear captures plus controlled home idle. Each table cell is
the median of three matched captures. These are independent duration/CPU metrics,
not an input-latency claim.

| Scenario | Callback max, baseline/trial ms | Callback P95, baseline/trial ms | Main Running, baseline/trial ms | Actual-frame max, baseline/trial ms |
| --- | ---: | ---: | ---: | ---: |
| Home navigation | 125.615 / 35.883 | 12.612 / 9.752 | 2280.751 / 2288.557 | 129.379 / 41.417 |
| Controls tab + scroll | 68.463 / 36.065 | 10.636 / 10.695 | 1277.703 / 1432.915 | 73.761 / 50.903 |
| System tab + scroll | 65.080 / 68.525 | 10.887 / 8.493 | 1020.828 / 1022.516 | 72.068 / 80.034 |
| Search | 81.992 / 81.458 | 58.394 / 58.565 | 416.467 / 408.778 | 84.407 / 84.233 |
| Clear | 57.253 / 57.299 | 23.331 / 24.356 | 645.073 / 617.186 | 59.809 / 59.992 |

Home retention reproduces the long-callback reduction; main CPU is approximately
unchanged in this combined phase (+0.34%). Startup costs from the independent
retention experiment still apply. Controlled home idle has zero main Running.
Actual-frame P95 is 15.400/15.381 ms; dropped-frame counts remain mixed.

The corrected Config trial is **not accepted**. Controls callback maxima improve,
but the independent out-of-callback layout spans are baseline 231.130/70.293 ms
(none among the top recorded spans in capture 2), versus 55.250/157.746/158.209 ms.
The first capture's improvement does not establish an overall win: the repeated
157–158 ms main-thread layout blocks move the stall outside the callback metric.
Controls main CPU also rises 12.1%, while System maximums are mixed. This fails
the design's rule against moving a severe stall elsewhere. The original Config
layout/editor ownership is restored; only shared canonical comparison remains.

Prepared Library ordering is retained for its verified removal of recurring
worker sorting and exact equivalence, not for a claimed large UI speedup. Physical
search/clear frame maxima and P95 remain similar. Main rendering/input work is
still a substantial bottleneck. The cold selective-query trade-off above applies.
No debounce or multi-query cache is introduced.

Accepted source consists of home lifetime/focus guards, prepared worker ordering,
the redundant already-at-top scroll avoidance, shared Config canonical comparison
and regression coverage. Chrome animation and Config section layout remain as
before the trials. Final accepted-APK Config qualification is recorded below.

The accepted APK completed another six guarded Config captures with ART `speed`
compilation and its installed SHA verified. Home/search source is unchanged from
the corrected-trial measurements above; Config now contains only shared canonical
comparison. The accepted universal APK and prior arm64 trial APK contain exactly
the same installed arm64 native entry/hash. Rejected Config row/editor classes
are absent from accepted dex. An initial test installation used an incorrect
APK filename; that interrupted run is excluded, and metadata-selected artifacts
were successfully reinstalled before the passing tests and measurement phase.

| Accepted Config scenario | Callback max, baseline/accepted ms | Callback P95, baseline/accepted ms | Main Running, baseline/accepted ms | Actual-frame max, baseline/accepted ms |
| --- | ---: | ---: | ---: | ---: |
| Controls tab + scroll | 68.463 / 29.697 | 10.636 / 10.281 | 1277.703 / 1268.071 | 73.761 / 38.288 |
| System tab + scroll | 65.080 / 22.378 | 10.887 / 10.698 | 1020.828 / 983.481 | 72.068 / 31.120 |

Accepted Controls out-of-callback layout blocks are 70.177/72.962/76.842 ms;
the rejected trial's repeated 157–158 ms blocks do not recur. Layout remains a
meaningful stall, not an eliminated bottleneck. Main CPU is approximately flat
for Controls (-0.75%) and lower for System (-3.66%). Actual-frame P95 medians are
14.331/13.637 ms for Controls and 14.717/14.123 ms for System. These sequential
three-capture debug phases do not isolate every observed difference to the
canonical comparison change or establish a release-performance guarantee.

## Validation and final decision

Qualification of the accepted source is complete: isolated debug APK and
androidTest APK builds, 511 passing Config/Library/app-list JVM tests and one
existing skipped test, 120 passing screenshot tests, and completed debug lint.
Differential projection tests cover 504 combinations;
flow tests cover reuse, source envelope/IDs, locale, generation, play statistics
and cancellation. `accepted-build-tests.log`, `accepted-final-checks.log`, the
JUnit XML, screenshot engine XML and accepted lint XML record the final checks.
The lint task completed in the first visual/lint run; that overall invocation
failed only for the two new Controls references before their reviewed update.
The final screenshot/JVM invocation completed `BUILD SUCCESSFUL`.
Accepted lint reports zero errors, 193 warnings and one hint; this work does not
claim to clear the repository's warning inventory.

The corrected trial's row Surface preserves content color, shape and grouping. Renderer
comparison found exactly two antialiasing corner pixels per image in eight
existing references (16 pixels total); all other pixels and the other existing
images are unchanged. Reference/actual images and magnified corner comparisons
were inspected. Original renderer PNG bytes were reviewed for these eight images,
and for two new Controls cases covering Indonesian copy and 1.8x font scale.
After rejecting row laziness, the eight old references were restored unchanged;
only the two new Controls cases remain and passed final renderer validation.
No image pixels were edited or reconstructed. The new cases and physical bounds
test cover the multi-preference row failure missed by the prior screenshot set.

The first fixed-device suite passed all 32 Library interaction tests and the
three new Config regressions, but exposed eight stale Config test selectors.
Those selectors used superseded resource copy, matched both an underlying row
and a dialog option, or omitted the existing warning prefix. Tests now resolve
the actual resources, scope dialog options and scroll preferences into view.
The multi-state warning test uses one composition and updates its state. Production
behavior was not changed to satisfy those selectors. The corrected-trial rerun
passed all 35 Config tests; 120 screenshot tests and lint also passed. Disposable
row/draft tests and their unused production editor machinery were removed together
with the rejected trial. The grouped-controls geometry regression remains and
the final accepted UI passed qualification.

The accepted source passed 33 physical Config tests, including the grouped-control
bounds regression, dialog options, colors, preset actions, warning transitions,
adaptive navigation and system-properties editing. The unchanged home source
passed all 32 physical Library viewport/collection tests. No guest was launched.
These are separate passing suites on unchanged home source and the accepted
Config source, not one claimed 65-test instrumentation run. The accepted diagnostic
APK remains installed, with Indonesian app locale restored. Physical bounds and
the final device screenshot again show the two gamepad preferences separated.

Only two new Controls reference PNGs are committed; all existing references
remain unchanged. The PNGs are original renderer output for the accepted section
layout. All trial helpers, snapshots, traces and logs stay in ignored build
directories. No temporary production diagnostics or rejected editor/chrome
machinery remain. This is local debug qualification, with native compilation
disabled; guest compatibility, release timing and native runtime qualification
are not claimed by these app-owned trials. No push or merge was performed.
