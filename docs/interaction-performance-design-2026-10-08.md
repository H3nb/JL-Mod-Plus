# Interaction performance solution decisions

**Historical, pre-trial design candidates.** The
[trial and acceptance record](interaction-performance-trials-2026-10-08.md)
is authoritative for the outcome: bounded home retention and selected ordering/
Config-comparison work were accepted, while row-lazy Config forms and the
permanently retained navigation/FAB chrome trial were rejected. The
`Selected design` table below records choices **proposed for measurement**,
not the currently adopted architecture; do not implement rejected proposals
based on this earlier document alone.

Analysis only, following the user's request to select the best solutions. Source
is unchanged at `1c16c1f0e`; measured evidence and its limitations are in
[interaction performance analysis](interaction-performance-analysis-2026-10-08.md).
This review adds source/design analysis, not another physical recording or an
implemented improvement. MIDlet execution remains outside scope.

## Preferred direction

Reduce repeated construction and transformations at their existing owners.
Preserve the pager, Material appearance, animation and durable configuration
semantics. Choose different lifetimes for small reusable navigation controls,
three home pages, long form rows, active editors and catalog snapshots.
Retaining every UI subtree or introducing one global cache would conflate these
different boundaries.

| Area | Selected design | Confidence / material trade-off |
| --- | --- | --- |
| Home tab return | Existing pager with bounded three-page retention, subject to inactive-interaction and startup qualification | Recurring construction is measured. Memory, cold-entry work and hidden-page updates increase; the final retention policy remains an experiment. |
| Library scroll chrome | Keep the applicable navigation/FAB composed, animate render properties, explicitly gate hidden input | Construction during scroll is measured. Retains a small subtree and layer resources; pointer pass-through and stable insets require validation. |
| Config tab entry and scrolling | Row-level lazy forms, stable field keys, one screen-owned form editor | Eager off-screen construction is measured. Trades entry peaks against recurring scroll composition; editor drafts must survive row disposal. |
| Config option changes | Compute effective configuration once per UI projection, share normalized comparison work and separate discovered metadata | Repeated work is source-proven. Method cost is unmeasured; serializer equivalence and metadata invalidation determine correctness. |
| Search | Avoid redundant viewport work; separate source ordering from relevance projection in the existing ViewModel | Main presentation spikes are measured; worker sort cost is unmeasured. One ordered snapshot retains references and must respect generation/locale invalidation. |

These are preferred designs for comparative implementation. The current debug
traces do not establish that a particular setting is best on all devices, or
predict a percentage saving.

## Home: one lifetime policy, explicit interaction ownership

The current pager disposes pages while native viewport/query state survives.
Returning to Apps nevertheless rebuilds its header and ten visible rows, with
recurring bounded callbacks of 164/136 ms. `beyondViewportPageCount = 2` is the
simplest complete retention candidate for three fixed pages. Nested lists remain
lazy; it does not construct all catalog entries. Count 1 still disposes Apps
while on More and therefore does not cover the measured distant return.

Before testing retention, resolve the inactive-page interaction boundary:

- Keep indication from `currentPage` and committed tab effects from
  `settledPage`. Preserve reversed gestures, adjacent animation/distant jumps,
  workdir-scoped query/viewport and Collection routing.
- Existing inactive semantics exclusion is insufficient for focus, keyboard
  input and separate-window popups/dialogs. Audit Apps/Collection sort menus and
  Collection create/rename/delete dialogs; hidden pages must not gain focus,
  expose modals or execute user actions. Preserve characterized dismissal/draft
  behavior rather than add a new clearing policy accidentally.
- Retain required Collection deletion/workdir reconciliation and pending
  operation completion. Do not suspend every collector or suppress query
  restoration just because the rendered page is inactive. Measure expensive
  hidden presentation work separately from required identity maintenance.

Compare the default/count-0 policy against count 2, with count 1 only if useful
to distinguish memory and neighboring-page costs. Keep count 0 if count 2 merely
transfers comparable stalling to cold opening or inactive updates. Then improve
the measured construction itself. A delayed 0-to-2 switch adds a new timing/state
policy and another work burst; a visited-page registry, manual composition cache
or new back stack is not justified by present evidence. Saveable state alone
preserves position but cannot preserve disposed composition.

## Scroll chrome: retain structure, animate pixels

