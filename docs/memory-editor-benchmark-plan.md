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
- Keep `MemoryEditorActivity` closed for an **engine-only** memory sample. Because the production Compose tree now runs in `:memory_engine`, also label any sample taken while the editor is visible as **engine+UI**. Do not compare engine-only PSS on one revision against engine+UI PSS on another.

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

## ResultStore canonical Known parity probe

Before the ResultStore backend is allowed to publish user-visible explicit-type Known results, debug builds provide a stateful differential probe. The preferred entry point is:

```java
Bundle parity = MemoryEngineBenchmarkRunner.validateKnownShadowQuery(
        diagnostics, type, predicate, first, second);
```

The query strings are parsed by the **same native `parseQuery()` implementation used by production Known search/refine**. Only the resulting canonical `(type, predicate, firstBits, secondBits)` plan crosses the diagnostics boundary. The v2 scanner/refiner never reparses user strings.

The canonical-plan boundary rejects representations the production parser cannot emit: invalid type/predicate values, non-zero hidden second thresholds on single-value predicates, out-of-width narrow raw values, non-finite Float/Double thresholds, and reversed `Between` bounds.

`diagnostics.validateKnownEqualShadow(type)` remains a compatibility helper for Equal-only manual testing, but new all-predicate validation should use the canonical-query helper above.

### Shadow revision lifecycle

A successful fresh production `startKnown()` automatically drops the retained debug shadow **result revision** while keeping the already-configured target mirror. Therefore the next parity call is always an independent v2 `SCAN`, even when the previous shadow session used the same primitive type. This prevents a new search from being misclassified as a refine.

A successful production `refineKnown()` intentionally does **not** reset the shadow result revision. If a successful first-scan parity seed already exists for that target/type, the next parity call performs `REFINE` against that immutable ResultStore revision.

`clearSearch()` drops the shadow result revision. Target configure/clear additionally replaces or drops the shadow target mirror and advances its generation. An in-flight generation change fails closed instead of publishing a mixed-target result.

### First-scan parity procedure

Use first-scan parity only when all of these are true:

1. the immediately preceding committed search is a fresh explicit-type Known search;
2. exactly one primitive type is selected (`Byte`, `Short`, `Char`, `Int`, `Long`, `Float`, or `Double`), not `Auto`;
3. the legacy result is non-empty;
4. no refine/filter/Undo has changed the result revision since that first search;
5. the MIDlet/game state is held as stable as practical while the parity probe runs;
6. the canonical query passed to `validateKnownShadowQuery()` is exactly the one used for the legacy operation.

The first parity result must report `shadowOperation=SCAN`.

### Refine parity procedure

1. Run one non-empty explicit-type Known search.
2. Validate it and require a successful `SCAN` parity result.
3. Perform exactly one non-empty legacy `Next Scan` using the same explicit primitive type.
4. Hold the game state stable.
5. Validate using the **new refine predicate/value(s)**.
6. Require `shadowOperation=REFINE` and both count/address parity booleans.

Repeat steps 3-6 for additional refinements. Starting a fresh Known search intentionally discards the old shadow revision and requires a new `SCAN` seed.

### What parity proves

The legacy backend remains authoritative. For a first probe, the shadow path independently scans the same configured resident target through the ResultStore explicit-type kernel. For a refine probe, it starts from the previously published immutable ResultStore revision, reads only logical blocks that still contain active bits for the selected type, removes failed bits, recounts affected blocks, and publishes the working revision only after every required read succeeds. Cancellation or target loss therefore leaves the prior shadow revision intact.

ResultStore bitmap payload is split into 32 KiB copy-on-write slabs. Copying a revision shares untouched slabs. A refine detaches a slab only when at least one bitmap word in that slab actually changes; a block whose candidates all survive keeps its old shared slab. This preserves transactional revisions without requiring a full payload copy on every refine.

The v2 store is enumerated through `ResultCursor` in pages of at most 100 unique addresses. The cursor also supports a transitional offset seek that skips complete 4 KiB result blocks through each header's `uniqueAddressCount`, so production can preserve the existing offset API during cutover without walking all preceding logical results.

A successful parity result requires all of the following:

