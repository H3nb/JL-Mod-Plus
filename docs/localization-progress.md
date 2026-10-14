# Localization implementation checkpoint — PR 169

Snapshot: 14 October 2026. Continue only on `localization/ai-matrix-workflow`;
base `alpha`. The PR remains Draft and is not ready to merge.

## Earlier accepted work

- Library, Collections, file picker, and Updates: 174 canonical resources
  reviewed across all 39 existing catalogs; complete in 38 locales. Santali
  has 11 bounded additions and 163 explicitly unresolved entries in this batch.
- Common actions, About, storage recovery, diagnostic labels, screenshots,
  and log states: 83 resources reviewed across 28 locales. Santali has one
  formatter-index correction, 30 retained isolated labels, and 52 unresolved
  entries, including nine stale existing messages. The other 27 catalogs in
  this group have complete batch coverage.
- Eleven previously near-complete catalogs received all 54 remaining resources
  outside the Library/files/Updates batch. These locales also had a focused
  30-key review of consequential configuration/runtime messages; retention
  summaries were corrected and Finnish controller identity terminology refined.
- The 12 October checkpoint added **7,052** localized declarations and revised
  **327** existing declarations relative to `0064a004`.
- Canonical English clarifies that only bundled components replace corresponding
  existing app data, and that Revert & remove **undoes** the last confirmed edit.
- The small reviewed-batch applier preserves unrelated XML bytes, comments and
  attributes; checks source/target hashes, identities, placeholders and review
  selection; rejects unsupported markup/reference edits; and writes atomically.
  Android XML remains the sole translation catalog.

## Configuration and controls checkpoint

The 269-resource configuration/physical and virtual controls/layouts/profiles
selection was processed across all 39 catalogs. This checkpoint adds **6,568**
localized declarations and revises **389** existing declarations relative to
`15fa0a0`. Canonical English and localization tooling are unchanged.

- All 269 resources have complete batch coverage and language/semantic review
  in 35 locales. Correct existing translations were preserved, including
  consequential profile-linkage, retention, and partial-save messages.
- `kk` has 168 accepted resources and 101 unresolved; `or` and `si` each have
  146 accepted resources and 123 unresolved. Accepted labels and simple
  statements do not imply certification of their remaining technical prose.
- `sat` retains all 264 missing resources and five existing declarations
  requiring review. No Santali changes were accepted in this checkpoint.
- Corrected stale FPS/speed/maximum-refresh semantics, neutral calibration
  instructions, diagnostic status and physical input scope, asynchronous frame
  presentation, scale limits/overflow, timing sources/installation conditions,
  keypad-shape exclusions, and shader-dependent controls.
- Cross-language review caught direct-proportional delay wording in several
  languages; corrected it to scaling according to speed. Arabic compound
  profile grammar and residual Persian inflection received another vertical
  pass after terminology changes. Numeric keypad, controller signature versus
  profile, and built-in profile names remain consistent within each locale.
- New property-count plurals use locale-appropriate categories and preserve
  count arguments. Chinese and Portuguese regional variants remain distinct.
- Restored canonical `formatted="false"` on the literal-percent
  `config_help_scale_ratio` declaration in eleven legacy catalogs after AAPT2
  exposed their lost formatting attribute. The source already has this contract;
  its caller uses the text without format arguments. This targeted direct XML
  correction does not change the English source or translation architecture.

Exact unresolved controls identities and reasons are recorded in
[Unresolved configuration and controls language review](localization-unresolved-controls.md).
Temporary matrices/proposals/review notes remain outside the committed catalog.

## Coverage and quality

The inventory has 977 canonical translatable resources and 39 target catalogs.
Missing localized declarations decreased from 1,411 to **466** after
an additional 197 Santali labels and simple UI/status strings on 14 October (1,492 before technical recovery,
2,111 before deferred-language recovery,
6,152 before settings/runtime, 8,758 before Memory Editor, 11,502 before
installer, 18,070 before controls, and 25,122 before 12 October).
Thirty-eight catalogs cover all 977 resources. Santali is the remaining
incomplete catalog (466 missing), with 76 recorded deferred existing cases.
**Resource presence does not establish language certification.** The remaining
existing-language uncertainty is documented separately from missing declarations.
Local app/resource, representative UI and cumulative remote CI validation pass
for the reviewed resource checkpoint recorded below. Santali completion remains
unfinished.

