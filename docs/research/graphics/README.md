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

## Evidence log

Initial commit: isolated two-pass FBO probe authored. Execution is pending
until CI reports have been inspected. Production renderer unchanged.

## CI separation (2026-10-30)

The original combined `graphics-rd.yml` workflow was retired after two runs stalled in native M3G and its post-failure ADB diagnostics. The research continues with `graphics-rd-fast.yml` for host-independent GLES API/ABI checks, and `graphics-rd-native-isolated.yml` for one potentially crashing M3G test per fresh emulator. These are **temporary** branch-only CI fixtures, not release gates. The native matrix's results must be inspected before deciding whether its failure is a product defect or emulator-specific.
