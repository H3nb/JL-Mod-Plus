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
catalog, and per-effect details/parameters. The catalog briefly describes an
effect's appearance; a stack card instead summarizes its effective settings.
A card opens details, marked by a chevron. Delete, move-up and move-down
actions are always visible; unavailable moves remain visible but disabled.
Long-press and drag on the card also reorders the stack. Removing an effect
offers Undo, restoring the same instance, parameters and former index. An empty parameter list is read-only. The empty stack remains top-aligned
with its Add action, like other configuration pages. Standard preference titles
use Material 3 bodyLarge, with bodySmall for supporting summaries. Legacy catalog
rows show available INI author metadata or the fragment filename instead of a
generic subtitle. Shared GLSL files do not imply shared tuning: the selected INI
variant must be matched by name, author and source pair. Missing INI metadata
does not necessarily mean shader source files are missing. The picker browses
existing INI files under shaders/; it does not import files from elsewhere.
Returning from the catalog or details does not mutate the draft unless the
user selected an effect, changed a parameter, deleted, or reordered an entry.

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
`u_pixelDelta` and `u_setting` legacy uniforms. As in the released GLES
presenter, user INI shaders execute at the game viewport resolution and honor
the selected screen texture filter. Historical `OutputResolution` and
`Upscaling` flags did not override these presentation rules.

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

The renderer supports at most 16 active GPU passes. `EffectTargetPlan`
computes full-chain FBO sizes and source/composite lifetimes, reusing
same-size targets where safe. It does not reject valid plans based on
estimated total RAM or a fixed intermediate-texture budget. Each target
must fit `GL_MAX_TEXTURE_SIZE`. OpenGL allocation or attachment failure
releases partial resources and returns to stock presentation without
changing the saved effect stack. The user receives an actionable host
notice, once per renderer instance. The final viewport pass draws directly
to the display, so a single-pass effect needs no intermediate FBO.
Targets are replanned only when source content or viewport size changes.
The stock GLES2 context remains the minimum supported configuration.

## Built-in effects and separation of responsibilities

The bundled catalogue consists of CRT Classic, LCD, Scanlines Lite, Color
Adjustment, Vignette, Sharpen, Cartoon Soft, Bloom, Color Depth & Dithering
and Monochrome Display. The legacy `cartoon` ID is retained while its
luminance remapping uses continuous smooth transitions rather than hard
round-to-nearest posterization. This avoids artificial 1/levels jumps
across smooth 2D backgrounds, while leaving the existing outline strength
contract unchanged; output RGB gamut clipping can still affect saturated colors. CRT Classic
is independently authored under Apache-2.0 as a one-sample CRT-inspired
presentation effect. Its luminance-dependent beam is centered on guest
pixel rows, and the RGB triad remains a separate output-space mask. It is not
derived from the former GPL EasyMode shader. The unreleased prototype ID
`crt-easymode` maps to CRT Classic at lookup time to preserve local test
profiles; old unsupported advanced settings are ignored.
Color correction includes gamma, temperature/tint and monochrome.
CRT, LCD and Scanlines Lite anchor their display patterns to logical game
resolution rather than the preceding pass's texture resolution. LCD positions
its RGB subpixels within guest pixel cells on highp devices. Its squared-cosine
grid integrates both harmonics analytically over each output pixel, with grid
visibility fading to zero near 1x. Scanlines Lite uses three-point
Gauss-Legendre quadrature on its raised-cosine profile and also fades at 1x;
very narrow lines remain approximations. On GLES2 devices without fragment
highp, LCD retains a coarse guest-column RGB triad instead of unresolved
within-pixel subpixels.

