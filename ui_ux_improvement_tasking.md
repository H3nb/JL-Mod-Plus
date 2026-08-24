# JL-Mod Plus UI/UX Improvement Tasking

Status: implementation complete in the current worktree; implementation follows the ordered work packages below.

Last audit: 2026-08-22; source/config/test claims below were rechecked against the current worktree after the prior plan review and the universal-bundle implementation slice.

Parallel audit: three read-only GPT-5.6 Luna Max lanes reviewed requirements/architecture, testing/adaptive/insets, and plan/history consistency; findings were reconciled below without source edits.

Branch: `improve/ui-ux-harmonization`

Base: `alpha` at `286d5018` (`Bulk MIDlet installation with safe batch planning and execution`)

Freshness check: `HEAD`, `alpha`, and `origin/alpha` all resolve to `286d501880dcc36419f43c008af0c38d95aef75d` at audit time.

PR shape: one improvement PR, delivered as multiple coherent commits. This document is local-only and must remain untracked.

Implementation checkpoint (2026-08-22): Library selection mode, icon-only selection controls, aligned contextual actions, shared search/shape foundations, accent/profile/glass treatments, app/collection scroll-anchor contracts, adaptive width-based navigation, universal v2 reader/writer/planner, single-app v2 restore, multi-app v2 routing through the bulk installer, themed app-owned notices, and the coordinated runtime chrome policy are implemented on the branch. Multi-app imports preserve filesystem payloads (JAR/config/data) while Room-only state remains local by policy. The latest slice also adds generation-safe bulk restore reconciliation, existing-app universal bundle restore, stale staging cleanup, ZIP routing/manifest hardening, canonical-directory cycle protection, runtime-menu chrome restoration, public framework resource lookups, Profiles IME resize, and localized runtime/selection copy. Durable process-death batch journaling, richer conflict UI, full device IME/system-bar evidence, and the global localization gate remain open gates.

Current focused implementation checkpoint (2026-08-23): the Config MIDlet slider now uses a
continuous custom Material3 track with a compact round thumb, no endpoint icons, and reserved
space for the automatic scroll affordance. Icon selection and app-bundle import now launch the
existing app-owned filesystem picker with purpose-specific titles and extension filters; picker
results are normalized to durable `file://` URIs, and icon/bundle readers accept both filesystem
and content-provider sources. The icon callback is keyed by the Room database id so a reordered
list cannot update the wrong app. Focused unit tests, Android-test compilation, lint, and the
85-case screenshot validation pass; no connected device is available for runtime picker/icon
execution evidence.

Final implementation validation (2026-08-22): the emulator debug Kotlin/Java and screenshot-test sources compile, screenshot references were visually reviewed and refreshed for the intentional UI changes, and `:app:validateEmulatorDebugScreenshotTest` passes all 85 cases. `:app:lintEmulatorDebug` reports 0 errors and 48 warnings; the requested safe findings (`UseKtx`, Compose autoboxing state creation/value access, `RtlHardcoded`, modifier-factory receiver misuse, obsolete SDK checks, `UseTomlInstead`, and `ViewConstructor`) are resolved. Remaining warnings are dependency/version availability, the existing x86_64 launcher ABI advisory, legacy default-resource `UnusedResources` entries retained for translation compatibility, the Java ME `UsableSpace` contract boundary, and existing raster launcher-shape advisories. The full emulator unit-test suite was rerun; 339 tests completed with one unrelated `org.microemu.cldc.socket.SocketConnectionTest` timeout (`closingInputFromAnotherThreadUnblocksRead`), including the same failure when run in isolation. No connected device is available, so the built-in profile theme instrumentation test is compiled but device execution and API 35+/IME/cutout evidence remain unverified.

## Follow-up feedback received after the first implementation slice

Follow-up implementation checkpoint (2026-08-22): addressed in the current worktree; the
remaining device-only system-bar/IME/cutout evidence is explicitly unverified because no
connected device is available.

Additional visual direction incorporated: the global Settings surface now follows the
non-tab Config MIDlet preference language (section spacing, surface treatment, row geometry,
and switch/value hierarchy). The About acknowledgement is integrated into the About body as a
concise, professional lineage note: JL-Mod Plus → JL-Mod by woesss/Yury Kharchenko →
J2ME-Loader by Nikita Shakarun, with the current maintainer and license-preservation note.

The next implementation slice must verify and close these user-reported regressions and additions:

1. Replace the current glass treatment with a visibly translucent, theme-aware system-bar scrim that keeps content readable beneath it; it must not look fully transparent.
2. Make hide-header/footer behavior deterministic when the content fits exactly, or is only one or two rows beyond the viewport, in both Apps and Collection surfaces.
3. Exercise and fix bulk share, bulk universal-bundle export, and bulk reinstall end-to-end, including provider paths, retained-source resolution, and installer re-install actions.
4. Bring global Settings option rows, sections, dialogs, spacing, typography, and shapes into the same visual system as the rest of the app.
5. Reorganize Library display options into a clearer hierarchy.
6. Add Library display preferences for rounded versus sharp icon containers (for both 1:1 and 3:4 ratios), plus list description visibility.
7. Add a zero-spacing grid option so adjacent tiles can touch.
8. Repair About and Licenses presentation and add a clear, respectful maintainer/contributor acknowledgement covering the human effort and project resources involved.

## Latest feedback addendum (2026-08-22)

The implementation audit also includes these follow-up requirements and regression checks:

- A favorite/star toggle is an in-place mutation: it may change only the star's filled state.
  It must not reorder, remeasure, or restore the Apps list/grid to a different anchor.
- The metadata editor keeps the originating list/grid mounted and restores one explicit stable
  anchor when it closes; ordinary favorite/stat updates must not replay that restoration effect.
- Library display exposes an Enhanced Icons toggle. Disabled mode renders the source bitmap
  without square adaptive normalization; enabled mode preserves the existing enhanced treatment
  across Apps, Collections, and the collection picker.
- Bulk action order and semantics remain explicit: Select, Edit Metadata, Add Shortcut, Add To
  Collection, Settings, Reinstall, Share App, Export App Bundle, Delete. Reinstall uses the direct
  retained-source path and does not show an install-conflict confirmation.
- Selection actions use a safe-area-aware Material navigation bar, and the contextual bar must not
  be clipped by gesture or three-button navigation in portrait/landscape or with the IME visible.
- FPS overlays, app-owned transient notices, legacy MIDP alerts/choice lists, and runtime/system
  chrome consume light/dark semantic colors and the selected global accent where that boundary is
  supported; Java ME specification colors remain unchanged.
- Scroll hints are data-driven: they are rendered only when content can scroll forward and use a
  compact themed affordance rather than an opaque label pasted over a non-scrollable popup.
- Show FPS remains small, translucent, and inset from display cutouts/rounded corners. System-bar
  protection is translucent in both themes while retaining icon legibility.

Acceptance for this slice: focused state/transfer coverage remains in the existing test stack;
the emulator debug build, screenshot reference validation, and lint gates are green; the tasking
document stays local and untracked. The full unit suite is currently blocked by the unrelated
socket timeout recorded above; connected-device evidence and the separately scoped full
localization inventory also remain open.

## Current worktree status (authoritative implementation checkpoint)

Completed in the current branch:

- Apps selection mode: long-press Select, stable generation-scoped IDs, checkbox list/grid toggles, icon-only select-all/unselect-all, hidden quick filters/favorites, aligned contextual action slots, localized accessibility state descriptions, and retry-preserving bulk deletion selection.
- Navigation return state: saveable destination/view/filter/sort/quick-view fields plus stable list/grid anchors for Apps, Collections, and Collection members; anchor restoration survives metadata/settings/MIDlet subtree replacement and supported recreation.
- Universal app-payload bundle v2: one-app and multi-app archives share the same namespace/manifest model; ZIP routing accepts universal bundles, stages once, restores existing targets, reconciles source metadata/icon revisions through the generation lease, and cleans abandoned staging roots.
- Safety/compatibility: reader byte/entry limits and malformed-manifest normalization, exporter canonical-directory cycle/out-of-root checks, public framework attribute/resource lookups, themed app-owned notices, runtime menu chrome restoration, and Profiles `adjustResize`.
- Validation assets: focused state, bundle, staging, runtime policy, screenshot-reference, and importer tests remain in the existing JUnit/Compose screenshot stack.

Open or intentionally deferred:

- Durable multi-process batch journal/resume and a product-approved Room-state sidecar/conflict policy are outside the current filesystem-payload contract.
- Connected-device screenshots for API 35+ system bars, cutout, portrait/landscape, split-screen, and IME are unavailable in this environment; policy/inset tests and the named manual-device gate remain required.
- The repository-wide `MissingTranslation` lint gate is still disabled pending a separate locale inventory; touched default/Indonesian resources are translated and no private AppCompat attribute references remain.

The remainder of this document contains the original ordered plan and baseline audit evidence. When a baseline bullet below says a feature is absent, the authoritative status above and current source take precedence; retain the bullet only as provenance for why the work package exists.

## Objective

Deliver a coherent, adaptive, theme-aware JL-Mod Plus UI with a first-class Library selection mode, safe bulk operations, a universal single/multi-app backup bundle, reliable Library position restoration, harmonized runtime system chrome, and automated visual regression coverage.

The work must preserve Java ME behavior and keep emulator/rendering/input boundaries native where they serve compatibility. It must not introduce unrelated dependency, toolchain, database, or architecture migrations.

## Execution Blueprint

This is the implementation order, not merely a grouping of requirements. It is designed to prevent visual rework, keep bundle compatibility safe, and delay binary screenshot churn until behavior is stable.

### Scope boundaries and implementation assumptions

