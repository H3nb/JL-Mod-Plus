# App-wide performance proposal

This extends PR 158's investigation to all application-owned surfaces and
services. MIDlet execution and guest API behavior remain outside this work.
The user permits visual/backend changes but requires a concrete recommendation
before adopting a material change. Measurements below explain the diagnosis;
they are not predictions of the improvement from each recommendation.

The user initially selected **analysis only**, then explicitly approved staged
implementation starting with **Settings**. Its implementation and physical
qualification are recorded in [Settings performance qualification](settings-performance-2026-10-08.md).
Config and background file/decode changes remain proposals;
the following composition attribution strengthens some recommendations and
lowers confidence in others.

The subsequent [interaction-focused analysis](interaction-performance-analysis-2026-10-08.md)
adds parallel source investigations and fresh USB traces for home navigation,
Library search/scroll, real Library-to-Config entry, Controls and System. It
refines priorities around recurring page construction, scroll chrome, active
editor ownership and repeated Config semantic/metadata projection work.

## Follow-up physical attribution

The attribution below uses `19b5ffd4c96fdf7f6abb4bedaee943730a6f1788` on
`fix/library-navigation-smoothness`. The isolated `emulatorDebug` APK has native
builds disabled and temporary Compose runtime-tracing/Perfetto dependencies
from an ignored init script. Its SHA-256 is
`9fc2334f4e7240d571401fc51284ce6127cbb02b3f888d9b9780b872a235020d`.
The same POCO Android 16 device is now connected through wireless ADB; the
application locale is Indonesian. No guest is launched.

| Controlled observation | Main callback wall / Running CPU | Work attribution | What it supports |
| --- | --- | --- | --- |
| Library scroll, repeat 0 | 64.608 / 64.038 ms | Header composition plus one new row; measure/layout 25.036 ms. Header 13.124 ms inclusive, mostly nested Material search/header work rather than its own code. | Separate header/row/layout investigations; no single icon-processing explanation. |
| Library scroll, repeat 1 | 39.322 / 37.926 ms | Navigation/animation composition; no LibraryListItem composition. Measure/layout 10.941 ms. | Keeping navigation/FAB composition alive while animating rendering is a worthwhile experiment, preserving hidden-state input/semantics and current viewport contracts. |
| Settings scroll | 41.384 / 40.466 ms | One lazy runtime section resumes paused composition: six switches, applyChanges, eleven text measures. Measure/layout 33.244 ms. | Strongest evidence for smaller lazy preference units. Matching FrameTimeline token reports App Deadline Missed; its expected deadline expires during section composition. |
| Settings opening | 78.794 / 70.114 ms | Complete inner callback: measure 43.869 ms, composition 34.040 ms, applyChanges 9.775 ms. | First-use preference construction is significant. This is not a prediction of the saving from laziness. |
| File Picker content opening | 85.741 / 78.532 ms | Complete inner callback: measure/layout 60.825 ms, eleven entry rows plus parent; seventeen composition scopes. | Initial row/Material layout work is significant. Small-folder filter/sort is not demonstrated as the dominant cause. |
| Config Display entry | 88.658 / 87.372 ms | Measure/layout 79.323 ms; 22 preference calls and 41 text measures in the worst callback. ApplyChanges 14.374 ms. | Strong evidence for addressing eager form construction. This is a pager transition, so adjacent Audio content can also be prepared. |

These numbers are **inclusive attribution**. Parent/child costs overlap and
must not be summed. Running CPU is scheduler intersection, not elapsed wall
time assigned wholly to a source method. Config's corresponding actual frame
is classified Dropped Frame; it is not relabeled App Deadline Missed.

Config initial opening also contains a 144.836 ms callback / 131.362 ms Running
and a 68.211 ms performCreate interval. Those observations include first-use
class verification, UI setup and uninstrumented activity work. They do not
identify shader discovery as the measured dominant leaf. Resource discovery
needs a separate bounded measurement before claiming a significant gain.

Settings' worst scroll frame spends only 0.129 ms exclusive CPU in class
verification. The section work remains a concrete cause within this debug
recording, although ART interpreter execution and tracing still inflate costs.
Library root composition occurs only twice per recording; action-gate costs
are small. Blanket stability annotations or per-pixel root-recomposition fixes
are not supported by these recordings.

## Recording controls and limitations

