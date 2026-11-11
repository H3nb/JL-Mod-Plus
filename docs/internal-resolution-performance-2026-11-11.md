# Internal-resolution image reconstruction performance — 2026-11-11

## Scope and evidence

PR #230, source commit `9fd8863fafd1411790b44e649ef959cb7d7d7f5e`,
introduces the temporary `BitmapUpscalingPerfProbeTest`. This test
draws synthetic 3-color opaque images through the actual LCDUI
`BitmapUpscaling` path on 4x internal render targets. It measures one
uncached draw and the fastest of three immediately repeated draws on
that same `Graphics` and source, in microseconds. The target's resulting
physical dimensions are asserted. The shared source-reconstruction policy
selects a factor independently of the destination scale, within a 4 MiB
RGBA result budget.

Evidence: [Android API 23/35 instrumented run #42977854123](https://github.com/H3nb/JL-Mod-Plus/actions/runs/42977854123),
with per-test `logcat-...BitmapUpscalingPerfProbeTest-...` files in
`bitmap-upscaling-pr-api23` and `bitmap-upscaling-pr-api35` artifacts.
This same run passed the 2D compatibility and native M3G/Micro3D tests.

## Observed cost (ms)

| Source | Reconstruction | API 23 MMPX cold / cached | API 23 xBRZ cold / cached | API 35 MMPX cold / cached | API 35 xBRZ cold / cached |
| --- | ---: | ---: | ---: | ---: | ---: |
| 64x64 | 4x | 3.947 / 0.019 | 2.651 / 0.019 | 17.715 / 0.022 | 21.597 / 0.022 |
| 128x128 | 4x | 5.245 / 0.065 | 9.435 / 0.043 | 15.958 / 0.090 | 10.563 / 0.078 |
| 240x320 | 3x | 33.846 / 0.442 | 44.896 / 2.304 | 18.492 / 0.748 | 28.011 / 2.209 |
| 422x208 | 3x | 27.630 / 0.501 | 50.232 / 2.714 | 15.187 / 0.820 | 28.206 / 2.374 |

**Interpretation:** large uncached images can consume more than a nominal
16.7 ms 60 FPS frame budget. After caching, these same draws can be
orders of magnitude cheaper. xBRZ's partial-factor presentation uses
bilinear filtering, while MMPX uses Nearest, so warm values measure
different presentation samplers as well as cache behavior.

**Limitations:** one sequential measurement per size and algorithm;
the first samples include class-loading, JIT and other startup costs.
The result measures the entire LCDUI draw, not exclusively scaling.
The Android hosted emulator is x86_64 with API-dependent emulator
software/drivers; it is not a calibrated throughput benchmark, not
a p95 latency distribution and not a physical ARM64 frame pacing test.
Sources are synthetic; palette, atlas and animation working sets vary
between games. No claims of a fixed device FPS or universal speedup
follow from this table.

## Working-set implications

An output image of 240x320 at 3x occupies roughly 2.64 MiB in ARGB_8888.
During a CPU miss, separate source arrays, result arrays and Bitmap
storage may coexist. Multiple large working-set images can therefore
evict one another under the current 4-16 MiB LRU budget.

A static inventory of the user's private original MIDlets identified
large mixed JSR-184/PNG and LCDUI sprite corpora; totals across all
archived images must **not** be mistaken for concurrently resident
textures. No original game, firmware or proprietary source bytes were
committed to the project.

## Architecture decision and next experiment

1. Preserve the independent Internal Resolution and source
   reconstruction factors. Do not collapse enhancement into
   whole-frame post-processing or force MMPX/xBRZ to match 10x targets.
2. Preserve current guest-pixel contracts and the simple synchronous
   cache while gathering stronger data. Do not introduce a background
   queue merely because one uncalibrated emulator cold sample exceeds
   16.7 ms: deferred fallback needs a repaint/invalidation policy for
   static frames and consistent first-frame appearance.
3. Next, measure repeated **independent cold sources** after warmup,
   split `getPixels`, scaler, Bitmap creation and Canvas draw timing,
   and collect p50/p95/p99 and allocations on physical ARM64.
   Capture cache hit/eviction rates under representative source
   reuse rather than raising the budget without working-set evidence.
4. Compare a capped reconstruction policy against any deferred
   alternative using pixel-output fixtures and actual MIDlet scene
   recordings, then decide whether the quality/runtime tradeoff is worth
   increased state/concurrency.
5. JSR-184 M3G texture enhancement must be investigated at
   `app/src/main/cpp/m3g/src/m3g_image.inl`'s
   `m3gBindTextureObject` boundary. That code owns texture uploads,
   palette/compressed formats, dirty reloads, mipmap regeneration and
   image-data lifetime. Do not modify guest `Image2D` or bypass
   `Texture2D` filter/wrap/mipmap rules as a shortcut.

This evidence is research-stage only; PR #230 remains Draft.
