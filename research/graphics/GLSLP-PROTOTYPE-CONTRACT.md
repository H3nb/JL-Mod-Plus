# GLSLP integration contract — R&D design checkpoint

Status: **proposal, not production implementation**.
Baseline: `alpha` + dedicated `research/graphics-architecture` branch.
Normative behavior must be verified against Libretro parser/source:
- https://docs.libretro.com/development/shader/glsl-shaders/
- https://docs.libretro.com/guides/shaders/
- https://github.com/libretro/RetroArch/blob/master/gfx/video_shader_parse.c
- https://github.com/libretro/glsl-shaders/blob/master/bilinear.glslp
- https://github.com/libretro/glsl-shaders/blob/master/blurs/dual_filter_2_pass.glslp

## User problem and minimal UX

A user wants to make a visually faithful, pixel-art-friendly, CRT-style or
smoother **presented game image** without modifying guest rendering behavior,
the logical J2ME screen size, touch/key mappings or the controls overlay.

- Keep existing shader selection and four legacy tuning values working.
- GLSLP is a different format; do not silently reinterpret an existing
  `.ini`/`.vsh`/`.fsh` shader selection as a GLSLP preset.
- Minimal prototype UX: **None**, **Legacy shader**, **GLSL preset** under
  the existing shader setting, with one active option and a preview. A failed
  user preset returns to a supported identity renderer and gives one useful,
  non-repeating explanation in the configuration surface; gameplay remains
  functional.
- For multi-file presets, importing just one `.glslp` is not enough. An
  importable bundle must preserve its relative `.glsl`, optional LUT images,
  and referenced preset paths. Consider a ZIP bundle with safe extraction
  rather than silently broken file-picker behavior. Do not bundle third-party
  presets without reviewing their individual licensing/attribution.
- Retain `screenFilter` for the original upload / legacy shader path.
  GLSLP passes can specify their own `filter_linearN`; do not globally
  overwrite pass-specific choices.
- Default stays **None** (current image output). GLSLP is opt-in.

## Source-verified existing presentation behavior

`javax/microedition/lcdui/Canvas.java`:
- `GLRenderer.onDrawFrame` uploads `offscreenCopy` through
  `GLUtils.texImage2D` when `publishedFrameSequence` changes, then draws
  a quad using the existing legacy shader.
- Surface code calls `glViewport(0,0,width,height)`, sets a
  padding-based `glScissor`, and clears/draws the optional ambient
  background before drawing the game.
- The game viewport (host coordinates) is `onX/onY/onWidth/onHeight`,
  separate from bitmap logical dimensions. Virtual controls are not a
  replacement for the game quad and must not enter FBO shader input.
- `GLRenderer` already has lifecycle semantics for context recreation and
  a published-frame mailbox. Do not add a second frame queue or share raw GL
  texture names with EGL contexts owned by M3G/Mascot.
- `ShaderSelection` currently serializes `fragment`, `vertex`, `name`,
  `author`, and `Settings` values. Retaining this identity is required
  by existing profiles.

## Proposed prototype data flow

```text
published Bitmap (unchanged guest image contract)
    |
    | upload once per new frame; preserve mailbox locking
    v
source texture — game content dimensions
    |
    +--> shader pass 0 --> FBO 0 at independently resolved size
    |                           |
    +--> shader pass 1 ... ------+
    |                           |
    +--> last pass --> **host game viewport**, on existing surface
                                    |
                   optional host ambient/skin/control composition
```

The diagram illustrates data flow, not fixed compositing order. **Actual
order:** ambient background is drawn first by the host; shader intermediates
must render offscreen without it, then the final game quad is drawn into the
correct host rectangle; other host controls/layers follow their current
ownership and ordering.

A single-pass legacy shader bypasses this new chain entirely. A one-pass
GLSLP shader should render directly into the game viewport if its contract
permits, without manufacturing an FBO.

## Pass sizing and per-pass GPU state

