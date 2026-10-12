# Localization implementation checkpoint — PR 169

Snapshot: 12 October 2026. Continue only on `localization/ai-matrix-workflow`;
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
- This checkpoint adds **7,052** localized declarations and revises
  **327** existing declarations relative to `0064a004`.
- Canonical English clarifies that only bundled components replace corresponding
  existing app data, and that Revert & remove **undoes** the last confirmed edit.
- The small reviewed-batch applier preserves unrelated XML bytes, comments and
  attributes; checks source/target hashes, identities, placeholders and review
  selection; rejects unsupported markup/reference edits; and writes atomically.
  Android XML remains the sole translation catalog.

## Coverage and quality

The inventory has 977 canonical translatable resources and 39 target catalogs.
Missing localized declarations decreased from 25,122 to **18,070**.
Twelve catalogs now cover all 977 resources: da, de, es, fi, fr, id, it, nl,
pt, pt-BR, ru, sv. **Coverage does not establish full language-quality review.**
Existing content outside the documented batches still needs review, including
within those complete catalogs. No claim of whole-catalog linguistic
certification is made.

Santali remains a material blocker. Its 215 unresolved batch entries require
competent standard Santali Ol Chiki review; CLDR/script documentation does not
establish trustworthy technical prose. Do not manufacture text, mark fallback
English as translation, or assume stale existing About/help/destructive warnings
are correct. Re-export the batches to enumerate exact identities; unresolved
existing core keys include About descriptions/provenance, help, stop/delete
warnings, storage recovery and diagnostic text.

## Validation evidence

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

## Exact next unfinished work

The next selected batch is configuration, physical/virtual controls and profiles:
`config_` except the two already-reviewed `config_missing_app_storage_` strings;
`preset_`, `profile_`, `mapping_dialog_`, `layout_edit_`, `runtime_preset_`,
`runtime_layout_`, `runtime_virtual_controls_`; plus `presets`,
`virtual_controls_save_failed`, `layout_switch`, `runtime_apply`, and
`saved_keyboard_layout_summary`. This is 269 identities. It was selected and
context prepared, but **no translations from that wave were applied**.

Read `docs/preset-config-contract.md`, verified callers, and existing localization
contracts. Preserve profile-following/detachment, settings/data retention,
calibration preservation, Controller Signature versus Profile, Flat/Fuzz,
physical versus virtual input, FPS/timing conditions and partial-save outcomes.
Continue remaining feature batches afterward (installer/bulk install, Memory
Editor, display/audio/performance, runtime/errors/permissions), then review all
existing translations vertically and horizontally. Re-export current inventory
and matrices rather than using stale scratch proposals. Finish representative
long-text/RTL/narrow UI checks and relevant final Android CI only when cumulative
resources are ready. Do not merge without explicit user instruction.
