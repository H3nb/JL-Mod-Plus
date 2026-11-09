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
target viewport and texture filter. Renderer programs share the host GLES
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
A pass may request input size or reduced viewport size for a blur. Size, texel
offset and UV orientation must be derived from the *actual* input texture.
Physical display padding, softbar and virtual controls remain outside this
effect chain. Normal rendering without active effects goes through the stock
presenter. Shader compilation failures should not crash the guest; the
renderer reports the failure and falls back to the stock presentation.

The present renderer supports at most 16 active GPU passes and a bounded
temporary framebuffer budget (64 MiB); no program compilation or texture
allocation is expected on each steady-state frame. The stock GLES2 context
remains the minimum supported device configuration.

## Qualification

Test separate and mixed chains, repeated effects with distinct parameters,
screen resize/rotation, temporal frame invalidation and context loss. In
particular test the 2D softbar content crop, portrait and landscape viewport
alignment, legacy vertex/fragment compatibility, Bloom source preservation,
sRGB/gamma assumptions and CRT pixel mask visual output.

GLSL source compilation, actual image output and frame pacing require a real
GLES2+ context or device; JVM planning tests cannot substitute for this.
No source or performance claim is qualified until corresponding evidence exists.

## Provenance

Built-in CRT EasyMode is adapted from
`libretro/glsl-shaders/crt/shaders/crt-easymode.glsl` by EasyMode
(upstream header states GPL). The project keeps an explicit provenance record
in `THIRD_PARTY_NOTICES.md`; release compliance is a separate review gate.
Other built-in example filters are native GPL-free contributions under
the project's Apache-2.0 license unless a file declares otherwise.
