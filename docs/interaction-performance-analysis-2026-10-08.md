# Interaction performance and code-quality analysis

Analysis requested after accepting the Settings CPU trade-off. Scope: main-home
tab taps/swipes, Library scroll/search/clear, Library-to-Config navigation,
Config destination construction, scroll and option-edit architecture. MIDlet
execution remains excluded. No production implementation is adopted here.

The subsequent [solution decisions](interaction-performance-design-2026-10-08.md)
compare lifetime policies, refine Config comparison semantics with a bounded JVM
probe, and specify the preferred designs and their acceptance conditions.

Source baseline is `6ac02c10568e0ef9054987f4e789bef386aa7f0a` on
`fix/library-navigation-smoothness`. Three parallel investigations cover home
navigation, Library projection/presentation and Config ownership. Their findings
are consolidated here rather than treated as independent fixes to apply blindly.

## Physical evidence

Nine new 12-second USB recordings use the isolated diagnostic package and the
existing traced APK, SHA-256
`9fc2334f4e7240d571401fc51284ce6127cbb02b3f888d9b9780b872a235020d`.
That APK is based on `19b5ffd4c`; inspected `applist`, `librarydb`, `config`,
PagerTabNavigation and GlassSystemBarScrim production sources are unchanged
through `6ac02c105`. Settings changes are outside these recorded scenarios.
The same POCO Android 16 device uses Indonesian and the retained synthetic
1,000-app Library. No guest is launched.

Source investigations and trace analysis run concurrently. Input workloads on
the one physical device run sequentially, with device-side pacing. Foreground,
hierarchy, intended content and APK identity are retained before/after each
recording; hierarchy reads occur outside the recording window. A genuine clear
recording explicitly requires query `0999` before input and verifies empty,
unfocused search with full results afterward.

| Interaction | Observed bounded wall / main Running CPU | Attribution and implication |
| --- | --- | --- |
| Home tab taps/swipes, return to Apps | 164.023 / 161.109 ms; later 136.449 / 133.200 ms | Header and ten visible rows reconstruct on repeated returns. Exact frames are Dropped Frame and App Deadline Missed respectively. Class verification is negligible in those two callbacks. Prioritize page construction lifetime. |
| Home navigation, independent layout | 118.308 / 118.248 ms | Another header/ten-row construction lies outside a closed callback. It is a real UI-thread interval, without an assigned frame-jank class. |
| Library scroll | 40.745 / 39.934 ms | Animation/navigation construction is material, with one new row. Exact frame: App Deadline Missed + Buffer Stuffing. Retaining page content alone will not resolve this separate chrome event. |
| Search typing `0→09→099→0999` | 54.787 / 52.950 ms | Recomposition/layout and result/header presentation dominate the bounded callback; exact App Deadline Missed. Worker filter/sort method cost is not separately recorded. |
| Clear `0999`, then Back | 51.295 / 49.893 ms | Layout 31.030 ms inclusive, recomposition 15.976 ms and applyChanges 8.180 ms overlap. Exact App Deadline Missed. Results and focus restore correctly. |
| Library context-menu Settings → Config | 133.312 / 120.988 ms | Includes first-use class/vector and form construction. Activity create is 35.956 / 35.237 ms; initial resume 10.372 / 9.807 ms. These aggregates do not identify optional file discovery as the dominant leaf. |
| Config Controls entry | 64.085 / 64.014 ms outside the frame callback | Eager composition/layout builds off-screen groups. A frame-only summary would miss the main construction stall. The longest closed frame, 26.043 / 4.360 ms, instead mostly waits in renderer synchronization. |
| Config System entry | 58.092 / 56.908 ms | Eager construction/layout; little class verification. Exact frame is Buffer Stuffing / Late Present with on-time finish, not relabeled App Deadline Missed. |
| Config Controls/System scrolling after construction | Peak Running CPU 10.473–13.636 ms during forward/reverse touches | These sampled scroll frames are substantially smaller than destination construction. This is not a complete fling distribution. |
| Config → Library Back | Config pause 23.804 / 18.127 ms; destroy 22.616 / 22.073 ms | Lifecycle and composition disposal are material. No method attribution proves that persistence accounts for the entire pause, or predicts explicit Save/Apply latency. |

