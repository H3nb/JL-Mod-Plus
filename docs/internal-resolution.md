# Internal resolution: 2D integration candidate

Feature branch: `feature/internal-resolution-alpha` from alpha
`599b8e7f98ecadef4f7c738c897c616a4f9ed5b9`.

The MIDlet profile's `InternalResolutionScale` setting is **1 by default**.
Valid choices: **Auto (0)** and **1..10**. A missing field retains 1x.
Choose it in Display -> Rendering and restart the MIDlet.
The scale selected at first Canvas sizing is frozen for that Canvas session.
Auto uses the game's actual destination viewport after layout/softbar
adjustments, capped to the supported integer factor.

Runtime-owned Canvas/GameCanvas 2D bitmaps can have larger physical
backings. Java ME logical getWidth/getHeight, pixel coordinates, pointer input
and Font metrics remain unscaled. Bitmap fonts in game-created Image assets
remain 1x. 2D glyphs drawn directly onto a scaled display target are
rasterized again at higher density.

The stock GLES presenter and existing GLSL/GLSLP shader pipeline are **not**
replaced: they receive the physical content rectangle and source texture
dimensions. Host keypad/overlays are independently presented.

Prototype limits: a factor <=10, target side <=4096 pixels, conservative
four-plane budget <=160 MiB. This is an estimate, not a physical GPU query,
and does not guarantee an allocation will succeed. Extreme settings may
hurt performance. Raw screenshots capture the higher-density source.

**Not qualified**: native M3G/Mascot rendering into scaled Bitmaps,
pixel-perfect 1x getRGB equivalence on antialiased edges, hardware texture
limits, device memory/fps, Android 6/physical ARM64 rendering. These need
dedicated integration testing before production adoption. Use 1x for games
with those native 3D engines until their backing/viewport/readback is adapted.

Validation includes JVM scale-policy and form-state tests, Android
Bitmap/Canvas instrumentation characterization sources, and the standard
pull-request checks. Instrumentation sources must also be executed on suitable
Android devices before full production qualification. The disposable APK build
workflow used during development is not part of this integration.
