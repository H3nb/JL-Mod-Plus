# Graphics R&D — Evidence register and experiment plan

Research branch: `research/graphics-architecture`. Production implementation is **not** authorized by an experiment's outcome. Baseline: `alpha@b2ebf48a1b4d10f2f0a571d9f8272378e582f14c`.

## Evidence classes

- **Normative**: Java ME/MIDP, JSR-184, vendor API descriptions in the user's J2ME Dev Kit.
- **Source verified**: current JL-Mod Plus source and tests at the pinned baseline.
- **Executed**: specific test run, source SHA, device/GPU, and observed output.
- **Hypothesis**: inference from architecture not yet shown by a reproducible experiment.

Do not promote a hypothesis to a verified bug, performance gain, or compatibility guarantee.

## Verified source and contract observations

1. The guest-visible `Canvas` size is independent from the Android viewport, scale and pointer transform. Raising `ScreenWidth`/`ScreenHeight` to simulate HD is **not** equivalent to supersampling because it modifies the MIDlet-visible `Canvas`.
2. LCDUI rasterization targets an Android bitmap. Methods such as `Image.getRGB`, `Graphics.copyArea`, and `Graphics.drawRGB` are part of the guest-facing pixel contract. A high-resolution backing target needs explicit treatment of those operations, including intermediate readback and clipping.
3. The JNI M3G `_bindGraphics` uses `AndroidBitmap_lockPixels` then `m3gBindMemoryTarget` with the bitmap's real width/height. Its `releaseTarget` calls `m3gReleaseTarget` and unlocks pixels. `Graphics3D` also supports mutable `Image2D` targets. A display-only HD texture cannot improve already-rasterized 3D.
4. The Mascot Capsule renderer creates its own GLES2 EGL pbuffer, mixes 2D data when needed and reads pixels back into the target bitmap on `release`. Its `bind(Graphics)`/`flush`/`release(Graphics)` API supports offscreen mutable Image drawing.
5. `Canvas.GLRenderer` uploads the finished bitmap into a GLES texture and applies one shader on presentation. Other graphics modes bypass that shader. The existing identity and legacy shader ABI is `.ini` + `.vsh/.fsh`, `a_position`, `a_texcoord0`, `sampler0`, `u_texelDelta`, `u_pixelDelta`, `u_setting`. Profiles serialize legacy shader `fragment`, `vertex` and `Settings` values.

## Research findings from real Libretro presets

