# Creating screen skins

A screen skin is artwork placed around the MIDlet's display. It can resemble a classic phone, use a minimal frame, or create an entirely different look. The one area that needs special care is the **opening where the game appears**: JL-Mod Plus uses transparency in the artwork to identify that space.

This guide describes how to prepare an image that works reliably. You can use whichever drawing or image-editing tools you prefer.

## Plan the canvas around the display

Two different aspect ratios matter when designing a screen skin:

- **The transparent display opening** should normally match the MIDlet's configured game-screen aspect ratio. For a **240 × 320** display, that is **3:4**. A **320 × 240** landscape display is **4:3**.
- **The outer skin canvas** should be designed for the proportions of the Android app's usable display area in the intended orientation. For example, a 20:9 portrait area corresponds to a 9:20 canvas, such as **1080 × 2400**. This is a working-size example, not a required resolution.

The opening and the outer canvas do **not** need the same ratio. A 1080 × 2400 frame can contain a 600 × 800 transparent opening for a 240 × 320 game, leaving room below it for a decorative keypad.

JL-Mod Plus currently scales the complete skin image to fill the display area. An outer canvas with very different proportions may therefore stretch its bezel and printed buttons. Design for the **space available to the running app**, not just the phone's advertised physical resolution; system bars and windowed modes can change that space.

For the game itself, configure the intended MIDlet screen size and choose **Fit to window** (the aspect-ratio-preserving scaling mode) where appropriate. An opening that matches the game's ratio makes it easier to use the whole opening without distorting the game. **Fill window (ignore aspect ratio)** can distort the guest image; **Manual**, a custom scale percentage, soft-key space, or different screen dimensions can also leave margins or change alignment. The skin alone does not override those settings.

Decide where the display opening sits **before** drawing decorative details. Leave room for the bezel and any printed keypad. For a single-device design, proportions close to that device's running app window will usually align better. For a skin meant for many devices, check more than one window ratio, since an image that looks accurate on one phone may stretch on another.

Think of a typical screen skin as three areas:

1. **The display opening:** transparent pixels through which the MIDlet is visible.
2. **The frame:** visible, preferably opaque artwork that should cover the game where necessary.
3. **Decorative or outer areas:** details outside the opening, which may use transparency if they do not interfere with opening detection.

### Make the display opening transparent

Create a **substantial, enclosed transparent area** for the game. For reliable detection, keep the middle of that opening completely transparent (alpha 0), and surround it with visible frame artwork. The detector looks for a large, mostly transparent region enclosed by the image; a thin transparent line or an opening connected to the outer edge may not be recognized as the display area.

This does **not** mean that the opening must be a sharp-cornered rectangle. Rounded corners and shaped edges can work: the emulator locates the rectangular bounds of the opening and then draws the original skin over the game. The skin's opaque and transparent pixels determine which parts of the game remain visible.

Use opacity intentionally:

- **Transparent:** the game shows through.
- **Opaque:** the frame hides the game underneath.
- **Semi-transparent:** the frame blends with the game, which can look unintended around screen edges.

A frame designed for a curved display should have opaque coverage outside the intended curved edge. Do not merely draw a rounded outline over a fully transparent rectangle if you expect the corners behind that outline to be hidden.

### Leave the original canvas intact

Avoid trimming the exported image to the visible decorative artwork if doing so changes the opening's position. Keep the planned outer margins and transparent regions. For SVG, retain the full `viewBox`; for raster images, retain the complete pixel canvas.

The opening is detected from the exported artwork, not from a separate coordinate file. Moving or resizing the opening changes where the game will appear.

## Recreating a feature phone with a working keypad

A screen skin can include the **printed keypad artwork** of a classic feature phone. The artwork itself is not interactive, but JL-Mod Plus can place the real virtual-control touch areas over it. With the virtual controls fully transparent, the printed buttons provide the visible keypad.

To set this up:

1. Draw the phone frame and keypad together on the screen-skin canvas. Keep the game's transparent display opening at the intended aspect ratio.
2. Import the skin under **Display → Skin** and launch the MIDlet.
3. Keep **Show virtual controls** enabled in the MIDlet's **Controls** settings.
4. Open the **virtual-control layout editor** while the controls are still visible. Move and resize the keys to match the printed numbers, soft keys, D-pad, or other button shapes. Check that neighboring touch areas do not overlap.
5. Save the layout and test the button mapping in the game.
6. Set **Controls → Customization → Opacity** to **0%**. Turn **off** **Show controls without transparency outside the MIDlet screen**, otherwise buttons outside the game area may remain opaque.

