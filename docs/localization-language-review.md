# Current localization language-review gaps

Snapshot: 15 October 2026, PR 169, following Kazakh/Odia/Sinhala completion
and further evidenced Santali recovery. This record supersedes the active counts
in earlier per-feature snapshots; their historical evidence remains valid.
Android XML remains the translation catalog. Re-export the inventory before writing.

## Current coverage

| Locale | Missing declarations | Unaccepted existing cases in deferred feature selections |
| --- | ---: | ---: |
| `kk` | 0 | 0 |
| `or` | 0 | 0 |
| `si` | 0 | 0 |
| `sat` | 0 | 76 |

The 39 catalogs contain 977 canonical identities; all 39 now have full resource presence.
There are **zero missing declarations**; Santali linguistic QA remains unfinished. Existing-case counts are a
deduplicated continuation of recorded deferred selections, not whole-catalog
native-speaker certification. Santali remains in the requested product scope.

## Completion and review evidence

This checkpoint adds 748 declarations against `de2efd71`: five Kazakh,
302 Odia, 300 Sinhala, and 141 Santali. Separate drafting, target-language review
and integration review preserve conditions, negation, affected objects, partial
outcomes and terminology.
All seven deferred existing Odia cases were corrected. Santali `midlet_version`
was corrected and unchanged `pref_app_sort_name` was corroborated. Further
whole-message and font/display review reduces the deferred existing selection
from 113 to 76; reviewed unchanged entries retain their original bytes.

