# Memory Editor UI

The editor is a sustained inspection workspace hosted in `MemoryEditorActivity`.
Search, Watch and Inspector share one surface; short search/edit operations use
`MemoryEditorDialog`, which reuses the shared adaptive dialog body and sizing.
A bottom sheet would reduce the data viewport in short
landscape windows without simplifying these workflows.

## Evidence and scope

The October 2026 UI reassessment prioritizes these findings:

| Finding | Evidence | Decision |
| --- | --- | --- |
| Inspector could leave live refresh paused after returning to its origin tab | Compose owned a separate tab while the controller retained the snapshot; selecting the existing Search/Watch tab returned early | Derive the visible tab from controller state and clear loaded/pending Inspector before the same-tab early return |
| Inspector scrolling depended on an assumed 55 dp row height | `userScrollEnabled` was disabled by a row-count estimate, independent of actual text/layout height | Always permit scrolling and center the initial anchor using measured list geometry |
| Large text could outgrow numeric fields and keys | Read-only fields had fixed heights and custom cursor measurement; keypad keys could shrink below 48 dp | Use an editable Compose field with the platform IME intercepted, intrinsic field height and keys at least 48 dp tall |
| Landscape and portrait used different content composition branches | Width/orientation changes could recreate local forms; the landscape rail occupied 176 dp | Keep the workspace content at one composition location and adapt only its header |
| Toolbar and metadata consumed scarce space | Eight actions could wrap; status text competed with location text | Keep a fixed row of frequent actions, duplicate them in a labeled menu, and let metadata/status flow onto separate lines |
| Peek could disappear behind a nested menu | Native dialogs and dropdowns have separate input windows; an eye inside a scrolling form is not globally reachable | Give every editor modal the same fixed footer and one shared visibility owner |
| An opaque workspace obscured the value being searched in the game | The editor overlays a live runtime; the user must compare its values with results | Default the panel background to 85% opacity, preserve foreground contrast, and persist an adjustable opacity |

The default opacity, typography and icon density are presentation choices, not
measured performance improvements. This work does not
change global application typography, Java ME font rendering, engine operations,
freeze timing or polling cadence. It makes no frame-time or allocation claim.

## Layout and interaction contracts

- Use available safe window bounds, not physical display orientation. The workspace
  has an 8 dp horizontal margin, a 6 dp vertical margin and a maximum width of 960 dp.
- Keep the three navigation icons and close action in one row. Add tab labels
  when the available width and font scale allow them. Icon actions retain localized
  accessibility descriptions and long-press tooltips; do not shrink text to fit.
- Keep Search and Watch list states in the workspace. Returning from Inspector or
  changing window width should not reset their positions. Back from Inspector
  returns to its origin tab; the close action still closes the editor.
- Known search, unknown search, edit, add to Watch and refresh have fixed positions
  and direct actions. Selection changes availability, not position. More repeats
  these actions with labels and adds Inspector, undo and removal. Inspector still
  requires one selected candidate.
- Put result counts/selection and Inspector provenance in the scrolling list, so
  growing text does not reserve the entire viewport. Keep full numeric values
  readable; source summaries may truncate, with provenance available in Inspector.
- Input dialogs use one scroll owner. In wide windows, title/controls and keypad
  sit side by side. Otherwise they stack. The title participates in body scrolling;
  confirmation/cancel remain outside it. Large fonts favor scrolling over smaller keys.
- Compose owns selection, cursor and horizontal text scrolling. The custom numeric
  keypad remains the input method; `InterceptPlatformTextInput` suppresses the Android
  IME without replacing text-editing semantics. Existing value validation still applies.
- Inspector is a snapshot. Initial positioning uses actual row size; passive changes
  must not repeatedly move the reader back to the anchor. Refresh remains explicit.

## Overlay visibility and modal ownership

The workspace owns one `MemoryEditorOverlayState`. Editor dialogs inherit it through
`LocalMemoryEditorOverlay`; their layer registration determines which window renders
the active eye. Every modal uses a full safe-window shell with the same 48 dp footer
and maximum width as the workspace. The form itself remains centered and bounded.
Choices use this shell too: a native dropdown would intercept the footer behind it.
Other application menus retain their existing presentation.

Holding the eye hides the workspace and all editor dialog windows, including native
dimming, without disposing the forms. Releasing restores them. Semantic activation
toggles Peek for accessibility, and Back restores the open form before dismissing it.
Hidden bodies have no actionable semantics; a pointer shield and key handling prevent
editing invisible content. Peek is a view-only action, not touch-through game control.
Tooltips dismiss when Peek starts. Input dialogs keep opaque surfaces for legibility.

Panel opacity affects the workspace background only. The default is 85%, adjustable
from 0% to 100% in its footer setting. The memory-engine process owns the small
`memory_editor_ui` preference file; save on slider release or dialog dismissal, not
on every movement. This does not modify game rendering or the runtime configuration.

## Regression coverage

`MemoryEditorAdaptiveComposeTest` covers gesture scrolling with large text, keypad
editing/validation and action reachability in a short window, and leaving both
loaded and pending Inspector through the real controller. It also checks Back and
retaining an open form across window-width changes. The old arithmetic-only
centering test was removed with the heuristic it asserted. Existing input-model
and engine tests remain relevant. `MemoryEditorOverlayComposeTest` exercises nested
production dialogs, hold/release visibility, fixed eye/action positions, cursor
retention, accessible Peek/Back recovery and opacity persistence. The old isolated
window-alpha test is replaced by the nested production-dialog coverage.

Preview references cover portrait, short/wide landscape, light/dark surfaces,
selection, empty results, unknown-search baseline, status feedback, Inspector and
200% text. Colored underlays make panel/foreground transparency regressions visible;
a compact large-text opacity dialog checks its bounded controls. Review actual renders before accepting reference
changes; screenshot comparison alone cannot prove gestures, IME suppression or asynchronous navigation.

## Separate follow-up work

Audit other app-owned menus/dialogs against their own workflows before extending
these changes. Profile runtime UI work before proposing performance optimizations.
Validate long translations, TalkBack traversal and physical-device rotation/IME
behavior independently of host-rendered previews. Keep MIDlet font settings a
separate compatibility concern.

The [GameGuardian help](https://docs.gameguardian.net/help.html) documents direct icons
with labeled menu equivalents and holding the eye to reveal the app. These inform
the interaction goals; JL-Mod Plus retains its own Material typography and managed
memory workflows.

Reference: Android's [InterceptPlatformTextInput API](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/InterceptPlatformTextInput.composable)
and the repository's [app UI guidance](app-ui-development.md),
[dialog guidance](ui-copy-style.md) and [candidate identity contract](memory-editor-identity.md).
