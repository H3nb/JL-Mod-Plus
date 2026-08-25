# JL-Mod Plus Emulation Speed Implementation Tasking

Status: timing foundation and host-side safety gates implemented on a dedicated branch; device/corpus readiness remains manual-validation-gated.

Created: 2026-08-23

Branch audited: `alpha` at `73791a1e` (the implementation branch is based on this integration point)

This is a local planning document. Keep it untracked until its work packages are turned into focused implementation PRs.

## Implementation checkpoint — 2026-08-23

Branch: `codex/emulation-speed-foundation`

The first implementation slice is now committed as focused commits and is ready to be reviewed as one PR. It includes:

- exact 25–1600% speed state, monotonic guest clock mapping, overflow-safe duration math, and injectable timing sources;
- a parent-owned `TimingSession`/`GuestTimingBridge` with generation-safe lifecycle cleanup;
- transformed guest `currentTimeMillis`, `nanoTime`, `sleep`, finite `Object.wait`, `Calendar.getInstance`, and guarded canonical `Date()` call sites;
- session-bound emulator `Timer`/`TimerTask` scheduling and finite LCDUI `Alert` deadlines;
- a session-scoped compatibility `FramePacer` that uses monotonic host time, scales the existing FPS ceiling by `C × S`, and never blocks inside an LCDUI callback;
- sequence-aware Game FPS / Render FPS telemetry across the Canvas, Surface, and GLES presentation paths, with repeated host redraws excluded from Render FPS;
- a bounded latest-frame `PresentationMailbox` with lifecycle generations integrated into the synchronous Surface (bounded drain plus generation-safe retry), parallel Handler, GLES, and CanvasView paths to close their producer/renderer lost-wakeup races;
- a converter-level DEX fixture that sends clock, sleep, wait, Date, and Calendar rewrites through the real dx pipeline and parses the resulting DEX;
- converted-artifact transform metadata and an explicit 1x fallback/disabled-control policy for incompatible artifacts;
- persisted MIDlet configuration controls, session-only Android Back-menu speed selection, and a host-only timing monitor that reports target and measured guest speed without requesting guest renders.

Focused unit, ASM transformation, Java compilation, configuration, bridge, session, monitor, metadata, timer lifecycle, pacing, mailbox, frame-metric, and converter-level DEX tests pass. The final full app unit suite completed 388 tests with zero failures or errors; the dexlib ASM/DEX suite completed 5 tests with zero failures or errors; an earlier socket teardown timeout was not reproduced and remains outside this branch.

The non-device gates are now closed: synchronous Surface redraw uses the mailbox and its 1× latest-frame coalescing contract is characterized, while the converter fixture verifies every rewritten call-site family, including guarded `Date()`. This is still deliberately not the final universal acceleration claim: device/corpus validation across representative games remains before widening the compatibility claim. Runtime controls stay artifact-gated for incompatible converted artifacts.

The audit and contract sections below preserve the pre-implementation baseline used to define the work packages. The checkpoint above is the current branch status and takes precedence where the source has already advanced.

## Decision

