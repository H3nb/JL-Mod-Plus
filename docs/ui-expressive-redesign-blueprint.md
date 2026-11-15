# JL-Mod Plus UI/UX redesign blueprint

Status: design proposal, not an active UI contract. Confirm interaction changes against `docs/app-ui-development.md`, `docs/ui-ownership-map.md`, `docs/ui-copy-style.md`, and `docs/testing.md` before implementation. This plan records the initial design decisions and the hypotheses to test. It does not supersede the existing behavior contracts.

## Goals and scope

Make common tasks immediately discoverable without removing expert capability: find and launch a game, install/import, open its settings, adjust presentation/controls, and return to the same place. Use Material 3 Expressive deliberately, preserve the app's own color palettes and the emulator's View/Surface/Java ME boundaries, and avoid adding navigation stacks or state owners solely for appearance.

Success requires better task clarity, consistent visual hierarchy, robust touch/controller/keyboard access, and no material degradation in UI latency or resource usage. Do not use library upgrades, Kotlin migration, or fewer lines of code as proxies for these outcomes.

## Observed implementation

| Surface | Current owner | Redesign implication |
| --- | --- | --- |
| Library tabs, search, filters, actions, viewport and collections | `LibraryComposeBridge.kt`, `LibraryViewModel`, `LibraryNavigationState`, `LibraryViewportState` | Maintain the Apps/Collections/More destinations and saved navigation/scroll/search ownership; Compose presents, host actions operate. |
| Grid/list game items | `LibraryGridItem` / `LibraryListItem` | Item tap launches; long press opens actions. List already has Favorite, but the actions menu has no permanent visible trigger. Grid has neither a visible menu trigger nor a favorite button. |
| Game action menu | `AppActionsDialog` | Single bounded, scrollable menu with controller focus index; preserve its supported operations and modal/Back semantics. |
| Config destinations | `ConfigComposeBridge.kt` | Five tabs: Basic, Display, Media, Controls, System. Basic currently combines resolution/orientation/scale, virtual keyboard/touch and maintenance; other screens contain advanced rendering, audio, input, and system settings. |
| Config values and drafts | `ConfigPreferenceComponents.kt`, `ConfigActivity.java`, `ConfigFormState` | Keep host-owned persistence/validation, profile linkage, unsaved drafts, activity result and Back contracts. Do not blindly change the column to a lazy list. |
| Theme and dialogs | `JlModPlusTheme.kt`, `AdaptiveDialog.kt` | Reuse accent palettes and window/modal policies. Test Expressive typography, shapes and motion rather than replacing every modal or forcing blur. |
| Installer and runtime UI | `InstallerComposeBridge.kt`, `BulkInstallerDialog.kt`, `RuntimeMenuCompose.kt` | Retain installer sequencing/cleanup and emulator input/rendering ownership; redesign only host presentation. |

## Decision 1: Library interaction model

The Library remains **game-first**, not a dashboard of emulator settings:

1. **Apps** remains the initial destination. Primary tap on an unselected game launches it. Long press continues to show its actions. Search, sort, quick views (All, Favorites, Recently added, Recently played), list/grid choice and install remain discoverable without navigating elsewhere.
2. **Game secondary action** becomes visible in the item when selection mode is inactive. In List view, evaluate placing an overflow affordance beside the existing Favorite action; preserve Favorite's quick access. In Grid, evaluate a consistently located affordance that does not obscure small JAR artwork or conflict with the selection checkbox. Confirm the exact placement in a compact-width prototype before coding.
3. **Selection mode** takes precedence over launching, Favorite and overflow. The whole item selects/toggles; selection indicators and toolbar remain legible and accessible. Do not create duplicate focus targets or conflicting click regions.
4. **Game action menu** retains every existing supported operation: selection, metadata/rename, shortcut, add/remove collection, per-game settings, reinstall, share, bundle export, delete. Put the most common non-destructive actions first and destructive delete last. A grouped presentation is optional if it improves scanning *without* breaking controller index/navigation.
5. **Collections** remains a primary tab. Preserve selection, management, picker state, active collection identity, list/grid scroll and route restoration. **More** contains global actions; avoid adding extra root destinations for functions already reachable there.
6. **Install** preserves existing single-file/folder flows, source menu semantics, and the distinction between installer review and batch installation. Avoid adding a second install route solely for visual symmetry.

### Library visual behavior

