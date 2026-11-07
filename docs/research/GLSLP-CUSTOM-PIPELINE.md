# Scoped shader file selection and custom pass configuration

External Legacy INI and GLSLP preset selection uses the existing file-picker
Activity with an explicit
shader-root boundary and single-tap selection. The existing named Legacy
catalog chooser remains available when assembling a custom Legacy pass.

## User-visible modes

- **Off** is the OpenGL ES default: the game continues through the internal stock
  presentation shader, with no extra GLSL effects. A new game configuration
  defaults to OpenGL ES with no selected effect. Changing renderers does not
  delete a saved shader choice.
- **Legacy shader** selects one .ini file using the scoped picker. A file with
  multiple valid effects lists those variants inline; a single valid effect
  is selected directly. A broken variant does not hide valid sections from
  that file. Existing .ini / .vsh / .fsh selections and their settings remain
  compatible with older profiles.
- **GLSLP preset** selects one .glslp file from the same scoped picker. It
  uses all passes and dependencies declared by that preset, without changing
  their internal ordering.
- **Custom pipeline** offers GLSL and Legacy types. GLSL uses the scoped .glsl
  picker, Legacy uses the existing named shader dialog for each pass. Each pass
  has its own source(s), tuning values, and optional filtering or scaling;
  incomplete chains cannot be saved. Up to 16 passes are supported.
- Opening Custom from an active GLSLP preset first resolves its original chain,
  including nested source paths and #reference inheritance, and initializes
  exactly the same number of pass slots (e.g. ten GLSLP passes -> ten custom
  stages). Unedited passes retain source/viewport/absolute size rules, aliases,
  wrap/mipmap/float-framebuffer flags, LUTs and compatible parameter overrides.
  Editing a pass source keeps non-source metadata while discarding parameter
  overrides whose declarations were removed or whose ranges no longer allow
  the saved value. Unsupported dependencies are rejected instead of silently
  approximated. A custom shader derived from GLSLP
  retains its original preset path as the base; custom source paths are deltas.
- Opening Custom from a legacy shader creates one stage with its vertex,
  fragment, name and values. Additional Legacy stages are compiled through a
  bounded GLES2-compatible adapter with separate per-pass u_setting uniforms.
  This adapter needs physical GPU qualification for visual equivalence.
- There is no default third-party shader bundle, ZIP installer, preset source
  editor, or new GPU presentation architecture. The internal stock GLSL remains.

## State / compatibility

- The reusable file picker accepts an optional strict root. It does not expose
  parent navigation outside the canonical shader directory and only returns
  in-root files. An additional result validator checks canonical location and
  allowed extensions. For this mode the picker has no checkboxes and requires
  only one tap to choose a file.
- Custom stages persist through ShaderSelection.CustomStages + CustomMode;
  legacy per-stage values use additional optional fields. The editor keeps its
  own draft until Save, including unsaved stages and parameter tuning across
  Activity state restoration; older profiles retain their existing selection.
- Display scroll state is owned by the parent configuration surface rather than
  the removable shader subpage, so Back does not reset the Display scroll.
- Legacy ShaderSelection fields, .ini parsing, profile semantics, and old
  standalone GLSL selections remain intact. An unsupported shader fails back
  to the stock presenter. The runtime logs the unsupported GPU feature or shader error.
- GLSLP resource paths are relative to shaders/, including nested directories.
  Canonical root confinement, individual source limits, the 2 MiB aggregate
  shader-source budget, and the existing GLSLP validator still apply.
  Custom sources extending a GLSLP preset can use only bound 2D sampler inputs
  (the source texture, causal earlier passes, inherited aliases and declared
  lookup textures); unknown samplers and unsupported sampler types fail
  validation before saving. Active samplers are also checked after GLSL linking
  to reject unsupported bindings introduced by macros.

## Supported runtime subset

The renderer supports 1–16 causal GLSL passes, bounded GLSLP #reference
inheritance, scalar parameters, and up to four PNG/JPEG lookup textures.
Intermediate FBOs and mipmaps share a 64 MiB allocation budget. Device
capabilities gate float framebuffers, border clamping, non-power-of-two
sampling and mipmap filtering. Unsupported GLSL features fail back to the
internal stock presenter; there are no bundled third-party presets.

## Qualification

Run focused JVM tests covering root traversal and extension filtering, legacy
INI multi-variant resolution, and GLSLP/custom nested paths; also run Android
build, lint, screenshot, and representative Compose interaction tests.
Validate GPU context loss, real-game softbar/viewport resize and actual ARM64
performance with external test shaders before merging. No experimental GPU
workflow or third-party test shader bundle is part of this integration.
