# Memory Editor v2 benchmark plan

This document defines the repeatable baseline used to compare the current candidate-centric backend with the incremental block/bitmap ResultStore work in issue #108 and PR #117.

## Rules

- Compare the same APK/build configuration, device, MIDlet, runtime state, search scope, and query values whenever comparing two backend revisions.
- Prefer a physical ARM64 device. Record Android version, device model, app commit, and whether the MIDlet was freshly launched.
- Warm up the MIDlet normally, but do not run a hidden warm-up Memory Editor search unless the scenario explicitly requires it.
- Run each scenario at least 5 times when practical. Report median elapsed time and the observed range; do not select only the fastest run.
- Record both successful and safely rejected operations. A cancellation, GC invalidation, identity failure, or resource-limit result is not a performance success.
- Do not raise safety/resource limits just to make a benchmark finish.
- Do not add per-candidate logging to timed runs.

## Debug instrumentation

Debug builds provide a non-exported `MemoryEngineDiagnosticsService` in the same `:memory_engine` process. It is pull-based and does not poll during normal scanning.

`MemoryEngineBenchmarkRunner` measures an operation from immediately before its Binder start call until the matching non-passive completion callback, then pulls one diagnostics snapshot.

The snapshot schema currently records:

- scan bytes scanned / total;
- logical result count;
- native search history depth;
- total process PSS;
- process RSS from `/proc/self/status`;
- Java-heap, native-heap, private-other, system, and swap memory summaries;
- native heap allocated bytes;
- Java runtime used heap bytes.

The diagnostics snapshot itself is intentionally taken **after** the timed interval so smaps/proc collection does not inflate the measured search latency.

Example operation wrapper from a debug/instrumentation caller:

```java
MemoryEngineBenchmarkRunner.Sample sample = MemoryEngineBenchmarkRunner.run(
        engine,
        diagnostics,
        () -> engine.startKnownSearch(token, scope, type, predicate, first, second),
        120_000L);
```

The diagnostics endpoint is debug-only. Use it for architectural A/B comparisons during v2 development. Before stable release, reproduce final headline numbers with a release-equivalent/profileable build and external process-memory sampling.

## ResultStore shadow parity probe

Before the ResultStore backend is allowed to publish user-visible results, debug builds provide a stateful differential probe:

```java
Bundle parity = diagnostics.validateKnownEqualShadow(type);
```

The first probe after target configuration validates a **first known `Equal` search**. When that succeeds, the debug shadow path retains the immutable ResultStore revision. If the legacy engine then performs a non-empty explicit-type **Next Scan `Equal`** without target reconfiguration, calling the same probe again validates a transactional bitmap refine of that retained revision. The diagnostics payload reports `shadowOperation=SCAN` or `shadowOperation=REFINE`; never treat a fresh `SCAN` as evidence for refine parity.

Use the first-scan probe only under all of these conditions:

1. the immediately preceding committed search is a first known `Equal` search;
2. exactly one primitive type is selected (`Byte`, `Short`, `Char`, `Int`, `Long`, `Float`, or `Double`), not `Auto`;
3. the legacy result is non-empty;
4. no refine/filter/Undo has changed the result revision since that first search;
5. the MIDlet/game state is held as stable as practical while the parity probe runs.

For refine parity, first obtain a successful `SCAN` parity result, then perform exactly one non-empty legacy `Next Scan Equal` using the same explicit primitive type, hold the game state stable, and call the probe again. A successful refine sample must report `shadowOperation=REFINE`. Repeat this sequence for additional Equal refinements; a target reconfigure/clear intentionally discards the shadow revision and requires a new first-scan parity seed.

The probe deliberately performs remote reads only when called. Normal Memory Editor use does not run the shadow scanner/refiner. If the debug-only shadow target mirror cannot be prepared, the probe fails closed as unavailable/target-lost while the validated legacy target and user-visible result remain untouched.

