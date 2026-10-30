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



## Executed evidence — 2026-10-30, GLES-only fast run

- Run: https://github.com/H3nb/JL-Mod-Plus/actions/runs/41041327515
- Source: `d26c3f0b42d2952d88bd5f40a577a7420619d5ac`.
- Result **SUCCESS**. Android API 35 x86_64 emulator, `jlmodNativeBuild=false`.
- GLES2 `GraphicsPipelineResearchTest` executed one two-pass FBO/RGBA readback test: **1 passed**.
- `ShaderAbiResearchTest` loaded the shipped legacy shader `shaders/simple.vsh`/`simple.fsh` and an independently authored Libretro-style conditional GLSL source. It checked the distinct old and Libretro attribute/sampler names and the output colors in all four quadrants: **1 passed**.
- The original legacy source/persisted user format was unchanged. This proves the two *small identity shaders* can work on an ES2 context, not that arbitrary external GLSL, GLSLP multipass, `#reference`, GPU FP render targets, temporal feedback, or production Android UI work.
- Next fast run `76cdfe56730f1ea6da990c71609d6c7dd776d24c` also includes a Mascot Capsule offscreen LCDUI `Image` bind/flush/release readback probe. It does **not** submit 3D geometry and must not be generalized to full Mascot compatibility.



## As-is LCDUI HD feasibility constraint — Image is not resolution-agnostic

Static source evidence from `javax/microedition/lcdui/Image.java` and `Graphics.java`:

- `Image(Bitmap, boolean)` initializes its `bounds` using `bitmap.getWidth()/getHeight()`; `getWidth()/getHeight()` then expose those bounds directly.
- `Image.getRGB()` delegates straight to `Bitmap.getPixels()` using *guest-provided coordinates*. `Image.createImage(image, x, y, w, h, transform)` directly calls `Bitmap.createBitmap(...)` using guest-provided crop dimensions.
- `Graphics(Image)` creates an Android `Canvas` over the backing bitmap, clips to `Image.getBounds()`, and performs Android Canvas raster operations.
- `Image.setSize()` can resize/replace the underlying bitmap and reset cached graphics; tests already protect bitmap-rebind behavior.

**Implication:** replacing every `Image` bitmap with an N-times larger backing bitmap is *not* a local change to the presentation pipeline. At minimum it affects logical bounds, source rects, getRGB, drawRGB, copyArea, image cropping/Sprite operations, mutable offscreen graphics, and cached Canvas transforms. Whole-frame supersampling remains a legitimate experiment, not the default architecture choice.

Do not confuse this observation with evidence that whole-frame supersampling is impossible; it only quantifies the compatibility surface that must be handled coherently. For a less invasive experiment, test a higher-resolution *presentation-only* or *supported-renderer* target separately, while explicitly acknowledging which actual rasterization detail cannot be recovered from the original logical bitmap.



## Comparative implementation evidence — J2ME-Loader-HD

Reference code: https://github.com/fuyijian/J2ME-Loader-HD

- `Image.java` adds a `pixelScale` independent of logical `bounds`; mutable image bitmaps can be N times larger. It catches `OutOfMemoryError` when allocating a scaled mutable image and falls back to 1x.
- `Graphics.java` applies `Android Canvas.scale(pixelScale,pixelScale)` before drawing guest operations and explicitly adjusts 1-logical-pixel strokes. `Image.getRGB` and Graphics pixel getters downsample rectangular areas using `Bitmap.createScaledBitmap(...,true)` (bilinear), which needs compatibility scrutiny for exact pixel-readback games.
- `Graphics3D.bindTarget(Graphics)` uses `finalG.getPixelScale()`, expands clip/viewport coordinates by that factor, and leaves the native bitmap target at physical dimensions. **Its `Image2D` branch explicitly resets `targetScale=1`**; this reference does not establish universal HD for all M3G rendering targets.
- The fork's documentation also warns that Mascot Capsule HD was not validated.

**Interpretation:** this is credible evidence that higher-resolution LCDUI/M3G rendering is technically implementable with dual logical/physical dimensions. It is *not* evidence that its guest-visible pixel colors, mutable-image crop, compositing ordering, offscreen Image2D, or device performance match the normative J2ME contracts. Treat it as a candidate design with known scope limits, not production code to copy wholesale.



