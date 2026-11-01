# Alternative rendering architectures — R&D checkpoint

Status: **experimental only**, branch `research/graphics-architecture`.
Do not merge this branch or enable HD / GLSLP from these probes.
This investigation deliberately compares fundamentally different target
ownership models rather than optimizing the existing supersampling design.

## Hypotheses

**A. Per-target physical backing (reference baseline):** an image owns
logical bounds and a physical pixel backing, with logical-to-physical
adapters on every bitmap access. Reference implementation:
`wlor0623/J2ME-Loader-HD` at `822abb8`. Directly supports high-resolution
LCDUI and M3G `Graphics` target without recording guest commands, but guest
pixel readback (especially filtered downsampling) may diverge from 1x.
`GameCanvas`, mutable image caches, and special vendor 3D targets need
coverage. Retain as a credible production candidate, not a strawman.

**B. Dual-resolution recorded commands (new alternative):** record Android
Canvas operations once and replay into independently rasterized 1x logical
and Nx presentation targets. Guest pixel reads consult the 1x target;
published presentation uses the physical high-resolution target. It
preserves the 1x raster for reproducible reads while still improving
subpixel rasterization. Framebuffer-dependent commands require *immediate*
materialization of earlier commands; recording one uninterrupted frame is
not sound. `android.graphics.Picture` works on API 23 and is available
since much earlier releases; Android `RenderNode` cannot be the Android 6
base because its public API requires Android 10.

**C. GPU-owned frame composition (new alternative):** 2D background and
HUD upload as textures; all 3D draws into the same GPU-resident target.
The output texture can feed GLSLP without an intermediate GPU -> Bitmap ->
GPU transfer. Correct event ordering, source-dirty regions and retained
guest logical pixels remain mandatory. An EGL/GLES1 native implementation
cannot be assumed to share an ES2 texture.

**D. GPU-owned HD plus authoritative logical CPU plane (hybrid):**
combine B's guest pixel contract with C's GPU physical frame. This could
avoid full native readback in games without pixel reads, but it cannot
promise native 1x 3D semantics if only the Nx 3D geometry is rendered.
When game code reads pixels after 3D, it needs a separately rendered
1x representation, a transparent materialization policy, or an explicit
compatibility fallback; downsampling is not automatically exact.

**E. GLES2 M3G native target/backend redesign (high investment):**
modify M3G's target ownership and fixed-function rendering so it can draw
to an ES2-owned GPU target, potentially eliminating readback barriers
and enabling one GLES2 composition/shader path. This is *not* a mere
GLRenderer replacement. The native M3G core uses GLES1-era functions
including matrix stacks, fixed-function lighting/texture environment,
vertex/texture coordinate client arrays, and memory target synchronization.
The current JNI `_bindGraphics` locks Android Bitmap pixels and calls
`m3gBindMemoryTarget`; `m3gReleaseTarget` finishes rendering and
commits back-buffer content to that target. Any ES2 migration must preserve
actual JSR-184 render/depth/clip/target semantics and compare with a
functioning GLES1 baseline on real ARM64. No ES2 M3G port was attempted.

## Executed experiments

### Recorded dual-resolution Picture — Android 6 and 35

[Four-job architecture CI 41263749052](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41263749052):
`RecordedDualPlaneResearchTest` passed **2/2 on API 23** and
**2/2 on API 35**, x86_64 emulators, research test sources only.

- A single recorded sequence of rects and antialiased subpixel line was
  replayed onto 24x20 at 1x and 72x60 at 3x. It matched **all pixels**
  of separately executed commands at each corresponding resolution.
  Compared with *nearest-neighbor-upscaled* 1x pixels, the 3x reraster
  contained **360 different pixels on API23** and **364 on API35**.
  This proves new physical rasterization detail, not universal prettier
  graphics or Java ME text fidelity. The numerical difference between
  API levels also cautions against assuming identical font/path raster.
- A deferred copy of framebuffer content from before recorded draws
  produced **12 mismatched logical pixels** on both API levels.
  Splitting command recording/replay at that framebuffer-dependent
  operation and copying the *already rasterized* sources in physical or
  logical coordinates restored pixel equality with independently executed
  1x and 2x controls. The 1x guest-visible image and HD presentation were
  simultaneously consistent in this specific operation sequence.
- Limits: this records Android Canvas commands, not real Java ME
  `Graphics`, `GameCanvas`, M3G, Mascot, text or sprites. Production
  `Graphics` is currently bound directly to a mutable Bitmap Canvas.
  A recording adapter would be a significant API-boundary change and
  might duplicate raster work, memory and per-barrier synchronization.
  Bitmap sources captured by recorded draw commands require explicit
  mutation/snapshot ownership, not arbitrary deferred references.

### GPU-resident mixed-stage texture — Android 6 and 35

