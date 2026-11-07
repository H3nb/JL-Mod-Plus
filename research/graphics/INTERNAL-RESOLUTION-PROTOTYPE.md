# Internal resolution 2D prototype

Branch: `prototype/internal-resolution`, based on the independent
`research/graphics-architecture` research branch. Do NOT merge this branch
or its disposable workflow into `alpha`.

## Trying it

1. Install the arm64 APK from the **Internal Resolution Prototype APK** Actions artifact.
2. In the MIDlet's display/rendering settings, choose **Internal resolution
   (experimental)**: Auto or 1x through 10x.
3. Save the MIDlet configuration and **restart the MIDlet**. The resolved
   factor is displayed with the renderer in the performance diagnostics.
4. Compare 1x, Auto, 2x and a higher supported factor. Try immediate text,
   sprite regions, copyArea, GameCanvas partial flushing, resizing and softbar.

The setting is per-game and defaults to **1x**. It is deliberately additive
in profile JSON. "Auto" is requested value zero and selects the largest
integer factor fitting the final game viewport, subject to prototype limits.
Manual factors may exceed the viewport size to explore supersampling.
The chosen scale remains fixed for the lifetime of each Canvas; resize does
not hot-switch its physical target.

## Technical boundaries

- This is a **2D target prototype**, not full JSR-184 or Mascot HD.
  Native M3G and Mascot binder/viewport/readback boundaries are not adapted.
  Use **1x** for games using those engines and report failures separately.
- Guest logical width/height, touch coordinates and font measurements remain
  independent from the larger Bitmap backing.
- Readback from a scaled Image samples cell centres; not guaranteed bit-for-bit
  identical to an independent 1x raster with antialiasing.
- Only runtime-owned display buffers are supersampled. Ordinary game-created
  Image objects remain 1x: text first drawn to a game-owned bitmap does not
  gain detail solely from scaling its final presentation.
- Existing GL shader selection/preset state is preserved. Both GPU and Canvas
  presenters consume the physical content rectangle, not the logical crop.
- Host virtual controls, background and overlays remain outside the guest target.
- Conservative prototype guards: no more than 10x, target side no more than
  4096 and a 160-MiB estimated four-plane budget. These are NOT a measured
  device capability probe; large textures or CPU allocation may still fail.
- This branch also retains unrelated, disposable shader research from its
  source branch. Do not merge the entire prototype into production.

## Evidence required before wider adoption

Android 23/35 instrumentation for logical pixel/copy/crop/text, actual
GameCanvas and MIDlet host operations, frame/softbar/resize/context loss,
physical ARM64 frame timings, Bitmap/GL allocation costs, and native M3G /
Mascot end-to-end geometry-to-HUD readback tests. The branch's own focused
test sources are characterization checks, not evidence of all these gates.