## Capability evidence qualification: requested ES2 vs actual GL version

From the *executed* first R&D logcat (run 41034949573), the test requested an
`EGL_CONTEXT_CLIENT_VERSION=2` context and used GLES20 APIs, but the emulator
actually reported:

- `GL_VENDOR: Google (Google Inc.)`
- `GL_RENDERER: Android Emulator OpenGL ES Translator (Google SwiftShader)`
- `GL_VERSION: OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)`
- `GL_MAX_TEXTURE_SIZE: 8192`

Therefore, the tests prove a **GLES2-API-compatible rendering path on this
emulator's ES3/SwiftShader implementation**. They do *not* prove that a strictly
ES2-only GPU driver supports the same shader/performance behavior, nor do they
establish real mobile GPU power efficiency. This distinction must accompany
future claims about the ES2 baseline.



## Rejected experimental test boundary — Mascot without a host

- Run: https://github.com/H3nb/JL-Mod-Plus/actions/runs/41042414793, source `76cdfe56730f1ea6da990c71609d6c7dd776d24c`.
- In this fast/no-native CI, GLES FBO and legacy/Libretro ABI tests **passed** before `MascotReadbackResearchTest` began.
- The Mascot test failed **before validating any rendered pixel**. `Render.release()` first touched `micro3d.Utils`, whose static initializer calls `PreferenceManager.getDefaultSharedPreferences(ContextHolder.getActivity())`; a bare Android instrumentation test has no `MicroActivity`, producing `NullPointerException` inside class initialization.
- The same initializer calls `System.loadLibrary("micro3d")`, but the fast test deliberately uses `jlmodNativeBuild=false`. A synthetic Activity or fake JNI dependency would make the test unrepresentative.
- **Classification: invalid test setup / wrong boundary; not a verified Mascot render bug.** Restore the two passing GLES-only checks in fast CI. The next Mascot probe must use an attached `MicroActivity` or a supported runtime fixture and a native-enabled build, then verify offscreen pixel orientation and bind/release semantics.
- Do not change the production `Utils` initializer solely for this artificial hostless scenario. Separately assess whether its Activity dependency is a real lifecycle weakness in supported runtime paths.



## Fast R&D revalidation after removal of invalid Mascot fixture

- Run: https://github.com/H3nb/JL-Mod-Plus/actions/runs/41043552238
- Source: `3c42499cd6671a0ead650a3ab2a758b2f58b9828` (before subsequent docs-only commits).
- Result **SUCCESS**: real API 35 emulator instrumented executions: FBO probe **1/1 passed** and shipped-legacy-vs-Libretro-style shader ABI **1/1 passed**.
- No production renderer, profile schema, or legacy shader file changed. This confirms the fast CI was restored after removing the invalid hostless Mascot test.
- M3G follow-up remains a separate native-enabled CI, not covered by this green result.



## Native isolation and source audit — 2026-10-30

Source-verified: `m3g_gl.h` includes `<GLES/gl.h>` and M3G
`m3g_rendercontext.c/.inl` uses GLES 1.x fixed-function
(`glMatrixMode`, `glOrthox`, `glClientActiveTexture`, `glTexEnvx`).
This is a fundamentally different graphics program from the GLES2 Mascot
and GLES2 presentation contexts. It is not evidence that GLES1 is inherently
broken or a reason to rewrite the engine by default.

Run https://github.com/H3nb/JL-Mod-Plus/actions/runs/41053290226
at `42d40a0451200c86bfd71449f8b28cd7661107d3` isolated the two
M3G cases on *separate* API35 x86_64 emulators:

- **Immediate triangle test**: instrumentation began and reached setup,
  native library loading, triangle preparation, bindTarget, setCamera,
  clear, render and the log immediately before `releaseTarget()`.
  No test completion or step after release appeared. The ADB transport
  disappeared / device list was empty by final diagnostics.
  Source shows `m3gReleaseTarget` calls `m3gMakeCurrent`, `glFinish`,
  optional `m3gUpdateTargetBuffer`, GL cleanup, and context release.
  This narrows the last observable boundary to the release path but
  **does not yet identify the precise native instruction**.
  The streamed log is available in the run artifact.