Keep navigation/FAB mounted across scroll hide/reveal, within their existing
route/rail/selection/IME ownership conditions. Preserve the current 220 ms
animation, easing, translation/scale, dimensions and trailing navigation inset.
Read animated state inside `graphicsLayer`, rather than in composition. This
follows the platform's [deferred state-read guidance](https://developer.android.com/develop/ui/compose/performance/phases).

Fully hidden controls must lose pointer actions, keyboard/controller focus and
accessibility actions. Also test that their old bounds let touches reach the
list beneath them: disabled clicks and alpha zero do not alone establish
pass-through. If Material hit testing still blocks content, use a narrow
settled-hidden placement gate that retains measurement/composition but does not
place the interactive child; avoid a custom pointer-dispatch framework.
Characterize transition eligibility before choosing its gate.

The decorative status-bar scrim can defer alpha reads and reuse a theme-keyed
gradient separately. Its measured cost is small. Chrome qualification can
succeed independently of pager retention; measure the two changes separately
before combining them.

## Config: lazy display with an editor outside disposable rows

Use one LazyColumn per Config destination, with viewport state keyed by
destination at the screen owner. Flatten ordinary long sections into heading and
control items with stable, namespaced field keys and suitable content types.
Preserve conditional visibility, maximum width, spacing, rounded group appearance
and heading semantics. Keep genuinely bounded special/highlighted cards together
when their shared decoration or coupled controls benefit from one surface.
Making an entire long section one lazy item leaves the measured stall intact.

Hoist the form's active choice/number/font/slider, screen-preset and overlay
parameter editors to ConfigScreen before making their launching rows disposable.
A small typed request owns field identity and initial draft; render the dialog
outside the lazy item. Keep nested custom-resolution text inside the screen-owned
preset editor. Confirmation applies
the edited field to the latest authoritative `state.form` through existing
events. Do not retain an entire old form in the request or overwrite a later
host update with it. Invalid partial numeric text remains local until confirm;
Cancel/Back must discard it without modifying currentForm.

Reuse existing controller-owned color/encoding/gamepad and native-editor
boundaries. This does not require one generic app-wide modal engine, a draft map
for every field, a schema-driven preference framework or a new ViewModel. Check
editor disposal, host refresh, conditional controls, IME, Back and native-picker
returns. Rotation restoration of currently unsaved dialog text is a distinct UX
decision, not a performance prerequisite or an existing guarantee.

Controls entry has a 64 ms construction/layout interval outside the closed frame
callback; System entry is about 58 ms. Their sampled scrolling after construction
is smaller. The appropriate target is entry construction plus total repeated
scroll cost, not treating every renderer wait as CPU saved by laziness. Retaining
all five eager Config pages would amplify the wrong lifetime boundary.

## Config computation and search

For Config, prefer removing duplicate transformations within `createUiState`
before adding a persistent cache. Compute one effective model only when a
comparison is needed, produce its canonical normalized comparison representation
once, and reuse it for persisted/built-in comparisons. Keep the existing Gson
round-trip canonicalization and normalization contract at this stage. In the
two-comparison path, sharing effective and left comparison work can reduce six
round trips/four comparison trees to four round trips/three trees; this counts
operations, not measured latency or a percentage performance gain.

A bounded JVM scratch probe used the built production ProfileModel, ShaderInfo,
ConfigFormState and pinned Gson 2.14.0. It found a real equivalence difference:
opaque Controller JSON containing programmatically constructed Long/BigInteger
values `9007199254740992` and `9007199254740993` compares equal after the current
round trip, but distinct in a direct tree. Parsed-number representation differs
from those original numeric objects. Null/default properties, normalization,
transient directories, null-versus-empty lists and changed shader values matched
in the sampled cases. These probes characterize a boundary; they are not the
full regression suite or device latency evidence. Raw output is retained at
`build/perf-interactions/gson-comparison-probe.txt`, with source in
`GsonComparisonProbe.java` in that directory. The scratch formula reproduces
the current matcher; it does not run its project JUnit tests. SparseIntArray
runtime behavior is not exercised by SDK stubs.

Consequently, direct tree comparison needs representation-equivalence work or an
explicit semantic correction before adoption. Handwritten ProfileModel equality
and copying also duplicate hidden persisted fields, custom keys, key mappings
and opaque Controller data. Neither is the simplest first performance fix.
Keep params, currentForm, persistedBaseline and built-in baseline distinct;
configuration equality must never establish LINKED ownership. Leave durable
serialization and pause-save transactions at their current authority boundary.