- v2 shadow scan/refine completed successfully;
- legacy logical result count equals ResultStore unique-address count;
- explicit-type ResultStore typed count equals its unique-address count;
- the ordered legacy address/type fingerprint equals the ResultStore fingerprint;
- re-enumerating the ResultStore through `ResultCursor` reproduces the same count, alias mask, ordering, and fingerprint.

A parity mismatch is a development signal, not a reason to repair or replace the legacy result. Do not publish the shadow store to the production result API and do not mutate target memory from the shadow path.

GC, target relocation, or game writes between the legacy operation and shadow probe may legitimately produce a mismatch. If that happens, discard the sample and repeat from a fresh first search rather than weakening the comparison.

For floating-point Known predicates the v2 explicit kernels intentionally use numeric comparisons matching the current production contract: `+0.0` and `-0.0` compare equal, target NaN never matches a Known predicate, parsed thresholds must be finite, and `Between` is inclusive.

## Required physical all-predicate parity matrix

Before explicit-type ResultStore becomes production-authoritative, exercise every predicate family on physical hardware:

- `=`
- `!=`
- `>`
- `<`
- `>=`
- `<=`
- inclusive `Between`

Cover every explicit primitive type across the matrix when practical. At minimum include representative edge cases that expose signedness/width differences:

- Byte: negative and positive boundaries;
- Short: negative and positive boundaries;
- Char: unsigned values including values above signed-short range;
- Int and Long: negative values, zero, positive values, and boundary-adjacent thresholds;
- Float and Double: ordinary negative/positive values, `+0.0`/`-0.0`, and finite values around a `Between` boundary;
- verify target NaN does not match Known predicates and non-finite query thresholds are rejected by the production parser rather than accepted only by v2.

For each accepted sample require exact logical-count and ordered address/type fingerprint parity. Do not accept a timing/RAM result from a query whose normalized result set differs.

## Required baseline scenarios

### First known search

Run both rare-match and common-match queries for:

1. Int
2. Float
3. Auto

Record elapsed time, scan bytes, logical result count, total PSS/RSS, native heap allocated, and Java/native heap PSS. Label memory samples as engine-only or engine+UI.

For explicit-type cases included in the v2 matrix, also run the canonical ResultStore shadow parity probe before accepting the sample as a correctness baseline.

### Known refine

Use representative retained sets close to:

1. 1,000,000 → 100,000 results
2. 100,000 → 10,000 results
3. 10,000 → small/manual result set when available

The exact counts do not need to be synthetic; record the actual before/after counts and query. For explicit-type refinements, seed the shadow revision with a successful first-scan parity probe and run the stateful canonical probe after each legacy refine. Accept a v2 refine correctness sample only when it reports `shadowOperation=REFINE` and both count/address parity booleans are true.

Include at least one high-survival refine and one low-survival refine. The former exercises the COW fast path where unchanged blocks/slabs should remain shared; the latter exercises mutation/recount behavior.

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
- whether the Memory Editor Activity was closed (engine-only) or visible (engine+UI);
- operation result code;
- elapsed nanoseconds;
- scan bytes scanned and total;
- logical result count;
- PSS/RSS and native heap allocated;
- history depth;
- any GC/identity/resource-limit message.

For explicit-type shadow samples also retain the shadow operation kind, predicate, first/second canonical bits, legacy count/fingerprint, v2 typed/unique counts, v2 block/retained-byte counts, v2 fingerprint, and the count/address parity booleans.

Performance numbers are not comparable if the two implementations produce different normalized `(address, type, value)` semantics. During ResultStore migration, use differential correctness checks before accepting a speed or RAM improvement.

## v2 acceptance direction

The block/bitmap backend should demonstrate improvements against this baseline without weakening the current safety model. In particular, target improvements are expected from:

- eliminating one rich `Candidate` record per ordinary match;
- retaining address-ordered block/bitmap results without a global sort;
- refining only active bitmap slots;
- sharing immutable ResultStore payload slabs between search-history revisions and cloning only changed slabs;
- sharing physical loads between compatible Auto aliases;
- materializing heavy tracked candidates only for Edit/Watch/Freeze/Inspector workflows.

Do not claim an optimization from architecture alone; record the before/after measurements.