- This PR owns the Library, directly equivalent app-owned Compose surfaces, app-owned transient feedback, and the Java ME runtime chrome combinations explicitly listed below. It does not authorize a repo-wide navigation migration, toolchain upgrade, database redesign, or emulator compatibility cleanup.
- Point 19 is a bounded audit of touched and directly equivalent UI. A newly found issue outside that boundary is recorded for follow-up instead of silently expanding this PR.
- Selection is ephemeral UI state; it does not require a Room schema change. It is restored only through the Compose saved-state contract and is invalidated when the active Library generation changes; no selection is persisted in Room. Destination, query, layout, and scroll anchors use the same saved-state holder and survive supported process recreation.
- Accent selection uses a curated palette. Arbitrary user-entered colors, dynamic color, and recoloring guest MIDlet content are outside this PR.
- Bulk JAR sharing and a restorable app-payload bundle remain distinct products: the former contains retained JARs only; the latter contains the v2 JAR/config/data payload and declared derived artifacts. The v2 bundle is not called a complete Library backup unless a versioned Room-state sidecar and conflict policy are explicitly approved and tested.
- Room is authoritative for Library-only state (custom metadata, favorites, collections/membership, play statistics, and receipts). The default v2 scope excludes that state, preserves existing target state on import, and must disclose the exclusion in export/import preview copy. Adding a Room-state sidecar is a separately gated product decision and must follow `docs/library-schema-evolution.md`; it is not silently inferred from filesystem payloads.
- Existing libraries are preferred. A device-screenshot dependency is added only after WP0/G0 confirms that previews cannot prove the system-owned case and a bounded candidate spike passes the configured emulator/CI compatibility check.
- This remains one user-requested improvement PR, but the commit sequence keeps visual foundations, domain safety, bundle compatibility, bulk services, UI wiring, and runtime policy separately reviewable. No unrelated cleanup is admitted to make the umbrella PR larger.

### Test-stack constraints

- Current stack is JUnit 4 host tests (including temporary file-backed Room paths), temporary file-backed/device `androidTest` database coverage, Compose `androidTest` behavior tests, Espresso where View boundaries remain, and the already-installed Compose Preview Screenshot Testing plugin. There is no current DI test framework, Robolectric screenshot runner, Dropshots rule, or coverage plugin.
- Use pure fakes and existing constructors/interfaces for new domain tests. Do not add Hilt, Koin, MockK, Robolectric, Jacoco, or a second screenshot framework just because a generic testing recipe mentions them.
- Preview screenshot testing is mandatory for Compose-owned layout/theme regression. A device screenshot suite is limited to system-owned chrome/IME/cutout cases; if the configured emulator/CI cannot make those images deterministic, keep automated policy/inset assertions and record the named device check as a release gate.
- Instrumentation tests must remain small and journey-focused. Keep destructive filesystem/install tests at the planner/coordinator boundary with temporary roots and fakes; do not exercise real user data from screenshot fixtures.

### Critical path and work lanes

```text
G0 Baseline + test inventory
  -> shared visual/test foundations
  -> pure state and domain contracts
       -> v2 reader/planner -> v2 writer -> bulk export/import UI
       -> selection reducer -> bulk services -> selection UI
       -> anchor model -> navigation-return wiring
  -> harmonization/adaptive/IME rollout
  -> runtime window policy + device system-UI verification
  -> reviewed screenshot references + bounded final audit
```

- Visual tokens and reusable containers land before feature UI so selection, search, dialogs, and profile emphasis are built once with the final geometry.
- Pure selection, anchor, bundle, bulk-operation, and runtime-window-policy models are tested before Compose/Activity wiring.
- The v2 importer/reader and backward-compatibility tests land before the exporter switches output to v2.
- State, domain, and test-fixture work may proceed as separate lanes, but integration follows the dependency arrows above.

### Validation gates

| Gate | Exit condition | Prevents |
|---|---|---|
| G0 — Baseline | Current screenshot task is green; active/orphan reference inventory, changed-screen inventory, and device-capture feasibility are recorded. If no target exists, the named manual-device fallback and policy/inset assertions are recorded. | Building on unknown visual failures, stale references, or an unusable tool |
| G1 — Foundations | Shared tokens/components compile; pure state models and fixtures are deterministic | Reworking selection/dialog UI and noisy screenshots |
| G2 — Domain safety | v0/v1/v2, batch planning, archive security, and partial-failure tests pass | UI hiding data-loss or compatibility defects |
| G3 — Integrated behavior | Selection, bulk actions, return anchors, Back, rotation, IME, and action reachability pass | Approving static images for broken interactions |
| G4 — Visual/system UI | Runtime truth table and tiered Compose matrix pass after human reference review; system-UI evidence is either a deterministic device suite or documented policy/inset assertions plus a named manual-device review. | Cross-size, theme, inset, cutout, and system-icon regressions |
| G5 — PR readiness | Relevant lint/build/tests pass; diff/resource/licensing/scope audit is clean | Shipping temporary artifacts or unrelated churn |

G0–G5 are milestone gates spanning multiple commits, not a claim that every individual commit satisfies the complete gate. Each commit still passes its local checks. Do not start the next dependent slice when its milestone gate is red. Fix the owning slice first; do not mask a failure by updating screenshot references.

## Evidence Reviewed

### Current repository

- `AGENTS.md`, including current-state, history-budget, test, compatibility, and PR rules.
- `docs/library-schema-evolution.md`, `docs/ui-ownership-map.md`, and `docs/runtime-menu-compose-migration.md` for database ownership, the hybrid Compose/View boundary, and runtime-menu constraints.
- The Java ME compatibility source of truth (`https://github.com/shinovon/J2ME_Docs`) is a required pre-implementation reference for any runtime-policy change; UI planning must not alter Java ME API semantics.
- Existing Compose Preview Screenshot Testing setup in `app/build.gradle.kts` and `app/src/screenshotTest`.
- `.github/workflows/android.yml`: current CI runs lint/unit/screenshot validation and assembles APK/`androidTest`; it does not provision or run a connected emulator, and its `--continue` invocation is diagnostic rather than a replacement for fail-closed milestone gates.
- Main Library UI and host bridge:
  - `LibraryComposeBridge.kt`
  - `LibraryCollectionsUi.kt`
  - `LibraryCollectionBrowser.kt`
  - `LibraryMetadataEditor.kt`
  - `CrashReportsComposeBridge.kt`
  - `AppsListFragment.java`
  - `MainActivity.java`
  - `LibraryViewModel.kt`, `LibraryGenerationToken.kt`, `LibraryFileOperations.kt`, `LibraryDao.kt`, and `LibraryRepository.kt`
- Current bundle/share/install architecture:
  - `LibraryAppBundleFormat.kt`
  - `LibraryAppBundleExporter.kt`
  - `LibraryAppBundleImporter.kt`
  - `LibraryShareManager.kt`
  - `LibraryTransferActions.kt`
  - `LibraryTransferIntents.kt`
  - `BulkInstallModels.kt`
  - `BulkInstallPlanner.kt`
  - `BulkInstallViewModel.kt`
  - `InstallerExecutionCoordinator.java`
- App-wide UI/theme/settings/config/runtime areas:
  - `JlModPlusTheme.kt`
  - `TransientNotice.kt`
  - `SettingsActivity.java` and `SettingsComposeBridge.kt`
  - `ConfigComposeBridge.kt`, `ConfigPreferenceComponents.kt`, `ConfigProfilePanel.kt`, `ProfilesActivity.java`, and `ProfilesComposeBridge.kt`
  - `MicroActivity.java`, `GuestWindowPolicy.java`, and `EdgeToEdgeCompat.java`
  - current resources, manifest, screenshot tests, Compose behavior tests, and platform inset tests.

### Narrow historical review

The historical review was limited to three overlapping merged PRs, then revalidated against current source. PR numbers were checked against their actual subjects rather than inferred from nearby commits:

1. `H3nb/JL-Mod-Plus#65` (`e436d7eb`) — Compose Material 3 migration for Library, Profiles, and host dialogs. It established the current hybrid boundary and the relevant Compose surfaces.
2. `H3nb/JL-Mod-Plus#89` (`b48901b5`) — app-wide Material 3 harmonization. It introduced quick filters, transient notices on selected surfaces, cutout opt-in behavior, dialog/UI harmonization, and physical-device QA refinements.
3. `H3nb/JL-Mod-Plus#56` (`da6c0ee3`), plus the focused follow-up `f0282758` — Android 16/platform and host edge-to-edge/cutout compatibility. The Java ME runtime window remains a separate contract.

Relevant current-history landmarks include `b48901b5` (Material 3 harmonization), `70afe835` (Library PR2 activation), `b3b66dc9` (Library UI polish/screenshots), `283359f4` (app-owned Compose migration), and `9fdd3e40` (runtime host chrome migration). Historical intent is not treated as current behavior; all findings below come from the present source.

## Current-State Audit

### Screenshot testing

- Compose Preview Screenshot Testing is already installed and active.
- The project currently has 81 `@Preview` cases and committed emulatorDebug references.
- Preview sources live under `app/src/screenshotTest`; committed emulatorDebug references live under `app/src/screenshotTestEmulatorDebug/reference` (current audit count: 83 PNGs, including generated variants).
- Validation task: `:app:validateEmulatorDebugScreenshotTest`.
- Intentional baseline update task: `:app:updateEmulatorDebugScreenshotTest`.
- CI already validates screenshot references.
- Baseline audit on 2026-08-21: `:app:validateEmulatorDebugScreenshotTest` passed without updating references (`BUILD SUCCESSFUL`, 1m37s).
- A fresh `:app:lintEmulatorDebug` audit on 2026-08-22 passed (`BUILD SUCCESSFUL`, 1m17s) but reports the warnings recorded below; lint output is evidence for planning, not permission to suppress or ignore them.
- The reproducible lint reports are `app/build/reports/lint-results-emulatorDebug.txt` and `app/build/reports/lint-results-emulatorDebug.xml`; the implementation gate must compare the targeted warning inventory against these outputs after each relevant slice.
- Current active/reference audit found 81 active preview cases and 83 PNGs; two tracked PNGs are orphaned and must be reviewed before deleting or retaining them:
  - `app/src/screenshotTestEmulatorDebug/reference/ru/playsoftware/j2meloader/applist/LibraryProfilesScreenshotTestKt/LibraryOverflowDialogScreenshot_Library overflow dialog_2f4f1ce9_0.png`
  - `app/src/screenshotTestEmulatorDebug/reference/ru/playsoftware/j2meloader/config/ConfigScreenshotTestKt/ConfigSystemPropertiesLandscapeScreenshot_Config system properties landscape_c93e55ac_0.png`
