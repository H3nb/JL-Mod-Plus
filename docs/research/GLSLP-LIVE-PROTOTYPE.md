# GLSL/GLSLP live prototype

Select **OpenGL ES 2.0** as the graphics renderer, then choose **GLSLP demo:
Scanlines** or **GLSLP demo: Soft blur** from the existing Shader filter
control. These two packaged Apache-2.0 examples work without copying files.
You can also select the bundled Libretro FSR, xBRZ Crisp, xBRZ fixed 4x and
8-pass Gaussian + xBRZ + FSR stress composition for comparison.
**Identity filter** restores the original path.

For external shaders, place a root-level `.glslp` preset alongside its
relative `.glsl` resources in the emulator `shaders/` directory.
A root-level `.glsl` source may also be selected as a standalone
Libretro-style combined VERTEX/FRAGMENT shader. Reopen game configuration
to refresh the list. Other graphics modes do not use GLSLP.

This branch supports **one to sixteen passes**, GLES2-compatible combined
shader sources, basic GLSLP scaling/filtering and numeric parameters.
It accepts a bounded chain (maximum 8 files) of simple `#reference` GLSLP
presets that inherit a full preset and override declared scalar parameters or
the file paths of declared lookup textures. Relative shader and LUT paths are
resolved from the file that declares them. Cycles, unsafe paths, structural
pass overrides, `.params` references, LUT sampler-settings overrides through
`#reference`, and other advanced reference features are rejected.

Named pass outputs are supported using GLSLP `aliasN` as well as
one-based `PassNTexture` samplers and bounded `PassPrev3Texture`–
`PassPrev16Texture` references. Aliases and pass-index samplers refer only to
outputs from **earlier passes in the current frame**; self/future references
are rejected when active. The output textures are already allocated for the
chain, so this support adds no extra intermediate render targets. The sampler
units are assigned once per linked program and must fit the device's fragment
sampler limit. Secondary and indexed pass samplers use the filtering associated
with the referenced source output (the corresponding `filter_linearN`, or the
default). Static lookup samplers default to linear if `NAME_linear` is absent. This is not temporal
frame history/feedback and does not imply full RetroArch equivalence.

Static GLSLP lookup textures are now supported as a bounded GLES2 subset:
up to four PNG/JPEG images per preset, decoded only when the GL context is
created, with at most 16 MiB of uncompressed RGBA8 pixel storage.
The GLSLP `textures` list, named sampler, per-lookup `_linear`,
`_wrap_mode`, legacy `_repeat`, and `_mipmap` are recognized.
GLES2 non-power-of-two images cannot use repeat/mirrored-repeat or mipmaps.
The same GPU texture is reused between frames and released with its pipeline.
The configuration UI only parses metadata; it never decodes or uploads images.

Common exported per-pass metadata `mipmap_inputN=false`,
`float_framebufferN=false`, and `srgb_framebufferN=false` is accepted. Input sampling can now use repeat or
mirrored-repeat and optional `mipmap_inputN=true` on power-of-two images.
Mipmaps are generated only when the guest frame or pass layout changes and
their extra level allocations count against the 64 MiB plan budget. Unsupported non-power-of-two GLES2 input combinations are rejected explicitly
unless the active context advertises full NPOT support through OpenGL ES 3.x
or `GL_OES_texture_npot`. The same check applies to static lookup textures.
The renderer probes the capability once at GL-context initialization; unsupported
devices do not silently emulate repeat or mipmap behavior.
A preset can explicitly request `clamp_to_border` for pass inputs or static
lookup textures. The GLSL pipeline supports transparent-black border sampling
using GLES 3.2 or `GL_OES/EXT/NV_texture_border_clamp` on the active GL context.
Unsupported GPUs reject the preset at initialization instead of silently
substituting clamp-to-edge. Non-power-of-two border sampling also requires
full NPOT support. The existing default for omitted wrap modes remains unchanged.
The new emulator GPU test checks real out-of-range sampling when supported,
or the explicit rejection path when the extension is absent.

The GLES2 pipeline now binds integer `FrameCount` and `FrameDirection`
uniforms. `FrameCount` follows the guest frame publication sequence (rather
than advancing on host-only repaints), with per-pass positive `frame_count_modN`
when specified. `FrameDirection` is always +1 because JL-Mod Plus does not
provide guest rewind. This enables frame-driven GLSL effects without adding
history buffers; it does **not** implement previous-frame feedback or guarantee
phase parity with RetroArch when its frontend renders frames on a different
cadence.

