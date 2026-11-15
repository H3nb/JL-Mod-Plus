# JL-Mod Plus UI/UX redesign blueprint

Status: design proposal, not an active UI contract. Confirm interaction changes against `docs/app-ui-development.md`, `docs/ui-ownership-map.md`, `docs/ui-copy-style.md`, and `docs/testing.md` before implementation. This plan records the initial design decisions and the hypotheses to test. It does not supersede the existing behavior contracts.

## Goals and scope

Make common tasks immediately discoverable without removing expert capability: find and launch a game, install/import, open its settings, adjust presentation/controls, and return to the same place. Use stable Material 3 1.4 with deliberate Expressive-inspired visual and interaction design, preserve the app's own color palettes and the emulator's View/Surface/Java ME boundaries, and avoid adding navigation stacks or state owners solely for appearance.

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
- **Product choice (2026-11-15): Material 3 1.4 stable is the active baseline.** Expressive is a visual/interaction design direction, not a requirement for the unavailable `MaterialExpressiveTheme`, 1.5-only components, or an imitation of their private APIs. Prefer official 1.4 components; add small app-owned styling only when necessary.
- Extend `JlModPlusTheme` with Expressive typography, shape/motion tokens and app-owned colors. Preserve accent selection and dark/light contrast; Expressive must not force Android dynamic colors.
- Evaluate new controls (e.g., split buttons, button groups, expressive menu/list variants) per interaction, not as a blanket replacement. Prefer real labels over icon-only actions when discovery would otherwise suffer.
- Follow established short directional page transitions and existing controller/Back/IME ownership; do not apply global Expressive animation to the guest renderer or runtime inputs.
- Use intentional soft surface layering; avoid mandatory dynamic blur or live glass effects for API 23 and slow devices.
- Preserve localization/resource ownership and sentence case. Do not hardcode new user-visible English strings in Compose.
- Keep one authoritative navigation and state owner per flow. The Java/Fragment/Activity hosts continue to own persistence, installers, Activity Results and runtime contracts until a demonstrated design need justifies migration.


## Compose components, visual hierarchy and motion audit (2026-11-15)

This is a **source-informed design review**, not a measured claim about a new implementation. Source reference: `alpha` at `9f86251186f010995f8370e7a6546f2ff5a4492a`. The earlier user-provided UI recording additionally shows the presentational symptoms; it may not represent the final code after intervening merges. No code or runtime behavior was changed by this review.

### Visual findings

The current UI is already dark/light themed, adaptive in many places, and reasonably consistent. Its main weakness is **low visual differentiation between important actions and secondary explanatory data**, not a complete absence of Material components. The recorded Library displays relatively small title/vendor text and frequent descriptions in compact rows; the Config recording shows long blocks of setting title, help, and value stacked together. Five compact Config destinations and small game artwork add pressure at phone widths. These are design hypotheses for controlled visual prototypes, not a blanket justification for larger cards, larger icons, or hiding essential settings.

The goal is an *understandable content hierarchy*: clear task/action at the top of each screen, generous touch targets but efficient vertical density, subdued metadata, prominent current values, consistent contrast, and refined surfaces. Do not add multiple nested card backgrounds or glass layers just to increase the number of visual effects.

### Component-level decisions

