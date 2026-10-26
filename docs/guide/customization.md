# Appearance and virtual controls

JL-Mod Plus separates the **game screen**, the **frame surrounding that screen**, and the **controls over or around it**. These can be customized independently. Understanding which layer you are changing avoids a common problem: artwork looks different, but the game still reacts to touches in the same place.

If you're preparing artwork rather than selecting an existing skin, start with [Creating screen skins](creating-screen-skins.md) or [Creating virtual-control skins](creating-control-skins.md). They cover canvas layout, file formats, image slots, and how to test the finished work. A screen skin can also include a printed feature-phone keypad with [invisible virtual-control touch targets](creating-screen-skins.md#recreating-a-feature-phone-with-a-working-keypad).

## Screen skins: the frame around the game

Open a MIDlet's configuration and go to **Display → Skin**. Choose an existing skin or import new artwork.

Screen skins can use PNG, JPEG, WebP, BMP, GIF, or supported static SVG artwork. The original source file is retained for screen-skin imports.

A skin is a presentation layer around the Java ME screen. The game still renders to its own rectangular framebuffer. Artwork with a transparent opening allows the game to show through; opaque parts hide the frame beneath them.

### Rounded or irregular openings

Artwork can depict a curved screen bezel, but the guest framebuffer does not become curved. The skin's transparency and the way it overlays the game create the visible edge. Pointer coordinates and the underlying screen geometry remain rectangular.

For a clean result, place the transparent opening carefully and keep opaque artwork where it should hide pixels. A partially transparent bezel may blend with the game instead of covering it completely.

This distinction matters when designing a skin from a screenshot of an older phone: the visible frame can imitate the phone, while the game's interactive display still follows the configured virtual resolution.

## Virtual-control skins: artwork for buttons and movement

Open **Controls → Customization → Skin** in a MIDlet's configuration.

Create a skin set or select an existing one. Within the manager, you can edit the images for ordinary keys, the D-pad, and the analog stick. A set need not customize every button; missing images use the built-in appearance.

Button images can be **PNG, WebP, or supported SVG**, with optional variants for the pressed state.

| Mode | What changes |
| --- | --- |
| **Icon only** | Replaces the legend inside the existing button; the normal button fill and outline remain |
| **Full button** | Uses the image as the full visible button, fitted to the existing button rectangle |

If you supply no pressed image, the renderer keeps its fallback pressed appearance. Full-button artwork may stretch when a button's width and height are adjusted independently; design the image with that in mind.

### Designing a D-pad

A D-pad skin can use one `dpad_base` and separate transparent overlays for **up**, **down**, **left**, and **right**. For a diagonal press, the corresponding horizontal and vertical overlays are drawn together over the base.

Make the overlays on the **same full-size canvas as the base image**. Do not crop an overlay tightly around the arrow it contains: transparent margins establish alignment. An overlay without a base is not active.

### Designing an analog stick

An analog skin can replace the **base**, **thumb**, and optionally the **pressed thumb**. These images follow the existing movement area and thumb travel. Drawing a visually larger stick does not increase its interactive reach.

You can mix PNG/WebP and SVG within a set. Static SVG files are converted into bitmaps when the MIDlet starts, rather than being rendered as vectors for every frame.

## Shared skin sets versus a MIDlet's layout

A **skin set is shared artwork**, not a copy of the game's touch configuration. Each MIDlet or profile selects a set; changes to that set's images appear in every MIDlet using it at the next launch.

Creating or duplicating a set opens it for editing but **does not select it automatically**. Tap its row to choose it for the current MIDlet. Renaming a set does not change its identity; deleting a set makes MIDlets that still refer to it use built-in artwork.

The skin manager saves each successful edit as you perform it. There is no separate Save action for its images. This is different from the **Edit metadata** screen, where icon and text edits are reviewed and saved together.

Want different artwork for one game only? Duplicate the set, edit that copy, and select it for that game.

Want buttons in a different position, with different sizes or key mappings? Change the **virtual-control layout or mapping**, not the skin artwork. Skins do not move hitboxes or change keys sent to the MIDlet.

## SVG artwork: what is actually supported?

JL-Mod Plus accepts **static, self-contained SVG** for screen skins, virtual-control skins, and Library icon imports. This does not add Java ME **JSR-226** support inside games.

SVG animations, scripts, external resources, DTD/entity declarations, and unsupported effects such as filters are outside the supported subset. SVG imports are limited to **8 MiB**, and the artwork needs valid dimensions or a `viewBox`.

Transparent padding is preserved. For control skins, all imported images are prepared as a group with a shared bitmap-memory budget. A very large collection may be rendered at reduced resolution to fit safely; if it cannot be prepared, the built-in controls remain available rather than leaving some buttons missing.

An SVG imported as a **Library icon** is normalized to the stored icon image format. An SVG imported as a **screen or control skin** remains an SVG source file and is rasterized when needed. These are deliberately different paths.

## Troubleshooting artwork

**Only part of the key or D-pad appears.** Check the source canvas, viewBox, and transparent margins. Overlays must align with their base; a tightly cropped image is rarely the right fix.

**The skin manager shows the image, but the running game still uses the old one.** Exit and relaunch the MIDlet. Skin files are prepared at launch and do not hot-reload.

**A custom button disappeared.** Check the file extension, image validity, and which state (normal or pressed) the image was assigned to. Missing or undecodable artwork falls back to defaults.

**The controls look correct, but taps miss the intended location.** Artwork does not change touch geometry. Review the control layout and orientation.

**An SVG imports but doesn't look like the source artwork.** Compare it with the supported SVG subset above. Effects not supported by the renderer may be absent or changed.

**Technical references:** [Control skin sets](../control-skin-sets.md) · [Static SVG artwork](../svg-artwork.md) · [Runtime UI](../runtime-ui.md)
