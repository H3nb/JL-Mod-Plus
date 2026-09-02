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

## Required baseline scenarios

### First known search

Run both rare-match and common-match queries for:

1. Int
2. Float
3. Auto

Record elapsed time, scan bytes, logical result count, total PSS/RSS, native heap allocated, and Java/native heap PSS.

### Known refine

Use representative retained sets close to:

1. 1,000,000 → 100,000 results
2. 100,000 → 10,000 results
3. 10,000 → small/manual result set when available

The exact counts do not need to be synthetic; record the actual before/after counts and query.

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

Performance numbers are not comparable if the two implementations produce different normalized `(address, type, value)` semantics. During ResultStore migration, use differential correctness checks before accepting a speed or RAM improvement.

## v2 acceptance direction

The block/bitmap backend should demonstrate improvements against this baseline without weakening the current safety model. In particular, target improvements are expected from:

- eliminating one rich `Candidate` record per ordinary match;
- retaining address-ordered block/bitmap results without a global sort;
- refining only active bitmap slots;
- sharing physical loads between compatible Auto aliases;
- materializing heavy tracked candidates only for Edit/Watch/Freeze/Inspector workflows.

Do not claim an optimization from architecture alone; record the before/after measurements.
