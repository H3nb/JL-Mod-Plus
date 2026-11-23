# FPS counter

JL-Mod Plus offers an optional, passive FPS counter above the MIDlet surface.
The per-MIDlet **Show FPS** setting retains the released `ShowFps` profile key and
is off by default. `PerformanceOverlayPosition` selects one of four screen corners.
Malformed positions fall back to top left. The pre-release multi-metric
`PerformanceOverlayMetrics` field is ignored on import and is no longer written;
old profiles with `ShowFps` enabled continue to display FPS regardless of their
old metric selection.

FPS counts **distinct guest mailbox sequences successfully consumed by the host
rendering path per real second**, not the number of complete guest buffer
publications or actual physical display scanouts. Host redraws of the same
sequence do not increase FPS. A low value in a static or deliberately low-rate
game is not automatically a performance problem, and Java ME games have no
universal target frame rate.

The counter uses the existing non-interactive `OverlayView` layer. It draws
one white, regular 12sp monospaced line over a compact translucent dark
rounded background with small padding and a soft shadow, following system font scaling. The selected corner is anchored
inside the safe host viewport (including system bars and cutouts). The counter
does not intercept game input.

A per-surface `FrameMetrics` counter is constructed only when Show FPS is on.
`PresentationMailbox` owns the sequence; the counter observes only successful
renderer consumption. At visibility transitions, pending old sequences are
abandoned under the publication lock, and the one-second sampling baseline
resets, avoiding a spurious spike after pause/resume. Surface destruction cancels
the sampler and discards the per-surface counter. All sampling uses host monotonic
time and does not change guest clocks or frame pacing.

The former CPU/memory/thermal sampling, frame timing rings, guest frame traffic,
pipeline timings and multi-metric overlays have been removed. Performance
investigations use targeted instrumentation when required; this user-facing
counter remains intentionally minimal.
