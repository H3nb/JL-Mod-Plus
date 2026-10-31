# Memory Editor UI

The editor is a fullscreen inspection workspace hosted in `MemoryEditorActivity`.
Search, Watch and Inspector share a full-bleed surface; short search/edit operations use
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
| An opaque workspace obscured the value being searched in the game | The editor overlays a live runtime; the user must compare its values with results | Default the panel background to 97% opacity, preserve foreground contrast, and persist an adjustable opacity |

The default opacity, typography and icon density are presentation choices, not
measured performance improvements. This work does not
change global application typography, Java ME font rendering, engine operations,
freeze timing or polling cadence. It makes no frame-time or allocation claim.

## Layout and interaction contracts

- Draw the workspace surface edge-to-edge across the whole Activity window with
  square corners, no external margins, no width cap and no popup/fullscreen switch.
  Do not size it to the MIDlet viewport, which can be letterboxed.
- Inset interactive content, including the header, lists, pagination and Peek,
  with Compose `WindowInsets.safeContent` combined with `WindowInsets.waterfall`.
  This protects controls from status/navigation bars, display cutouts, curved display
  edges and system gesture regions without clipping the fullscreen background.
  Keep only small internal horizontal spacing. Follow the existing shared status
  bar preference rather than guessing bar dimensions.
- Keep the editor title and labeled navigation visible. Place them in one row when
  width permits; stack them otherwise. In short windows with large text, retain the
  labeled tabs and omit the redundant app title to protect the data viewport.
- The workspace remains fullscreen even before the first scan. Present two
  labeled search actions for empty results, without adding an empty floating
  dialog; keep undo/menu access after a scan returns no results.
- Frequent icon actions retain localized accessibility descriptions and long-press
  tooltips; do not shrink text to fit.
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
and its own bounded width. The form is centered in the whole safe viewport, not the shorter region above the Peek dock. Its maximum height preserves clearance around the 48 dp dock, including in short landscape windows.
Choices use this shell too: a native dropdown would intercept the footer behind it.
Choice/action/settings dialogs have a 400 dp width cap; input forms can use 720 dp
for controls beside the keypad. Use the theme's large corners for dialog surfaces.
Other application menus retain their existing presentation.

The eye is a plain 24 dp icon inside a 48 dp touch target, without a circular
background. Begin Peek on pointer down and retain the hold if that pointer drifts
outside the target; release or cancellation restores visibility. The footer stays
within the safe window in both portrait and landscape.

Holding the eye hides the workspace and every editor dialog surface, including native
dimming, without disposing the forms. Keep the native input windows visible to Android:
setting their window alpha to zero caused injected Back events to be dropped before
reaching the app. Hide Compose surfaces instead, leaving the eye visible and reachable.
Releasing restores the panels. Semantic activation
toggles Peek for accessibility, and Back restores the open form before dismissing it.
Hidden bodies have no actionable semantics; a pointer shield and key handling prevent
editing invisible content. Peek is a view-only action, not touch-through game control.
Tooltips dismiss when Peek starts. Input dialogs keep opaque surfaces for legibility.

The workspace footer uses a solid Material surface with matching foreground colors to keep pagination and controls readable over changing game pixels, including at reduced panel opacity. Dialog footer space remains transparent; the workspace dock backdrop also becomes transparent during Peek.

Panel opacity affects the workspace background only. The default is 97%, adjustable
from 0% to 100% in its footer setting. The memory-engine process owns the small
`memory_editor_ui` preference file; format percentages for the active locale and
save on slider release or dialog dismissal, not
on every movement. This does not modify game rendering or the runtime configuration.

## Regression coverage

`MemoryEditorAdaptiveComposeTest` covers gesture scrolling with large text, keypad
editing/validation and action reachability in a short window, and leaving both
loaded and pending Inspector through the real controller. It also checks Back and
retaining an open form across window-width changes. The old arithmetic-only
centering test was removed with the heuristic it asserted. Existing input-model
and engine tests remain relevant. `MemoryEditorOverlayComposeTest` exercises nested
production dialogs, hold/release visibility, fixed eye/action positions, pointer drift during a hold, undo after empty results, cursor
retention, accessible Peek/Back recovery and opacity persistence. The old isolated
window-alpha test is replaced by the nested production-dialog coverage.

Preview references cover portrait, short/wide landscape, light/dark surfaces,
selection, empty results, unknown-search baseline, status feedback, Inspector and
200% text. Colored underlays make panel/foreground transparency regressions visible;
compact large-text opacity dialogs check bounded controls and RTL layout. Review actual renders before accepting reference
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