Each scenario has one recording. First Collections entry includes about 20 ms
exclusive class-verification CPU and is kept separate from repeated Apps return.
The More page also constructs seven actions in one lazy item inside a bounded
31.031 ms prefetch interval. Nested inclusive durations must not be summed.

The earlier recording named `search-clear-back` starts with an empty query, so
it supports focus/Back analysis only. It is excluded as evidence for nonempty
clear; the separate guarded `query-clear` recording supplies that evidence.

These are debug/composition-tracing attribution results, **not release FPS,
an A/B improvement, or predicted savings**. The processor is Perfetto v58.2.
Records have 8–11 conflicting composition descriptors and 36 unsupported ftrace
setup errors. Unclosed outer/final callbacks are excluded; bounded inner and
independent main-thread work remain explicit. Temperature, background load and
GPU state are not continuously controlled. Long callbacks, renderer waits and
FrameTimeline jank classes are distinct evidence.

## Recommended implementation packages

### 1. Bound the lifetime of the three home destinations

The largest recurring measured cost is Apps construction on tab return.
`LibraryComposeBridge.kt:1209` hosts destinations in HorizontalPager while
viewport/query/chrome state already live outside disposable page content.
Preserving scroll state therefore does not preserve the rendered subtree.

Test bounded retention using the current pager API, initially
`beyondViewportPageCount = 2` for the three destinations. The parameter is
verified in the locally resolved Foundation API; no navigation framework,
dependency upgrade, manual visited-page registry or second back stack is needed.
Keep the existing adjacent/distant tap policy and manual swipes.

Trade-off: retaining pages increases memory and can move first-use work into
opening or the first transition. Compare cold opening, warm returns, allocations,
inactive-page update CPU and memory before choosing the retention policy. Retain
settled-page ownership of side effects; inactive pages must not retain active
keyboard/controller handlers, focus or modal overlays. Reject a change that
merely relocates an unacceptable stall into startup. Do not apply the same
all-pages retention mechanically to the much larger five-destination Config.

### 2. Keep scroll chrome composed; animate rendering and gate interaction

`LibraryComposeBridge.kt:1096,1138` disposes navigation/FAB through
AnimatedVisibility. Both earlier and fresh scroll traces identify construction
work in these small subtrees. Keep applicable chrome mounted and use render-phase
alpha/translation/scale with the existing easing, duration and appearance.
Visibility remains owned by the existing viewport state. Fully hidden controls
must lose pointer, keyboard/controller focus and accessibility actions.

Preserve the fixed trailing inset, final-row reachability, selection-toolbar and
rail/IME behavior. At `GlassSystemBarScrim.kt:48`, retain the alpha State and read
it in graphicsLayer; remember the theme-keyed gradient rather than recreate it
during composition on every fade tick. The fresh scroll trace contains 35 scrim
compositions totaling 4.923 ms exclusive CPU; this is a small recurring cost,
not the dominant stall. Compare hidden memory and draw/GPU cost, not just CPU.

### 3. Make long Config forms lazy, with one screen-owned active editor

`ConfigComposeBridge.kt:407–435` and `ConfigPreferenceComponents.kt:51–87` eagerly
construct full page/group Columns. Display attribution from the previous study
and fresh Controls/System attribution make this a high-confidence target.
Use stable field keys and row-level laziness, retaining group appearance and
destination scroll state. More's seven-action lazy item (`LibraryComposeBridge.kt:2621`)
is a smaller application of the same measured construction issue.

Before disposing preference rows, hoist one active choice/number/font/slider
editor request and its draft to the Config screen owner. Render dialogs outside
lazy rows; keep currentForm authoritative for confirmed changes. Preserve partial
invalid numeric text, Cancel/Back/confirmation, IME, conditional controls and
native picker returns. Avoid a map of every field draft or a generic nullable
form schema. Preserve bounded special cards where their decoration benefits
from a single surface. Rotation restoration of currently unsaved dialog drafts
is a separate UX decision; do not silently claim it is already guaranteed.

Measure entry plus repeat scroll, maximum and P95 CPU, active CPU, memory and
frame classification. The Settings result demonstrates why peak reduction must
be reported together with recurring CPU cost, rather than assume laziness is
universally cheaper.

### 4. Stop rebuilding Config semantics and metadata for each confirmed edit

