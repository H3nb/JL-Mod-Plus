# Memory Editor UI

The editor is a sustained inspection workspace hosted in `MemoryEditorActivity`.
Search, Watch and Inspector share one surface; short search/edit operations use
`AdaptiveAlertDialog`. A bottom sheet would reduce the data viewport in short
landscape windows without simplifying these workflows.

## Evidence and scope

The October 2026 UI reassessment prioritizes these findings:

| Finding | Evidence | Decision |
| --- | --- | --- |
| Inspector could leave live refresh paused after returning to its origin tab | Compose owned a separate tab while the controller retained the snapshot; selecting the existing Search/Watch tab returned early | Derive the visible tab from controller state and clear loaded/pending Inspector before the same-tab early return |
| Inspector scrolling depended on an assumed 55 dp row height | `userScrollEnabled` was disabled by a row-count estimate, independent of actual text/layout height | Always permit scrolling and center the initial anchor using measured list geometry |
| Large text could outgrow numeric fields and keys | Read-only fields had fixed heights and custom cursor measurement; keypad keys could shrink below 48 dp | Use an editable Compose field with the platform IME intercepted, intrinsic field height and keys at least 48 dp tall |
| Landscape and portrait used different content composition branches | Width/orientation changes could recreate local forms; the landscape rail occupied 176 dp | Keep the workspace content at one composition location and adapt only its header |
| Toolbar and metadata consumed scarce space | Eight actions could wrap; status text competed with location text | Show contextual primary actions, put secondary actions in More, and allow metadata/status to flow onto separate lines |

Opaque themed surfaces, quieter typography and fewer simultaneous icons are
presentation choices, not measured performance improvements. This work does not
change global application typography, Java ME font rendering, engine operations,
freeze timing or polling cadence. It makes no frame-time or allocation claim.

## Layout and interaction contracts

- Use available safe window bounds, not physical display orientation. The workspace
  has an 8 dp horizontal margin, a 6 dp vertical margin and a maximum width of 960 dp.
- Show title and tabs together only when their width budget fits the font scale.
  Otherwise place horizontally scrollable text tabs below the title. Do not shrink
  text to force all tabs onto a line.
- Keep Search and Watch list states in the workspace. Returning from Inspector or
  changing window width should not reset their positions. Back from Inspector
  returns to its origin tab; the close action still closes the editor.
- Search/Edit are primary contextual actions. Search variants, refresh, undo and
  removal remain available in menus. Inspector still requires one selected candidate.
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

## Regression coverage

`MemoryEditorAdaptiveComposeTest` covers gesture scrolling with large text, keypad
editing/validation and action reachability in a short window, and leaving both
loaded and pending Inspector through the real controller. It also checks Back and
retaining an open form across window-width changes. The old arithmetic-only
centering test was removed with the heuristic it asserted. Existing input-model,
engine and peek-window tests remain relevant.

Preview references cover portrait, short/wide landscape, light/dark surfaces,
selection, empty results, unknown-search baseline, status feedback, Inspector and
200% text. Review actual renders before accepting reference changes; screenshot
comparison alone cannot prove gestures, IME suppression or asynchronous navigation.

## Separate follow-up work

Audit other app-owned menus/dialogs against their own workflows before extending
these changes. Profile runtime UI work before proposing performance optimizations.
Validate long translations, TalkBack traversal and physical-device rotation/IME
behavior independently of host-rendered previews. Keep MIDlet font settings a
separate compatibility concern.

Reference: Android's [InterceptPlatformTextInput API](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/InterceptPlatformTextInput.composable)
and the repository's [app UI guidance](app-ui-development.md),
[dialog guidance](ui-copy-style.md) and [candidate identity contract](memory-editor-identity.md).
