# HD target boundaries — architectural research checkpoint

Status: **research-only; no production HD implementation or merge approval**.
Scope: decide which targets should receive physical supersampling and which
coordinate/readback contracts must remain logical. This proposal does not
supersede the shader presentation design in `GLSLP-PROTOTYPE-CONTRACT.md`.

## Discriminating evidence

Source inspected on the R&D branch:
- `Canvas` retains a guest draw buffer (`offscreen`) distinct from its
  published presentation buffer (`offscreenCopy`). Scaling only the latter
  would upscale an already-rasterized 1x image, not implement true HD.
- `Image` exposes logical `getWidth/getHeight/getRGB` but owns a Bitmap;
  `Graphics.copyArea` and sprite/crop paths directly read bitmap coordinates.
  `CanvasWrapper.drawImage` uses `Image.getBounds()` as the source rectangle.
- M3G `Graphics3D.bindTarget(Graphics)` forwards physical Bitmap dimensions,
  unscaled Graphics clip/translation to `_bindGraphics`; native
  `m3gBindMemoryTarget` receives physical dimensions but uses that unscaled
  clip/viewport. `setViewport` forwards native viewport coordinates and
  `getViewportX/Y/Width/Height` returns native values. There is a separate
  `bindTarget(Image2D)` with its own pixel format and logical dimensions.
- Mascot `Render.bind(Graphics)` sizes its GLES2 pbuffer from Android
  Canvas dimensions, while it also uses Android Canvas clip values in
  `glScissor`. `setCenter` and projection functions use that center over
  `env.width/height`. Results return via a separate native readback path.
  Game geometry and clipping must not be silently reinterpreted as physical.
- GLES presentation/shader has a single published game-frame source and a
  separately calculated **game viewport**. Its GLSLP `source` basis must
  consume physical active-content pixels after HD, not the MIDlet's logical
  dimensions, while `viewport` remains the final game rectangle.

**Executed 2D gate:** [HD boundary CI 41258338746](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41258338746),
Android 35 x86_64 emulator, targeted LCDUI job **2/2 PASS**.
`HdLogicalTargetBoundaryResearchTest` uses *actual* `Image`,
`Graphics.fillRect`, `Image.getRGB`, and `Graphics.copyArea`, under a
research-only state with logical 12x10 pixels and physical 24x20 backing.

- Ordinary integer-aligned drawing matched all **120/120** logical sample
  points at 1x vs 2x.
- Unadapted `getRGB` read the wrong physical pixel; naïve `copyArea`
  generated **5 mismatched logical pixels** in this fixture.
- Explicit 2x physical *source* mapping for copy, while drawing to
  logical Canvas destination, restored **120/120** identical logical pixels.
- The current presenter's use of logical `Image.getBounds()` as the
  physical source crop gave **59/120** mismatches; using the full physical
  source content rectangle restored **120/120**.
- These are bounded diagnostic cases, **not** compliance for fonts,
  fractional shapes, alpha/transparency, rotated regions, arbitrary images,
  real gameplay, or mixed 2D/M3G/Mascot. The test mutates the Image bounds
  only to reproduce the problematic *hypothetical* `Canvas.scale(2)` design;
  that mutation is neither a production implementation nor a recommended API.

**GLES control:** [CI 41258305293](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41258305293)
passed the existing **9 classes / 13 instrumentation tests** after
the two new fixture sources were added. Nothing in these HD probes changes
guest production drawing, profiles or the GLSLP executor. A later
[CI 41259088148](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41259088148)
reached and passed the real Canvas 3/3 suite but its shell exited 255
at the subsequent ADB/logcat operation, before the final mounted-view class.
This is an incomplete environment/logging run, *not* a demonstrated shader
pixel failure or a second full green suite. The earlier full green suite
remains the strongest existing GLES regression checkpoint.

**Android 6 differential:** [CI 41259108210](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41259108210),
API 23 x86_64 emulator, passed **LCDUI 2/2 and Mascot 1/1**.
The LCDUI diagnostic mismatch counts were unchanged from Android 35:
5 naive copy-area pixels and 59 wrong-crop pixels out of 120 logical samples.