- **Image2D case**: instrumentation output was completely empty; this
  execution never reached a recorded test method. The host-streamed
  device log ended during emulator boot. `adb devices` showed the
  emulator online at the end, but the instrumentation command exited
  255. Classify **infrastructure / launch inconclusive**, NOT a
  second reproduced Image2D failure.

The prior combined run
https://github.com/H3nb/JL-Mod-Plus/actions/runs/41042049268
passed translated/clipped Graphics target output before becoming offline
during the Image2D selector. Its log did not establish the exact failure
location either. Do not conflate unrelated failures with one root cause.

For deeper evidence, R&D commit `7fdbceec5ccb18276dc320ac6ce5c34a41c0c15c`
temporarily adds JNI and core release-stage markers, then runs only
the immediate test on a fresh emulator. Independently, the fast CI adds an
explicit GLES1 context/GL fixed-function probe. Probe outcomes must be read
before drawing a backend-modernization decision.



## Differential root-cause hypothesis — native library loading in Android instrumentation

Streamed log from run
https://github.com/H3nb/JL-Mod-Plus/actions/runs/41053290226
shows the same native library name loaded from two different locations:

1. The instrumentation test `M3GRuntimeTest.loadTargetNativeLibraries()`
   extracts and explicitly `System.load`s `libc++_shared.so` and
   `libjavam3g.so` under `code_cache/m3g-runtime-test-native/x86_64/`.
2. `Platform.uiThreadAvailable()` (production M3G Java entry point) calls
   `System.loadLibrary` and the log also shows the corresponding libraries
   loading from `base.apk!/lib/x86_64/` in the target class-loader namespace.

These are distinct path/classloader load events; this does **not** by itself
prove duplicate ELF instances, cross-DSO pointer corruption, or native-symbol
binding to the wrong module. It does establish that the research harness does
*not* simply execute the normal guest loading sequence.

**Highest-value differential experiment:** run an otherwise identical bounded
M3G test with ONLY the production library-loading path. If it fails with an
`UnsatisfiedLinkError` due instrumentation restrictions, classify that as a
harness problem and design an app-owned runtime fixture instead. If the release
stall vanishes, investigate library ownership/duplication before touching the
M3G renderer. If it persists, follow the JNI/core `M3G_RD` trace markers and
verify on a physical Android GPU. Do not infer a production bug from an
instrumentation-only loading path.



## Four passing independent graphics probes — 2026-10-30

Run: https://github.com/H3nb/JL-Mod-Plus/actions/runs/41054406134
SHA `b1bf1badc6fe880a5d5c7ad99325224d2f6134cb`.
All 4 *executed* API35 x86_64 instrumentation tests passed:
`GraphicsPipelineResearchTest` 1/1; `ShaderAbiResearchTest` 1/1;
`LegacyEs1ContextResearchTest` 1/1;
`SupersamplingSemanticsResearchTest` 1/1.

Emulator reports `OpenGL ES-CM 1.1` with EGL client version exactly **1**
using the independent fixed-function test, and `OpenGL ES 3.0 SwiftShader`
with the GLES2 API test. This establishes that the emulator implements
**a working GLES1 pbuffer**. It does **not** validate JL-Mod Plus M3G's
native EGL configuration/memory targets, target synchronization or driver
behavior in all workloads.

The 32x32 native vs 2x Android Canvas rasterization/downsample experiment
recorded **71 changed logical pixels out of 1024**, including **71 partially
covered pixels** after downsampling of a subpixel diagonal line. It
characterizes the expected guest-visible color trade-off; it neither proves
that a particular MIDlet violates its spec nor that HD 2x is undesirable.

## Release-stage trace revision — 2026-10-30

Run https://github.com/H3nb/JL-Mod-Plus/actions/runs/41054712353 at
`7fdbceec5ccb18276dc320ac6ce5c34a41c0c15c`:
the *immediate M3G* test went offline after the marker
`immediate: render triangle`, **before** the
`immediate: release target` marker. No `M3G_RD` JNI-release log was
reached. Prior run `41053290226` reached
`immediate: release target` but no later marker. This is not
consistent enough to blame `glFinish` or target-release alone.

Both logs show separate `libjavam3g.so` load events:
extracted code-cache path from instrumentation, then APK native path from
production M3G Platform loader. This is a *confounder hypothesis*, not a
verified crash explanation.