- Coverage is broad by surface but shallow by device matrix. Most references are 360×640/800 or 640×360. The requested compact/medium/expanded matrix, font-scale coverage, selection mode, accent variants, IME, system bars, and cutout combinations are not covered.
- Preview screenshots cannot validate real Android system chrome. Device-based screenshot coverage is required for status/navigation bars, cutouts, and IME.
- No emulator/device is currently attached (`adb devices` is empty); the workflow builds `androidTest` but does not run connected instrumentation. Therefore a deterministic device suite is not yet a CI fact and must be conditional or explicitly named manual evidence.

### Library navigation and state

- `LibraryScreen` owns the pager destination, app action dialogs, metadata editor target, and Library chrome visibility.
- `LibraryAppsDestination` creates its own `LazyListState` and `LazyGridState`.
- Launching MIDlet Config or global Settings normally leaves the MainActivity composition alive, so the scroll state often survives.
- Opening the in-composition metadata editor replaces the Library subtree with an early return. That disposes the list/grid state and explains the deterministic scroll reset on return.
- Process recreation and collection subflows need explicit characterization tests. Current behavior depends too much on composition lifetime rather than a documented Library navigation-state contract.
- MainActivity handles orientation through `configChanges`, so the plan must test both in-place orientation changes and true Activity/process recreation; one must not be mistaken for the other.
- Adaptive navigation is selected by orientation, not by window width. A narrow landscape phone receives the same rail decision as a wide tablet, while a large portrait tablet still receives phone-style bottom navigation.

### Compose state and available-window sizing

- The former `MutableCollectionMutableState` site in `CrashReportsComposeBridge.kt` now stores a read-only list and replaces it on mutation, so selection changes always trigger recomposition and saveable-state serialization.
- The former `screenHeightDp` dialog/list sizing sites now use the shared `availableWindowHeightDp()` helper backed by `LocalWindowInfo.current.containerSize`; explicit Java ME guest geometry remains outside this replacement.
- The shared window-metrics helper is also used for Library navigation width decisions, so narrow landscape windows and large portrait windows select navigation based on actual available width rather than orientation alone.

### Selection and bulk actions

- Long press currently opens `AppActionsDialog` only.
- There is no Library selection state, checkbox rendering, bulk action surface, bulk mutation coordinator, or selection behavior test.
- Crash Reports already contains a selection-mode interaction pattern that can inform semantics, but Library operations require separate domain handling.
- Library UI IDs are deliberately transient integers generated by `AppsListFragment` and mapped to Room row IDs. Selection may use those IDs only with the active generation token; it must clear on generation change. Stable anchors and bulk domain requests must carry the repository `Long` identity/storage key separately rather than persisting or widening the transient UI ID by assumption.

### App bundles and sharing

- Current bundle format v1 is a ZIP with `bundle.json` containing only `formatVersion`, plus fixed single-app namespaces: `app/`, `config/`, and `data/`.
- Importer supports the current v1 and unversioned preview v0, validates paths/quotas, stages extraction, validates source identity, and performs recoverable per-app restore transactions.
- Current bundle picker already accepts ZIP MIME types, but routes only to the single-app `InstallerDialog` flow.
- Sharing a single app produces one retained JAR. Bulk sharing needs a separate archive format from the restorable app-payload bundle; neither is a full Library database backup by default.
- Bulk MIDlet installation now has explicit planning, staging, progress, and execution coordination. The bulk bundle importer should reuse its workflow concepts rather than create an unrelated batch framework.

### Theme, shapes, and notices

- `JLModPlusTheme` has fixed light/dark color schemes and a shared Material 3 shape scale.
- Several screens still bypass the shared shape scale with literal radii such as 18, 12, 10, 8, and 6 dp.
- There is no global accent preference.
- `SettingsActivity` persists the existing theme preference through `SharedPreferences`, while Compose hosts derive their effective light/dark value through the AppCompat configuration; the accent design must follow the same source of truth and be verified after restart and when the MIDlet process is launched separately.
- Several app-owned Compose surfaces use `TransientNotice`, but Java/config/crash/file-picker paths still use platform `Toast`; the current inventory includes `CrashReportDetailsActivity`, `CrashReportsActivity`, `ConfigActivity`, `ShaderTuneAlert`, `SaveProfileAlert`, `LoadProfileAlert`, `FilteredFilePickerActivity`, and a hard-coded Key Mapper `Error`.
- Current theme config controls light/dark behavior but not a user-selectable semantic accent palette.

### Insets, IME, and runtime chrome

- Main Library is edge-to-edge and manually applies top/horizontal/IME insets.
- Several screens use `imePadding`; full device behavior is not visually regression-tested.
- The manifest explicitly opts MainActivity, ConfigActivity, and FilteredFilePickerActivity into `adjustResize`; ProfilesActivity also hosts Compose text fields but currently has no equivalent manifest declaration and must be resolved in the IME pass. SettingsActivity has no text-entry surface today, while MicroActivity's system IME and custom virtual keyboard are a separate runtime contract.
- Two full-screen platform Compose dialogs (`ConfigComposeBridge.kt` and `ConfigProfilePanel.kt`) use `usePlatformDefaultWidth = false` and `fillMaxSize`; their `decorFitsSystemWindows`/cutout ownership must be explicit rather than assumed safe.
- Parent padding and list `contentPadding` are mixed across Library, Collection Browser, and File Picker. WP6 must record one inset owner per surface and assert first/last-item, FAB, navigation-bar, and IME safety.
- The Library header is translated completely offscreen. It does not expose an intentional persistent status-bar protection layer when hidden.
- Runtime window policy now resolves the Canvas/form chrome contract through `GuestWindowPolicy.Chrome`: toolbar visibility, status-bar visibility, navigation-bar visibility, cutout eligibility, and guest padding share one state for each transition.
- Runtime toolbar height still uses the established `getToolBarHeight() / 1.5` compact value for Canvas when the action bar is enabled; the value is now derived from the same resolved chrome state.
- System UI visibility and cutout layout mode are applied from that state on displayable transitions; the remaining gap is device evidence for API 35+, IME, and physical cutout combinations.
- Current cutout eligibility requires Canvas, user opt-in, hidden status bar, and hidden action bar. This rule is tested, but the complete visual/state transition matrix is not.

### Copy, dialog affordances, and consistency

- `docs/ui-copy-style.md` already defines Title Case for screen/dialog titles and compact actions, and sentence case for descriptions/body copy.
- Current resources still contain inconsistent title/description casing and a small number of hard-coded rendered strings.
- App Actions and multiple choice dialogs use bounded `LazyColumn`s but do not consistently show a scrollbar, fading edge, or explicit continuation cue.
- Library search fields are close but not identical: App List specifies an 18 dp shape, while Collection Browser and Collection App Picker use the default field shape. Header/action alignment also varies.
- Config, Settings, Library Options, metadata editing, and dialogs use overlapping but not identical section padding, typography, separator, and surface rules.
- Profile controls currently look like a normal Config section and do not receive the requested emphasis.
- The former private AppCompat string-resource usages (`abc_action_bar_up_description` and `abc_action_menu_overflow_description`) resolve through app-owned translatable resources. The six private attribute/ID lookups were subsequently replaced with public framework attributes and the public `action_bar` child/parent lookup; `rg` no longer finds private AppCompat resource references in current source.
- Lint reports eight `PluralsCandidate` resources: `library_collection_member_count`, `bulk_install_found`, `bulk_install_selected`, `bulk_install_review_summary`, `bulk_install_result_summary`, `bulk_install_warning_count`, `bulk_install_omitted_sources`, and `config_system_properties_value`. The implementation converts the single-count messages to `<plurals>` and splits the multi-count summaries into independently localized plural segments; progress ratios and unrelated numbers are not pluralized mechanically.
- Six candidates are single-count messages (`library_collection_member_count`, `bulk_install_found`, `bulk_install_selected`, `bulk_install_warning_count`, `bulk_install_omitted_sources`, and `config_system_properties_value`); the review and result summaries contain multiple independent counts and must be split into labeled plural components or receive a documented call-site-reviewed exception rather than being forced into one `<plurals>` argument.
- `values-in/plurals.xml` currently repeats identical `one` and `other` forms for `file_picker_selected_count`; Indonesian needs the `other` form only after resource/call-site validation. `values-in/strings.xml` redundantly overrides `file_picker_parent_marker` even though the default is `translatable="false"`; remove that locale override.
- `app/build.gradle.kts:107-110` disables `MissingTranslation` while calling it a deferred localization pass. WP5/WP8 must audit touched resources, default and Indonesian values, remove accidental suppressions, and decide whether to re-enable the lint check globally or retain a documented baseline for unrelated locales. No touched UI string may be excluded silently.

### Audit conclusions

| Finding confirmed in current source | Planning consequence |
|---|---|
| Compose screenshot validation is healthy, but references are mostly 360 dp/640–800 dp and cannot render real system chrome | Keep the existing preview plugin as the primary gate; use the tiered 400/610/900 matrix and a bounded system-UI proof rather than multiplying every state |
| Two tracked PNGs have no active preview owner, and no device is attached or executed by current CI | Add an orphan-reference check to G0; make device evidence conditional, with policy/inset assertions and a named manual-device fallback when no configured target exists |
| `LibraryAppUiItem.id` is a transient `Int` mapped by `AppsListFragment` to Room `Long` IDs | Scope selection to the active generation; carry stable domain IDs/storage keys only at the state/domain boundary |
| Metadata editor replaces the Library subtree; collection browser/picker own separate list/grid states | Hoist a destination-level anchor holder and test metadata, collection, Settings, Config, MIDlet, refresh, and recreation returns independently |
| ProfilesActivity hosts text-entry Compose UI without the manifest `adjustResize` declaration seen on other text-entry Activities | Resolve the manifest/IME decision explicitly in WP6 and verify no double inset owner |
| Bundle import supports a single staged ZIP and external bundle requests already have pending-state handling | Preserve the current route, add one/many v2 planning behind it, and test MIME/extension/provider/cancellation paths before changing writers |
| Room owns custom metadata, favorites, Collections, play statistics, and receipts, but current exporter/importer moves filesystem payloads only | Call v2 an app-payload bundle by default; disclose the Room-state exclusion and define a separately gated sidecar/conflict policy before using “complete backup” language |
| MainActivity/InstallerDialog currently have one-app pending/bundle state and no durable multi-app journal | Add one import-intent classifier, a ParsedBundle/ImportBatchPlan boundary, batch idempotency/status, resume/cleanup rules, and shared routing for Options, external intents, and file picker |
| Runtime toolbar, system bars, cutout, and guest padding now consume one pure `GuestWindowPolicy.Chrome` state; menu dismissal and Library footer/header visibility remain separate interaction concerns | Keep the pure policy truth table covered by host tests, then verify transitions on real system UI |
| Toast call sites remain across crash, Config/profile, file-picker, and Key Mapper paths | Inventory every app-owned call site and migrate through one themed notice adapter; do not theme platform Toast internals |