- Library repeats 0 and 1 retain pre/post hierarchy and foreground checks.
  Repeat 2 is excluded: wireless trace transfer exceeded the host timeout and
  the post-recording foreground check did not run.
- Settings before/after hierarchy contains the intended package and settings;
  Picker hierarchy confirms populated content. Their foreground activity dumps
  were not saved, so their controls are weaker than the Library repeats.
- Config Display retains selected-destination hierarchy and Config foreground.
  The subsequent Controls sample failed foreground verification when a call
  activity took over; exclude it. System was not recorded. The phone was not
  manipulated further during the call.
- All follow-up traces report eight conflicting track descriptors and 36
  ftrace setup errors. The processor ignores conflicting descriptors; missing
  producer tracks and unsupported ftrace sources limit completeness. Vendor
  android.log recording is unavailable, so external state checks replace
  trace-side interaction markers.
- Settings/Picker opening has an unclosed outer Choreographer callback.
  Attribution uses complete bounded inner callbacks and the matching actual
  FrameTimeline entries. Depth-zero-only aggregation gives misleading results
  for these recordings and is not used for opening conclusions.
- CPU sampling is useful for attribution context but opening samples are too
  sparse for precise method percentages. Debug/tracing, first-use classes,
  cache state and uncontrolled background activity prevent a release-FPS claim.
- The temporary tracing APK was replaced with the previous diagnostic APK
  after measurement. The normal application installation was not changed.

Local raw evidence, SQL and reports are retained in ignored
`build/perf-layout/`: `analysis-leaf/report.md`,
`analysis-leaf/surfaces-report.md`, `config-*-attribution.txt`,
`config-display-health.txt`, `config-display-row-count.txt`, trace files,
hierarchies, recorder/build logs, `source-identities.json` with APK/trace hashes,
and `restore-diagnostic.log`.
The init script and tracing dependencies are not production changes.

## Evidence and priority

The physical Android 16 diagnostic package isolates synthetic application data
from the user's normal installation. Existing traces establish main-thread
composition/layout spikes in Library, Settings and File Picker. Debug builds,
JIT, first-use rendering, and tracing overhead limit absolute timing claims.
See [completed investigation](app-ui-performance-2026-10-08.md).

| Area | Current evidence | Proposed change | Material trade-off |
| --- | --- | --- | --- |
| Settings | `SettingsComposeBridge.kt:181–263` puts a whole section into each lazy item; the section contains an eager Column. A prior physical trace includes 34.451 ms urgent prefetch, of which 17.516 ms is composition. | Make section headings and individual preferences lazy items with stable keys and distinct content types. Preserve the grouped rounded-card appearance by drawing matching top/middle/bottom row backgrounds. | Careful treatment of rounded corners, ripple clipping, spacing, heading semantics and scroll restoration. No preference is removed. Composition traces must first establish the expensive section. |
| Config presentation | `ConfigComposeBridge.kt:407–435` uses an eager scrolling Column for each pager destination; Display and Controls build many off-screen preferences. | Apply the row-level lazy model to long Config forms after Settings establishes the design. Share the small section presentation primitive where it actually fits both surfaces. | Text drafts, focus, IME, conditional rows and destination scroll state must survive disposal. This is a larger change than Settings and needs its own validation. |
| Config resource discovery | `ConfigActivity.java:449–451` scans soundbanks/skins and parses shader files before installing the form, including when only Basic is opened. | Discover optional resources on a lifecycle-owned worker, preferably when the relevant destination is requested. Preserve persisted selections while loading; publish only current results. | An explicit loading state for option lists; results may arrive after the form. Critical preset recovery, installed identity and persistence locks must retain their contracts. |
| Config repeated metadata | Every `updateForm` calls `createUiState`; `ConfigActivity.java:2178,2183–2197` reads/parses the installed manifest again to derive timing-transform compatibility. | Derive this capability once from the opened artifact identity and refresh only on a known artifact change. Keep draft changes separate from artifact metadata. | A stale-artifact contract must be explicit; retain installed-write identity checks. Actual latency contribution has not been measured, so this is a source-backed work-reduction candidate. |
| File Picker | `FilePickerCompose.kt:288–295` filters and sorts entries on the main thread on every search/sort change. Directory listing is already asynchronous. | Sort on entry/sort changes, filter that ordered snapshot for search, and move substantial projection to the existing controller worker with generation gating. | Search results become asynchronous. Old directory/query results must never replace current results or enable stale selection. Large-directory measurement is needed; this is not established as the cause of a small-directory first-frame spike. |
| Crash reports | `CrashReportsActivity.java:74–83` loads stored records synchronously, potentially twice when maintenance is already ready. Details reload the repository to locate one record. | Load one current snapshot asynchronously; resolve details on a worker. Keep authoritative storage, incident interpretation and deletion behavior. | Loading state and lifecycle/result ownership. Use the actual retention bounds when measuring; do not invent an unbounded-report workload. |
| Installer artwork | `InstallerComposeBridge.kt:228–230` decodes an entire bitmap file during composition. | Decode asynchronously for the displayed target size, keyed to the current artifact identity, with a temporary placeholder. | The image can arrive after the text. Large-image sampling must preserve image quality and avoid stale images after a state change. Ordinary small icons may offer little gain. |
| Library | New composition traces distinguish row/icon presentation work, header work and layout. Attribution is still being evaluated. | First reduce measured unnecessary composition/layout work while preserving appearance. Consider replacing non-interactive decorative Card internals only if paired measurements show a benefit. | Material Card semantics, clipping and theme behavior must be preserved if replaced. Inclusive slice timings are not independent costs and must not be added together. |