The [current language-review record](localization-language-review.md) lists every
remaining missing identity and the active deferred existing cases. Earlier
per-feature counts below are historical snapshots, superseded by that record.

Santali remains a material blocker. The current consolidated identity lists,
rather than historical per-feature totals, define the unfinished work. CLDR,
script rendering and plausible machine output do not establish trustworthy
technical prose. The current recovery corrects the stale About, Help and deletion
consequences; other stop warnings, storage recovery, diagnostics, controller,
installer, profile and Memory Editor messages still require competent whole-text
review. Continue achievable recovery without counting unresolved cases as
accepted translations or silently dropping Santali from the requested scope.

## Installer and bulk-installation checkpoint

The 116-resource installer/bulk-installation/reconversion selection, including
18 plurals and four styled descriptor labels, was processed across 39 catalogs.
This checkpoint adds **2,744** declarations and revises **83** existing
declarations relative to `5697f31`. Canonical English, application code, and
localization tooling are unchanged.

- The batch is complete and reviewed in 35 locales. Kazakh has 68 accepted
  identities and 48 unresolved; Odia and Sinhala each have 67 accepted and 49
  unresolved. Reliable labels/count messages do not certify technical prose.
- Santali retains three reliable isolated labels; 104 missing declarations and
  nine existing entries remain explicitly unresolved. No new Santali text was
  fabricated. See [exact installer gaps](localization-unresolved-installer.md).
- Corrected conversions mislabeled as installation or reading JAD, metadata
  vendor mislabeled as seller/owner/author, and incomplete same-version/possible
  variant and ambiguous installed-app classification. Clear selection no longer
  suggests deleting apps/files in the affected catalogs.
- Corrected omitted downgrade/data-compatibility risk and overbroad all-data-save
  promises. Preserved ordinary replacement's settings/save-data retention,
  separate-copy preservation, installed-but-restore-failed outcomes, and
  saved-files-but-Library-update-failed recovery before retry.
- Preserved single cancellation at the next safe point versus bulk cancellation
  after the current app. Processed/total counts do not claim successful installs;
  warning/omitted-detail plurals distinguish source Java class files from apps,
  instances, and diagnostics not shown. Feature failure remains a possibility.
- Reconfirmed remote JAR restrictions apply to bulk installation, not all single
  installs. Retained raw names, versions, URI schemes, JAR paths, and exact
  `MIDlet-Jar-URL` notation. Regional vocabulary and plural grammar remain distinct.
- The existing applier handles supported plain-text changes. Direct descriptor
  label edits preserve bold markup/attributes and explicit trailing separation;
  no catalog is regenerated and no temporary proposals are committed.

There are 259 explicitly unresolved installer review cases: 247 missing and 12
existing declarations. These are tracked separately from earlier feature batches;
per-batch review counts must not be treated as a deduplicated whole-catalog total.

## Memory Editor checkpoint

The 109-resource `memory_editor_` selection, including six plurals, was processed
across all 39 catalogs. This checkpoint adds **2,606** localized declarations and
revises **57** existing declarations relative to `c554868`. Canonical English,
application code, localization scripts/tests, and build dependencies are unchanged.

- Complete batch coverage and semantic/language review in 35 locales. Kazakh,
  Odia, and Sinhala each have 33 accepted labels/simple states/count identities
  and 76 unresolved missing declarations. Santali has 109 unresolved missing
  declarations and no new text accepted. See [exact Memory Editor gaps](localization-unresolved-memory.md).
- Corrected existing group-search hints/help to require values in the same Java
  object, clarified semicolon separation and delta-range predicates, corrected
  Indonesian batch type wording, Finnish Freeze trigger, French identity grammar,
  and Russian MiB units. Retained already correct translations.