## Product and Architecture Decisions

These defaults remove ambiguity from implementation. If product direction changes, update this document before coding.

### 1. Library selection mode

- Long press continues to open App Actions.
- Add a `Select` action to App Actions. Choosing it closes the dialog, enters selection mode, and selects the long-pressed app.
- While selection mode is active:
  - the `All`, `Favorite`, `Recently Added`, and `Recently Opened` quick-filter controls are hidden;
  - favorite stars/placeholders are hidden;
  - list and grid items show checkboxes with full-row/tile toggle behavior;
  - tapping an item toggles selection instead of launching it;
  - long press does not open the single-app action dialog;
  - install FAB and normal bottom navigation are hidden;
  - Back exits selection mode without changing the current query, sort, layout, or scroll anchor.
- The search box stays available. `Select All` applies to the currently visible search projection. With an empty query, it applies to all apps in the Apps destination.
- Header shows a dismiss/back action, a localized plural count without redundant “selected” wording, and an icon-only `Select All`/`Unselect All` toggle with localized content descriptions.
- Contextual bulk actions use concise verbs (`Delete`, `Reinstall`, `Export Bundle`) rather than repeating that the checked items are selected; every action icon and label shares one top-aligned slot so mixed one-/two-line labels cannot shift icons vertically.
- Compact width uses a contextual bottom action bar with primary actions and a scrollable `More Actions` sheet/dialog for overflow.
- Medium/expanded width may show more actions directly, but all actions must remain reachable in portrait, landscape, large font, and with IME open.
- The reducer stores the active `LibraryGenerationToken` and an ordered set of repository `Long` app IDs (or storage keys at the file boundary); transient UI `Int` IDs are adapter-only and never persisted or passed to bulk services.
- `Select All` and `Unselect All` operate on the current visible Apps projection only. Existing selections outside a search projection remain selected until explicitly cleared, generation replacement/deletion invalidates them, or Back exits the mode; this policy is shown in the selection count and tested.
- Selection is scoped to the active READY Library generation. A generation/workdir replacement clears it safely. Configuration recreation may restore it through saved state; process death intentionally starts normal mode.
- Selection mode is an Apps-destination feature in this PR. Collection browser/picker keeps its existing membership-selection semantics and uses the same stable-ID/bulk DAO boundary rather than silently inheriting Library selection mode.

### 2. Bulk action semantics

- Delete/Uninstall:
  - show one destructive confirmation with app count and an expandable app-name summary;
  - execute file/catalog removal sequentially;
  - report successes, failures, and leftover config/save data explicitly;
  - never claim all-or-nothing because filesystem deletion cannot provide that guarantee.
  - remove the Room catalog row only after the authoritative converted payload is gone, or retain/mark a cleanup-pending row when deletion fails; never hide a filesystem failure by deleting its catalog entry first.
- Add to Collection:
  - show the collection picker once;
  - update all selected memberships through one bulk DAO transaction guarded by the expected Library generation;
  - preserve user-owned collection state and avoid per-row UI flicker.
- Share Apps:
  - create a plain ZIP archive of retained JARs only, suitable for sharing with other users/tools;
  - sanitize filenames, resolve collisions deterministically, stream data, and expose one FileProvider URI;
  - do not include config or save data.
- Reinstall:
  - preflight every selected app;
  - clearly identify missing retained sources;
  - allow running only eligible apps after confirmation;
  - reuse the bulk installer planner/progress/result model and serialize mutations through the existing coordinator.
- Export App Bundle:
  - create one restorable app-payload ZIP using the universal v2 bundle below;
  - include only selected apps;
  - stream payloads and publish atomically from a staging file;
  - show progress, the explicit Room-state exclusion, and a final result suitable for Android sharing/saving.
- Every batch request carries the active `LibraryGenerationToken`, obtains the coordinated file/catalog mutation lease before mutation, and discards stale callbacks/results. A generation change yields a recoverable result instead of applying work to a replacement directory.
- Partial outcomes define selection cleanup: successful deletions/membership changes are removed from the selection, failed or skipped apps remain selected for retry, and share/export retain selection until the user exits. Reinstall/import use the same per-app result model.
- Operations that change membership or remove apps exit selection mode only when no retryable item remains; otherwise the failure summary keeps the retryable selection visible.

### 3. Universal bundle format v2

Single-app and multi-app export use the same schema and directory structure. A single bundle is simply `apps.size == 1`. This is a restorable app-payload format, not a full Library database backup by default.

The implementation boundary is explicit:

- `ParsedBundle(formatVersion, apps: List<BundleApp>)` is the only reader output.
- `BundleApp` contains manifest identity, staged payload roots, source hash, and declared config/data presence.
- `ImportBatchPlan` contains ordered `ImportItem`s, conflicts, required storage, and the per-app restore operation; one-app and multi-app inputs use the same planner.
- v0/v1 adapters produce one `BundleApp` with legacy-assurance flags; they do not fabricate v2 metadata or hashes.
- A future Room-state sidecar, if product-approved, is a separately versioned payload with explicit merge/conflict rules and round-trip tests. It is not inferred from the current filesystem schema.

Proposed ZIP layout:

```text
bundle.json
apps/a0001/app/res.jar
apps/a0001/app/converted.dex.conf
apps/a0001/app/icon.png
apps/a0001/config/**
apps/a0001/data/**
apps/a0002/app/res.jar
apps/a0002/config/**
apps/a0002/data/**
```

Proposed manifest contract:

```json
{
  "schema": "io.github.h3nb.jlmodplus.app-bundle",
  "formatVersion": 2,
  "apps": [
    {
      "bundleId": "a0001",
      "title": "Demo MIDlet",
      "vendor": "Example Vendor",
      "version": "1.0",
      "payloadRoot": "apps/a0001/",
      "sourceSha256": "...",
      "configState": "present",
      "dataState": "present-empty"
    }
  ]
}
```

Format rules:

- `bundleId` is an archive-local opaque identifier, not the workdir storage key.
- App order and entry order are deterministic.
- Every app requires a non-empty retained `res.jar`.
- Manifest app records and `apps/<bundleId>/` namespaces must be one-to-one; reject orphan namespaces, duplicate bundle IDs, missing payload roots, and entries outside a declared root.
- `configState` and `dataState` are each `absent`, `present-empty`, or `present`; the writer emits the state even when a directory has no files. Restore replaces the corresponding target namespace for `present`/`present-empty` and leaves it untouched only for `absent`, so stale files cannot survive an import silently. A `present-empty` replacement that would clear an existing target namespace is shown as a destructive conflict and requires explicit confirmation.
- Manifest identity is preflight information; source JAR metadata remains authoritative and must match before restore.
- Keep v0/v1 readers through an adapter that exposes one logical v2 app. Writers emit v2 only after importer/tests are ready.
- Apply per-entry, per-app, app-count, and total expanded-byte limits. Reject duplicate, absolute, traversal, NUL, symlink-like, and unknown namespace entries.
- v2 requires `sourceSha256` for the retained source JAR. It is the lowercase hex SHA-256 of the exact staged authoritative `res.jar` bytes; verify it before planner approval and before filesystem publish, reject mismatches without mutation, and cover fixed test vectors. A v0/v1 adapter may have no source hash, so it must retain the existing descriptor/source-identity checks and report that legacy assurance explicitly rather than fabricating a hash.
- Extract to an isolated staging root. Never restore config/data before the corresponding JAR installation identity is verified.
- Import UI previews every app, conflict, required storage, and invalid entry before mutation.
- Export computes size and source hash while streaming staged payloads where possible; it never loads all selected apps or archive entries into memory.
- Use per-app recoverable restore transactions and a durable batch journal/result. The journal has a batch ID/idempotency key and per-app `planned/running/installed/restored/failed/skipped` states, is written atomically before/after each transition, supports resume/reconciliation after process death, cleans staging deterministically, and permits cancellation only between apps. Entire-batch atomicity is intentionally not promised because installation spans filesystem conversion and catalog mutation.
- A single import-intent classifier consumes initial intents, `onNewIntent`, Options, and file-picker results. It preserves action/type/URI, classifies ZIP by manifest/MIME/extension (including missing MIME with `.zip`), deduplicates URI/content identity, and distinguishes retryable provider failures from terminal invalid archives.
- Extend the MainActivity manifest filters for `application/zip`, `application/x-zip-compressed`, and `.zip` URI paths without weakening the existing JAR/JAD/KJX filters. The same classifier is called after every route rather than relying on manifest resolution alone.
- A duplicate URI/content identity is acknowledged only after a terminal outcome; transient provider/read failures keep a retryable pending record, while invalid/traversal/hash-failing archives are terminal and produce a localized error with cleanup.
- A v2 ZIP containing `bundle.json` routes to the bundle planner. A plain retained-JAR share ZIP has no restorable manifest and must either use the explicitly supported JAR-install path or be rejected with a localized explanation; it must never be interpreted as an app bundle by accident.
- A v2 ZIP opened externally or through Options routes to the same bundle planner. One app may use compact UI; multiple apps use the bulk summary UI, but execution semantics stay identical.
- Keep `.zip` compatibility and existing ZIP MIME acceptance. A vendor MIME may be added for produced shares, but generic ZIP providers must remain supported.
- Canonical JSON serialization, fixed UTF-8/ZIP method and compression-level settings, fixed entry ordering, no variable extra/comment fields, and a fixed timestamp policy (for example, zero/epoch entry times) are required for byte-stable golden archives; filesystem mtimes must not make equivalent exports differ.
- `app/icon.png` is a declared derived cache artifact. If a custom icon is user-owned, its authoritative config-side copy is exported/restored through the documented config namespace; the importer must not silently accept and discard an icon entry.

