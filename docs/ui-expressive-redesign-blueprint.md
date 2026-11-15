# JL-Mod Plus UI/UX redesign blueprint

Status: design proposal, not an active UI contract. Confirm interaction changes against `docs/app-ui-development.md`, `docs/ui-ownership-map.md`, `docs/ui-copy-style.md`, and `docs/testing.md` before implementation. This plan records the initial design decisions and the hypotheses to test. It does not supersede the existing behavior contracts.

## Goals and scope

Make common tasks immediately discoverable without removing expert capability: find and launch an installed Java ME application (MIDlet), install/import, open its settings, adjust presentation/controls, and return to the same place. MIDlets include games, productivity tools, utilities, browsers and other Java ME applications; never assume the installed Library contains games only. Use stable Material 3 1.4 with deliberate Expressive-inspired visual and interaction design, preserve the app's own color palettes and the emulator's View/Surface/Java ME boundaries, and avoid adding navigation stacks or state owners solely for appearance.

Success requires better task clarity, consistent visual hierarchy, robust touch/controller/keyboard access, and no material degradation in UI latency or resource usage. Do not use library upgrades, Kotlin migration, or fewer lines of code as proxies for these outcomes.

## Observed implementation

| Surface | Current owner | Redesign implication |
| --- | --- | --- |
| Library tabs, search, filters, actions, viewport and collections | `LibraryComposeBridge.kt`, `LibraryViewModel`, `LibraryNavigationState`, `LibraryViewportState` | Maintain the Apps/Collections/More destinations and saved navigation/scroll/search ownership; Compose presents, host actions operate. |
| Grid/list MIDlet items | `LibraryGridItem` / `LibraryListItem` | Item tap launches; long press opens actions. List already has Favorite, but the actions menu has no permanent visible trigger. Grid has neither a visible menu trigger nor a favorite button. |
| MIDlet action menu | `AppActionsDialog` | Single bounded, scrollable menu with controller focus index; preserve its supported operations and modal/Back semantics. |
| Config destinations | `ConfigComposeBridge.kt` | Five tabs: Basic, Display, Media, Controls, System. Basic currently combines resolution/orientation/scale, virtual keyboard/touch and maintenance; other screens contain advanced rendering, audio, input, and system settings. |
| Config values and drafts | `ConfigPreferenceComponents.kt`, `ConfigActivity.java`, `ConfigFormState` | Keep host-owned persistence/validation, profile linkage, unsaved drafts, activity result and Back contracts. Do not blindly change the column to a lazy list. |
| Theme and dialogs | `JlModPlusTheme.kt`, `AdaptiveDialog.kt` | Reuse accent palettes and window/modal policies. Test Expressive typography, shapes and motion rather than replacing every modal or forcing blur. |
| Installer and runtime UI | `InstallerComposeBridge.kt`, `BulkInstallerDialog.kt`, `RuntimeMenuCompose.kt` | Retain installer sequencing/cleanup and emulator input/rendering ownership; redesign only host presentation. |

## Single-page Library design research (2026-11-15): Java ME applications, not a games-only launcher

**Status:** preferred **product design candidate**, not implemented and not an active UI contract. Supersedes the *product direction* of the older three-tab Search/Collections alternative below, but **not** the existing source/API, saved-state and lifecycle guarantees. The user proposes one Library root with top-right Search and Settings, an expanded Search surface, integrated collection scope, context-dependent quick filters, optionally recently used collections, and a FAB to install/import. PR #251 remains a separate draft prototype: do not absorb this navigation redesign into its patch.

### Terminology and information architecture

- A **MIDlet** is a Java ME application, not necessarily a game. For general audience-facing copy use **Apps / Aplikasi**, **All apps / Semua aplikasi**, **Search apps / Cari aplikasi**, **Open / Buka**, and **App settings / Pengaturan aplikasi** where unambiguous. Use **MIDlet** in technical/configuration contexts, compatibility and diagnostics when distinguishing guest apps from JL-Mod Plus itself. Reserve *game* for a confirmed game-specific context; never name the entire Library *Games* or use only controller/gamepad imagery as the product identity.
- **Top-level product experience:** a single **Library** root. This removes the always-composed Apps/Collections/More destinations and their three-tab bar/rail **if** the state migration and interaction evidence justify the change. Search is a full-screen/docked **task surface**, Collection is **Library scope**, Settings is a coherent **global settings destination**, not another hidden tab.
- Keep **global JL-Mod Plus settings** separate from **per-MIDlet Config** and virtual controls; the gear in the Library header must not be confused with changing an individual MIDlet's resolution, mappings or sound. A row's overflow and long-press lead to that MIDlet's Config.
- Avoid home-screen *dashboard* composition. The main content remains a fast launcher/browser of installed MIDlets. Sections should not push the first item below the first screen, especially for five-to-ten items.