Project read-only option/template metadata when discovery changes, rather than
copying every list or rebuilding every template after a field edit. Keep the
existing producer authoritative, publish one snapshot, and account for mutable
ShaderInfo.values instead of declaring a shallow wrapper immutable. Timing
capability can be derived per bound installed-artifact identity after measuring
the repeated manifest read; invalidate on actual reload/replacement. Keep device
hotplug reactive. Moving optional discovery to a worker comes after measuring
its leaf cost and preserving load/recovery/native-editor refresh behavior.

For search, preserve header reveal and first-result reset, but avoid
`scrollToItem(0)` when the active list/grid is already at index/offset zero.
Keep query, applied-result state, IME Back and metadata viewport restoration
ownership intact. This removes a concrete redundant request; whether it lowers
the measured 55/51 ms typing/clear spikes still needs A/B evidence.

The preferred worker design has two stages in the existing LibraryViewModel:

1. Order immutable authoritative app rows on Default when generation/workdir,
   app content, effective sort mode or locale changes. Retain one completed
   ordered snapshot, full available IDs and matching repository envelope.
   All/Favorites share their selected order; recent views keep timestamp/id
   descending behavior. Match locale at the host configuration boundary.
2. Scan that ordered snapshot for each normalized query, applying quick-view
   eligibility and the existing first-match relevance rule. Append matches to
   six buckets and concatenate in relevance order. Within each bucket the
   comparator order is already preserved. Blank All can reuse the ordered list.

This replaces per-query matching sort with linear scanning and output, retaining
initial O(N log N) source ordering. Preserve ROOT case matching, selected-locale
SECONDARY collation, ascending secondary/id ties even for descending primary,
unknown-date placement, recent descending-id ties and literal wildcard matching.
Compare exact result IDs against the current algorithm at 100/1k/5k scales.
One very selective query after a large source update can favor the current
filter-then-sort approach; measure broad and narrow workloads before adoption.
Defer a stored normalized-string index until scan allocations justify its memory.

Preserve the broad sourceRevision used to refresh Collection members. Reuse
ordering on collection-only changes while forwarding the newer matching
envelope/revision; do not pair old ordered rows with a new generation's metadata
or derive full available IDs from filtered results. Opening/error/indexing must
supersede old Ready results. Keep mapLatest and latest-only publication; add
cooperative cancellation in scan chunks and before/after source sorting.
Synchronous sort remains a cancellation gap unless measured obsolete sort CPU
justifies additional checks. Current cancellation behavior is not proof of stale
result publication.

Do not add debounce, a multi-query cache, a generic Main/Collection projection
engine or a Room search/schema migration for this evidence. Main and Collection
sorting currently have distinct rules. Worker projection already runs on Default;
its unmeasured cost cannot explain main layout directly. Measure worker order/
scan and input-to-ready latency separately from host result mapping/header/row
presentation. Preserve existing per-row reuse and generation fences.

## Qualification and implementation order

First qualify the home lifetime and scroll-chrome candidates independently.
Config editor ownership is a prerequisite for row laziness. Config semantic
projection and Library ordering/presentation can be developed and measured as
separate bounded packages alongside those UI changes; serialize input workloads
on the single physical phone.

Reuse the existing viewport/navigation, Collection identity, search/IME, Config
form/preset, dialog and screenshot tests. Add coverage only for newly extended
inactive lifetimes, editor disposal or changed algorithms that existing tests do
not exercise. Test exact result/config equivalence at the lowest effective layer;
test real focus, hit testing and Android lifecycle at their integration boundary.

Use matched repeated A/B scenarios with the same fixture, variant and device.
Separate cold opening, first-use routes, warmed adjacent/distant return, typing,
real nonempty clear, tab reversal and long-form scroll. Report callback and
out-of-frame main CPU, P95/tail latency, total interaction CPU, exact FrameTimeline
classes, resident memory/allocation and hidden-page idle/update work. Do not sum
overlapping inclusive slices or equate a renderer wait to a CPU bottleneck.

Accept a candidate when its target workload improves reproducibly, its required
behavior/appearance survives and memory/startup/recurring CPU trade-offs are
explicit. Small extra CPU can be acceptable under the user's preference; an
equally severe stall moved elsewhere is not an established improvement.
Recheck practical performance in a build close to production without composition
tracing before a high-performance release claim. Do not adopt an arbitrary
unmeasured percentage target, assume one refresh-rate budget for every frame,
or infer durable-save cost from the earlier no-edit Back capture.

No production code, resources or tests change in this analysis. No new device
installation, build, push or merge is part of this decision review.