Bloom extracts highlights before downsampling with a 2x2 sampling footprint
up to 2:1 minification, or a 4x4 footprint at larger reduction ratios. Bloom intermediates use one shared scale on both axes: one-third of the
viewport for low-resolution J2ME sources, increasing toward full viewport
where required to limit bright-pass minification to 4:1. A 4x4 sampling
grid covers aligned 4:1 reductions; fractional or more extreme ratios
remain approximations. The target budget still applies to every FBO.
Both separable blur passes use a normalized 17-tap discrete Gaussian (nine
bilinear texture fetches per direction). Radius controls Gaussian sigma corrected for the intermediate-to-output
resolution ratio, preserving approximately the same screen-space blur width
as the former half-viewport implementation. Paired weights/offsets are
recomputed on the CPU only when the relevant target scale changes, never
per GPU pixel. The +/-8-texel support still limits radius at the upper
extreme for high-resolution sources that need larger intermediates;
a wider kernel would increase GPU bandwidth on low-end devices.
Bloom blur uses GL_LINEAR independently of ScreenFilter; its base composite
still uses the user's filter but clamps samples to the valid image rectangle.
Other bundled effects also clamp reads against input padding, including
the final identity pass. Legacy user INI shaders retain their released
sampling semantics. CRT Classic needs no precision-sensitive Lanczos
reconstruction; it uses logical-grid RGB triads on mediump-only GLES2.
Vignette's strength now reaches its full requested amount at image corners;
Cartoon Soft bounds edge darkening to its configured strength.

Color Depth & Dithering applies RGB444/RGB565/RGB666 channel quantization,
optionally using deterministic Bayer 4x4/8x8 ordered dithering. The matrix
is anchored to logical guest pixels rather than physical display pixels;
its viewport-stage placement makes it a visual approximation rather than a
change to the MIDlet's reported display capabilities. Monochrome Display
offers two/four-tone black-and-white, green and amber LCD-inspired palettes.
Both are stateless GLES2 passes and preserve the source alpha. Enum-like
parameters are selected using localized named options, not numeric sliders.
Catalog descriptions explain the appearance and typical use of every effect.
Temporal LCD response-time simulation is deliberately not included: it
requires history textures, explicit timing and surface/context lifecycle
handling beyond the current stateless pass contract.

Pixel-art reconstruction (xBRZ, MMPX, HQ-style), bitmap/texture filters,
internal resolution and final display scaling have separate responsibilities.
Bitmap enhancement may use the selected internal scale up to the scaler's
supported 10x factor; Java array and RGBA buffer representation limits still
apply. A Java allocation failure falls back to the original bitmap/texture
and reduces subsequent oversized reconstruction attempts for the session.
Successful large immutable regions may enlarge the bounded LRU cache so
repeated draws do not reconstruct every frame. Display-target allocation
tries lower scales after failure; the GLES presenter additionally checks
the actual GPU texture limit before uploading a game frame. At 1×, temporary
upload failures retry after 500 ms through a host-only redraw; an image
exceeding the GPU texture limit waits for a changed surface or GL context.
Any runtime downgrade is reported through the existing nonmodal host notice
with suggested action, leaving the saved profile unchanged.
Bitmap upscaling mode 3 retains its stored `EDGE_AWARE` ID and appears as
xBR Lv3. Its MIT-licensed CPU scaler classifies 15-, 30-,
45-, 60- and 75-degree edge candidates once per source pixel. Rotated and
reflected decisions use an alpha-aware color difference, with scale-dependent
subpixel coverage. Output is written in row blocks to avoid a full temporary
magnified pixel array. This is not an xBRZ implementation.
Only immutable LCDUI source regions and opaque Mascot Capsule model textures
use the enhancement before composition. Mutable guest images, M3G textures,
guest-visible pixel data and framebuffer post-effects are unchanged.
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

JVM tests verify FBO slot lifetime and GPU-size planning and bundled resource/parameter
contracts. GLSL compilation, visual fidelity and frame pacing still require a
real GLES2+ context or device. Test CRT/LCD/scanlines before and after other
full-viewport effects with both padded inputs and higher internal resolution;
test low-precision GLES2 devices and Bloom source preservation.
No source or performance claim is qualified until corresponding evidence exists.

## Provenance

CRT Classic is a self-authored Apache-2.0 shader for JL-Mod Plus, using
elementary trigonometry and per-channel modulation; it does not copy
upstream CRT shader source. The GPL-marked EasyMode adaptation has been
removed from the APK assets. LCD was informed conceptually by Gigaherz's
public-domain LCD3x shader (no source copied). The GPL-marked PPSSPP Cartoon
shader was inspected for behavioral comparison only, without adapting its
source. All bundled post-processing shaders are project-authored under Apache-2.0. This does not certify
licensing of other dependencies.