- Cross-language review corrected eight new batch notices that incorrectly said
  data types would change; only values with matching types change. Clarified
  arithmetic-sequence/common-difference terminology without requiring positive
  steps, including Japanese, Thai, Vietnamese, Afrikaans and Greek. Czech retry
  wording explicitly refreshes the view.
- Separately reviewed remove/revert and partial outcomes: remove hides results
  without memory writes; revert undoes only the last confirmed edit when the
  current value matches the written value, retaining conflicts. Failed removal
  can follow completed restoration; game-changed values are not overwritten.
- Preserved momentary memory-readback confirmation and all five distinct write
  counters, including the separate failure explanation. Cancellation and partial
  completion do not promise rollback or zero writes. Search Undo remains distinct
  from edits and game saves; Inspector refresh rereads inspected data.
- Retained monitoring/Freeze safety pauses, runtime-bound target identity and
  reference provenance. Untraced source does not assert no source exists, and raw
  field/array/owner IDs and localized reference-kind arguments keep their roles.
- Per-language vertical review and consequential horizontal comparison covered
  every selected identity and relevant plural grammar. This is not independent
  native-speaker certification or rendered Android UI verification.

All 337 explicitly unresolved Memory Editor review cases are missing declarations.
Accepted subsets do not certify the remaining technical or consequential prose.
Every accepted change uses the existing hash-guarded plain-text batch applier;
no catalog is regenerated and no scratch matrix or proposal is committed.

## Settings, performance and runtime checkpoint

The remaining 175 presentation/settings/performance identities and 51 runtime/
diagnostics/miscellaneous identities were processed across all 39 catalogs. The
combined 226-resource selection has four plurals. This checkpoint adds **4,041**
declarations and revises **967** existing declarations relative to `14a23ef`:

| Selection | Identities | Additions | Existing revisions |
| --- | ---: | ---: | ---: |
| Presentation, settings and performance | 175 | 2,908 | 908 |
| Runtime, diagnostics and remaining copy | 51 | 1,133 | 59 |

- Complete batch coverage and semantic/language review in 35 locales. Kazakh has
  26 accepted/200 unresolved identities, Odia 25/201, Sinhala 9/217, and Santali
  0/226. Reliable repeated labels and raw formatter displays do not certify
  technical prose. Exact [settings/runtime gaps](localization-unresolved-settings-runtime.md)
  comprise 710 missing and 134 existing declarations needing competent review.
- Corrected widespread stale synchronous/parallel redraw labels to asynchronous
  presentation, pixel/resolution wording to Android font scaling, forced-fullscreen
  wording to startup without the command bar, and general Audio wording to MIDI.
- Corrected virtual-controls versus keyboard terms, opacity versus transparency,
  pressed/base/outline colors, key-code versus visual layout, added-date versus
  other sorting dates, and backend/shape/default/manual distinctions. Retained
  correct existing text and eight proposed case/spacing/synonym changes.
- Raw screenshot summaries retain virtual resolution before scaling, linear
  filtering and shader processing. Status-bar/cutout summaries retain reciprocal
  side effects, compatible Canvas content and runtime-toolbar scope; resume labels
  retain literal `startApp()` and do not imply skipping initial startup.
- Performance labels distinguish renderer consumption, all MIDlet frame
  publications and physical refresh; configured speed, frame interval percentiles/
  maxima, input/frame queues, submission duration, core equivalents, heap usage
  and thermal severity retain their actual measurement domains. Diagnostic
  abbreviations remain unchanged. See [metric definitions](performance-overlay.md).
- Diagnostic messages retain public exposure on attachment, attach-before-submit,
  permanent local report/generated-bundle deletion, uploaded copies unaffected,
  partial batch deletion and secondary cleanup after primary success. The real
  `JL-Mod Plus Diagnostics` folder name remains literal in every accepted locale.
- Runtime copy preserves another runtime's storage reservation, failed stop before
  replacement, request/list/installation/storage distinctions, callback nesting,
  locally saved diagnostic evidence, and continued app usability.
