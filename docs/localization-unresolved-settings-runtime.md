# Unresolved settings and runtime language review

Snapshot: 13 October 2026, PR 169. This records unaccepted review cases in
the 175-resource presentation/settings/performance and 51-resource runtime/
diagnostics selections. It is not another translation catalog. See
[progress](localization-progress.md), [controls gaps](localization-unresolved-controls.md),
[installer gaps](localization-unresolved-installer.md), and
[Memory Editor gaps](localization-unresolved-memory.md).

## Review limitation

The blocker for every marked identity below is unavailable competent target-
language judgment for its grammar, terminology, or full product meaning.
Existing text is preserved without certification; missing declarations remain
missing. No English fallback or guessed technical prose is counted as accepted.
Santali requires standard Ol Chiki competence; script/dictionary/plural tables
alone cannot establish it. Kazakh, Odia and Sinhala acceptance is limited to
reliable labels or raw formatter-only displays, including same-role terms
already verified in the earlier feature batches.

| Batch | Locale | Accepted | Unresolved missing | Unresolved existing |
| --- | --- | ---: | ---: | ---: |
| presentation | kk | 25 | 127 | 23 |
| presentation | or | 24 | 135 | 16 |
| presentation | sat | 0 | 87 | 88 |
| presentation | si | 8 | 167 | 0 |
| runtime | kk | 1 | 50 | 0 |
| runtime | or | 1 | 47 | 3 |
| runtime | sat | 0 | 47 | 4 |
| runtime | si | 1 | 50 | 0 |

The 844 unresolved cases comprise 710 missing and 134 existing declarations.
These cases are separate from earlier batches; do not infer a whole-catalog
linguistic certification or a deduplicated total from per-batch review counts.

## Product distinctions requiring competent rendering

- Settings: initial command-bar suppression, Android font scaling, asynchronous
  presentation, virtual-control opacity/colors and layout replacement.
- Display/audio: virtual resolution, raw screenshot before scaling/filter/shader,
  status-bar/cutout mutual effects, MIDI soundbank rather than all audio.
- Performance: renderer consumption versus MIDlet publication versus physical
  presentation; configured speed, core equivalents, thermal severity, separate
  queue domains and frame-interval percentiles/maxima. See
  [measurement definitions](performance-overlay.md).
- Runtime: failed stop/replacement, launch request/path, other-runtime storage
  ownership, callback nesting, locally saved diagnostics and continuing app use.
- Diagnostics: attach before submitting, public exposure on attachment, permanent
  local report/generated-bundle deletion, uploaded copies unaffected, and primary
  deletion versus partial secondary cleanup. See [bundle contract](diagnostic-bundles.md).

## Exact unresolved identities

`M` = missing declaration; `E` = existing declaration preserved for language
review; `—` = accepted in this batch. The competence limitation above applies
to each marked locale/key. Re-export current inventory before resolving a case.

### Presentation, settings and performance