Sources:
- [GLSL shader developer documentation](https://docs.libretro.com/development/shader/glsl-shaders/)
- [Shader overview / scale semantics](https://docs.libretro.com/development/shader/shader-overview/)
- [`bilinear.glslp`](https://github.com/libretro/glsl-shaders/blob/master/bilinear.glslp)
- [`blurs/dual_filter_2_pass.glslp`](https://github.com/libretro/glsl-shaders/blob/master/blurs/dual_filter_2_pass.glslp)

`bilinear.glslp` defines one pass (`stock.glsl`) and linear input filtering. This simple preset still uses a *different source ABI* from the legacy JL-Mod Plus shader: a combined `.glsl` source compiled with `VERTEX` or `FRAGMENT` definitions, and attributes/uniforms such as `VertexCoord`, `TexCoord`, `MVPMatrix`, `Texture`, `InputSize`, `TextureSize`, `OutputSize`.

`dual_filter_2_pass.glslp` actually defines **four** shader passes; its name does not imply two GPU passes. It specifies a floating-point framebuffer for each pass, plus source-relative downsampling and viewport-relative final output. For hypothetical 240x320 input and 1080x1920 destination, its requested output target dimensions are:

| Pass | Scale | Output | RGBA32F target size, theoretical |
| --- | --- | --- | --- |
| 0 | source 1x | 240x320 | 1.17 MiB |
| 1 | source 0.5x | 120x160 | 0.29 MiB |
| 2 | source 2x | 240x320 | 1.17 MiB |
| 3 | viewport 1x | 1080x1920 | 31.64 MiB |

These are theoretical *individual* color texture allocations (16 bytes/pixel), not measured peak GPU memory. Full operation may require simultaneously live previous-pass buffers, original input, extra format/alignment overhead, and a final blit to the display. Some targets can be reused only when doing so does not violate a shader input/history dependency.

**Material design consequence:** ES2 FBO and GLSLP support do not imply availability of 16/32-bit floating-point color-renderable FBOs. The required texture and color-buffer extensions/capabilities are separate. At runtime, check actual framebuffer completeness and GL errors rather than relying on the reported GLES version alone.

## Temporary architecture constraints supported by evidence

- Keep Java ME raster/target semantics separate from final host presentation.
- Preserve the existing `.ini`/`.vsh`/`.fsh` ABI and its persisted settings without requiring conversion.
- Treat `.glslp` parsing, `.glsl` compilation, and pass texture/FBO capabilities as **three separately testable** layers.
- Pass-size resolution must know both current input size and final *game viewport* size (which is distinct from Android surface size and virtual controls).
- Default identity presentation must remain independent from GLSLP support; any unsupported preset must not make a MIDlet unplayable.
- Do not force M3G and Mascot into one EGL context; their bind/release and readback semantics differ.
- Do not invent a universal HD path before verifying interleaved guest 2D/3D operations and readback.

## Experiments and decision gates

### R0 — emulator readiness and existing rendering correctness

- Existing LCDUI Graphics bitmap/Canvas tests, `M3GRuntimeTest`, and `M3GEglOwnershipTest`.
- The dedicated FBO probe checks actual 4x4 texture -> 4x4 FBO -> 8x8 surface output and verifies RGBA pixels after both passes.
- Success of these tests establishes only their scope: it does not prove the actual Android production `GLRenderer`, overlay, imported GLSLP, or physical-device performance.

### R1 — Libretro shader ABI and resource compatibility

- Compile the same original `.glsl` source twice using `VERTEX` and `FRAGMENT` defines.
- Bind proper uniforms and attribute layouts for the combined source ABI; verify output pixels.
- Compile and draw the existing production legacy `simple.vsh`/`simple.fsh` on the same GLES2 context without changing their names.
- Probe RGBA8, RGBA16F and RGBA32F FBO *completeness* on supported devices; log capability flags and actual results.
- Analyze last-pass viewport scaling, input cropping (backing bitmap may exceed content), and subsequent filtering.

### R2 — guest logical pixel invariants and mixed graphics

- Original MIDP `GraphicsPixelProbeMidlet.java` fixture tests fill, clipping, copyArea, and getRGB at native resolution. Compile is not evidence of execution.
- Build small deterministic 2D->M3G->2D and 2D->Mascot->2D fixtures that obey bind/release rules. Use 1x oracle screenshots / pixel values.
- For HD alternatives, check guest-visible pixel reads and unchanged Java ME Canvas logical dimensions at 1x versus 2x.
- Prefer a test that demonstrates a specific breakage over speculative per-game compatibility switches.

### R3 — performance cost and lifetime

- On a real physical GLES2/3 Android device, measure separate CPU drawing, GPU upload, GPU shader execution, glReadPixels and synchronization costs.
- Do not infer a physical GPU power/thermal benefit from an x86_64 emulator.
- Stress EGL context recreation and target reallocation after orientation/resolution change.
- Use the same fixture, backend and source revision for any A/B comparison.

## Stop rule

If an approach provably violates guest-visible semantics or offers no material measurable benefit, record the limitation and stop investing in it. The target is an actionable design decision, not maximizing experiment or test count. Production PRs must be scoped separately; temporary CI and diagnostics are disposable.


## Executed evidence — 2026-10-30, first native run

- Run: https://github.com/H3nb/JL-Mod-Plus/actions/runs/41034949573
- Source: `cdd8b8de1e4069c7f6c445fefe947b9f194ac1dd`, API 35 x86_64 emulator.
- Build and install: SUCCESS. GLES2 RGBA8 two-pass FBO test: **1 passed**.
- LCDUI `GraphicsCanvasStateTest`: **2 passed**.
- LCDUI `GraphicsBitmapRebindTest`: **1 passed**.
- Native M3G flag-validation method: **1 passed**.
- Native M3G `immediateRenderingExecutesOn64BitNativeBackend` began but gave no completion callback before the 55-minute job timeout; the remaining methods were not executed. **Full workflow conclusion: cancelled**, not success.
- The log contained `glClear ... GL err 0x500` and `glMatrixMode ... GL err 0x500` near native tests, but these lines alone are **not** evidence of the hang's root cause. The wrapper uses a single M3G executor, and the API call boundary still needs isolation.
- Collected run artifact: https://github.com/H3nb/JL-Mod-Plus/actions/runs/41034949573/artifacts/12970626280, including instrumented output, saved logcat, and exact source SHA.
- Next CI revision `dcdd078d63ebe6b9b34fe672a16838e8b480a357` executes each M3G method in its own instrumentation invocation, applies a **75-second per-method timeout**, captures logcat and requests Java thread stacks on a hang, then force-stops the research app.
- Distinguish a stopped test from a functional rendering regression. Do not change native production code until the method and blocked thread/native call are identified.

