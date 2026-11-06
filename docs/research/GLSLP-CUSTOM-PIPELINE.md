# Scoped shader file selection and custom pass configuration

This draft extends PR #224 without merging. Shader selection uses the existing
file-picker Activity with an explicit shader-root boundary and single-tap
selection, not the former shader-list dialog.

## User-visible modes

- **Off** is the OpenGL ES default: the game continues through the internal stock
  presentation shader, with no extra GLSL effects. A new game configuration
  defaults to OpenGL ES with no selected effect. Changing renderers does not
  delete a saved shader choice.
- **Legacy shader** selects a named effect in the existing legacy catalog
  dialog. Existing .ini / .vsh / .fsh selections and their settings remain
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
  wrap/mipmap/float-framebuffer flags, LUTs and parameter overrides. Editing a
  pass source keeps the original pass metadata; unsupported dependencies are
  rejected instead of silently approximated. A custom shader derived from GLSLP
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
  own draft until Save, and older profiles retain their existing selection.
- Display scroll state is owned by the parent configuration surface rather than
  the removable shader subpage, so Back does not reset the Display scroll.
- Legacy ShaderSelection fields, .ini parsing, profile semantics, and old
  standalone GLSL selections remain intact. An unsupported shader fails back
  to the stock presenter, subject to PR #224's remaining runtime diagnostics.
- GLSLP resource paths are relative to shaders/, including nested directories.
  Canonical root confinement, source-size limits, and the existing GLSLP
  validator still apply.

## Qualification

Run focused JVM tests covering root traversal and extension filtering, legacy
INI multi-variant resolution, and GLSLP/custom nested paths; also run Android
build, lint, screenshot, and representative Compose interaction tests.
Validate GPU context loss, real-game softbar/viewport resize and actual ARM64
performance before marking the stacked PR ready to merge.