`ConfigActivity.java:2263–2270` routes accepted edits through createUiState.
`ProfileConfigMatcher.kt:30–55` uses three Gson round trips plus two JSON trees
per effective comparison. Built-in status and dirty comparison can both run,
giving up to six round trips plus four trees for one edit. Derive the effective
configuration once per confirmed draft revision and compare the explicit
persisted/built-in baselines through one normalized equality contract.

A bounded first design can reuse normalized baseline representations and stop
redundant effective copies; a typed copy/comparison should only replace JSON
after equivalence checks cover every persisted field, defaults, shader arrays
and normalized system properties. Avoid introducing field-maintenance drift or
collapsing params, currentForm, persistedBaseline and built-in baseline: those
roles are distinct. Serialization remains appropriate for file persistence.

`createUiState` also rebuilds profile templates, copies option lists and reopens
the installed manifest (`ConfigActivity.java:2144–2196`, `ConfigUiState.java:132–148`).
Build read-only option metadata when discovery changes; derive timing eligibility
once per bound installed-artifact identity. Shallow unmodifiable lists do not
make ShaderInfo.values immutable. Async publication must use current selection
and reject stale workdir/artifact results. Controller capability must still react
to device add/remove. Measure these host methods before promising percentages.

### 5. Separate Library source ordering from query ranking

`librarydb/LibraryListProjection.kt:18–119` normalizes fields, allocates ranked
rows and sorts matches using a locale comparator per query. Query projection
already runs on Default/mapLatest; typing does not issue a Room search query.
Prepare one ordered source snapshot per source/sort/quick-view/locale revision,
then scan it into the six existing rank buckets. Stable bucket insertion keeps
the exact comparator order within each rank; blank search can reuse the snapshot.

Keep immediate text feedback and latest-only publication. Add cooperative
cancellation to genuinely long loops; cancellation of publication alone does
not stop a synchronous sort already consuming CPU. Normalize source strings
once only if timing/allocation measurements justify the retained memory.
Preserve non-ASCII matching, date nulls, descending semantics and database-id
tie breaks. Compare exact result ID sequences to the current algorithm.

The typing/clear traces prove costly main-thread result presentation, not the
sort's exclusive cost. Also inspect redundant already-at-top scroll requests
and result/header rebuilds before treating background sorting as the complete
search fix. Separate collection-only invalidation from Apps source revisions
where dependency evidence supports it; membership and metadata refresh must
still update the correct routes.

## Deferred candidates and common contracts

Optional skin/soundbank/shader discovery can move to a lifecycle-owned worker
after preserving critical initial identity/recovery. Initial onCreate/onResume
appears to load/inspect twice; distinguish initial load from genuine external
returns rather than weaken refresh. Decorative icon Card/BoxWithConstraints
simplification needs list/grid-specific measurement. Explicit lazy content types
and smaller header eligibility scopes are lower-priority experiments. No evidence
requires removing animations, reducing artwork quality or replacing the pager.

Keep preset authority, installed appId/workdir fences, recovery and durable save
contracts. Explicit Save/Apply requires separate transaction timing; the no-edit
Back trace does not authorize asynchronously weakening pause-save durability.
Validate tab reversal/rapid taps, workdir and artifact replacement, Library
viewport/query/selection restoration, hidden control reachability, nonempty
search clear/IME Back, and dialog disposal/confirm/cancel with the existing tests.
Use physical CPU/latency/memory and native renders together for each candidate.

## Handoff and artifact state

Recommendation order: investigate home page lifetime and scroll chrome first,
then Config lazy/editor ownership; measure Config semantic/metadata rebuilding
and Library query/presentation in parallel as independent work packages.
Production changes still require the next implementation authorization.

Raw traces, hashes, configs, guard XML/focus, SQL and the six bounded source/trace
reports are retained under ignored `build/perf-interactions`. The diagnostic APK
is restored to the accepted Settings candidate SHA-256
`5534632824ce78b3aedd57a1e2198036d590739022fdc1a0bed262a8a62d7fd5` and stopped.
The normal application is returned to the foreground and retains its original
2026-10-08 13:22:09 installation timestamp. Production code, resources and tests
are unchanged; the unrelated qualifier script is preserved. No push or merge.
