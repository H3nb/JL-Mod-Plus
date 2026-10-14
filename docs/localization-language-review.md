# Current localization language-review gaps

Snapshot: 14 October 2026, PR 169, following Kazakh/Odia/Sinhala completion
and further evidenced Santali recovery. This record supersedes the active counts
in earlier per-feature snapshots; their historical evidence remains valid.
Android XML remains the translation catalog. Re-export the inventory before writing.

## Current coverage

| Locale | Missing declarations | Unaccepted existing cases in deferred feature selections |
| --- | ---: | ---: |
| `kk` | 0 | 0 |
| `or` | 0 | 0 |
| `si` | 0 | 0 |
| `sat` | 663 | 76 |

The 39 catalogs contain 977 canonical identities; 38 have full resource presence.
There are **663 missing declarations**, all Santali. Existing-case counts are a
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

Specific unsuccessful references remain evidence for the corresponding gaps:

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

## Exact missing identities

Every key below is missing in the current XML and remains
`NEEDS_LANGUAGE_REVIEW`, with no accepted proposal in the current recovery.
Reliable standard Santali Ol Chiki whole-message grammar and technical meaning
still need review. Tested reference failures are identified above; the list also
contains identities outside those bounded reference selections. Those untested
identities are not declared impossible to translate. Isolated labels still require
matching UI-role and orthographic judgment. The lists are exhaustive for resource
presence, not a claim that every missing identity received the same reference test.

### `sat` — 663 missing