Pass input size is the *previous pass output*, not always the original game
resolution. For each axis independently:
- `source`: scale relative to the pass's input size.
- `viewport`: scale relative to the final **game viewport**, not entire
  Android surface (unless the game viewport fills it).
- `absolute`: explicit pixel dimension.

Support separate X/Y modes and `scale_type` override semantics specified
by the Libretro parser. Do not assume the filename's number of passes equals
`shaders = N`. Final-pass output and shader `OutputSize` must agree;
physical host screenPadding/glScissor is applied only at final composite.

For each offscreen pass:
1. Bind the intended FBO, set `glViewport` to the resolved FBO size,
   disable host padding scissor (or replace it with the correct full-pass
   scissor), and explicitly define clear/blend policy.
2. Bind input textures, sampler/filter/wrap/mipmap policies and set all ABI
   uniforms consistently: `Texture`, `InputSize`, `TextureSize`,
   `OutputSize`, `MVPMatrix`, `FrameCount` etc as required.
3. Draw; do not read back to CPU solely to feed the next pass.
4. For the last pass, restore the real surface framebuffer, viewport,
   scissor and complete the host game quad. Preserve current letterboxing,
   ambient background and virtual-control geometry.

Create/delete GL textures, FBOs and programs on the owning GL thread.
Invalidate all on context recreation. Cache appropriately sized FBOs; avoid
blindly creating/deleting them every frame. A new host resize changes
viewport-relative FBO sizes; a guest bitmap resize changes input-relative
sizes. Both require re-resolution.

**Do not accidentally make RENDERMODE_WHEN_DIRTY draw continuously** for
every preset. A temporal shader requiring `FrameCount` should follow new
guest frame publication by default, not count ambient-only redraws as new
game frames. A future time-dependent preset needs explicit frame pacing and
CPU/GPU impact analysis before enabled support.

## Shader source and package compatibility

- GLSLP shaders typically define `VERTEX` and `FRAGMENT` branches in one
  source file. Do not mix that ABI with legacy `a_position`,
  `a_texcoord0`, `sampler0`, `u_texelDelta`, `u_pixelDelta` and
  `u_setting` programs.
- Preserve any `#version` directive at the start of a shader when adding
  stage defines. Arbitrarily prepending `#define VERTEX` can cause a
  compile error for GLSL sources with explicit versions.
- A preset may include `#reference`, named `parameters`, `textures`,
  `aliasN`, `wrap_modeN`, `mipmap_inputN`, `float_framebufferN`,
  `frame_count_modN`, and pass/filter/size keys. Start with a documented
  usable subset, detect unsupported keys, and fail clearly instead of
  silently pretending to support a complex preset.
- Safe imports: canonical relative-path handling within a user-approved
  bundle, bounded file count/bytes, reject archive traversal and malformed
  references, handle cycles in `#reference`, preserve source files without
  path guessing. Treat user shader code as untrusted GPU programs: failures
  must not make the app unusable.
- GLSL source files from various Libretro preset packs may require desktop
  features unavailable to Android GLES2. A preset file being syntactically
  valid does **not** guarantee it can compile on a given phone.

## Memory and capability gate

- GLES2 framebuffer support does **not** guarantee 16/32-bit float FBO
  renderability. Probe the actual texture format and FBO completeness.
- Before allocating a chain, resolve all pass dimensions; reject invalid,
  zero, overflow, above-`GL_MAX_TEXTURE_SIZE` or capability-incompatible
  dimensions. A conservative memory estimate must include simultaneously
  live textures, original Bitmap upload, staging and driver overhead; a
  theoretically small render target is not proof of usable free GPU memory.
- If a preset fails compile, link, FBO creation or GPU capability checks,
  release partial resources, keep the app's identity renderer operational,
  and identify the unsupported pass/feature.
- Begin with a small GLS2-compatible chain; float/sRGB/feedback/LUT/history
  are separate gates, not implied by two-pass RGBA8 success.