### 4. Scroll/navigation state contract

- Introduce a Library navigation-state holder above destination/editor replacement, owned by the Activity-scoped Library ViewModel plus `SavedStateHandle` (or an equivalent saved-state holder) for process recreation.
- Store independently for Apps list, Apps grid, Collections list, and each open Collection list/grid:
  - stable anchor app/collection database ID or storage key resolved outside the transient `LibraryAppUiItem.id`;
  - anchor offset;
  - fallback index;
  - destination;
  - query and quick view where applicable;
   - header/navigation visibility state only when meaningful.
- Hoist list/grid states so opening the metadata editor does not dispose them.
- Persist a return-origin and active generation with each anchor. Restore exactly once after the matching generation reaches READY; discard stale callbacks and re-resolve the anchor after a data refresh rather than repeatedly scrolling on every recomposition.
- On return from MIDlet, Config, Settings, icon picker, metadata editor, collection picker, or external installer, restore the stable anchor after data is READY.
- If the anchor was deleted or filtered out, restore the nearest valid fallback without jumping to index zero unless no other item exists.
- Layout list/grid has its own retained anchor. Switching layout should not overwrite the other layout's last position.
- Characterize normal Activity pause/resume, editor subtree replacement, in-place orientation/configuration change, `ActivityScenario.recreate`/saved-state restoration, process death/restart, data refresh, workdir/generation replacement, deletion, and sort/filter changes. Selection must be restored only for configuration recreation and must be cleared after process death.

### 5. Theme and visual system

- Keep Material 3 and the existing fixed default blue palette.
- Add curated accessible accent palettes rather than arbitrary HEX in this PR. Proposed choices: Default Blue, Teal, Green, Amber, Rose, and Violet.
- Each accent defines verified light/dark semantic roles; guest MIDlet canvas colors and user-selected virtual-key colors are unaffected.
- Add Accent Color under global Settings > Appearance, persist it beside the existing theme preference, and observe preference changes so active Compose hosts update consistently. Verify new Activities, the separate MIDlet process, and process restart all resolve the same palette.
- Centralize tokens for:
  - search/field shape;
  - section/card shape;
  - dialog shape;
  - compact control shape;
  - screen horizontal padding;
  - section spacing;
  - row vertical padding;
  - dividers and when they are allowed;
  - typography roles.
- Replace literal component radii with semantic `MaterialTheme.shapes` or named app tokens. Color-picker geometry may keep purpose-specific small radii.
- Use no divider inside a section when spacing/surface grouping already communicates hierarchy. Use dividers only for dense homogeneous rows where scanning materially benefits.

### 6. Glass-like status-bar protection

- Add a persistent top system-bar protection composable outside the translating Library header.
- When header is visible, its surface extends naturally behind the status bar.
- As header hides, cross-fade to a light/dark-aware translucent tonal gradient with a subtle lower hairline/gradient fade. This is a glass-like material treatment, not an expensive live backdrop blur.
- Status bar icons follow effective theme/accent contrast.
- Header and footer/navigation visibility are coordinated by one chrome-visibility contract: hiding either may reclaim content space, but the top protection remains present whenever content can reach the status bar, and the bottom navigation/FAB/notice anchors retain independently testable safe-area handling.
- Content may scroll behind the protection, but controls and first/last items retain correct safe-area padding. Do not apply the same inset through both `Scaffold` padding and a parent padding modifier.
- Add a synthetic component screenshot for deterministic visual regression and device screenshots for actual system-bar integration.

### 7. Toast replacement

- Do not attempt to theme platform Toast internals.
- Route app-owned feedback to one Material 3 transient-notice/snackbar abstraction with semantic Info/Success/Warning/Error styles.
- Provide host adapters for Java Activities and dialogs so current Toast call sites can publish into the Compose host.
- Migrate file picker, Config/profile operations, Crash Reports, Key Mapper, and remaining app-owned Toast calls.
- All messages must use string resources; remove the hard-coded Key Mapper `Error`.
- Preserve platform Toast only where Android/platform ownership makes replacement impossible; document any exception.

### 8. Search and Collection header alignment

- Create one shared `LibrarySearchField` used by App List, Collection Browser, and Collection App Picker.
- Standardize height, shape, leading/clear behavior, content descriptions, padding, focus/keyboard dismissal, and disabled state.
- Use a shared Library header grid so back arrow, title, contextual action, search field, and sort control align to the same 16 dp content edge.
- Specifically align the Collection back arrow/title/add-app action with the search field below; remove the current optical offset caused by an IconButton starting inside the already padded row.

### 9. Scrollable dialog affordance

- Introduce a reusable bounded scroll container for dialogs and sheets.
- Show a subtle vertical scroll indicator when more content exists. Also retain a bottom fade/partial next-row cue so discoverability does not depend on a thin scrollbar alone.
- Apply it to App Actions, selection overflow actions, Config choice/template dialogs, collection pickers, information dialogs, installer/bulk summaries, and any other bounded scrollable popup touched by this PR.
- Ensure top/bottom dialog actions remain reachable with large font and landscape height.

### 10. Runtime action bar/status bar/cutout policy

- Replace scattered boolean effects with one pure `RuntimeWindowPolicy` state calculation.
- Inputs: displayable type (Canvas vs host Form/List), action-bar preference, status-bar preference, cutout preference, API level, current insets, and IME visibility.
- Outputs: toolbar visibility/height mode, status/navigation visibility, cutout layout mode, guest safe padding, system-bar icon appearance, and whether immersive re-hide is allowed.
- Remove the `/ 1.5` toolbar-height magic number. The Compose runtime toolbar owns an explicit compact/regular height token.
- Apply the complete policy in one method whenever displayable, focus, configuration, or relevant inset state changes.
- Keep `RuntimeWindowPolicy` limited to MIDlet runtime outputs: Android system-bar visibility, the runtime toolbar, cutout mode, guest virtual-display safe padding, icon appearance, and immersive re-hide. The Library header/footer contract may consume shared inset/system-bar tokens, but must not mutate runtime preferences or be inferred as a runtime-policy output.
- Required behavior matrix for Canvas (pure tests cover API 23 minimum-SDK behavior, API 28 cutout behavior, and API 35/36 edge-to-edge behavior; device images may use only configured targets):
  - Action bar off, status bar off, cutout on: immersive; content may use cutout.
  - Action bar off, status bar off, cutout off: immersive; reserve cutout only.
  - Action bar on, status bar off: toolbar visible; cutout reserved; status hidden.
  - Action bar off, status bar on: status visible; cutout reserved; toolbar hidden.
  - Both bars on: both visible; cutout reserved; no overlap.
- The truth-table axes also include IME hidden/visible, gesture/three-button navigation mode where observable, icon appearance, API bucket, and the immersive re-hide output. Assert every distinct output rather than multiplying equivalent screenshots.
- Non-Canvas Java ME displayables always reserve safe system/cutout/IME areas and use regular host chrome.
- Opening a transient runtime menu must not permanently mutate the configured chrome state; dismiss reapplies the policy.
- Preserve Java ME Canvas geometry behavior outside the explicitly corrected inset/chrome combinations.

### 11. Profile emphasis

- Give the Config profile section a distinct primary-container tonal surface, profile icon, current profile title, and clear active/modified/default badge treatment.
- Keep it at the beginning of relevant Config flows.
- Emphasis must use semantic theme roles and remain readable for every accent and light/dark theme.
- Avoid error/danger styling; this is attention hierarchy, not a warning.

### 12. Additional QoL and accessibility

- Localized plural selection/result counts.
- Haptic feedback when entering selection mode and on destructive confirmation where platform conventions allow.
- Progress, cancellation, and partial-result summaries for long batch operations.
- Stable semantic roles/test tags only when simple text/role matchers are insufficient.
- Minimum 48 dp touch targets for interactive controls.
- Correct content descriptions for select all, clear selection, checkbox state, favorite state, sort direction, and dialog scrollability.
- Preserve focus and bring edited fields into view with IME.
- Avoid invisible enabled actions; unavailable operations explain why.
- Do not animate large layout changes when system reduced-motion behavior indicates animations should be minimized.

### 13. Compose correctness, window metrics, and localization hygiene

- Mutable collections must not be stored inside ordinary `MutableState`; use immutable `List`/`Set` replacement semantics by default, and permit a snapshot collection only as an explicitly justified exception with a behavior test that observes recomposition after selection changes.
- For Compose-owned dialog/list sizing, use the actual available window (`LocalWindowInfo.current.containerSize` or constraint/inset-aware equivalent) rather than `LocalConfiguration.screenHeightDp`. Test compact/medium/expanded, multi-window, IME-visible, and orientation changes. Keep Java ME guest geometry and platform compatibility calculations on their existing contract until separately verified.
- App-owned strings own all user-visible navigation/overflow content descriptions; private AppCompat string resources are not an API. AppCompat attributes/IDs that remain at View/runtime boundaries require an explicit compatibility comment and test.
- Run a dedicated localization pass for the eight quantity candidates, Indonesian plural-category cleanup, the non-translatable parent marker, and every touched resource. Use resource APIs at call sites, preserve formatting arguments, and make the final MissingTranslation decision explicit rather than silently suppressing findings.

## Change Map

This map assigns ownership before implementation so each commit has a narrow review surface. Exact test filenames may follow existing naming conventions, but responsibilities must not drift between lanes.