- Strong hierarchy: Library title / search / quick filters / content; game artwork and title remain dominant. Default List vs Grid and description visibility should be decided from actual small-icon samples and real task trials, *not* from aesthetic preference alone. Preserve user preferences.
- Keep row density appropriate to narrow widths. Do not crowd title, Favorite and overflow into unusable touch targets; size actions for accessibility. Do not sacrifice game names to display redundant metadata.
- Honor every existing chrome-scroll constraint, search/IME Back ordering, pager swipe and direct tab transition, workdir-scoped viewport identity, safe insets, keyboard/controller focus and metadata editor restoration.
- Avoid expensive full-screen blur, unnecessary shadow layers and image transformations in scrolling items. Keep existing bounded icon preparation until measurement justifies changes.

### Representative Library tasks to validate

| Task | Expected result |
| --- | --- |
| Open a game | One primary tap from the visible item; controller Activate remains supported. |
| Open per-game Config | Visible secondary action or long press > Settings; Back returns to the same Library viewport. |
| Favorite a game | One direct action in List where supported; Grid access remains discoverable; selection mode cannot accidentally favorite. |
| Search and switch quick view | Preserve query and header position as specified; new result list starts at the top; first Back dismisses IME/focus. |
| Manage a collection | Enter/leave collection without losing identity, scroll, or selection state. |
| Install from file or folder | Existing picker, cancellation, and installer lifecycle continue unchanged. |

## Decision 2: Config information architecture

**Do not assume that adding an Overview to five tabs is useful.** First prototype two alternatives:

- **A: five direct destinations**, with Basic redesigned as a concise *Quick setup* within the existing destination model. Display, Media, Controls and System remain directly accessible. Group existing Basic sections visually; preset summary and destructive maintenance retain appropriate prominence and separation.
- **B: a category landing page plus subpages**, only if A remains too dense or fails basic discoverability. Measure the extra navigation steps and handling of initial destination, pager gestures, profile editing, Back and scroll restoration before choosing B.

**Provisional choice: A.** It retains single-step access for power users, reuses existing state ownership and minimizes navigation duplication. It is not a requirement to keep the present five-tab labels or row styling unchanged.

### Config content model

- Basic / Quick setup: existing resolution presets, orientation, scaling, input essentials, profile/preset entry and clearly separated maintenance actions. Do not duplicate every setting under two tabs.
- Display: existing graphics/rendering, fonts and performance controls, shader subpages. Link to the relevant category rather than cloning its values into Quick setup.
- Media: audio and camera.
- Controls: control layout, skin, input mapping and associated hardware/controller settings as currently supported.
- System: compatibility/system settings and the system-property editor.
- Per-game editing and profile editing share presentation only where semantics match; profile draft saving/discard must remain explicit and correct.

### Preference row design

The common value row should make **title and selected value** easy to scan. Show an explanatory description when it improves a decision; avoid repeating long help text at full prominence on every row. Switch and checkbox rows must remain a single accessible target. Messages, disabled states and dangerous actions stay distinguishable. Numeric/slider editors keep existing commit semantics, bounds, labels and error behavior.

Test row density at 320–360dp width, large font scale, landscape, dialog with IME, and controller focus. Migrate only the affected rows to new expressive variants; avoid an app-wide generic preference DSL unless it removes demonstrated duplication.

### Representative Config tasks to validate

| Task | Expected result |
| --- | --- |
| Set screen dimensions/orientation | Quickly accessible; edits update the form correctly and save according to host lifecycle. |
| Change shader, scaling or FPS limit | Technically accurate values, existing subpage/Back semantics and remembered scroll. |
| Edit controls and return | Existing picker/editor results and control settings remain intact. |
| Edit a linked preset or profile draft | No accidental overwrite, lost draft, or silent change in ownership. |
| Launch a configured game | Start action and configuration persistence behave as before. |

## Decision 3: design-system and engineering boundaries

