# True Memory Editor prototype

> **Status:** debug-only device experiment. Do not merge PR #109 until the stability gates below pass on a physical Android 16 device.

## Architecture under test

The editor no longer launches a `MemoryEditorActivity`. The debug build installs a tiny `MEM` trigger directly on the running `MicroActivity` through a process-local bootstrap provider. On first open, the complete classic View hierarchy is allocated once and the scanner service/self-tests are started. **New Search stays disabled until that warm-up is complete.**

```text
:midlet process
├─ MicroActivity / running MIDlet
│  ├─ game surface
│  ├─ tiny MEM trigger
│  └─ transparent classic-View Memory Editor overlay
├─ MemoryScanService
└─ libmemory_scan.so
```

Opening and closing the editor after a search is only `VISIBLE/GONE`. It does not create another Activity, does not trigger a Compose composition, and does not bind/unbind the scanner service on each round trip.

## GC-pressure rules

1. Build result rows, edit pane and diagnostics pane **before** a search can bind addresses.
2. Keep candidate storage and scan/refine working sets native.
3. Reuse the target-process `long[]` result page.
4. Status delivery is callback/event driven; there is no fast status polling loop.
5. Result refresh is **manual by default**. `Live: On` is optional and explicitly trades more observation work/text formatting for convenience.
6. The overlay and scanner service stay attached while the same MIDlet generation lives.
7. Exiting/destroying `MicroActivity` invalidates the generation, cancels native work, clears candidates and stops the scanner service.
8. GC relocation recovery remains a fallback. A higher GC count alone is not treated as proof that an address moved.

`UI warm-up GC Δ` is shown in the overlay. A non-zero value during first open is acceptable because it happens before New Search is enabled. The key measurement is whether GC and/or address relocation occurs during later game/editor round trips.

## Device gate: Green Farm explicit Int32

1. Launch Green Farm and open `MEM`.
2. Wait for `Self access: OK` and `Managed ART: PASS`.
3. Note `UI warm-up GC Δ`.
4. Search the known coin value as explicit `Int32` and refine to the authoritative candidate.
5. Record its raw address and scanner GC diagnostics.
6. Close the overlay. Do nothing in-game.
7. Reopen it using `MEM` and press **Refresh**. Do not enable Live for this first test.
8. Confirm the same raw address is readable and still represents the coin value.
9. Repeat several close/open cycles, then change the coin value normally and use Next Scan.

Primary success criterion: direct addressing remains authoritative across editor/game round trips. The GC count may increase without failure if the address itself remains valid.

## Android 11 comparison

The Android 11 Virtual Master sandbox previously kept the Green Farm address authoritative across multiple GC-count increases. Use the same explicit-Int32 workflow there as a control. The purpose is to distinguish ART/OEM lifecycle/compaction behavior from scanner correctness.

## Auto/type behavior

Supported prototype types:

- Auto
- Int8
- Int16
- UInt16 / Java `char`
- Int32
- Int64
- Float32
- Float64

Auto means all supported primitive numeric representations, not encoded/fixed-point/encrypted/custom formats. The native scanner reads a resident chunk once and tests compatible enabled representations from that buffer. Same-address typed aliases are grouped only in the UI; native candidates remain typed so Next Scan semantics stay exact. Tapping a grouped address lets the edit pane cycle through the aliases before performing an expected-current guarded typed write.

## Safety

There is no Edit All. Raw writes can corrupt an unrelated object field, reference, object header or other heap data when a candidate is wrong. A prior mass-edit experiment produced a SIGSEGV later inside ART MarkCompact, consistent with heap corruption surfacing during GC. Edit individual candidates and validate them through normal game behavior.

Freeze, fuzzy changed/unchanged/increased/decreased search, group search, raw viewer and watch-list work remain future steps. Freeze must stop writing when a saved address becomes stale and only resume after a confident rebind.
