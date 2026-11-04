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
It rejects unsupported preset keys, LUTs, history, feedback, `#reference`,
float/sRGB framebuffers, unverified desktop-only GLSL constructs and non-clamp wrap. It is not
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
rejects unsupported GPU syntax. Presets requiring float/sRGB framebuffer
formats, LUTs, or temporal feedback are rejected rather than silently
substituting an incorrect RGBA8 approximation.

All offscreen stages are subject to a combined 64 MiB RGBA8 budget.
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
