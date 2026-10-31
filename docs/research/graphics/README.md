# Graphics architecture R&D

Status: research-only. Branch: research/graphics-architecture.
Frozen starting point: alpha @ b2ebf48a1b4d10f2f0a571d9f8272378e582f14c.

## Evidence and contracts

Source of normative Java ME references: the user's private ChatGPT Library at
/J2ME Dev Kit/Documentation/J2ME_Docs.zip, extracted for targeted research.
Within that archive:

- docs/jsr184/javax/microedition/m3g/Graphics3D.html — a rendering target is
  Graphics or Image2D; origin is captured on bindTarget, and writes are clipped
  to the intersection of target clipping rectangle and viewport.
- docs/Mascot_Capsule_API/com/mascotcapsule/micro3d/v3/Graphics3D.html —
  bind(Graphics), render, flush, release(Graphics), including offscreen
  mutable Image targets, are part of the documented integration model.
- docs/midp-2.0/javax/microedition/lcdui/Graphics.html — logical drawing,
  clipping and pixel semantics constrain all changes to rasterization.

Current JL-Mod Plus source and active docs remain the implementation evidence,
not an unconditional requirement to preserve incidental architecture.

Libretro references:
- https://docs.libretro.com/development/shader/glsl-shaders/
- https://docs.libretro.com/guides/shaders/
- https://github.com/libretro/glsl-shaders/blob/master/blurs/dual_filter_2_pass.glslp

Libretro GLSL uses VERTEX/FRAGMENT compile branches and a sampler named Texture,
while the existing JL-Mod Plus legacy .ini + .vsh/.fsh program uses a_position,
a_texcoord0, sampler0, u_texelDelta, u_pixelDelta and u_setting. Future support
must retain the legacy format and its persisted ShaderSelection values. The
formats must not be treated as interchangeable without a real adapter.

## Initial experimental gates

E1: Baseline guest rendering, JNI, M3G target/clip and EGL ownership. Run
existing native/bitmap Android instrumentation separately and record outcome.

E2: Offscreen GLES2 multipass feasibility. Probe a real source texture ->
4x4 FBO -> 8x8 default framebuffer using two shader draws and RGBA readbacks.
The disposable test is *not* the production GLRenderer and does not prove
libretro GLSL syntax, parsing, UI integration or gameplay correctness.

E3: Mixed 2D/3D and HD feasibility. Compare 1x vs 2x rasterization, guest
pixel readback, interleaved 2D/HUD layers, target size, clip and viewport.
Do not select whole-frame or selective HD until these are verified.

E4: GLSLP compatibility. Test actual libretro inputs and pass scaling
(source/viewport/absolute), parameters, LUT, feedback, #reference, shader
compiler errors and GPU capability limits; preserve old .ini shaders.

E5: Profile GPU/CPU transfers on physical hardware before optimizing
glReadPixels, texImage2D or context ownership.

## CI and stopping rule

The temporary branch-scoped workflow runs on pushes only to this R&D branch,
not as a permanent production CI gate. Reports are real instrumented Android
test executions, not merely compiled tests. Record commit ID, OS/emulator,
GL vendor/renderer/version, failure logs, and limitations.

A successful offscreen ES2 probe only shows the API works in that environment.
It does not prove performance, compatibility with a production GL context,
complex GLSLP presets, or correctness for interleaved 2D/M3G/Mascot calls.

Stop investigating an approach once its correctness or major trade-off is
established; remove probes/workflow before production integration. The final
architecture decision should document accepted/rejected approaches and be
implemented through focused PRs, not by blindly merging this entire branch.

## Current evidence and decisions — 2026-10-30

