# Creating virtual-control skins

Virtual-control skins replace the artwork of JL-Mod Plus's on-screen keypad, D-pad, and analog stick. They let you design a consistent set of controls while leaving the actual button positions, touch areas, and key mappings under the MIDlet's control settings.

If you are recreating a feature phone whose **entire keypad is already drawn on the screen frame**, a separate control-skin set may not be necessary. You can instead [align invisible virtual controls with the printed keys](creating-screen-skins.md#recreating-a-feature-phone-with-a-working-keypad). Use individual control skins when you need artwork that moves with the controls or changes when pressed.

You can make one button, a complete keypad, or a mixed set using images in different supported formats. The most important design decision is whether an image replaces **a key's label** or **the entire visible button**.

## Start with the way the button will be drawn

Each regular key offers two display modes:

| Mode | Best use | How artwork is drawn |
| --- | --- | --- |
| **Icon only** | A custom symbol that should keep the standard button background | The image is centered inside the button, inset by about 10% on each axis while keeping its aspect ratio |
| **Full button** | A custom keycap or button surface | The image fills the existing button rectangle and can stretch when width and height differ |

For **Icon only**, draw a clear symbol with some breathing room, but avoid excessive transparent margins: the renderer already adds an inset. Test the result at normal keypad size, not only in an enlarged editor.

For **Full button**, design the background, border, and label together. Avoid putting details too close to the edge because the entire image is fitted to the button bounds. If a user's layout uses wide soft keys and square number keys, the same source proportions may look different between them.

Every ordinary key can also have an optional **pressed image**. If none is supplied, JL-Mod Plus retains its built-in pressed feedback for icon-only keys and slightly darkens a full-button image. Provide a pressed variant when your design needs a clear change in color, shape, or lighting.

### Useful starting sizes

There is no single required artwork size because virtual controls can be resized. For uncomplicated designs, **256 × 256** or **512 × 512** pixels is a reasonable square working canvas for individual keys and movement-control artwork. These are recommendations, not output requirements.

PNG and WebP sources must be no larger than **2048 pixels on either axis** and **8 MiB per file**. Static SVG sources are limited to **8 MiB** and need valid dimensions or a `viewBox`; their vector coordinate system is not subject to that raster pixel limit.

Use **PNG** for exact transparency and raster details, **WebP** when file size matters, or **SVG** for simple, crisp vector artwork. Animated or externally linked SVG content is not supported.

## Keypad artwork

A set can customize the number keys, `*`, `#`, soft keys, FIRE, directions, diagonals, and other supported buttons. Each key has a separate image slot, so designs need not repeat the same geometry everywhere.

For example, a minimal set might replace only the FIRE key and the two soft keys. The rest of the virtual keypad keeps its built-in look. This is often easier to maintain than drawing a full set before testing whether the style is readable on a phone.

### Normal and pressed states

Keep normal and pressed artwork aligned on the same canvas dimensions. A button should not visibly jump or change scale when pressed unless that motion is intentional. Test the pressed image against the normal image at small size, where subtle changes may disappear.

Images are loaded at the beginning of a MIDlet session. The original files are kept; the runtime prepares a bitmap snapshot for the active controls.

## D-pad: one base and four directional layers

The D-pad uses a **base image** and up to four transparent directional overlays.

A useful starting arrangement:

~~~text
dpad_base.png
dpad_up.png
dpad_down.png
dpad_left.png
dpad_right.png
~~~

The overlays are optional, but **the base is required for custom directional overlays to appear**.

Make the base and every overlay use **exactly the same canvas dimensions**. For example, if the base is 512 × 512, the four overlays should also be 512 × 512. Keep each arrow or highlight in its final position on that full canvas. Do **not** crop `dpad_up.png` down to just the arrow shape.

This allows the renderer to stack the up and right overlays for an upper-right diagonal press without guessing where either image belongs. When both directions are active, both overlays appear over the base.

Keep the resting D-pad legible without any overlays, and make the active layers distinct enough to show a press without obscuring the center.

## Analog stick: base and thumb

The analog control has independent layers:

~~~text
analog_base.png
analog_thumb.png
analog_thumb_pressed.png
~~~

The **base** is the stationary shell. The **thumb** is the part that moves. A pressed-thumb variant is optional; without it, the normal thumb remains visible while active.

The thumb occupies a smaller area than the base: the current renderer targets approximately **48% of the movement control's diameter** for thumb artwork. Design the thumb for that smaller area rather than drawing an entire second joystick inside its image.

Unlike D-pad overlays, the analog thumb is its **own image**, not a full-canvas overlay. It does not need the base's exact pixel dimensions; it should be centered with sensible transparency and have a shape that remains readable as it moves.

Artwork cannot change the analog stick's allowed movement range or touch geometry. Those belong to the layout and input settings.

## Build and select a set

The simplest workflow uses the app:

1. Open a MIDlet's configuration and go to **Controls → Customization → Skin**.
2. Create a skin set and give it a recognizable name.
3. Open the set and import images for the keys, D-pad, or analog slots you want to customize.
4. Choose **Icon only** or **Full button** for individual keys when appropriate.
5. Return to the skin-set list and **tap the set to select it** for the current MIDlet.
6. Launch the MIDlet to see the finished controls.

Creating or duplicating a set opens it for editing; **it does not select the set automatically**. Edits in the skin manager are saved as successful operations complete—there is no separate Save action.

A set is a **shared resource**. If two games use the same set, editing its images changes the appearance of both at their next launch. Duplicate a set if you want to customize one game without affecting another.

### For creators organizing files directly

JL-Mod Plus stores each skin set in its own folder under the emulator work directory, such as:

~~~text
skins/controls/Classic Keypad/
  key_1.png
  key_1_pressed.png
  key_2.svg
  key_star.png
  key_pound.png
  key_soft_left.webp
  key_soft_right.webp
  key_fire.svg
  dpad_base.png
  dpad_up.png
  dpad_down.png
  dpad_left.png
  dpad_right.png
  analog_base.png
  analog_thumb.png
  analog_thumb_pressed.png
~~~

Use `key_<name>.<extension>` for a normal key and `key_<name>_pressed.<extension>` for its pressed image. Supported names are the digits `0`–`9`, `star`, `pound`, `soft_left`, `soft_right`, `fire`, `a`–`d`, `menu`, the four directions, and diagonals such as `up_left` and `down_right`.

Each slot should have **one source format**—do not place `key_fire.png` and `key_fire.svg` in the same folder and expect both to be used. The built-in manager removes alternate formats when replacing a slot.

A manually created folder can be discovered by the skin manager; you do not need to hand-write `skin.json` just to begin. The manager assigns the set its own stable identity on scan. Rename, duplicate, and select sets from the app so linked MIDlets remain consistent.

There is **no direct ZIP import of an entire control-skin set** in this manager. If you share a set as a ZIP, it is a convenient way to transfer the folder and source artwork; the recipient must extract it into the appropriate location or import the images into a set through the app.

## Check the finished result

Test on an actual MIDlet after selecting the set. Verify:

- Every customized key has a readable normal state and, where applicable, visible pressed feedback.
- The D-pad's four directions align, especially when two directions are pressed together.
- The analog thumb remains centered at rest and looks correct near the edge of travel.
- Controls remain recognizable in both intended orientations and at different layout sizes.
- The artwork does not obscure neighboring controls or rely on tiny text.
- The buttons still deliver the expected input. If touch areas are wrong, fix the layout or key mapping; the skin does not change them.

JL-Mod Plus prepares images as a group under a **24 MiB runtime bitmap budget**. Oversized or very dense sets may be rendered at reduced resolution; invalid or missing images use built-in controls. If the whole set cannot be prepared safely, the built-in controls remain usable. Well-sized artwork tends to look more consistent and load with less overhead than excessive high-resolution images.

## If something goes wrong

**A D-pad direction appears in the wrong position.** Confirm that all directional overlays retain the entire base canvas and that their artwork is aligned before export.

**An image looks cropped or too small.** Inspect its transparent margins and whether the key is using Icon only or Full button. The renderer may add an inset or fit the whole image to a different rectangle than expected.

**Pressed artwork never appears.** Check the `_pressed` slot and confirm that the normal image for that key is valid. A pressed-only key image is not active by itself.

**The set works in the manager but not in-game.** Make sure you tapped the set to select it, then relaunch the MIDlet. Changes do not hot-reload into an active session.

**The game responds in the wrong place.** Use layout customization rather than editing the artwork: skin images do not modify touch hitboxes or key mappings.

For using skin sets in different MIDlets and profiles, see [Appearance and virtual controls](customization.md). For the exact slot names, metadata, and rendering contract, see [Virtual control skin sets](../control-skin-sets.md).