### Proposed phone hierarchy and visual priority

1. **Persistent compact app bar:** existing JL-Mod Plus wordmark at left, *Search* icon and *Settings* icon at right. Use status indicators (e.g. update availability) only when real, not decorative badges. Keep safe-area / curved-cutout handling. On scroll, evaluate a pinned compact header while only scope/chips/secondary controls scroll; retaining the current measured chrome is an implementation decision to verify by frame/viewport tests, not an assumption.
2. **Scope + view controls:** a clearly labeled **All apps ▾** selector, changing to the selected collection's actual name; List/Grid view toggle (where already discoverable) and Sort near the content heading or at the scope row. Sort direction must be shown as a state, not a generic ambiguous slider icon.
3. **Quick views only in All apps scope:** Favorites, Recently played/opened, Recently added (localize without implying game-only play when the recorded event is an app launch). Do not duplicate an “All” filter chip when “All apps” already names the active scope unless it also performs a documented distinct reset action. A selected collection initially shows its membership without inherited hidden All-scope filters; preserve each scope's scroll and restoration rules.
4. **Optional collection shortcuts:** at most two recently **accessed** collections if valid recent-access evidence exists and height permits; compact single-row chips/cards, not two tall hero cards. No access history currently exists in \`LibraryCollectionsUiState\`; do not invent a default order and label it “recent.” If zero collections, remove the section entirely but leave **Create/Manage collections** discoverable in the scope selector. If no recorded access, omit shortcuts until a justified local-only history exists. A tap switches scope; never silently merges collection members into the All list.
5. **Application list/grid:** immediately following scope and quick views, with a lightweight count, visible Sort and the established List/Grid preference. Prefer app icon and app name as primary hierarchy; author/vendor/version as secondary, description tertiary and optional. Allow long names, 3:4/1:1 custom icons and user settings. Main tap runs the selected MIDlet. One consistent per-item menu leads to Config and management. On an active selection, replace normal item action semantics with selection semantics.
6. **One contextual Add FAB:** actions **Install from file**, **Install from folder**, **Import bundle**. The button can say **Add / Tambah** with a screen-reader label identifying MIDlets/apps. “Import bundle” is not equivalent to ordinary installation because it may restore settings/save data and overwrite existing values; retain the current confirmation and host result contracts. The FAB may collapse to icon-only for narrow width, compact height, 200% text or when the header/scroll policy dictates, but it may not obscure the last row or accept taps behind an overlay.

**Avoid duplicating four launch routes to the same function** (permanent tabs, large cards, header menu, FAB). Keep one obvious primary entry and a justified secondary path for expert tasks.

### Scope selection and Collections

- First selector entries: **All apps**; named Collections, with **Manage collections** and **Create collection** in a logically separate action section. One item per collection with count, never a horizontal chip for *each* collection. For large numbers of collections, provide a scrollable bounded selection surface; a Material \`DropdownMenu\` suits *short* anchored choices only. For many collections, prefer a constrained dialog or dedicated collection picker with search only if the collection count warrants it.
- The existing \`LibraryCollectionsHost\`/store already owns create/rename/delete, members and membership editing. Retain the collection's stable **id + workdir scope**, \`LibraryCollectionBrowser\` and its adaptive \`ListDetailPaneScaffold\` where that reduces real navigation; reuse, do not copy its membership code into \`LibraryAppsDestination\`.
- Switching to a collection must label the scope conspicuously and provide an obvious All apps exit. A missing/deleted collection, workdir change and process restore must return to a valid scope without exposing members from another workdir.
- **Recently opened** is ambiguous for general MIDlets: it means launch/open history, not necessarily games played; use *Recently opened / Baru dibuka* for generic user-facing labels where the existing statistics represent launch events.

### Search surface (Material 3 1.4, not 1.5 RC)

- Tap Search icon: open an expanded search surface; focus field and request IME **on deliberate activation**. Input immediately narrows **installed MIDlets**. Search is not a permanent root page; closing returns to the same visible Library scope, scroll and selection state.
- The official Material 3 API offers \`SearchBarState\`, \`SearchBar\`, \`ExpandedFullScreenSearchBar\` and \`ExpandedDockedSearchBar\`. The expanded implementations open their own window; test this against JL-Mod Plus's host \`Fragment\`/controller focus, Activity Back, edge-to-edge insets, rotation and window restore. An ordinary Compose full-screen replacement is a viable fallback only if it is demonstrably simpler and preserves semantics; do not recreate an entire search-bar framework for styling.
- **Search input scope:** default clearly to **all installed apps**; explicitly switching to a selected collection is optional if justified. Do not filter the background Library through \`LibraryViewModel.setFilter\` without separating the browse and search projections: the current global \`MutableStateFlow\` query drives the existing \`LibraryUiState\` and will also shift the underlying list while search is open.
- Search result items must use the same item actions, launch IDs and localized titles as Library. Do not manufacture recommendation/recent-query telemetry to fill an empty Search page; show a concise prompt, or a small truly local recent query list only if this is later approved.
- Back order: first dismiss the IME **and** resolve text focus correctly (do not require two presses because a hidden field still owns focus); when the keyboard is closed, next Back closes Search; further Back follows current host behavior. Close explicitly from the leading back control. The search field must not reactivate unexpectedly after external Config, orientation changes or controller focus changes.
- On large windows, evaluate full-screen vs docked search according to available pane width and task efficiency; do not assert tablet must always be full-screen. Animated expansion should be interruptible, respect reduced motion where applicable, and avoid layout jitter or expensive blurred background.

### Application item design: name, Favorite, overflow, description

- **List**: prioritize icon, up to two lines of app name, and concise vendor/version information. Description stays logically aligned with the name (not a separate left-aligned block under an icon) and retains the existing per-user visibility setting and expandable semantics; two preview lines is a maximum, not a forced two-line blank reserve. High-contrast surfaces distinguish selection, hover/focus and pressed states.
- PR #251's two equal trailing 48dp Favorite + overflow targets demonstrably compete with names on compact/200% layouts. Prototype (a) direct Favorite + visually quieter overflow **and** (b) state indicator + overflow with Favorite available in actions. Preserve direct Favorite for power users **only if** the full title, description and touch geometry are satisfactory. Do not choose the visually cleanest row at the cost of a commonly used action without feedback.
- **Grid**: icons may be 1:1 or 3:4, high/low resolution, optionally with hidden titles. The established **88dp minimum cell width** can result in four columns at 360dp; an overlaid 48dp menu affordance therefore consumes too much art/hit area. Keep tap-to-launch, long-press and controller context actions while investigating a clearly discoverable alternative that does not overlap artwork. Do not force all tiles into padded elevated cards or show redundant vendor/version under each small icon.
- Do not infer that a MIDlet is a game from its icon art or show a gamepad badge by default. Preserve user-edited titles and localized descriptions, optional custom icons and sorting.

### Global settings destination: consolidate, not rename More

The current \`LibraryMoreDestination\` links **Settings Activity, Updates, Import bundle, Donation, Crash reports and About**. The current \`SettingsComposeBridge\` already edits **Appearance, Language, Library appearance, MIDlet runtime, Storage/workdir, Profiles and Experimental**. A gear that first opens an unchanged More menu requiring a second tap to open Settings is not an improvement.

Preferred **single global Settings surface** (may reuse \`SettingsActivity\` and its existing host, locale restart and Activity Result semantics):
- **Appearance & language**: theme, accent palette, system/dark choices, app language.
- **Library**: list/grid layout, icon ratio 1:1/3:4, shape, enhanced icons, titles/descriptions, spacing and other presentation settings actually present.
- **Storage & profiles**: working directory in a prominent, clearly explained row; profiles action if globally accessible. Changing workdir can switch database/library identity and must preserve file access and restore behavior.
- **Updates & diagnostics**: update availability/check, release access, crash reports / diagnostic artifacts.
- **About & support**: project info, repository/links, licenses, voluntary support/donation.
- **MIDlet runtime** and **Experimental** remain discoverable categories when currently supported; they must not be mislabelled as app visuals and their existing settings must remain intact.

Use ordinary section headings and grouped rows, not one card per setting, nested drawers and duplicated "Advanced" routes. Keep the workdir value understandable without clipping important identity. For tablet/expanded width a master/detail settings layout is optional where it materially reduces navigation; on a phone use an independent scrollable list with a clear Back and stable state.

### Responsive and adaptive rules (start with window **area**, not device name)

| Available window | Recommended composition | Risks/tests |
| --- | --- | --- |
| 320–599dp width, >=480dp height | One-column List or existing preference-driven Grid, compact pinned header, short selector, horizontally scrollable quick chips only when All scope, contextual Add FAB | 320/360dp, 200% font, 1:1/3:4 art, long Indonesian/MIDlet titles, IME and inset collisions |
| >=600dp width, <480dp height (e.g. phone landscape) | **Short single-pane**. Keep app bar to one row; put scope/filter/sort into scrollable content or contextual menus, use icon-only FAB and bounded menus. **Do not automatically force two panes just because width is medium.** | Very small remaining vertical area, keyboard, selection toolbar, dialog reachability, controller |
| 600–839dp width with usable height | Widen List items; optional compact collection/sidebar and main items **only if** it improves actual browsing; Grid density must not simply jump to excessive columns | Pane/selector consistency, scroll anchors on resize, landscape, split screen |
| >=840dp width with usable height | Center content within a readable max width or use a meaningful scope/list-detail arrangement for collection management. Grid cells should not become a dozen 88dp tiles; adjust content width / grid geometry while honoring explicit density preferences | Multiwindow/fold hinge, collection list-detail restore, pointer/keyboard navigation |

Use Material's adaptive window info (already in Collections) for layout choices, including posture/hinges where the existing code handles them. A **single root destination** does not justify adding a \`NavigationRail\`/drawer: those components are for multiple destinations, not just because the window is wide.

### Concrete Material 3 visual language for JL-Mod Plus

- **Identity:** a restrained modern utility/library with nostalgic content, not a gaming-only arcade. Existing \`JLModPlusTheme\` already provides stable dark/light semantic surface roles and Sapphire/Violet/Teal/etc accent palettes. Reuse \`surface\`, \`surfaceContainerLow\`, \`surfaceContainerHigh\`, \`primaryContainer\` and on-colors rather than adding transparent glass behind every item.
- **Hierarchy:** the title and MIDlet icons are the dominant visual landmarks. Controls are 48dp touch targets but can have quieter 20–24dp glyphs. Use subtle shape and tonal contrast for selector/selected chips, not a border around every row and no full-size illustration unless it communicates genuine empty-state purpose.
- **Motion:** the current app-wide page baseline is ~220ms directional/180ms fade. Search can use the Material expanded-state transition; scope swaps should feel immediate with short selected-state changes. Keep list content stable while actions expand; do not stagger hundreds of list entries or combine slide+scale+fade for every UI chrome event. Prefer drawing-phase updates for repeated animation frames; preserve interruption/Back/focus and test whether remeasure is a real cost before optimizing.
- **Runtime and icons:** protect guest Java ME Canvas/GL rendering and controls from host UI visual effects; no backdrop blur over running MIDlets. Do not recolor actual MIDlet icons with global accent tints or force square art into a uniform crop.
- **Failure/empty states:** first-launch empty Library should clearly show **Install from file/folder** and **Import bundle** with no false implied MIDlet catalog. A collection with no members offers **Add apps**, an empty search distinguishes *no query* from *no matches*, and workdir indexing/permissions/error remain explicitly visible. Avoid decorative skeletons that misstate background index progress.

### Realistic engineering and acceptance boundaries

- Existing owners: \`LibraryNavigationState.Saver\`, \`LibraryViewportState\`, \`LibraryCollectionsUiStore\`, \`LibraryViewModel\` (Room-backed global filter, sort, quick view), Compose bridge \`LibraryScreen\`, Android \`SettingsActivity\` and file/installer \`DialogFragment\`/Activity Results. Saved tab values \`Apps/Collections/More\` require a safe **name-based restore mapping** to single-root Library and a valid collection route; do not reinterpret ordinals silently. Deleting the pager changes the current retained-page startup/callback trade-off; profile rather than assume performance wins.
- Preserve workdir-specific collection IDs; no duplicated database, hidden cross-workdir state or mismatched search/list projections. Preserve collection membership editor state, selection, keyboard/controller navigation and all current host actions. No global renderer or Java ME API changes.
- **Small-scope prototype gates:** (1) 5–10 mixed MIDlets (game, utility, browser with different artwork/metadata) and a 200–500-item fixture, (2) no/one/several collections, (3) List/Grid, 1:1/3:4, dark/light accent, Indonesian/English and 200% font, (4) compact landscape and expanded window, (5) Back/IME/rotation, workdir/collection isolation, modal/relaunch/selection, (6) startup, warm scroll, CPU/frame pacing vs current alpha on representative device. Test the flow and screenshot at meaningful boundaries, not a huge cross-product.
- Keep the **first draft narrow**: responsive shell + Search or scope-selector prototype, then implement in deliberate slices with focused tests and independent review. Do not merge PR #251 simply to force its separate item action design into the new root.
- **Research basis:** Android official Compose Material3 1.4 Search APIs and search-view window behavior; adaptive window-size classes (including compact height below 480dp); Compose performance/animation phase guidance; minimum accessible 48dp touch targets. These are general platform references, not evidence of performance or UX success on JL-Mod Plus; the repository source and CI/device tests decide adoption.

## Historical Library interaction baseline (before single-page redesign)

This section describes the older three-tab baseline and PR #251 experiment, not the active proposed destination hierarchy. The up-to-date single-page research appears above. Preserve pre-existing contracts until the redesign is implemented and validated; the historical layout itself is not normative.

The Library remains **MIDlet-first**, not a dashboard of emulator settings:

1. **Apps** remains the initial destination. Primary tap on an unselected MIDlet launches it. Long press continues to show its actions. Search, sort, quick views (All, Favorites, Recently added, Recently played), list/grid choice and install remain discoverable without navigating elsewhere.
2. **MIDlet secondary action** should be visible where the item has room. The List prototype in PR #251 adds a 48dp overflow affordance beside Favorite, reserving trailing width during selection to avoid row reflow. A Grid corner-overlay variant was **rejected**: with the existing 88dp minimum cell width, a 48dp hit region occupies over half the tile horizontally, risking unintended menu activation and obscuring JAR art. Dense Grid retains tap-to-play and long-press actions for now; future discoverability options must preserve both.
3. **Selection mode** takes precedence over launching, Favorite and overflow. The whole item selects/toggles; selection indicators and toolbar remain legible and accessible. Do not create duplicate focus targets or conflicting click regions.
4. **MIDlet action menu** retains every existing supported operation: selection, metadata/rename, shortcut, add/remove collection, per-MIDlet settings, reinstall, share, bundle export, delete. Put the most common non-destructive actions first and destructive delete last. A grouped presentation is optional if it improves scanning *without* breaking controller index/navigation.
5. **Collections** is currently a primary tab, but its placement is **under reconsideration**, not a frozen design requirement. See the integrated-Library/Search exploration below. Preserve its selection, management, membership editing, workdir identity, and list/grid restoration regardless of entry point. **More** continues to host global actions.
6. **Install** preserves existing single-file/folder flows, source menu semantics, and the distinction between installer review and batch installation. Avoid adding a second install route solely for visual symmetry.

### Library visual behavior

- Strong hierarchy: Library title / search / quick filters / content; MIDlet artwork and title remain dominant. Default List vs Grid and description visibility should be decided from actual small-icon samples and real task trials, *not* from aesthetic preference alone. Preserve user preferences.
- Keep row density appropriate to narrow widths. Do not crowd title, Favorite and overflow into unusable touch targets; size actions for accessibility. Do not sacrifice MIDlet names to display redundant metadata.
- Honor every existing chrome-scroll constraint, search/IME Back ordering, pager swipe and direct tab transition, workdir-scoped viewport identity, safe insets, keyboard/controller focus and metadata editor restoration.
- Avoid expensive full-screen blur, unnecessary shadow layers and image transformations in scrolling items. Keep existing bounded icon preparation until measurement justifies changes.

### Representative Library tasks to validate

| Task | Expected result |
| --- | --- |
| Open a MIDlet | One primary tap from the visible item; controller Activate remains supported. |
| Open per-MIDlet Config | Visible secondary action or long press > Settings; Back returns to the same Library viewport. |
| Favorite a MIDlet | One direct action in List where supported; Grid access remains discoverable; selection mode cannot accidentally favorite. |
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
- Per-MIDlet editing and profile editing share presentation only where semantics match; profile draft saving/discard must remain explicit and correct.

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
| Launch a configured MIDlet | Start action and configuration persistence behave as before. |

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

The current UI is already dark/light themed, adaptive in many places, and reasonably consistent. Its main weakness is **low visual differentiation between important actions and secondary explanatory data**, not a complete absence of Material components. The recorded Library displays relatively small title/vendor text and frequent descriptions in compact rows; the Config recording shows long blocks of setting title, help, and value stacked together. Five compact Config destinations and small MIDlet artwork add pressure at phone widths. These are design hypotheses for controlled visual prototypes, not a blanket justification for larger cards, larger icons, or hiding essential settings.

The goal is an *understandable content hierarchy*: clear task/action at the top of each screen, generous touch targets but efficient vertical density, subdued metadata, prominent current values, consistent contrast, and refined surfaces. Do not add multiple nested card backgrounds or glass layers just to increase the number of visual effects.

### First interaction prototype findings (PR #251)

The first stable-Material-3 prototype deliberately tests **visible actions in List** and a **labeled Install** control. CI screenshot comparison showed an initial Grid overlay hiding MIDlet artwork. A smaller indicator cannot repair the underlying 48dp clickable-region conflict on dense tiles, so this approach was removed rather than adding arbitrary focus/touch exceptions. The Install label is now displayed only when window width, height and font scale provide sufficient room; otherwise the icon FAB retains its accessible label.

These are design observations, not device performance qualification. The List layout still requires review at 200% text, navigation and selection transitions, and realistic long/multilingual titles. No global Compose component swaps are justified by these screenshots alone.

### Component-level decisions

| Current component / code | Why reconsider it | Proposed direction | Novice impact | Advanced-user impact | Risk and gate |
| --- | --- | --- | --- | --- | --- |
| `FloatingActionButton` + `DropdownMenu` in Library | The icon-only plus requires users to infer installation; menu already correctly distinguishes file and folder | Prototype a labeled/extended Install FAB at rest; retain existing two source actions, responsive collapse only if needed | More recognizable entry to install | Existing file/folder access and minimum steps retained | Ensure no collisions with nav chrome, IME, scrolling; test available width and header visibility before changing animation |
| `LibraryListItem` custom row + Favorite | Existing Favorite occupies a 48dp trailing slot; actions require long press | Prototype visible MIDlet-actions affordance while preserving Favorite in List; allow adaptive trailing layout instead of cramming both controls at every width | Settings and metadata become discoverable | Direct Favorite and long-press remain; no more taps for common tasks | Two 48dp controls may squeeze title, especially at 320dp; test real title widths and separate clickable/focus semantics |
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
| Runtime overlay over MIDlet | Minimal host-only transitions, no blur or repeated opacity/layer work over guest frames unless proven safe | Prioritize MIDlet frames/input and overlay transparency contract |

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
| List/Grid secondary action | Finds per-MIDlet settings without instruction or long-press knowledge | Launch remains one tap; direct Favorite and controller flow still work | Consumes too much title space, overlaps artwork/selection, increases accidental launches |
| Labeled Install affordance | Understands how to add a file or folder | File and folder tasks remain no harder | Obscures MIDlets or interferes with list/nav chrome |
| Compact preference row | Can identify setting, current value and effect | Dense but precise; long values and advanced dialogs accessible | Essential help lost; 200% text or translated values clipped |
| Config navigation A vs B | Common orientation/screen-size and control tasks are easy to discover | Shader/profile/system paths require no gratuitous extra steps | Duplicate state, broken Back or significant extra expert navigation |
| Revised motion | State changes are clear; actions seem immediate | Fast repeat actions and controller interrupts remain smooth | Observable latency increase, lost focus, stale viewport, jank spike or gratuitous motion |

Stop experimenting when one design meets user tasks and technical gates sufficiently; do not force multiple component swaps or chase unmeasured cosmetic micro-optimizations.

## Information-architecture exploration: Collections inside Library, Search in the middle

**Status: design hypothesis, not an approved navigation change.** The maintainer seldom opens the current Collections destination with a small installed library. That is legitimate feedback but does not establish that existing users with hundreds of MIDlets or curated collections behave the same way. Do not infer adoption or conversion percentages without evidence. Keep PR #251 focused on its existing List action/FAB changes; do not merge a navigation rewrite into it.

### Problem and candidate models

Today's primary destinations are \`Apps | Collections | More\`, with a prominent search field inside Apps and separate quick views (All, Favorites, Recently added, Recently played). A distinct Collections landing page requires a root navigation step even when the user only wants to view one collection. The large search field consumes vertical space at the top of Apps despite being a temporary task for some users.

| Candidate | Primary navigation | Collections entry | Search entry | Trade-off |
| --- | --- | --- | --- | --- |
| **A — three meaningful destinations (prototype first)** | **Library / Search / More** | A single collection selector inside Library; selected collection is a contextual Library view | Search **destination** with its own field/results, opening the keyboard when explicitly tapped | Preserves a familiar three-item bar, one-tap search, and cleaner main Library; search needs its own viewport and an explicit scope contract |
| **B — Library-centric actions** | Library with global More access, not necessarily a two-item M3 NavigationBar | Same collection selector | Search icon/action in Library top bar opens focused search mode or a search view | Less global navigation, no dedicated Search screen; may hide search and require rethinking the entire root chrome rather than simply using two bottom-nav destinations |
| Existing reference | Apps / Collections / More | Full Collections destination | Persistent Apps search field | Existing state/gesture behavior is known; low-frequency Collections occupies a permanent slot and search consumes header space |

**Provisional direction:** build a *bounded interaction prototype of A* alongside a cheap visual/task comparison with B, not a full database/navigation implementation of both. A is promising **only if Search is a real place for results and navigation**, not a tab item that behaves as a keyboard-only button. Material's root navigation is for durable destinations; tapping a Search icon in a nav bar should select a Search destination. Opening the keyboard is a contextual side effect of **explicit touch activation**, never the purpose of a phantom destination.

### Collections as a contextual Library view

- Keep the existing **All, Favorites, Recently added/played** quick-view semantics. Expose **Collections** as a **single stable selector**, not one horizontal chip for each user-created collection. Examples: a compact "Collections ▾" chip after the quick views, or a library-scope control "All apps ▾" that opens an organized selection list. Select the less crowded layout in narrow/200% text prototypes.
- Selecting a collection changes the visible MIDlet set under the **Library** destination and provides an unmistakable scope label (e.g. collection name with a clear return to All), without permanently inserting its MIDlets into the unfiltered Apps list. The current list/grid appearance and item actions should continue to apply.
- Keep **create, rename, delete and manage members** reachable through the selector's **Manage collections** action, the app actions (Add to collection), and/or More if it genuinely helps discoverability. No per-collection permanent bottom-nav entries; do not make a full management screen the default experience.
- For users with **zero collections**, do not waste a large empty tab or row. But do not make creating the first collection undiscoverable either; leave a small discoverable entry in the Library selector or More. **If no collection exists**, Library still opens directly to All apps with no mandatory empty-state interception.
- Preserve host-owned collection persistence, the \`LibraryCollectionsHost\` and \`LibraryCollectionsUiStore\`, the \`ListDetailPaneScaffold\` behavior on larger windows where useful, the collection membership editor and the \`selectedCollectionId\` + \`selectedCollectionScope\` identity contract. A new location in the UI is not a mandate to flatten this model into one giant list or to duplicate projection logic.

### What a Search destination must do

- **Explicitly tapping Search:** navigate to the Search screen, focus its search field and request the software keyboard *after the field is composed and focusable*. The field is visible and search results are accessible; input actions are not delayed behind an animation.
- **Swiping across/to Search, controller NextTab, Activity/process restoration, returning from external activities:** **do not automatically open the soft keyboard**. Focus can remain on navigation or a suitable non-text target until the user intentionally types/taps. This prevents surprise IME appearances when swiping from Library to More through the middle page.
- **Pressing Android Back with the IME shown:** close the keyboard/focus first while preserving the query and results; a later Back returns to Library according to the selected navigation contract. Tab changes should hide IME and preserve search text only within its explicit state lifetime. Avoid duplicated system-Back interception.
- **Search scope:** default to **all installed apps**, with scope visible to the user; optionally allow a selected collection scope using the existing collection identifier. Avoid silently searching only the last viewed collection.
- **Library state isolation:** unfiltered Library must remain unfiltered after leaving Search. Keep independent query and viewport states by workdir, but reuse underlying Room/repository data and common search/projection algorithms; never copy the entire Library data or create another persistent database. The current \`LibraryViewModel\` owns one global \`filter: MutableStateFlow<String>\`; a real Search destination must explicitly resolve this ownership before code changes. Do not assume wrapping the existing Apps text field in another pager page is safe.
- **Search result actions:** tap launches; per-MIDlet action menu and Favorite have the same accessible semantics as the main Library. Maintain result scroll and search query through modal actions where relevant. Empty query may show a calm prompt and/or recent search shortcuts, but do not invent histories, recommendations, or telemetry solely to fill the page.
- **Keyboard and screen height:** avoid bottom nav/IME overlap, clipped search results, and jumping chrome. Preserve controller/hardware keyboard navigation; IME should not steal focus on non-touch navigation. Respect existing Android 6 support and adaptive rail/landscape behavior.

### Existing architecture that this proposal affects

- \`LibraryDestination\` and saved \`LibraryDestinationKey\` currently expose \`Apps, Collections, More\` and initialize the \`HorizontalPager\` from a saved ordinal. Introducing Search changes the destination identity; migrate **saved names**, not just ordinal positions, so an old \`Collections\` saved route resolves to Library with its collection context, not an unintended Search selection.
- \`LibraryNavigationState\` already owns \`selectedCollectionId\`, workdir scope and \`collectionManageApps\`; keep this single owner. The current root logic also keys selection actions and controller Back/NextTab to the old destination, so update these consciously.
- \`LibraryAppsDestination\` owns the current search field/focus and \`LibraryViewportState\` keeps a query and scroll state per workdir/collection. Moved search should not leak IME or filter into Library's base viewport and should preserve collection result anchors on return.
- \`LibraryCollectionsDestination\` currently has list/detail adaptive presentation and membership editor logic. Prefer rerouting/reusing these screens from a contextual Library entry rather than copying their components and callback paths.
- \`LibraryViewModel.setFilter\` drives the single global Room-backed projection. Separate Browse and Search presentation intent **without** creating two competing persistent state owners or redundant sorts; determine the minimal projection API after reviewing actual consumers.
- The UI contract currently specifies Apps/Collections/More, quick filters only in Apps and search/chrome restoration. Those observable contracts must be **intentionally updated together with the approved design**, rather than silently broken by the prototype.

### How to decide without product analytics

Use the current screenshots plus **small, task-based feedback**, not arbitrary popularity claims. Compare A versus B with representative fixtures of ~5–10 MIDlets and ~200–500 MIDlets with several user collections. Ask a small number of people who have not worked on JL-Mod Plus to perform four tasks without coaching: (1) launch a MIDlet, (2) find a MIDlet by name, (3) create/use a collection, (4) adjust MIDlet Settings. Include one frequent controller/power user. Record where they look/tap, wrong turns, and task steps. Feedback is qualitative and does not represent usage prevalence.

Reject A if it simply replaces one underused destination with another without reducing confusion, requires extra taps for frequent actions, causes surprise IME on swipe/restoration, compromises library query/scroll isolation, or burdens startup/performance. Favor it if dedicated Search and contextual Collections demonstrably improve flow for both small and large libraries. Do **not** add analytics collection or remote telemetry solely to settle a local redesign decision.

### List item coherence: Favorite, overflow and description

The PR #251 screenshot shows Favorite and More as two equally visible trailing controls, while description is an independent text block under the title/vendor row. This is a genuine hierarchy/density question, not just familiarity.

Compare **two bounded designs** instead of immediately removing the direct Favorite action:
- **Direct action model:** keep Favorite and More at 48dp touch sizes, use weaker visual weight for the overflow, clearly align description under the title, cap preview lines and preserve the optional expanded-description setting. Avoid reducing the title to a narrow strip; keep button geometry stable while loading/favoriting.
- **Status + menu model:** show favorite state as a small non-interactive marker next to the title, place Favorite toggle in the contextual action menu, and keep only one clear overflow control on the row. This improves title space but adds a step for frequent Favorite changes; reject if power-user task costs rise unacceptably. If needed, consider a single visible direct Favorite shortcut in a dedicated Favorites quick-view rather than a global settings toggle.

Do not add horizontal swipe-to-reveal actions to a root \`HorizontalPager\` without proving gesture competition is harmless. Keep descriptions tied to the MIDlet metadata and the existing expand/collapse accessibility behavior. The user preference to show/hide descriptions must continue to work. Measure the result at 320–360dp, 200% font, long titles, Indonesian/English strings and actual mixed-size MIDlet icons.

## Implementation sequence and refactoring rules

1. **Foundation**: record the minSdk-23 failure in the dedicated RC experiment (PR #249). Continue with the stable Material 3 foundation and app-owned expressive-inspired tokens; re-qualify 1.5 only when its transitive Android 6 support is proven. Inspect compilation, lint, screenshots and representative devices. Separately qualify theme tokens on a contained preview before widespread restyling.
2. **Library vertical slice**: Apps list/grid item, visible actions, MIDlet actions and return-to-Library path. Make directly affected composables easier to read and test. Keep existing projection, icon cache and viewport owners unless evidence requires otherwise.
3. **Library consistency and navigation prototype**: compare integrated Collections + a real Search destination versus contextual Search on Library; settle Search focus/IME/Back, saved-state migration and result scope before editing the root pager. Address empty/loading/error states and responsive chrome afterwards.
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
2. **List or Grid initial presentation:** compare MIDlet recognizability, artwork fidelity, density and speed on representative MIDlet libraries. Existing users keep their chosen layout.
3. **Config A vs B:** compare completion steps and confusion for a novice adjusting resolution and an expert changing shaders, profiles and controls. Prefer A unless B demonstrates meaningful advantage.
4. **Motion and typography:** select the smallest set of Expressive tokens that genuinely clarifies hierarchy and transitions on both low-end and modern Android.
5. **Collections/Search placement:** compare three-destination search against a Library action/search view with small and large libraries, including IME-on-tap vs swipe, selection, saved state and workdir identity.

Do not resolve these choices with speculative implementation. The first Library slice and focused Config prototype supply the evidence.
