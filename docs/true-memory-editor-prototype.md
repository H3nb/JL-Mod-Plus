# True Memory Editor prototype

> **Status:** debug-only physical-device prototype. Keep PR #109 draft. Freeze/Edit All remain out of scope until remote Search/Next Scan/live-address behavior is proven across multiple MIDlets.

## Proven process architecture

Physical Android 16 testing proved same-UID cross-process raw access in multiple MIDlets. `/proc/<pid>/maps`, `process_vm_readv`, `process_vm_writev`, independent readback and restore all pass without root.

```text
:midlet
├─ MicroActivity / running MIDlet
│  └─ classic reusable Memory Editor View overlay
├─ MemoryTargetBridgeService
└─ libjlprobe.so
   ├─ target PID / runtime generation
   ├─ disposable capability probe
   └─ target-local /proc/self/maps + mincore resident ART runs
        │
        │ Binder / same app UID
        ▼
:memory_engine
├─ MemoryEngineService
├─ RemoteMemoryScanService
└─ libjlremote.so
   ├─ remote Search candidate DB
   ├─ remote Next Scan
   ├─ relocation recovery
   ├─ remote live values
   ├─ visible-candidate live address tracker
   └─ exact remote write + independent readback
```

There is no `SYSTEM_ALERT_WINDOW`, no second UI Activity and no root requirement. The overlay is a child View of `MicroActivity`, so it disappears naturally when the MIDlet dies.

The PR base remains `alpha-pre-emulation-speed`; emulation-speed commit `4a4a1142b59e5c748b69de06597e80476e9ae4e8` is excluded from the performance baseline.

## Target-heap isolation

On the normal remote path, the in-process fallback scanner is not started. Candidate vectors, scan buffer, refinement state, relocation pools and live-address recovery live in native memory in `:memory_engine`.

The target process keeps only:

- the running game;
- the classic reusable overlay;
- a small Binder target bridge;
- `libjlprobe.so` for PID/generation and resident-page discovery.

`mincore()` must execute target-side because it operates on the caller's address space. Consecutive resident ART pages are compressed into `[start,end)` runs and the engine remotely reads only those runs.

Live UI polling is OFF by default to minimize TextView/string churn in the MIDlet ART heap. The overlay is fully built before New Search can bind addresses. Physical testing has already observed `UI warm-up GC Δ: 0`.

## Remote Search semantics

Supported scopes/types remain:

- Java Fast / Java Thorough;
- Auto;
- Int8, Int16, UInt16, Int32, Int64, Float32, Float64.

Search state and candidate buckets are native in `:memory_engine`.

Next Scan first direct-refines the retained remote addresses. If direct refinement returns zero and relocation identity is available, the engine refreshes target resident ranges and creates a fresh exact-value recovery pool. That pool is identity evidence only: it never replaces the old logical result set wholesale.

A valid no-match ends at **0 candidates**. Previous stale results are not retained merely because relocation was unavailable.

## Physical evidence

A Green Farm remote Int32 test demonstrated:

- scanner host PID different from target PID;
- Java Fast New Search reading about 12.36 MiB of resident ART memory in ~52 ms;
- one authoritative Int32 result;
- exact remote edit + independent readback changing the game value;
- no target GC dependency in scanner correctness.

An Auto address-aware refinement demonstrated:

```text
old typed aliases       = 24
old address groups      = 11
fresh exact matches     = 52
fresh address groups    = 25
recovered typed aliases = 4
recovered groups        = 1
ambiguous               = 0
```

This is the intended behavior: the 52 fresh matches remained a recovery pool and only one logical old address group survived.

`Seen=0 retained=1` on individual type lines after relocation is currently diagnostic bookkeeping: recovered buckets do not inherit the fresh-pool per-type seen counter. Global `relocationFreshMatches`, group counts and retained counts are authoritative for that operation.

## Remote live values and live addresses

A raw address is a mutable binding, not the logical identity of a visible result.

The first visible address groups (max 32 unique groups from the first 100 typed rows) are tracked in `:memory_engine` using:

```text
source address
current address
previous address
type/alias mask
temporal context signature
state
confidence
relocation count
```

Tracking states are:

```text
UNTRACKED
STABLE
SUSPECT
RELOCATED / Rebound
AMBIGUOUS
LOST
```

### Temporal signature

For a tracked address the engine captures:

