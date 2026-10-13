# Localization implementation checkpoint — PR 169

Snapshot: 13 October 2026. Continue only on `localization/ai-matrix-workflow`;
base `alpha`. The PR remains Draft and is not ready to merge.

## Accepted work

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
Missing localized declarations decreased from 8,758 to **6,152** in the
Memory Editor checkpoint (11,502 before installer, 18,070 before controls, and
25,122 before the 12 October checkpoint).
Twelve catalogs now cover all 977 resources: da, de, es, fi, fr, id, it, nl,
pt, pt-BR, ru, sv. **Coverage does not establish full language-quality review.**
Existing content outside the documented batches still needs review, including
within those complete catalogs. No claim of whole-catalog linguistic
certification is made.

Santali remains a material blocker. Its earlier 215 unresolved batch entries require
competent standard Santali Ol Chiki review; CLDR/script documentation does not
establish trustworthy technical prose. Do not manufacture text, mark fallback
English as translation, or assume stale existing About/help/destructive warnings
are correct. Re-export the batches to enumerate exact identities; unresolved
existing core keys include About descriptions/provenance, help, stop/delete
warnings, storage recovery and diagnostic text.
The controls selection adds 269 unresolved review cases, for **484** across the
documented batches; this is not its total missing-catalog count. Kazakh, Odia,
and Sinhala also need competent review of the 347 explicitly deferred controls
messages. Continue achievable work in other feature groups without counting
those gaps as accepted translation or silently dropping their locales. The
installer selection records another 113 unresolved Santali cases and Memory
Editor records 109; counts from separate batches do not establish total catalog
coverage or language certification.

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

## Exact next unfinished work

The processed controls selection is:
`config_` except the two already-reviewed `config_missing_app_storage_` strings;
`preset_`, `profile_`, `mapping_dialog_`, `layout_edit_`, `runtime_preset_`,
`runtime_layout_`, `runtime_virtual_controls_`; plus `presets`,
`virtual_controls_save_failed`, `layout_switch`, `runtime_apply`, and
`saved_keyboard_layout_summary`. This is 269 identities; it is complete in
35 languages and partially accepted in three, with Santali still unresolved.

Read `docs/preset-config-contract.md`, verified callers, and existing localization
contracts. Preserve profile-following/detachment, settings/data retention,
calibration preservation, Controller Signature versus Profile, Flat/Fuzz,
physical versus virtual input, FPS/timing conditions and partial-save outcomes.
Resolve the exact deferred controls messages when reliable language review is
available, including the exact installer gaps in the linked review record.
The installer selection is `installer_`, `install_`, `bulk_install_`, `reinstall_`,
`reconversion_`, plus `install`, `reinstall`, `reconverting_wait`, `converting_wait`,
`warn_install_from_net`, and the four `midlet_name/size/vendor/version` labels.
It contains 116 identities, complete in 35 languages and partially accepted in
the same three, with Santali limited to retained isolated labels.
The Memory Editor selection is 109 `memory_editor_` identities, complete in
35 languages and partially accepted in the same three, with all Santali entries
unresolved. Resolve its exact deferred keys when competent language judgment is
available; preserve runtime identity, write/readback, undo conditions, partial
operations, and arithmetic-sequence semantics.
The next broad feature batch is display/graphics/audio/performance followed by
runtime lifecycle/compatibility, permissions/errors, and uncaptured resources.
Rebuild membership from the current inventory, exclude previously reviewed
configuration/controls keys explicitly, and inspect current feature contracts and
callers. Continue reviewing all existing translations vertically and horizontally.
Re-export current inventory and matrices rather than using stale scratch proposals. Finish representative
long-text/RTL/narrow UI checks and relevant final Android CI only when cumulative
resources are ready. Do not merge without explicit user instruction.
