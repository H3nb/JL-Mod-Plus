# Performance overlay

The profile's Performance Overlay switch retains the persisted `ShowFps` key.
`PerformanceOverlayMetrics` stores independent metric bits; missing fields in old
profiles use Standard, while an explicitly empty selection remains empty. The
released legacy `ShowFps` key is preserved. Metric bits are not renumbered;
existing development profiles may retain custom selections.
`PerformanceOverlayPosition` selects one of four corners. Presets derive from
the selected bits, so there is no separate preset state to become inconsistent.

The overlay remains a passive native `OverlayView` layer above the guest surface.
It does not consume game input. It uses white 12sp regular monospaced text following
system font scaling, with no outline or background panel. A black 80% opacity
shadow uses a 1dp blur radius and 1dp offset on both axes to separate glyphs from
bright game content.
Uppercase abbreviations are intentional for this diagnostic component; settings
show full names and abbreviations in a compact checkbox list. Technical definitions
are documented below.
Selected cells reflow with ` | ` between cells, with no trailing separator.
Cell widths follow their content, with no padding or reserved numeric slots.
Left positions align each row to the left; right positions align each row to the
right, so shorter rows and changing values keep their right edge anchored.
Frame interval statistics share one group; renderer, display Hz, and thermal
severity share the final host-information group.
Short, wide windows can use columns without shrinking the font. If the available
viewport cannot contain every selected metric at the user's font scale, content
is clipped to the safe viewport; reduce the selection or choose another position.

## Measurement definitions

All observed rates and intervals use a host monotonic clock and the active
Canvas surface lifecycle. Guest/publication, host renderer-consumption, and
physical display presentation are distinct events. This overlay currently
measures the first two, **not** actual physical display presentation. Guest
publications can contain identical pixels, and an Android renderer callback
is not a guarantee of a completed display scanout.

Java ME MIDlets have no universal native frame-rate target. Speed is the
configured guest-clock multiplier, not measured achieved original-device
speed. A low FPS in a static or deliberately low-rate MIDlet is not by itself
a performance failure. No automatically inferred achieved-speed, dropped-frame
alarm, or GPU utilization is included.

| Label | Measurement |
| --- | --- |
| FPS | Distinct guest mailbox sequences consumed by the host rendering path per real second. Repeated host redraws of the same sequence do not count; not confirmed display presentation. |
| GFPS | Complete guest buffer publications per real second, including visually unchanged buffers. |
| CAP | Effective host pacing ceiling from the configured FPS limit or the maximum supported display refresh rate, scaled by the manual speed multiplier. It is **not** the native target FPS or a guaranteed bound for non-blocking callback paths. The configured value 0 means the display maximum, **not** unlimited. |
| SPD | Configured TimingSession guest-clock multiplier. Not achieved emulation speed; if unavailable, display —, never assume 1.00x. |
| GFI / GP95 / GMAX | Mean, nearest-rank 95th percentile, and maximum host-time intervals between complete guest publications. |
| RFI / RP95 / RMAX | Mean, nearest-rank 95th percentile, and maximum host-time intervals between distinct successful renderer frame-consumption observations. Not hardware presentation intervals. |
| PAINT | Mean elapsed time in guest paint callbacks; absent for games that only use flush APIs. |
| COPY | Mean elapsed time in buffer-copy operations; not necessarily one copy per frame. |
| SUB | Mean elapsed host renderer submission duration for newly consumed sequences. Includes host work and waits, not measured GPU execution or display scanout. |
| INQ | Mean time from input event queue entry to guest callback dispatch; not touch-to-photon latency. |
| FRQ | Mean time from guest publication to host renderer acquisition under the presentation buffer lock. |
| COAL | Published mailbox sequences replaced before rendering, recorded at consumption per real second; not necessarily a defect or intentional frameskip. |
| CPU | Process CPU time per wall time, displayed in core equivalents: 1.00c equals 100% of one core. Includes all runtime-process threads and can exceed 1.00c. Not device-wide utilization. |
| RAM | Runtime process proportional set size (PSS), MiB; not just guest allocations or all processes of the application. |
| JAVA / NATIVE | Used Java heap / allocated native heap in MiB, not additive components of PSS. |
| CPUT / GPUT | Hottest accessible current CPU/GPU sensor reading, without substituting battery readings. If unavailable, display —. |
| BAT / THRM | Battery temperature reported by Android / Android thermal severity if supported. |
| REN / DISP | Host renderer backend / active display refresh rate reported by Android, not the maximum supported pacing rate. |

