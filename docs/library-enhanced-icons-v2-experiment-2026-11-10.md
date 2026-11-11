# Library enhanced icons v2 — experiment plan (2026-11-10)

Status: **experiment only**. This document scopes work on `experiment/library-enhanced-icons-v2`; it is not an active behavior contract or evidence of completed optimization.

Baseline: `alpha` at `d0923ba11bf4df4bbdd0febf6388c8b8d62b2a01`.

## Goal

Improve the visual consistency of legacy MIDlet icons while lowering the CPU and allocation cost of preparing them. Preserve the original files and avoid destructive crops, unpredictable filtering, or regressions in Library scrolling, custom artwork, and icon edits. Make no performance claim until matched A/B measurements exist.

## Current ownership and compatibility boundaries

- `app/src/main/java/io/github/h3nb/jlmodplus/applist/LibraryComposeBridge.kt` owns decoding, bitmap analysis, normalization, LRU cache, and Compose rendering.
- `LibraryIconPresentation.kt` owns the deterministic `Subject`, `Backed`, `Cover`, `SafeFit`, and `Fallback` decisions.
- A supplied portrait 3:4 composition stays aspect-preserving; missing icons use the vector fallback, not bitmap analysis.
- The user may disable enhancement. Icon replacements and resets must invalidate the displayed icon through `iconRevision`, even for the same path.
- Existing decode bounds, worker dispatching, the three-permit semaphore, and the bounded 8–24 MiB LRU remain unchanged until profiling justifies modifications.
- Do not modify persisted source icons, MIDlet resources, or import/export semantics. Preserve the rendering path on Android versions below API 28.

## Verified optimization candidates

1. `normalizeLibraryIcon()` computes a representative color even when the mode or earlier color evidence makes that result unnecessary. Measure and gate the expensive fallback computation; preserve the selected tile color exactly in this phase.
2. Bitmap analysis scans the same image repeatedly for global features, framed foreground, corner matte, and dominant/representative colors. Share computed features where the result can be proven identical. Do **not** impose a single pass when later steps depend on earlier classifications.
3. `LibraryNormalizedIcon` retains fields not read by the current artwork renderer. Remove unused state only after checking all owners; avoid unnecessary cache invalidation.
4. Pixel-art detection uses decoded dimensions, which can vary with the requested slot size. First construct an actual regression case; then qualify a stable filtering policy with visual output, memory, and compatibility checks.
5. Physical cropping versus source-rectangle rendering is undecided. Compare peak retained pixels and latency: retaining a full source bitmap can cost more memory than retaining a cropped result.

## Validation before changing production output

First establish a characterization corpus:

- Transparent sprites (dense circular, sparse, elongated, off-center, semitransparent), logos with margins, solid/gradient/photographic backgrounds, text-at-edge cover artwork, transparent and opaque pixel art, antialiased graphics, extreme aspect ratios, and empty/corrupt inputs.
- Include both 1:1 and 3:4 slots, original 3:4 override, light/dark themes, and multiple slot sizes.
- Use a small deterministic set of generated test fixtures plus independently reviewed, appropriately reusable real MIDlet icons. Do not silently commit copyrighted game artwork or redraw screenshot references.

For a **behavior-preserving optimization**, compare the old and new normalized pixel dimensions/ARGB hashes, presentation mode, visual scale, tile color, and filter quality for the same source, slot size, and enhancement setting. Add a focused rendered-component test where bitmap properties alone cannot detect a crop, shape, or compositing regression. Differences must fail this gate.

For an **intentional visual improvement**, capture baseline and candidate rendered output side by side, classify each difference by a known defect and expected improvement, and review regressions before accepting renderer-produced golden images. Never promote screenshots merely to clear CI.

Cover lifecycle separately: toggling enhancement, switching list/grid sizes and 1:1/3:4, replacing an icon at the same path and revision bump, resetting icons, scrolling rapidly, and cancelling an offscreen load. Existing `LibraryIconPresentationTest` and `LibraryIconRefreshTest` are useful but do not provide comprehensive enhanced-artwork characterization; current Library screenshot previews primarily use missing-icon placeholders.

## Reproducible performance experiment