The legacy backend remains authoritative. For a first probe, the shadow path independently scans the same configured resident target through the ResultStore explicit-type equality kernel. For a refine probe, it copies the previously published shadow ResultStore, reads only blocks that still contain active bits for the selected type, clears failed bits, recounts the affected blocks, and publishes the working revision only after every required read succeeds. Cancellation or target loss therefore leaves the prior shadow revision intact.

The v2 store is enumerated through `ResultCursor` in pages of at most 100 unique addresses. A successful parity result requires all of the following:

- v2 shadow scan/refine completed successfully;
- legacy logical result count equals ResultStore unique-address count;
- explicit-type ResultStore typed count equals its unique-address count;
- the ordered legacy address/type fingerprint equals the ResultStore fingerprint;
- re-enumerating the ResultStore through `ResultCursor` reproduces the same count, alias mask, ordering, and fingerprint.

A parity mismatch is a development signal, not a reason to repair or replace the legacy result. Do not publish the shadow store to the production result API and do not mutate target memory from the shadow path.

GC, target relocation, or game writes between the legacy operation and shadow probe may legitimately produce a mismatch. If that happens, discard the sample and repeat from a fresh first `Equal` search rather than weakening the comparison. Target configure/clear also increments a debug generation and drops the retained shadow revision; an in-flight generation change fails closed instead of publishing diagnostics for a mixed target generation.

For floating-point equality the v2 explicit kernel intentionally uses numeric equality, matching the current known-search contract: `+0.0` and `-0.0` compare equal while `NaN` does not compare equal to itself.

## Required baseline scenarios

### First known search

Run both rare-match and common-match queries for:

1. Int
2. Float
3. Auto

Record elapsed time, scan bytes, logical result count, total PSS/RSS, native heap allocated, and Java/native heap PSS.

For explicit-type `Equal` cases, also run the ResultStore shadow parity probe before accepting the sample as a correctness baseline.

### Known refine

Use representative retained sets close to:

1. 1,000,000 → 100,000 results
2. 100,000 → 10,000 results
3. 10,000 → small/manual result set when available

The exact counts do not need to be synthetic; record the actual before/after counts and query. For explicit-type `Equal` refinements, seed the shadow revision with a successful first-scan parity probe and run the stateful probe after each legacy refine. Accept a v2 refine correctness sample only when it reports `shadowOperation=REFINE` and both count/address parity booleans are true.

### Unknown workflow

Measure separately:

1. Unknown baseline capture
2. first Changed/Unchanged-style materialization
3. a subsequent relative refine

If ART GC invalidates the baseline, record the safety result and repeat from a new baseline rather than treating invalid address comparison as a benchmark sample.

### Cancellation

Cancel one large known search and one large refine. Record the delay from cancel request to completion callback and verify the previous committed state remains available.

## Correctness fields beside every timing

For each sample retain at least:

- commit SHA;
- scenario label and query/type/scope;
- operation result code;
- elapsed nanoseconds;
- scan bytes scanned and total;
- logical result count;
- PSS/RSS and native heap allocated;
- history depth;
- any GC/identity/resource-limit message.

For explicit equality shadow samples also retain the shadow operation kind, expected bits, legacy count/fingerprint, v2 typed/unique counts, v2 block/retained-byte counts, v2 fingerprint, and the count/address parity booleans.

Performance numbers are not comparable if the two implementations produce different normalized `(address, type, value)` semantics. During ResultStore migration, use differential correctness checks before accepting a speed or RAM improvement.

## v2 acceptance direction

The block/bitmap backend should demonstrate improvements against this baseline without weakening the current safety model. In particular, target improvements are expected from:

- eliminating one rich `Candidate` record per ordinary match;
- retaining address-ordered block/bitmap results without a global sort;
- refining only active bitmap slots;
- sharing physical loads between compatible Auto aliases;
- materializing heavy tracked candidates only for Edit/Watch/Freeze/Inspector workflows.

Do not claim an optimization from architecture alone; record the before/after measurements.
