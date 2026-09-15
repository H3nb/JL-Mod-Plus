# Gamepad and virtual controls checkpoint

Working branch: `feature/extend-gamepad-support`  
Pull request: `H3nb/JL-Mod-Plus#124` → `alpha`

This checkpoint describes the current controller/virtual-control architecture. JL-Mod Plus host navigation and MIDlet guest input remain separate even when both originate from the same keyboard or gamepad.

## 1. Input ownership

Physical input enters one dispatch boundary and is routed by context:

- **HOST** — JL-Mod Plus Library/UI, runtime menu, dialogs, and other emulator-owned surfaces.
- **GUEST** — the active MIDlet `Canvas`/`Screen`.

A host modal owns input while visible; its key/motion events must not leak to the MIDlet behind it. Guest mappings do not redefine JL-Mod Plus UI navigation.

## 2. Universal digital mapping

Digital keyboard/gamepad mapping remains in the existing `KeyMapper` / `ProfileModel.keyMappings`. There is no second user-facing gamepad-button mapping table.

The user-facing logical targets are:

- `0`–`9`, `*`, `#`
- `Up`, `Down`, `Left`, `Right`, `Fire`
- `Soft Left`, `Soft Right`
- `A`, `B`, `C`, `D`
- `M` — opens/closes the runtime MIDlet menu

Multiple physical inputs may map to the same logical target. Common gamepad buttons are included in the shared default guest map, while START/SELECT/L1/R1 remain ordinary assignable inputs. A physical button mapped to `M` opens the runtime menu through the same KeyMapper contract. Android Back retains its established Activity/Back behavior.

## 3. Compact analog configuration

There is no dedicated Gamepad configuration section in the MIDlet settings UI. Analog controls live with the rest of the input configuration under **Controls > Key Input**.

Key Input exposes one compact **Analog Stick** choice for guest directional output:

- **Off** — the primary stick does not emit MIDlet directions.
- **4-Way** — Up/Down/Left/Right.
- **8-Way** — cardinal directions plus simultaneous diagonal pairs.
- **Numeric** — `7/2/9`, `4/6`, `1/8/3`.

The option is profile configuration, not hardware discovery, so it can be edited before a controller is connected. Digital gamepad buttons continue to use Key Mapping regardless of this setting.

**Calibrate Controller** also lives in Key Input. It becomes available when a compatible physical controller is connected, while the Analog Stick mode remains configurable without hardware attached. Calibration keeps its existing staged capture/save flow and changes only the controller calibration profile when the user explicitly saves it.

`ControllerConfig` remains an opaque-compatible persistence subtree so existing/future calibration or advanced fields can round-trip safely. Editing the compact option changes only the primary stick's guest-direction behavior instead of rebuilding unrelated controller state.

Host analog navigation is independent from the MIDlet's selected analog output mode.

## 4. Analog processing

`StickProcessor` handles normalization, radial deadzone, outer saturation, response shaping, press/release hysteresis, angular hysteresis, calibration, and direction quantization.

The capability signature used for calibration lookup is cached by `(deviceId, source)` and invalidated when the Android input device changes or is removed.

Standard MIDP has no portable analog-axis API, so analog guest support intentionally adapts continuous Android axes to MIDlet directional controls rather than inventing a non-standard Java ME axis contract.

## 5. Virtual controls

`VirtualControlsKeyboard` keeps legacy numeric/soft/game buttons while replacing independent direction buttons with two grouped movement controls:

- one grouped virtual **D-pad**;
- one grouped virtual **analog stick**.

Each grouped control has one normalized center and radius. During layout editing:

- dragging the control moves the whole control;
- pinch-resizing changes the whole control proportionally;
- movement/radius use the edit grid and normalized persistent geometry;
- geometry remains stable across viewport/orientation changes.

The analog editor reads the live normalized geometry while a drag or pinch is active. It does not rebuild the gameplay `VirtualAnalogStick` object for every motion event; the gameplay object is rebuilt after the edit is committed. This keeps the visual control tracking the gesture while avoiding unnecessary work on the touch/render hot path.

