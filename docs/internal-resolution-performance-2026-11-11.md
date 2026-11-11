# Internal-resolution image reconstruction performance — 2026-11-11

This dated study documents temporary PR-only performance instrumentation,
removed from the production source after qualification. Original raw results
remain in the linked GitHub Actions artifacts and source commits; they are
not part of the permanent Android instrumentation suite.

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

## Phase-split profiling and working-set experiment

[Run #43010892989](https://github.com/H3nb/JL-Mod-Plus/actions/runs/43010892989)
measured 12 runs after three warm-ups per image/mode. A 240x320 source
reconstructed at 3x produced the following median **phase times** (ms):

| Android emulator | Mode | Read/allocate input | Scaler | Create Bitmap | Draw |
| --- | --- | ---: | ---: | ---: | ---: |
| API 23 | MMPX | 0.278 | 18.181 | 1.784 | 0.502 |
| API 23 | xBRZ | 0.281 | 39.206 | 1.945 | 0.513 |
| API 35 | MMPX | 0.218 | 12.387 | 1.117 | 0.801 |
| API 35 | xBRZ | 0.216 | 20.645 | 1.225 | 0.786 |

The scaler, not `getPixels` or Bitmap creation, dominates cold source
work. The read phase includes the input `int[]` allocation; the measured
draw uses the experimental host canvas target, not a physical-device
GPU presentation pipeline.

An independent nine-image alternating-source experiment (same 240x320
source and 16 MiB LRU budget) captured first draw, immediate cache hit
and subsequent revisit after the other eight sources:

| Emulator | Mode | 3x first / warm / revisit (ms) | 2x first / warm / revisit (ms) |
| --- | --- | --- | --- |
| API 23 | MMPX | 20.547 / 0.758 / 20.883 | 24.450 / 0.109 / 0.230 |
| API 23 | xBRZ | 44.021 / 2.436 / 43.677 | 52.013 / 0.091 / 0.167 |
| API 35 | MMPX | 19.274 / 0.780 / 14.990 | 17.113 / 0.157 / 0.279 |
| API 35 | xBRZ | 26.346 / 2.814 / 26.322 | 29.824 / 0.166 / 0.328 |

At 3x, each result requires 2,764,800 bytes (about 2.64 MiB), so a
16 MiB cache fits at most six such results. Nine require about 23.73 MiB;
the first source is evicted and is reconstructed upon revisit. At 2x,
each result requires 1,228,800 bytes (about 1.17 MiB), so all nine fit.
The 2x run uses a **2x internal target** versus a **4x target** for the
3x run; its cached draw timings also reflect target size and sampling
differences. They are not a direct 2x→4x visual-quality comparison.

[Run #43013480213](https://github.com/H3nb/JL-Mod-Plus/actions/runs/43013480213)
also compared direct scaler costs at 2x and 3x within the same test run:

| Emulator | Mode | 2x scaler median (ms) | 3x scaler median (ms) |
| --- | --- | ---: | ---: |
| API 23 | MMPX | 19.419 | 21.736 |
| API 23 | xBRZ | 46.096 | 50.762 |
| API 35 | MMPX | 14.325 | 15.989 |
| API 35 | xBRZ | 24.868 | 27.588 |

Reducing result size by 56% saves roughly 9–11% of direct scaler time
on these synthetic test sources; neighborhood analysis dominates cost.
The more material gain for a nine-image working set is fewer evictions.
This does **not** justify silently reducing enhancement quality for all
games: 2x→4x and 3x→4x image composition need separately reviewed
pixel/visual fixtures. For MMPX with Nearest, 2x→4x provides an exact
integer presentation ratio whereas 3x→4x does not; this is a hypothesis
about edge uniformity, not yet an accepted production policy.

### Negative micro-optimization result

Commit `8365745e` tested squared color-distance comparisons to avoid
some square roots in CPU xBRZ. A seeded Java comparison of 2 million
color pairs detected no predicate differences, and existing unit/native
tests passed. However [A/B run #43012245687](https://github.com/H3nb/JL-Mod-Plus/actions/runs/43012245687)
showed **no gain**: xBRZ 240x320/3x median was 42.820 versus 39.206 ms
on API 23 and 21.388 versus 20.645 ms on API 35; these are separate
emulator runs, not isolated hardware benchmarks. MMPX control timings
also varied. The change was **reverted** in `84d37340`; no speculative
speedup was retained.

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
3. Phase-split emulator measurements and a nine-source eviction fixture
   now exist. Next, collect repeated warmups, p50/p95/p99 frame times,
   allocations and cache hit rates on physical ARM64 with representative
   MIDlet working sets. Do not increase cache capacity solely from
   these emulator results.
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