Research commit `5ac6ec6fa370d8e09d835b75432ad93b1f528168`
adds an isolated immediate test that **skips test-only native preload**
and relies on normal `Platform` loading. It also corrects the earlier
misplaced optional `collectGLObjects` trace to the actual release path.
Review that specific CI run before claiming the root cause.


## JNI-loading differential and native clear trace — 2026-10-30

- Run [41055942209](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41055942209)
  disabled manual preload of `libjavam3g.so`. Android loaded the
  target APK native library normally and the test reached `g3d.clear()`,
  then exited 255 after becoming nonresponsive. **Manual double-path preload
  is not necessary to reproduce the failure.** It is not proved to be
  irrelevant in other scenarios.
- Run [41063347467](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41063347467)
  traced the public `m3gClear()`, background validation,
  `m3gClearInternal`, `m3gMakeCurrent`, and viewport/scissor state.
  The last logged statement is `clear: before lockFramebuffer`; there
  is no corresponding subsequent marker.
- Crucial static audit: `m3gLockFrameBuffer(ctx)` in
  `m3g_rendercontext.inl` expands to
  `M3G_ASSERT_NO_LOCK(M3G_INTERFACE(ctx))`. This expands to
  `M3G_ASSERT(!m3gMemoryLocked(interface))` in the selected non-NGL
  branch. `m3g_defs.h` compiles `M3G_ASSERT` to a no-op unless
  `M3G_DEBUG_ASSERTS` is defined; the current `m3g/Android.mk` uses
  `-O3 -Werror -DM3G_TARGET_ANDROID`, not the debug assertions.
  **Therefore no active framebuffer lock is evidenced at that log marker.**
  It is incorrect to attribute the hang to that macro.
- The in-method native thread (distinct from the Java instrumentation
  thread) stops yielding logs near this point. Multiple runs stop at
  different stages. Device-offline events also occurred during boot
  in isolated CI. Classify **native/runtime/emulator instability with
  undetermined root cause**, not an established M3G algorithm defect.
- Research stop condition: do not issue more identical emulator replays.
  Validate a minimal M3G fixture on a physical ARM64 Android device or
  alternative GLES1 implementation, capture native crash/tombstone and
  per-stage GPU timing, then re-open only a hypothesis supported by that
  differential evidence. Debug-only logging must be deleted before
  production integration.

## Normative MIDP 2.0 raster constraints — J2ME Dev Kit

Primary source: `J2ME_Docs.zip`,
`docs/midp-2.0/javax/microedition/lcdui/Graphics.html` and
`Image.html`. These say:

- All coordinates are **integers** and denote pixel boundaries.
  `fillRect(0,0,3,2)` affects exactly six logical pixels; `drawLine(0,0,0,0)`
  touches the one corner pixel under SOLID stroke.
- Source pixels are composed using **Source Over Destination**.
  A fully opaque source replaces the destination, fully transparent leaves
  it unchanged, and semitransparent source blends.
- All mutable image pixels are **fully opaque**. `Image.getRGB()`
  exposes ARGB data of the specified *logical image region*.
- The 71/1024 changed-pixel 2x test draws a **fractional-coordinate
  Android Canvas line**, which does not directly represent MIDP 2.0's
  integer-coordinate drawing API. It is useful evidence that filtering
  can alter observed pixels, **not a normative compatibility failure**.
- Whole-target supersampling remains a candidate: its implementation
  must demonstrate that integer logical coordinate bounds, area fills,
  opaque mutable target behavior, image source regions, clipping,
  translation, drawing order and defined pixel operations still work.
  The reference implementation's bilinear downsampling is not by itself
  sufficient proof of these contracts.

## Presentation-side low-risk optimization research

`Canvas.GLRenderer.onDrawFrame` currently calls
`GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)` whenever its
published-frame sequence advances, even if dimensions are unchanged.
Reusing the existing storage with `GLUtils.texSubImage2D` may reduce
driver allocation churn, but not CPU→GPU transfer bytes.
`TextureUpdateResearchTest` compares both outputs across changing
RGBA/alpha data and explicit resize; the test's CI status must be
checked before reporting correctness, and physical-device benchmarks
remain necessary for a claimed speed/power improvement.


## Successful GLS presentation experiment — 2026-10-30

