# Performance overlay

The profile's Performance Overlay switch retains the persisted `ShowFps` key.
`PerformanceOverlayMetrics` stores independent metric bits; missing fields in old
profiles use Standard, while an explicitly empty selection remains empty.
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

All rates and durations use host monotonic time, independent of the guest clock
multiplier. Speed is the applied setting, not achieved original-device speed.
There is no universal original-device FPS target for Java ME applications.

| Label | Measurement |
| --- | --- |
| FPS | Complete guest buffer publications per real second, including visually unchanged buffers. |
| CAP | Effective pacing target after the applied speed multiplier. It is not the game's native target or a guaranteed ceiling for non-blocking callback paths. FPS and CAP combine as `FPS actual/cap`; an unrestricted target is `∞`. |
| RFPS | New guest sequences consumed by the host renderer per real second; repeated draws of one sequence are excluded. Not physical display presentation. |
| SPD | Applied timing multiplier, with `AUTO` when selected by the governor. |
| FI / P95 / MAX | Mean, nearest-rank 95th percentile, and maximum gaps between complete guest publications. Not render work or input latency. |
| PAINT | Mean elapsed time in the guest paint callback; absent for games using only flush APIs. |
| COPY | Mean elapsed time in buffer copy operations. |
| SUB | Mean elapsed host renderer submission duration for a newly consumed sequence. Includes host work and waits; not CPU utilization, GPU completion, or display presentation. |
| INQ | Mean time from input event queue entry to guest callback dispatch. Not physical-touch-to-display latency. |
| FRQ | Mean time from publication to renderer acquisition of that buffer under the buffer lock, before drawing/uploading it. |
| COAL | Published sequences replaced before rendering, recorded per real second at consumption; not a percentage or configured frameskip. |
| CPU | Process CPU time divided by host elapsed time: 100% equals one occupied core; multiple threads may exceed 100%. Does not indicate CPU frequency or total device utilization. |
| RAM | Runtime process proportional set size (PSS), in MiB. Not all JL-Mod processes or just the guest's allocations. |
| JAVA / NATIVE | Used Java heap / allocated native heap in MiB; not additive components of PSS. |
| CPUT / GPUT | Hottest valid current CPU/GPU sensor reading from Android's hardware properties service, falling back to readable thermal zones with explicit component labels. Restricted or missing sensors produce `—`; battery readings are never substituted. |
| BAT | Android's reported battery temperature. |
| THRM | Android thermal severity, when supported. |
| REN / DISP | Host rendering backend / active display refresh rate reported by Android. Neither is the maximum supported display rate used by compatibility pacing. |

Text refreshes every 500ms. Frame rates use elapsed windows of at least one real
second. Timing statistics retain up to the newest 4096 samples within five real
seconds; sufficiently high event rates shorten this bounded window. CPU samples
update every second; PSS, heap, hardware temperature, and thermal samples update
every five seconds. Battery temperature follows Android battery broadcasts.
Only selected optional diagnostics are collected. Existing timing/governor frame
counters remain available to their independent runtime consumers.

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

Visibility and surface boundaries reset timing windows and sampler baselines.
Queued input carries its source generation so old events cannot enter a resumed
window. Publication timestamps and sequences are captured together under the
buffer lock. Repeated or stale render callbacks cannot contribute new samples.

Mailbox sequences restart with a new presentation surface. Frame counter
sequences belong to the counter owner and continue independently; all renderer
paths capture that owner and its sequence with the selected buffer. Activation
abandons previously pending sequences without resetting lifetime totals, so
pre-pause frames do not inflate the new visible window's COAL or RFPS.
