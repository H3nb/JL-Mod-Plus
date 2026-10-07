# Java ME compatibility changes

Use this guidance when changing Java ME APIs, JSRs, vendor APIs, or guest compatibility behavior. For host runtime presentation and LCDUI command boundaries, also read [Runtime UI](runtime-ui.md).

## Java ME compatibility

For changes to Java ME APIs, JSRs, vendor APIs, or compatibility behavior:

- Consult the applicable Java ME/JSR or vendor specification before editing compatibility-sensitive implementation; `https://github.com/shinovon/J2ME_Docs` is the repository's convenient reference set.
- Verify the relevant API contract, constants, state transitions, return values, exceptions, and edge cases. When the specification is incomplete, verified device/game behavior may provide compatibility evidence.
- Compare the specification and other verified compatibility evidence with existing JL-Mod/JL-Mod Plus behavior before changing compatibility-sensitive code.
- Do not silently "correct" known-compatible behavior just because Android or desktop Java behaves differently.
- If documentation is incomplete or ambiguous, prefer preserving known behavior and add a focused characterization/regression test instead of guessing.

## Nokia polygon rendering

Nokia `DirectGraphics` polygon/triangle methods use their explicit ARGB argument
without replacing the shared `Graphics` color. `getAlphaComponent()` reads that
shared context, including changes made through another wrapper or MIDP setters.
The [Nokia UI API reference](https://nikita36078.github.io/J2ME_Docs/docs/Nokia_UI_API_1_1/com/nokia/mid/ui/DirectGraphics.html)
defines closed polygons, even-odd interior coverage, and Source Over compositing.

Filled Nokia polygons include their one-pixel outline. The outline geometry is
combined with the even-odd interior before one normal ARGB draw, so one call
cannot darken its own boundary through repeated alpha blending. Separate calls
still composite independently. If native path operations cannot form that union,
opaque interior/outline coverage is combined in a bounded temporary layer before
applying alpha; opaque fallback calls draw directly. This fallback can differ in
channel rounding from a direct draw. MIDP fill primitives retain their existing
coverage and paint state.

This boundary treatment is supported by reconstruction of integer-separated
water regions from Bounce Tales: fill-only rendering leaves a source-background
column at each right edge. The observed columns move with the camera and match
both the positions and unblended colors in the reported captures. This is
compatibility evidence, not a claim that the reference specifies every boundary
rounding case or that whole-game/device validation has completed. The
`DirectGraphicsRenderingTest` instrumentation protects the general API behavior
without shipping game assets or selecting behavior by MIDlet identity.

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