GLSLP `float_framebufferN=true` now allocates an RGBA16F intermediate only for
the requested pass, with 8 bytes per pixel charged to the existing 64 MiB
offscreen budget. A float final pass is automatically offscreen with a normal
stock blit to the display. GLES2 contexts require `GL_OES_texture_half_float`
and `GL_EXT_color_buffer_half_float` (or a compatible float color-buffer
extension); GLES3 contexts still require a renderable half-float attachment.
The executor checks framebuffer completeness when allocating each output.
Linear filtering of half-float textures requires the relevant ES2 extension
(`GL_OES_texture_half_float_linear`); unsupported combinations are rejected
rather than silently sampling incorrectly. The emulator GPU test checks a value
above 1.0 survives an intermediate pass when supported, or verifies the
explicit capability rejection on unsupported contexts. A passing rejection
test is not evidence that a physical device supports float rendering.

Other unsupported keys, history, feedback, sRGB framebuffers and
desktop-only GLSL constructs remain unsupported. This is not
general RetroArch GLSLP compatibility. Failed programs revert to the
identity presentation; inspect logcat for the rejection reason.

GLSLP postprocesses the published guest frame. It does not change
guest logical size, M3G/Mascot rasterization, controls or HD internal
resolution. This is a **testing branch**, not production support.
Validate first launch, softbar/fullscreen changes, resize, context loss,
and 2D/M3G/Mascot games on a physical device.

## Bundled upstream sources and restrictions

The upstream `.glsl` sources and original `.glslp` presets are copied unmodified from
https://github.com/libretro/glsl-shaders at pinned upstream commit
`1aaabbebdd88a8a89a82edfdc94e10580c99ffdd` with its original embedded
copyright and license notices intact. The upstream sources are kept separate from application-owned shader code;
`stress/eight-pass-composition.glslp` is an explicitly local test composition.

**Licensing review is required before redistributing or merging this
prototype.** xBRZ sources include GPL-3.0 notices alongside other
licenses; do not assume the repository's shaders share one license.
FSR and Gaussian shaders have their own upstream author notices.

The eight-pass stress preset is **JL-Mod Plus-authored composition**, not
an upstream Libretro preset. It uses unchanged upstream Gaussian, xBRZ and
FSR shader sources to stress offscreen sizing, the previous-previous image,
the original guest texture, and viewport resizing.
A genuine upstream seven-pass Super 4xBR preset was also tested but failed
on GLES2 because its shader requires non-square `mat4x3` matrices, absent
from GLSL ES 1.00. It is intentionally **not selectable** on GLES2.
The pipeline preserves upstream `#version 130` source files on disk, but
removes that exact directive in-memory for GLES2 compatibility shaders.
This is not a general desktop GLSL-to-GLES translation. Compilation still
rejects unsupported GPU syntax. Presets requiring unsupported sRGB formats
or temporal feedback are rejected rather than silently substituting
an incorrect RGBA8 approximation. LUTs with unsupported formats, too many
samplers, or invalid GLES2 texture options are also rejected.

Pipeline size planning and GL_MAX_TEXTURE_SIZE queries are cached while source
and viewport dimensions are unchanged, and rebuilt on resize or source-size
change. This reduces per-presentation CPU work; no device-level speedup is
claimed without profiling.

All offscreen stages share a 64 MiB budget, accounting for RGBA8, RGBA16F
and the required mipmap levels individually.
Unsupported passes revert to the identity presentation, leaving the
guest rendering, controls, and background logic unchanged.

## Rendering configuration prototype (PR #224)

The Display > Rendering section now provides **Shader effects** and **Rendering
pipeline** pages. Choose a preset in Shader effects; GLSLP numeric parameters
can be tuned per game without editing the .glslp file. The pipeline page shows
the resolved pass order and input filter/output sizing, alongside the existing
graphics renderer, scaling and texture filtering settings. Pass structure is
read-only in this prototype; arbitrary editing is intentionally not advertised.
The internal resolution indicator remains Native, not an HD implementation.

External storage uses `shaders/shaders_legacy/` for .ini/.vsh/.fsh files and
`shaders/shaders_glsl/` for GLSLP preset trees, with nested relative GLSL
dependencies. Root-level old shader files are moved on configuration opening
when no destination collision exists. The legacy loader checks both old and
new locations so existing saved vertex/fragment references still resolve.
For an older emulator sharing the same directory, moved files may need to be
restored before using that older version. Root-level external .glsl/.glslp
remain readable for compatibility. Reopen the configuration screen to
refresh the catalog after manually adding files.

The pipeline overview is a configuration preview, not evidence of successful
GPU compilation. Runtime GLES still owns shader programs and can fall back
to identity if a preset is unsupported or invalid. Existing GLSLP scope and
physical-device qualification restrictions above still apply.

Rendering page strings currently use the canonical English source copy. Missing
translations are explicitly scoped to the prototype resource file for lint;
this must be revisited once the UX wording is accepted for release.
