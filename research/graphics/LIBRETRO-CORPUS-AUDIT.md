# Libretro GLSL corpus audit — 2026-10-31

**Status:** R&D evidence, not a claim of full GLSLP compatibility.
**Pinned corpus:** [libretro/glsl-shaders @ cba01a48](https://github.com/libretro/glsl-shaders/tree/cba01a48b0974a1232bea68d999a7c21bc4c233e), 2026-10-23.
**Reference driver:** [RetroArch shader_glsl.c](https://github.com/libretro/RetroArch/blob/master/gfx/drivers_shader/shader_glsl.c) and [video_shader_parse.c](https://github.com/libretro/RetroArch/blob/master/gfx/video_shader_parse.c), examined 2026-10-31.
**Scope:** complete Git tree enumeration, targeted actual-source/preset inspection across categories, and earlier genuine GLES2-API probes. Individual compilation, rendering and correctness of all files were **not** tested.

## Corpus shape

The pinned recursive Git tree was complete (`truncated=false`): **623 .glslp presets**, **684 .glsl source files**, **143 PNG** and **15 JPG** files. The largest preset groups include handheld (152), presets (88), CRT (78), borders (35), misc (25), NTSC (20), interpolation (19) and xBR (19). Counts are files, not unique runnable effects or an Android compatibility percentage.

Individual source files have varied copyright terms; `crt-easymode.glsl` and CRT Royale declare GPL terms, while FSR and xBR-related files may have other licenses. **Do not blindly bundle** this repository or copy shader sources into the Apache-licensed application. Loading user-provided bundles is a separate feature with path, resource and GPU safety constraints.

## Evidence-driven feature matrix

| Real pinned input | What it requires | Consequence for JL-Mod Plus |
| --- | --- | --- |
| `bilinear.glslp`, `nearest.glslp` | Single-pass stock GLSL, sampler filter | Baseline direct path, no FBO merely for one pass |
| `blurs/gauss_4tap.glslp` | Horizontal/vertical stages, source-relative sizes | Already executed on emulator; pass input is previous pass output |
| `fsr/fsr.glslp` | Two passes, viewport then source scale, explicit wrap | Pass/output dimensions differ; postprocess upscaling is not guest 3D HD |
| `xbrz/xbrz-freescale-multipass.glslp` | Distinct passes, source and viewport sizing | Do not force every pass to full Android surface size |
| `handheld/lcd-grid-v2.glslp`, `crt/crt-geom.glslp` | Preset parameter overrides plus many `#pragma parameter` declarations in GLSL source | Dynamic named parameters, ranges and uniform binding, not four legacy sliders |
| `handheld/agb001-gba-color-motionblur.glslp`, `motionblur/response-time.glslp` | Previous-frame samplers; subsequent color/upscale passes | Temporal ring/history must follow **guest frame publications**, not ambient redraw count |
| `ntsc/ntsc-256px.glslp`, `vhs/ntsc-vcr.glslp` | Mixed absolute/source dimensions, `frame_count_mod`, floating-point FBOs | Independent x/y scale, temporal frame index, explicit attachment capability |
| `crt/crt-guest-dr-venom.glslp` | 11 passes, three LUTs, mipmapping, float targets | Resource dependencies and format checks are not optional for this preset |
| `crt/crt-royale.glslp` | 12 passes, LUT masks, aliases, mixed absolute/source/viewport axes, sRGB and mipmapping | Distinct multi-input pass bindings, expensive allocations and color-space correctness |
| `reshade/bloom.glslp` | 9 passes, aliases, LUTs, float targets | Later stages can consume earlier named outputs, not only previous pass |
| `bezel/uborder/uborder-bezel-reflections.glslp` | `#reference` of a base preset; resource override without `shaders = N` | Recursive preset reference resolver with overriding values and relative declaring-file paths |
| `interpolation/prescale_2x_axis.glslp` | `feedback_pass=0`, unequal x/y scales | Explicit feedback target and pass sizing, not a blind linear pipeline |
| `borders/gameboy-player/gameboy-player.glslp` | Absolute 608×448 output, external border image | Viewport/memory budget and image assets even for one-pass presets |
| `procedural/iq-canyon/iq-canyon.glslp` | Four named external image channels, wrap modes | Named LUT/resource registry; semicolon list and per-texture policy |

These rows describe source requirements; they do not imply the shaders work with the current Android GLES backend. The current research only proved a subset of stock/bilinear/gauss stages.

## Source language and ABI are separate from preset syntax

The pinned sources include `#version 130` **desktop GLSL**, e.g. `handheld/shaders/lcd-cgwg/lcd-grid-v2.glsl`, `reshade/shaders/bloom/BloomPass0.glsl`, and `ntsc/shaders/ntsc-pass1-composite-3phase.glsl`. Submitting desktop `#version 130` unchanged to an OpenGL ES context is invalid; inserting `#define VERTEX` before a `#version` also violates GLSL directive ordering.

**Critical correction to a naïve rejection rule:** RetroArch's actual `gl_glsl_compile_shader` maps a range of desktop version directives to `#version 300 es` (and later ES versions) when capabilities allow; on earlier GLES it uses `#version 100`. It separates the version directive, stage defines, alias defines and original source. This is a compatibility strategy, **not** proof that all original desktop-language features can be translated losslessly to GLES2. Source-dependent features still require successful compilation/linking and visual validation. GLES3 requires an actually negotiated-capable context; JL-Mod Plus's `GlesView` currently requests GLES2.

The upstream GLSL backend additionally probes uniforms `MVPMatrix`, `Texture`, `InputSize`, `TextureSize`, `OutputSize`, `FinalViewportSize`, `FrameCount`, `FrameDirection`, `Orig*`, `PassN*`, `PassPrevN*`, `Prev*`, aliases and named LUTs. Some newer driver uniforms also cover frame timing, original FPS and sensors. Implement only the contracts required by supported presets; validate/decline other cases explicitly, rather than reporting general compatibility.

The current JL-Mod Plus legacy shader ABI (`a_position`, `a_texcoord0`, `sampler0`, `u_setting`) and persisted `ShaderSelection` are **not** the Libretro ABI. Keep legacy loading/serialization unchanged and add a distinct GLSL preset descriptor and compiler binding path, with one owning GLES presentation thread.

## Proposed support boundary, based on the corpus

1. **Required infrastructure:** safe relative resource resolver/import that can preserve a preset bundle; quoted and bare values, comments, semicolon lists, recursive `#reference`/override cycles and deterministic errors; named parameter extraction from shader `#pragma parameter`; capability-aware GLSL stage compiler; single/multipass planning with source/viewport/absolute scales per axis, filtering and wrap; explicit content extent independent from bitmap allocation; GL-context generation and resource invalidation.
2. **Required for advertised nontrivial presets:** original and earlier pass texture bindings including alias; named LUT images and per-resource sampler policy; strict float/sRGB and mipmap capability checks; allocation budget before any FBO; temporal `Prev*` and `feedback_pass` where declared, tied to new guest frame sequence. A preset using any absent feature must fail before gameplay rendering starts, not silently ignore it.
3. **Device- and shader-dependent:** desktop-GLSL source compatibility, GLES3-only formats/builtins, derivative/LOD extensions, large multiple render targets, complex shader parameters, high memory/shader instruction cost. Never claim the entire corpus works because simple shaders link.

Do not create a generic all-purpose render graph merely to accommodate 12-pass CRT effects. A list of ordered passes with explicit **named inputs, optional history/feedback inputs and output lifetime** is sufficient until a real preset proves otherwise. Allocate output FBOs only for passes whose results need retaining; recycle compatible temporary targets after their last consumer. Count live textures/history/LUTs in the GPU budget.

For presets with shader effects that change over time, distinguish **guest-frame-driven history** from independent host redraws. The current GLES presentation is `RENDERMODE_WHEN_DIRTY`; activating a shader must not introduce an unconditional polling/render loop.

**HD interaction:** true guest 3D/2D internal-resolution scaling occurs *before* shader processing. GLSLP `InputSize` should describe the actual source image sampled (including HD when enabled); the MIDlet's logical display size remains separate. Some pixel-art/CRT shaders intentionally expect low-res input and should not be assumed to benefit from global HD.

## Validation checkpoints and stop criteria

- Existing executed GLES/real GLSL/gauss/lifecycle and integer-HD probes are recorded in [EXPERIMENTS.md](EXPERIMENTS.md); none represents full GLSLP compatibility.
- **Executed compiler probe:** [run 41162334179](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41162334179) linked CRT EasyMode, LCD3x and FSR EASU pass 0, while unadapted desktop GLSL 130 LCD Grid v2/NTSC failed. [Run 41163071776](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41163071776) then linked **both** LCD Grid v2 and NTSC first-pass shader on a GLSL ES 3.00 SwiftShader emulator after only the pinned-source version-to-300-es and `PARAMETER_UNIFORM` adaptation. This is not pixel/runtime correctness or GLES2 support; see [EXPERIMENTS.md](EXPERIMENTS.md).
- Next discriminating integration experiment: use actual `Canvas.GLRenderer` publication, preserve legacy fallback, choose tight source/normalization by measurable cost, then run pinned presets of distinct requirements (1-pass CRT, xBR/FSR source-vs-viewport, LUT/alias, temporal history, `#reference`, and one capability-rejected CRT Royale configuration). Never run all 623 as a CI gate or claim compatibility from file discovery.
- Device testing must report actual GLES context/shading-language version and supported FBO formats; the emulator's SwiftShader outcome is not an ARM64 performance/battery result.

**Current architectural recommendation:** modernize the **presentation shader pipeline**, do not rewrite guest renderers to support Libretro. Keep internal resolution a separate resolution-aware guest target project. The next meaningful uncertainty is *actual preset semantics + guest frame ownership* rather than whether a simple GLES2 FBO can draw.