```text
PREF_EMULATION_SPEED
PREF_TIMING_MODE
PREF_UNIT_MS
about_build_label
about_build_title
about_compatibility
about_evolution
about_github_browser_unavailable
about_github_link_copied
about_lineage
about_lineage_title
about_links_title
about_maintainer
about_revision_label
about_subtitle
about_version
bulk_install_action_jar_only
bulk_install_action_separate_copy
bulk_install_attention_count
bulk_install_cancel_requested
bulk_install_cancelled
bulk_install_choose_files
bulk_install_choose_folder
bulk_install_clear
bulk_install_complete
bulk_install_empty
bulk_install_empty_filter
bulk_install_failed_count
bulk_install_fatal
bulk_install_filter_review
bulk_install_filter_selected
bulk_install_found
bulk_install_install_count
bulk_install_installed_count
bulk_install_installed_version
bulk_install_library_changed
bulk_install_omitted_sources
bulk_install_planning
bulk_install_prepare_failed
bulk_install_ready_count
bulk_install_reason_ambiguous_match
bulk_install_reason_conflicting_jads
bulk_install_reason_duplicate_source
bulk_install_reason_jad_jar_mismatch
bulk_install_reason_jar_not_found
bulk_install_reason_missing_jar_url
bulk_install_reason_missing_parent
bulk_install_reason_older_candidate
bulk_install_reason_remote_jar
bulk_install_reason_source_error
bulk_install_reason_unsupported_uri_scheme
bulk_install_reason_version_conflict
bulk_install_reinstall_missing_apps
bulk_install_reinstall_source_missing
bulk_install_reinstalled_count
bulk_install_restored_count
bulk_install_result_failed
bulk_install_result_installed
bulk_install_result_reinstalled
bulk_install_result_restored
bulk_install_result_skipped
bulk_install_result_updated
bulk_install_running
bulk_install_selected
bulk_install_skipped_count
bulk_install_skipped_entries
bulk_install_skipped_result_count
bulk_install_source
bulk_install_source_changed
bulk_install_source_unavailable
bulk_install_status_ambiguous
bulk_install_status_conflict
bulk_install_status_downgrade
bulk_install_status_duplicate
bulk_install_status_error
bulk_install_status_mismatch
bulk_install_status_older_batch
bulk_install_status_reinstall
bulk_install_status_remote
bulk_install_status_same
bulk_install_status_update
bulk_install_target_unverified
bulk_install_title
bulk_install_unreadable_archives
bulk_install_updated_count
bulk_install_warning_count
clear_report_selection
config_analog_stick_summary
config_background_immersive
config_background_immersive_help
config_background_theme
config_background_theme_help
config_basic_display
config_color_picker_hex
config_color_picker_hint
config_color_picker_hue
config_color_picker_invalid
config_color_picker_saturation_value
config_controller_help_body_compact
config_controls_key_input
config_controls_maintenance
config_controls_virtual_keyboard
config_controls_virtual_keyboard_colors
config_current_screen_size
config_decrease_value
config_delete_app_data
config_delete_app_data_summary
config_destination_graphics
config_display_performance
config_display_rendering
config_edit_system_properties
config_font_sizes_value
config_gamepad_calibrate
config_gamepad_calibrate_summary
config_gamepad_calibration_cancelled
config_gamepad_calibration_issue_insufficient_samples
config_gamepad_calibration_issue_invalid_constraints
config_gamepad_calibration_issue_invalid_range
config_gamepad_calibration_issue_no_channels
config_gamepad_calibration_issue_non_finite
config_gamepad_calibration_issue_rest_outside_range
config_gamepad_calibration_issue_rest_too_noisy
config_gamepad_calibration_neutral_instructions
config_gamepad_calibration_no_analog_ranges
config_gamepad_calibration_no_supported_ranges
config_gamepad_calibration_phase_cancelled
config_gamepad_calibration_phase_committed
config_gamepad_calibration_phase_neutral
config_gamepad_calibration_phase_review
config_gamepad_calibration_phase_stick_range
config_gamepad_calibration_phase_trigger_range
config_gamepad_calibration_phase_unknown
config_gamepad_calibration_profile
config_gamepad_calibration_rejected
config_gamepad_calibration_review_instructions
config_gamepad_calibration_save_failed
config_gamepad_calibration_saved
config_gamepad_calibration_start
config_gamepad_calibration_stick_instructions
config_gamepad_calibration_trigger_instructions
config_gamepad_calibration_waiting_controller
config_gamepad_calibration_waiting_samples
config_gamepad_diagnosis_analog_settings
config_gamepad_diagnosis_axis
config_gamepad_diagnosis_capabilities
config_gamepad_diagnosis_descriptor
config_gamepad_diagnosis_device
config_gamepad_diagnosis_header
config_gamepad_diagnosis_mode
config_gamepad_diagnosis_no_controller
config_gamepad_diagnosis_notice
config_gamepad_diagnosis_pointer
config_gamepad_diagnosis_samples_not_sent
config_gamepad_diagnosis_signature
config_gamepad_diagnosis_source_controller
config_gamepad_diagnosis_source_explicit
config_gamepad_diagnosis_triggers
config_gamepad_directions_eight
config_gamepad_directions_four
config_gamepad_directions_numeric_v2
config_gamepad_keypad_title
config_gamepad_mapping_help_title
config_gamepad_opaque_summary
config_gamepad_pointer_cursor
config_gamepad_pointer_joystick
config_gamepad_status_disabled
config_gamepad_status_enabled
config_gamepad_stick_directions
config_gamepad_stick_pointer
config_gamepad_stick_unassigned
config_gamepad_test
config_gamepad_unsupported_summary
config_help_background
config_help_background_custom_color
config_help_emulation_speed
config_help_encoding
config_help_filter
config_help_font_aa
config_help_font_dimensions
config_help_font_presets
config_help_font_sizes
config_help_font_sizes_long
config_help_force_fullscreen
config_help_fps_limit
config_help_generic_color
config_help_graphics_mode
config_help_immediate
config_help_key_layout
config_help_key_mapping
config_help_lock_custom_resolution_ratio
config_help_orientation
config_help_parallel_redraw
config_help_reset_layout
config_help_scale_ratio
config_help_scale_type
config_help_screen_gravity
config_help_screen_padding
config_help_screen_size
config_help_shader
config_help_shader_tuning
config_help_skin
config_help_skip_resume
config_help_soundbank
config_help_system_properties
config_help_system_properties_long
config_help_timing_mode
config_help_timing_unavailable
config_help_touch_input
config_help_virtual_keyboard
config_help_vk_alpha
config_help_vk_background
config_help_vk_button_shape
config_help_vk_feedback
config_help_vk_force_opacity
config_help_vk_foreground
config_help_vk_hide_delay
config_help_vk_outline
config_help_vk_selected_background
config_help_vk_selected_foreground
config_increase_value
config_lock_custom_resolution_ratio
config_maintenance
config_message_delete_app_data
config_message_reset_profile_settings
config_message_reset_settings
config_missing_app_storage_generic
config_missing_app_storage_named
config_reset_all_settings
config_reset_all_settings_summary
config_select_screen_size
config_swap_screen_size
config_system_properties_info
config_system_properties_value
config_virtual_controls_preview_hint
config_warning_filter_shader
copy_selected_reports
crash_report_bundle_cleanup_failed
crash_report_bundle_failed
crash_report_bundle_preparing
crash_report_bundle_ready_message
crash_report_bundle_ready_title
crash_report_copied
crash_report_delete_failed
crash_report_delete_message
crash_report_delete_title
crash_report_github_unavailable
crash_report_java_report
crash_report_list_subtitle
crash_report_locate_failed
crash_report_midlet_failure
crash_report_process_exit
crash_report_unavailable
crash_reports
crash_reports_batch_delete_message
crash_reports_batch_delete_title
crash_reports_bundle_cleanup_failed
crash_reports_copied
crash_reports_delete_failed
crash_reports_empty
crash_reports_loading
crash_reports_selected_count
delete_selected_reports
diagnostic_reason_anr
diagnostic_reason_java_crash
diagnostic_reason_native_crash
diagnostic_reason_signal
diagnostic_reason_unknown
diagnostic_report
dismiss_mapping
file_picker_create_folder_failed
file_picker_empty
file_picker_empty_folder
file_picker_folder_exists
file_picker_invalid_folder_name
file_picker_load_failed
file_picker_navigate_back
file_picker_no_matches
file_picker_parent_folder
file_picker_permission_denied
file_picker_permission_message
file_picker_permission_required
file_picker_select_all
file_picker_select_app_bundle
file_picker_selected_count
file_picker_selection_unavailable
file_picker_sort_modified_newest
file_picker_sort_modified_oldest
file_picker_storage_volume
file_picker_unselect_all
fps_limit_maximum
install_done_with_warning
installer_ambiguous
installer_cancel_pending
installer_conversion_omitted_details
installer_conversion_skipped_entry
installer_conversion_warning_summary
installer_copy_details
installer_error_message
installer_existing_data_preserved
installer_failed_conversion
installer_failed_download
installer_failed_storage
installer_hide_details
installer_needs_recovery
installer_not_processed
installer_partial_restore
installer_refresh_library
installer_removed_leftovers
installer_retry_remaining
installer_separate_copy
installer_show_details
installer_stage_download
installer_stage_save
installer_stage_wait
installer_title
layout_edit_continue
layout_edit_done
layout_edit_save_changes
library_action_about_summary
library_action_crash_reports_summary
library_action_export_bundle
library_action_help_summary
library_action_import_bundle
library_action_import_bundle_summary
library_action_licenses_summary
library_action_save_log_summary
library_action_settings_summary
library_back
library_bulk_delete_apps
library_bulk_delete_confirmation
library_bulk_delete_result
library_bulk_export_apps
library_bulk_share_apps
library_collapse_description
library_collection_manage_apps_summary
library_collection_member_count
library_collection_members_empty
library_collection_ready_empty_message
library_collection_remove
library_collection_remove_from_current
library_collection_select_message
library_collections_empty_message
library_collections_empty_title
library_collections_unavailable_title
library_description_less
library_description_more
library_donation_browser_unavailable
library_donation_continue
library_donation_link_copied
library_donation_message
library_donation_provider_note
library_donation_summary
library_donation_title
library_enhanced_icons_summary
library_enhanced_icons_title
library_expand_description
library_export_chooser_title
library_export_progress
library_favorite_coming_soon
library_filter_recently_opened
library_grid_spacing_compact
library_grid_spacing_none
library_grid_spacing_spacious
library_grid_spacing_standard
library_grid_spacing_title
library_hide_grid_titles
library_hide_grid_titles_summary
library_icon_ratio_title
library_icon_shape_square
library_import_done
library_import_done_with_warning
library_import_invalid_bundle
library_import_preparing
library_import_restore_existing
library_import_restoring
library_indexing_current
library_indexing_progress
library_load_error_title
library_load_failed_message
library_metadata_reset_to_original
library_no_matches
library_play_stats_summary
library_reinstall_source_missing
library_remove_from_favorites_action
library_selection_checkbox_description
library_selection_checked
library_selection_count
library_selection_unchecked
library_show_list_description
library_show_list_description_summary
library_transfer_unavailable
licenses_unavailable
loading_apps
locate_bundle
log_save_failed
log_save_started
mapping_dialog_add_input
mapping_dialog_assigned_inputs
mapping_dialog_message
memory_editor_add_to_watch
memory_editor_bubble
memory_editor_cancelled
memory_editor_candidate_ambiguous
memory_editor_candidate_instance
memory_editor_candidate_lost
memory_editor_candidate_relocating
memory_editor_candidate_static
memory_editor_candidate_untraced
memory_editor_candidates
memory_editor_change_amount
memory_editor_edit_batch
memory_editor_edit_batch_help
memory_editor_edit_batch_no_common_type
memory_editor_edit_batch_type_notice
memory_editor_engine_reconnecting
memory_editor_engine_unavailable
memory_editor_expression_group_hint
memory_editor_expression_group_needs_type
memory_editor_expression_query_help
memory_editor_expression_single_hint
memory_editor_freeze
memory_editor_freeze_after_edit
memory_editor_freeze_after_edit_help
memory_editor_freeze_paused
memory_editor_identity_unsafe
memory_editor_increment_invalid
memory_editor_increment_preview
memory_editor_incremental_edit
memory_editor_inspector_empty
memory_editor_inspector_loading
memory_editor_inspector_no_rows
memory_editor_invalid_request
memory_editor_max_change
memory_editor_min_change
memory_editor_next_results_page_action
memory_editor_next_scan_result
memory_editor_no_candidates
memory_editor_no_runtime
memory_editor_no_search_history
memory_editor_no_watch
memory_editor_partial_write
memory_editor_path_changed
memory_editor_path_incomplete
memory_editor_peek_underlay
memory_editor_peek_underlay_active
memory_editor_predicate_changed_by
memory_editor_predicate_decreased_by
memory_editor_predicate_decreased_range
memory_editor_predicate_increased_by
memory_editor_predicate_increased_range
memory_editor_previous_results_page_action
memory_editor_reference_instance
memory_editor_reference_static
memory_editor_remove_candidates_description
memory_editor_remove_candidates_title
memory_editor_resource_limit
memory_editor_results
memory_editor_revert_and_remove
memory_editor_revert_remove_commit_failed
memory_editor_revert_remove_outcome
memory_editor_safety_limit
memory_editor_search_complete
memory_editor_search_known_values
memory_editor_search_progress
memory_editor_search_unknown_values
memory_editor_searching
memory_editor_selected
memory_editor_source_unknown
memory_editor_type_auto
memory_editor_unfreeze
memory_editor_unsupported
memory_editor_watch
memory_editor_working
memory_editor_write_confirmed
memory_editor_write_failure_outcome
memory_editor_write_outcome
message_reset_layout
midlet_failure_recovery_message
midlet_failure_recovery_message_named
midlet_failure_recovery_title
midlet_running_notification_channel
midlet_running_notification_text
midlet_running_notification_title
msg_error_loading_shader
msg_immediate_mode_disabled
msg_no_matches
msg_press_again_to_close
open_github
perf_overlay_bottom_left
perf_overlay_bottom_right
perf_overlay_cap
perf_overlay_coalesced
perf_overlay_copy
perf_overlay_cpu
perf_overlay_display
perf_overlay_fps
perf_overlay_frame_queue
perf_overlay_group_pipeline
perf_overlay_group_queue
perf_overlay_group_rate
perf_overlay_group_resources
perf_overlay_group_runtime
perf_overlay_group_timing
perf_overlay_guest_fps
perf_overlay_help
perf_overlay_input_queue
perf_overlay_interval
perf_overlay_java
perf_overlay_max
perf_overlay_native
perf_overlay_p95
perf_overlay_paint
perf_overlay_parameters
perf_overlay_parameters_help
perf_overlay_position
perf_overlay_position_help
perf_overlay_preset
perf_overlay_preset_help
perf_overlay_ram
perf_overlay_render_interval
perf_overlay_render_max
perf_overlay_render_p95
perf_overlay_renderer
perf_overlay_selected
perf_overlay_speed
perf_overlay_submit
perf_overlay_thermal
perf_overlay_title
perf_overlay_top_left
perf_overlay_top_right
pref_accent_amber
pref_accent_blue
pref_accent_classic_blue
pref_accent_coral
pref_accent_green
pref_accent_indigo
pref_accent_rose
pref_accent_sapphire
pref_accent_teal
pref_accent_title
pref_accent_violet
pref_apps_view
pref_audio_title
pref_enable_statusbar_summary
pref_screen_padding_title
pref_skin_title
pref_skip_resume_call
pref_title_emulation
pref_use_display_cutout_summary
pref_use_display_cutout_title
preset_apply
preset_apply_failed_summary
preset_based_on_modified
preset_builtin
preset_builtin_summary
preset_change_default
preset_choose_parts
preset_continue_editing
preset_custom_configuration
preset_default_picker_title
preset_default_policy_summary
preset_discard_changes_summary
preset_discard_changes_title
preset_edit_title
preset_includes_keyboard_layout
preset_invalid_name
preset_keyboard_layout_unavailable
preset_manager_default_section
preset_manager_keyboard_section
preset_manager_presets_section
preset_name_exists
preset_picker_title
preset_save_as
preset_save_help
preset_saved_unlinked_warning
preset_unavailable_summary
preset_update
preset_update_summary
preset_update_title
preset_use
preset_virtual_keyboard_off
preset_virtual_keyboard_on
process_exit_recovery_message
process_exit_recovery_message_midlet
process_exit_recovery_message_named
process_exit_recovery_title
profile_apply_layout_keeps_settings
profile_apply_layout_only
profile_apply_settings_keeps_layout
profile_apply_whole_follows
profile_apply_whole_without_layout
profile_builtin_settings
profile_create_summary
profile_default_badge_short
profile_default_fallback_notice
profile_delete_template_message
profile_follows_updates
profile_more_actions
profile_name_exists
profile_template_operation_failed
profile_template_summary
reconversion_done_with_warning
reconversion_error
reconversion_error_summary
reconverting_wait
remove_screen_preset
removed
report_on_github
runtime_apply
runtime_installation_changed
runtime_invalid_intent
runtime_layout_apply_title
runtime_midlet_list_failed
runtime_no_midlets
runtime_preset_apply_destination
runtime_preset_local_explanation
runtime_preset_save_destination
runtime_preset_this_midlet_only
runtime_preset_update_destination
runtime_preset_update_explanation
runtime_preset_update_failed
runtime_preset_update_unlinked
runtime_replacement_failed
runtime_storage_in_use
runtime_storage_unavailable
runtime_virtual_controls_dpad
runtime_virtual_controls_edit
runtime_virtual_controls_edit_guide_dont_show_again
runtime_virtual_controls_edit_guide_message
runtime_virtual_controls_edit_guide_title
runtime_virtual_controls_show_controls
runtime_virtual_controls_switch_layout
runtime_virtual_controls_template_analog_standard
runtime_virtual_controls_template_dpad_standard
runtime_virtual_controls_title
saved_keyboard_layout_summary
screenshot_save_failed
screenshot_saved_path
select_all_reports
settings_profiles_summary
settings_section_midlet_runtime
updates_automatic
updates_available
updates_browser_unavailable
updates_build_code
updates_channel
updates_current
updates_debug_build
updates_failed
updates_installed_version
updates_last_checked
updates_link_copied
updates_more_available
updates_more_summary
updates_no_release
updates_not_checked
updates_not_installable
updates_notice
updates_preview
updates_stable
updates_view_release
view_reports
virtual_controls_save_failed
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

- `FORCE_CLOSE_CONFIRMATION`: Existing wording refers to app forced closure/data corruption; current text names MIDlet stop and only potential unsaved-change loss. Cannot certify or safely correct Santali wording.
- `PREF_FORCE_FULLSCREEN`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_IMMEDIATE`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_LAYOUT`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_LIMIT_FPS`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_SCALE_RATIO`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_SYS_PROPS`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_TOUCH_INPUT`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VIRTUAL_KEYBOARD_OPTIONS`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_ALPHA`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_BACK`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_FEEDBACK`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_FORCE_OPACITY`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_FORE`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_HIDE_DELAY`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_OUTLINE`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_SEL_BACK`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `PREF_VK_SEL_FORE`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `RESET_LAYOUT_CMD`: Existing text names key layout rather than the explicitly scoped virtual controls layout. Correct standard Santali wording is unresolved.
- `action_lock_orientation`: Existing compound wording cannot be certified as screen rotation lock rather than lock screen orientation without competent Santali review.
- `alert_map_menu`: Competent standard Santali Ol Chiki judgment unavailable; preserve existing bytes without accepting semantics.
- `alert_msg_workdir_not_exists`: Competent standard Santali Ol Chiki judgment unavailable; preserve existing bytes without accepting semantics.
- `converting_wait`: Existing Ol Chiki progress text retained; requires competent Santali verification of JAR conversion phase grammar.
- `create_apps_dir_failed`: Existing wording/argument differs from current working-directory preparation failure with explicit %1$s and supported recovery. Cannot safely correct complete text.
- `install_done`: Existing Ol Chiki success sentence retained; requires competent Santali review of completed installation result and register.
- `install_jar_non_matched_jad`: Existing Ol Chiki confirmation retained; competent Santali review must preserve mismatch identity and decision to ignore only the JAD.
- `invalid_resolution_not_saved`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `layout_edit_finish`: Existing Ol Chiki label retained unchanged. Requires competent Santali verification of finish-editing action; must not imply save completion.
- `layout_edit_finished`: Existing Ol Chiki label retained unchanged. Requires competent Santali verification of virtual-controls editing-complete status; cannot certify its editing-mode wording or whether it implies persistence.
- `layout_switch`: Existing Ol Chiki label retained unchanged. Canonical action now chooses a control layout including D-pad/analog, not only switching key layout; competent Santali revision required.
- `loading_info`: Existing abbreviated information/loading wording cannot be certified as natural current standard Santali UI.
- `mapping_dialog_key_not_specified`: Existing Ol Chiki label retained unchanged. Canonical empty state means no physical inputs assigned, not an unspecified key; competent Santali revision required.
- `mapping_dialog_title`: Existing Ol Chiki label retained unchanged. Canonical capture title accepts a key or controller button, not only a key; competent Santali revision required.
- `midlet_vendor`: Existing Ol Chiki vendor label retained; appears to use a merchant/trader term, and software publisher/vendor meaning requires competent Santali review.
- `no_data_for_display`: Existing text names no data rather than no apps. Correct scope needs standard Santali wording.
- `parallel_screen_redrawing`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `permission_request_failed`: Competent standard Santali Ol Chiki judgment unavailable; preserve existing bytes without accepting semantics.
- `pref_app_sort_date`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_app_sort_vendor`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_button_shape_round`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_category_experimental`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_emulator_dir`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_enable_actionbar_summary`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_enable_actionbar_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_enable_statusbar_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_graphics_hw_gles`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_graphics_hw_surface`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_graphics_hw_view`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_graphics_mode_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_map_keys`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_mascot_summary`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_mascot_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_screen_scale_type_fill`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_screen_scale_type_fit`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_screen_scale_type_none`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_screenshot_summary`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_theme_auto_battery`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_theme_auto_time`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_theme_system`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vibration_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_hide_hint`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_save_alert`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_arrows`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_arrows_numbers`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_custom`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_numbers`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_numbers_arrows`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_phone`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_vk_type_phone_arrows`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `pref_wakelock_title`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `reinstall`: Existing Ol Chiki prose retained; competent review needed for reinstall decision, preservation clause, and Android newline rendering.
- `reinstall_newest`: Existing Ol Chiki prose retained; competent review needed for target/current version roles and preserved-data clause.
- `reinstall_older`: Existing Ol Chiki prose retained; competent review needed for downgrade target/current roles and possible failure with retained data.
- `saved`: Competent standard Santali Ol Chiki judgment unavailable; preserve existing bytes without accepting semantics.
- `take_screenshot`: Existing Ol Chiki text preserved: competent standard Santali semantic and grammar judgment is unavailable for this identity.
- `warn_install_from_net`: Existing Ol Chiki sentence retained; requires competent review of future download, rather than completed download.

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

## Resume point

Resolve the remaining keys above with competent language review and the existing
feature contracts, then check cross-feature consistency and representative
long-text, narrow-screen, RTL and script rendering. Current resource checkpoint
`d6616507f` passes [cumulative Android CI](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38573678907),
including build/tests, lint and screenshot validation; local rendering evidence
is in [the progress record](localization-progress.md). Future resource changes
need corresponding validation. Passing checks do not resolve the remaining
language judgments. Keep PR 169 Draft; no merge is authorized.