| Identity | kk | or | si | sat |
| --- | --- | --- | --- | --- |
| `PREF_EMULATION_SPEED` | M | M | M | M |
| `PREF_FILTER` | M | M | M | E |
| `PREF_FONT_ANTI_ALIASING` | E | M | M | E |
| `PREF_FONT_LARGE` | — | — | M | E |
| `PREF_FONT_MEDIUM` | — | — | M | E |
| `PREF_FONT_SIZE_IN_SP` | M | M | M | E |
| `PREF_FONT_SMALL` | — | — | M | E |
| `PREF_FORCE_FULLSCREEN` | M | M | M | E |
| `PREF_HEIGHT` | — | — | M | E |
| `PREF_IMMEDIATE` | M | M | M | E |
| `PREF_LAYOUT` | M | M | M | E |
| `PREF_LIMIT_FPS` | M | M | M | E |
| `PREF_ORIENTATION` | E | M | M | E |
| `PREF_SCALE_RATIO` | M | M | M | E |
| `PREF_SHADER_FILTER` | E | M | M | E |
| `PREF_SYS_PROPS` | E | M | M | E |
| `PREF_TIMING_MODE` | M | M | M | M |
| `PREF_TOUCH_INPUT` | M | M | M | E |
| `PREF_UNIT_MS` | — | — | — | M |
| `PREF_VIRTUAL_KEYBOARD_OPTIONS` | M | M | M | E |
| `PREF_VK_ALPHA` | E | M | M | E |
| `PREF_VK_BACK` | M | M | M | E |
| `PREF_VK_FEEDBACK` | M | M | M | E |
| `PREF_VK_FORCE_OPACITY` | M | M | M | E |
| `PREF_VK_FORE` | M | M | M | E |
| `PREF_VK_HIDE_DELAY` | M | M | M | E |
| `PREF_VK_OUTLINE` | M | M | M | E |
| `PREF_VK_SEL_BACK` | E | M | M | E |
| `PREF_VK_SEL_FORE` | E | M | M | E |
| `PREF_WIDTH` | — | E | M | E |
| `SIZE_PRESETS` | E | M | M | E |
| `SWAP_SIZES` | E | M | M | E |
| `fps_limit_maximum` | M | M | M | M |
| `identity_filter` | E | M | M | E |
| `invalid_resolution_not_saved` | M | E | M | E |
| `msg_error_loading_shader` | M | M | M | M |
| `parallel_screen_redrawing` | E | M | M | E |
| `perf_overlay_bottom_left` | M | M | M | M |
| `perf_overlay_bottom_right` | M | M | M | M |
| `perf_overlay_cap` | M | M | M | M |
| `perf_overlay_coalesced` | M | M | M | M |
| `perf_overlay_copy` | M | M | M | M |
| `perf_overlay_cpu` | M | M | M | M |
| `perf_overlay_custom` | — | — | — | M |
| `perf_overlay_debug` | M | M | M | M |
| `perf_overlay_display` | M | M | M | M |
| `perf_overlay_done` | M | M | M | M |
| `perf_overlay_fps` | M | M | M | M |
| `perf_overlay_frame_queue` | M | M | M | M |
| `perf_overlay_group_pipeline` | M | M | M | M |
| `perf_overlay_group_queue` | M | M | M | M |
| `perf_overlay_group_rate` | M | M | M | M |
| `perf_overlay_group_resources` | M | M | M | M |
| `perf_overlay_group_runtime` | M | M | M | M |
| `perf_overlay_group_timing` | M | M | M | M |
| `perf_overlay_guest_fps` | M | M | M | M |
| `perf_overlay_help` | M | M | M | M |
| `perf_overlay_input_queue` | M | M | M | M |
| `perf_overlay_interval` | M | M | M | M |
| `perf_overlay_java` | M | M | M | M |
| `perf_overlay_max` | M | M | M | M |
| `perf_overlay_minimal` | M | M | M | M |
| `perf_overlay_native` | M | M | M | M |
| `perf_overlay_p95` | M | M | M | M |
| `perf_overlay_paint` | M | M | M | M |
| `perf_overlay_parameters` | M | M | M | M |
| `perf_overlay_parameters_help` | M | M | M | M |
| `perf_overlay_position` | M | M | M | M |
| `perf_overlay_position_help` | M | M | M | M |
| `perf_overlay_preset` | M | M | M | M |
| `perf_overlay_preset_help` | M | M | M | M |
| `perf_overlay_ram` | M | M | M | M |
| `perf_overlay_render_interval` | M | M | M | M |
| `perf_overlay_render_max` | M | M | M | M |
| `perf_overlay_render_p95` | M | M | M | M |
| `perf_overlay_renderer` | M | M | M | M |
| `perf_overlay_selected` | M | M | M | M |
| `perf_overlay_speed` | M | M | M | M |
| `perf_overlay_standard` | M | M | M | M |
| `perf_overlay_submit` | M | M | M | M |
| `perf_overlay_thermal` | M | M | M | M |
| `perf_overlay_title` | M | M | M | M |
| `perf_overlay_top_left` | M | M | M | M |
| `perf_overlay_top_right` | M | M | M | M |
| `pref_accent_amber` | M | M | M | M |
| `pref_accent_blue` | M | M | M | M |
| `pref_accent_classic_blue` | M | M | M | M |
| `pref_accent_coral` | M | M | M | M |
| `pref_accent_cyan` | M | M | M | M |
| `pref_accent_green` | M | M | M | M |
| `pref_accent_indigo` | M | M | M | M |
| `pref_accent_orange` | M | M | M | M |
| `pref_accent_pink` | M | M | M | M |
| `pref_accent_rose` | M | M | M | M |
| `pref_accent_sapphire` | M | M | M | M |
| `pref_accent_teal` | M | M | M | M |
| `pref_accent_title` | M | M | M | M |
| `pref_accent_violet` | M | M | M | M |
| `pref_app_sort_ascending` | M | M | M | M |
| `pref_app_sort_date` | M | E | M | E |
| `pref_app_sort_descending` | M | M | M | M |
| `pref_app_sort_name` | — | — | — | E |
| `pref_app_sort_vendor` | — | E | — | E |
| `pref_apps_view` | M | M | M | M |
| `pref_audio_title` | — | — | — | M |
| `pref_button_shape_oval` | M | — | M | E |
| `pref_button_shape_rect` | M | — | M | E |
| `pref_button_shape_round` | M | E | M | E |
| `pref_button_shape_title` | M | — | M | E |
| `pref_category_experimental` | E | M | M | E |
| `pref_emulator_dir` | — | — | M | E |
| `pref_enable_actionbar_summary` | M | E | M | E |
| `pref_enable_actionbar_title` | M | E | M | E |
| `pref_enable_statusbar_summary` | M | M | M | M |
| `pref_enable_statusbar_title` | M | E | M | E |
| `pref_encoding_title` | M | — | M | E |
| `pref_graphics_hw_gles` | M | M | M | E |
| `pref_graphics_hw_surface` | M | M | M | E |
| `pref_graphics_hw_view` | M | M | M | E |
| `pref_graphics_mode_title` | E | M | M | E |
| `pref_graphics_software` | M | M | M | E |
| `pref_language` | M | M | M | M |
| `pref_map_keys` | M | — | M | E |
| `pref_mascot_summary` | E | M | M | E |
| `pref_mascot_title` | E | M | M | E |
| `pref_orientation_auto` | — | — | — | E |
| `pref_orientation_default` | E | M | M | E |
| `pref_orientation_land` | — | M | M | E |
| `pref_orientation_port` | — | M | M | E |
| `pref_screen_gravity` | E | M | M | E |
| `pref_screen_gravity_bottom` | — | — | M | E |
| `pref_screen_gravity_center` | — | — | M | E |
| `pref_screen_gravity_left` | — | — | M | E |
| `pref_screen_gravity_right` | — | — | M | E |
| `pref_screen_gravity_top` | — | — | M | E |
| `pref_screen_padding_title` | M | M | M | M |
| `pref_screen_scale_type` | M | M | M | E |
| `pref_screen_scale_type_fill` | M | M | M | E |
| `pref_screen_scale_type_fit` | — | M | M | E |
| `pref_screen_scale_type_none` | E | M | M | E |
| `pref_screenshot_summary` | M | E | M | E |
| `pref_screenshot_title` | — | E | M | E |
| `pref_skin_not_set` | M | M | M | M |
| `pref_skin_title` | M | M | M | M |
| `pref_skip_resume_call` | M | M | M | M |
| `pref_soundbank_title` | M | M | M | M |
| `pref_theme_auto_battery` | M | E | M | E |
| `pref_theme_auto_time` | M | E | M | E |
| `pref_theme_light` | M | E | M | E |
| `pref_theme_night` | M | E | M | E |
| `pref_theme_system` | M | E | M | E |
| `pref_theme_title` | M | — | M | E |
| `pref_title_emulation` | M | M | M | M |
| `pref_use_display_cutout_summary` | M | M | M | M |
| `pref_use_display_cutout_title` | M | M | M | M |
| `pref_vibration_title` | M | E | M | E |
| `pref_vk_hide_hint` | E | M | M | E |
| `pref_vk_save_alert` | M | M | M | E |
| `pref_vk_type_arrows` | E | M | M | E |
| `pref_vk_type_arrows_numbers` | E | M | M | E |
| `pref_vk_type_custom` | — | — | — | E |
| `pref_vk_type_numbers` | — | M | M | E |
| `pref_vk_type_numbers_arrows` | M | M | M | E |
| `pref_vk_type_phone` | E | M | M | E |
| `pref_vk_type_phone_arrows` | E | M | M | E |
| `pref_wakelock_title` | M | — | M | E |
| `settings_profiles_summary` | M | M | M | M |
| `settings_section_appearance` | M | M | M | M |
| `settings_section_language` | M | M | M | M |
| `settings_section_library_appearance` | M | M | M | M |
| `settings_section_midlet_runtime` | M | M | M | M |
| `settings_section_profiles` | — | — | — | M |
| `settings_section_storage` | M | M | M | M |
| `shader_tuning` | — | M | M | E |
| `take_screenshot` | M | M | M | E |

