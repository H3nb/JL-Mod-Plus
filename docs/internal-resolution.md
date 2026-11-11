# Internal resolution and bitmap enhancement

Internal resolution and source-image enhancement are separate per-MIDlet
rendering settings. Neither changes the guest's logical image dimensions,
input coordinates, fonts or Java ME graphics API contracts.

## Settings

- **Internal resolution:** 1–10×, or Auto (default 1×). Auto selects an
  integer factor based on the visible game viewport. The selected factor
  remains fixed for the Canvas session; restarting applies profile changes.
- **Bitmap upscaling:** Nearest (default), Bilinear, MMPX or xBRZ Freescale.
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

Reconstruction is independent of the render target factor. MMPX and xBRZ
use factors from 2× through 4× where the result does not exceed 4 MiB;
otherwise the original source is drawn using Nearest. For example,
a 240×320 image with a 4× target is reconstructed at 3× and then composed
at the target density. MMPX preserves source palette colors; xBRZ
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
mutable render targets retain their original texture pixels. Source
dimensions, palette data and logical UV coordinates remain unchanged.
Android `Image` textures are explicitly encoded as RGBA bytes before upload.
Uploaded texture names are scoped to their EGL context, so context loss or
recreation cannot accidentally reuse another texture with the same numeric GL ID.

## Memory and compatibility

The requested internal scale is capped at 10× with a maximum backing
dimension of 4096 pixels and a conservative 160 MiB four-plane allocation
estimate. These limits do **not** guarantee enough device memory.
Large-source reconstruction is limited separately from frame target density.

The regression suites protect partial GameCanvas flush, negative-stride
readback, all sprite transforms, atlas isolation, high-density M3G
viewports, and Micro3D GLES texture upload/alpha semantics. Passing
instrumentation on Android API 23 and 35 does not replace physical
ARM64 performance, GPU-loss or representative game testing. Measured
emulator costs and static observations from private MIDlet reference
archives are retained in
[the dated performance investigation](internal-resolution-performance-2026-11-11.md).

## Redistribution

MMPX is MIT licensed. The Azahar-derived CPU implementation of xBRZ
Freescale retains GPL-2.0-or-later licensing, with its source notice and
license included in the app. Combined APK redistribution requires review
of the GPL version, compatible licensing, corresponding-source access,
and distribution obligations. See `THIRD_PARTY_NOTICES.md`. Do not assume
that the application's Apache-2.0 root license alone clears this requirement.