- 64 bytes before the value;
- an 8-byte maximum target span excluded;
- 64 bytes after the target;
- 4-byte lanes;
- zero and `0xFFFFFFFF` lanes ignored;
- changing lanes progressively removed while the address remains valid.

GC count is not used as proof of movement. If the signature still validates at address A, the binding remains `STABLE` even if ART performed GC.

Only when identity at A fails does the candidate become `SUSPECT` and become eligible for recovery.

### Recovery

Recovery is bounded:

- max 32 visible tracked groups;
- max 6 informative anchors per group;
- max candidate tests per track;
- only resident ART runs already provided by the target bridge;
- one shared recovery scan for all suspect visible groups;
- ambiguous/lost recovery is backed off for several tracker epochs.

A move is accepted only when a best candidate is sufficiently strong and not closely tied with another candidate. On unique `A -> B` rebind, the signature is rebased to B so legitimate ART reference updates do not immediately cause an oscillation back to `SUSPECT`.

`SUSPECT`, `AMBIGUOUS`, and `LOST` bindings are exposed as unreadable to the existing UI result contract and cannot pass the exact-write guard.

### ANR safety

Full live-address recovery is **not executed on the synchronous Binder/UI path**.

`getResultsPage()` returns immediately using the last published binding. A reusable raw snapshot is queued to the single `remote-memory-scan-worker`; validation/recovery runs there and publishes a new binding for the next Refresh/Live tick.

Therefore a moved address may be visible one refresh later:

```text
Refresh #1 -> schedules remote validation/recovery
background engine worker -> A -> B
Refresh #2 / next Live tick -> UI shows B
```

This one-tick delay is intentional to keep full ART recovery scans away from Android input dispatch.

The branch also retains the separate EventQueue ANR hardening: Android's main/UI thread does not enter the synchronous immediate MIDlet callback path that can wait behind a long game paint callback.

## Diagnostics

Remote scanner diagnostics include:

```text
backend=remote-memory-engine
remoteTargetPid=
scannerHostProcess=:memory_engine
scannerHostPid=
residentRuns=
residentRunsTruncated=
residentBytes=
residentPages=
bytesRead=
retained=
relocationTracking=
relocationAttempted=
relocationOriginal=
relocationFreshMatches=
relocationRecovered=
relocationAmbiguous=
gcDependency=false
residencySource=target-mincore
```

Live-address diagnostics additionally include:

```text
liveAddressBackend=remote-memory-engine
liveTrackedGroups=
liveTrackLimit=
liveStable=
liveRelocated=
liveSuspect=
liveAmbiguous=
liveLost=
liveUntracked=
liveValidationReads=
liveRecoveryScans=
liveRecoveryBytes=
liveRebinds=
liveAmbiguousTotal=
liveLostTotal=
```

The service only sends an extra status callback when a live binding becomes interesting (address changed or became suspect/ambiguous/lost), not on every stable live tick.

## Highest-priority device tests

### Green Farm explicit Int32 live binding

1. Wait for `ENG✓` and confirm `Backend: :memory_engine`.
2. Java Fast + Int32 New Search the known coin value; expect one candidate.
3. Leave Live OFF initially and record address A.
4. Return to gameplay and change the coin normally; allow normal runtime/GC activity.
5. Reopen Memory Editor and press Refresh once. This schedules asynchronous identity validation/recovery.
6. Wait briefly, then press Refresh again (or enable Live and wait for the next tick).
7. If the address did not move, expect the same A with the new live value and `liveStable > 0`.
8. If ART moved it, expect address B, `liveRebinds > 0`, and `liveRelocated`/subsequent `liveStable` evidence.
9. Edit the displayed current address and verify the game changes.

### Auto

Repeat with several-thousand-candidate Auto searches and narrow normally. Search semantics remain independent of visible live binding; the live tracker must never expand the Search result set.

### Large result set

For >25k typed candidates, Next Scan must still direct-refine and may end at zero. It must never stick on the previous count simply because relocation context was unavailable.

### ANR/performance

Exercise gameplay aggressively before/after opening and closing Memory Editor. Compare target responsiveness and address stability against the old in-process scanner build.

## Still out of scope

- Freeze;
- mass/Edit All writes;
- treating heuristic live rebinds as authoritative Search DB mutations;
- dependence on private ART object layouts or collector forwarding internals.

Freeze should only be considered after live address tracking survives repeated device tests and must suspend writes immediately when identity is suspect, ambiguous, or lost.
