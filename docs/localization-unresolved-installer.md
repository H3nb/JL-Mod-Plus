# Unresolved installer language review

This is a historical feature checkpoint. Active remaining identities and counts
are consolidated in [current language-review gaps](localization-language-review.md)
after the deferred-language recovery; do not treat resolved entries below as
current blockers.

Snapshot: 13 October 2026, PR 169. These are unaccepted review cases in the
116-resource installer/bulk-installation selection, not a second translation
catalog. See [progress](localization-progress.md) and the separate
[controls gaps](localization-unresolved-controls.md).

## Scope and reason

- Kazakh: 48 unresolved identities, including 47 missing declarations and one
  existing reinstall explanation needing competent review.
- Odia: 49 unresolved identities, including 47 missing declarations and two
  existing explanations needing competent review.
- Sinhala: 49 missing messages need competent sentence-level review.
- Santali: 113 unresolved identities, including 104 missing declarations and nine
  existing explanations/labels. Three reliable existing isolated labels were
  retained after review; no new Santali text was manufactured.

The accepted subsets contain reliable labels, actions, short states, and bounded
count messages. They do not establish grammar or semantic fidelity for technical
or consequential prose. Remaining messages cover downgrade risk, preservation of
settings/save data, partial success, cancellation boundaries, conversion-class
warnings, and source/catalog recovery. Do not count fallback English as translation.

## Existing declarations requiring review

- `kk` / `reinstall`: kk: competent sentence-level review unavailable for consequential operation scope, timing, retained data, or partial-success clauses; no new text accepted; any existing translation remains unchanged.
- `or` / `install_jar_non_matched_jad`: or: competent sentence-level review unavailable for technical sentence grammar and full conditional/consequence fidelity; no new text accepted; any existing translation remains unchanged.
- `or` / `reinstall`: or: competent sentence-level review unavailable for consequential operation scope, timing, retained data, or partial-success clauses; no new text accepted; any existing translation remains unchanged.
- `sat` / `converting_wait`: Existing Ol Chiki progress text retained; requires competent Santali verification of JAR conversion phase grammar.
- `sat` / `install_done`: Existing Ol Chiki success sentence retained; requires competent Santali review of completed installation result and register.
- `sat` / `install_jar_non_matched_jad`: Existing Ol Chiki confirmation retained; competent Santali review must preserve mismatch identity and decision to ignore only the JAD.
- `sat` / `midlet_vendor`: Existing Ol Chiki vendor label retained; appears to use a merchant/trader term, and software publisher/vendor meaning requires competent Santali review.
- `sat` / `midlet_version`: Existing Ol Chiki label retained; competent review must establish the MIDlet-Version metadata meaning rather than a generic updated/current adjective.
- `sat` / `reinstall`: Existing Ol Chiki prose retained; competent review needed for reinstall decision, preservation clause, and Android newline rendering.
- `sat` / `reinstall_newest`: Existing Ol Chiki prose retained; competent review needed for target/current version roles and preserved-data clause.
- `sat` / `reinstall_older`: Existing Ol Chiki prose retained; competent review needed for downgrade target/current roles and possible failure with retained data.
- `sat` / `warn_install_from_net`: Existing Ol Chiki sentence retained; requires competent review of future download, rather than completed download.

## Exact unresolved identities

Each list applies to exactly the named locales. Re-export current source and
target resources before writes; missing and existing cases require the same
product-semantic and language review.

### `kk`, `or`, `sat`, `si` — 48 identities

```text
bulk_install_cancel_requested
bulk_install_cancelled
bulk_install_complete
bulk_install_fatal
bulk_install_library_changed
bulk_install_omitted_sources
bulk_install_planning
bulk_install_prepare_failed
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
bulk_install_running
bulk_install_source_changed
bulk_install_source_unavailable
bulk_install_status_ambiguous
bulk_install_target_unverified
install_jar_non_matched_jad
installer_ambiguous
installer_cancel_pending
installer_conversion_omitted_details
installer_conversion_skipped_entry
installer_conversion_warning_summary
installer_error_message
installer_existing_data_preserved
installer_failed_conversion
installer_failed_download
installer_failed_storage
installer_needs_recovery
installer_partial_restore
installer_removed_leftovers
reconversion_done_with_warning
reconversion_error
reconversion_error_summary
reconverting_wait
reinstall
reinstall_newest
reinstall_older
```

### `or`, `sat`, `si` — 1 identities

```text
bulk_install_status_reinstall
```

### `sat` — 64 identities

```text
bulk_install_action_jar_only
bulk_install_action_separate_copy
bulk_install_attention_count
bulk_install_cancel
bulk_install_choose_files
bulk_install_choose_folder
bulk_install_clear
bulk_install_close
bulk_install_empty
bulk_install_empty_filter
bulk_install_failed_count
bulk_install_filter_all
bulk_install_filter_review
bulk_install_filter_selected
bulk_install_found
bulk_install_install_count
bulk_install_installed_count
bulk_install_installed_version
bulk_install_ready_count
bulk_install_recommended
bulk_install_reinstalled_count
bulk_install_restored_count
bulk_install_result_failed
bulk_install_result_installed
bulk_install_result_reinstalled
bulk_install_result_restored
bulk_install_result_skipped
bulk_install_result_updated
bulk_install_selected
bulk_install_skipped_count
bulk_install_skipped_entries
bulk_install_skipped_result_count
bulk_install_source
bulk_install_status_conflict
bulk_install_status_downgrade
bulk_install_status_duplicate
bulk_install_status_error
bulk_install_status_mismatch
bulk_install_status_new
bulk_install_status_older_batch
bulk_install_status_remote
bulk_install_status_same
bulk_install_status_update
bulk_install_title
bulk_install_unreadable_archives
bulk_install_updated_count
bulk_install_warning_count
converting_wait
install_done
install_done_with_warning
installer_copy_details
installer_hide_details
installer_not_processed
installer_refresh_library
installer_retry_remaining
installer_separate_copy
installer_show_details
installer_stage_download
installer_stage_save
installer_stage_wait
installer_title
midlet_vendor
midlet_version
warn_install_from_net
```
