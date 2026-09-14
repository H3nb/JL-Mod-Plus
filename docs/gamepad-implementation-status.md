# Gamepad and virtual controls checkpoint

Working branch: `feature/extend-gamepad-support`  
Pull request: #124 → `alpha`

This checkpoint describes the current architecture after the controller-input redesign. It intentionally separates JL-Mod Plus host navigation from MIDlet guest input even when both originate from the same keyboard/gamepad.

## 1. Input ownership

Physical input enters one dispatch boundary and is routed by context:

- **HOST** — JL-Mod Plus Library/UI, runtime menu, dialogs, and other emulator-owned surfaces.
- **GUEST** — the active MIDlet `Canvas`/`Screen`.

A host modal owns input while visible; its key/motion events must not leak to the MIDlet behind it. Guest mappings do not redefine JL-Mod Plus UI navigation.

## 2. Universal digital mapping

Digital keyboard/gamepad mapping remains in the existing `KeyMapper` / `ProfileModel.keyMappings`. There is no second user-facing gamepad-button mapping table.

The user-facing logical targets are exactly:

- `0`–`9`, `*`, `#`
- `Up`, `Down`, `Left`, `Right`, `Fire`
- `Soft Left`, `Soft Right`
- `A`, `B`, `C`, `D`
- `M` — opens/closes the runtime MIDlet menu

Multiple physical inputs may map to the same logical target. Common gamepad buttons are included in the shared default guest map, while START/SELECT/L1/R1 are not reserved as emulator shortcuts and remain assignable through Key Mapping.

`M` is the only additional host action exposed through this mapping. User-added keyboard, phone-key, gamepad, and vendor-button bindings to `M` are detected through `KeyMapper.isOptionsMenuKey()`. Android BACK intentionally retains its established Activity/Back-dispatch path so existing short/long-press behavior is not changed by the controller router.

## 3. Analog direction modes

Standard MIDP has no portable analog-axis API, so directional analog support is an adapter from continuous Android axes to guest digital controls.

Supported output modes:

- **4-Way:** Up, Down, Left, Right.
- **8-Way:** Up+Left, Up, Up+Right, Left, Right, Down+Left, Down, Down+Right.
- **Numeric:** `7/2/9`, `4/6`, `1/8/3` where 2=Up, 4=Left, 6=Right, 8=Down.

`StickProcessor` applies normalization, deadzone, press/release hysteresis, angular hysteresis, calibration and response shaping before direction quantization. The capability signature used for calibration lookup is cached by `(deviceId, source)` and invalidated when the Android input device changes or is removed.

When a JL-Mod Plus HOST surface owns input, analog navigation remains host navigation and does not inherit the MIDlet's 4-Way/8-Way/Numeric output mode.

## 4. Virtual controls

`VirtualControlsKeyboard` keeps legacy numeric/soft/game buttons while replacing the old independent direction buttons with grouped movement controls:

- one grouped virtual **D-pad**;
- one grouped virtual **analog stick**.

Each grouped control has one normalized center and one normalized radius. During layout editing:

- dragging the body moves the entire control;
- dragging its resize edge/handle resizes the entire control proportionally;
- movement and radius snap to the edit grid;
- geometry is stored in the MIDlet profile so it scales with viewport/orientation changes.

The legacy keypad resize mode remains available for ordinary keypad buttons; grouped D-pad/analog resizing is not implemented as four/eight independent buttons.

Pure behavior is covered by `VirtualDpadTest`, `VirtualAnalogStickTest`, `VirtualAnalogDirectionAdapterTest`, and `StickProcessorTest`.

## 5. Compatibility hardening

The redesign preserves existing non-gamepad behavior:

- Physical-keyboard repeat continues to use Android's native repeat events.
- Legacy virtual-keypad repeat retains its previous cadence instead of inheriting controller repeat timing.
- The controller neutral gate applies to analog takeover; digital DOWN is not sacrificed merely to activate a controller after resume/focus changes.
- Unknown/vendor gamepad key codes remain available to the universal `KeyMapper` instead of being swallowed by a canonical-button whitelist.
- Digital bindings no longer live in `ControllerConfig`; that schema now owns analog/controller settings only, avoiding two competing sources of truth.
- Calibration capability signatures are cached off the motion hot path.

## 6. Automated evidence

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
- `PointerLeaseControllerTest`
- `PointerControllersTest`
- `GameCanvasKeyStateTest`
- `ControllerConfigTest`
- `ProfilesManagerAtomicSaveTest`

Android instrumentation also contains `KeyMapperMappingRulesTest`, including the explicit many-to-one `M` contract.

GitHub Actions is the authoritative final gate for the current PR head: compile Kotlin/Java and Android-test sources, run the JVM suite, lint, assemble the app/test APKs, verify native packaging, and validate screenshot references. Do not infer a green current head from an older cancelled/superseded run.

## 7. Hardware limitations

Physical controller behavior is still not claimed as fully hardware-verified because no gamepad is available in the development environment. Connected Android instrumentation and a real MIDlet/controller smoke matrix remain release evidence to collect when hardware is available.

Recommended physical smoke matrix:

1. D-pad and A/B/X/Y through Key Mapping.
2. START or another button remapped to `M`.
3. Left stick in 4-Way, 8-Way and Numeric modes.
4. Focus loss/resume and controller disconnect/reconnect while controls are held.
5. Runtime menu/modal isolation: host navigation must not fire guest actions behind the menu.
6. Grouped touch D-pad/analog move, resize, orientation change and persistence.
7. Pointer/cursor modes where configured.

Until that matrix is run, the implementation should be described as architecturally complete with automated coverage, but not universally hardware-certified across controller models.