| Area | Primary current surfaces | Planned responsibility | Main verification |
|---|---|---|---|
| Visual test infrastructure | `app/build.gradle.kts`, `app/src/screenshotTest`, current CI workflow | Tiered preview annotations, deterministic fixtures, recomposition/window-metric regression cases, optional device-capture spike | Existing screenshot validation plus behavior/lint evidence and a bounded device proof |
| Library UI and selection | `LibraryComposeBridge.kt`, `LibraryCollectionsUi.kt`, `LibraryCollectionBrowser.kt` | Selection shell, contextual actions, shared search/header, adaptive layouts | Compose behavior tests and tiered screenshots |
| Library state/navigation | Library ViewModel/state holders, `AppsListFragment.java`, `MainActivity.java`, metadata/config launch bridges | Generation-safe selection and stable destination/list/grid/collection anchors | Pure reducers plus return/recreation instrumentation |
| Bulk operations | `LibraryTransferActions.kt`, `LibraryShareManager.kt`, collection DAO/repository, bulk installer classes | Generation-aware delete, bulk collection transaction, JAR sharing, reinstall planning/results | Unit, file/catalog lease, Room transaction, partial-failure, and coordinator tests |
| Bundle format/import | `LibraryAppBundleFormat.kt`, exporter/importer, `LibraryTransferIntents.kt`, `MainActivity.java`, `AppsListFragment.java`, manifest intent filters, installer flow | Universal v2 reader/writer/planner with v0/v1 adapters, import-intent classifier, durable batch journal, and every supported ZIP entry path | Golden ZIP, security, recovery/resume, URI/MIME/dedupe routing, and compatibility tests |
| Theme and common UI | `JlModPlusTheme.kt`, `TransientNotice.kt`, Config/Settings/Profile/common Compose components and resources | Accent setting, semantic geometry/type/spacing, app-owned notices, profile emphasis, private-resource replacement, plural/localization pass | Component/theme screenshots, preference, resource/lint audit |
| Dialog/adaptive/IME | Touched Config, Settings, Profiles, metadata, Library, picker, installer, and information dialogs | Scroll cue, consistent containers, available-window sizing, width-driven behavior, focus/inset safety | Short-height/large-font/window-metric screenshots and interaction tests |
| Runtime/system chrome | `MicroActivity.java`, `GuestWindowPolicy.java`, `EdgeToEdgeCompat.java`, runtime Compose toolbar, manifest | Pure window policy, glass-like Library status protection, cutout/bar integration | Truth-table unit tests, instrumentation, real system-UI screenshots |

## Work Packages and Acceptance Criteria

### WP0 — Visual audit harness and evidence contract

- Inventory every existing reference and declare the materially changed top-level screens before adding new cases.
- Fail the inventory if a tracked PNG has no active preview owner; review the two current orphan candidates before deleting or retaining them.
- Add reusable preview annotations/configurations for the tiered matrix in “Screenshot and Test Matrix”; do not form a Cartesian product of size, state, theme, accent, and font scale.
- Reuse deterministic fixtures for search, selection, profile highlight, dialog overflow, notices, and accent coverage.
- Add a lint-warning inventory to G0: `MutableCollectionMutableState`, nine `ConfigurationScreenWidthHeight` occurrences, six private AppCompat strings, eight `PluralsCandidate` resources, and the Indonesian `Untranslatable` marker are tracked by owning WP and cannot be hidden by a new suppression.
- Run a minimal instrumented proof that real status/navigation bars, cutout, and IME can be captured deterministically. Add a device-screenshot dependency only if the current stack cannot meet that need and the proof passes locally plus on the configured CI/emulator target; otherwise use automated inset/policy assertions and a named manual-device review for those system-owned pixels. Current CI has no connected emulator job, so this is a conditional gate, not an assumed automated fact.
- Keep screenshot tests visual only; add separate behavior/state tests.

Acceptance:

- Baseline validation passes before UI implementation.
- The inventory records each reference’s owning screen/component, tier, and reason to exist; redundant cases are removed from the plan before baselines are generated.
- The inventory records the configured emulator ABI/SDK and whether a case is preview, instrumentation, or named manual-device evidence.
- If a device target exists, record its API/ABI/navigation mode/IME setup and upload both `app/build/outputs/screenshotTest-results/**` and the rendered report artifact. If no target exists, record the exact fallback owner and review date.
- Deliberate UI changes produce readable diffs rather than widespread nondeterministic noise.
- References are updated only after human review.

### WP1 — Selection state and visual mode

- Add Select to long-press App Actions.
- Implement generation-scoped selection state (transient UI IDs are never persisted), list/grid checkboxes, select all/unselect all, Back behavior, and adaptive contextual actions.
- Hide quick filters, favorites, install FAB, and normal Library navigation while selecting.
- Preserve query, sort, layout, and scroll anchor across entering/exiting selection.

Acceptance:

- Behavior tests cover long press > Select, item toggle, visible select all, unselect all, Back, configuration rotation/state restoration, generation replacement/ID invalidation, list, and grid; process-death clearing is covered by the explicit WP4 restoration contract.
- Selection changes assign a new immutable `List`/`Set` value; a snapshot-backed exception requires written justification. A recomposition test proves that checking, unchecking, select-all, and clear-all update both the visible count and every affected item.
- The representative selection shell gets the layout tier; no/partial/all/filtered states use only the canonical state tier unless a state changes adaptive geometry.

### WP2 — Bulk domain operations

- Add batch request/result models and a single mutation coordinator.
- Implement bulk delete, collection membership, JAR-share archive, and reinstall preflight/execution.
- Add confirmation/progress/partial-result UI.

Acceptance:

- Unit tests cover stable ordering, duplicates, missing sources, filename collisions, partial failures, generation changes, cancellation, collection bulk-transaction behavior, stale callbacks, shortcut cleanup, and explicit leftover config/save reporting.
- Share tests verify deterministic safe filenames, retained-JAR-only contents, FileProvider URI/ClipData read grants, no config/save leakage, and cleanup after cancellation or provider failure.
- Filesystem/catalog mutation uses a generation-aware lease or durable two-phase outcome; a generation change between file and Room steps cannot publish a stale catalog row or orphan an unreported payload.
- No operation runs against a stale READY generation.

### WP3 — Universal bundle v2 and ZIP import

- Implement v2 manifest/model/writer/reader.
- Adapt v0/v1 to one logical app.
- Export one or many selected apps with the same format.
- Route v2 import through a batch planner and installer/restore coordinator.
- Keep the existing single-bundle entry point working while routing a ZIP with one or many v2 apps through the same preview/planner contract. Cover Options, external `content://`/`file://` intents, generic ZIP MIME, missing MIME with `.zip`, duplicate delivery, cancellation, and provider read failure.

Acceptance:

- Golden-format tests cover one-app and multi-app archives.
- Import tests cover v0, v1, v2 single, v2 multi, conflicts, invalid identity, traversal, duplicates, unknown entries, quotas, truncation, bad hashes, interruption, recovery, and partial execution.
- Import tests cover empty/present namespace states, canonical JSON/ZIP timestamps, plain JAR-share ZIP rejection or explicit install routing, initial intent/`onNewIntent`/Options/file-picker classification, duplicate delivery, provider read failure, retry preservation, and durable journal resume/reconciliation after process death.
- Existing single-bundle compatibility remains intact.
- A v2 ZIP opened by every supported entry path produces the same logical plan; only presentation density changes between one-app and multi-app previews.

### WP4 — Library navigation and position restoration

- Hoist/retain list and grid states above metadata/config transitions.
- Restore by stable item anchor, not index alone.
- Characterize MIDlet, Settings, Config, metadata, collection browser, collection app picker, rotation, process recreation, data refresh, and external installer return.
- Keep the active query, quick view/sort, layout, selected collection, and open dialog/editor target separate from scroll anchors so restoring one cannot reset another.

Acceptance:

- Returning from each flow shows the same anchored item at the same practical offset unless it no longer exists, including collection browser/picker, icon picker, external installer return, refresh, rotation, and saved-state recreation.
- List and grid maintain independent positions.
- Returning from collection membership editing restores the collection and its query/anchor; returning from app metadata restores the originating Library destination and anchor.
- Tests explicitly distinguish configuration recreation (anchor and selection restored) from process death (anchor restored from saved state; selection cleared).

### WP5 — Shared visual tokens and accent palettes

- Foundation slice: add semantic geometry/spacing/type tokens, shared search/header, bounded-dialog, section, and notice primitives before new feature UI consumes them.
- Rollout slice: migrate touched and directly equivalent surfaces after their behavior is stable; do not perform a blind global replacement.
- Add global accent preference and palette-aware light/dark schemes.
- Highlight Config profile section.
- Replace remaining app-owned Toasts.
- Resolve the existing Compose state warning in `CrashReportsComposeBridge.kt` with immutable selection state and a focused recomposition/saveable-state test; any snapshot-collection exception must be documented before implementation.
- Normalize copy casing and resource ownership, replace the six private AppCompat string references with app-owned resources, and complete the dedicated plural/localization pass.

Acceptance:

- No user-facing hard-coded English remains in touched production UI.
- Search fields match exactly.
- Equivalent rows/sections/dialogs use the same tokens.
- All accent/light/dark screenshot variants meet visual contrast review.
- Info/Success/Warning/Error notices remain legible and correctly positioned in light/dark themes, landscape, and above the navigation/IME safe area.
- Resource/copy audit covers all new labels, plural forms, descriptions, content descriptions, and error/result text in source and Indonesian resources; no user-facing string is hard-coded in touched production UI.
- The six AppCompat string warnings are gone; the eight named quantity candidates use verified plural/call-site semantics; the Indonesian `one` duplicate and `file_picker_parent_marker` override are removed; and the final MissingTranslation policy is recorded.
- Single-count messages use the quantity API with preserved format arguments; multi-count review/result summaries either render separately labeled plural segments or carry a reviewed rationale for remaining a single localized summary string.

### WP6 — Scrollable dialog affordance and adaptive/IME pass