**0% opacity hides the virtual-control artwork, not its touch targets.** The keypad in the screen skin remains visible because it is separate artwork. Controls become visible again in layout edit mode so they can be positioned. You do not need an additional virtual-control skin set for buttons already drawn into the screen skin.

This method works with numeric keys and supported movement controls, but the overlay does **not automatically detect** the artwork or assign touches to printed shapes. The clickable areas remain the configured key rectangles or D-pad/analog geometry. An unusually shaped printed key may need a slightly larger, simpler touch target to be usable.

Keep some temporary opacity while testing if you need to see where the touch targets lie. If a button stops responding after being hidden, check that virtual controls remain enabled and that an automatic hide delay has not deactivated the overlay. Visual press feedback from the virtual controls is also hidden at 0%; haptic feedback can help when available and enabled.

**Portability matters:** the skin image scales with the available display, while the virtual-control layout is a separate configuration. Their alignment may differ after changing the device, display insets, orientation, or scaling. Recheck the layout on the intended phone, and verify portrait and landscape placements separately if both are used. This is not an automatic responsive-keypad mapping system.

For more about artwork replacing the individual control surfaces, see [Creating virtual-control skins](creating-control-skins.md). That technique is useful when you want the control images themselves to have separate pressed states instead of using a static keypad printed on the frame.

## Choose a suitable format

Screen skins accept **PNG, JPEG, WebP, BMP, GIF, and supported static SVG**. For newly created skins, these are the most useful choices:

| Format | Good for | Considerations |
| --- | --- | --- |
| **PNG** | Pixel-perfect frames, detailed transparency, and painted artwork | Lossless; often larger than WebP |
| **WebP** | Compact raster artwork | Use an export that preserves transparency when the opening needs it |
| **SVG** | Clean vector shapes, crisp frames, scalable geometry | Must be static and self-contained; not every SVG effect is supported |

JPEG has no alpha channel, so **it cannot define a transparent display opening by itself**. GIF and BMP are accepted inputs, but are not usually the first choice for a new skin.

For SVG, provide valid finite dimensions or a `viewBox`. Avoid animations, filters, scripts, embedded document declarations, and external images or resources. An SVG file must be **8 MiB or smaller**. The result is rasterized when the MIDlet starts; it is not continuously rendered as vectors during gameplay.

Choose detail appropriate for the size at which the frame will actually be seen. Extremely fine lines, tiny labels, and narrow gaps may disappear after scaling. High-resolution source artwork is useful, but cannot compensate for a poorly positioned opening.

## Import and test

1. Export the finished image, preserving the intended transparency.
2. Open the MIDlet's configuration and choose **Display → Skin**.
3. Import the image and select it.
4. Launch the MIDlet and check the placement of the display.

Do not judge the result from the artwork preview alone. Try a MIDlet with a clearly visible screen border or distinctive corners, then inspect whether the game begins and ends where the opening was intended.

Check the following before sharing a skin:

- Does the opening match the MIDlet's intended aspect ratio (for example, 3:4 for 240 × 320)?
- Does the opening expose the entire intended game area under the selected scaling settings?
- Are rounded corners and opaque edges covering the correct pixels?
- Does the frame still look right after switching to the intended orientation?
- Are decorative elements legible at actual phone size?
- Does an unusually wide or tall device stretch the design more than expected?

A skin changes screen presentation, not the MIDlet's framebuffer dimensions or touch coordinates. If the game is displayed at the wrong resolution, adjust the MIDlet display settings rather than drawing a different-sized opening as a workaround.

## Common problems

**The game appears behind the frame in the wrong place.** The transparent region may be too small, connected to the image edge, or split into several competing openings. Try one large enclosed transparent area.

**The game is visible through corners that should be hidden.** Use fully opaque artwork outside the intended rounded edge. Semi-transparent borders allow game pixels to show through.

**The skin looks stretched.** Compare the outer canvas ratio with the actual app display area, not the MIDlet resolution. A frame designed for a different device ratio may need a separate export.

**The game is stretched or leaves margins inside the opening.** Check the MIDlet's configured screen resolution and scaling mode. The opening ratio should match the game, and Fit preserves its proportions.

**The printed keypad looks right but touches miss.** Enable virtual controls, temporarily raise their opacity, and move or resize the invisible hit targets in the layout editor. The skin artwork does not create hitboxes.

**The SVG imports but loses effects.** Replace unsupported filters, external resources, or animation with ordinary static shapes and paths.

**Changes do not appear in a running game.** Exit and relaunch the MIDlet. Skin files are loaded when the session starts rather than updated live.

For choosing and managing skins, see [Appearance and virtual controls](customization.md). For the exact renderer behavior and SVG limitations, see [Static SVG artwork](../svg-artwork.md).