- Run baseline SHA and candidate SHA on the **same device**, Android build, ABI, build variant, workdir fixture, display settings, and recorded icon corpus. Record actual revision and APK identifiers; do not compare unrelated debug/release builds.
- Measure cold and warm Library entry plus controlled list/grid scroll with stable item counts, icon sizes and ordering. Separate decode, normalization, cache-hit behavior, Compose rendering, and total interaction/frame cost.
- Record normalization thread-CPU P50/P95, allocations and GC, cache hit/miss counts, peak heap/PSS, first-image latency, and actual FrameTimeline overrun/P95/P99. Use matched repeats and report variability, not just a best run.
- Historical evidence in [app UI performance investigation](app-ui-performance-2026-10-08.md) observed significant CPU normalization cost and a prior row-batching improvement. That PR-specific probe and workflow have since been retired; do not describe their measurements as this branch's baseline or assume they can be rerun unchanged.
- Use official Perfetto/Android tooling and current build/test instructions in [development.md](development.md). Keep profiling instrumentation opt-in and out of shipped paths unless its continued value is demonstrated.

## Experiment phases and decision gates

1. **Characterize:** add targeted bitmap and rendered-icon regression fixtures; record baseline output and reproducible CPU/memory traces without changing production behavior.
2. **Simplify:** remove redundant color computations and unused state, then consolidate pixel work. Require exact output parity and a measured cost improvement before retaining an optimization.
3. **Improve presentation:** address proven threshold instability, incorrect optical sizing, or wrong backgrounds one class of icon at a time. Require reviewed visual improvement and no destructive crops or significant memory regression.
4. **Optional filters:** consider source-aware resampling only when real artwork shows measurable value. Do not revive universal MMPX/xBRZ sharpening or background segmentation by default; the previous global enhancement experiment in PR 77 was rejected after device review.
5. **Qualify:** run the targeted tests, Android UI verification and appropriate CI on the final effective diff; perform independent pre-merge review before proposing squash merge to `alpha`.

Do not silently change the icon enhancement default, add new UI controls, introduce a generic image framework, or alter persistent artwork. If the optimization does not improve real workloads without harm, keep the existing implementation rather than add complexity.

## Dedicated A/B quality and normalization benchmark

The experiment adds a focused workflow: [Enhanced Icon Quality Benchmark](../.github/workflows/icon-quality-benchmark.yml). It is automatically triggered by relevant icon/benchmark changes in pull requests targeting alpha; manual dispatch becomes available when the workflow exists on the GitHub default branch. It is **not** part of the normal Android CI or its required checks.

- The workflow checks out exact PR base and head SHAs and compiles the **same** instrumentation probe into two disposable worktrees, including the unmodified baseline production algorithm. It runs both on one API 35 x86_64 emulator. Never infer execution from AndroidTest compilation.
- Synthetic fixtures cover transparent sprites, solid mattes, photographic gradients, 48-color pixel art, antialiased subjects, sparse artwork, portrait overrides, disabled enhancement, and fully transparent images.
- The test captures original/normalized PNG, bitmap ARGB hash, classification mode, visual scale, filter, synthetic tile color, and normalization thread-CPU and thread-allocation samples. The companion Python comparator emits JSON/Markdown tables, a local HTML A/B gallery and full raw artifacts.
- PR runs use **strict pixel and presentation parity**. The comparator fails on differences, missing fixtures, missing images, or malformed measurements. Review mode (for intentional future visual changes) can report differences without treating them as a pass on quality; review the PNGs and intended artwork presentation manually.
- CPU changes are **descriptive**, not performance gates: emulator scheduling, thermal state, order (baseline first), JIT, and reflection overhead can bias results. These samples exclude decode, Compose frame rendering, interaction FPS, GPU time, PSS, and real device behavior. Use equivalent physical-device trials before claiming product-level performance gains.
- Download the **enhanced-icon-quality** artifact from the workflow run; inspect **index.html**, **report.json**, **report.md**, **environment.txt**, raw samples, test logs, and source/normalized PNGs. The GitHub step summary also shows median CPU and allocations per fixture.
- The workflow is an experiment-specific qualification path. Reassess its CI cost and continued usefulness once Enhanced Icons v2 is integrated; do not silently turn noisy emulator timing into a required long-term performance check.
