# GLSL/GLSLP live prototype

Select **OpenGL ES 2.0** as the graphics renderer, then choose **GLSLP demo:
Scanlines** or **GLSLP demo: Soft blur** from the existing Shader filter
control. These two packaged Apache-2.0 examples work without copying files.
**Identity filter** restores the original path.

For external shaders, place a root-level `.glslp` preset alongside its
relative `.glsl` resources in the emulator `shaders/` directory.
A root-level `.glsl` source may also be selected as a standalone
Libretro-style combined VERTEX/FRAGMENT shader. Reopen game configuration
to refresh the list. Other graphics modes do not use GLSLP.

This branch supports **one or two passes**, GLES2-compatible combined
shader sources, basic GLSLP scaling/filtering and numeric parameters.
It rejects unsupported preset keys, LUTs, history, feedback, `#reference`,
additional passes, desktop GLSL dialects and non-clamp wrap. It is not
general RetroArch GLSLP compatibility. Failed programs revert to the
identity presentation; inspect logcat for the rejection reason.

GLSLP postprocesses the published guest frame. It does not change
guest logical size, M3G/Mascot rasterization, controls or HD internal
resolution. This is a **testing branch**, not production support.
Validate first launch, softbar/fullscreen changes, resize, context loss,
and 2D/M3G/Mascot games on a physical device.