Run [41063931268](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41063931268),
source `a5bb362ec8d30ee749dd7aed77e066bf7c15e941`:
five Android API35 x86_64 instrumentation classes executed and passed:
`GraphicsPipelineResearchTest`, `ShaderAbiResearchTest`,
`LegacyEs1ContextResearchTest`,
`SupersamplingSemanticsResearchTest`, and
`TextureUpdateResearchTest` (one method each). No claim of performance
improvement follows from pixel-equivalence execution alone.

**Texture upload:** `GLUtils.texSubImage2D` for unchanged dimensions
returned byte-identical framebuffer pixels compared with
`GLUtils.texImage2D` redefining storage across five distinct 4x4
Bitmap.ARGB_8888 frames with alpha. Both paths agreed after reallocation
for an 8x8 bitmap. This is one supported test environment, not a complete
pixel-format or chipset matrix. A production change would require proper
surface/context-loss/size invalidation, source ownership, and real-device
benchmarking.

**Float FBO:** SwiftShader exposes
`GL_OES_texture_half_float`,
`GL_EXT_color_buffer_half_float`,
`GL_OES_texture_float`, and
`GL_EXT_color_buffer_float`. GLES2-style `glTexImage2D` with
**unsized `GL_RGBA`** plus either `HALF_FLOAT_OES` or `GL_FLOAT`
succeeded with no GL upload error but produced
`GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT (0x8CD6)` when attached to
an FBO. **Do not call this GPU incapable of floating-point FBOs**:
attachment renderability depends on the chosen internalformat, not
merely texture upload success or an extension name. A follow-up probe
on actual ES3 path uses sized `RGBA16F`/`RGBA32F` and is pending
at run https://github.com/H3nb/JL-Mod-Plus/actions/runs/41065280377.



## Executed genuine Libretro GLSLP input and presentation-crop boundary — 2026-10-30

- **Run:** https://github.com/H3nb/JL-Mod-Plus/actions/runs/41074602946
  on commit 2652e6d80d8a6f1093e6ede994d1cc9d421c49c9.
  All six selected instrumented tests **passed** on Android API 35 x86_64
  SwiftShader GLES3 (using GLES2 API calls). The new probe uses genuine
  upstream libretro/glsl-shaders stock.glsl and bilinear.glslp pinned to
  cba01a48b0974a1232bea68d999a7c21bc4c233e, downloaded only for
  the disposable Android test APK. This does not prove arbitrary presets
  or integration into the production Canvas.GLRenderer.
- **Source crop:** a 4x4 backing texture with only a valid left 2x4 red
  subrectangle and inactive green right 2x4 area was sampled using
  cropped UVs (u=0..0.5). The real bilinear preset's linear filter gave
  **green=106/255** at the host game viewport's right edge; the interior
  remained pure red and pixels outside the game viewport remained blue.
  UV cropping does not constrain texture filter footprints to a
  subrectangle. This fixture is synthetic; no real-game artifact was shown.
- **Offscreen route:** first drawing the logical source into a 2x2 GLES2
  FBO with nearest sampling, then stretching to the 12x8 game viewport
  with linear filtering, kept the probed right-edge pixels red. Host
  scissor did not clip the offscreen pass after deliberate state changes.
  This proves the small composition, **not** that an extra normalization
  pass is optimal, supports arbitrary shaders, or is fast on real GPUs.
- **Floating FBO correction:** in the same executed artifact logs, unsized
  GL_RGBA + OES half-float or float generated incomplete FBOs (0x8CD6);
  GLES3 sized RGBA16F and RGBA32F produced complete FBOs (0x8CD5).
  These cases verify upload/FBO completeness only, NOT fragment draws,
  readback precision, speed, or strictly ES2-only device compatibility.

**Next discriminating research:** compare a tightly packed logical-content
texture upload versus a GPU normalization FBO for backing>content,
using the real renderer publication cadence and representative resizes.
Avoid treating a shader-coordinate clamp as generic compatibility: external
shaders may sample outside interpolated input UVs. Prefer a source that
physically excludes inactive pixels, if this is affordable. Profile CPU
copy/upload, GPU pass time, memory, and temperature on physical ARM64.
The production-like GLRenderer lifecycle/ambient/controls gate remains open.
