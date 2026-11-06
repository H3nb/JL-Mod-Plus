# Scoped shader file selection and custom pass configuration

This draft extends PR #224 without merging. Shader selection uses the existing
file-picker Activity with an explicit shader-root boundary and single-tap
selection, not the former shader-list dialog.

## User-visible modes

- **Off** is the OpenGL ES default: the game continues through the internal stock
  presentation shader, with no extra GLSL effects. A new game configuration
  defaults to OpenGL ES with no selected effect. Changing renderers does not
  delete a saved shader choice.
- **Legacy shader** selects one .ini file from shaders/ (including nested
  folders). A multi-section INI lists its shader variants inline on the
  configuration page; selecting one saves its original vertex/fragment pair
  and legacy settings. No GLSL pipeline conversion is attempted.
- **GLSLP preset** selects one .glslp file from the same scoped picker. It
  uses all passes and dependencies declared by that preset, without changing
  their internal ordering.
- **Custom pipeline** uses a fixed pass count chosen from 1–16 and one .glsl
  source picker per pass. Intermediate scaling (1x, 2x, 4x) and input filtering
  remain optional. An incomplete chain cannot be saved.
- There is no default third-party shader bundle, ZIP installer, preset source
  editor, or new GPU presentation architecture. The internal stock GLSL remains.

## State / compatibility

- The reusable file picker accepts an optional strict root. It does not expose
  parent navigation outside the canonical shader directory and only returns
  in-root files. An additional result validator checks canonical location and
  allowed extensions. For this mode the picker has no checkboxes and requires
  only one tap to choose a file.
- Custom stages persist through the existing ShaderSelection.CustomStages
  configuration field; the editor keeps its own draft until Save.
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