Issues [#98](https://github.com/H3nb/JL-Mod-Plus/issues/98) and [#99](https://github.com/H3nb/JL-Mod-Plus/issues/99) describe the right long-term architecture:

- speed changes guest logical time, not Android's global clock;
- compatibility FPS pacing remains a separate control;
- guest clock, rendering, Android presentation, audio, lifecycle, and external timestamps stay in explicitly separated domains;
- rendering may coalesce complete guest frames but may not expose partial buffers;
- a single converted artifact must work at every speed.

The current branch is ready for constrained manual device/corpus validation, but **not** ready to claim a final universal speed-control integration. The host-side architecture and converter gates are complete; representative devices and games must still validate lifecycle, pacing, rendering, UI controls, and compatibility behavior before the claim is widened.

Do not implement this as any of the following:

- disabling `FpsLimit`;
- changing Android/process-wide time APIs;
- injecting a branch into every guest loop;
- tying guest time to Activity visibility or MIDP `pauseApp()`;
- requiring Android to physically present every guest frame;
- silently lowering a requested speed or silently falling back to 1x after failed conversion.

## Product contract

`S` is the exact target emulation speed and `C` is the existing compatibility FPS limit expressed at 1x.

```text
effective frame ceiling = C x S
```

`C` and `S` solve different compatibility problems and must remain independently configurable. Unlimited `C` means no compatibility pacing; it does not disable logical guest-time acceleration.

| Guest-scaled by default | Host/unscaled by default |
|---|---|
| `System.currentTimeMillis()` | Android lifecycle, service, watchdog, ANR, and crash timing |
| explicit `Thread.sleep(long)` | Android UI scheduling, input repeat, and renderer scheduling |
| finite `Object.wait(long[, int])` | audio/MMAPI and physical vibration/backlight duration |
| emulator-owned `Timer`/`TimerTask` | network, file/platform, RMS, HTTP, and PushRegistry timestamps |
| no-arg `Date`, CLDC `Calendar.getInstance` | the existing generated `Thread.yield()` compatibility sleep |
| finite LCDUI Alert timeout | physical display scanout and display refresh |
| compatibility `FramePacer` | |

At a non-1x speed, guest-visible elapsed time intentionally diverges from physical elapsed time. This is necessary for games that derive movement from `currentTimeMillis()`. It cannot promise universal acceleration for pure CPU-bound loops, hardware/API-limited games, or external/audio-driven behavior.

## Current-state audit summary

### Rendering and event delivery

`javax.microedition.lcdui.Canvas` currently has a static FPS limit but a per-Canvas `lastFrameTime`. `limitFps()` uses wall-clock milliseconds and `Thread.sleep()`. It is called before no-op validation in `repaint()` and both flush paths, may run from a callback path, and swallows interruption after printing it. The source also makes direct Surface presentation synchronous for some backend settings.

`EventQueue` serializes normal event delivery through `callbackLock`, but its optional immediate mode explicitly processes events inline and documents that it violates serialization. In that mode `serviceRepaints()` returns without logical paint synchronization. That legacy compatibility mode cannot be treated as a normal MIDP event-delivery implementation without an explicit, tested policy.

The current `offscreenCopy` lock prevents ordinary renderer reads from observing an in-progress copy, which is a useful starting point. It is not a bounded sequence-aware mailbox. `requestRender()`, `postInvalidate()`, direct Surface drawing, and the Handler path have different blocking/coalescing behavior. The Handler path has no sequence handoff to close the producer/consumer lost-wakeup race.

`FpsCounter` measures raw host renderer/surface callbacks and refreshes with a host `java.util.Timer`; it does not distinguish a newly published guest frame from a redraw of the same frame. It must be replaced by the [issue 98](https://github.com/H3nb/JL-Mod-Plus/issues/98) Game FPS / Render FPS metrics contract rather than repurposed as proof of speed.

### Guest timing and transformation

The correct transform boundary already exists in `dexlib`:

- `AndroidMethodVisitor` already remaps guest `Timer`/`TimerTask` and rewrites `Thread.yield()` to a real one-millisecond sleep.
- `AndroidProducer` instruments guest classes before DEX generation.
- emulator/framework classes are not part of the converted guest artifact.

There is no `GuestTimingBridge`, `TimingSession`, transformed-artifact version, bridge ABI metadata, or timing-transform test fixture yet. `AndroidProducer` uses ASM `COMPUTE_MAXS`, not recomputed frames. Date-constructor and stack-sensitive callsite rewrites therefore need verifier and Android DEX tests before they can be trusted.

The custom `javax.microedition.shell.custom.Timer` is the right single implementation to evolve, but it currently derives deadlines from host `System.currentTimeMillis()` and waits on its own monitor. Preserve and characterize its 1x behavior before replacing its time source. Its fixed-rate/fixed-delay behavior, cancellation, `purge`, task-exception behavior, and `scheduledExecutionTime()` are compatibility contracts.

`Alert` currently delegates finite timeouts to `ViewHandler.postDelayed`, does not cancel a previous runnable when an Alert is replaced/closed, and accepts invalid timeout values. Fix those existing semantic gaps as part of the logical Alert deadline coordinator; do not just multiply Android Handler delays.

### Session, class loading, and artifacts

`MicroActivity` runs in Android's `:midlet` process and `MicroLoader.loadMIDlet()` provides a clear pre-class-load insertion point. This is a strong basis for a `TimingSession`.

However, `:midlet` is a process shared by all `MicroActivity` instances, not a process created per MIDlet. Current static runtime state (`AppClassLoader`, `Canvas`, `MidletThread`, and context holders) already assumes one active runtime. The new feature must make that invariant explicit: either enforce one active MIDlet/activity or reject/tear down a previous session before publishing a new one. Every delayed Timer, Alert, pacer, and renderer callback needs a generation token.

`AppInstaller` preserves the original `res.jar` and already stages/replaces converted directories with recovery support. That is a useful recovery foundation. Its `InstallerExecutionCoordinator` is process-local, while installation runs in the main process and a MIDlet may be loading DEX in `:midlet`. A transform migration needs an OS-visible file lock or an immutable-generation/pointer design. Never rewrite a DEX artifact concurrently with `AppClassLoader` creation.

The bridge must be guaranteed to resolve from the emulator parent classloader. `CoreClassLoader` supports configurable exclusions, so a guest JAR must not be able to shadow `GuestTimingBridge` due to a classpath exclusion or a package collision.

### Profiles and validation

`ProfileModel.VERSION` is currently 3. A missing JSON primitive speed field would deserialize as zero, so a schema bump plus an explicit v3-to-v4 migration is mandatory. The migration must set a validated exact default of 100%, preserve `FpsLimit` and `ShowFps`, and define independent defaults for a future Show Speed / detail-mode setting.

The current full host unit suite does not have timing, display-mailbox, or DEX-transform coverage. `dexlib` has no test source set. On 2026-08-23, `:app:testEmulatorDebugUnitTest` compiled both app and dexlib but failed reproducibly in the current checkout at `SocketConnectionTest.closingInputFromAnotherThreadUnblocksRead` (a two-second timeout). This is not a timing test, but the baseline must be triaged before a feature branch can claim a green full-suite gate.

## Local documentation and sample evidence

The supplied documentation path resolved locally as:

```text
D:\Personal\J2ME_Docs\J2ME_Docs_AI_Friendly\docs
```

Relevant contract sources are CLDC 1.1 `Object`, `Thread`, `System`, `Timer`, `Date`, and `Calendar`, plus MIDP 2.0 `Canvas`, `Display`, `GameCanvas`, and `Alert`.

The MIDP/CLDC implications that must remain observable are:

- `Canvas.repaint()` is asynchronous and may coalesce.
- `Canvas.serviceRepaints()` provides logical paint synchronization; it is not a physical scanout barrier.
- `Display.callSerially()` stays asynchronous, ordered, and serialized with display events; a pending paint completes before its runnable.
- `GameCanvas.flushGraphics()` makes the guest buffer safe to reuse after return, but hidden/busy cases may return immediately and physical scanout is not required.
- `Thread.sleep()` preserves interruption, validation, zero-duration behavior, and held monitors.
- timed `Object.wait()` preserves monitor ownership, release/reacquisition, `notify`/`notifyAll`, interruption, and argument validation.
- fixed-rate and fixed-delay `Timer` schedules remain distinct.
- no-arg `Date` and `Calendar.getInstance` expose current time; explicit timestamp inputs retain their exact value.

### 2026-08-23 static sample scan

The current local corpus contains 10,936 game JARs and 16 application JARs (10,980 total). A read-only diagnostic selected 400 games deterministically using SHA-256 rank of `98 + newline + relative path`, then included all 16 applications. It inspected 13,522 class entries across 416 readable archives; no archive or large class entry was skipped.

| Constant-pool signal | Archives | Presence |
|---|---:|---:|
| `System.currentTimeMillis` | 356 | 85.6% |
| `Thread.sleep` | 311 | 74.8% |
| `Object.wait` | 123 | 29.6% |
| `java.util.Timer` | 67 | 16.1% |
| `Canvas.repaint` | 318 | 76.4% |
| `Canvas.serviceRepaints` | 207 | 49.8% |
| `GameCanvas.flushGraphics` | 52 | 12.5% |

This is API-presence evidence only, not invocation frequency or a claim that a game will accelerate correctly. The manifest/selection definition and, where legally permissible, hashes must be recorded whenever the scan is used as implementation evidence. Do not commit copyrighted JARs.

Targeted binary inspection shows mixed patterns across real titles:

| Title | Observed timing/display references |
|---|---|
| Asphalt 3 - Street Rules | current time, sleep, Timer, GameCanvas flush |
| Asphalt 3 - Street Rules 3D | current time, sleep, wait, Timer |
| Need for Speed - Most Wanted | current time, sleep, wait |
| Real Football 2010 | current time, sleep, wait, repaint, serviceRepaints |
| Sonic Advance | current time, sleep, GameCanvas flush |
| Sonic Unleashed | current time, sleep, wait, repaint |

## Architecture invariants

```text
Profile (exact percent) ──> TimingSession (one active MIDlet, generation N)
                                  │
        ┌─────────────────────────┼────────────────────────────┐
        │                         │                            │
 GuestTimingBridge          custom Timer / Alert           FramePacer
 transformed guest calls       guest deadlines             base FPS C
        │                         │                            │
        └──────────> immutable ClockState <─────────────────────┘
                              │
                   bounded PresentationMailbox
                              │
                    Android renderer / Surface / View
```

1. `ClockState` is immutable and read through one atomic/volatile reference. It contains host monotonic anchor, guest wall/monotonic anchors, exact numerator/denominator or integer-percent speed, fractional remainder, and session generation.
2. Guest monotonic time is nondecreasing and is the deadline domain. Guest wall time is epoch-based and used only for guest current-time APIs and conversion of absolute guest dates.
3. A speed transition first computes current guest time under the old state, then re-anchors at the same host monotonic instant. It must never make guest time move backward.
4. A fresh session that never left 1x may delegate through the bridge to native-equivalent host APIs where safe. A session that returned from non-1x to 1x remains on its continuous mapped clock until teardown.
5. Guest timed waits use the speed snapshot at call entry. A slider update may wake emulator-owned Timer/Pacer waits but must never `notify` a guest monitor or implement wait through polling.
6. Producer completion publishes a complete frame plus a sequence. Renderer consumption is latest-complete-frame-wins and bounded; it never requires guest code to wait for physical scanout.
7. Stale callbacks cannot mutate a new session, Canvas, Alert, or renderer state after teardown/replacement.
8. Metrics use host monotonic time. Game FPS counts complete guest publications; Render FPS counts consumption of a new published sequence.

## Runtime UI/UX contract

### One model, two entry points

The feature needs both a persistent configuration path and an in-session path. They are two views of one validated profile/session state, not two competing speed settings.

- **MIDlet configuration:** add `Emulation speed` and `Show emulation speed` to the existing **Display & performance** section, beside the existing `Show FPS` and `Limit FPS` preferences. The configuration screen is the durable place to select the launch/default speed and whether the speed monitor is visible.
- **Running MIDlet:** Android system Back already opens `RuntimeMenuDialog` without synthesizing a guest Back key. Add `Emulation speed` to its Canvas/performance actions. It is the primary quick-control entry point during play, not a new global toolbar or a second overlay.
- **Single source of truth:** `ProfileModel`, `ConfigFormState`, the configuration screen, and the active `TimingSession` must use the same exact integer percentage and independent visibility flags. A runtime change is successful only after the active session and persisted profile agree; a write/update failure keeps the previous state and shows an app-owned error rather than silently falling back.

The initial schema should store a validated `emulationSpeedPercent` in the inclusive 25--1600 range and a distinct `showEmulationSpeed` Boolean. `showFps` and `fpsLimit` retain their present meaning; neither implies a speed setting or monitor visibility. Migrate older profiles to 100% and `showEmulationSpeed = false`.

### Configuration-screen design

`Emulation speed` is a discrete selection preference, not unrestricted numeric input. The initial curated choices are 0.25x, 0.5x, 0.75x, 1x, 1.25x, 1.5x, 2x, 3x, 4x, 6x, 8x, 12x, and 16x. The selected value is shown in the preference summary as an exact value such as `2x`; 1x is the explicit normal-speed/reset option.

The persistent monitor controls are independent switches:

- `Show emulation speed` shows the speed monitor.
- `Show FPS` keeps its existing behavior, but after WP1 it uses the Game FPS / Render FPS metric contract rather than raw view callbacks.

The section must explain in localized text that emulation speed changes guest time and compatible delays, while audio remains normal-speed. Configuration changes made before launch take effect on the next session; a configuration route that edits an already active profile must use the same atomic active-session update as the runtime menu.

### Back-menu and speed-picker flow

The Back flow is deliberately host-owned:

```text
Android Back while MIDlet runs
  -> Runtime menu
  -> Emulation speed (summary: selected target, for example 2x)
  -> Emulation-speed picker
  -> validate + atomically persist/apply
  -> return to the running MIDlet
```

The speed picker follows the existing Material runtime-dialog pattern used for the FPS limit. It must:

- visibly mark the current target;
- expose only the exact curated presets above, with an explicit 1x option;
- state: `Changes game time and compatible delays. Audio stays at normal speed.`;
- state: `Higher targets may reduce frame smoothness when the device cannot keep up.`;
- apply a chosen value immediately without restarting the MIDlet, then dismiss to the running MIDlet;
- dismiss with Back/Cancel without changing the selected value;
- treat Back while a child picker is open as dismissal of that picker first, then dismissal of the runtime menu on the next Back; it must never emit a MIDP Back key event;
- preserve the current guest geometry, lifecycle, clock state, and render scheduling merely because the host menu/picker is visible.

During staged rollout, do not ship a disabled or placebo speed item. Hide the runtime item until the active `TimingSession`, profile migration, and transform compatibility gates are implemented; expose it first through the internal feature gate.

### Timing monitor, not a misleading FPS substitute

The requested debug-style tool is a compact **Timing monitor**. It defaults off and is display-only: it reads immutable telemetry snapshots and must never publish a guest frame, advance a renderer sequence, wake a guest monitor, or schedule a redraw merely to update itself.

When `Show emulation speed` is enabled, it shows the requested target plus a measured **clock rate**. The clock rate is a rolling ratio of guest-monotonic elapsed time to eligible host-monotonic elapsed time. It may be displayed as `Target 2x | Clock 2.0x`.

Do not label that value `actual game speed` and do not derive it from FPS. No generic emulator can infer title-specific gameplay progress from rendered frames: a game may intentionally run at 15, 30, or 60 FPS, may redraw an unchanged image, or may do most work outside a render loop. If performance is overloaded, retain the selected target and surface independently measured pacing/deadline/coalescing diagnostics in a detail view rather than falsely lowering the reported clock rate.

When `Show FPS` is also enabled, append Game FPS and Render FPS from WP1. The four supported visibility states are:

| Show emulation speed | Show FPS | Overlay result |
|---|---|---|
| Off | Off | No timing overlay |
| On | Off | Target and clock rate |
| Off | On | Game FPS and Render FPS |
| On | On | Compact combined timing monitor |

The runtime menu may expose a `Timing monitor` action/toggle as a convenience, but it changes the same two persisted configuration flags. On narrow or landscape displays, use the existing dialog layout/insets rules, scroll where needed, retain accessible names and at least standard touch targets, and localize all labels. The initial monitor deliberately has no audio-status row.

## Work packages and gates

### WP0 — establish a trustworthy baseline

Scope:

- Triage the reproducible socket unit-test timeout or document an approved deterministic environment constraint before relying on a green full-suite claim.
- Add a timing test inventory; do not modify timing behavior.
- Record current behavior for every `graphicsMode x parallelRedrawScreen x immediateMode x visibility` combination.
- Add characterization fixtures for repaint, `serviceRepaints`, `callSerially`, hidden Canvas, full/regional `flushGraphics`, and Alert replacement.

Exit gate:

- current behavior is named and tested before refactoring;
- test failures are attributable rather than silently ignored;
- no guest timing has changed.

### WP1 — issue 98 display semantics and frame ownership

Scope:

- Introduce a session-scoped monotonic `FramePacer`; remove scattered `Canvas.limitFps()` sleeping behavior only after equivalent path-specific pacing exists.
- Add an EventQueue callback-depth/context API. Never sleep in LCDUI callbacks, paint, key/pointer handlers, command handlers, or `callSerially` handlers.
- Add a bounded `PresentationMailbox` with complete-frame ownership, sequence number, edge-triggered renderer scheduling, and lifecycle generation.
- Preserve the current serial staging lock first if it is safer than an early double/triple-buffer implementation.
- Replace raw callback FPS counting with Game FPS, Render FPS, coalesced-frame, and deadline-miss metrics.
- Define the immediate-mode policy explicitly. Do not silently claim normal asynchronous repaint semantics while it is enabled.

Exit gate:

- no pacing under `bufferLock`, `surfaceLock`, EventQueue locks, Canvas locks, or renderer locks;
- no logical repaint waits for physical presentation;
- no lost wakeup after a producer publishes while renderer scheduling is being cleared;
- backend matrix tests pass at 1x and unlimited/finite `C`;
- old 1x behavior has focused regression evidence.

### WP2 — session, classloader, artifact, and profile foundation

Scope:

- Add a `TimingSession` lifecycle owned by the active MIDlet runtime, with an idempotent close and monotonic generation.
- Enforce/serialize the one-active-MIDlet invariant in `:midlet`; do not rely on existing static fields as implicit protection.
- Add a host-owned `GuestTimingBridge` that cannot be shadowed by a guest archive or configurable classpath exclusion.
- Define a private transform sidecar: source JAR identity, transform version, bridge ABI, integrity/hash, output generation, and conversion result.
- Preflight metadata before `AppClassLoader` creation. Rebuild atomically from retained `res.jar`; preserve a prior valid artifact on failure.
- Use a cross-process lock or immutable artifact generations so installer/reconversion and `:midlet` loading cannot race.
- Bump `ProfileModel.VERSION`; persist exact speed percentage with validation and migration to 100%.

Exit gate:

- no class can load before compatible transform metadata and `TimingSession` are available;
- no artifact replacement can race DEX loading;
- malformed/missing/out-of-range profile speed cannot enter clock arithmetic;
- session replacement leaves no live emulator-owned callback tied to the old generation.

### WP3 — clock and explicit sleep transform

Scope:

- Implement exact rational/integer-percent `ClockState` using Android monotonic time.
- Remap only guest `System.currentTimeMillis()` and explicit `Thread.sleep(long)` callsites.
- Keep generated yield-to-host-sleep(1 ms) below the transform boundary so it is never scaled.
- Add ASM fixtures, DEX conversion verification, and runtime tests for zero/negative/small/overflow durations, interruption, monitor ownership, 0.25x, 1x, and 16x.

Exit gate:

- same converted artifact operates at all speed values;
- fresh 1x is native-equivalent at the API boundary;
- speed changes are continuous, exact, and race-free;
- conversion failure is recoverable and never silently downgrades requested non-1x behavior.

### WP4 — timed waits, Timer, Date, Calendar, and Alert

Scope:

- Remap both CLDC timed `Object.wait` descriptors to static bridge helpers that preserve JVM monitor semantics.
- Move custom Timer deadlines to guest monotonic time while preserving one worker thread, sequential callback ordering, fixed-delay/fixed-rate distinction, cancellation, purge, and guest `scheduledExecutionTime()`.
- Rewrite only no-arg `Date` construction and CLDC `Calendar.getInstance` factories; explicit time values stay exact.
- Replace Alert `postDelayed` ownership with a logical guest deadline and generation-checked cancellable runnable.

Exit gate:

- no synthetic `notifyAll`, polling wait, or guest-monitor wake on speed change;
- timer catch-up is ordered and bounded without dropping tasks;
- replacing/closing Alert cancels stale callbacks;
- all validation/exception contracts are tested at 1x and non-1x.

### WP5 — controls, overlay, and release gating

Scope:

- Add the persistent configuration controls in the existing Display & performance section and the Back -> Runtime menu -> Emulation speed picker using the shared exact 25-1600 percentage state.
- Keep FPS limit, Show FPS, emulation speed, and Show emulation speed independent. Runtime convenience controls must persist through the same profile path as configuration edits.
- Compose the Timing monitor from immutable snapshots. It reports target and measured clock rate, with Game FPS / Render FPS only when the existing FPS switch is enabled; overlay-only work cannot create a guest frame or renderer sequence.
- Preserve host Back semantics, dialog dismissal behavior, guest geometry, and active-session lifecycle while either popup is visible.
- Keep audio normal-speed and do not introduce an audio-status overlay row.

Exit gate:

- malformed settings safely resolve to 100% with diagnostic evidence;
- runtime/profile changes agree on the same underlying state;
- target versus measured clock rate is clearly defined rather than inferred from FPS or presented as universal game-progress speed;
- configuration, runtime menu, Back/Cancel, narrow/landscape layout, and the four timing-monitor visibility combinations have focused UI tests;
- feature remains opt-in/internal until corpus and backend gates pass.

### WP6 — corpus and device validation

Scope:

- Maintain a legal deterministic sample manifest/selection algorithm, not copyrighted archives in the repository.
- Test time-driven, sleep-driven, wait-driven, timer-driven, frame-driven, and mixed games.
- Exercise all graphics backends, orientation/surface recreation, hidden/show transitions, lifecycle pause/resume, low/high speed, and repeated speed changes.
- Test at least two Android/API/device-performance tiers before treating 16x telemetry as release quality.

Exit gate:

- documented game-specific exceptions are explicit profile policy, never automatic hidden behavior;
- high-speed overload reports actual shortfall rather than lowering target speed;
- crash, ANR, and stale-callback regression tests are clean.

## Required test matrix

| Layer | Minimum coverage |
|---|---|
| Pure clock | rational arithmetic, continuity, remainder retention, overflow saturation, 25/100/1600%, concurrent read/update |
| Bridge APIs | current time, sleep, wait overloads, Date, Calendar, interruption, validation, held monitor, no busy-spin |
| Transform | normal owner, legal alternative owner where applicable, idempotence, constructor verifier state, unsupported input fails cleanly |
| Custom Timer | one-shot, absolute Date, fixed delay, fixed rate, catch-up, cancel, purge, exception, generation teardown |
| LCDUI | repaint async/coalesced, `serviceRepaints`, `callSerially` ordering, hidden/busy no-op, full/regional flush buffer reuse, Alert replacement |
| Renderer | new sequence only, coalescing, lost-wakeup race, surface loss/recreate, resize/orientation, each backend matrix entry |
| Artifact | old/new sidecar, corrupted output, rollback, cross-process contention, launch before/after migration |
| Profile/UI | v3 migration, missing/invalid speed, independent Show FPS/Show emulation speed, config/runtime shared persistence, Back picker apply/cancel/dismissal, target summary, narrow/landscape accessibility, four monitor states |
| Corpus | selected representative real titles plus synthetic contract MIDlets |

## Source ownership map

| Concern | Primary current source |
|---|---|
| Canvas pacing, staging, rendering backend | `app/src/main/java/javax/microedition/lcdui/Canvas.java` |
| Event serialization/callback context | `app/src/main/java/javax/microedition/lcdui/event/EventQueue.java` |
| LCDUI ordering and Alert display | `app/src/main/java/javax/microedition/lcdui/Display.java`, `Alert.java` |
| Current FPS overlay | `app/src/main/java/javax/microedition/lcdui/overlay/FpsCounter.java` |
| Guest Timer replacement | `app/src/main/java/javax/microedition/shell/custom/Timer.java`, `TimerTask.java` |
| MIDlet lifecycle/loading | `MicroActivity.java`, `MicroLoader.java`, `MidletThread.java` |
| Guest parent classloader | `AppClassLoader.java`, `CoreClassLoader.java` |
| Artifact conversion/publishing | `AppInstaller.java`, `InstallerExecutionCoordinator.java`, `LibraryInstallRecovery.kt` |
| Bytecode conversion | `dexlib/src/main/java/org/microemu/android/asm/AndroidMethodVisitor.java`, `AndroidProducer.java` |
| Profile persistence/UI | `ProfileModel.java`, `ProfilesManager.java`, `ConfigFormState.java`, `ConfigComposeBridge.kt`, `RuntimeMenuCompose.kt`, `MicroActivity.java` |

## PR boundaries

Keep separate focused PRs:

1. Baseline characterization and test infrastructure only.
2. Issue 98 FramePacer/mailbox/event-context work at 1x only.
3. Session/artifact/profile foundation, internally gated.
4. Clock/current-time/sleep transform.
5. Wait/Timer/Date/Calendar/Alert semantics.
6. UI/overlay and corpus release evidence.

Do not combine a renderer refactor, bytecode rewrite, profile schema bump, and UI control in one PR. Each PR must retain source attribution notices, avoid unrelated formatting/toolchain changes, and run its narrowest relevant validation. The final integration PR must run CI without `[skip ci]`.

## Final go/no-go checklist

- [ ] Issue 98 normal 1x display contract and backend matrix are green.
- [ ] Immediate mode has an explicit supported/unsupported policy.
- [ ] A single active `TimingSession` and generation-safe teardown are enforced.
- [ ] Guest bridge resolution and transformed-artifact ABI are protected.
- [ ] Conversion/reconversion is atomic across the main and `:midlet` processes.
- [ ] Profile migration makes 100% deterministic for every old/malformed input.
- [ ] MIDlet configuration and the Back runtime picker expose one exact, persisted speed state and independent monitor visibility switches.
- [ ] Timing monitor reports target and measured clock rate without claiming title-specific gameplay speed or creating guest/render work.
- [ ] No guest monitor semantics are replaced by polling or synthetic notification.
- [ ] Custom Timer and Alert use logical guest deadlines without stale callbacks.
- [ ] Renderer observes only complete frames and metrics count sequence consumption correctly.
- [ ] DEX verifier/instrumentation fixtures cover all stack-sensitive rewrites.
- [ ] Full baseline suite is either green or its unrelated failure has an approved, tracked resolution.
- [ ] Sample/corpus evidence and real-device backend validation support the release speed range.

Only after every item is satisfied may the normal user-facing Emulation Speed control be enabled.
