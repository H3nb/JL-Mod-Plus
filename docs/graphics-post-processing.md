# GLES post-processing effect stack

The post-processing layer consumes the published guest image and affects only the
host presentation. It must not change the pixels returned to a MIDlet by LCDUI
or the renderer-specific semantics of M3G and Mascot Capsule.

## Model and persistence

`ProfileModel.PostEffects` stores an ordered list of `EffectEntry` values.
Each has a stable instance key, parameters and either a built-in
effect ID or a legacy user shader selection. All entries in the stack are active;
old pre-release per-effect disable flags are intentionally discarded. Catalog names, descriptions and
GPU handles are not persisted. A profile's historical single `Shader`
selection migrates to one user entry on v9-to-v10 publication if it references
both legacy source files. Obsolete pre-release GLSLP selections are not activated.

The Compose editor has three surfaces: an ordered stack, a two-tab built-in/user
catalog, and per-effect parameters. A row opens its parameters; separate compact
controls delete, move one position, or long-press-drag the card itself. An
empty parameter list is displayed read-only. Returning from the catalog or
parameters does not mutate the draft unless the user selected an effect,
changed a parameter, deleted, or reordered an entry.

The Compose editor owns only a draft. ConfigActivity/preset publication owns
persistence, installed-app identity and cancellation. Built-in GLSL sources
ship in the APK; external user effects are discovered from the existing
installation-owned `shaders/` directory. Never move/delete user shader files
or create `shaders_glsl`/`shaders_legacy` automatically.

## Rendering contract

`PostProcessRenderer` owns program compilation, uniform binding, temporary
RGBA8 textures/FBOs and presentation while a GLES2 context is current. It
receives the source texture, its actual dimensions, valid content rectangle,
target viewport and texture filter, plus the logical guest dimensions separately
from source texture dimensions (required when internal resolution is raised).
Renderer programs share the host GLES
context but own no MIDlet frame or Android overlay. Built-in effects use a
shared fullscreen vertex shader; user effects use their own original
`.vsh`/`.fsh` program, with `sampler0`, `u_texelDelta`,
`u_pixelDelta` and `u_setting` legacy uniforms.

An effect may expand to more than one sequential pass. The first pass of each
effect captures that effect's incoming texture; Bloom retains this texture
until its final composite pass. Intermediate texture reuse must not alias a
currently sampled source or a still-live effect input. A GLES2 texture must
never be rendered into while bound as a sampler of the same draw.

Screen-space effects are normally evaluated at the game content viewport.
A pass may request input size or reduced viewport size for a blur. Size and
texel offsets derive from the *actual* input texture. Built-ins requiring a
physical screen pattern receive `uGameSize` (original logical content),
`uDisplayUvScale/uDisplayUvOffset` (cropped/flipped input UV to normalized
display coordinates), and `uOutputSize` (pass output pixels).
`uContentUvMax` prevents sampling into padded source texture regions.
These uniforms must not be inferred from a previous pass's resolution; CRT,
LCD and scanlines remain anchored to guest/display coordinates through a
chain and future internal-resolution/texture-filter integrations.
Physical display padding, softbar and virtual controls remain outside this
effect chain. Normal rendering without active effects goes through the stock
presenter. Shader compilation failures should not crash the guest; the
renderer reports the failure and falls back to the stock presentation.

The present renderer supports at most 16 active GPU passes. `EffectTargetPlan`
computes full-chain FBO slot sizes and source/composite lifetime protection
before allocating textures, reusing same-size slots where safe. The current
temporary framebuffer budget is 64 MiB (a project policy, **not a device
hardware limit**); the limit excludes source textures and other application
GPU memory. The final viewport-sized pass writes directly to the display,
so a single-pass effect requires no intermediate FBO. Planning and texture
creation are repeated only when the source content size or viewport changes,
not for every steady-state frame. The stock GLES2 context remains the minimum
supported device configuration.

## Built-in effects and separation of responsibilities

The bundled catalogue consists of CRT EasyMode, LCD, Scanlines Lite, Color
Adjustment, Vignette, Sharpen, Cartoon Soft and Bloom. The legacy `cartoon` ID
is retained while its luminance-preserving algorithm improves. CRT has
primary controls and separately accessible advanced parameters; color
correction includes gamma, temperature/tint and monochrome.
LCD and Scanlines Lite intentionally depend on logical game resolution rather
than the texture resolution of the preceding pass. Phosphor/subpixel masks are
suppressed when GLES2 fragment high precision is unavailable.

Pixel-art reconstruction (xBRZ, MMPX, HQ-style), bitmap/texture filters,
internal resolution and final display scaling have separate responsibilities.
FXAA/SMAA are future anti-aliasing options, **not** additional entries in this
effect catalogue. The underlying GPU may reuse the same full-screen pass
machinery without presenting these controls as user-orderable decorative
effects.

## Qualification

Test separate and mixed chains, repeated effects with distinct parameters,
screen resize/rotation, temporal frame invalidation and context loss. In
particular test the 2D softbar content crop, portrait and landscape viewport
alignment, legacy vertex/fragment compatibility, Bloom source preservation,
sRGB/gamma assumptions and CRT pixel mask visual output.

JVM tests verify FBO slot lifetime/budget planning and bundled resource/parameter
contracts. GLSL compilation, visual fidelity and frame pacing still require a
real GLES2+ context or device. Test CRT/LCD/scanlines before and after other
full-viewport effects with both padded inputs and higher internal resolution;
test low-precision GLES2 devices and Bloom source preservation.
No source or performance claim is qualified until corresponding evidence exists.

## Provenance

Built-in CRT EasyMode is adapted from
`libretro/glsl-shaders/crt/shaders/crt-easymode.glsl` by EasyMode
(upstream header states GPL). The project keeps an explicit provenance record
in `THIRD_PARTY_NOTICES.md`; release compliance is a separate review gate.
LCD is original project code informed by the public-domain LCD3x approach of
Gigaherz (no LCD3x source copied); Scanlines Lite, Color Adjustment, Vignette,
Sharpen, Cartoon Soft and Bloom are project-written Apache-2.0 shader sources.
The CRT GPL release compliance review remains mandatory before redistribution.
