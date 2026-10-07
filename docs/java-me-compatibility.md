# Java ME compatibility changes

Use this guidance when changing Java ME APIs, JSRs, vendor APIs, or guest compatibility behavior. For host runtime presentation and LCDUI command boundaries, also read [Runtime UI](runtime-ui.md).

## Java ME compatibility

For changes to Java ME APIs, JSRs, vendor APIs, or compatibility behavior:

- Consult the applicable Java ME/JSR or vendor specification before editing compatibility-sensitive implementation; `https://github.com/shinovon/J2ME_Docs` is the repository's convenient reference set.
- Verify the relevant API contract, constants, state transitions, return values, exceptions, and edge cases. When the specification is incomplete, verified device/game behavior may provide compatibility evidence.
- Compare the specification and other verified compatibility evidence with existing JL-Mod/JL-Mod Plus behavior before changing compatibility-sensitive code.
- Do not silently "correct" known-compatible behavior just because Android or desktop Java behaves differently.
- If documentation is incomplete or ambiguous, prefer preserving known behavior and add a focused characterization/regression test instead of guessing.

## MIDlet locale identity

MIDP 2.0 defines `microedition.locale` as the current locale of the emulated device, not as an
Android resource-directory name and not as a MIDlet-specific resource choice. Resolve one Java ME
device locale independently of guest content and expose that same value to every MIDlet launched
under the same emulator locale state.

The runtime Activity's effective Android locale supplies the language, script intent and any
explicit region. If the region is absent, it may be borrowed only from a same-language system
locale whose language and script are compatible. If no such real regional locale exists, preserve
the language-only device locale instead of inventing a country. Canonicalize legacy Android
language aliases through BCP-47 before serializing back to the MIDP 2.0
`language[-COUNTRY[-variant]]` shape. MIDP requires a lower-case two-letter ISO-639 language and,
when present, an upper-case two-letter country. Preserve a two-letter country actually supplied by
the platform even when it is a historical Java ME-era ISO-3166 identifier that modern Java no
longer maps to an ISO3 code. Do not invent a Java ME locale for a host language that cannot be
represented by the language contract; `microedition.locale` may be absent.

Do not derive guest locale identity from the MIDlet JAR, generated Android locale metadata, or
`Locale.getDefault()`. `MidletSystem` owns emulator-visible property publication and updates
both the host system-property backing store and the transformed MIDlet property delegate so stale
process state cannot override the current device locale. Profile system properties are applied
afterward and remain the final authority, including an explicit `microedition.locale` override.

## Nokia polygon rendering

Nokia `DirectGraphics` and MIDP `Graphics` share one current ARGB drawing state.
MIDP color-query methods expose only `0x00RRGGBB`: `Graphics.getColor()` hides
the shared alpha byte and `getDisplayColor(int)` ignores the argument's high
byte. Nokia `getAlphaComponent()` reads the same shared state's alpha, including
changes made through another wrapper or MIDP setters.

