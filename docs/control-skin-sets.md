# Virtual control skin sets

Button skins are **global assets**, selected independently by each MIDlet or named
preset. The selected profile stores `ControlSkinSetId` in `config.json`; the
keyboard layout and mapped key codes are unchanged. Editing assets in a set
affects every MIDlet using that set at its next launch.

## Workdir layout

```text
skins/controls/Fantasy/
  skin.json
  key_5.png
  key_5_pressed.png
  key_fire.webp
  dpad_base.png
  dpad_up.png
  analog_base.webp
  analog_thumb.png
  analog_thumb_pressed.png
```

Grouped image layers use the literal stems above, each with .png or .webp
extension. All eight slots are optional. D-pad overlays use the same full-size
canvas as the D-pad base to allow exact composition for diagonal presses.

One folder is one set. The folder name is the display name and can be renamed.
`skin.json` stores an independent UUID, so renaming through the editor does
not invalidate a MIDlet's selection:

```json
{
  "version": 1,
  "id": "e7a26d3b-92c1-4c7d-93de-58f14b8d2170",
  "defaultMode": "icon",
  "buttons": { "key_fire": "full" }
}
```

Users can create a folder manually with just images. The main-process library
editor assigns metadata on its next scan. Duplicate UUIDs in copied folders
are assigned new IDs by the editor. A runtime with an ambiguous ID instead
uses built-in buttons rather than modifying global files.

Image slots are named `key_<slot>.png` or `key_<slot>.webp`, with an optional
`_pressed` suffix. Supported slots: numbers `0`–`9`, `star`, `pound`,
`soft_left`, `soft_right`, `fire`, `a`–`d`, `menu`, `up`, `down`,
`left`, `right`, and the four diagonal names `up_left`, `up_right`,
`down_left`, and `down_right`. PNG and WebP cannot coexist for the same
slot and state when imported through the editor.

## Rendering rules

- **Icon only** (the default): retain the existing key fill and outline; render
  the original-color image centered inside the button with 10% inset on each
  axis and unchanged aspect ratio. The image replaces the key legend.
- **Full button**: image replaces the entire button surface and fills the
  button rectangle. It stretches with independently resizable width/height.
- Pressed image is optional. Without it, icon-only buttons keep the existing
  selected fill, while full buttons darken slightly on press.
- No image for a slot, an undecodable image, or an unavailable set retains
  default controls. The global keyboard opacity still applies.
- A skin set never alters game input, pressed-key delivery, touch rectangles,
  virtual-key visibility or the persisted binary keyboard layout.
- The legacy 28 button slots retain their existing icon/full and pressed rules.
- Grouped D-pad and analog skins only replace appearance. The D-pad uses a full
  `dpad_base` image and optional transparent `dpad_up`, `dpad_down`,
  `dpad_left`, and `dpad_right` overlays. Active diagonal input composites
  two direction overlays over the base; no synthetic pressed image is generated.
  Directional overlays without a base image are inactive.
- The analog stick can independently replace `analog_base` and
  `analog_thumb`. An optional `analog_thumb_pressed` image replaces the
  thumb while active; without it the normal thumb stays visible.
  A pressed-only thumb without a normal thumb is inactive.
- Grouped images use the existing control bounding boxes (D-pad: full diameter;
  analog: existing shell and thumb sizes). Images fill their designated bounds;
  designers must prepare their layers and transparent padding accordingly.
  Image transparency, visible shape, and artwork do not change input hitboxes,
  thumb travel, control geometry, or key delivery.
- Images are decoded and sampled once when the MIDlet starts, not per frame.
  The runtime reads global assets but does not create or modify set metadata.

## Editing and ownership

The Compose manager opens from MIDlet virtual-controls settings. Its compact
library selects a set by tapping its row, and the overflow menu edits or
manages the set. The editor groups movement controls, numeric keys, navigation,
and other buttons. On compact windows groups scroll in one column; wider
windows arrange them across two or three columns. Each group still edits the
same global skin assets.

The editor uses a bottom sheet when vertical space permits, or a split
preview/asset-detail page in short, wide windows. Grouped controls present a
composite preview and a compact list of individual image slots. The legacy
button editor retains its icon/full mode and normal/pressed image slots.
Previews use MIDlet appearance colors for built-in controls and available
custom layers; they do not simulate movement or affect the actual control
renderer.

Set edits (create, image import/removal, rename, duplicate, mode, delete) are
saved globally after each successful operation; there is no redundant Save
button in the image editor. Creating or duplicating a set opens its editor without changing the active
MIDlet selection. Tapping a set row in the library explicitly selects it and
returns its ID to ConfigActivity,
which follows its existing config persistence and preset-ownership rules.
Renaming keeps the ID; duplicating creates a new one. Deleting the selected
set clears the MIDlet selection on return. Other MIDlets holding references
to a deleted set harmlessly fall back to built-in buttons.

The editor accepts PNG/WebP up to 8 MiB and 2048 pixels on either axis. Files
replaced through the editor are staged before publication. External filesystem
changes are picked up on the next library scan or MIDlet launch; there is no
runtime hot-reload, SVG conversion, or grouped-control asset contract in this
initial release.
