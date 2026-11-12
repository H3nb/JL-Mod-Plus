# Internal resolution and bitmap enhancement

Internal resolution and source-image enhancement are separate per-MIDlet
rendering settings. Neither changes the guest's logical image dimensions,
input coordinates, fonts or Java ME graphics API contracts.

## Settings

- **Internal resolution:** Auto (default), or 1–10×. Auto selects an
  integer factor based on the visible game viewport. The selected factor
  remains fixed for the Canvas session unless a logical resize exceeds
  target limits or a physical buffer allocation fails. The density then
  decreases for the remainder of that session, down to 1× if necessary.
  Restarting applies profile changes.
  Built-in profiles use the OpenGL ES graphics backend and Auto density,
  including existing MIDlets still owned by Built-in. This refresh preserves
  Built-in ownership. Custom profiles and named presets keep their saved
  choices; missing fields in those historical profiles retain legacy defaults.
- **Bitmap upscaling:** Nearest (default), Bilinear, MMPX or Edge-aware (smooth).
  The selected image reconstruction works on supported immutable sources,
  only when the destination has a physical scale above 1×.
- **Screen scaling filter and shader effects:** independent presentation
  stages applied to the completed game image. Neither changes source pixels.

Native 2D text and primitives are rasterized into the enlarged backing
surface. The display presenter consumes the corresponding physical content
rectangle. MIDlet-visible geometry, pointer positions, clipping, translations
and `getRGB` sizes stay logical; raw screenshots may contain HD pixels.
Readback of antialiased HD raster edges can differ from a separate 1× render.

## Immutable source enhancement

The runtime reconstructs immutable LCDUI image regions before composing
them into an HD target. `drawRegion` isolates the source rectangle to prevent
filtering across neighboring tiles in a sprite atlas. The cache is shared
within the currently selected graphics profile, bounded to 4–16 MiB based
on Java heap tier, and keyed by bitmap identity, generation, region and
reconstruction factor. Existing `Graphics` objects consult the active
session cache at draw time, so retained framebuffers cannot use a previous
profile's enhancement mode.

Reconstruction is independent of the render target factor. MMPX and Edge-aware
use factors from 2× through 4× where the result does not exceed 4 MiB;
otherwise the original source is drawn using Nearest. For example,
a 240×320 image with a 4× target is reconstructed at 3× and then composed
at the target density. MMPX preserves source palette colors; Edge-aware
antialiases reconstructed edges with alpha-aware interpolation.

Mutable `Image`, `drawRGB` and ordinary guest off-screen surfaces are not
source-enhanced; their contents may change on every frame. An unsuccessful
optional reconstruction falls back to the original bitmap rather than
changing guest data. Cold-cache reconstruction is synchronous and can
cause occasional longer frames, especially with many large sprite sources.

## Three-dimensional rendering

**JSR-184 / M3G:** supports HD target rasterization with physical native
viewport and clip dimensions. `Graphics3D` keeps guest coordinates and
viewport getters logical. Mutable `Image2D` render targets remain at their
specified resolution. **M3G source textures are not enhanced**: altering
`Image2D` data, `Texture2D` wrapping/filtering or mipmap ownership would
change the Java ME API contract.

**Mascot Capsule / Micro3D:** renders to an HD-size EGL pbuffer and copies
physical pixels back to the LCDUI target; camera and projection coordinates
remain logical. Opaque immutable **model** textures can be reconstructed for
GLES upload at 2–4×, bounded by `GL_MAX_TEXTURE_SIZE` and the same 4 MiB
result limit. Translucent/alpha-cutout model textures, environment maps and
mutable render targets retain their original texture pixels. Offscreen
render-to-texture targets operate at 1× regardless of the previous HD target. Source
dimensions, palette data and logical UV coordinates remain unchanged.
Android `Image` textures are explicitly encoded as RGBA bytes before upload.
Uploaded texture names are scoped to their EGL context, so context loss or
recreation cannot accidentally reuse another texture with the same numeric GL ID.

## Memory and compatibility

The requested internal scale is capped at 10× with a maximum backing
dimension of 4096 pixels and a conservative 160 MiB four-plane allocation
estimate. These limits do **not** guarantee enough device memory.
Large-source reconstruction is limited separately from frame target density.

GameCanvas retains its buffer and previously obtained Graphics objects across
host resizes, including when a constrained HD density must decrease.
Resizable image-region drawing holds the source image monitor while resolving
its physical scale and sampling rectangle, including transformed sprites.
Micro3D snapshots the target bitmap, density and clip under one image lock
at bind, and retains that bitmap through background composition and readback. If the host replaces that Bitmap during a bound 3D operation,
the old frame is not written into the new backing buffer; the next MIDlet
repaint supplies the new frame. Native RGBA8888 readback rejects invalid
destination rectangles and unsupported Bitmap formats before accessing pixels.
Operations through retained Java ME Graphics objects share the buffer Image
monitor with its resize/rebind operation. Each guest paint retains its own
scratch-buffer reference until the frame is published, so host resizes can
stage another buffer without waiting for the guest paint event.

The published frame, scratch surface and GameCanvas buffer are allocated
before the new density is published. If a high-density allocation fails,
the host tries the next smaller density while preserving the previous frame;
a failure to allocate even a 1× buffer still propagates. This recovery is
best-effort, not a guarantee against complete process memory exhaustion.

The retained regression suites protect partial GameCanvas flush, negative-stride
readback, all sprite transforms, atlas isolation, high-density M3G
viewports, and Micro3D GLES texture upload/alpha semantics. Passing
instrumentation on Android API 23 and 35 does not replace physical
ARM64 performance, GPU-loss or representative game testing. Measured
emulator costs and static observations from private MIDlet reference
archives are retained in
[the dated performance investigation](internal-resolution-performance-2026-11-11.md).

## Redistribution

MMPX retains its MIT license. The Azahar-derived GPL xBRZ CPU port was
removed from the distributable source and replaced by a locally authored
Apache-2.0 edge-aware smoothing mode, which does not implement xBRZ and does
not reproduce identical pixels. Historical selection value 3 now selects
the replacement filter. Nokia M3G retains its EPL-1.0 license; this specific
GPL/EPL combination is no longer bundled. See `THIRD_PARTY_NOTICES.md`.