- Apply the bounded scroll container and indicator/cue.
- Replace orientation-only decisions with width-class/adaptive-info decisions where appropriate on Compose-owned surfaces; keep the Fragment/View host boundary and existing Navigation 3 usage unless a concrete migration is separately approved.
- This is a bounded exception to the deferred adaptive work recorded in `docs/ui-ownership-map.md`, not a repo-wide Navigation 3 or multi-pane migration. File Picker's existing adaptive pattern is the reference; do not add new experimental layout frameworks for this PR.
- Use available-width classes consistently: compact `< 600 dp`, medium `600–839 dp`, and expanded `>= 840 dp`; use height/constraints for short or tall layouts instead of treating orientation as the layout policy.
- Replace the nine `screenHeightDp` calculations with available-window/constraint-aware sizing where they describe Compose layout bounds. Cover multi-window, foldable/large window, IME-visible, compact landscape, and orientation changes; retain and test any Java ME/runtime calculation that intentionally uses a different coordinate contract.
- Verify every touched action in compact portrait/landscape, medium, expanded, 1.5 font, and IME-visible layouts.
- Audit `AndroidManifest.xml` and every touched Activity for `adjustResize`; apply it only to Activities that can host the system IME, and preserve the custom MIDlet/virtual-keyboard contract where Android IME is not the layout driver.
- Resolve `ProfilesActivity` explicitly (it has text fields), extend the manifest contract test, and add a focus/IME journey that reaches the lower dialog action. Do not mechanically add `adjustResize` to `MicroActivity`.
- Trace each inset from source to consumer in an ownership table: host View, Scaffold `PaddingValues`, list `contentPadding`, app-bar insets, dialog properties, and IME padding must have one owner and one `consumeWindowInsets` boundary. Prefer list `contentPadding` for first/last-item safety and assert no double padding around FAB/navigation/IME.
- Make full-screen platform Compose dialogs that use `usePlatformDefaultWidth = false`/`fillMaxSize` explicit about `decorFitsSystemWindows` and cutout ownership, with a device/manual proof if they remain edge-to-edge.
- Exercise external bundle/file-picker entry points in portrait and landscape with IME closed/open, including `content://` URIs, ZIP MIME, missing MIME with `.zip`, and provider cancellation.
- Keep experimental Compose Grid/FlexBox out unless a concrete fixed-grid need emerges and is separately approved.

Acceptance:

- No action is clipped or unreachable.
- No text field is obscured by IME.
- Insets are consumed once; no double padding.
- All system-IME Activities have an explicit manifest decision and a test or documented reason for exceptions.
- Compact landscape is covered at a width below 600 dp, and width-boundary tests cover 599/600/839/840 dp when a branch changes geometry.
- Every migrated Compose size calculation is driven by available window bounds (`LocalWindowInfo.current.containerSize`, constraints, and insets as appropriate), with a density-aware conversion and a named exception for any remaining configuration/runtime calculation.
- Window-metric tests cover multi-window, tablet/desktop or foldable-sized bounds, IME-visible height changes, orientation, and compact landscape; no test relies on `screenHeightDp` as a proxy for the available Compose container.

### WP7 — System-bar glass and runtime chrome policy

- Add Library status-bar protection synchronized with header visibility.
- Implement pure runtime window policy and remove scattered/magic toolbar decisions.
- Keep the Library top protection and bottom navigation/FAB/notice safe areas independently observable from the runtime toolbar, Android system bars, and guest canvas padding; shared tokens are allowed, shared mutable policy state is not.
- Expand unit, instrumentation, and device screenshot coverage only for distinct policy outputs; do not duplicate equivalent platform permutations.

Acceptance:

- All runtime bar/cutout combinations follow the documented matrix on API 28, 35, and 36 behavior paths; API 23 minimum-SDK branches are covered by pure policy tests even when no device image is available.
- Light/dark system icons remain legible.
- Canvas geometry changes only for the intended inset/chrome fixes.
- Header/footer hide/show transitions do not leave a stale system-bar icon mode, overlay, or unreachable bottom action.

### WP8 — Final UI/QoL audit

- Run resource/copy audit, accessibility semantics audit, dialog reachability audit, and visual diff review.
- Fix only issues found on touched or directly equivalent surfaces; do not expand into unrelated emulator APIs.

Acceptance:

- Final diff has no unrelated cleanup, dead code, temporary scripts, licensing regressions, or accidental version bump.
- Final audit explicitly checks haptic/reduced-motion behavior, 48 dp targets, focus restoration, semantic content descriptions, disabled-action explanations, dialog scrollability, and partial-result accessibility.
- Final audit confirms no `MutableCollectionMutableState`, no unreviewed `ConfigurationScreenWidthHeight`, no private AppCompat string references, and no unreviewed named PluralsCandidate/Untranslatable findings (each candidate is converted or has a call-site-reviewed exception), plus an explicit MissingTranslation result.

## Proposed Commit Sequence

1. `test(ui): define visual matrix and deterministic fixtures`
   - G0 inventory, tier annotations, behavior-test scaffolding, and the bounded device-capture feasibility proof. No production behavior or broad baseline update.
2. `feat(ui): establish shared visual tokens and accent infrastructure`
   - Semantic geometry/type/spacing, theme preference/model, shared search/header/dialog/notice primitives, and focused component tests. This foundation precedes new UI.
3. `refactor(library): add selection and navigation state contracts`
   - Pure generation-scoped selection reducer, independent list/grid/collection anchors, saved destination/query state, and characterization tests; no bulk filesystem mutation yet.
4. `feat(bundle): add backward-compatible universal bundle v2 reader`
   - v0/v1 adapters, v2 reader/planner, ZIP routing, quotas/security/recovery tests, and the one/many-app logical plan. No writer switch yet.
5. `feat(bundle): add deterministic universal v2 writers`
   - Single/multi-app export using the same schema, golden-format tests, atomic staging/publish, and the writer switch only after commit 4 compatibility tests pass.
6. `feat(library): add safe bulk app operation services`
   - Delete, transactional collection membership, JAR-share archive, reinstall preflight/coordinator, progress/cancellation/partial results; domain tests before UI wiring.
7. `feat(library): add adaptive selection and bulk action UI`
   - Long-press Select, checkboxes, contextual actions, overflow, confirmations/results, and v2 bulk export/import presentation using the final shared primitives.
8. `fix(library): preserve destination and scroll anchors`
   - Wire the state contract through MIDlet, Config, Settings, metadata, collection, refresh, rotation, and recreation returns; retain independent list/grid positions.
9. `fix(ui): harmonize themes dialogs copy notices and IME behavior`
   - Roll out tokens to touched/equivalent surfaces, profile emphasis, Toast migration, translations/casing, scroll cues, available-window sizing, immutable-state/recomposition checks, private-resource replacements, plural/localization cleanup, focus, and inset safety.
10. `fix(runtime): unify action bar status bar and cutout policy`
   - Pure policy truth table first, then wiring; remove magic toolbar sizing, add Library glass protection, and run instrumentation/device verification.
11. `test(ui): approve visual references and final QA`
   - Human-reviewed Compose/device references plus only narrow fixes from the bounded accessibility/resource/visual audit. If a code fix is non-trivial, place it in a separate preceding fix commit so this commit remains reviewable.

Each commit must pass its local checks and remain independently understandable. The milestone mapping is: G0 = commit 1/WP0; G1 = commits 2–3/WP0 foundations plus pure state; G2 = commits 4–6/WP2–WP3 domain safety; G3 = commits 7–9/WP1/WP4/WP6 integrated behavior; G4 = commits 10–11/WP7 plus reviewed visual/system evidence; G5 = the final commit’s diff/resource/licensing/scope audit. Split a commit when review would otherwise mix domain policy with presentation, but do not reorder the importer-before-writer, domain-before-UI, or token-before-rollout dependencies. Rebase/fix conflicts by owning area; do not create cross-cutting cleanup commits. Do not use `[skip ci]` on the final relevant state.

## Screenshot and Test Matrix

### Compose preview screenshots

Use tiers. Apply the next tier only when it tests a different visual contract; never multiply all tiers together.

#### Tier A — top-level layout shells

Each materially changed top-level screen gets one representative, data-rich state at the 9 layout sizes below. The G0 inventory freezes the screen list before reference generation.

| Width | Heights | Purpose |
|---|---|---|
| 400 dp | 400, 500, 1000 dp | Compact square/portrait/tall behavior |
| 610 dp | 400, 500, 1000 dp | Medium/foldable/tablet transition behavior |
| 900 dp | 400, 500, 1000 dp | Expanded/tablet/desktop-width behavior |

Normal and selection Library shells are separate Tier A shells because their navigation/action geometry differs. A dialog/component does not receive this full matrix unless it is itself a top-level adaptive shell.

#### Tier B — canonical state/theme/accessibility deltas

Use 400×500 only, unless the case specifically targets a width breakpoint or short landscape height:

- light and dark for each changed top-level shell;
- font scale 1.5 for each changed top-level shell;
- state deltas that materially alter controls or hierarchy: list/grid, no/partial/all/filtered selection, empty/error/progress/partial result, collection browser/picker, metadata editor, and selection overflow;
- a short-height overflow case for each reusable dialog family, showing the scroll indicator/cue and reachable actions.
- a compact-landscape case at `500×400` (or the smallest configured width below 600 dp) and width-boundary cases at `599`, `600`, `839`, and `840` dp whenever a breakpoint changes geometry. These are targeted deltas, not a second Cartesian matrix.

Do not duplicate a state at all nine sizes unless behavior or geometry actually differs at a breakpoint. Behavior-only differences belong in interaction/unit tests.

#### Tier C — shared component and palette contracts

- Capture shared search/header, selection controls, profile highlight, bounded scroll container, and semantic notices as focused components.
- Verify every accent palette in one compact light/dark theme specimen containing the semantic roles used by this PR. Add full-screen accent variants only for a discovered screen-specific contrast/composition risk.
- Keep deterministic content, locale, time, animation clock, font, and data ordering. Any fixture randomness is a test failure.

#### Reference budget and update policy

- G0 records the expected new/changed reference count by Tier A/B/C and the reason for each case.
- Adding a new dimension requires either a distinct visual risk or removal of a redundant case. “More coverage” alone is not sufficient justification for a Cartesian-product baseline.
- Generate references only at reviewed visual milestones; the final dedicated commit contains the approved stable set. Never update references to make a failing gate green without inspecting the diff.

### Compose behavior/instrumentation tests

- Selection interaction and state restoration.
- Every selection action (delete, collection, JAR share, reinstall, bundle export) has a semantic enabled/disabled path, confirmation where destructive, progress/cancellation where long-running, and partial-result rendering.
- Navigation Back and editor/config returns.
- Search/focus/IME-safe behavior.
- All actions reachable by semantics in portrait and landscape.
- Collection batch membership.
- Accent setting persistence/recomposition.
- Transient notice semantics.

