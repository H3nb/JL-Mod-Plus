# Static SVG artwork

JL-Mod Plus accepts SVG artwork as an additional image input for three app-owned
customization flows. This does **not** implement the Java ME JSR-226 SVG API.

| Use | Picker | Persisted representation | Runtime |
| --- | --- | --- | --- |
| Screen skin | MIDlet Config → Display → Skin → Import | Original `.svg` in `skins/` | Bitmap rasterized on MIDlet launch; existing transparent-screen frame detection |
| Virtual controls | MIDlet Config → Controls → Customization → Skin | Original `.svg` per slot in `skins/controls/<set>/` | Display-sized bitmap snapshot with the existing 24 MiB total budget |
| Library icons | Library → Edit Metadata → Main/Portrait icon | Canonical PNG override | Existing icon loading and Library revision/transaction rules |

Import SVG through the existing file picker. There is no SVG-specific rendering
setting or hot reload. Screen and control skin sources remain editable SVG files;
Library icon imports are normalized to PNG, like other custom icon sources.
The existing main (1:1) and optional portrait (3:4) icon selection rules remain
unchanged. The selected skin-set UUID and keyboard layout stay unchanged.

Only **static self-contained SVG artwork** is in scope. AndroidSVG 1.4 provides
the parser and canvas renderer; unsupported effects (including SVG filters),
animations, scripting, DTD/entity declarations and external resources are not
supported. SVGs need positive finite dimensions or a `viewBox`, and must be
no larger than 8 MiB. Vector coordinates are not pixel allocation dimensions.
Files with invalid geometry, parse failures or unacceptable memory requirements
may be rejected or use existing built-in skin fallbacks.

The complete transparent SVG canvas is preserved: do not trim transparent padding.
Screen skin transparency determines the MIDlet content frame; control D-pad
overlays must share the full base canvas. Normal/pressed images, opacity, input
hitboxes, resize behavior and editing semantics are format-independent.

The SVG source is parsed only during preparation, never on the per-frame
rendering path. Control bitmaps are planned and published using the existing
whole-set quality/budget logic; no parsed SVG document is retained across a
live MIDlet session. Changes to global skin assets appear at the next launch.

Before releasing, verify on a device or instrumented emulator: screen-frame
placement, control normal/pressed and diagonal overlays, mixed SVG/PNG sets,
icon 1:1 and 3:4 draft Save/Discard, parser safety, orientation, launch cost,
memory usage and unchanged frame pacing. Instrumented SVG tests do not establish
all of these runtime/visual properties by themselves.
