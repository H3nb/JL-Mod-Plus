# Custom GLSL chain — stacked prototype

Status: **draft**. Branch feat/custom-glslp-pipeline extends PR #224, not alpha.

## User behavior

- Default shaders remain off. Existing legacy shader and external GLSLP preset selections are not rewritten.
- Shader effects > Custom pipeline opens a draft. Add, remove, or reorder external standalone GLSL stages (up to 16), choose per-stage input filter, and set source-relative 1x, 2x, or 4x on intermediate stages.
- The final stage draws to the actual game viewport. Back/Cancel discards the draft; Save writes one config selection per game or named profile through existing form ownership.
- Complex .glslp presets remain opaque and retain their own pass order. External shader directories are read; there is no installer, ZIP import, or new bundled third-party catalog.
- Save validates source paths, sampler dependencies, and GLSLP parameter metadata on an IO dispatcher before changing the form. The actual GPU still compiles at runtime; real-device compilation and pixel accuracy remain qualification requirements.

## Implementation boundary

- Profile ShaderSelection stores optional CustomStages. Old serialized selections stay valid and the profile authority/ownership contract remains responsible for durable writes.
- GlslpAssets.loadCustom resolves an in-memory GLSLP preset. It uses the same GlslpPreset planner and GlslpPipeline executor as packaged or external GLSLP.
- Source files are never modified. A chain accepts only standalone .glsl sources from allowed locations, and rejects additional texture samplers rather than silently breaking multi-input dependencies.
- No new EGL context, presentation queue, feedback/history textures, internal resolution, M3G or Mascot rendering changes.

## Important limits

- Custom chains are not general GLSLP graph editing. Source shader assumptions and GLSL dialect still require GPU validation, and the current editor does not edit individual shader pragma parameters inside a custom chain.
- Missing/invalid files produce a configuration error; runtime falls back to identity. A non-fatal shader failure must not interrupt Java ME rendering.
- PR #224 has separate prototype Libretro assets and licensing questions. They are not custom-chain requirements; remove nonessential third-party assets before shipping.

## Required qualification

Run focused JVM tests for config round-trip, safe paths, stage order and size, Android build/lint, and representative UI checks. On physical Android ARM64, verify both legacy and external GLSLP rendering, context recovery, resize, softbar/fullscreen, and performance before changing status to production-ready.