### Device screenshots

Preview screenshots cannot include real system UI. First run the G0 capture spike using current instrumentation. If it cannot produce stable stored diffs, add the smallest compatible device-screenshot library after confirming Gradle/CI fit. The current workflow has no connected emulator job, so run the device list only when a configured target exists; otherwise the named manual-device fallback plus policy/inset assertions is the accepted evidence. Keep the suite bounded to cases that cannot be proven in previews:

- API 28, 35, and 36 behavior paths, with one configured API as the canonical image producer when pixel output differs by platform (API 23 remains pure-policy/unit coverage for the minimum-SDK path);
- light/dark Library with header and footer/navigation visible/hidden combinations;
- portrait and landscape/cutout;
- one representative case for each distinct runtime action-bar/status-bar/cutout policy output, not every duplicate input permutation;
- search or Config field with IME visible;
- gesture and three-button navigation only where the configured infrastructure can reproduce them deterministically.

Use assertions/inset traces alongside images so a platform pixel difference does not obscure a policy regression. If CI cannot capture a case deterministically, retain automated state/inset assertions and record a named manual-device check rather than accepting flaky screenshot tests.

### Unit/database/security tests

- Pure selection reducer/state holder.
- Bulk planners/results and archive naming.
- Bundle v2 parser/writer/limits/path safety/hash validation/recovery.
- Room bulk collection membership uses the existing temporary file-backed host/device database paths; add an in-memory database only if a pure DAO test needs it without replacing production-path coverage.
- Runtime window policy complete truth table.
- Stable-anchor restoration reducer.

## Validation Commands

Use the narrowest applicable command while iterating. Do not run `clean` routinely.

```powershell
.\gradlew.bat :app:compileEmulatorDebugScreenshotTestKotlin
.\gradlew.bat :app:validateEmulatorDebugScreenshotTest
.\gradlew.bat :app:testEmulatorDebugUnitTest
.\gradlew.bat :app:assembleEmulatorDebugAndroidTest
.\gradlew.bat :app:lintEmulatorDebug :dexlib:lintDebug
.\gradlew.bat :app:assembleEmulatorDebug
git diff --check
```

Run `:app:updateEmulatorDebugScreenshotTest` only for an intentional, reviewed baseline update. If a configured emulator exists, run `.\gradlew.bat :app:connectedEmulatorDebugAndroidTest` with the repository's current ABI/property configuration; otherwise record the named manual-device fallback and do not claim connected instrumentation passed. Upload `app/build/outputs/screenshotTest-results/**` alongside the report when reviewing references. Device/instrumentation commands must follow the current CI/emulator ABI configuration and must not be invented independently of the configured test target.

## Mapping to Requested Points

This register is the single source of truth for requirement coverage. WP headings intentionally do not repeat point numbers; supporting work may appear in more than one row.

| # | Requirement (short form) | Primary WP | Supporting WPs | Acceptance/test anchor |
|---|---|---|---|---|
| 1 | Long-press App Actions adds Select; selection mode hides quick filters/favorites | WP1 | WP0, WP6 | Reducer/UI behavior and normal-vs-selection shell screenshots |
| 2 | Select/check/unselect all, delete/uninstall, collection, JAR share, reinstall, selected-only bundle export, ZIP bundle import | WP1/WP2/WP3 | WP6, WP8 | Per-action semantics, partial results, v2 route/security tests |
| 3 | Universal single/bulk bundle architecture | WP3 | WP2 | Same `ParsedBundle`/planner and one/many golden archives |
| 4 | Glass-like top system-bar treatment when Library chrome is hidden | WP7 | WP0, WP5 | Synthetic component plus bounded system-UI evidence |
| 5 | Preserve list/grid/collection position across MIDlet, metadata, Config, Settings, and return flows | WP4 | WP1, WP6 | Stable-anchor and saved-state/recreation journeys |
| 6 | Light/dark themed transient feedback | WP5 | WP0, WP8 | Notice component/theme/position/accessibility checks |
| 7 | Align collection arrow/name/add action with the search field | WP5 | WP6 | Shared header alignment screenshot and semantics |
| 8 | Use one search-box shape/style in Apps and Collections | WP5 | WP0 | Shared `LibrarySearchField` component screenshot |
| 9 | All user-facing UI text translatable | WP5/WP8 | WP1–WP7 | Resource/default/Indonesian, private-resource, plural, MissingTranslation, and hard-coded-string audit |
| 10 | Title Case headings/options; sentence-case descriptions | WP5/WP8 | WP0 | Copy-style/resource review and component screenshots |
| 11 | Scrollable popups show a scrollbar/fade/continuation cue | WP6 | WP0, WP8 | Short-height/large-font dialog cases and semantics |
| 12 | Adaptive, IME-safe, portrait/landscape menu reachability | WP6 | WP0, WP7 | Available-window metrics, width classes, compact landscape, IME/inset and action journeys |
| 13 | Harmonize action bar, status bar, and cutout settings in MIDlet runtime | WP7 | WP0 | Pure truth table plus API/device/manual system evidence |
| 14 | Harmonize padding, typography, separators, surfaces, dialogs | WP5/WP6 | WP8 | Token rollout and cross-surface visual diff review |
| 15 | Consistent light/dark behavior | WP5/WP7 | WP0 | Theme/accent contrast and system-icon checks |
| 16 | Consistent Compose corner radii | WP5 | WP6, WP0 | Semantic shape-token inventory and component references |
| 17 | Global accent-color setting | WP5 | WP0, WP8 | Preference/recomposition/restart/separate-process tests |
| 18 | Highlight Config MIDlet profile section | WP5 | WP0, WP6 | Profile component/theme/large-font screenshot and semantics |
| 19 | Bounded QoL/accessibility improvements | WP8 | All touched WPs | Haptic/reduced-motion, 48 dp, focus, descriptions, disabled-action, partial-result audit |

## Risks and Guardrails

- Large screenshot matrix: use shared annotations/fixtures and limit it to materially changed screens/components; avoid redundant state combinations that do not alter layout.
- Binary baseline churn: update references once per reviewed visual milestone, with a final dedicated reference commit.
- Bundle compatibility: importer support lands before writers switch to v2; keep v0/v1 tests permanently.
- Filesystem mutation: serialize destructive/install/restore work, use generation-aware leases/journals, and report partial outcomes honestly.
- Bundle ownership: v2 is an app-payload bundle by default; preview/export copy must disclose that Room-only state is preserved locally. A future Room sidecar requires a versioned schema, conflict policy, and round-trip tests before any “complete backup” claim.
- Room state: no schema change is currently required for selection or bulk collection membership. If a sidecar or other change is discovered, follow `docs/library-schema-evolution.md` and the full migration chain; never delete/reset the database as a shortcut.
- ZIP routing: one classifier owns initial intents, `onNewIntent`, Options, and file-picker results; dedupe, retry, MIME/extension, plain-share-vs-bundle distinction, and provider failure behavior are tested.
- Runtime compatibility: implement/test a pure policy first, then wire it; do not mix Java ME API behavior changes into UI work.
- Adaptive scope: WP6 is a bounded width/IME/layout exception on existing Compose surfaces; use existing Compose and Navigation 3 where already present, but do not force a repo-wide Navigation 3 migration, `NavigationSuiteScaffold`, Scenes, Grid/FlexBox, or foldable/desktop support without concrete evidence.
- Compose state/window metrics: never mutate an `ArrayList`/`HashSet` held in ordinary `MutableState`; use immutable replacement or an intentional snapshot collection with a recomposition test. Migrate `screenHeightDp` only where it represents Compose layout bounds; preserve Java ME guest geometry and compatibility-boundary calculations until their contracts are proven equivalent.
- Accent scope: curated semantic palettes only; do not recolor guest MIDlet content or user-authored colors.
- Localization: change source/default and Indonesian strings for the new feature, mark all new strings translatable unless technically fixed, and leave broader locale translation completion to normal localization workflow while preserving resource availability. Convert only true quantity messages to plurals, preserve multi-count/progress semantics, remove the Indonesian marker override, and make the `MissingTranslation` suppression/baseline decision explicit.
- CI/device evidence: current workflow's `--continue` behavior is diagnostic only; milestone gates remain fail-closed. If no emulator is configured, retain automated policy/inset assertions and a named manual-device review rather than claiming an automated device pass.
- Final audit commands include `git diff --check`, orphan-reference inventory, hard-coded UI string/resource audit, Apache modification/attribution review, and dependency/asset scope review.
- Branch/PR: keep all implementation on `improve/ui-ux-harmonization`; do not push, create the PR, or merge without separate authorization.

## Definition of Done

- All 19 requested areas are implemented or explicitly documented as out of scope with user approval.
- Screenshot validation and behavior/unit/instrumentation suites pass.
- System-UI evidence is reviewed through deterministic device diffs or the documented policy/inset assertions plus named manual-device fallback; no unavailable CI target is implied.
- Light/dark/accent, compact/medium/expanded, portrait/landscape, large font, and IME-visible states are usable.
- Library returns to the same practical scroll anchor from MIDlet, Config, Settings, metadata, collection picker/browser, icon picker, external installer, refresh, rotation, and saved-state recreation flows.
- Single and bulk exports use the same v2 app-payload schema; v0/v1 imports remain supported, and Room-state exclusions/sidecar policy are visible and tested.
- Every selection action has confirmation/progress/result semantics appropriate to its risk.
- Final resource audit finds no new hard-coded user-facing text.
- The targeted Compose/lint audit is clean or has an explicit reviewed exception: no `MutableCollectionMutableState`, no unreviewed `ConfigurationScreenWidthHeight`, and no private AppCompat string-resource use; any retained AppCompat attr/ID boundary is documented and covered by a compatibility test.
- The localization pass verifies all eight named quantity candidates at their call sites, Indonesian plural categories, the non-translatable parent marker, and touched-resource `MissingTranslation` behavior; no warning is hidden by a new blanket suppression.
- Final diff contains no unrelated refactor, dependency/toolchain upgrade, version bump, temporary artifact, orphan screenshot, or licensing regression; `git diff --check` and the reference/resource/attribution audits are clean.