- All new and revised language was read vertically and consequential messages
  compared horizontally. Regional variants remain distinct. Existing English,
  application code, localization tooling/tests, dependencies and screenshots are
  unchanged; supported changes use the established applier and fresh file hashes.

## Earlier complete-catalog quality extension

The earlier common/core review covered 28 locales. Extended vertical and
consequential review across all 257 earlier Library/core identities in the
remaining eleven previously near-complete catalogs: da, de, es, fi, fr, it, nl,
pt, pt-BR, ru and sv. This adds no declarations and makes **42** material
revisions; all existing plural forms and correct earlier translations remain.

- Corrected nine stale help messages that promised flicker repair and omitted
  normal Java ME event-queue bypass and possible instability. Preserved the
  literal HTML `<BR>` separator; it is escaped string text, not child markup.
- Corrected virtual-controls reset scope, mapping-overlay close versus discarding
  assignments, IME keyboard access, generic action/name-entry wording, French
  license versus permission terms, untranslated German Create, and Danish folder
  terminology. Included-data replacement, collection membership, deletion scope,
  optional restore/recovery and update conditions received a separate reread.
- Cross-feature comparison of the same `MIDlet-Vendor`/`source_vendor` value
  found developer/seller labels. Corrected the Russian Library label in this
  extension; five additional sort/descriptor/Library labels in Russian, Hindi,
  Simplified Chinese and Traditional Chinese use already reviewed provider or
  publisher terminology for the same field. Raw vendor values are unchanged.
  The styled Russian descriptor label retains bold markup and trailing separation.
