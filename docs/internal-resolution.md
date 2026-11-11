# Internal resolution and source image enhancement (research)

This draft PR #230 is based on the current `alpha` rendering architecture.
Both settings are per-MIDlet and are intentionally **independent of post-processing**.

## Contract and defaults

- `InternalResolutionScale`: Auto (0) or 1–10, default 1. Auto chooses an
  integer target density based on the visible game viewport. The chosen
  density is frozen for the Canvas session; restart to apply changes.
- `BitmapUpscalingMode`: Nearest (0, default), Bilinear (1), MMPX (2), and
  experimental xBRZ Freescale (3). It affects supported immutable image
  sources only when the internal target is larger than 1×.
- Java ME image sizes, clip/translation, font metrics, pointer coordinates,
  getRGB dimensions, and guest-visible texture data stay logical.
- Host controls, screen scaling, built-in post-processing effects and physical
  screenshot destinations remain separate. Raw screenshots contain HD pixels.

The display's "Screen scaling filter" controls Nearest/Linear sampling of the
final game image and unspecified shader passes. It is independent of both
source-image reconstruction and internal render target density.

The internal render target is the output of composition, **not** the source of
bitmap reconstruction. Scalable text and primitives rasterize directly at
higher density. For source images, the runtime may reconstruct a cached
higher-resolution copy before compositing it into that HD target.

## 2D bitmap reconstruction

Immutable LCDUI images and isolated `Graphics.drawRegion` sprite rectangles
use per-region cache entries, keyed by source identity, generation, rectangle
and reconstruction factor. Filtering subrectangles cannot sample neighboring
sprite-atlas tiles. Mutable Image and `Graphics.drawRGB` remain unenhanced
rather than silently changing guest drawing semantics.

A source's reconstruction factor is **not** the internal render target factor.
The current CPU policy chooses the highest factor from 2× up to min(4×, target
scale) within a 4 MiB RGBA output limit. E.g. 240×320 at a 4× target uses
3× reconstruction followed by presentation to the 4× destination. If no
factor qualifies, use Nearest. The existing 16 MiB LRU cache is per
`BitmapUpscaling` session. Bilinear crops are stored separately at 1×.
Cold-cache CPU reconstruction may still stall the guest; asynchronous work
and a device-adaptive memory budget remain research topics.

MMPX inherits the reference 2× color/quadrant decisions, with a documented
palette-preserving extension for other integer factors. xBRZ Freescale ports
the Citra/Azahar GPL-2.0-or-later shader's gradient/line rules to CPU.
Alpha-weighted interpolation differs deliberately from the straight-alpha
GLSL implementation to avoid fringes in Android bitmap compositing.

## 3D: rasterization and source textures

JSR-184 M3G renders into a high-density bitmap/native memory target when
bound to an HD LCDUI Graphics. Native viewport and clip dimensions are
converted to physical pixels, while getters and guest coordinates remain
logical. Mutable `Image2D` targets still render at logical resolution.
**M3G source texture enhancement is not implemented.** Never rescale its
guest-accessible `Image2D` data merely to force enhancement.

Mascot Capsule/Micro3D uses a physical-size EGL pbuffer, with logical camera
and projection coordinates. Its scissor and readback bounds are physical.
When an opaque, immutable **model** source texture is uploaded to GLES,
experimental MMPX/xBRZ can reconstruct it using the same 2×–4× policy and
the reported `GL_MAX_TEXTURE_SIZE`. Environment maps and textures containing
any non-opaque alpha are deliberately bypassed: their palette and alpha-test
semantics must not be silently changed by source reconstruction. Support for
safe alpha coverage reconstruction requires separate visual/GLES qualification.
Android Image-derived textures are packed into RGBA bytes explicitly so the
upload is independent of CPU endianness. Their guest Image pixels are not modified.
Source data and logical UV coordinates are unchanged;
the shader's `uTexSize` remains the logical source size. Mutable render
textures remain unenhanced at 1×. GPU resource loss can cause a re-upload.
GPU uploads still require source-to-GPU copies, and Micro3D still performs its
existing CPU–GPU background/readback transfers.

Current texture enhancement is not an automatic, per-texture style detector.
Nearest/Bilinear retain native sampling semantics. No Vulkan migration or
whole-frame texture enhancement is planned for this prototype.

## Budget and compatibility boundaries

The Internal Resolution target has a factor cap of 10×, a 4096-pixel side
limit, and a conservative 160 MiB four-plane estimate. This is a heuristic,
**not** a hardware memory query and not an allocation guarantee.
M3G native physical viewports retain a separate 4096-pixel cap.

A logical 1-pixel horizontal/vertical `Graphics.drawLine` is rasterized as a
full logical-pixel row/column on HD-backed Canvas targets. This avoids gaps
between adjacent progress-bar strokes; default 1× behavior is retained.
Transparent edges and guest `getRGB` readback can still differ from a
separate 1× rasterization; high-density sampling does not guarantee
pixel-identical guest reads.

## Validation and release gates

The JVM tests check factor selection and CPU RGBA reconstruction. Android
instrumentation covers atlas isolation, 2D HD surfaces, logical geometry,
sprite transformations and preliminary 3D/native behavior. Compilation or
JVM tests alone do **not** qualify M3G, GLES, alpha, EGL pbuffer use, or
real MIDlet performance.

Still required before merge: representative ARM64 2D/3D MIDlet tests
(the Bomberman 3D loading-bar issue is reported fixed; protect it against
regression and investigate remaining viewport cases independently);
API 23/35 instrumented GLES and native M3G coverage; CPU cold-cache latency,
frame pacing and GPU/RAM measurements; inspection of screenshot mismatches;
removal of the temporary PR-scoped workflow; and GPL redistribution/license
compatibility assessment. Do not promote screenshot baselines without
reviewing the renderer-produced candidates. Keep PR #230 Draft.