## Evidence and stop gates

1. Existing `GraphicsPipelineResearchTest` passes 4x4 source ->
   independent FBO -> 8x8 target with verified RGBA reads.
2. `ShaderAbiResearchTest` verifies the actual shipped legacy shader and
   an independent Libretro-style combined GLSL shader on SwiftShader.
3. New float FBO probe records extensions, actual `glTexImage2D` errors,
   FBO status; supports only its probed GLES2-style internalformat/type
   combinations, not a universal GLES3 capability claim.
4. Next genuine implementation experiment should load a *real* one-pass
   preset and validate the shader compiler, file/resource resolver, and
   pixel output; then a bounded two-pass preset with actual different
   `source` and `viewport` dimensions. Reuse the existing GLRenderer
   rather than building a parallel display path.
5. Profile any actual GPU and upload optimizations on physical Android
   hardware. SwiftShader timings are not mobile power benchmarks.

Do not merge this research branch as a production feature; derive focused
implementation PRs after evidence gates pass.


## Guest content extent versus texture allocation — empirical gate

Canvas.offscreenCopy can have a backing bitmap larger than its active
logical content rectangle; the legacy GL quad crops with tw/th UVs.
A pinned genuine Libretro bilinear.glslp / stock.glsl Android probe
(run 41074602946) verified that linear filtering over a cropped
4x4 texture can sample inactive backing pixels (green=106/255 in the
red/green edge fixture). GL_CLAMP_TO_EDGE clamps to the entire allocated
texture, not an interior content rectangle.

For GLSLP, compare two genuinely viable input boundaries:
1. A source texture allocated/uploaded at exactly the logical content
   dimensions, via bounded CPU row copy or supported partial upload.
2. A GPU content-normalization FBO updated only on new guest-frame
   publication, feeding a tightly bounded input texture to GLSLP.

The second path avoided crop contamination in the small experiment but
adds a GPU pass and temporary texture; it is NOT a preselected solution.
Do not inject nonstandard sampling behavior into arbitrary GLSL source,
reinterpret TextureSize, or create another frame queue. Select based
on actual integrated-frame correctness and measured physical-device cost.
Legacy profile values and the old shader ABI remain unchanged.


## Executed bounded GLSLP proof

[Run 41080969873](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41080969873) established identical 12x8 RGBA output from tightly cropped Android Bitmap versus GPU-normalized logical source for the tested opaque fixture. [Run 41083533898](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41083533898) executed the two distinct gauss_4tap.glslp shader stages at source-relative 2x4 sizes, with a separate host viewport blit. The stronger fixture observes horizontal filtering and output that differs from unfiltered stock.

These proofs are isolated from the production GLSurfaceView lifecycle. The small Properties reader recognizes only the selected unquoted preset keys, not GLSLP generally. No claims are made for imported files, dynamic parameters, #reference, LUT, history, driver-specific formats, alpha, performance or recovery. Choosing between a CPU-cropped input and a GPU normalization pass requires integrated frame-ownership and physical-GPU evidence. Do not introduce redundant FBOs when backing and active content dimensions coincide.

## Corpus coverage audit

The pinned libretro/glsl-shaders source/preset requirements, RetroArch version mapping, and feature tiers are tracked in [LIBRETRO-CORPUS-AUDIT.md](LIBRETRO-CORPUS-AUDIT.md). It supersedes any inference that `#version 130` proves fundamental Android incompatibility or that a valid `shaders=N` preset can be executed with previous-pass-only bindings. Device compiler compatibility and GLES capability are separate gates from parsing.

**Executed compiler adapter proof:** [CI 41162334179](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41162334179) linked pinned CRT EasyMode, LCD3x and FSR pass0 as-is; raw desktop GLSL 130 LCD Grid v2/NTSC sources failed. [CI 41163071776](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41163071776) linked those two after a bounded `#version 300 es` + `PARAMETER_UNIFORM` adaptation on GLES3 SwiftShader. Use this as an ES3 source-compatibility evidence gate, not full preset or GLES2 proof; all uniform bindings, actual pixels, history and device requirements remain to be demonstrated.


