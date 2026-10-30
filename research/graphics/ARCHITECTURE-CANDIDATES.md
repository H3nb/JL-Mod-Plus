# Graphics R&D — Architecture hypotheses and decision gates

**Status:** preliminary hypothesis, not production architecture approval.
**Frozen baseline:** `alpha` @ `b2ebf48a1b4d10f2f0a571d9f8272378e582f14c`.
**Date:** 2026-10-30. See [EXPERIMENTS.md](EXPERIMENTS.md) for test evidence and limits.

## Product invariants (must survive any pivot)

1. MIDlet logical `Canvas`, image dimensions, pointer geometry, translation, clipping, pixel reads and writes, and M3G `Graphics` / `Image2D` target behavior must not change solely because the host increases visual resolution.
2. Default rendering remains the proven native-resolution path, without GLSLP enabled.
3. Legacy `.ini` + `.vsh/.fsh` shaders, profile `ShaderSelection`, values, and runtime shader interface remain usable without conversion.
4. Graphics modes unrelated to the GLES presentation path must not silently change their semantics when shaders are implemented.
5. Android lifecycle and GL resource ownership remain explicit. A frame or image is not made valid merely by passing a texture ID across context boundaries.
6. Core J2ME guest pixels and optional host presentation pixels are different concepts; do not hide an incompatibility using a per-game workaround.

## Source-verified paths

```text
MIDlet LCDUI Graphics --> Android Canvas --> Bitmap -------+
M3G / JSR-184        --> GLES 1.x fixed function --> Bitmap/Image2D
Mascot Capsule v3    --> independent GLES 2.0 pbuffer --> Bitmap
                                                            |
                                                  presentable game frame
                                                            |
                                             host GLES 2.0 or Canvas backend
                                                            |
                                             optional shaders and viewport
```

The drawing stages do **not** already share a modern GPU pipeline.

- Native M3G includes `<GLES/gl.h>`, and uses `glMatrixMode`, `glOrthox`,
  `glClientActiveTexture`, `glTexEnvx`, etc. It selects/caches EGL contexts
  through `m3g_rendercontext.inl`; it is not a GLES2 shader implementation.
- M3G `Graphics3D.bindTarget(Graphics)` locks a real Android bitmap buffer
  via `AndroidBitmap_lockPixels` and `m3gBindMemoryTarget`. M3G separately
  supports mutable `Image2D` targets.
- Mascot Capsule creates its own GLES2 pbuffer and uses explicit 2D
  copy/readback to preserve the target `Graphics` model.
- Host `Canvas.GLRenderer` uploads a bitmap and executes a legacy single-pass
  GLES shader. Renderer and presentation GL contexts are not interchangeable.

The code must not be redesigned under the assumption that GLES1 and GLES2 can
run in the same pipeline without translating the fixed-function behavior.

## Candidate approaches

### C1 — Rewrite all guest rendering to a single GPU engine

**Potential benefit:** fewer CPU/GPU boundaries in some mixed rendering paths
and a direct route to HD geometry.

**Cost/risk:** implement LCDUI's 2D text/sprite/clip/pixel semantics and
JSR-184's GLES1-era scene behavior in a new GLES2+ renderer, while preserving
target readback and alternate non-GLES presentation modes. Significant
correctness and long-term maintenance burden.

**Decision:** reject as a *prerequisite* for GLSLP or HD. Reopen only with
measured evidence that a localized adapter or optimizing current renderers
cannot satisfy compatibility/performance goals.

### C2 — Whole-target supersampling with separate logical/pixel dimensions

**Potential benefit:** re-rasterizes text/vector 2D and actual 3D at higher
resolution, including intermixed content using shared target storage.

**Reference:** `fuyijian/J2ME-Loader-HD` adds `Image.pixelScale`, scales
Android Canvas drawing, extends M3G viewport, and downscales readbacks. That
reference's M3G `Image2D` target still has scale 1x; Mascot HD is unverified.

