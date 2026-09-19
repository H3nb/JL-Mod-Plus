# Runtime UI and compatibility boundaries

The Android runtime toolbar and options menu are app-owned presentation and
use Compose Material 3. They are separate from the Java ME LCDUI command system.
Renderer, input, and MIDP lifecycle behavior remain in the runtime implementation.

## Contract references

For compatibility work, consult [J2ME_Docs](https://github.com/shinovon/J2ME_Docs)
under `docs/midp-2.0/`, especially these pages:

- `javax/microedition/lcdui/Canvas.html`: Canvas key, pointer, paint, show, and
  hide callbacks are serialized by the implementation; command availability
  is device-specific.
- `javax/microedition/lcdui/Display.html`: there is one current `Displayable`,
  and `setCurrent()` controls the MIDP screen transition.
- `javax/microedition/lcdui/Displayable.html`: title, commands, and
  `CommandListener` belong to the `Displayable` contract.
- `javax/microedition/lcdui/Command.html`: a `Command` carries semantic
  information while the implementation decides its device presentation.

The host-menu Compose code must not invoke a
MIDP `CommandListener`, synthesize Java ME key events, alter `ViewHandler`
ordering, or replace `Displayable` views.

## Preserved action contract

| Presentation action | Visibility | Existing runtime callback retained |
| --- | --- | --- |
| Exit | Every Displayable | `showExitConfirmation()` |
| Save Log | Every Displayable | `saveLog()` |
| Lock Screen Rotation | Every Displayable | Existing lock/unlock orientation calculation |
| Keyboard (IME) | Canvas when Android IME exists | Existing toggle semantics, posted after popup dismissal, using the Canvas/GLSurfaceView window token and its explicit `InputConnection` contract |
| Take Screenshot | Canvas | `takeScreenshot()` and existing asynchronous result handling |
| Limit FPS | Canvas | Compose Material 3 digits-only dialog and existing `Canvas.setLimitFps()` values (`0` unlimited, `-1` reset) |
| Virtual Keyboard options | Canvas when a virtual keyboard exists | Existing layout edit/resize/finish/switch/hide methods |

The finish-layout item is visible only while the virtual keyboard is in an
edit mode. The Canvas-only group remains absent on `Form` and other
non-Canvas Displayables.

## Guest input boundary

Guest digital input is owned by the runtime Canvas boundary, not by Compose.
The supported guest-input sources are Android keyboard/keypad `KeyEvent`s,
gamepad digital `KeyEvent`s, gamepad HAT motion, the physical left analog
stick, legacy virtual-keyboard buttons, the grouped virtual D-pad, and the
virtual analog stick.

- Digital physical buttons resolve through `KeyMapper`. Its effective map is
  the default map plus persisted overrides, including explicit removal
  tombstones. Multiple physical inputs may map to the same guest logical key.
- Gamepad HAT and left-stick axes are not Key Mapper entries. HAT and the
  virtual D-pad remain normal 8-way J2ME game directions. Physical and virtual
  analog sticks use radial activation hysteresis followed by angle-based
  quantization. Analog Direction Mode selects 4-way, 8-way (the default), or
  Numeric output; Numeric uses the raw phone-keypad direction keys 1/2/3/4/6/7/8/9.
  No low-pass or time-based smoothing is applied.
- The virtual analog supports Fixed and Relative center modes. Relative uses the
  initial touch as the center for that gesture only, then returns the base and
  thumb to the configured layout center on release or cancellation. Center
  selection happens before the unchanged analog radial and direction processing.
- Joystick MotionEvent history is processed oldest-to-newest before the current
  sample, using the same MotionRange normalization and HAT/left-stick paths.
- HAT, left-stick, virtual D-pad, and virtual analog outputs synthesize held-key
  repeat after the initial delay, with one repeat stream per guest logical key.
  Ordinary Android keys and legacy virtual keys retain their existing repeat paths.
- All guest logical keys share first-owner/last-owner ownership in Canvas. A
  second source holding the same key does not emit another press, and releasing
  one source cannot release the key while another owner remains.
- `M` (`KeyMapper.KEY_OPTIONS_MENU`) is host-only. Any effective digital
  mapping to `M`, plus Android's dedicated `KEYCODE_MENU` path, opens the
  runtime menu before guest dispatch. START and SELECT are not reserved host
  buttons and remain ordinary mappable inputs unless the user explicitly maps
  them.
- Runtime teardown, focus loss, surface destruction, virtual cancellation, and
  physical-device removal release the corresponding guest ownership so stale
  inputs cannot remain held.
- Physical controller behavior still requires device smoke testing because
  Android key/axis reporting and `MotionRange` values vary across hardware.

## Geometry and lifecycle safeguards

- The runtime hierarchy is constructed by `RuntimeHostView`;
  `displayable_container` and `OverlayView` remain direct
  View boundaries without an `AndroidView` measurement wrapper.
- The Compose toolbar uses the AppCompat action-bar height. A Canvas
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
- `CanvasView` and `GlesView` report `onCheckIsTextEditor() == true` alongside
  their existing key-event `InputConnection`. The host requests focus on the
  actual Surface/GL view, restarts input, and chooses show/hide from current IME
  insets; it never sends text into a MIDP `TextField` or changes Canvas key
  dispatch.
- Host-only recovery, exit/settings, MIDlet selection, and virtual-keyboard
  dialogs are Compose Material 3 surfaces. The Java side still owns loader,
  orientation, persistence, cleanup, and `MidletThread` callbacks.

## Validation gates

- Use the relevant commands in [Build and validation](development.md).
- Keep Compose UI tests for Canvas versus non-Canvas action visibility,
  virtual-keyboard submenu state, and dismiss-before-callback ordering.
- Keep screenshot baselines for phone toolbar/overflow and dark landscape
  fullscreen menu.
- On a device or emulator, smoke-test Canvas/GL rendering size, Back and menu
  keys, rotation lock/unlock, IME, screenshot, FPS, virtual-keyboard editing,
  a non-Canvas Form, and transitions between them.

## Screen soft-key boundary

`ScreenSoftBar` hosts a Material 3 Compose bar and receives the command set
from the LCDUI implementation. The current `ScreenSoftBarPolicy` uses command
type and the existing `Command.compareTo()` ordering; labels remain presentation data.

- the first `BACK` or `EXIT` command occupies the right soft key;
- when that right-side command exists, the first `OK` command occupies the middle;
- without `BACK`/`EXIT`, `OK` occupies the right when there are other commands,
  or the left when it is the only command;
- remaining commands stay in their existing compatibility order and become a
  left-side menu when more than one competes for that slot;
- a single remaining command is shown directly on the left.

These are project compatibility rules, not a requirement that every MIDP device
use this layout. `ScreenSoftBarPolicyTest` covers placement and duplicate prevention.
Selecting either a direct or overflow action calls `Displayable.fireCommandAction()`, which posts the existing
`CommandActionEvent`; Compose never calls a MIDlet listener directly.

The native Canvas soft bar remains a separate `OverlayView` layer. Its popup
continues to use the same command objects and event path, but command updates
close an open popup and rebuild its adapter from a snapshot to avoid stale or
duplicated entries.

Implementation and regression coverage live under
`app/src/main/java/javax/microedition/{shell,lcdui/commands}/`,
`app/src/test/java/javax/microedition/lcdui/commands/`, and the corresponding
`androidTest` and `screenshotTest` source sets.
