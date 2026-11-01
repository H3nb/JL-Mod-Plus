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
merely texture upload success or an extension name. The executed follow-up run https://github.com/H3nb/JL-Mod-Plus/actions/runs/41065280377 showed complete sized GLES3 RGBA16F and RGBA32F framebuffer attachments. Float rendering/readback precision and real GPU performance remain unverified.



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


## Follow-up: preserve 2x4 logical extent in crop-normalization test

The first green genuine Libretro test (run 41074602946) normalized the
valid 2x4 content to a 2x2 FBO. Its vertically uniform source proved
removal of inactive-color leakage in the small fixture, but could not
prove preservation of vertical detail.

Follow-up **run 41076325469**, commit db06f5bafac74078bc61994f400a4e9ec6f3985f
(https://github.com/H3nb/JL-Mod-Plus/actions/runs/41076325469),
passed all six selected instrumented tests on Android API 35 x86_64
SwiftShader. The refined test uses an exactly matching 2x4 intermediate
FBO, distinct red/blue source rows and green inactive backing columns.

Its assertions checked: (1) direct bilinear UV-cropped sampling still
contains inactive color at the edge (green=106/255 from logcat);
(2) normalizing at the source's actual 2x4 logical dimensions retains
different red/blue rows; (3) the final 12x8 host viewport retains both
colors to its right edge while the host background remains unmodified.

This corrects the old fixture's resolution-loss blind spot. It does NOT
establish full-image equivalence, real Android Bitmap/GLUtils orientation
and alpha behavior, arbitrary GLSLP support, integrated GLRenderer
lifecycle or hardware performance. Compare a tightly packed source
upload with an extra GPU normalization FBO before selecting either.


## Actual Android Bitmap input and genuine Libretro multipass — 2026-10-30

- **Source-isolation A/B:** [run 41080969873](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41080969873), commit 907a5f96c893b31f6bd1aa606ae9b357641d92ef, passed all six selected API35 x86_64 SwiftShader instrumented tests. Android ARGB_8888 Bitmap source 4x4 with active 2x4 content was uploaded via GLUtils, versus a tightly cropped Bitmap 2x4. Real stock.glsl and bilinear filtering yielded byte-identical RGBA over the **entire 12x8 host game viewport** for FBO-normalized and cropped sources. Outside-game host background remained unchanged. Fixture used opaque colors, one source size and one emulator; neither per-frame upload cost nor semi-alpha behavior was measured.
- **True two-pass GLSLP:** [run 41082360028](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41082360028), commit fc2c0cfb9d6de7ece4c267338e0e37348cabf392, passed. Pinned Libretro blurs/gauss_4tap.glslp uses two distinct horizontal/vertical shaders. The probe kept both requested source-relative 1x FBO outputs at 2x4 and performed a separate host blit at 12x8. It did not silently reinterpret source relative as viewport relative.
- **Stronger oracle:** [run 41083533898](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41083533898), commit 78623af4e801380cb948f08cbb15eb97e665b7dd, passed all six instrumented tests. Source columns have different intensities, so the horizontal pass must change its 2x4 output. The test verifies that change and that the final Gaussian output differs from unfiltered stock rendering, closing the earlier uniform-input false-positive gap.

**Interpretation:** genuine Libretro source and a real two-pass preset work in a controlled GLES2-API pbuffer. These are not integrated GLSLP support, a general parser, valid #reference/LUT/temporal/parameter support, or EGL lifecycle evidence. No CPU, GPU, frame-time, thermal or power comparison was made.

**Source-verified cost/ownership trade-off:** GLUtils accepts a full Bitmap without a source rectangle. CPU cropping or row packing may require an extra copy and could add time while the current Canvas.GLRenderer holds bufferLock across source upload. A GPU normalization pass could instead occur after the source upload and lock release, but adds a GPU draw and render target. The least-cost decision remains open; when physical backing equals active content, neither path should be necessary.

**Stop gate:** isolated EGL pass feasibility is sufficiently characterized. The next meaningful trial is an R&D-only real Canvas.GLRenderer lifecycle integration (published-frame updates, logical content sizing, host layers, resizing, GL context loss and fallback). Benchmark source isolation on physical ARM64 before production selection. HD remains a separate target-compatibility problem; see ARCHITECTURE-CANDIDATES.md.


## GlesView lifecycle and integer HD pixel-equivalence gates — 2026-10-31

**GLES surface lifecycle:** [run 41157653910](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41157653910) failed before any rendering because the test host Activity was registered in the separate instrumentation APK/process. This was a test harness ownership mistake, not a shader failure. The disposable host was moved to the target APK for the research branch only. [Run 41158291554](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41158291554) on commit 307bf06940e09083e6119ed88fa5c78916d89936 executed and passed all seven instrumented classes, including the new lifecycle test. Logcat explicitly recorded `GLSurface lifecycle frames verified: initial, repeat, resize, context recreation`. The test uses the app's actual `GlesView` and `RENDERMODE_WHEN_DIRTY`, a pinned real Libretro stock/gauss shader chain, source normalization, FBO passes, an isolated host scissor, two viewport sizes (128x96 and 160x120), and forced EGL context recreation after pause/resume. Assertions checked observable color channels and GL errors, and that the GL surface initialization count advanced after resume.

**Scope limit:** the test runs its own disposable `GLSurfaceView.Renderer`, **not** the production `Canvas.GLRenderer` inner class, guest publication mailbox, `bufferLock`, screenshot, ambient sampling or overlay. It proves a real host-surface lifecycle on SwiftShader, not production GLSLP integration or frame-pacing/performance. The research-only `GraphicsRdHostActivity` and entry in the app manifest must be removed before any production integration.

**Integer-coordinate HD target:** [run 41158942239](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41158942239) on commit 56d96c191552800521889a5ad441b19e9501bd87 passed seven instrumented classes, including both `SupersamplingSemanticsResearchTest` methods. Its new control fixture draws the same opaque integer-defined operations into 12x10 (logical) and 24x20 (physical 2x) Android Canvas targets. It explicitly scales source dimensions when copying an area and returns the HD result to 12x10 with filtered downsampling. The complete **120 guest-visible pixels were identical** to the 1x oracle for fill, clipping, sprite and copy operations; logcat recorded `2x integer/clip/sprite/copy readback exact match on 120 logical pixels`. The existing fractional Android Canvas edge characterization still reports 71 changed of 1024 pixels, which is not normative MIDP integer-coordinate evidence.

**Interpretation:** exact logical readback for the selected opaque integer operations is feasible with explicit physical source mapping. This is a manually simulated host Canvas fixture, NOT an integrated JL-Mod Plus HD mode, normative full MIDP/J2ME conformance, semi-alpha/font/primitive equivalence, or M3G/Mascot compatibility. Current source consumers (screenshots, ambient sampler, non-GLES presentation, `Graphics.copyArea`, `Image.getRGB`, M3G target, Mascot pbuffer) remain adaptation gates.

**Stopping decision:** no more synthetic EGL or integer-rectangle tests are justified without a distinct unresolved invariant. Next meaningful work is a bounded real `Canvas.GLRenderer` integration experiment with frame publication and legacy fallback; HD needs a guest-facing 1x/2x correctness fixture including pixel reads after a mixed 2D/M3G/2D sequence, preferably on real ARM64 to avoid prior SwiftShader-native inconclusiveness. Physical-device profiling is still required before choosing CPU packing over GPU normalization or promising power/frame improvements.


## Executed real shader-source dialect probes — 2026-10-31

- [Run 41162334179](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41162334179), executable commit `aca4644dfed9e85573ae0a18338d10420c184036`, **success**. All seven selected instrumented classes passed (the real Libretro class executed two tests). Raw, pinned original shader source successfully compiled both stages and linked for `crt/shaders/crt-easymode.glsl`, `handheld/shaders/lcd3x.glsl`, and `fsr/shaders/fsr-pass0.glsl`. Raw desktop `#version 130` sources `handheld/shaders/lcd-cgwg/lcd-grid-v2.glsl` and `ntsc/shaders/ntsc-pass1-composite-3phase.glsl` failed compile as expected; logcat explicitly reported `'130': version number not supported`. The LCD Grid source also exposed errors from default constant parameter code without `PARAMETER_UNIFORM`.
- [Run 41163071776](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41163071776), executable commit `57fa5b0247690be220ca57cdd8f1f53c0498e33d`, **success**. The existing raw rejects remained, but mapping the pinned desktop `#version 130` to `#version 300 es` and defining `PARAMETER_UNIFORM` **successfully compiled both stages and linked** for LCD Grid v2 **and** NTSC composite pass 1. The pinned original shader body was otherwise unchanged. Logcat confirmed both adapted link successes.
- Both ran on Android API35 emulator with `OpenGL ES 3.0 (SwiftShader 4.0.0.1)` and `GLSL ES 3.00` despite requesting an ES2 EGL context. The tests prove **source compilation/link feasibility on that environment only**, not pixel output, correct uniforms, parameter values, frame history, full GLSLP preset parsing, GLES2 compatibility, physical GPU behavior or power.
- **Design conclusion:** a bounded, capability-sensitive version/stage/parameter compiler adapter is technically credible for this subset; replacing all desktop GLSL with arbitrary regex rewrites is not justified. GLES3 negotiation/fallback remains a separate integration gate. Retain the current legacy shader program contract and do not promote the disposable test to production unchanged.



## Executed production Canvas.GLRenderer baseline — 2026-10-31

[Run 41166769301](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41166769301) passed on executable commit `786b9a6683839cfa192b43f31a25289c4c22c768` (Android API35 x86_64 SwiftShader). All **eight** selected research instrumentation classes passed, **ten** test methods in total. The new `CanvasRendererPublicationResearchTest` directly instantiates the **actual** `Canvas.GLRenderer` and drives its standard `Canvas.flushBuffer` → `PresentationMailbox.publish` → `bufferLock` → bitmap upload → default legacy GL draw path. This closes a distinct gap from `GlesViewLifecycleResearchTest`, whose renderer is disposable.

**Observable proof:** two successive pbuffer EGL contexts each processed red then blue guest frames with exact center RGB readback and `GL_NO_ERROR`; the intermediary **host-only redraw** consumed the same published sequence without re-uploading a guest frame. An explicit check compared `lastUploadedSequence` to the Canvas publication sequence. The CI artifact logcat records:
- `GraphicsRD_RealCanvas: generation=0 published red=1 blue=2 host redraw preserved texture`
- `GraphicsRD_RealCanvas: generation=1 published red=3 blue=4 host redraw preserved texture`

**Necessary test boundary:** Creating an LCDUI `Canvas` normally requires a running `MicroActivity` to construct `SoftBar`. The research branch therefore adds a **disabled-by-default, test-only headless SoftBar fixture** that supplies an unattached `OverlayView` via the application context; it is enabled only while constructing the fixture and then disabled. The test deliberately calls the actual GLSurfaceView.Renderer callbacks directly with an EGL pbuffer, rather than mounting `Canvas.getDisplayableView` through a real running MIDlet. It verifies the game frame/publication/upload/draw implementation; it does **not** verify `ViewCallbacks`, runtime Activity lifecycle, display overlays, screenshots, skins, host background composition, all bitmap padding cases, actual GLSLP, physical GPU cost, or arbitrary concurrent producer races. Those remain separate gates.

**Architectural consequence:** A GLSLP renderer can consume the existing uploaded game texture and reuse the same mailbox; a second frame queue is not justified. Shader pass execution should occur **after** the source upload has left `bufferLock`; host-only redraw must not advance temporal history. The renderer must restore host framebuffer/viewport/scissor state and fall back to a validated simple renderer if a preset fails, rather than continuously rescheduling a failing game frame.

**Next discriminating gate:** a bounded Libretro multi-pass trial **inside** the actual Canvas.GLRenderer draw/lifecycle path, including a legacy fallback and host-background isolation. Do not mistake this legacy-path success for successful GLSLP integration. Remove the headless SoftBar seam and disposable tests before a production PR, and integrate against the latest alpha rather than merging the full research branch.


## Executed real Canvas.GLRenderer Libretro two-pass trial — 2026-10-31

- **[CI 41168266751](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41168266751) PASS**, executable SHA `ec203ccab339d6dbb9d6bfb805550e8be1b4bce2`. 8 selected instrumentation classes, 10 methods, all passed on API35 x86_64 SwiftShader GLES context. The original pinned `blurs/gauss_4tap.glslp` source stages `gauss_2tap_h.glsl` and `gauss_2tap_v.glsl` were downloaded into **androidTest assets only** (no shipping/vendor of third-party GLSL).
- This iteration adds disposable `LibretroTwoPassResearchPipeline` to `Canvas.GLRenderer` behind a default-off research source injection. It compiles real Libretro combined vertex/fragment stages with `PARAMETER_UNIFORM`, binds `SIGMA=1`, executes the horizontal pass to an 8×8 RGBA FBO, and vertically filters into the host game quad (6×6 in the pbuffer fixture). The production mailbox and source texture upload remain unchanged. **No generic `.glslp` parser or production shader selection was implemented.**
- Four observed EGL generations in one instrumented test: 0 legacy without GLSL; 1 real Libretro two-pass; 2 invalid GLSL compile rejected, legacy fallback; 3 Libretro two-pass rebuilt on a new context. Exact uniform red/blue reads passed; the red/blue split image yielded a mixed edge pixel **only under active two-pass**; outside-host pixel unchanged. The first pass executed exactly once for the red guest publication and **did not re-execute on host-only redraw**; ran again for blue and split-color publications. `GL_NO_ERROR` checks passed. Logcat confirms the deliberately invalid shader at generation 2: `Libretro research compile: ... 'invalid': syntax error`, followed by legacy rendering without test failure.
- The intermediate FBO, GLES program and source sampler are owned by the GL presentation path, and the prototype restores framebuffer 0 / host viewport / scissor / original source sampling after drawing or failure. Render execution occurs **after** `bufferLock` is released. No additional guest-frame queue was introduced.
- **Known limits:** The experimental shader pipeline explicitly rejects padded backing textures instead of processing them incorrectly with linear filtering. It only understands one hardcoded two-pass graph at source resolution; it does not parse `gauss_4tap.glslp` itself, support LUTs/history/feedback/float/sRGB/desktop-version mapping there, or prove full `MicroActivity`, overlay skin/video, screenshots, device GLES2 behavior or ARM64 performance. The research-only headless SoftBar seam remains in `Canvas.java`; **do not merge this branch wholesale**.
- **Next gate:** resolve padded source using an exact-size presentation bitmap versus a GPU normalized input FBO under real `Canvas` (including resize/softbar), then perform actual `.glslp` resource parsing and capability planning independently of the execution backend. Integrate through a clean production PR only after proving error handling, viewport/overlay ownership, and real device costs.


## Real Canvas softbar backing-versus-active extent experiment — 2026-10-31

**Evidence:** [CI run 41170967593](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41170967593), tested executable commit `9e2d03ecde4eb6e24587cf954753cce19fe37dd1`, **success**. All eight selected Android instrumentation classes passed, **eleven** test methods overall (the real Canvas publication class ran 2 tests). Run artifact `canvas-renderer-publication-logcat.txt` includes these exact observations from the emulator's actual `Canvas.GLRenderer` with non-fullscreen `SoftBar`:
- **Baseline padded:** `softbar scenario=0 logical=32x45 backing=32x48 multipass=false`. The pinned Gaussian GLSL pipeline rejected the mismatched backing dimensions and the legacy renderer displayed the frame; the shader draw failure/fallback was logged.
- **Exact allocation:** `softbar scenario=1 logical=32x45 backing=32x45 multipass=true`. The original Libretro Gaussian horizontal+vertical passes executed, readback was valid and `GL_NO_ERROR`.
- **Conversion of an existing padded buffer:** `softbar scenario=2 logical=32x45 backing=32x45 multipass=true`. The previously flushed red image retained its checked corner pixels after converting `offscreenCopy` to a new tight backing Bitmap; real GLSL drawing and GL validation passed.
- Host-only invalidation did not re-run the horizontal offscreen pass on the two valid shader scenarios. The **guest paint buffer** remained unchanged; only the `offscreenCopy` presentation image was made tight.

**Source-verified cause:** `Canvas.updateSize()` establishes `maxHeight` before `SoftBar.resize()` decreases the active `height`; `Image.setSize()` only changes logical bounds when shrinking, retaining the taller Bitmap. Therefore padded texture storage can exist from the **first frame**, even with no resize. GLSLP expects a correct `TextureSize` and full logical domain, so merely using cropped legacy UVs is not a generally correct shader input.

**Bounded R&D implementation:** `Canvas.setGraphicsResearchTightPresentationBitmap(true)`, disabled by default, allocates `offscreenCopy` at the active logical extent. On an actual backing-size mismatch it allocates a new tight presentation Image **once at geometry change**, copying only intersecting old *logical* content under the existing `bufferLock`. No frame queue, permanent crop Bitmap, or source-normalization GPU pass was added. Non-shader and default behavior are not altered. The test uses the headless SoftBar fixture and EGL pbuffer; it is not a real end-to-end MIDlet runtime test.

**Architecture decision at this evidence level:** Prefer **exact active-size presentation storage** as the primary GLSLP source-boundary candidate. A normalized source FBO is a conditional contingency, not the default. The prior isolated CPU crop and GPU FBO-normalization trials also produced correct output for their limited fixtures, but both add work or resources not needed for this softbar case. There are still no controlled physical ARM64 performance, memory-bandwidth, thermal, or frame-time measurements, so no numerical speedup is claimed.

**Remaining gates:** Size changes across different logical widths/heights, repeated fullscreen/softbar/orientation transitions, bitmap content and `Graphics` reset semantics in a real running MIDlet, raw/host screenshot and ambient paths, concurrent guest publication vs resize, physical GLES2/ARM64 devices, and other shader presets remain unproven. The current static toggle and test-only SoftBar host belong **only** in the disposable research branch; any shipping integration should make selection per Canvas/GLSLP capability and clean up the test hooks rather than merging this branch.


## Actual-geometry resize with active Canvas GLSL — 2026-10-31

**Passed executable checkpoint:** [CI 41175037707](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41175037707) on `34c8770738e0f55049846a1d56d6bcfc2f83d0e3`, Android API35 emulator / SwiftShader. All 8 selected instrumentation classes and 11 test methods passed, including the two genuine `Canvas.GLRenderer` R&D methods. First prototype run [41174503998](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41174503998) also passed; the second run isolated initial test geometry per scenario and corrected post-resize logging of the active backing image.

The new sequence operates on the **same EGL context and actual GLRenderer**, not just allocation before first GL frame:
- Start with an ordinary non-fullscreen Canvas at `32×45` active pixels (screen setting `32×48`), shader enabled with an exact-sized `offscreenCopy`. The original padded mode remains a separate legacy fallback case `32×45` in `32×48`.
- Call `setFullScreenMode(true)` on that live renderer; active height grows to 48. Check overlapping red pixel preservation, publish a host invalidation, and read back the same red game color after the shader processes its newly sized input.
- Change the logical configured width and height to `40×56`, call the real `Canvas.updateSize()`, check exact `40×56` backing, preserved source pixels, and **one** new first-pass execution/FBO reconstruction when only the input extent changes. Publish a new blue frame at `40×56` and verify its RGB readback.
- Return via `setFullScreenMode(false)`; verify `40×52` active logical content and `40×52` backing, retained blue pixel corners, redrawn blue output and **the same active two-pass pipeline** (no silent legacy fallback). The full sequence logged five first-pass executions as expected for geometry changes and new publications.
- The other isolated softbar scenarios still pass: padded input rejected safely; exact backing works from creation; a previously padded presentation image can be converted without losing checked logical pixel corners. No `GL_NO_ERROR` violation.

**What changed:** The R&D headless view has no mounted `GLSurfaceView`. `GLRenderer.updateSize()` now guards the optional view dispatch with `mView != null`, while still synchronously updating the shared quad; the normal mounted runtime always provides a view. This is a fixture-boundary correction, not a second lifecycle mechanism.

**Interpretation:** This is stronger evidence for **exact active-size presentation bitmap** as the simplest GLSLP input. It proves spatial shader execution after *live logical size changes* on the renderer path tested; it does not measure ARM64 GPU timings or prove real `MicroActivity`/view-queue ordering, guest paint and visibility callbacks, orientation changes, concurrent producer/resize races, screenshot/ambient semantics, or 2D/M3G/Mascot mixed draws. The experimental global setter and headless SoftBar constructor must not be promoted as-is. The remaining meaningful gate before production is a real mounted game host test, not another synthetic size matrix.

## Mounted GlesView + real Canvas shader/GL lifecycle — 2026-10-31

**[CI 41178625175](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41178625175) PASS**, executable commit `c02586000cd62e3149622ec9bda1ac325ecaf043`. Nine targeted Android instrumentation classes (**12 tests**) passed on API35 x86_64 emulator / SwiftShader. The `canvas-mounted-view.txt` instrumentation artifact reports `OK (1 test)`; the source-commit artifact confirms the exact tested revision. Logcat shows `GraphicsRD_Mounted: softbar host resize changed source=true from 32x38 to 32x40` and `Attached GlesView real Canvas shader verified: host resize, guest resize, host redraw, context recreation`.

**New distinct boundary:** `CanvasMountedGlesViewResearchTest` attaches the *actual* `Canvas.GLRenderer` to the app's `GlesView` in the already-existing `GraphicsRdHostActivity`. A test-only `GLSurfaceView.Renderer` wrapper invokes every real lifecycle/draw callback and only reads framebuffer pixels **after** the renderer returns. The game image is published through `Canvas.flushBuffer` and the existing `PresentationMailbox`; `GLSurfaceView.requestRender`, `queueEvent`, mounted-view resizing and `onPause/onResume` execute on Android's real SurfaceView/GL thread. The test reads the game-quad center and an outside-host pixel; it does not substitute a synthetic renderer.

**Observed invariants:** Red guest publication produces exact red pixel; a host-only invalidation redraw does not recompute the horizontal FBO pass. A mounted host resize with softbar changed the *actual source* from 32×38 to 32×40, so **one** input-pass recompute was required. After entering fullscreen, a distinct host resize to 176×132 left guest logical dimensions unchanged and **did not** rerun the first pass. Growth to guest 40×56 rebuilt the intermediate target while preserving red pixels; a newly published 40×56 blue frame read back as blue. After EGL context destruction/recreation, the currently published blue frame and the two-pass GLSL pipeline were reconstructed in the new context with exactly one new-context first-pass execution. The source Bitmap remained tight across all checked transitions; the outside-host pixel was not replaced by game red.

**Research debugging outcome:** [CI 41177581689](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41177581689) initially failed because a host resize was incorrectly assumed to leave softbar-adjusted guest dimensions constant. [CI 41178135991](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41178135991) then failed because the test consumed an older, queued GL callback with the same host dimensions and guest-frame sequence *before* observing the new logical size. These are **fixture expectation/selection defects** identified by source and log evidence; neither demonstrated a new shader rendering regression. The passing test waits for the expected actual guest extent instead of sleeping or loosening the pixel oracle.

**Limits and decision:** This is a real attached Android `GlesView` and the production `GLRenderer`, but setup still uses a headless research `SoftBar` and reflection to bind the renderer/view. It **does not** instantiate `MicroActivity`, call the complete `Canvas.getDisplayableView` + `ViewCallbacks` lifecycle, execute a real JAR MIDlet, or measure ARM64/GPU performance. Combined with earlier pbuffer trials, it supports a single presentation GLES owner, exact-active-size `offscreenCopy`, mailbox-aligned guest-frame processing, and viewport-only reuse. No additional FBO normalization or frame queue is justified by current evidence. The next useful engineering work is the **safe GLSLP preset/resource resolver** with bounded support tiers and explicit rejection; a separate runtime MIDlet smoke gate remains necessary before release.

## Real xBRZ Freescale source/dependency and FBO orientation gate — 2026-10-31

**Source:** original Libretro `xbrz/xbrz-freescale-multipass.glslp` at pinned
`cba01a48b0974a1232bea68d999a7c21bc4c233e`. Its source-sized pass 0
produces per-pixel encoded RGBA edge decisions; viewport-sized pass 1 consumes
both the immediately previous pass's `Texture` and the pre-pass original
via `PassPrev2Texture`. Both inputs require nearest sampling; pass 1 also
requires the correct original `PassPrev2TextureSize` and
`PassPrev2InputSize`. The existing Gaussian-only previous-pass pipeline
did not satisfy this contract.

**Executed checkpoint:** [run 41220537772](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41220537772),
executable SHA `b44d30b0429d12c24eb489bec605119b3e1cfac8`,
**PASS**: nine selected instrumented classes, **13 tests**, on Android API35
x86_64 / SwiftShader. The new `realXbrzFreescaleUsesOriginalTextureAndReusesFirstPass`
fixture uses the real `Canvas.GLRenderer` with `Canvas.flushBuffer`,
an 8x8 red triangle on blue guest bitmap and a 96x96 host game viewport.
It verifies shader link/no legacy fallback, the original color inputs,
nonzero encoded FBO metadata, actual corner flags, mixed red/blue output
pixels, no GL error and exactly one analysis-pass execution across a
host-only redraw. Other research classes, including the previously passing
mounted-`GlesView` lifecycle class, remained green.

**Discriminating failure and correction:**
- [run 41218815077](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41218815077)
  compiled and ran both original xBRZ programs, yet all 9,216 viewport
  pixels remained pure nearest-like red/blue: **2,160 red**, **7,056 blue**,
  **zero blended**. The earlier Gauss tests remained green.
- [run 41219613185](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41219613185)
  established **20 nonzero encoded FBO bytes** but still **zero blended**
  pixels. A nonempty first-stage output alone therefore did not prove
  the second stage was using its spatial data correctly.
- Source review identified a real R&D quad mismatch: bitmap-upload texture
  row 0 corresponds to UV y=0, while the first-pass draw placed that row
  at the **top** of an FBO, whose UV y=0 subsequently reads the **bottom**
  row. Consequently the second pass read corner metadata at a different
  vertical position than `PassPrev2Texture`. Flipping the offscreen quad's
  source V mapping (without changing the final host quad) aligns both
  textures in their sampled domain.
- With this one orientation correction the passing run recorded **20 nonzero
  edge-decision bytes**, **9 actual corner-flag bytes**, **66 mixed**
  red/blue output pixels, **2,073 red**, and **7,077 blue**. The counts sum
  to 9,216 target pixels. They prove a real observable xBRZ transformation
  for this fixture, not universal visual parity with RetroArch.

**Scope and constraints:** the prototype intentionally injects exactly two
pinned GLSL source strings; there is **no GLSLP parser, asset resolver,
user-facing selection or dynamic parameter binding**. It uses upstream
default `#pragma` parameter values rather than `PARAMETER_UNIFORM`, so
`xbrz-crisp.glslp` overrides are not yet supported. Its extra source
sampler, nearest-filter selection and one source-resolution FBO are
**fixture-specific**, not a general graph implementation. In particular
the current Gaussian prototype still renders its final pass directly to
the host viewport although that preset specifies a source-sized final
pass; a genuine planner must honor the preset's actual last-pass size
and perform a separate host blit when needed.

**Next discriminating gates:** implement an independent, safely bounded
GLSLP resource resolver + pass descriptor (including stage sizes,
referenced original/earlier-pass textures and named parameter defaults/
overrides). Exercise real `xbrz-crisp` parameters and one ES3-dependent
xBR preset instead of assuming source-level success. Keep host/GPU resource
ownership on the existing presentation GL thread. Prove an actual MIDlet
through `MicroActivity` and measure GPU latency, upload bandwidth and
thermal/power cost on physical ARM64 before shipping. HD 2D/M3G/Mascot
guest logical-pixel research remains a separate open track.

## Bounded GLSLP preset planner on JVM — 2026-11-01

**[CI 41251825986](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41251825986) PASS**, executable commit
`6fcec69ec1458aa8b296e3a528e26d2ff6b28541`.
The focused `:app:testEmulatorDebugUnitTest --tests
io.github.h3nb.jlmodplus.research.GlslpPresetResearchTest` completed
**4 tests / 0 failures / 0 errors / 0 skipped**. This was an Android
Gradle JVM unit-test run on the GitHub Ubuntu runner, **not** Android
instrumentation, shader compilation, an Android GLES runtime, or a physical
GPU benchmark. The artifact `graphics-rd-preset-jvm-evidence` contains
the JUnit result XML for that exact revision.

**Experiment:** `GlslpPresetResearch` parses a bounded, explicit GLSLP
metadata subset and computes a separate source/viewport/absolute output
size for each pass. It records named preset overrides without applying
them to GPU uniforms. Test cases use minimal synthetic source text shaped
after *actual pinned* xBRZ Freescale/Crisp, 4xBRZ Linear, Gauss and FSR
preset metadata. They verify a `../stock.glsl` relative dependency,
FBO size and filter distinctions, source-to-viewport transitions,
independent X/Y scales and fractional truncation. Invalid/malformed
presets, unsupported keys (including LUT/feedback), `#reference`,
traversal outside the logical bundle root and over-limit sizes fail
closed. A separate read-only audit of the original pinned preset files
confirmed representative key names, not complete Libretro compatibility.

**Discriminating correction:** [CI 41251486190](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41251486190)
built successfully and passed three of four tests, but the FSR test
incorrectly expected no final blit when the numeric last-pass resolution
matched the viewport. Original `fsr.glslp` specifies
`scale_type1 = source`, and RetroArch `gl2.c` chooses final FBO usage
from the **declared scale mode**, not only pixel-size equality. The
planner already preserved that distinction; only the stale test
expectation changed. The next focused CI run passed all four tests.

**Qualification:** this is evidence of metadata parsing and sizing
semantics only. The root-confining `AssetReader` implementation for
real imported files, shader `#pragma parameter` extraction and GPU
uniform binding, `PassPrev2` resource lifetimes, per-pass actual
texture allocation, shader language translation, runtime EGL lifecycle,
and complete `MicroActivity` operation remain unimplemented/untested.
The existing real xBRZ SwiftShader experiment remains a separate
proof of GPU rendering with hardcoded sources. Do not declare general
GLSLP or xBRZ Crisp runtime support from these JVM results.

## xBRZ Crisp pragma/defaults-to-GPU gate — 2026-11-01

**Executable SHA:** `92e23fde719cf6755e6beb912884f7658a2e7870`.
**[JVM run 41252673920](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41252673920) PASS**
(4 targeted tests, zero failures/errors/skips) and
**[GLES run 41252709399](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41252709399) PASS**
(API35 x86_64 emulator/SwiftShader, 9 selected research classes).
The attached `canvas-renderer-publication.txt` reports 3/3 tests passing,
including the extended real `Canvas.GLRenderer` xBRZ trial.
The logcat fixture recorded **261 changed RGB pixels out of 9,216** on a
static 8x8 low-contrast grayscale staircase presented at 96x96.

**What this distinguishes:** the R&D test loads the genuine pinned
`xbrz-freescale-multipass.glslp` and `xbrz-crisp.glslp`, including their
two real shader source files, via the bounded `GlslpPresetResearch`
reader. It resolves all five declared `#pragma parameter` defaults
(`XBRZ_TOL=15`, `XBRZ_DOM=3.8`, `XBRZ_STEEP=3.2`,
`XBRZ_MINC=0`, `XBRZ_DIAG=0`), validates range and applies the
two Crisp preset overrides (`XBRZ_MINC=24`, `XBRZ_DIAG=100`).
With `PARAMETER_UNIFORM` compiled into the **same upstream GLSL**, the
test reads back **all five** `glGetUniformfv` values from the actual
pass-0 GL program. It then compares framebuffer RGB of default and
Crisp modes using an **identical published guest image**. A re-created
research pipeline recomputes its first pass once; a subsequent host-only
redraw does not recompute it. Both the earlier red/blue edge fixture
and preexisting renderer research checks remain passing.

**Architecture consequences:** supplying *only the overridden uniforms*
would leave the three other xBRZ controls at zero in
`PARAMETER_UNIFORM` mode; source defaults are therefore mandatory.
Source declarations and preset overrides need one resolved parameter
contract before GL program creation. This experiment tested static
pipeline replacement, **not** efficient in-place parameter updates,
which will require dependency-aware cache invalidation. Parameter
uniforms are not proof of correct GLSLP execution for presets with
more than two passes, alternate scale semantics, LUTs, temporal history
or mixed format capabilities.

**Limits:** 261 changed pixels prove an observable shader effect, **not**
that Crisp is visually preferable to Freescale for real games. The
original Libretro shaders were downloaded into the disposable androidTest
APK, not vendored into the shipping application. The Android test used
a headless research Canvas fixture and controlled pbuffer, not a real
`MicroActivity` executing a MIDlet. There are no physical ARM64
latency/battery measurements or J2ME HD compatibility results.

## Actual GLSLP pass plan controls Canvas GPU output — 2026-11-01

**[CI 41255596735](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41255596735) PASS**:
executable SHA `348d0a5ac7b932a3c8f0bfbce4753d7c6561dbaf`.
All **9 selected research classes / 13 executed instrumentation tests**
passed on an Android 35 x86_64 SwiftShader emulator. Test APK assets were
downloaded from pinned `libretro/glsl-shaders@cba01a48`; no third-party
shader source was added to production.

The real `Canvas.GLRenderer` now exercises a bounded two-pass executor
driven by actual GLSLP descriptors rather than fixture-specific pass
sizing. `gauss_4tap.glslp` has **two source-sized passes**; the second
writes an RGBA8 FBO followed by pinned `stock.glsl` presentation to a
smaller game viewport. The source-sized final-pass test confirms both
offscreen stages execute once for each new guest frame and both cache
their results across host-only redraw; red/blue edge blending and host
background separation remain observable. `xbrz-crisp.glslp` has a
source-sized analysis stage and viewport-sized final stage. The planner
avoids the second FBO and its rendered RGB bytes equal the previously
verified hardcoded Crisp pipeline on the same published guest frame.

The **initial** [CI 41254724452](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41254724452)
passed all three actual Canvas pbuffer tests but failed the separate
attached-`GlesView` guest growth test: while Canvas logical geometry
had become 40×56, the GL draw saw the previously published 32×48 bitmap
and permanently discarded its R&D pipeline. This exposed a cross-thread
source-snapshot race, not an unexpected Gaussian/xBRZ shader result.
The final change takes `offscreenCopy` Bitmap **and its logical content
extent** within the same `bufferLock`. The mounted test now accepts
transient old-size presentations only while waiting for a genuinely
recomputed, correctly sized shader frame; it does **not** weaken the
requirement to retain the pipeline, keep content/pixels, resize correctly
or survive EGL context recreation. Its previously failing scenario
passed in the final CI run. A deliberately padded source bitmap still
falls back to legacy as intended.

**Evidence limit:** this proves the two distinct preset-stage topologies
and an attached-view resize/context boundary on SwiftShader, not
arbitrary GLSLP presets, GPU throughput, real MIDlet operation, power
or true HD internal rendering. The two-pass executor still uses the
existing research-only injection path and has no production preset
selection/import. Default shader behavior remains unchanged.
