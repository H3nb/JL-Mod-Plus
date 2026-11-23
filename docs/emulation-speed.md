# Emulation speed

Emulation Speed is a manual, session-only control from 0.25x to 16x. A fresh
MIDlet session starts at 1x; Reset returns the active session to 1x. Choosing a
speed does not modify the persisted MIDlet profile. The control requires a
compatible timing transform in the installed MIDlet. Newly converted MIDlets record
an SHA-256 fingerprint of the installed DEX; the runtime disables speed control
when another emulator replaces that payload without updating the descriptor.
Older installations without a fingerprint receive conservative structural DEX
checks for untransformed clock calls. An inconclusive inspection does not prove
incompatibility, and the normal launcher reconversion policy remains unchanged.

## Timing ownership

`TimingSession` owns the guest clock and the selected multiplier. Speed changes
re-anchor the clock at its current value so guest time remains continuous.
Transformed guest calls use the parent-owned `GuestTimingBridge`; the bridge
contains no speed governor or frame diagnostics. Its transformed-call ABI is
retained for already converted MIDlets.

Clock Mode remains a persisted compatibility choice. Emulated Clock scales
guest wall time along with relative delays. Real-World Clock keeps date/time
reads real while relative delays still follow the selected speed. Games that
derive simulation progress from wall time can therefore respond differently in
the two modes.

Guest sleep and timed monitor wait sample the multiplier at entry. An ongoing
wait retains that timeout after a speed change. Monitor ownership, notify, and
interruption semantics remain part of the Java ME compatibility boundary.
Guest Timer schedulers reevaluate their deadlines after a speed change and
retain their fixed-delay/fixed-rate behavior.

## Frame pacing and diagnostics

The compatibility frame ceiling scales with the selected multiplier. Host
presentation and UI scheduling use host time. Media playback retains its own
timebase and is not accelerated by this control.

Canvas creates an optional per-surface frame counter only when Show FPS is
enabled. The presentation mailbox owns publication sequences, and the counter
observes distinct frames consumed by the renderer. It resets across visibility
and surface lifecycle transitions and does not affect pacing or guest clocks.

The displayed FPS describes renderer consumption, not game simulation steps or
the emulation-speed multiplier. CPU-bound work, I/O, scheduler precision, and
game-specific loop behavior can limit the effect of a speed selection.

The former Auto mode has been removed. Frame throughput cannot establish a
general fastest stable gameplay speed across MIDlets with different update,
paint, frame-skipping, and media synchronization behavior.