**Verified Mascot pbuffer/projection boundary:** [API 35 CI 41259088191](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41259088191)
passed **Mascot 1/1** and LCDUI 2/2; [API 23 CI 41259108210](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41259108210)
passed the same probe. Both emulator artifacts show real `Render.bind`
pbuffer dimensions **16x12 -> 32x24** while `Image` still reports
**16x12** logical pixels. The current `glScissor` source clip is **16x12**
from the scaled Android Canvas, which no longer spans the 32x24
physical target. For a logical center (4,3), the real projection's
normalized horizontal translation changed **-0.5 -> -0.75** when physical
dimensions doubled without a center adjustment. Mapping the center to
physical (8,6) restored **-0.5**. This is evidence that the projection
and clip require *separate* logical-to-physical adaptation.

The initial Mascot run failed at `Render.release` because
`micro3d.Utils` accesses `ContextHolder.getActivity()`, which is null
in a headless test (and its JNI readback helper was not part of the
native-disabled fast test). The final bounded probe avoids that call,
checks the actual GLES pbuffer/projection, then explicitly destroys
its EGL resources in test cleanup. **The experiment does not claim that
`draw/flush/release`, geometry, background-to-3D-to-HUD composition, JNI
readback, or performance works at 2x.** Those need real `MicroActivity`
and native-enabled tests, not a fake Activity fallback in production.

## Candidate architecture — smallest compatible scope

**Keep the existing guest 2D, native GLES1 M3G, and GLES2 Mascot renderers.**
No general GPU scene queue or complete renderer replacement is warranted.

1. Represent a *render target* with a consistent relationship between
   **guest logical extent**, **physical content extent**, **backing allocation
   extent**, and **active source rectangle**. Frame publication/sequence
   remains the single ownership authority; avoid a parallel shader queue.
   This is an invariant, not a prescription for a class hierarchy.
2. For initial HD, selectively test the **Canvas/Display game drawing
   target** and its published copy as a matched pair. Keep ordinary
   game-created `Image` buffers and M3G `Image2D` at 1x initially, unless
   a separately verified target adapter is needed. This lowers migration
   scope but limits HD benefit for games that draw 3D into offscreen images.
3. Rasterize in physical pixels while preserving API-visible logical sizes,
   clips, image rectangles, pointer coordinates, origin and viewport getters.
   The 1x path should continue as an identity mapping, without unnecessary
   extra allocations/copies.
4. Treat direct bitmap sampling, `getRGB`, `copyArea`, `drawRegion`,
   sprite crop, mutable Image reads/writes, and screenshots as boundary
   operations that require an explicit logical-to-physical mapping or a
   defined guest-visible pixel-sampling policy. A global `Canvas.scale`
   alone is not correct.
5. For M3G `Graphics` target, adapt native viewport/setter *and* getter,
   origin/clip, physical buffer size/stride, target readback and mixed
   composition. Preserve the independent `Image2D` target contract. Do
   not assume ES1 target resources can be reused in the host GLES2 EGL
   context.
6. For Mascot, adapt pbuffer size, GL scissor, projection/center, background
   upload, clip-aware readback and any orientation mapping as a cohesive
   boundary; do not alter guest layout/command values. Validate draw/flush/
   release in an actual `MicroActivity` before calling this supported.
7. Consume the resulting **physical active content** in the presentation
   pipeline, whose shader stages are already independent of source renderer.
   Host controls/keyboard/background remain outside shader input.
   No shader is required for HD; HD is not required for GLSLP.

## Gates before final architecture or PR approval

- Actual `Canvas.paint` / `GameCanvas` target ownership under 2x physical
  backing, along with partial flush/publish, softbar/fullscreen/resize and
  non-GLES present screenshot/ambient/source rectangle. Compare 1x control.
- Correct guest-visible reads/copies/crops, clip/translate, alpha, and
  interleaved 2D -> M3G -> 2D (also M3G `Image2D`) on a **real native
  ARM64 GLES1** backend. Existing x86_64 emulator native M3G instability
  has no proven root cause; do not mask or repeatedly retry identical cases.
- Mascot real Figure or command-list rendering with 2D background/HUD and
  correct `flush/release`, including its JNI readback, in a genuine host.
- Actual shader input at 1x and 2x physical target; source/viewport resizing,
  parameter/cache invalidation, context loss and pixel output parity.
- Android 6/23 differential and physical ARM64 timing, memory, thermal
  and power data before a user-facing HD setting. HD scale S raises
  target pixel count quadratically; input/framebuffer allocations and
  source uploads must be budgeted, including GPU-dependent caps.

**Decision today:** production **GLSLP-first** still offers the lower-risk
independent feature path. Do not ship internal resolution HD from these
fixtures. Research and implement the bounded game display-target HD
contract, then separately adapt M3G and Mascot and make the feature
user-visible only after mixed-composition correctness is proven.