- One existing Odia Library vendor label remains explicitly unaccepted pending
  competent review, separately from the 226-resource gaps. No guessed replacement
  was submitted. This is documented in the [metadata-field uncertainty](localization-unresolved-settings-runtime.md#additional-existing-metadata-field-uncertainty).

The full checkpoint therefore adds **4,041** declarations and revises **1,014**
existing declarations: 967 in the remaining feature selection, 42 in this
quality extension, and five targeted cross-feature field labels. All canonical
identities have now been processed through feature-group reviews, including the
broader earlier-core pass for the eleven catalogs. This is textual semantic and
language review, not independent native-speaker certification or visual approval.

## Earlier validation evidence

- All 30 focused Python localization tests passed.
- Inventory: no duplicate identities, resource-type mismatches, extra localized
  resources, localized invariant overrides, or clear string argument mismatches.
- Changed declarations have compatible formatter indices/types; plural items
  were separately checked, retaining `other` and language-specific categories.
- Current CLDR cardinal-category data was consulted. Hebrew's extra compatible
  category and Vietnamese invariant fallback do not change their grammar.
- All 40 English/localized strings catalogs passed AAPT2 compilation and a
  resource-only link using `9.4.1-15978811`, matching the project's AGP version.
  This is native resource validation, **not a full application build or UI test**.
- `git diff --check` passed. No screenshot baselines were changed.
- Prior Android CI run [38236621726](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38236621726)
  succeeded on `0064a004` (build/tests, lint, screenshots). It does not validate
  this checkpoint. This intermediate commit uses the user's explicit `[skip ci]`
  exception; final resource state still needs actual full CI evidence.

## Controls checkpoint validation

- All 30 focused Python localization tests passed.
- Inventory: 977 source identities / 39 catalogs / 11,502 missing declarations;
  no duplicates, type mismatches, extra resources, localized invariant overrides,
  or clear string argument mismatches. Changed strings and plurals retain their
  indexed argument/type contracts and valid locale quantity forms.
- All 40 English/localized strings catalogs passed matching-AGP AAPT2
  compilation (`9.4.1-15978811`) and a resource-only link after the diagnosed
  literal-percent attribute correction. This is not a full application build.
- Declaration-level diff confirms exactly 6,568 additions and 389 revisions,
  confined to the selected keys. Source English is byte-for-byte unchanged.
  Supported changes use the existing hash-guarded applier; the percentage key's
  existing attribute discrepancy was handled with a reviewed direct XML patch.
- Diff/whitespace and documentation-link checks passed. No screenshot baselines,
  application code, localization scripts/tests, or build dependencies changed.

Final app build, app unit tests/lint, representative long-text/RTL/narrow UI
verification, and final CI remain pending. The intermediate resource commit uses
`[skip ci]` under the user's explicit exception; the earlier full CI success is
not evidence for these changes. Keep the PR Draft while material scope remains.

## Installer checkpoint validation

- All 30 focused Python localization tests passed.
- Inventory: 977 source identities / 39 catalogs / 8,758 missing declarations;
  no duplicates, type mismatches, extra resources, localized invariant overrides,
  or clear string argument mismatches. Every changed plural form preserves its
  indexed count arguments, including processed and total arguments together.
- All 40 English/localized strings catalogs passed matching-AGP AAPT2 compilation
  and a resource-only link. Styled descriptor labels retain bold markup and
  supplied metadata remains outside translation text.
- Declaration-level diff confirms exactly 2,744 additions and 83 revisions,
  confined to the selected identities. Canonical English is byte-for-byte
  unchanged. Whitespace and documentation links passed; temporary matrices,
  proposals, helper scripts, and validation artifacts are not committed.

Full application build/tests/lint, representative visual verification, and final
CI remain pending. Use `[skip ci]` for this intermediate checkpoint under the
user's explicit exception; resource-only compilation is not a full app test.

## Memory Editor checkpoint validation

- All 30 focused Python localization tests passed.
- Inventory: 977 source identities / 39 catalogs / 6,152 missing declarations;
  no duplicates, type mismatches, extra resources, localized invariant overrides,
  or clear string argument mismatches. Changed string and plural forms preserve
  indexed argument/type contracts and locale quantity categories.
- All 40 English/localized strings catalogs passed matching-AGP AAPT2 compilation
  and a resource-only link. This does not validate application interaction or
  target-language visual fit.
- Declaration-level diff confirms exactly 2,606 additions and 57 revisions within
  the 109 selected identities. Canonical English remains byte-for-byte unchanged;
  diff/whitespace and documentation-link checks passed. No unrelated files,
  screenshot baselines, generated matrices, or temporary helpers are committed.

Full app build/tests/lint, representative visual verification, and final CI are
pending. This intermediate resource checkpoint uses the user's explicit
`[skip ci]` exception. Keep the PR Draft while material scope remains incomplete.

## Settings/runtime checkpoint validation

- Inventory: 977 canonical identities / 39 catalogs / 2,111 missing declarations;
  35 catalogs have full presence. Remaining missing declarations are kk401,
  or428, sat817 and si465. No duplicate identities, type mismatches, extra
  resources, localized invariant overrides or clear string argument mismatches.
- Changed string/plural forms preserve their argument/type contracts and locale
  quantities; literal HTML separators, raw metadata and stable folder names remain.
  Existing report-only formatter uncertainties are not declared resolved.
- All 40 English/localized strings catalogs passed matching-AGP AAPT2 compilation
  and a resource-only link. Compiled Russian descriptor text retains its bold
  span/trailing space without visible quote wrappers.
- Declaration-level diff confirms exactly 4,041 additions and 1,014 revisions,
  selected identities and byte-for-byte unchanged English. Whitespace and document
  links passed. Existing 30 passing localization-tool tests remain applicable:
  scripts/tests and their inputs were unchanged, so this checkpoint uses targeted
  checks on the new resource inputs without repeating those fixture tests.
- No application code, tooling/tests, dependency/build/workflow files or screenshot
  baselines changed. Scratch matrices/proposals/helpers/artifacts are uncommitted.

Full app build/tests/lint, representative visual verification and final CI remain
pending. This intermediate resource checkpoint uses the user's explicit `[skip ci]`
exception. Keep the PR Draft while material scope remains incomplete.

## Deferred-language recovery checkpoint

Added **619** declarations and revised **24** existing declarations relative to
`8df5bcd`. Independent language review and a separate integration semantic pass
covered previously deferred settings, controls, installer, Memory Editor and
runtime cases. Correct existing text was retained.

| Locale | Added | Revised | Accepted recovery identities | Still unaccepted in recovery selection |
| --- | ---: | ---: | ---: | ---: |
| Kazakh | 360 | 18 | 383 | 42 |
| Odia | 101 | 6 | 109 | 341 |
| Sinhala | 145 | 0 | 145 | 320 |
| Santali | 13 | 0 | 17 | 6 |

- Kazakh completes the earlier deferred installer and Memory Editor selections.
  Consequential messages retain controller calibration, settings/data retention,
  next-launch profile propagation, partial success, readback and conditional undo.
  A second semantic pass corrected the batch-edit notice: matching values change,
  rather than their data types. Opacity, detailed calibration and pipeline metrics
  remain explicitly unaccepted where technical terminology could not be established.
- Odia corrects software-provider metadata rather than seller wording, rounded
  rectangle corners, dimensions, date-added sorting and vibration. Pressed-state
  label/symbol colors explicitly belong to the pressed button. New simple report
  plurals retain count parameters and standard numeric classifier usage.
- Sinhala adds reviewed settings/control labels and bounded messages. Review
  corrected button outlines, pressed-state color scope, keep-screen-on wording,
  safety-paused enforcement and restoration of an earlier search.
- Santali adds thirteen unchanged isolated labels attested in actual Mozilla
  Ol Chiki software contexts with matching action/option/heading roles. Four
  existing labels were kept. Five existing variants were preserved without a
  new correctness claim; file-picker Search remains missing because pane-title
  evidence did not establish its action role. No technical prose was manufactured.

The [current language-review record](localization-language-review.md) consolidates
all 1,492 missing identities and 128 recorded deferred existing cases: one Kazakh,
14 Odia and 113 Santali. Separate historical batch counts must not be summed as
current gaps or treated as whole-catalog language certification. Remaining
uncertainty and reference evidence are explicitly documented.

## Deferred-language recovery validation

- Inventory: 977 canonical identities / 39 catalogs / 1,492 missing declarations /
  35 full-presence catalogs. No duplicates, resource-type mismatches, extras,
  localized invariant overrides or clear string argument mismatches. The 61
  report-only ambiguous/disabled formatter comparisons are not declared resolved;
  the added literal-percent scale help preserves canonical disabled formatting.
- Changed strings and plural forms passed the existing hash-guarded applier's
  identity, plain-text, argument/type and quantity checks. Dry-run preceded writes.
- All 40 English/localized strings catalogs passed matching-AGP AAPT2 compilation
  and resource-only linking for the cumulative current resource state.
- Declaration-level diff confirms exactly 619 additions and 24 revisions in the
  accepted recovery selections; no removed identities and byte-identical English.
  Whitespace and documentation-link checks passed.
- Earlier 30 passing localization-tool fixture tests remain applicable: tooling,
  tests and fixtures are unchanged. Targeted checks validate the new resource
  inputs. Application code, build/dependencies/workflows and screenshot baselines
  are unchanged; generated matrices, reference corpora and helpers are uncommitted.

This intermediate checkpoint uses the user's explicit `[skip ci]` exception.
Full app build/tests/lint, rendered UI review and final CI remain pending. The
successful earlier CI run on `0064a004` does not validate these resource changes.

## Technical recovery checkpoint

Added **81** declarations and revised **six** existing declarations relative to
`7bdb8e6`. All proposals received independent language/semantic review, followed
by integration review. Acceptance remained bounded; three Sinhala compound
messages proposed in the draft were withheld after independent review.

| Locale | Added | Revised | Accepted identities including retained text | Unaccepted in this selection |
| --- | ---: | ---: | ---: | ---: |
| Kazakh | 36 | 2 | 38 | 5 |
| Odia | 25 | 4 | 32 | 12 |
| Sinhala | 20 | 0 | 20 | 3 |

- Kazakh corrects opacity terminology using actual GIMP/KWin software contexts,
  with transparent/opaque endpoints and inside/outside-screen scope retained.
  Calibrating sticks/triggers remain neutral throughout the timer; full traversal,
  first-input start and unchanged previous calibration until save are explicit.
  Metrics distinguish MIDlet publication from renderer consumption, preserve
  percentile/max statistics, process core equivalents and submission duration.
  Callback depth is not queue fullness. The fallback notice uses the exact
  existing built-in profile display name.
- Odia corrects runtime toolbar scope on MIDlet Canvas screens, system status-bar
  ownership and time-of-day theme wording. Added labels preserve Android font
  scale, initial command-bar suppression, emulation timing, virtual controls,
  scaling aspect-ratio exclusion, MIDI soundbank and resume-call scope. Correct
  existing raw-screenshot and automatic-theme labels were retained.
- Sinhala adds destructive/reset confirmations with correct settings/data
  retention, profile application/following distinctions and partial-save/update
  warnings. Three longer conditional/next-start constructions remain explicitly
  unaccepted; target grammar was not inferred from correct placeholder syntax.

At this technical recovery checkpoint, the inventory had **1,411 missing declarations**: Kazakh 5, Odia 302,
Sinhala 300, Santali 804. There are **120 recorded deferred existing cases**,
seven Odia and 113 Santali. Kazakh's remaining keys are four compound color names
and the non-finite sample label. Exact identities and language limitations remain
in the [current review record](localization-language-review.md). Santali is still
in scope; no new Santali prose was accepted in this checkpoint.

## Technical recovery validation

- Exact declaration diff: 81 additions/six revisions within accepted selections;
  no removed identities, canonical English byte-identical, unrelated declarations
  unchanged. XML/identity/placeholder checks and applier dry-run preceded writes.
- Inventory retains 977 canonical identities/39 catalogs/35 full-presence catalogs,
  with no duplicates, resource-type mismatches, extras, invariant overrides or clear
  string argument mismatches. The same 61 report-only formatter comparisons remain.
- AAPT2 compiled the three changed strings catalogs, reused prior compilation
  evidence for 37 byte-identical catalogs, and successfully resource-linked the
  cumulative 40-catalog state. This is not full app or rendered UI validation.
- Whitespace and documentation links passed. Localization tooling/tests/fixtures
  remain unchanged, so earlier 30 passing fixture tests remain applicable. No
  application code, build/dependency/workflow or screenshot-baseline changes.
  Temporary references, proposals and helpers are not committed.

This intermediate checkpoint uses the user's explicit `[skip ci]` exception.
Full app build/tests/lint, representative UI review and final CI remain pending;
prior successful CI on `0064a004` does not validate this resource state.

## 14 October cumulative quality validation

This checkpoint adds **748 declarations**, revises **23 existing strings**
and extends 185 existing plural declarations with reviewed quantity forms
relative to `de2efd71`. Santali contributes 141 additions and
14 corrections, including all three prioritized stale messages.
Kazakh, Odia and Sinhala now have all 977 canonical resource identities. Their
completion preserves mathematical non-finiteness, compound color identities,
controller calibration, profile following at next start, supported settings,
installer/data-retention scope and conditional Memory Editor outcomes. Odia's
seven recorded existing cases were corrected. Santali recovery continues with
aligned Mozilla software resources and independently reviewed Google Translate
English-to-Santali references; resource presence does not certify language quality.

The cumulative review revisited 19 consequential identities across all 39
catalogs, retaining prior accepted feature reviews as its baseline. Arabic
controller diagnosis now describes an identifying fingerprint; Indonesian neutral
calibration instructions retain the scope of detected sticks and triggers.

Android lint exposed missing plural categories. Added 185 reviewed quantity
forms in Catalan, Czech, Spanish, French, Italian, European/Brazilian Portuguese
and Turkish, preserving every existing form and argument. CLDR category rules
and complete target-language count constructions were checked separately;
million/decimal categories are not copied blindly from English.

The reviewed-batch applier failed atomic replacement on Windows because its
temporary file was still open. It now closes the file before replacement and
cleanup, retaining both source/target stale-state guards. The existing plural
preservation fixture checks LF and CRLF explicitly. All 30 localization-tool
tests pass on Windows.

Local native-disabled Android APK assembly, app/dexlib JVM tests and resource
compilation/linking passed. The app reports 1,264 tests with no failures/errors
and one skipped test; dexlib reports 20 tests with no failures/errors/skips.
The cumulative final-snapshot build/tests/lint command passed in 9m 40s.
App/dexlib lint passes: the app report has no errors or missing-quantity findings,
with 82 warnings and two hints remaining.
This validates host/resource inputs and does not certify native MIDlet execution.

A temporary Android rendering probe exercised production adaptive dialogs on a
320dp-wide API 36 display at 1.3 font scale. Seventeen cases covered controller
identity/calibration, conditional Memory Editor writes/undo, profile linkage,
deletion, installer/data replacement, public diagnostic attachments and cutouts,
including the corrected Santali About, Help, deletion and partial-restore text.
Odia, Sinhala, Ol Chiki, Arabic/Persian RTL and Japanese/Chinese/Korean glyphs,
long-text scrolling, final text/action visibility and formatted arguments were
visually inspected. No credible clipping, missing glyph or bidirectional defect
was observed. Help's encoded HTML paragraph break was also checked in the probe.
This is scoped rendering evidence, not full feature/device testing.
The probe and generated images remain outside committed sources; screenshot
baselines are unchanged.

The refreshed remote record includes failed validation
[38514764068](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38514764068) on
`de2efd71`: build/tests and screenshot validation passed, while lint failed on
the missing plural categories repaired here. Earlier successful CI does not
validate this changed resource state. The one subsequent cumulative validation
[38573678907](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38573678907)
passes **Build and tests, Screenshot validation, Lint and the final build gate**
on resource commit `d6616507f1b0eccba771c33470df8c6eaa317b56`. Native arm64 APK
assembly, app/dexlib tests and instrumentation compilation are included. This
documentation-only follow-up changes no executable/resource input; no repeat CI
run is needed on identical validated inputs.

## Santali contextual UI recovery — 14 October follow-up

Resource commit `b271719389686d75b5e97dbf4d101aa59bc3a8fd` adds 197
previously missing Santali UI identities and retains all previously
accepted translations unchanged. All 197 keys exist in the canonical
English catalog; the integration check rejected no formatter mismatch,
duplicate key, or XML-special-character defect. The missing count is
now **466**, comprising 432 strings and 34 plurals. The 76 deferred
existing Santali reviews remain open.

This is a bounded UI/control-label recovery, not a native-speaker
certification. The earlier cumulative CI validates `d6616507f`. A separate
[Android CI run](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38580930757)
validates the newer resource commit `b27171938`: **Build and tests**,
**Screenshot validation**, **Lint**, and the final **build** gate all pass.
Representative manual display checks and fluent target-language review
are still needed. Do not mark the PR ready to merge.

## Exact next unfinished work

Continue from [current language-review gaps](localization-language-review.md),
not historical missing counts. Resolve the remaining 466 Santali missing
declarations and 76 deferred existing cases with competent language judgment.
The prioritized `message_delete`, `about_message`, and `help_message` defects
are corrected. Continue the exact remaining controller, installer, profile,
Memory Editor, runtime and diagnostic identities in the current review record.
Kazakh, Odia and Sinhala have no remaining missing identities.
Preserve the verified
feature contracts for profile following, controller identity/calibration,
physical versus virtual input, data retention, partial installation/write/save
outcomes, conditional undo, timing/metrics and local versus public diagnostics.

Continue cross-feature language review and validate future resource changes with
relevant XML/build/tests/lint and representative rendering checks. Batch further
work before another meaningful cumulative CI run; do not rerun unchanged inputs.
Current passing validation does not resolve the remaining Santali language scope.
Keep PR 169 Draft; no merge is authorized.
