# Managed Java Memory Editor benchmark plan

This document defines a repeatable benchmark for the current Managed Java-only
Memory Editor. It is the source of truth for performance work after the Raw
native editor was removed. Do not use the former native ResultStore, resident
range, GC-relocation, or process-memory read/write plans as current baselines.

## Current process boundary

The production implementation has two relevant app processes:

- `:midlet` owns `MemoryTargetBridgeService` and `ManagedJavaMemoryEngine`,
  including target traversal, revisions, history, watches, and freeze state.
- `:memory_engine` owns the Binder coordinator and Memory Editor Activity. It
  forwards logical operations and renders the result; it does not own physical
  memory addresses or a second search database.

Record both processes when measuring memory. A visible editor sample is
`midlet + memory_engine + UI`; a hidden-editor sample may still include the
`:midlet` engine and must be labelled accordingly. Never compare samples with
different process visibility or different MIDlet lifecycle state.

## Rules

- Record device model, Android version, ABI, build type, commit SHA, package
  name, MIDlet/JAR fingerprint, and whether the MIDlet was freshly launched.
- Keep the game state and screen orientation stable across repetitions.
- Warm up the MIDlet normally, but do not run an unreported Memory Editor
  operation before the timed scenario.
- Run each scenario at least five times when practical. Report median and
  observed range, not only the fastest run.
- Record successful, cancelled, stale, identity-unsafe, and resource-limit
  results separately. A safe rejection is not a performance success.
- Do not raise traversal, candidate, history, or storage limits to make a
  scenario finish.
- Do not add per-candidate logging to timed runs.
- Stop the scenario and repeat from a fresh runtime if the MIDlet ends or the
  target becomes unavailable.

## Measurement procedure

Measure elapsed time from the operation request reaching the editor controller
until the matching completion callback updates the UI. Use the same procedure
for all operation types and record the operation result code.

Capture process memory outside the timed interval with the Android shell:

```text
adb shell pidof <package>:midlet
adb shell pidof <package>:memory_engine
adb shell dumpsys meminfo <package>
```

Record at least PSS and RSS for `:midlet`, `:memory_engine`, and their total.
The process names may be absent when the editor/runtime is not active; record
that state instead of substituting a different sample.

Record the logical engine counters available from the session/result callback:

- operation result code and diagnostic message;
- result count and Unknown baseline count;
- committed revision and history depth;
- watch count and Freeze Lock count;
- page materialization time and page size where relevant.

For future targeted instrumentation, prefer bounded counters for visited owners
and primitive slots visited. Keep those counters debug-only and pull them after
the timed operation; they must not allocate per-candidate objects or change the
production result contract.

## Required scenarios

Run each scenario with a small, medium, and large MIDlet where available.
Include both rare-match and common-match cases for Known searches.

### Known search

Measure:

1. explicit `Int32` equality;
2. explicit `Float32` equality;
3. `Auto` equality;
4. representative negative, zero, positive, and boundary-adjacent values.

Record elapsed time, result count, revision, history depth, and both process
memory samples. Verify that the displayed result set remains usable for page,
Watch, Edit, Inspector, and Freeze operations.

### Unknown search and relative refine

Measure separately:

1. Unknown baseline capture;
2. first `Changed` or `Unchanged` refine;
3. subsequent `Increased`/`Decreased` or magnitude refine;
4. Undo back to the baseline stage.

Record baseline count, result count, revision transitions, history depth, and
the time between each operation. Confirm the first baseline already has a
materializable result list and can be added to Watch before refining.

### Group same-owner search

Use plain semicolon terms such as `75;100` with one concrete type. Cover:

- two fields on one object;
- duplicate terms such as `7;7` requiring distinct slots;
- two terms on one primitive array;
- two static fields on one declaring class;
- terms split across different objects, which must produce no group match;
- a large irrelevant primitive array of another type, which must be skipped
  before array-size limits are applied.

Record result count, owner-sharing behavior, elapsed time, and process memory.

### Filter, Undo, and lifecycle

Measure these transitions as independent scenarios:

```text
Known → Filter → Undo
Known → refine → Undo
Unknown baseline → Changed → Undo
Search → Clear Search
Search → Watch → Undo → Clear Search
```

Verify that Undo publishes a fresh revision, stale mutations are rejected,
Clear Search resets history depth to zero, and Watch remains independent of the
search revision.

### Watch and Freeze Lock

Measure Watch refresh with one and many logical candidates, then measure a
short Freeze Lock interval. Record refresh/tick latency, write confirmation,
paused/lost states, watch count, freeze count, and both process memory samples.

### Cancellation and runtime loss

Cancel one large Known/Unknown operation and one large refine. Record the delay
from cancellation to completion and verify that the previously committed
revision remains available. Repeat once with the MIDlet ending during the
operation and verify that the result fails closed without publishing a partial
revision.

## Correctness fields beside every timing

Retain at least:

- commit SHA and build variant;
- device/Android/ABI and MIDlet/JAR fingerprint;
- scenario, query text, concrete type, predicate, and compare baseline;
- editor visibility and process-lifecycle state;
- operation result code and diagnostic message;
- elapsed nanoseconds;
- result count, Unknown baseline count, revision, and history depth;
- Watch/Freeze counts;
- `:midlet` PSS/RSS, `:memory_engine` PSS/RSS, and total PSS/RSS;
- cancellation, target-loss, identity-safety, or resource-limit observations.

Do not accept a timing result if the logical result set, revision transition,
or safety outcome differs from the expected scenario. Performance claims must
be tied to the current Managed Java implementation and the measured process
boundary, not to architecture claims from the removed Raw backend.

## Optimization policy

Start with measurements on the Poco F7 and a small set of heavy MIDlets. Do not
preemptively introduce another process, custom serialization, disk-backed
results, JVMTI, or shared memory. If `:midlet` heap pressure is material,
identify the largest retained revision/history or traversal allocation first,
then make the smallest targeted optimization and rerun this plan.