### Runtime, diagnostics and miscellaneous copy

| Identity | kk | or | si | sat |
| --- | --- | --- | --- | --- |
| `alert_map_menu` | M | E | M | E |
| `alert_msg_workdir_not_exists` | M | E | M | E |
| `crash_report_bundle_cleanup_failed` | M | M | M | M |
| `crash_report_bundle_failed` | M | M | M | M |
| `crash_report_bundle_preparing` | M | M | M | M |
| `crash_report_bundle_ready_message` | M | M | M | M |
| `crash_report_bundle_ready_title` | M | M | M | M |
| `crash_report_copied` | M | M | M | M |
| `crash_report_delete_failed` | M | M | M | M |
| `crash_report_delete_message` | M | M | M | M |
| `crash_report_delete_title` | M | M | M | M |
| `crash_report_github_unavailable` | M | M | M | M |
| `crash_report_java_report` | M | M | M | M |
| `crash_report_list_subtitle` | — | — | — | M |
| `crash_report_locate_failed` | M | M | M | M |
| `crash_report_midlet_failure` | M | M | M | M |
| `crash_report_process_exit` | M | M | M | M |
| `crash_report_unavailable` | M | M | M | M |
| `crash_reports` | M | M | M | M |
| `crash_reports_batch_delete_message` | M | M | M | M |
| `crash_reports_batch_delete_title` | M | M | M | M |
| `crash_reports_bundle_cleanup_failed` | M | M | M | M |
| `crash_reports_copied` | M | M | M | M |
| `crash_reports_delete_failed` | M | M | M | M |
| `crash_reports_empty` | M | M | M | M |
| `crash_reports_loading` | M | M | M | M |
| `crash_reports_selected_count` | M | M | M | M |
| `diagnostic_report` | M | M | M | M |
| `memory_editor` | M | M | M | M |
| `midlet_failure_recovery_message` | M | M | M | M |
| `midlet_failure_recovery_message_named` | M | M | M | M |
| `midlet_failure_recovery_title` | M | M | M | M |
| `midlet_running_notification_channel` | M | M | M | M |
| `midlet_running_notification_text` | M | M | M | M |
| `midlet_running_notification_title` | M | M | M | M |
| `msg_immediate_mode_disabled` | M | M | M | M |
| `msg_no_matches` | M | M | M | M |
| `msg_press_again_to_close` | M | M | M | M |
| `permission_request_failed` | M | E | M | E |
| `process_exit_recovery_message` | M | M | M | M |
| `process_exit_recovery_message_midlet` | M | M | M | M |
| `process_exit_recovery_message_named` | M | M | M | M |
| `process_exit_recovery_title` | M | M | M | M |
| `runtime_installation_changed` | M | M | M | M |
| `runtime_invalid_intent` | M | M | M | M |
| `runtime_midlet_list_failed` | M | M | M | M |
| `runtime_no_midlets` | M | M | M | M |
| `runtime_replacement_failed` | M | M | M | M |
| `runtime_storage_in_use` | M | M | M | M |
| `runtime_storage_unavailable` | M | M | M | M |
| `saved` | M | M | M | E |

## Additional existing metadata-field uncertainty

`or` / `library_metadata_vendor` is preserved for competent review. The current
term denotes a seller; the field holds software provider/publisher metadata from
`MIDlet-Vendor`/`source_vendor`. A dictionary seller equivalence does not establish
this software role or a natural replacement. This is one additional existing
review case outside the 226-resource selections above, identified by comparing
Library, sort and descriptor labels for the same stored value. It does not add
a missing declaration or authorize guessed Odia text.
