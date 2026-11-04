# GLSL/GLSLP live prototype

Select **OpenGL ES 2.0** as the graphics renderer, then choose **GLSLP demo:
Scanlines** or **GLSLP demo: Soft blur** from the existing Shader filter
control. These two packaged Apache-2.0 examples work without copying files.\nYou can also select the bundled Libretro FSR, xBRZ Crisp, xBRZ fixed 4x and\nSuper 4xBR (7-pass) presets for comparison.
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

The `libretro/` asset tree is copied unmodified from
https://github.com/libretro/glsl-shaders at pinned upstream commit\n`1aaabbebdd88a8a89a82edfdc94e10580c99ffdd` with its original embedded
copyright and license notices intact. The source files are intentionally
kept separate from application-owned shader code.

**Licensing review is required before redistributing or merging this
prototype.** xBRZ sources include GPL-3.0 notices alongside other
licenses; do not assume the repository's shaders share one license.
FSR and Super 4xBR have their own upstream author notices.

The 7-pass Super 4xBR preset exercises offscreen sizing, texture history
limited to the previous-previous pass and the original guest texture.
The pipeline preserves each `#version 130` source file on disk, but
removes that exact directive in-memory for GLES2 compilation so the
shader's compatibility macros select GLSL ES 1.00. Compilation still
rejects unsupported GPU syntax. Presets requiring float/sRGB framebuffer
formats, LUTs, or temporal feedback are rejected rather than silently
substituting an incorrect RGBA8 approximation.

All offscreen stages are subject to a combined 64 MiB RGBA8 budget.
Unsupported passes revert to the identity presentation, leaving the
guest rendering, controls, and background logic unchanged.
