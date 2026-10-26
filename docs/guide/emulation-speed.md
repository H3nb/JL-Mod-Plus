# Emulation Speed

Some Java ME games move too slowly on modern devices; others benefit from slowing down during difficult sections. **Emulation Speed** changes supported game timing while a MIDlet is running, without editing the game's original files.

The selectable range is **0.25× to 16×**. A value below **1×** slows supported timing operations, above **1×** speeds them up, and **Reset** returns the session to 1×.

## What changes when you choose a multiplier?

JL-Mod Plus adjusts timing operations used by compatible game code. The displayed multiplier describes the virtual timing relationship; it is not a promise that the game will draw, simulate, or respond exactly that many times faster.

A game may advance according to timed waits, the wall clock, its rendering loop, or a mixture of those mechanisms. Heavy processing can become the limit even when the virtual clock is accelerated. Sound playback has a separate timebase and is **not** accelerated by Emulation Speed.

This is why 4× may feel different between two games, and why measuring FPS alone is not a reliable way to judge the actual gameplay speed.

## Clock Mode: emulated or real time

Clock Mode is a separate setting in the MIDlet's configuration:

- **Emulated Clock:** relative delays and guest wall-clock reads follow the selected speed.
- **Real-World Clock:** date and time readings stay aligned with real time, while supported relative delays still follow the multiplier.

For a game that uses the clock to calculate progress, the difference can be noticeable. If a game behaves oddly at a non-default speed, try its original 1× setting first, then compare the two Clock Modes.

**Clock Mode is saved with the configuration. Emulation Speed is not.** Every new MIDlet session starts at **1×**.

## What happens to waits already in progress?

Some guest sleep and wait operations use the multiplier present when the wait begins. Changing speed does not necessarily shorten an already-running wait. It affects later timing operations and supported scheduled work according to their normal semantics.

This is most noticeable in games that pause for a fixed period between screens or actions.

## When it doesn't work as expected

**The speed setting changes, but gameplay barely does.** The game may be limited by processing or rendering, or it may use timing mechanisms that the compatibility transform cannot control.

**The game becomes unstable at a high multiplier.** Restore 1×. Then test a moderate setting or another Clock Mode, changing one thing at a time.

**The game goes back to normal speed after reopening.** That is intentional; the selection is session-only.

**There's no Auto mode.** Automatic selection was removed because rendering throughput is not a dependable measure of the fastest safe simulation speed across different MIDlets.

**Technical reference:** [Guest timing and frame pacing](../emulation-speed.md)