The HUD deliberately names both guest and renderer cadence domains. It never
treats FPS/CAP as a percentage or combines independent PAINT, COPY, and SUB
measurements into a fabricated total frame duration. Intervals expire after
their sampling window and then become —; no observed frame may legitimately
yield an FPS of zero.

Presets are Minimal (FPS/CAP/SPD), Standard (the default concise renderer and
guest cadence diagnostics), Debug (curated useful diagnostics), and Custom
(individual metric bits). A manual selection of all metrics remains possible,
but selecting Debug does not automatically activate every sensor. Existing
metric-bit identities remain stable; additional renderer interval options use
new bits. An explicit empty selection stays empty.

Text refreshes every 500ms. Frame rates use elapsed windows of at least one real
second. Timing statistics retain up to the newest 4096 samples within five real
seconds; sufficiently high event rates shorten this bounded window. CPU samples
update every second; PSS, heap, hardware temperature, and thermal samples update
every five seconds. Battery temperature follows Android battery broadcasts.

Optional work is proportional to the selected metrics. `FrameMetrics` exists only
when FPS, RFPS, or COAL needs frame-traffic counters. Renderer frame accounting is
enabled only for RFPS or COAL. Host renderer timing remains independent: selecting
SUB or FRQ still records `PerformanceDiagnostics` renderer timing without creating
or invoking `FrameMetrics`. Timing rings are allocated only for selected timing
metrics, and process/device resource work follows the selected resource metrics.
When `ShowFps` is true with an explicit zero metric mask, no overlay layer, timer,
resource sampler, `FrameMetrics`, or `PerformanceDiagnostics` is created.
Renderer cadence measurements use the same mailbox sequence and visibility
boundary as FPS, without a second frame-ID authority. Debug timing is still
optional and writes primitive samples into bounded, preallocated rings.

The temperature fallback discovers `/sys/class/thermal/thermal_zone*/type` once
per sampler and reads selected sensors' `temp` files on the five-second worker
interval. It recognizes `cpu`, `cpuss`, and `gpu` labels with optional numeric
hyphen suffixes, including the CPU clusters and GPU sensors verified on the
reported device. Values use the Linux thermal ABI's millidegrees Celsius; no
unit guessing, root, shell, Shizuku, or zone-number mapping is used by the app.
Ambiguous board, battery, anonymous TSENS, and SoC labels are excluded. Individual
read failures remain unavailable and never retain an old temperature. Different
tools may select or aggregate sensors differently; these values do not promise
the same reading as DevCheck.

Unavailable or insufficient data is `—`, not zero. A real zero-FPS static buffer
is not treated as a failure. The overlay never infers achieved speed or marks a
game unhealthy merely because its FPS is below the display's Hz or CAP.

## Lifecycle and sequence ownership

`TimingSession` is the only emulation-speed authority. It owns the manual
multiplier, guest clock mapping, and timing revision; `FramePacer` reads that same
session. The overlay never owns or changes speed.

For each Canvas surface lifecycle, `PresentationMailbox` is the only publication
sequence authority. A successful complete publication returns mailbox sequence N;
Canvas stores that same N, optional `FrameMetrics` counts the publication, and
selected `PerformanceDiagnostics` records its host timestamp. Renderers consume
that same N. `FrameMetrics` does not create a second sequence and never persists
across replacement surfaces.

All publication-owned diagnostics are constructed and installed under the existing
publication buffer lock before `PresentationMailbox.begin()` opens the new
surface lifecycle. Surface teardown first makes presentation effectively unusable,
closes the mailbox, stops rendering and the overlay sampler, then detaches the
per-surface diagnostic owners and clears publication state. A stale renderer may
finish against its old object, but that object is no longer sampled or owned by a
replacement surface.

Visibility boundaries reset sampler windows and `PerformanceDiagnostics` active
state. When FPS or COAL is selected, effective-visible activation serializes
`FrameMetrics.abandonPendingFrames(currentMailboxSequence)` with renderer
`recordRender(sequence)`. Publications pending before activation are therefore
absorbed into the boundary rather than appearing as new-window FPS/COAL. A stale
renderer callback either finishes before the boundary and is covered by the new
sampling baseline, or executes afterward and is rejected because its sequence is
not newer. No extra epoch or publication counter is required.

Queued input carries its source generation so old events cannot enter a resumed
window. Publication timestamps and sequences are captured together under the
buffer lock. Repeated or stale render callbacks cannot contribute new samples.