| Current component / code | Why reconsider it | Proposed direction | Novice impact | Advanced-user impact | Risk and gate |
| --- | --- | --- | --- | --- | --- |
| `FloatingActionButton` + `DropdownMenu` in Library | The icon-only plus requires users to infer installation; menu already correctly distinguishes file and folder | Prototype a labeled/extended Install FAB at rest; retain existing two source actions, responsive collapse only if needed | More recognizable entry to install | Existing file/folder access and minimum steps retained | Ensure no collisions with nav chrome, IME, scrolling; test available width and header visibility before changing animation |
| `LibraryListItem` custom row + Favorite | Existing Favorite occupies a 48dp trailing slot; actions require long press | Prototype visible game-actions affordance while preserving Favorite in List; allow adaptive trailing layout instead of cramming both controls at every width | Settings and metadata become discoverable | Direct Favorite and long-press remain; no more taps for common tasks | Two 48dp controls may squeeze title, especially at 320dp; test real title widths and separate clickable/focus semantics |
| `LibraryGridItem` custom column | Grid tap to play is good but no visible action control; small JAR art is sensitive to overlays | Prototype a consistent action affordance **outside** art where possible; compare minimal corner affordance with a layout that reserves a small controls row | Discoverable settings in Grid | Fast tap and selection mode unchanged | Never overlap action with selection checkbox; preserve 1:1/3:4 art and optional hidden titles; test 2/3/4-column configurations |
| `AppActionsDialog` bounded list | Long undifferentiated action list, especially in landscape and large text | Keep shared adaptive modal initially; consider a visually grouped action list with prominent Settings/Metadata, contextual collection actions, destructive Delete separated. Only compare bottom sheet if it proves a better full interaction | Quicker scanning and fewer ambiguous choices | Stable controller traversal, keyboard focus and actions must remain | Group labels affect controller indices; maintain logical focus order, modal Back, scroll bounds and selectable actions |
| Library `NavigationBar`/`NavigationRail` + `HorizontalPager` | Existing model already solves two device widths, gestures and selection | Retain; refine selected-state styling, text hierarchy and icon/label affordance rather than installing a second navigation framework | Familiar bottom destinations | Swipe, direct jump and controller navigation preserved | Existing all-pages retention has startup trade-off; do not drop retention to save memory without matched profiling |
| `ConfigSection`, `ConfigValuePreference`, `ConfigCompactValuePreference` | Long stacked title/help/value makes each entry visually equally important and costly to scan | Prototype two row densities: selected value trailing at sufficient width, below title on narrow widths; one-line helpful description when needed, detail/help affordance for genuinely long guidance. Preserve warnings/errors and destructive separation | Understands what is adjustable and what is currently selected | More rows visible, values easier to compare; all advanced settings remain directly reachable | No hidden essential compatibility explanations, no truncated values without a way to inspect, one semantic click target per row |
| `ConfigChoicePreference` / number / slider dialogs | Editors have local `remember(value)` visibility/draft semantics and host-owned save behavior | Retain editor ownership, customize presentation selectively. Do not mechanically move all sections to `LazyColumn` or swap every dialog for a menu | Consistent editing and cancellation | No loss of precise numerical entry, bounded values or profile draft behavior | Revisit the prior rejected lazy-Config trial before changing layout; test open dialog across parent state update, IME and Back |
| `AdaptiveAlertDialog` backed by `BasicAlertDialog` | Already provides bounded width/height and routes controller input/focus | Keep as a shared compatibility boundary; refine surface shape, spacing and action emphasis without replacing it globally | Predictable confirmation choices | Controller and keyboard navigation remain intact | Screen-height, 200% text, forced rotation, window ownership and dismissal must still work |
| `JlModPlusTheme` semantic typography/shapes/accent | Present shape scale 4/8/12/16/24dp and default semantic typography form a sound foundation but offer limited hierarchy variation | Design a small **stable-1.4-compatible** visual token set for title emphasis, density, surface levels, selected controls, and interaction feedback; avoid per-screen hard-coded color/size | Coherent and more legible appearance | Values and status remain precise; user accents retained | Check all accent variants, dark/light, Android default fonts, localization and contrast |
| Settings/Installer/File Picker/Runtime Menu | Already use Compose presentation with separately owned platform and emulator contracts | Defer component replacement until the Library/Config design system proves reusable. Consistency work must respect each surface's action, preview and state rules | Less fragmented experience | Existing machine-oriented tools remain available | Do not fold renderer, MIDP input, installer transactions or file picker lifecycle into a generic Compose abstraction |

A component **may be replaced** when its current API or structure obstructs the intended interaction, introduces concrete layout/focus/performance problems, or when the alternative reduces total state/ownership complexity. Standard Material `ListItem` is not universally better than a focused custom row; a bottom sheet is not universally better than a custom dialog; a `LazyColumn` is not automatically faster than a bounded `Column`. Evaluate by task semantics and measured behavior.

### Motion and responsiveness audit

Observed source:
- `NavigationMotion.kt`: existing full-screen transition uses **220ms** horizontal movement over **16dp** with **180ms** fade. Keep this as a coherent initial baseline, not a universal fixed rule.
- `LibraryComposeBridge.kt`: sibling tabs use `HorizontalPager` and `PagerTabMotion`; the finger swipe follows the gesture. Direct nav jumps use alpha/translation via `graphicsLayer`. Nav chrome uses fade + short slide; Install FAB uses fade + scale + slide. These are real implementations; they should not be replaced blindly with heavier animations.
- `ConfigComposeBridge.kt`: hierarchical `AnimatedContent` uses shared directional page motion. Preserves System Properties and Rendering subpage Back behavior.
- `LibraryScrollChrome.kt`: header placeholder height is read in Layout measurement rather than composition; nested scroll synchronizes header with list/grid displacement. This prevents actual viewport gaps/jumps and is not redundant complexity.
- `AdaptiveDialog.kt`: dialog input/focus routing is more than decoration; replacing the modal without that behavior would be a regression.

Proposed **motion language**, subject to a focused interaction trial:

