# Runtime menu Compose migration contract

The Android runtime toolbar and options menu are app-owned presentation and
may use Compose Material 3. They are not the Java ME LCDUI command system.
This boundary lets the menu be modernized without moving renderer, input, or
MIDP lifecycle behavior into Compose.

## Specification review

The migration was checked against the local `J2ME_Docs` checkout requested by
the project maintainer, especially these MIDP 2.0 pages:

- `javax/microedition/lcdui/Canvas.html`: Canvas key, pointer, paint, show, and
  hide callbacks are serialized by the implementation; command availability
  is device-specific.
- `javax/microedition/lcdui/Display.html`: there is one current `Displayable`,
  and `setCurrent()` controls the MIDP screen transition.
- `javax/microedition/lcdui/Displayable.html`: title, commands, and
  `CommandListener` belong to the `Displayable` contract.
- `javax/microedition/lcdui/Command.html`: a `Command` carries semantic
  information while the implementation decides its device presentation.

Consequently, Compose owns only the Android host menu. It must not invoke a
MIDP `CommandListener`, synthesize Java ME key events, alter `ViewHandler`
ordering, or replace `Displayable` views.

## Preserved action contract

| Presentation action | Visibility | Existing runtime callback retained |
| --- | --- | --- |
| Exit | Every Displayable | `showExitConfirmation()` |
| Save Log | Every Displayable | `saveLog()` |
| Lock Screen Rotation | Every Displayable | Existing lock/unlock orientation calculation |
| Keyboard (IME) | Canvas when Android IME exists | Existing `InputMethodManager` toggle, posted after popup dismissal, using the Canvas/GLSurfaceView window token |
| Take Screenshot | Canvas | `takeScreenshot()` and existing asynchronous result handling |
| Limit FPS | Canvas | Compose Material 3 digits-only dialog and existing `Canvas.setLimitFps()` values (`0` unlimited, `-1` reset) |
| Virtual Keyboard options | Canvas when a virtual keyboard exists | Existing layout edit/resize/finish/switch/hide methods |

The finish-layout item is visible only while the virtual keyboard is in an
edit mode. The Canvas-only group remains absent on `Form` and other
non-Canvas Displayables.

## Geometry and lifecycle safeguards

- The former `activity_micro.xml` hierarchy is constructed by
  `RuntimeHostView`; `displayable_container` and `OverlayView` remain direct
  View boundaries without an `AndroidView` measurement wrapper.
- The Compose toolbar uses the prior AppCompat action-bar height. A Canvas
  with the toolbar preference disabled still receives a zero-height toolbar;
  opening its menu uses a modal Material 3 panel and does not resize the
  Canvas.
- The established compact Canvas-toolbar height is retained when the toolbar
  preference is enabled, avoiding a silent change to `Canvas.getHeight()`.
- Android Back, the toolbar overflow, and the legacy physical/menu-key paths
  all open the same modal host menu. Dialog Back dismisses that menu and
  returns focus to the MIDlet; it never exits the MIDlet. A long press from a
  legacy hardware key follows the same safe menu path and is not an exit
  shortcut.
- The explicit Exit item remains the only host-menu exit path and continues to
  use `showExitConfirmation()`. A MIDlet-owned Exit command and system-level
  task removal/force-stop remain independent termination paths.
- A `Displayable` transition closes the menu before replacing its View, then
  refreshes the host title and action visibility.

## Validation gates

- Compile both Kotlin and Java for `emulatorDebug`.
- Run unit, lint, Compose screenshot, and Android-test compilation tasks.
- Keep Compose UI tests for Canvas versus non-Canvas action visibility,
  virtual-keyboard submenu state, and dismiss-before-callback ordering.
- Keep screenshot baselines for phone toolbar/overflow and dark landscape
  fullscreen menu.
- On a device or emulator, smoke-test Canvas/GL rendering size, Back and menu
  keys, rotation lock/unlock, IME, screenshot, FPS, virtual-keyboard editing,
  a non-Canvas Form, and transitions between them.

## Screen soft-key boundary

`ScreenSoftBar` now hosts a Material 3 Compose bar, but it still receives the
the command set from the LCDUI implementation. The placement policy follows
the MIDP contract in the local `J2ME_Docs` checkout: `Command` type and lower
priority values guide placement, while labels remain presentation data.

- the first `OK` command is preferred for the middle soft key;
- the first `BACK` or `EXIT` command is preferred for the right soft key;
- remaining commands stay in their existing compatibility order and become a
  left-side menu when more than one competes for that slot;
- a single remaining command is shown directly on the left.

This matches the MIDP example where `BACK` is right and other commands are
available from an options menu, while retaining the vendor three-button
variant when an `OK` command is present. Selecting either a direct or overflow
action still calls `Displayable.fireCommandAction()`, which posts the existing
`CommandActionEvent`; Compose never calls a MIDlet listener directly.

The native Canvas soft bar remains a separate `OverlayView` layer. Its popup
continues to use the same command objects and event path, but command updates
close an open popup and rebuild its adapter from a snapshot to avoid stale or
duplicated entries.

The reviewed contracts are:

- `D:\Personal\J2ME_Docs\docs\midp-2.0\javax\microedition\lcdui\Command.html`;
- `D:\Personal\J2ME_Docs\docs\midp-2.0\javax\microedition\lcdui\Displayable.html`;
- `D:\Personal\J2ME_Docs\docs\midp-2.0\javax\microedition\lcdui\Canvas.html`.
