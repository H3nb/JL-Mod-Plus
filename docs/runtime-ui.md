# Runtime UI and compatibility boundaries

The Android runtime toolbar and options menu are app-owned presentation and
use Compose Material 3. They are separate from the Java ME LCDUI command system.
Renderer, input, and MIDP lifecycle behavior remain compatibility-sensitive runtime concerns.

This document defines observable ownership, lifecycle, input, geometry, and
Java ME compatibility contracts. Class names, Android UI primitives, renderer
algorithms, and host decomposition describe the current implementation unless a
rule is explicitly identified as a compatibility requirement. They may be
replaced by a simpler design when the same contracts are preserved and the
affected boundaries are revalidated.

## Contract references

Performance diagnostics remain a passive native overlay. Metric definitions,
configuration, sampling, and lifecycle rules are documented in
[Performance overlay](performance-overlay.md).

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

App-owned host UI must not directly invoke a MIDP `CommandListener`, synthesize
guest Java ME key events, or take ownership of `Displayable` transitions. The
current implementation preserves the required event ordering through the
runtime/LCDUI boundary; a future implementation may change that mechanism
without changing the guest-visible contract.

## Preserved action contract

| Presentation action | Visibility | Contract / current implementation evidence |
| --- | --- | --- |
| Exit | Every Displayable | `showExitConfirmation()` |
| Save Log | Every Displayable | `saveLog()` |
| Lock Screen Rotation | Every Displayable | Existing lock/unlock orientation calculation |
| Fullscreen | Canvas | Global preference shared with Settings; hides only host toolbar, not MIDP commands or guest fullscreen mode |
| Keyboard (IME) | Canvas when Android IME exists | Existing toggle semantics, posted after popup dismissal, using the Canvas/GLSurfaceView window token and its explicit `InputConnection` contract |
| Take Screenshot | Canvas | `takeScreenshot()` and existing asynchronous result handling |
| Limit FPS | Canvas | Compose Material 3 digits-only dialog and existing `Canvas.setLimitFps()` values (`0` display maximum, `-1` reset) |
| Emulation Speed | Canvas with compatible timing transform | Manual session-only multiplier from 0.25x to 16x; Reset selects 1x. See [Emulation speed](emulation-speed.md). |
| Swap camera | Active camera with front/rear available, Canvas or Form | Fourth toolbar shortcut when space permits; options-menu fallback otherwise. Disabled during capture/switch; same session-only action in both locations. |
| Virtual Keyboard options | Canvas when a virtual keyboard exists | Existing layout edit/resize/finish/switch/hide methods |

The finish-layout item is visible only while the virtual keyboard is in an
edit mode. The Canvas-only group remains absent on `Form` and other
non-Canvas Displayables.

The Canvas layout editor groups **Show controls**, **Grid size**, and **Snap**
above **Done** in one automatically placed host panel. Done keeps the existing
transactional Save/Discard/Continue behavior; showing controls reuses the runtime
dialog. The visual grid stays visible during editing, with coarse (16), normal
(24, default), and fine (32) divisions of the shorter editor dimension. Snap is
on by default and can be disabled for free placement. The grid setting affects
subsequent movements, not positions already edited. These tool settings reset
for each editing session and are not serialized with the custom layout. Legacy
keys retain valid screen/key anchors even when snapping is disabled, preserving
the existing layout format and orientation-specific drafts. The host places and
hides the entire panel together during control manipulation.

## LCDUI presentation authority

`Display` owns the current-screen identity and MIDlet foreground grant. The Android host
confirms mounted screens and shown Alerts; a stale or unmounted `Displayable` cannot
report `isShown() == true`. Canvas also requires a usable presentation surface.