| Interaction | Motion behavior | Reason and acceptance condition |
| --- | --- | --- |
| Press/click feedback | Immediate ripple/pressed/selected state using Material 3 1.4 controls; optional subtle scale/color feedback only if it does not delay action | Make input acknowledgment instantaneous; never animate before dispatching the action |
| Switching root tabs | Preserve gesture-tracking swipes and short direct-jump transition; selected indicator tracks the actual indicated destination | Maintain spatial continuity without animating through every intermediate destination |
| Opening hierarchical Config page | Short directional enter with reverse Back; retain 220/180ms baseline unless trial shows a better fit | Helps navigation orientation; avoid duplicate transition owners |
| Library chrome hide/reveal | Existing fade/slide baseline; compare simpler draw-only transition if profiling shows cost or if visual critique finds stacked motion distracting | Preserve header/list alignment and reachable controls; no invisible interactive targets |
| Install FAB appear/disappear | Existing fade+scale+slide is a candidate for simplification to a less busy combined motion; retain only if screenshot/video + traces support it | Distinct but not noisy, especially with bottom navigation animating |
| Filter/search results | Give immediate selected/working feedback, update content when state is ready; avoid full-list staggered entrance and artificial delays | Perceived response is more important than motion flourish |
| Modal open/close and destructive actions | Use short platform/Material transitions, keep focus scrim and dismissal semantics | Never let outgoing UI accept commands or show duplicate accessibility content |
| Selection and Favorite | Visible local state feedback; optional lightweight state-change motion, no whole-row reflow | Preserve list position and fast repeated operations |
| Runtime overlay over game | Minimal host-only transitions, no blur or repeated opacity/layer work over guest frames unless proven safe | Prioritize game frames/input and overlay transparency contract |

Animation policy: use motion to explain state changes, not to hide latency. Prefer draw-phase `graphicsLayer { ... }` for frequently changing alpha/translation and correctly keyed state reads, and avoid changing size/constraints every frame without a strong UX reason. This is a direction for measurement—not a reason to micro-optimize every animation. Respect system animation-duration settings, interruption/reversal, first frame, rotation and IME; never delay click handlers while awaiting an animation. In any motion, guard background focus/semantics and input through exit as existing contracts require.

### Performance evidence and experiment order

Existing physical/debug evidence in `docs/app-ui-performance-2026-10-08.md` and `docs/interaction-performance-trials-2026-10-08.md` is specific, not a guarantee for the new design:
- A recorded 60.429ms frame spent **43.438ms in measure/layout** after the most recent icon normalization had already completed; do not claim icon cache or animation is proven responsible for that stall.
- A Library pager-retention trial reduced an extreme navigation callback stall (~125.615 to ~36.437ms) but increased cold-start latency (~635 to 710ms) and had mixed frame results. Retention is a trade-off to validate if changing the pager.
- A rejected Config lazy-row experiment introduced recurring ~157–158ms layout blocks and **+12.1% Controls main CPU**. Do not repeat that structure as a generic modernization.
- A separately attempted chrome retention had no convincing scroll P95/CPU improvement and was rejected.

For the new design, measure in this order: **(1)** perception and task discoverability using a bounded prototype, **(2)** interaction correctness, **(3)** matched device timing and frame health, **(4)** state/refactoring simplicity. Collect first input response, callback duration, Compose layout/draw, FrameTimeline app deadline misses, warm scroll, startup, and memory where relevant. Do not infer overall responsiveness from average FPS or one warm device trace. Keep comparison artifacts local/CI and avoid adding permanent profiling machinery without an ongoing requirement.

### Prototype comparison and impact rubric

Use a small representative fixture with real/simulated low-res Java ME art, long mixed-language titles, loaded and unloaded metadata, a few empty states, and a collection. Compare at narrow portrait, compact landscape, larger window, light/dark, one or two contrasting accents, and 200% font only where the proposed component is sensitive.

| Design question | Novice success | Advanced success | Reject when |
| --- | --- | --- | --- |
| List/Grid secondary action | Finds per-game settings without instruction or long-press knowledge | Launch remains one tap; direct Favorite and controller flow still work | Consumes too much title space, overlaps artwork/selection, increases accidental launches |
| Labeled Install affordance | Understands how to add a file or folder | File and folder tasks remain no harder | Obscures games or interferes with list/nav chrome |
| Compact preference row | Can identify setting, current value and effect | Dense but precise; long values and advanced dialogs accessible | Essential help lost; 200% text or translated values clipped |
| Config navigation A vs B | Common orientation/screen-size and control tasks are easy to discover | Shader/profile/system paths require no gratuitous extra steps | Duplicate state, broken Back or significant extra expert navigation |
| Revised motion | State changes are clear; actions seem immediate | Fast repeat actions and controller interrupts remain smooth | Observable latency increase, lost focus, stale viewport, jank spike or gratuitous motion |

Stop experimenting when one design meets user tasks and technical gates sufficiently; do not force multiple component swaps or chase unmeasured cosmetic micro-optimizations.

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