- Material 3 `1.5.0-rc01` is **currently blocked** for production: PR #249 CI reports that the transitive `material3-ripple-android:1.5.0-rc01` requires minSdk 24, while JL-Mod Plus requires API 23. Keep the current stable BOM-managed Material 3 for implementation unless a properly supported API-23-compatible release is verified or product support policy is explicitly changed. Do not use `tools:overrideLibrary`, force an older ripple artifact, or silently raise minSdk.
- Extend `JlModPlusTheme` with Expressive typography, shape/motion tokens and app-owned colors. Preserve accent selection and dark/light contrast; Expressive must not force Android dynamic colors.
- Evaluate new controls (e.g., split buttons, button groups, expressive menu/list variants) per interaction, not as a blanket replacement. Prefer real labels over icon-only actions when discovery would otherwise suffer.
- Follow established short directional page transitions and existing controller/Back/IME ownership; do not apply global Expressive animation to the guest renderer or runtime inputs.
- Use intentional soft surface layering; avoid mandatory dynamic blur or live glass effects for API 23 and slow devices.
- Preserve localization/resource ownership and sentence case. Do not hardcode new user-visible English strings in Compose.
- Keep one authoritative navigation and state owner per flow. The Java/Fragment/Activity hosts continue to own persistence, installers, Activity Results and runtime contracts until a demonstrated design need justifies migration.

## Implementation sequence and refactoring rules

1. **Foundation**: record the minSdk-23 failure in the dedicated RC experiment (PR #249). Continue with the stable Material 3 foundation and app-owned expressive-inspired tokens; re-qualify 1.5 only when its transitive Android 6 support is proven. Inspect compilation, lint, screenshots and representative devices. Separately qualify theme tokens on a contained preview before widespread restyling.
2. **Library vertical slice**: Apps list/grid item, visible actions, game actions and return-to-Library path. Make directly affected composables easier to read and test. Keep existing projection, icon cache and viewport owners unless evidence requires otherwise.
3. **Library consistency**: search, filters, Collections, More, empty/loading/error states, navigation/chrome and adaptive widths; fix interaction inconsistencies surfaced by the slice.
4. **Config vertical slice**: choose A/B using task prototype, then redesign Basic/Quick setup and shared preference rows, followed by Display/Media/Controls/System. Refactor form-to-UI projection only when profiling or ownership problems justify it.
5. **Supporting surfaces**: Profiles, installer/batch installer, Settings, File Picker, Key Mapper/skins and runtime host menu; preserve each host's lifecycle/compatibility contracts. Memory Editor deserves a distinct risk-focused review.
6. **Final consolidation**: remove superseded in-scope components, check locale/contrast, accessibility, device performance, and independent effective-diff review. Ship only after the relevant gates are satisfied.

Refactor **with** the affected screen, not in one preparatory rewrite of the Android Java host. Splitting a long bridge file is useful only when it clarifies responsibilities; avoid forwarding wrappers, duplicate state, speculative caches, new navigation frameworks or migration of stable renderer code to satisfy style preferences.

## Verification gates

- **Behavior:** task-by-task user flow, action semantics, install and draft persistence, Back, controller, search/IME, selection, scroll restoration, collection/workdir isolation.
- **Visual and accessibility:** targeted screenshot cases, both themes/accent contrast, 200% text, narrow and landscape windows, safe drawing and camera cutout. Review diffs manually; never blindly promote all goldens.
- **Compatibility:** Java ME input and runtime surfaces remain untouched by ordinary app-UI work; on changes to host overlays, check guest launch, pause/resume and overlay state.
- **Performance:** compare matched build/device/workload for startup, scrolling, navigation, interaction callbacks, frame time and CPU; use current interaction trials (`docs/interaction-performance-trials-2026-10-08.md`) as cautionary evidence, not as a directly transferable numeric baseline. Previous Config lazy-layout trial increased layout stalls and Controls CPU; do not repeat without a changed hypothesis and measurements.
- **PR quality:** minimal relevant tests, normal Android CI, final effective diff against fresh `alpha`, no unnecessary test matrix, no obsolete compatibility shims.

## Open choices to close with a bounded prototype

1. **List overflow and Grid affordance:** exact touch-friendly placement alongside Favorite / small artwork without harming tap-to-play and selection mode.
2. **List or Grid initial presentation:** compare game recognizability, artwork fidelity, density and speed on representative game libraries. Existing users keep their chosen layout.
3. **Config A vs B:** compare completion steps and confusion for a novice adjusting resolution and an expert changing shaders, profiles and controls. Prefer A unless B demonstrates meaningful advantage.
4. **Motion and typography:** select the smallest set of Expressive tokens that genuinely clarifies hierarchy and transitions on both low-end and modern Android.

Do not resolve these choices with speculative implementation. The first Library slice and focused Config prototype supply the evidence.
