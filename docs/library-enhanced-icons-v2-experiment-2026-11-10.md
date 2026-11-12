# Enhanced Library Icons — PR #238

Status: **Draft experiment**. This describes the candidate implementation and its
qualification requirements, not an approved release contract.

## Behavior and ownership

- `LibraryComposeBridge.kt` owns icon decoding, bitmap analysis, normalization, bounded
  caching, and Compose rendering. `LibraryIconPresentation.kt` owns the pure
  `Subject`, `Backed`, `Cover`, `SafeFit`, and `Fallback` decisions.
- The original icon file and metadata remain unchanged. Icon revisions invalidate cached
  presentation results. Existing decode sampling, bounded LRU (8–24 MiB), and
  three-worker normalization limit are retained.
- Disabling enhancement preserves the bitmap and uses `SafeFit`. Explicit portrait
  artwork retains its original composition and may reuse detected background color.
  Missing icons retain their vector fallback; a fully transparent source uses the normal SafeFit path.
- For `Cover`, crop only when at least 95% of each source dimension remains visible
  at the slot aspect ratio; otherwise use `Fit` to preserve edge content.
- A `Cover` can use the source's border color for a visible letterbox when a
  candidate color is confirmed by both corner samples and samples across all four
  edges. Otherwise use the current theme container color. Do not infer a solid
  background from four matching corners alone.
- Keep pixel-art filtering and source pixels unchanged. Do not add universal
  sharpening, MMPX/xBRZ, synthetic backgrounds, or destructive segmentation.

## Normalization cost

- Skip hue/average-color scans when presentation already supplies a background or
  requires no synthesized background.
- Track at most 65 distinct quantized colors in a lazily allocated 4,096-bit set,
  avoiding boxed per-pixel hash-set entries.
- Sample corners into bounded primitive arrays, retaining the lowest-quantized-bin
  tie rule. Avoid a 4,096-bin histogram for a small sample set.
- Skip dominant-color histogram passes when `Cover` classification or a colored
  corner already determines the relevant result.
- Keep decode and analysis off the main thread. Avoid adding a new cache, state
  owner, or image-processing abstraction without evidence of a net benefit.

## Verification

[Dedicated benchmark workflow](../.github/workflows/icon-quality-benchmark.yml)
builds the target branch and PR head from exact SHAs and executes the **same**
Android normalization probe on both revisions, using an API 35 x86_64 emulator.
It also runs `LibraryIconNormalizationTest` on the candidate. Regular Android
CI only compiles Android instrumentation tests.

[Expected visual changes](../scripts/icon-quality/expected-visual-changes.json)
enumerates the intentionally changed fitted-cover policies. Strict A/B comparison
rejects missing evidence and any other changes to normalized pixel hashes,
presentations, slot previews, or tile colors. Source PNGs, output PNGs,
JSON/Markdown measurements, raw samples, and an HTML gallery are uploaded as
`enhanced-icon-quality`.

The benchmark reports per-icon batch-averaged normalization thread-CPU P50/P95
and thread allocations. Input Bitmap allocation is outside the timed interval.
Measurements are exploratory: fixed-order emulator tests are sensitive to JIT
and host load, and do **not** measure image decode, scrolling FPS, total
application memory/PSS, GPU cost, power, or physical-device performance.

Representative real MIDlet JARs were inspected locally (including 12–16px
pixel-art/transparent icons and 32–48px photographic/antialiased artwork);
none of the owned game assets is committed.

## Pre-merge gate

- Inspect the gallery and compare representative **real** MIDlet icons on a device
  in square and portrait slots, light and dark themes, and with enhancement
  both enabled and disabled. Synthetic slot previews do not reproduce final
  Compose clipping, filtering, or themes.
- Check Library icon refresh, cancellation, list/grid transitions, and scroll
  performance on a physical Android device.
- Compare the final effective PR diff with current `alpha`; remove superseded
  code and temporary workarounds, run relevant checks, and complete an
  independent pre-merge review.
- Keep the PR Draft until these checks are satisfied. Do not merge without
  explicit authorization.
