# True Memory Editor prototype

> **Status:** debug-only device prototype. Keep PR #109 draft until physical-device address tracking is proven. Freeze/Edit All remain out of scope.

## Architecture

The Memory Editor now follows a GameGuardian-inspired split-process architecture while preserving self-process raw-memory access in the target.

```text
:memory_ui
├─ MemoryEditorOverlayService
├─ TYPE_APPLICATION_OVERLAY bubble/panel
├─ result formatting / scrolling / edit UI
└─ live-refresh scheduler
        │
        │ Binder control + primitive snapshots
        ▼
:midlet
├─ MicroActivity / running MIDlet
├─ MemoryScanService
├─ MemoryLiveService
└─ libjlmem.so
   ├─ raw Search / Next Scan candidate DB
   ├─ process_vm_readv/writev self access
   ├─ resident ART range scanning
   ├─ typed alias grouping support
   ├─ visible-candidate temporal identity tracker
   └─ live value + live-address recovery
```

The UI process is intentionally separate from `:midlet`: UI allocation, text formatting, scrolling and UI GC must not pressure the MIDlet's ART heap. Cross-process raw-memory access is deliberately avoided. `libjlmem.so` is loaded by the thin target-process agent and reads/writes its own process.

The debug overlay requires Android's **Display over other apps** permission (`SYSTEM_ALERT_WINDOW`). Until permission is granted, a tiny `MEM` setup button may temporarily be attached to `MicroActivity`. Once granted, the bubble and full panel are owned by `:memory_ui`.

## Search semantics remain unchanged

The proven scanner path remains the source of truth for Search/Next Scan:

- scopes: Java Fast / Java Thorough;
- types: Auto, Int8, Int16, UInt16, Int32, Int64, Float32, Float64;
- native candidate storage;
- resident-page filtering with `mincore()`;
- dynamic OS page size;
- exact typed write with expected-current guard and readback;
- Next Scan is a real filter: valid no-match => **0 candidates**;
- GC count is evidence to revalidate, never proof that a candidate moved;
- search state is tied to one live MIDlet runtime generation.

The live tracker intentionally does **not** mutate the Search candidate DB yet. This keeps relocation heuristics from silently changing Search/Next Scan semantics during the prototype.

## Live value and live address model

A raw address is treated as a temporary binding, not the logical identity of a result.

For the first visible address groups (currently capped at 32 groups from the first 100 typed rows), `libjlmem.so` keeps a native tracking record containing:

```text
logical track id
source address
current address
previous address
type/alias mask
temporal context signature
tracking state
confidence
relocation count
```

The UI displays the current value and current address on every live refresh. Live observation is ON by default at a conservative 1 second interval; UI polling itself occurs in `:memory_ui`.

### Tracking states

```text
UNTRACKED
STABLE
SUSPECT
RELOCATED / Rebound
AMBIGUOUS
LOST
```

A GC count increase by itself does not change the state.

### Stable-address validation

Each tracked candidate captures context around the target value:

- 64 bytes before;
- the 8-byte maximum target span excluded;
- 64 bytes after;
- context split into 4-byte lanes;
- zero and `0xFFFFFFFF` lanes ignored;
- lanes that change during otherwise-valid observation are progressively removed from the temporal identity.

On refresh, the tracker first validates those anchors at the existing `currentAddress`.

```text
identity still matches at A
=> STABLE at A
```

This remains true even if ART's GC counter increased.

### Relocation detection

Only when the identity no longer validates at A does the candidate become `SUSPECT` and trigger recovery.

All suspect visible candidates in that refresh share one resident-ART recovery scan. The native engine chooses several informative anchors per candidate, searches resident Java heap pages, reconstructs possible target addresses from anchor offsets, then validates the full temporal signature.

```text
old identity fails at A
+ same identity uniquely wins at B
=> RELOCATED A -> B
```

The UI then exposes:

- new/current address B;
- previous address A;
- confidence;
- relocation count;
- live value read from B.

If multiple locations are too close in confidence, state becomes `AMBIGUOUS`. If no acceptable location exists, state becomes `LOST`. Ambiguous/lost/suspect candidates are shown but are not considered safe for writes.

Recovery is deliberately bounded:

- max 32 actively tracked unique address groups;
- max 6 distinct recovery anchors per group;
- resident Java/ART pages only;
- max candidate tests per tracked group;
- recovery scan occurs only after address validation fails.

This avoids continuously rescanning the heap merely because GC happened.

## Write safety

The UI may edit a visible candidate only when its live identity is acceptable (`STABLE`, `RELOCATED`, or legacy/untracked fallback) and the typed value is readable. `SUSPECT`, `AMBIGUOUS`, and `LOST` candidates refuse the write.

The underlying typed write still uses the existing expected-current guard and independent readback.

There is intentionally no Freeze yet. A future Freeze must use the same identity state machine and must suspend writes immediately when a candidate becomes suspect/ambiguous/lost.

## Diagnostics

Scanner diagnostics remain available, plus live tracker fields such as:

```text
uiProcess=:memory_ui
targetProcess=:midlet
nativeBackbone=libjlmem.so
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
liveRebinds=
liveAmbiguousTotal=
liveLostTotal=
```

Diagnostics remain bounded to ~200 lines and copyable from the overlay.

## Highest-priority physical-device tests

### Green Farm explicit Int32

1. Grant **Display over other apps** when the temporary `MEM` setup button requests it.
2. Confirm the independent `MEM` bubble appears.
3. Open Memory Editor; verify diagnostics say `uiProcess=:memory_ui`, `targetProcess=:midlet`, `nativeBackbone=libjlmem.so`.
4. Java Fast + Int32, search the known coin value. Expected: one authoritative candidate.
5. Record current address and value.
6. Close the panel and change coins through normal gameplay.
7. Reopen/observe live results without Next Scan first.
8. If address did not move, expect `Stable` and the same address with updated live value.
9. If it moved, expect `Rebound`, a new current address, the previous address, and `liveRebinds > 0`.
10. Only after live observation is correct, perform an exact typed edit and verify the game changes.

A GC-count increase without an address change is a valid `STABLE` result.

### Auto / several thousand candidates

Repeat New Search using Auto, then narrow with normal Next Scan. The search result set must still obey normal filtering semantics. Visible rows may independently report live address states; the live tracker must not expand or rewrite the search set.

### Large result set (>25k)

Regression gate: GC must not cause Next Scan to stick on the previous count. Direct refine still runs; valid no-match ends at zero.

## Known limitations of this iteration

- Live identity is heuristic; raw primitive address -> Java object identity is not available through a stable public ART API.
- Only a bounded number of visible address groups receive full temporal tracking.
- Stable surrounding data may legitimately change enough that a candidate becomes LOST even though the logical value still exists.
- A sufficiently similar neighborhood can become ambiguous; the implementation intentionally fails closed instead of guessing.
- The live tracker does not yet feed a rebound address back into the Search candidate DB.
- No Watch List or Freeze yet.
- No Edit All / mass write.
- No bytecode instrumentation, JVMTI, private ART object pinning, root daemon, pointer/reference editing, stack/register scanning, encoded/encrypted-value discovery, or RMS/save editing.

## Acceptance rule before Freeze

The next milestone is not “GC count stays zero”. The required behavior is:

```text
same logical value
├─ stays at A -> tracker remains STABLE
└─ moves A -> B -> tracker reports a unique REBOUND and continues live observation at B
```

Watch and Freeze should be built only after that behavior survives repeated real-device gameplay and multiple ART GC cycles without false rebinds.