**Cost/risk:** guest-visible `Image.getRGB`, `drawRGB`, `copyArea`, sprite
cropping, immutable/mutable images, pointer coordinates, target clips, alpha
and exact pixel values can diverge. Supersampling by factor S requires S²
pixels; conversion/readback costs also grow. Downsampling solves dimensions,
not automatically the guest's pixel-color contract.

**Decision:** viable candidate for a constrained 2x feasibility experiment,
not a pre-approved universal engine.

### C3 — Renderer-local HD with a separate presentation surface

**Potential benefit:** only re-rasterize where a renderer can recover
additional geometry detail (especially 3D), without redesigning LCDUI.

**Cost/risk:** a 3D image rendered into a detached high-res texture does not
fulfil guest-visible `Graphics`/`Image2D` target semantics by itself.
Interleaved 2D background, 3D, and 2D HUD require correctly ordered
composition; guest pixel readbacks may force synchronization and downsampling.
Additional layers and copies could erase performance gains.

**Decision:** test only once a representative mixed-composition oracle exists.
Do not build a generic scene-command queue or texture-sharing framework
without evidence that it reduces actual total complexity.

### C5 — Replace or supplement GPU M3G with a software rasterizer

**Reference:** [TASEmulators/freej2me-plus](https://github.com/TASEmulators/freej2me-plus)
uses a software JSR-184 rendering implementation: `Graphics3D.java`
owns pixel/depth buffers and `Graphics3DPipelines.java` defines
composition/texture sampling operations. Its docs explicitly offer a
half-resolution 3D speed option because software M3G can be CPU-heavy.

**Potential benefit:** no GLES1 driver dependency for M3G; a single
guest-visible pixel target can be easier to reason about; independent of
EGL context lifecycle. It offers a second backend with useful diagnostic
value when GLES1/native GL breaks on particular devices.

**Cost/risk:** substantial 3D scene and rasterization code, CPU memory
bandwidth and power demand; HD 2x/4x multiplies per-pixel CPU work. The
reference's M3G implementation is **GPLv3**, whereas JL-Mod Plus is
**Apache-2.0**: copying it into this project is not an acceptable shortcut
without an explicit licensing decision or separate permission. An original
implementation may still be researched against public JSR-184 standards.

**Decision:** research as a genuine alternative, but neither replace the
native renderer nor ship a second full 3D backend unless measured
compatibility/performance benefits outweigh duplication and resource costs.

### Presentation upload reuse: smaller optimization before backend redesign

**Source observation:** `Canvas.GLRenderer.onDrawFrame` currently calls
`GLUtils.texImage2D(..., bitmap, ...)` each time the published frame sequence
changes, even when bitmap dimensions match the previous upload. The call
*redefines texture storage*. Android `GLUtils.texSubImage2D` can instead
update an already allocated texture with same-sized bitmap content.

**Candidate:** allocate/redefine only on first frame, context recreation,
bitmap size/format changes; use subimage on same-size frame changes. Preserve
published-frame mailbox ownership and failure recovery. The current
`bufferLock` covers GPU upload; measure whether GL work blocks the guest
producer under load before changing lock/queue ownership.

**Required evidence:** byte-identical RGBA output across several source frames
and dimensions; test transparent pixels and format/premultiplication
behavior; benchmark CPU, GPU and thermals on real Android hardware. Emulator
correctness is not an FPS or energy-speedup claim. This optimization is
orthogonal to GLSLP and true HD.

### C4 — Preserve guest renderers; modernize the final presentation only

**Potential benefit:** straightforward multipass GLSLP, per-pass scaling,
shader parameterization, and optional postprocessing, while retaining
existing rendering semantics and code.

**Limit:** cannot create missing geometric information from a 240x320
rasterized game frame. It is *upscaling/postprocessing*, not true HD J2ME
rasterization.

**Decision:** preferred independent near-term GLSLP route; retain a separate
HD feasibility effort.

## Provisional architecture boundaries (not a class design)

- **Guest target authority:** `Graphics`, `Image2D`, `Image` own the
  logical behavior the game observes. Native/JNI or Android Canvas must
  complete guest-visible operations at the prescribed API boundary.
- **Presentation authority:** the existing output viewport and its input
  bitmap content rectangle are the source of truth. Shader pass dimensions
  depend on original content, preceding pass, and final *game* viewport.
  Virtual keyboard/controls are host UI and not shader input.
- **GPU resource authority:** a GL texture/FBO/program belongs to a defined
  EGL context and GL thread; context recreation invalidates it. Separating
  guest backend from presentation does not authorize cross-context IDs.
- **Profile authority:** existing per-MIDlet shader settings preserve their
  serialized identity and values. A future GLSLP preset should use an
  independent definition/selection type rather than repurposing four legacy
  shader parameters into a generic render graph.

These are interface contracts, **not** a requirement to add four managers,
new thread pools, or an abstract rendering framework before a prototype.

## Gates that can change the recommendation

**G1: Native M3G baseline.** M3G test on API35 x86_64 has gone ADB-offline
after running an `Image2D` case; earlier whole-class run stalled at immediate
geometry. Isolate each on a fresh emulator, capture host-streamed logs, then
check on real hardware. A hang in emulator does not establish a universal
emulator bug or justify a rewrite. Also test GLES1 context version and active
renderer identity if evidence points to an EGL context mismatch.

**G2: GLSLP reality.** A synthetic Libretro-style source and the actual
shipped legacy shaders passed on a SwiftShader GLES3 emulator through GLES2
APIs. Next require an actual documented preset/source compatibility probe,
separately from parser, float-target capability and FBO allocation. Do not
claim arbitrary desktop GLSL/GLSLP compatibility from identity tests.

**G3: HD logical semantics.** 1x oracle versus 2x target-aware supersampling
must preserve screen size, source-rectangle/copy semantics, clips, alpha
and guest-visible reads across draw ordering. Any incompatibility must be
measured and documented; a silent filter-based readback change is not proven
compatible.

**G4: GPU/CPU efficiency.** Measure per-stage CPU raster, M3G draw, Mascot
pbuffer readback, `GLUtils.texImage2D` uploads, shader pass GPU timings,
FBO memory and frame pacing on physical Android hardware. Emulator CPU/GPU
timings are diagnostic only.

**G5: Final architecture.** Pick the least complex combination satisfying
G1–G4. A radical backend change needs a clear measured benefit, a
compatibility demonstration, and a rollback path. Do not merge this R&D branch
as-is; implement reviewed production PRs from accepted contracts.

## Current research CI

- `graphics-rd-fast.yml`: two *executed* GLES2/FBO and shader ABI pixel
  probes passed in SwiftShader, without native M3G.
- `graphics-rd.yml`: initial full M3G run stalled; the second run passed
  flag validation and translated/clipped targets but went `adb: device
  offline` during `Image2D`; unbounded post-failure ADB calls exhausted
  the job limit.
- `graphics-rd-native-isolated.yml`: disposable matrix, one native case per
  fresh emulator with bounded ADB calls and prestarted stream logcat.
  Its outcome must be inspected before drawing conclusions.

**Provisional outcome:** no justification yet to pivot to a unified GPU
renderer. The preferred immediate architecture is a retained guest contract
with a separately modernizable presentation path, subject to all gates.


## Updated provisional decisions (executed evidence)

- API35 x86_64 SwiftShader genuinely supports a GLES1 fixed-function
  pbuffer (`EGL_CONTEXT_CLIENT_VERSION=1`, `GL_VERSION=OpenGL ES-CM 1.1`),
  and GLES1 matrix/clear/readback passed. The M3G hang does **not**
  prove GLES1 absence and does not alone motivate rewriting M3G.
- 2x Android Canvas rasterization plus downsampling changed 71/1024
  deliberately subpixel-sensitive pixels. Exact guest color reads are a
  separate compatibility constraint; supersampling is not an innocuous
  bitmap enlargement.
- On independent native M3G runs, the last observed call differed
  (`render` versus `releaseTarget`); multiple JNI library load
  paths in the test harness are an unverified confounder. Prioritize
  differential test of normal production library loading before
  replacing an entire renderer or changing EGL ownership.