The Canvas host state requests presentation without calling guest code. Its immediate
hide edge stops rendering and input; its show edge becomes guest-visible after
`showNotify()` returns. Both notifications run serially on the existing LCDUI queue,
never inline under host locks. A superseded show is skipped; a delivered show retains
its hide notification. Rendering and input do not run before show notification.
For a foreground `setCurrent(Canvas)` request, the host mount and usable-surface
facts complete the earlier LCDUI screen-change transaction before later callbacks
run. The existing Canvas dispatcher finishes `showNotify()` and commits visibility
on that event position; initial repaint still uses the normal rendering path.
The transaction does not await a GPU-presented frame. Superseded, backgrounded,
detached, or failed host requests release their position without fabricating show.
Outside such a transaction, `callSerially()` remains independent of visibility;
MIDlet lifecycle stays on MidletMain so applications may wait for presentation
during `startApp()`.

## Geometry and lifecycle safeguards

- Host presentation must not silently alter guest LCD/Canvas geometry or
  overlay hit geometry. The current `RuntimeHostView` structure keeps
  `displayable_container` and `OverlayView` as direct View boundaries to
  preserve that contract.
- Disabling the toolbar contributes zero host-toolbar height. Canvas toolbar
  controls use 48dp height in portrait and 40dp in a landscape-shaped window
  at normal text scale; enlarged text restores 48dp. The compact landscape row
  deliberately trades touch height for MIDlet space, without extending hit
  regions into the guest. Icon actions occupy adjacent 40dp-wide slots with 24dp
  icons; the speed label grows when its measured text needs more space. Portrait
  controls retain their 48dp height. This compact horizontal spacing trades the
  former 48dp target width for a denser action group, without overlapping targets.
  The toolbar background occupies
  the cutout area without adding the cutout height above its content row.
  Whole action targets avoid intersecting cutout rectangles. Rounded-corner
  clearance follows the centered visible text/icon band; strict system and
  waterfall exclusions remain separate. Layout uses actual window bounds,
  insets, density and text scale rather than a device model or orientation alone.
- The runtime window draws edge-to-edge. Toolbar controls independently avoid
  cutouts, waterfall edges and reported rounded corners. Guest Canvas bounds
  follow the profile's `AllowDisplayCutout` flag (default true since schema v9).
  On devices with a reported display cutout, the user-facing "Avoid camera/notch area"
  option inverts this flag: enabling it reserves a safe guest rectangle while leaving
  the window edge-to-edge. The option is hidden if the window has no cutout, without
  modifying the stored profile field. The
  padded host area uses the Canvas background fallback color (Custom color or
  Theme color; Immersive uses the theme base), not an unrelated Activity surface.
  Restoring the runtime refreshes chrome and requests insets even when fullscreen
  is unchanged. Existing virtual-control layout, input, and persistence semantics
  remain unchanged.
  APIs without rounded-corner information retain the standard control margins.
- Fullscreen uses the inverse existing global toolbar preference, written only
  by the main-process fullscreen authority. Settings and the runtime use its
  asynchronous client and change notifications; the runtime never writes the
  main-process SharedPreferences cache. Host dialogs and transient system bars
  do not change this preference. Non-Canvas screens retain host navigation.
- Toolbar shortcuts and menu/controller actions come from the same layout
  decision. Available speed, IME and screenshot actions receive space before
  the title, which elides or disappears when necessary. IME, screenshot and
  compatible emulation speed appear in the menu
  whenever their shortcut is absent. Android Back opens the host menu;
  the toolbar has no redundant overflow button. Fullscreen remains reachable
  in the Canvas menu.
- Explicit guest LCD dimensions remain fixed. Automatic dimensions continue to
  follow the established Canvas resize/sizeChanged path when host space changes.
- Android Back and the legacy physical/menu-key paths
  all open the same modal host menu. Dialog Back dismisses that menu and
  returns focus to the MIDlet; it never exits the MIDlet. A long press from a
  legacy hardware key follows the same safe menu path and is not an exit
  shortcut.
- Runtime host menus and dialogs preserve the configured system-bar visibility.
  Presenting or dismissing a host modal is not a runtime-chrome transition;
  transient system bars remain controlled by the user's system gesture.
- The explicit Exit item remains the only host-menu exit path and continues to
  use `showExitConfirmation()`. A MIDlet-owned Exit command and system-level
  task removal/force-stop remain independent termination paths.
- Until multiple runtimes are supported, launching a different MIDlet force-stops
  the previous runtime as an intentional user stop. A main-process handoff retains
  the launch request and waits for the old process's Binder death before starting
  a fresh heap. Reopening the same live runtime retains its session and heap.