## Recommended sequence

1. Finish composition attribution for Settings, Library and Picker; inspect
   Config opening and its optional-resource discovery separately.
2. Implement and compare **Settings row-level laziness** first. It is the
   smallest useful experiment for the shared preference design, and can retain
   today's visual grouping. Compare rows composed per frame, main-thread CPU,
   deadline misses and real screenshots. Reject a change that only moves work
   elsewhere without improving responsiveness.
3. If that design succeeds, apply it to long Config destinations with explicit
   draft/focus/IME/restore coverage. Move optional Config resource discovery
   off the first-interactive path as a separate change.
4. Address demonstrated main-thread file/decode work in Picker, reports and
   Installer. Keep each operation's owner and cancellation rules local; do not
   introduce a general cache, job framework or new navigation architecture.

The expected improvement is fewer off-screen preferences built at once and
less file work blocking input. Percent improvements remain unknown until A/B
measurement. Changing Compose/Room versions, replacing the app backend, or
removing visual effects has no supporting evidence at this point.

For Config, the central form draft already belongs to the activity, but choice,
number, slider and font dialog state can belong to individual rows. Before
making them disposable lazy rows, move the active dialog and its draft to the
screen owner, identified by the preference field. This needs one active-dialog
state, not a map of all field drafts or per-keystroke persistence. Keep small
highlighted preset cards eager where their bounded border/elevation grouping
is simpler than splitting them. Shared section code should own spacing and
grouped backgrounds; it should not merge every preference into a large generic
schema.

Optional discovery should default to one background read per editor session.
Destination-specific loading adds complexity and should be adopted only if a
resource fixture demonstrates material additional benefit. Publish shader
selection against the current form rather than mutable params captured by the
worker. Existing config recovery and preset/installed-identity transactions
are separate responsibilities and should not be moved merely to optimize lists.

Crash report work has lower priority based on source evidence: normal store policies keep 64
records per evidence type for 30 days; journal pruning preserves unacknowledged
or unreadable entries, so it is not a hard aggregate bound. Installer artwork
uses 40/56 dp slots, which makes bounded decoding plausible for unusually large
images, but common small icons may not benefit materially. Neither surface has
received a physical opening/interaction benchmark in this follow-up.

## Acceptance criteria

- Measure the same APK variant, fixture, interaction and tracing configuration
  before and after; retain source/APK identities and state checks.
- Preserve every setting, selection, action, persistence contract and localized
  label. Guest compatibility and renderer/audio behavior are not changed.
- Inspect actual renders for grouped corners, touch/ripple clipping, theme,
  readable spacing, accessibility headings and long text. Exercise large text
  and compact landscape where the changed layout makes them relevant.
- Test Config draft/focus/IME and destination restoration before adopting the
  shared layout there; test stale async result and lifecycle paths for workers.
- Record app CPU/composition and deadline classification separately. Do not
  equate traced debug timing or all FrameTimeline jank categories with release
  FPS improvement.
- Keep diagnostic tracing dependencies local to the ignored init script.
  Local commits do not authorize pushing, merging or publishing PR 158.