The same [CI 41263749052](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41263749052)
executed `GpuResidentCompositionResearchTest` **1/1 PASS** on each
emulator. At 48x32 physical pixels: CPU Bitmap background -> ES2 upload
-> GPU scissored opaque geometry **stand-in** -> CPU transparent HUD
uploaded/blended onto the same RGBA8 FBO. **All 1,536 framebuffer
pixels matched the separately drawn CPU Bitmap oracle.** No
intermediate GPU-to-CPU readback occurred. The sole `glReadPixels`
was the final research assertion.

This proves ordered GPU ownership and RGBA8 blending, **not**
M3G/Mascot GPU interoperability, textured/depth-tested 3D, JNI
`Graphics` target conformance, GPU throughput/energy benefit or
real GLSLP input from that FBO. CPU-to-GPU uploads still occur.
An unqualified claim of zero copies would be false.

### EGL GLES1/GLES2 context sharing differential

[CI 41264320851](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41264320851)
passed **1/1 on API 23** and **1/1 on API 35**, SwiftShader x86_64.
The exact emitted capability state was identical on both emulators:

- GLES2-to-GLES2 same-version texture sharing: **true** (positive control).
- GLES2-shared GLES1 context creation: **true**; `eglGetError=0x3000`
  (`EGL_SUCCESS`), so merely checking context creation would mislead.
- GLES2-created texture visible in GLES1 using `GLES11.glIsTexture`
  **before binding the texture name**: **false**.
- GLES1 and GLES2 standalone contexts both worked. A prior test-build
  compile failure was due to looking for `glIsTexture` in `GLES10`
  instead of `GLES11`, **not** driver failure; the corrected test was
  executed independently on both API levels.

This rejects **unconditional classic shared-context texture reuse**
for M3G on the tested SwiftShader configurations even when context
creation appears successful. It does *not* prove the same failure on
every physical ARM64 driver or rule out a separately validated
`EGLImage`/native-image interop path. Cross-GLES interoperability
should be a conditional optimization at most, not the production
correctness contract. A functioning GPU-owned M3G target may require
a different native target API or a coherent GLES2 fixed-function bridge.

## First-principles trade-offs

- **Fidelity:** per-target HD and filtered Nx-to-1x readback can change
  observable pixels. Dual-plane keeps logical readback exact *for
  operations actually executed on its 1x plane*. GPU-only rendering
  needs a coherent policy for a subsequent J2ME `getRGB`.
- **Work:** picture replay performs 1x + Nx draws and requires barriers
  at memory-observing operations. GPU composition avoids some readbacks
  but introduces texture uploads, resource sync and EGL ownership
  complexity. Count *real* transfer bytes/events rather than draw calls.
- **Memory:** for a 240x320 guest at 3x, one physical RGBA8 plane
  costs ~2.64 MiB; two physical Canvas buffers ~5.27 MiB before
  a GL source (~2.64 MiB), FBOs, game-managed images, 1x oracle and
  allocations. For 800x600 at 4x, a single plane ~29.30 MiB; two
  Canvas buffers + GL source + two full-size FBOs would be ~146.5 MiB.
  Real GLSLP passes may be smaller or larger; caps must apply to
  *aggregate* resource usage, not one Bitmap in isolation.
- **Correctness:** 2D -> native 3D -> 2D must preserve order, alpha,
  clipping, origin, depth and readback. M3G `Image2D` differs from
  `Graphics`. Mascot creates its own ES2 pbuffer and readback path.
  Neither dual-plane replay nor a GPU framebuffer proof removes
  these requirements automatically.
- **Compatibility:** keep the Android 6 GLES2 floor, 1x fallback and
  existing legacy shader format. A GPU-first rebuild must not force
  Vulkan or ES3 for compatibility without a separate contract.

## Decision gates

**Do not select the most novel architecture solely because its small
probe passes.** Before a production architecture pivot, prove:
1. An actual `Canvas.paint`/ `GameCanvas` sequence, including text,
   sprite transforms, `drawRGB`, partial flush, `copyArea`, repeated
   `getRGB` and a source Bitmap mutation, at 1x and 2x;
2. Native ARM64 M3G `Graphics` and `Image2D` target bind/viewport/
   release, then 2D -> M3G -> HUD with guest-visible readback;
3. Mascot `bind` / `flush` / `release` with real 3D geometry and
   mixed 2D/background/HUD in an actual `MicroActivity`;
4. Source, host-viewport and GLSLP pass-size invariants under resize
   and context loss, with no shader processing of host controls;
5. Physical device ARM64 frame times, upload/readback bytes, allocations,
   heap/GL memory, power and thermals, *before* preferring repeated
   replay or a new GPU owner to existing target adapters.

**Interim assessment:** per-target HD remains an implementation-ready
baseline *candidate* once real game integration gates pass. Picture-based
replay and GPU-owned composition are both **feasible mechanisms**,
not validated full emulator architectures. The potential integrated
hybrid is promising but unproven. A native M3G GLES2 port would be a
separate research program with its own compatibility baseline rather
than a prerequisite for bounded GLSLP delivery. Keep GLSLP independent
of any guest renderer target implementation.