## Verified game-presentation boundary (real Canvas renderer)

[CI 41166769301](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41166769301) exercised the real `Canvas.GLRenderer` with `Canvas.flushBuffer`, the existing publication mailbox, source uploads, host-only redraw, and fresh GLES context generation; exact red/blue readbacks and upload-generation checks passed. The **headless test-only SoftBar host** and controlled EGL pbuffer do not prove real `MicroActivity`/GLSurfaceView integration or GLSLP execution. The independent `GlesView` lifecycle proof remains complementary.

This evidence favors a single presentation authority (mailbox + uploaded bitmap + viewport), with pluggable None/Legacy/GLSLP program execution; do not bolt a second frame queue onto the shader path. Temporal feedback and `FrameCount` must follow actual **consumed guest frames** rather than host invalidations. Do not hold `bufferLock` during multipass draws, assume padded bitmap input is a tight Libretro texture, or leave a failed shader active and repeatedly retry the same frame.


## R&D stage gate: real two-pass execution on Canvas.GLRenderer

[CI 41168266751](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41168266751) verified actual `Canvas.GLRenderer` execution of pinned Libretro Gauss horizontal+vertical GLSL through a source-resolution FBO, including guest publication, spatial blur readback, host-only redraw reuse, invalid shader fallback, host-background isolation and fresh EGL-context rebuilding. A default-off, R&D-only source injection is used; there is still **no GLSLP parser or product feature**. Source texture must currently be tight; padded content is rejected by this experiment. The full evidence and limits are in [EXPERIMENTS.md](EXPERIMENTS.md).

This validates the **single GLES presentation owner** and **pass execution ordering**. It does not validate other Libretro preset dependencies. Next determine a correctness- and cost-aware tight source strategy, then introduce a separate resource-safe preset resolver without hardcoding a new all-purpose render graph.


## Source-domain resolution: softbar backing experiment

[CI 41170967593](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41170967593) passed a real `Canvas.GLRenderer` probe with a non-fullscreen `SoftBar`: original backing **32×48** for logical content **32×45** forced the GLSL path to fail closed; exact allocation and conversion to **32×45** both preserved expected pixels and allowed real two-pass Libretro GLSL to draw. The host redraw did not rerun the offscreen pass. The guest paint buffer was unchanged.

**Preferred candidate:** allocate **`offscreenCopy` at the current active logical extent** in a future opt-in GLSLP path and copy overlapping logical content only when dimensions change. This removes the baseline padded-source mismatch without paying for an unconditional FBO-normalization pass. This is a correctness-driven architectural preference, **not** a physical-device performance verdict. Preserve legacy bitmap and guest image behavior outside the new feature. The research static toggle and headless host must not ship; lifecycle/softbar/fullscreen transitions and screenshot behavior still need targeted verification before implementation approval.


## Live geometry checkpoint

[CI 41175037707](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41175037707) established that the real `Canvas.GLRenderer` in a controlled EGL pbuffer can keep a pinned two-pass Libretro shader active across softbar → fullscreen → logical 32×48 to 40×56 enlargement → softbar (40×52) **without recreating the EGL context**. `offscreenCopy` stayed exactly sized to the active guest content; overlap pixels survived GPU input reallocation, the first pass/FBO was recomputed on size changes, a fresh full-size guest publication produced correct blue readback, and host redraw did not double-count unchanged source frames. Legacy padded input still fails closed. This validates source-size and pipeline-resize mechanics, **not** a general runtime lifecycle. Next validate attached `GLSurfaceView` and actual guest events/resize order, then implement safe GLSLP preset resolution. Avoid a permanent GPU normalization pass solely for legacy softbar padding.