Nokia polygon/triangle methods with an explicit ARGB argument use that call-local
color without replacing the shared `Graphics` color.
The [Nokia UI API reference](https://nikita36078.github.io/J2ME_Docs/docs/Nokia_UI_API_1_1/com/nokia/mid/ui/DirectGraphics.html)
defines closed polygons, even-odd interior coverage, and Source Over compositing.

Filled Nokia polygons include their one-pixel outline. The even-odd interior
and unshifted outline retain the same raster coverage for opaque and translucent
colors. Opaque Source Over is idempotent, so opaque calls draw those constituent
coverages directly. Translucent calls unite them in clipped integer `Region`
coverage and perform one ARGB draw, preventing the boundary from darkening
through repeated alpha blending. Separate calls still composite independently.
This avoids geometric union artifacts and alpha-layer rounding while keeping the
common opaque path lightweight. MIDP rectangle coverage and shared paint state
are preserved.

This boundary treatment is supported by reconstruction of integer-separated
water regions from Bounce Tales: fill-only rendering leaves a source-background
column at each right edge. The observed columns move with the camera and match
both the positions and unblended colors in the reported captures. This is
compatibility evidence, not a claim that the reference specifies every boundary
rounding case. Device verification confirmed that the reported Bounce Tales
visual artifacts are resolved. The `DirectGraphicsRenderingTest` instrumentation
protects the general API behavior
without shipping game assets or selecting behavior by MIDlet identity.

## MIDP filled triangles

MIDP `Graphics.fillTriangle` includes the lines connecting all three vertices.
MIDP's solid one-pixel line model places coverage immediately below and to the
right of integer coordinates; exact diagonal rasterization remains
implementation-dependent. JL-Mod retains the existing non-antialiased interior
and unions it with compatible solid boundary coverage. Bevel joins prevent acute
corners from extending beyond the vertex bounds; endpoint cells cover thin,
collinear and point triangles. The shifted outline also includes its interior:
on diagonal edges a half-pixel shift can separate a one-pixel stroke from the
original interior, producing periodic gaps and detached edge pixels. Filling
the shifted interior connects the boundary without discarding original pixels.

For opaque current colors, JL-Mod draws the same interior, shifted interior,
solid outline and endpoint coverage directly; overlapping opaque Source Over
draws are idempotent. For translucent current colors, those constituents are
rasterized within the current clip into reusable Android `Region` objects,
united as integer pixel coverage, and drawn once so overlap cannot apply alpha
more than once. Both paths are regression-tested for identical coverage.
Dotted stroke style does not affect either path. Separate translucent triangle
calls still composite independently; no alpha layer or float path union is used.

Geometric `Path.op` union can change raster coverage while rebuilding float
contours, even when it reports success. On adjacent triangles this exposed
isolated background pixels and irregular edge fragments. A reconstructed
terrain triangle `(-282,221), (-283,417), (162,236)` loses interior pixel `(57,278)`
with that model, matching the reported camera position and capture. Raster union
preserves all interior pixels while keeping the closed boundary that eliminates
the earlier horizontal seam. The [Android Region reference](https://developer.android.com/reference/android/graphics/Region#setPath(android.graphics.Path,%20android.graphics.Region))
guarantees coverage identical to a non-antialiased path through its clip.

`GraphicsTriangleRenderingTest` protects the seam, interior preservation and
exact raster union for offscreen/acute triangles, joined mesh coverage, vertex
ordering, degenerate cases, alpha, clipping and translation. The half-open
rectangle convention, Nokia even-odd rule and its outline positioning remain
unchanged. Connected horizontal slices of convex triangles protect against
gaps between the interior and the shifted outline.

## Foreground ownership boundary

JL-Mod keeps Android task foreground, emulator/AMS foreground selection, MIDlet lifecycle, and LCDUI display foreground as separate facts. In particular, MIDP `Display.setCurrent(null)` retains the current `Displayable` and is treated as a request to yield emulator foreground to the Library; it never means Android Home. A live runtime may therefore coexist with Library foreground. Non-null `setCurrent()` calls update guest display state but do not directly foreground Android Activities. The runtime storage lease remains liveness evidence only; emulator foreground selection is persisted separately and generation-fenced.

## MMAPI file video

File 3GP/MP4 video has one Player contract and a soundtrack on the shared runtime
audio bus. JSR135 `VideoControl` supports both direct Canvas and GUI Item/Form
modes, including their initialization, default visibility, geometry and error
contracts. Pure video follows Android host eligibility without audio focus.
Unsupported codecs are rejected rather than presented as successful audio-only
video; actual audio-only MP4 remains supported. See
[the supported subset and timing policy](audio-runtime.md#file-video-and-presentation-clock)
and [runtime UI ownership](runtime-ui.md#validation-gates).


## Malformed source classes during conversion

MIDlet conversion uses an optimistic single-pass fast path. A class that converts normally is not
prevalidated or traversed a second time. When the JL-Mod bytecode transform encounters an anomaly,
the converter may perform a neutral traversal of that class only to distinguish unreadable guest
input from a transform failure.

A source class that is already unreadable before the JL-Mod transform may be omitted from the
generated DEX and reported as a conversion warning. The retained source JAR is never rewritten.
A skipped class that is declared as a `MIDlet-n` entry is not recoverable and aborts installation
or reconversion. Class/path identity mismatches, failures while transforming readable source,
DX parse/translation/assembly failures, and output failures remain fatal.

Successful conversion diagnostics are bounded and surfaced to the user instead of being silently
discarded. Skipped-class identities used to verify declared `MIDlet-n` entry points are tracked
separately from those presentation diagnostics. If every skipped identity cannot be retained
within that safety bound, the installer fails closed rather than publish an app whose entry points
cannot all be verified.

The transform-version marker therefore means that every executable class emitted into
the converted payload completed the current JL-Mod transform; it does not claim that every malformed
`.class` entry from the source archive was emitted.