- Kazakh retains the full coral/peach, berry/rose, jade/blue-green and
  orchid/violet identities. `config_gamepad_calibration_issue_non_finite`
  explicitly describes a sample whose value is not finite, including NaN and
  infinity. Mathematical use of `ақырлы` is corroborated by this
  [university mathematics text](https://www.ektu.kz/files/DistanceEducation/Resource/695554/%D0%95%D1%80%D0%BA%D0%B0%D1%80%D0%B0%20%D0%90%D0%B9%D0%B4%D0%BE%D1%81%20%D0%96%D0%BE%D0%BB%D0%B4%D1%8B%D0%B1%D0%B0%D0%B9%D1%83%D0%BB%D1%8B%20%D0%BC%D0%B0%D1%82%D0%B5%D0%BC%D0%B0%D1%82%D0%B8%D0%BA%D0%B0-2.pdf).
  The five complete phrases received independent review; a terminology source
  alone is not certification of new wording.
- Odia/Sinhala complete the deferred controls, installer, Memory Editor,
  settings/performance, profiles and runtime selections. Counts, quantities,
  next-start relationships, supported-setting scope, neutral holding, numeric
  non-finiteness and conditional readback/revert outcomes remain explicit.
  Odia raw-screenshot wording now describes virtual-resolution capture before
  scale/filter/shader processing. Sinhala integration review corrected blue hue
  spelling, on-screen versus physically-above-screen placement, and recipient
  grammar in next-start profile clauses.
- All 39 catalogs were revisited against the current English source for a
  targeted 19-identity consequential selection: deletion/data retention,
  bundled-only replacement, partial restore, deferred cancellation, profile
  following at next start, controller identity/neutral/non-finite input,
  conditional undo and partial writes, type-compatible batch changes, public
  diagnostic attachment, and display-cutout/status-bar interaction. Prior
  accepted feature reviews remain the baseline. This is cumulative risk review,
  not a claim that every existing sentence received a new independent audit.
- Arabic diagnosis now names the controller's identifying fingerprint. Its
  caller supplies `ControllerCapabilityCache.buildSignature` (descriptor plus
  sorted capability ranges), not a signing operation. Indonesian neutral
  instructions now retain the detected-channel scope for both sticks and
  triggers throughout the timer.

Actual software terminology was checked against Mozilla
[Odia](https://github.com/mozilla-l10n/firefox-l10n/blob/main/or/browser/browser/preferences/preferences.ftl)
and [Sinhala](https://github.com/mozilla-l10n/firefox-l10n/blob/main/si/browser/browser/preferences/preferences.ftl)
resources. Sinhala opacity terminology is also attested in Xfce's UI resources.
[Unicode CLDR cardinal rules](https://github.com/unicode-org/cldr/blob/main/common/supplemental/plurals.xml)
support category selection; complete count constructions still require language
judgment. The new Odia/Sinhala plurals use count constructions valid in both
`one` and `other`, including Sinhala's integer zero in `one`.

Santali adds 47 complete attested labels/actions/statuses from
[Firefox desktop](https://github.com/mozilla-l10n/firefox-l10n/tree/25ac5fffb285324e734e31a4f35e6f2e1bc7b0b6/sat)
and [Firefox Android](https://github.com/mozilla-l10n/android-l10n/blob/2270e00c1d1dbe2df7d35095133643de7d605d2d/mozilla-mobile/fenix/app/src/main/res/values-sat/strings.xml),
aligned against English and the app's UI role. Examples include Search as an
action, Edit/Manage profiles, Select/Deselect all, Undo, collection labels,
version, and checking for updates. A postal-address translation was rejected
for a memory address; an Update action was rejected for an installer status;
Add to new collection was rejected where existing collections are supported.
Conflicting profile/theme terminology did not justify replacing accepted text.
No technical sentence was assembled from dictionary fragments or fallback English.

## Google-assisted Santali review

[Google Translate, English to Santali (Ol Chiki)](https://translate.google.com/?sl=en&tl=sat&op=translate)
helped recover 94 further missing declarations and correct 13 existing
messages/labels, alongside actual Mozilla software wording,
current callers and independent whole-message review. Its output and reverse
translation were not automatic acceptance criteria. Literal product names,
format arguments, feature labels and supported-operation scope were checked
against the current catalog.

The deletion question now includes the app, its settings, saved data and
Library entry. The initial reference turned save data into an instruction to
save it and was rejected; complete contextual English references resolved that
relationship. Final review removed a games-only qualifier so utility MIDlet data
is covered as well. About now covers Library organization, controls, display, audio,
performance and free/open-source status. Help retains linear-filtering slowdown
and disabling it when necessary, plus event-queue bypass, possible faster response
and possible instability. Its explanation is associated with the actual displayed
Immediate Processing label. The old flicker-correction promise is removed.

Ten font/display corrections restore linear filtering, character encoding,
screen placement, font presets, Android font scale and omitted qualifiers.
Twenty-two positively reviewed existing font/display labels remain unchanged.
The partial bundle-restore failure message keeps installation success, failed
bundle-data restoration and a restore-only retry question. Reviewed terminology
refinements preserve complete sentence constructions; they do not assemble new
technical prose from a glossary.

Specific unsuccessful references remain quality-review evidence, including for newly present machine-reference candidates:

| Identity | Required meaning and review limitation |
| --- | --- |
| `file_picker_parent_folder` | The containing filesystem directory; reference names mother/father instead. |
| `file_picker_sort_modified_newest`, `file_picker_sort_modified_oldest` | Modification time and the appropriate ordering; reference uses shown rather than changed and can omit the newest qualifier. |
| `library_filter_recently_opened` | Previously played apps; reference describes ongoing play. |
| `config_gamepad_calibration_cancelled`, `config_gamepad_calibration_save_failed`, `config_gamepad_calibration_saved`, `config_gamepad_calibration_waiting_samples` | Calibration, preserving prior calibration on cancellation/failure and waiting for numerical controller samples; reference substitutes sharing/division for calibration. |
| `config_gamepad_calibration_issue_non_finite` | Numerical NaN/infinity rather than missing/incomplete samples; a phonetic non-finite label does not establish the mathematical concept in Santali. |
| `config_gamepad_calibration_neutral_instructions`, `config_gamepad_calibration_start` | Hold the relevant sticks centered and triggers released throughout the timer; reference confuses controller sticks with hanging/sticking and leaves channel/temporal relationships uncertain. |
| `config_gamepad_diagnosis_signature` | Device-identification fingerprint, descriptor and capabilities; reference uses a handwritten signature and corrupts the literal formatter argument. |
| `library_import_restore_existing` | Only bundled settings/save components replace their corresponding existing data; a reverse reference appears plausible but competent judgment of the full target-language affected-object relationship remains unavailable. |
| `memory_editor_edit_batch_help` | Apply only to targets compatible with the selected type; named Watch/Freeze operations are unavailable; reference target terminology and Watch versus View remain uncertain. |
| `memory_editor_remove_candidates_description` | Hide without game-memory writes; undo the last confirmed edit only on a current-value match and retain conflicts; reference negates the undo clause and corrupts the conflicting-result consequence. |
| `memory_editor_revert_remove_commit_failed` | Values may already be restored despite result-removal failure; refresh/retry never overwrites game-changed values; reference omits the restored-value outcome. |

These observations do not imply that every remaining Santali message is
untranslatable. They identify why these proposals could not be accepted. Continue
bounded contextual review rather than treating correct script or a plausible
reverse translation as proof of target-language accuracy.

## Further Santali catalog recovery — 14 October

A follow-up resource commit (`b27171938`) adds **197** Santali string
declarations across About, file selection, Library, controller labels,
diagnostic reports, installation states, Memory Editor controls, virtual
controls and Updates. The new entries use existing reviewed UI vocabulary
where applicable, preserve resource arguments, and leave higher-risk
technical instructions or loss-of-data warnings in the open review set.

Structural identity and format-argument comparisons against the English
catalog pass for all 197 additions. No native-speaker certification, representative manual rendering,
or full linguistic review is claimed for this follow-up. The new
[Android CI run](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38580930757)
passes build/tests, screenshot validation, lint, and the build gate for
resource commit `b27171938`; this verifies tooling, not target-language prose. Santali plural
resources remain missing; CLDR cardinal rules require one/two/other
grammar and are not interchangeable with English plurals.

## Multi-reference Santali recovery — 14 October (324 additional strings)

Resource commit [`0b440ea9e`](https://github.com/H3nb/JL-Mod-Plus/commit/0b440ea9e81a0334b78242791ad01c79130635e6)
adds 324 formerly missing **single-string** declarations; prior declarations remain unchanged.
English resource identities, selected file attributes, per-string indexed arguments,
inline line-break markers, reserved XML characters, preserved technical identifiers,
and uniqueness were checked before committing. Candidate selection compared two
user-supplied Gemini translations and rejected direct Google Translate imports
where machine translation altered IDs, placeholders, product names or resource syntax.
Most additions use the more literal candidate with targeted alternative wording
for known vocabulary/semantic problems. This is **source-checked machine-reference
recovery, not native-speaker linguistic certification** or proof of every
sentence's pragmatics. CI is tracked separately.

At the earlier checkpoint inventory reached **835/977** Santali declarations, with **142 missing**
(108 strings and 34 plural resources). The existing 76 deferred translation
reviews remain distinct and open. Newly present strings in previously identified
semantic-risk areas still require competent language review rather than automatic
approval; in particular controller calibration outcomes, applied profile changes,
memory operations, and data-retention/error consequences should be checked in
context. Do not interpret presence as a resolved quality-review finding.

Santali follows [CLDR plural forms](https://unicode.org/cldr/charts/49/supplemental/language_plural_rules.html)
`one`, `two`, `other`. The English-only `one`/`other` machine-reference
batches do **not** resolve the missing 34 plurals. A separate, target-locale
plural review is required; review existing Santali plurals for the dual form as
well. The translation-batch source itself was damaged at `T0225`
(`config_help_scale_ratio`): source `1% to 1000%` was transformed into
`1{1}o 1000%`; the damaged translations were **not** imported. Use
canonical English (`formatted="false"`) for subsequent translation.

## CLDR-aware Santali plural completion — 15 October

Resource commit [`f075df46d`](https://github.com/H3nb/JL-Mod-Plus/commit/f075df46de1ca41f232778cf77dfb90dbc0d7d1e) adds **all 34 formerly missing Santali plural resources**, each with `one`, `two`, and `other` variants, and 21 additional contextual UI strings. For noun-bearing dual-count forms, the distinct two-item candidate uses the CLDR-attested Santali `ᱠᱤᱱ` construction; count-only statuses retain the same phrasing when no noun requires inflection. See [CLDR cardinal categories](https://unicode.org/cldr/charts/49/supplemental/language_plural_rules.html). Both English-derived formatting arguments and quantity declarations have been checked for every variant.

At the plural checkpoint, coverage reached **890 of 977** translatable identities. **87 strings** remained missing at that checkpoint, **zero plurals** were missing; the older **76 deferred existing translations** are still pending competent linguistic review. The dual forms here are **linguistically plausible candidates, not native-speaker-certified wording**; review noun agreement in context with fluent Santali speakers before declaring quality complete. The input/proposal errors around `config_help_scale_ratio` (`T0225`, literal `%` signs) remain excluded.

The plural resource commit passed all jobs of [Android CI #38599807318](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38599807318). Documentation-only commits use `[skip ci]`. Keep PR #169 Draft; no merge is authorized.

## Complete resource presence; pending Santali language-quality review

All **977 canonical translatable Android identities** now have Santali Ol Chiki
declarations: **zero missing `<string>` and `<plurals>` resources**.
This records presence only, **not native-speaker-certified translation quality**.

The [final resource completion commit](https://github.com/H3nb/JL-Mod-Plus/commit/1d3a80d32c6b3f76f83bb439596d82665bf746ce)
adds the remaining **87 strings** after English-key and formatting checks.
It restores literal `1%`, `100%`, and `1000%` in the separately handled
`config_help_scale_ratio` with `formatted="false"`, and distinguishes
`non-finite` numerical samples with `NaN/±∞`. The established
`JL-Mod Defaults` profile identity, GitHub name, and diagnostic download
destination remain unchanged. Exact format indices, declared string names,
line-break contracts (including literal blank lines), and XML escaping were
validated before the resource write.

The following **87 keys are PRESENT**, not missing, but require careful
contextual and fluent Santali language review. Prioritize negation, data
retention and deletion, unsuccessful or partial outcomes, calibration bounds
and unchanged previous state, profile propagation, Memory Editor writes/undo,
diagnostic public exposure, and runtime timing semantics. Review also the
recent 324 machine-reference strings, 34 newly added dual-form plurals and
21 contextual strings: having multiple AI references is not linguistic
certification. The 76 previously deferred existing cases remain listed below.

### Newly present 87-string language-review queue

```
config_analog_stick_summary
config_controller_help_body_compact
config_gamepad_calibrate_summary
config_gamepad_calibration_issue_insufficient_samples
config_gamepad_calibration_issue_invalid_constraints
config_gamepad_calibration_issue_non_finite
config_gamepad_calibration_issue_rest_outside_range
config_gamepad_calibration_neutral_instructions
config_gamepad_calibration_no_analog_ranges
config_gamepad_calibration_review_instructions
config_gamepad_calibration_save_failed
config_gamepad_calibration_start
config_gamepad_calibration_stick_instructions
config_gamepad_calibration_trigger_instructions
config_gamepad_calibration_waiting_controller
config_gamepad_opaque_summary
config_gamepad_unsupported_summary
config_help_emulation_speed
config_help_encoding
config_help_filter
config_help_font_sizes_long
config_help_fps_limit
config_help_graphics_mode
config_help_immediate
config_help_parallel_redraw
config_help_scale_ratio
config_help_skip_resume
config_help_system_properties_long
config_help_timing_mode
config_help_timing_unavailable
config_message_delete_app_data
config_message_reset_profile_settings
config_message_reset_settings
config_missing_app_storage_generic
config_missing_app_storage_named
config_reset_all_settings_summary
config_warning_filter_shader
crash_report_bundle_cleanup_failed
crash_report_bundle_ready_message
crash_report_delete_message
crash_reports_batch_delete_message
installer_ambiguous
installer_conversion_skipped_entry
installer_needs_recovery
installer_partial_restore
installer_removed_leftovers
library_import_invalid_bundle
library_import_restore_existing
memory_editor_edit_batch_help
memory_editor_edit_batch_no_common_type
memory_editor_edit_batch_type_notice
memory_editor_expression_query_help
memory_editor_freeze_after_edit_help
memory_editor_identity_unsafe
memory_editor_partial_write
memory_editor_remove_candidates_description
memory_editor_resource_limit
memory_editor_revert_remove_commit_failed
memory_editor_revert_remove_outcome
memory_editor_safety_limit
memory_editor_write_failure_outcome
memory_editor_write_outcome
midlet_failure_recovery_message
midlet_failure_recovery_message_named
msg_immediate_mode_disabled
perf_overlay_parameters_help
pref_use_display_cutout_summary
pref_use_display_cutout_title
preset_save_as
preset_save_help
preset_saved_unlinked_warning
preset_update_summary
process_exit_recovery_message
process_exit_recovery_message_midlet
process_exit_recovery_message_named
profile_apply_whole_follows
profile_apply_whole_without_layout
profile_default_fallback_notice
profile_delete_template_message
reconversion_error
reconverting_wait
runtime_preset_update_explanation
runtime_preset_update_failed
runtime_preset_update_unlinked
runtime_replacement_failed
runtime_virtual_controls_edit_guide_message
updates_not_installable
```

## Corrected Santali semantic blockers

The three previously prioritized existing resources now have reviewed complete
corrections: `message_delete`, `about_message`, and `help_message`. Their precise
contracts and reference failures are recorded above. The remaining deferred
existing list still includes known stale meanings and uncertain grammar; none is
certified merely because its XML is present. No language was removed and the
localization objective remains all 39 catalogs.

## Deferred existing translations

### `sat` — 76 existing

- `FORCE_CLOSE_CONFIRMATION`: 15 October rewrite now names stopping the MIDlet and possible loss of unsaved changes; the earlier unsupported data-corruption claim was removed. Fluent Santali grammar and register review remain open.
- `PREF_FORCE_FULLSCREEN`: Revised against canonical English on 15 October: Removes obsolete force-fullscreen claim; identifies launch without command bar. Standard Santali grammar/idiom review remains open.
- `PREF_IMMEDIATE`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_LAYOUT`: Revised against canonical English on 15 October: Names key-code layout rather than generic layout. Standard Santali grammar/idiom review remains open.
- `PREF_LIMIT_FPS`: Revised against canonical English on 15 October: Uses frame-rate limit rather than a malformed FPS imperative. Standard Santali grammar/idiom review remains open.
- `PREF_SCALE_RATIO`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_SYS_PROPS`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_TOUCH_INPUT`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VIRTUAL_KEYBOARD_OPTIONS`: Revised against canonical English on 15 October: Names displaying virtual controls rather than a virtual keyboard. Standard Santali grammar/idiom review remains open.
- `PREF_VK_ALPHA`: Revised against canonical English on 15 October: Removes obsolete trailing colon in the opacity label. Standard Santali grammar/idiom review remains open.
- `PREF_VK_BACK`: Revised against canonical English on 15 October: Includes button **color**, previously missing. Standard Santali grammar/idiom review remains open.
- `PREF_VK_FEEDBACK`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_FORCE_OPACITY`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_FORE`: Revised against canonical English on 15 October: Identifies label/symbol color rather than only labels. Standard Santali grammar/idiom review remains open.
- `PREF_VK_HIDE_DELAY`: Revised against canonical English on 15 October: Names automatic hide delay, not an imperative with trailing colon. Standard Santali grammar/idiom review remains open.
- `PREF_VK_OUTLINE`: Revised against canonical English on 15 October: Includes outline **color**. Standard Santali grammar/idiom review remains open.
- `PREF_VK_SEL_BACK`: Revised against canonical English on 15 October: Identifies color of a **pressed** button, not unexplained '(P)'. Standard Santali grammar/idiom review remains open.
- `PREF_VK_SEL_FORE`: Revised against canonical English on 15 October: Identifies pressed label/symbol color, not unexplained '(P)'. Standard Santali grammar/idiom review remains open.
- `RESET_LAYOUT_CMD`: Revised against canonical English on 15 October: Names virtual-controls layout rather than key layout. Standard Santali grammar/idiom review remains open.
- `action_lock_orientation`: Existing compound wording cannot be certified as screen rotation lock rather than lock screen orientation without competent Santali review.
- `alert_map_menu`: Competent standard Santali Ol Chiki judgment unavailable; preserve existing bytes without accepting semantics.
- `alert_msg_workdir_not_exists`: Competent standard Santali Ol Chiki judgment unavailable; preserve existing bytes without accepting semantics.
- `converting_wait`: Existing Ol Chiki progress text retained; requires competent Santali verification of JAR conversion phase grammar.
- `create_apps_dir_failed`: Revised against canonical English on 15 October: Restores the exact `%1$s` format argument and the working-directory failure/recovery action; removes bogus quoting. Standard Santali grammar/idiom review remains open.
- `install_done`: Existing Ol Chiki success sentence retained; requires competent Santali review of completed installation result and register.
- `install_jar_non_matched_jad`: 15 October rewrite separates the mismatch from the choice to ignore JAD and install, restoring canonical line breaks. Fluent Santali verification of the two affected objects/decision remains open.
- `invalid_resolution_not_saved`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `layout_edit_finish`: Existing Ol Chiki label retained unchanged. Requires competent Santali verification of finish-editing action; must not imply save completion.
- `layout_edit_finished`: Existing Ol Chiki label retained unchanged. Requires competent Santali verification of virtual-controls editing-complete status; cannot certify its editing-mode wording or whether it implies persistence.
- `layout_switch`: Revised against canonical English on 15 October: Uses the same translated label as `runtime_virtual_controls_switch_layout` for the identical English action. Standard Santali grammar/idiom review remains open.
- `loading_info`: Existing abbreviated information/loading wording cannot be certified as natural current standard Santali UI.
- `mapping_dialog_key_not_specified`: Revised against canonical English on 15 October: States that no physical inputs are assigned, not merely unspecified. Standard Santali grammar/idiom review remains open.
- `mapping_dialog_title`: Revised against canonical English on 15 October: Names both key and button as valid capture input. Standard Santali grammar/idiom review remains open.
- `midlet_vendor`: Existing Ol Chiki vendor label retained; appears to use a merchant/trader term, and software publisher/vendor meaning requires competent Santali review.
- `no_data_for_display`: Revised against canonical English on 15 October: Specifies there are no apps to show, rather than no generic data. Standard Santali grammar/idiom review remains open.
- `parallel_screen_redrawing`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `permission_request_failed`: Competent standard Santali Ol Chiki judgment unavailable; preserve existing bytes without accepting semantics.
- `pref_app_sort_date`: Revised against canonical English on 15 October: Includes the date's *added* role, not generic date. Standard Santali grammar/idiom review remains open.
- `pref_app_sort_vendor`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_button_shape_round`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_category_experimental`: Revised against canonical English on 15 October: Removes unsupported additional experimentation instructions. Standard Santali grammar/idiom review remains open.
- `pref_emulator_dir`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_enable_actionbar_summary`: Revised against canonical English on 15 October: Restores MIDlet Canvas, runtime toolbar and visibility contract. Standard Santali grammar/idiom review remains open.
- `pref_enable_actionbar_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_enable_statusbar_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_graphics_hw_gles`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_graphics_hw_surface`: Revised against canonical English on 15 October: Keeps technical renderer discriminator `Surface` verbatim. Standard Santali grammar/idiom review remains open.
- `pref_graphics_hw_view`: Revised against canonical English on 15 October: Keeps technical renderer discriminator `Window` verbatim. Standard Santali grammar/idiom review remains open.
- `pref_graphics_mode_title`: Revised against canonical English on 15 October: Removes extraneous colon. Standard Santali grammar/idiom review remains open.
- `pref_map_keys`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_mascot_summary`: Revised against canonical English on 15 October: Restores omitted condition `Mascot Capsule 3D`. Standard Santali grammar/idiom review remains open.
- `pref_mascot_title`: Revised against canonical English on 15 October: Preserves exact product name `Mascot Capsule 3D`. Standard Santali grammar/idiom review remains open.
- `pref_screen_scale_type_fill`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_screen_scale_type_fit`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_screen_scale_type_none`: Revised against canonical English on 15 October: Uses the existing manual-scaling term from `config_help_scale_type`. Standard Santali grammar/idiom review remains open.
- `pref_screenshot_summary`: Revised against canonical English on 15 October: Restores saving a MIDlet frame at virtual resolution *before* scaling/filter/shader, not an instruction to turn these off. Standard Santali grammar/idiom review remains open.
- `pref_theme_auto_battery`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_theme_auto_time`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_theme_system`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vibration_title`: Revised against canonical English on 15 October: Names vibration rather than an additional show/enable command. Standard Santali grammar/idiom review remains open.
- `pref_vk_hide_hint`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_save_alert`: 15 October rewrite names the *custom virtual controls layout* rather than a custom keyboard, and removes an unsupported extra line break. Fluent Santali review remains open.
- `pref_vk_type_arrows`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_arrows_numbers`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_custom`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_numbers`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_numbers_arrows`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_phone`: Revised against canonical English on 15 October: Identifies phone **keypad**, rather than phone only. Standard Santali grammar/idiom review remains open.
- `pref_vk_type_phone_arrows`: Revised against canonical English on 15 October: Identifies phone **keypad** with arrows. Standard Santali grammar/idiom review remains open.
- `pref_wakelock_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `reinstall`: Existing Ol Chiki prose retained; competent review needed for reinstall decision, preservation clause, and Android newline rendering.
- `reinstall_newest`: Existing Ol Chiki prose retained; competent review needed for target/current version roles and preserved-data clause.
- `reinstall_older`: Revised against canonical English on 15 October: Restores requested/current version roles and *may not work* compatibility warning, without extra newline or retained-data promise. Standard Santali grammar/idiom review remains open.
- `saved`: Competent standard Santali Ol Chiki judgment unavailable; preserve existing bytes without accepting semantics.
- `take_screenshot`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `warn_install_from_net`: Revised against canonical English on 15 October: Uses future download status rather than reporting a completed download. Standard Santali grammar/idiom review remains open.

## Santali isolated-label evidence

Thirteen missing declarations reuse complete attested labels with matching UI
roles. No technical sentence was assembled from these terms.

| App identities | Reference identity | Role |
| --- | --- | --- |
| `bulk_install_cancel` | `sync-device-name-cancel.label` | Cancellation action; timing remains in the separate status text. |
| `bulk_install_close` | `close-button.aria-label` | Close action. |
| `config_analog_stick_off`, `config_gamepad_pointer_off` | `preferences-doh-setting-off.label` | Disabled setting choice. |
| `config_background_custom`, `perf_overlay_custom`, `profile_custom` | `enhanced-tracking-protection-setting-custom.label` | User-customized option/state. |
| `config_destination_general` | `pane-general-title` | General settings destination. |
| `mapping_dialog_remove`, `memory_editor_remove` | `search-remove-engine.label` | Remove selected list entries; undo remains separate. |
| `memory_editor_search_tab` | `pane-search-title2.title` | Search workspace destination. |
| `perf_overlay_standard` | `enhanced-tracking-protection-setting-standard.label` | Standard/default preset adjective. |
| `settings_section_language` | `language-header` | Language settings heading. |

The file-picker Search key also labels an action, so a pane-title reference
alone was insufficient. Existing Settings/Close/Save/Custom variants were retained:
corpus differences alone do not establish a material defect. Existing Light is
also attested in the reference theme tooltip and was kept. This bounded check
does not imply certification of the rest of the Santali catalog.

## Earlier-catalog targeted semantic cleanup — 15 October

A further Santali [resource commit `fa0ee515`](https://github.com/H3nb/JL-Mod-Plus/commit/fa0ee515aeb6d7d45b2551bd7e4d311987915204)
corrects **seven already-present declarations** without altering resource
identity or argument contracts. The built-in profile names
`profile_builtin_settings` and `preset_builtin` now retain the exact
`JL-Mod Defaults` identifier. `FORCE_CLOSE_CONFIRMATION` no longer claims
data corruption and instead identifies possible *unsaved-change* loss.
`install_jar_non_matched_jad` distinguishes JAD mismatch from the choice
to ignore only the JAD; `pref_vk_save_alert` now specifies the custom
virtual-controls *layout*, not an unrelated keyboard. `reinstall` and
`reinstall_newest` no longer promise unrelated data preservation beyond
the canonical confirmation copy. The latter retains two version arguments
and original line-break contract.

These changes address identifiable **source-meaning defects** and
formatting, **not** a native-speaker certification; the related entries
stay in the 76-case existing-quality review until fluent linguistic
verification. All 39 locale catalogs remain at complete resource presence.
The post-cleanup resource validation is
[Android CI #38602008299](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38602008299);
its eventual verdict must be checked.

## Existing Santali catalog cross-feature semantic corrections — 15 October

Resource [commit `9f546a55`](https://github.com/H3nb/JL-Mod-Plus/commit/9f546a55ef380c877b0f9ca1533242ddc34ee572)
revises **32 already-present Santali strings** after comparing canonical
English semantics and reusing existing app terminology. No string identity,
required formatter argument, literal newline or plural structure was added,
deleted or altered. All 39 supported catalogs retain **977/977** declaration
presence.

These were not arbitrary style changes: the old translations frequently
dropped the affected UI object (color, virtual controls, MIDlet Canvas),
missed a distinguishing modifier (key code, date added, phone keypad,
pressed state), translated technical `Surface`/`Window` names as ordinary
nouns, or changed a future download into a completed one. The earlier
working-directory failure also used unindexed `%s` and added spurious
quotes to the supplied path; it now uses the canonical indexed `%1$s`
and appropriate recovery copy. The downgrade prompt now matches
the canonical version/compatibility contract.

| Correction group | Keys addressed |
| --- | ---: |
| Controls/labels | 15 |
| Colors and virtual-control states | 7 |
| Technical/system references | 7 |
| Installer and storage semantics | 3 |

These are **evidence-based semantic repairs**, not independent validation by
a fluent Santali speaker. Keep all 32 keys within the existing **76
pending native-language checks**; do not reduce that count solely because
source meaning was repaired. Review language morphology, grammar, script
orthography, natural register and UI fitting in context. The resource
commit triggers [Android CI #38654421339](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38654421339);
record the verified conclusion separately.

## Resume point

All 977 Santali declarations are present; keep PR #169 Draft. The full-resource completion CI run is [Android CI #38601105978](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38601105978). Its conclusion requires a separate status check.

Review the 87 present candidates above, the 76 deferred existing cases and
prior machine-reference work with competent Santali language judgment and
feature contracts, then check cross-feature consistency and representative
long-text, narrow-screen, RTL and script rendering. Previous resource checkpoint
`d6616507f` passes [cumulative Android CI](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38573678907),
including build/tests, lint and screenshot validation; local rendering evidence
is in [the progress record](localization-progress.md). The later Santali resource addition
`b27171938` passes [Android CI](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38580930757),
but still requires competent linguistic review and representative manual
rendering for long or consequential strings. Passing checks do not resolve the remaining
language judgments. Keep PR 169 Draft; no merge is authorized.