- [Five independent GLES/HD/texture probes passed](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41063931268) on Android API35 x86_64 SwiftShader. This includes 4x4-to-8x8 GLES2 multipass, the shipped legacy shader versus Libretro-style ABI, a genuine GLES1 fixed-function pbuffer, 1x/2x Android pixel characterization, and byte-identical `GLUtils.texImage2D` versus `texSubImage2D` output across same-size frame updates and an explicit resize.
- Float color attachments are *format-dependent*. This emulator reports texture/color float extensions but `GL_RGBA` plus OES half-float or float made an **incomplete FBO (0x8CD6)**; the subsequent test checks sized GLES3 `RGBA16F`/`RGBA32F` separately. Do not conclude that all floating-point attachments are unsupported.
- Native M3G [still fails on the emulator](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41063347467). The apparent `m3gLockFrameBuffer` stall is **not a proven lock deadlock**: the current non-debug build compiles that assertion macro out. Earlier testing without manual JNI preload still failed. Next decisive investigation requires a physical ARM64 Android GPU/driver and a production-like host, not more identical SwiftShader runs.
- `Canvas.GLRenderer` currently uses `GLUtils.texImage2D` on each new published frame. An existing-storage `texSubImage2D` update is a promising simpler optimization; correct pixels are proven on the emulator but **FPS, CPU/GPU cost and battery improvements are unmeasured**.
- No production backend was changed, no PR was merged, and `alpha` remains outside this branch.

## R&D deliverables

- [Evidence register](../../../research/graphics/EXPERIMENTS.md) — actual run results, limits, normative sources and rejected hypotheses.
- [Architecture candidates](../../../research/graphics/ARCHITECTURE-CANDIDATES.md) — unified GPU, whole-target and selective HD, software rasterizer, presenter-only GLSLP, and the smaller upload optimization.
- [GLSLP prototype contract](../../../research/graphics/GLSLP-PROTOTYPE-CONTRACT.md) — pass sizing, framebuffer ownership, legacy format compatibility, user import behavior and failure recovery.
- Research-only workflows: `graphics-rd-fast.yml` (bounded API probes) and `graphics-rd-native-isolated.yml` (single native M3G case per emulator). The old combined native workflow was removed after unreliable hangs.

**New executed gate:** [run 41074602946](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41074602946) executed genuine pinned Libretro bilinear.glslp / stock.glsl on the Android emulator. Direct linear sampling over a cropped backing texture read inactive pixels (green=106/255); a tightly bounded intermediate FBO produced the expected sampled color. Sized GLES3 RGBA16F/RGBA32F FBOs were complete on SwiftShader, but their draw/readback was not tested. These results do not establish integrated production shader support or performance.

**Additional executed proof:** [run 41080969873](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41080969873) found full-viewport pixel equivalence between tight Android Bitmap input and GPU FBO normalization for the tested opaque source. [run 41083533898](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41083533898) executed both real gauss_4tap GLSL stages with observable horizontal filtering and different output from unfiltered stock. These isolated probes do not establish production lifecycle, performance or general GLSLP support.

**Next gates:** stop additional synthetic EGL probes. Integrate a bounded GLSLP prototype into the real GLRenderer lifecycle on the R&D branch, then verify source packing versus FBO cost on physical ARM64 and test resizing/context loss/host layers/fallback. HD still requires target-aware guest logical-pixel and mixed 2D/M3G/Mascot fixtures. Investigate native M3G on physical hardware instead of repeating inconclusive SwiftShader hangs. Remove temporary native traces/CI before production integration.


## Executed extension — 2026-10-31

- [GlesView lifecycle run 41158291554](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41158291554) passed the R&D real-surface host test: genuine Libretro multipass survived initial frame, repeat, 128x96-to-160x120 resize and forced context re-creation. An earlier test-APK Activity process mismatch failed before drawing and was repaired with a disposable target-APK Activity. This is **not** the production `Canvas.GLRenderer` or MIDlet frame publication path.
- [Integer-HD run 41158942239](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41158942239) passed both HD characterization tests: all 120 selected 12x10 logical pixels matched native 1x after 2x drawing, explicit copy source conversion and downsample; the existing fractional-edge fixture still showed 71 changed/1024 pixels. This does not qualify actual M3G/Mascot, text, guest `Image.getRGB` or a shipped HD feature.
- The branch carries a temporary `GraphicsRdHostActivity` in the target app manifest solely to make instrumentation run in the correct process. Remove the Activity/manifest entry and disposable CI/test code before preparing production PRs.

**Next discriminating gates:** an actual R&D-only `Canvas.GLRenderer` frame-integration trial, physical ARM64 cost comparison for cropped Bitmap versus FBO-normalized input, and a guest-facing HD pixel/clip/mixed-rendering oracle. Do not infer universal GLSLP or HD support from isolated green tests.
