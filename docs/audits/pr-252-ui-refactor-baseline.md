# PR #252 UI/UX refactor — Stage 1 baseline

**Snapshot:** 2026-11-22, PR #252 (`feature/single-page-library`) at `dea33bd6d2328ce9c7e00c56f498f11102341652`, compared with `alpha` at `fd034535e493a1d856c307555a715ce3b84d5f8a`.

**Status:** Baseline/investigation only. This is a dated review record, not a new product contract or an authorization to change behavior. Read `AGENTS.md` and the owning documents linked below before changing an area. Later stages may replace implementation details while preserving the observable contracts.

## Scope and validation baseline

The effective PR diff against `alpha` contains **175 changed files**: 58 production source files, 7 resources, 11 Android instrumentation tests, 6 JVM tests, 3 screenshot-test source files, 84 screenshot references, and 6 documentation files. Source changes span the Library, MIDlet Config and profiles, Settings, Key Mapper, File Picker, installer, crash reports, shared app-owned presentation, and runtime host chrome. Some sources were split or removed; file count and changed-line count are not refactor goals.

At this snapshot, the required GitHub **Build and tests**, **Lint**, and **Screenshot validation** checks passed ([workflow run 44985664836](https://github.com/H3nb/JL-Mod-Plus/actions/runs/44985664836)). The build assembles an arm64 debug APK and runs app/dexlib JVM tests; Android instrumentation is **compiled, not executed** by the default validation run. Connected runtime tests, real-device visual/interaction checks, launch-time profiling, and release packaging/startup were **not established by this baseline**. A passing preview does not prove Back, IME, controller input, or activity recreation.

## UI surfaces and authoritative owners

| Surface | Presentation and transient state | Data, operation, and lifecycle authority | Boundary to preserve |
| --- | --- | --- | --- |
| Library browse, filters, search, collection scopes, list/grid and details | `LibraryScreen` / `LibraryComposeBridge.kt`, `LibraryNavigationState`, `LibraryViewportState`, `LibraryAppActions.kt` | `LibraryViewModel` and Room-backed repository for catalog data; `AppsListFragment` for host intents, installed identities, UI ID mapping and file results; collections store for membership UI requests | Workdir + generation identity, stable app ID, selected collection, search/browse isolation, viewport restoration, scoped item actions and installed-data safety |
| MIDlet configuration and quick settings | `ConfigScreen`, category/child-page state, shared preference components; `ConfigComposeController` for Compose host overlays | `ConfigActivity` owns `currentForm`, validation, persistence, installed-app write guard, asset results and guest launch | A failed write must not become a committed edit; preset ownership and installation identity cannot be silently retargeted |
| Named presets / profile editor | `ProfilesComposeBridge.kt`, Config presentation in profile mode | `ProfilesActivity`, `ConfigActivity` draft session and `ProfilesManager` source publication | Explicit Save/Discard, isolated draft, preset-source identity and atomic recovery |
| Global Settings, Updates, About | `SettingsComposeBridge.kt` owns screen and dialog presentation | `SettingsActivity` owns preference reads/writes, locale, Android pickers and navigation actions | Existing preference keys/defaults, result paths, system/theme behavior and locale identity |
| Key Mapper / control skins | `KeyMapperComposeBridge.kt`; Compose control-skin manager | `KeyMapperActivity` and existing layout/config/storage contracts | Mapping semantics, Activity results, per-orientation layouts and installed identity |
| File Picker | `FilePickerCompose.kt` owns visual presentation/preview; shared inline search UI | `FilePickerController` owns folder/search/selection/sort state; `FilteredFilePickerActivity` owns Android permission/result contracts | Select-all acts on visible matches without dropping hidden selections; root/path/mode/filtering and cancellation unchanged |
| Installer / bulk review | `BulkInstallerDialog.kt` provides review/progress/results | `BulkInstallViewModel`, installation planner and `LibraryViewModel` own action status, cancellation, identity and cleanup | No UI-only rewrite of install, rollback, overwrite or saved data |
| Runtime host controls | `RuntimeMenuCompose.kt`, `RuntimeHostDialogs.kt` and transient menu state | `MicroActivity`, `RuntimeHostView`, guest `Display` and native renderer/input | Host actions never usurp MIDP commands, event dispatch, guest lifecycle, surface geometry or runtime input |
| Crash reports / diagnostics | `CrashReportsComposeBridge.kt` | `CrashReportsActivity` and diagnostic storage/export owners | Report/export contracts and safe deletion remain independent of list layout |
| Shared app-owned UI | `JlModPlusTheme.kt`, `PreferencePresentation.kt`, `AdaptiveDialog.kt`, `InlineSearchField.kt`, `TransientNotice.kt` | Each caller retains its own state and actions | Common semantic typography/colors, responsive modal bounds, accessible controls, focus/IME/Back; no app-global navigation/notice state |

These are current owners, not immutable classes. For the complete current host/UI map see [UI ownership](../ui-ownership-map.md).

## Invariants to retain during stages 2–5

1. **User data and identity.** Library data in Room is not replaceable by a destructive reset; installed `appId`, workdir, generation and storage identity constrain every mutation. Keep existing database migrations, preset authority, transaction ordering and recovery. See [Library data](../library-data-contract.md) and [preset/config identity](../preset-config-contract.md).
2. **Navigation.** Library search and quick views do not mutate the inactive browse projection; returning from dialogs, metadata and contextual settings preserves the applicable workdir/collection viewport. Config uses Quick Settings as root plus category and at most one child page, with independent category scroll. File Picker has a controller-owned folder hierarchy; no parallel navigation stack. See [app UI](../app-ui-development.md).
3. **Platform and input.** Preserve Android 6+ (API 23+) compatibility, ActivityResult behavior, file picker raw-path/permission contracts, IME focus and Back priority. Runtime host presentation must not change Java ME LCDUI dispatch, Canvas dimensions/surface geometry, keyboard/controller events or native rendering. See [runtime UI](../runtime-ui.md).
4. **Semantic presentation.** App-owned typography and colors follow Material 3 roles and the existing theme. Long text, Indonesian/English localization, 200% font, short landscape, safe insets and IME must not make actions inaccessible. Share policy where behavior is the same; allow screen-specific density, geometry and content hierarchy. See [UI copy/style](../ui-copy-style.md), [localization](../localization.md) and [app UI](../app-ui-development.md).
5. **Feedback and failure.** A failed save or operation must remain detectable and must not silently discard an unsaved draft. One modal/focus owner should handle dismissal; transient notices do not substitute for durable error or validation state.
6. **Performance.** Evaluate real duplicate transformations, main-thread I/O and startup/scroll costs before adding caches, synchronization, derived state or layout rules. Preserve intentionally stabilized sheets/viewport behavior unless an alternative is demonstrated better.

## Stage 1 observations, not preapproved changes

| ID | Evidence at snapshot | Classification | Follow-up |
| --- | --- | --- | --- |
| C1 | `ConfigActivity.onPause()` ignores the result of `saveParams()`; normal `onResume()` reloads persisted state into `currentForm` | **Credible data-loss failure path**, inherited from `alpha`; no device reproduction claimed | Stage 2: prove failure path, protect draft on failed save, preserve installed-identity guard |
| C2 | Profile-editor `profileDraftDirty` is set after an ordinary subsequent resume and on any `updateForm()`, not necessarily an effective change | **Confirmed state-derivation weakness**, inherited from `alpha`; impact on user flows needs interaction verification | Stage 2: separate effective form changes from external layout edits; keep Save/Discard correct |
| C3 | File Picker calculates `filterAndSort` separately in the composable toolbar and list, with the controller also computing selection eligibility at action time | **Duplicated projection work**; actual performance cost not measured | Stage 3/4: unify presentation projection if it removes work without creating another state owner; retain action-time authority |
| C4 | MIDlet App Actions applies a body-height bound derived by subtracting fixed header/footer allowances from window height | **Adaptive-layout risk**, not a proven clipping bug | Stage 4: inspect 200% text, short landscape and IME; preserve deliberate height stability that avoids sheet bounce |
| C5 | `LibraryScreen` coordinates numerous temporary editor, selection, controller and viewport states | **Review hotspot**, not itself a confirmed architectural defect | Stage 2/3: map mutually exclusive overlays and stale-event boundaries; extract only when ownership becomes clearer |
| C6 | UI refactor has moved and removed code, including old Library collection-browser source and screenshot references | **Dead-code audit candidate**, not proof that remaining legacy code is unused | Stage 3/4: inspect references, reflection/resource entry points and guest compatibility before deletion |

Do not treat these observations as requirements to rewrite a file or add a framework. Validate the cause and failure mode before editing; retain intentional flexibility.

## Targeted verification map

| Boundary | Lowest useful evidence | Remaining validation |
| --- | --- | --- |
| Library state, collection identity and viewport | Existing `LibraryNavigationStateTest`, `LibraryComposeTest`, `LibraryViewportNavigationTest`, `LibraryCollectionsNavigationTest`, `LibraryUiContractSuite` | Focused connected UI interaction for search/IME/Back, scope switching, selection, return from details/editor and rotation |
| Config drafts, profiles and persistence | Existing configuration/preset JVM tests, `ConfigComposeTest`, `ConfigActivityComposeSmokeTest`, profile identity tests | Android activity lifecycle and injected save failure; real editor return, reset, rotation, Save/Discard |
| File Picker selection and results | `FilePickerComposeTest`, `FilePickerIntentContractTest`, FilePicker rules/unit coverage | Search/selection across folder changes; cancel/choose/permission and IME Back on device |
| Shared layouts, theme and notices | Shared component tests, `AdaptiveDialogComposeTest`, screenshot previews | Review actual renders and test action reachability at narrow width, 200% text, short landscape, insets and dark/dynamic color |
| Runtime host menu and input | `RuntimeMenuLayoutTest`, `RuntimeMenuComposeTest`, runtime contracts | Focused device tests for controller Back, menu and host/guest boundary; never infer guest correctness from screenshot tests |
| Installer, Settings, Key Mapper and reports | Existing relevant Compose interaction/host smoke tests | Execute focused Android tests when changing lifecycle, storage, permissions or results |

Use the [testing policy](../testing.md) and [build/CI workflow](../development.md). Do not add a broad test matrix or regenerate goldens by default; tests must target distinct regressions. Compare rendered output before accepting any reference update.

## Stage boundaries and completion criteria

- **Stage 1 — complete with this record:** inventory current source and PR-vs-`alpha` scope, identify authoritative owners, protected user-visible behavior, validation gaps and candidate risks. No executable behavior changed.
- **Stage 2 — correctness first:** investigate C1/C2 and any proven navigation/modal/identity defects; validate failure/recovery and Back/restore contracts before cleanup.
- **Stage 3 — consolidation:** remove justified duplicate computations, stale bridges or dead code and migrate Java to Kotlin selectively when total conceptual complexity decreases; preserve platform/guest boundaries.
- **Stage 4 — presentation/performance:** evaluate adaptive bounds, type/color hierarchy, locale, accessibility, startup/scroll performance with targeted renders, interactions and measurements.
- **Stage 5 — pre-merge:** independently audit the **effective diff against `alpha`**, including old fallbacks, obsolete layers, safe migration paths, test execution rather than compilation, and device evidence. Keep PR Draft until outstanding validation and product decisions are resolved.

**Stop rule:** Do not refactor merely to reduce line count or make every Java source Kotlin. Stop when protected behaviors are satisfied, relevant checks pass, and remaining alternative designs have no demonstrated material benefit.