The D-pad uses press/release radial hysteresis and angular hysteresis so a thumb near the center or sector boundary does not chatter between neutral/cardinal/diagonal output.

The virtual analog stick clamps its thumb to the configured circular gate and emits normalized samples through the shared stick processing stages.

The runtime **Virtual Controls** menu intentionally exposes only three customization actions:

- **Edit Layout** (or **Finish Editing** while edit mode is active);
- **Layout Templates**;
- **Show Or Hide Controls**.

The visibility dialog includes the grouped D-pad and analog stick alongside the existing virtual buttons, so visibility is configured in one place instead of through separate D-pad/analog switches.

The existing layout templates keep their original order and behavior. **D-pad Standard** and **Analog Standard** are appended as additional templates; neither replaces the existing default. Applying either standard movement template starts from the established Numbers & Arrows geometry and then becomes a normal custom layout, preserving the legacy template/default contract.

There is no second gamepad-specific virtual-control editor.

## 6. Compatibility hardening

The redesign preserves existing non-gamepad behavior:

- physical-keyboard repeat continues to use Android native repeat events;
- legacy virtual-keypad repeat retains its established cadence;
- the controller neutral gate applies to analog takeover; digital DOWN is not sacrificed merely to activate a controller after resume/focus changes;
- unknown/vendor gamepad key codes remain available to KeyMapper instead of being swallowed by a canonical-button whitelist;
- digital bindings do not live in `ControllerConfig`;
- held outputs are released across focus, target and device ownership changes;
- controller capability signatures are cached off the motion hot path.

## 7. Automated evidence

Relevant pure/unit coverage includes:

- `ControllerLifecycleGateTest`
- `ControllerInputRouterTest`
- `KeyOwnershipLedgerTest`
- `ControllerHostOwnershipLedgerTest`
- `StickProcessorTest`
- `VirtualDpadTest`
- `VirtualAnalogStickTest`
- `VirtualAnalogDirectionAdapterTest`
- `LegacyVirtualKeypadRepeatTest`
- pointer controller/lease tests
- `GameCanvasKeyStateTest`
- `ControllerConfigTest`
- profile persistence tests

Android instrumentation includes `KeyMapperMappingRulesTest`, the runtime-menu virtual-control interaction coverage in `RuntimeMenuComposeTest`, and `GamepadInputPreferencesComposeTest` for offline analog selection, controller-gated calibration, and preservation of the legacy template order.

The obsolete standalone gamepad-section screenshot was removed after the analog controls were integrated into Key Input. Existing screenshot references are otherwise left unchanged; a new reference should only be accepted after the current integrated Controls screen has been rendered and visually reviewed.

GitHub Actions remains the authoritative final gate: compile Kotlin/Java and Android-test sources, run JVM tests, lint, assemble app/test APKs, verify native packaging, and validate screenshot references. The latest follow-up commits still require a current-head Actions run; older successful or superseded runs are not evidence for this head.

## 8. Hardware validation boundary

No physical gamepad is available in the development environment, so this PR does not claim universal hardware certification.

Recommended physical smoke matrix:

1. D-pad and A/B/X/Y through Key Mapping.
2. START or another button remapped to `M`.
3. Primary analog stick in Off, 4-Way, 8-Way and Numeric modes.
4. Focus loss/resume and controller disconnect/reconnect while controls are held.
5. Runtime menu/modal isolation: host navigation must not fire guest actions behind the menu.
6. Grouped touch D-pad/analog move, pinch-resize, orientation change and persistence.
7. Pointer modes for profiles that already contain compatible pointer configuration.
8. Key Input calibration availability with controller disconnect/reconnect.
9. D-pad Standard and Analog Standard template selection without changing the legacy default layout.

Until that matrix is run, describe the implementation as architecturally/automatically validated, not universally hardware-certified.

- The grouped virtual controls use a physical-controller-inspired visual treatment: a layered blue-ring analog well and four separated D-pad arms with a center pivot. Pressed D-pad arms light independently, so diagonal input visibly highlights both directions; profile opacity, normalized geometry, drag/pinch editing, and input hysteresis remain intact.