- Android Home retains the live runtime and its keep-alive service. Removing an
  emulator task from Recents or explicitly exiting the emulator completes the
  session as a user stop, removes emulator tasks, and terminates runtime, memory
  engine, and main processes. MIDlet Exit returns to Library after runtime
  termination, including when a hung `destroyApp()` requires forced cleanup.
  Normal emulator shutdown stops auxiliary processes, then runtime, then main.
  If dispatch to the main coordinator fails, emergency shutdown stops auxiliary
  processes first and keeps its caller alive until peer termination is requested;
  a runtime caller therefore stops main immediately before terminating itself.
- A `Displayable` transition closes the menu before replacing its View, then
  refreshes the host title and action visibility.
- `CanvasView` and `GlesView` report `onCheckIsTextEditor() == true` alongside
  their existing key-event `InputConnection`. The host requests focus on the
  actual Surface/GL view, restarts input, and chooses show/hide from current IME
  insets; it never sends text into a MIDP `TextField` or changes Canvas key
  dispatch.
- Host-only recovery, exit/settings, MIDlet selection, and virtual-keyboard
  dialogs are Compose Material 3 surfaces. The current Java host owns loader, orientation, persistence, cleanup, and `MidletThread` callbacks. Preserve those semantics if ownership is refactored.

## Immersive background boundary

Immersive background is host presentation only. It must not modify the guest
framebuffer, Java ME LCD geometry, input ownership, presentation-mailbox
sequence, or MIDP lifecycle. Ambient work must remain bounded and
lifecycle-aware, and the derived background must remove readable guest detail
rather than becoming a second legible game surface.

The background should remain visually related to the current guest content
around the LCD boundary without making output depend on a particular renderer
backend. Transparent guest pixels use the host-theme snapshot associated with
the sampled guest state. Once guest-derived ambience is active, a later theme
change does not retroactively reinterpret that sample; a subsequent guest sample
may naturally use the newer host theme.

Canvas and GLES should produce materially consistent ambient semantics for
equivalent guest/host geometry. Sampling stops when the relevant visibility or
surface boundary is inactive and resumes without manufacturing guest
publications. Geometry changes rebuild the host-side representation from the
current host surface and guest LCD rectangle.

The current implementation uses a reusable low-resolution linear-light source
field, edge-anchored diffusion, temporal interpolation, a small raster for the
software path, and an ambient mesh for GLES. Those mechanisms are implementation
evidence, not permanent architecture. A simpler or more efficient algorithm may
replace them when it preserves the host-only boundary, bounded cost, transparency
semantics, lifecycle behavior, backend consistency, and intended visual loss of
guest detail.

## Validation gates

File video must remain an independent presentation layer clipped to guest LCD
geometry, below host overlays, and outside Canvas key/pointer ownership. In GUI
mode it remains an LCDUI Item owned by Form, including Form scrolling and
command semantics. Presentation-surface lifetime changes must be coordinated
with decoder teardown; hide/show and LCDUI screen changes preserve playback
intent, while host/focus suspension freezes the applicable media clock. The
current implementation satisfies this with a `TextureView` producer and
worker-acknowledged Surface lifetime. See
[file-video timing and qualification](audio-runtime.md#file-video-and-presentation-clock).

- Use the relevant commands in [Build and validation](development.md).
- Keep focused automated coverage for the runtime contracts actually affected by the change, such as Canvas versus non-Canvas action visibility, virtual-keyboard state, command dispatch, or dismiss-before-callback ordering.
- Select renders and golden baselines using [Visual verification](development.md#visual-verification-and-screenshot-references); distinct runtime states do not each require a permanent reference.
- Select device/emulator smoke cases from Canvas/GL rendering size, Back and menu keys, rotation, IME, screenshot, FPS, virtual-keyboard editing, non-Canvas screens, and transitions according to the boundary changed. Broaden coverage for cross-boundary changes or stable-release qualification according to the supported contracts and platforms. An automatic alpha build does not itself require every smoke case.

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
