# True Memory Editor prototype

> **Status:** debug-only physical-device experiment. Keep PR #109 draft. Do not merge until the remote-access gate, Green Farm regression, Auto refinement and ANR checks pass on the physical Android 16 device.

## Current architecture

This iteration is intentionally **capability-first**. The Memory Editor UI has returned to the running MIDlet Activity, while a new sibling process tests whether it can become the long-term scanner host.

```text
:midlet
├─ MicroActivity / running MIDlet
│  └─ classic Memory Editor View overlay
├─ MemoryScanService                  # existing proven scanner fallback
├─ MemoryLiveService                  # existing fallback tracker, not the target architecture
├─ MemoryTargetBridgeService          # PID/generation + resident ART runs only
└─ libjlprobe.so
   ├─ disposable 64-bit capability probe
   ├─ /proc/self/maps
   └─ mincore() resident-run compression
        │
        │ Binder / same application UID
        ▼
:memory_engine
├─ MemoryEngineService
└─ libjlremote.so
   └─ remote /proc/<pid>/maps + process_vm_readv/writev capability gate
```

There is no `SYSTEM_ALERT_WINDOW`, no `TYPE_APPLICATION_OVERLAY`, and no `:memory_ui` process in this iteration. The classic overlay is a child View of `MicroActivity`, so it disappears naturally with the MIDlet Activity/process.

The PR base remains `alpha-pre-emulation-speed`; emulation-speed commit `4a4a1142b59e5c748b69de06597e80476e9ae4e8` is intentionally absent from the performance baseline.

## Why capability-first

A non-root virtual environment such as Parallel Space is not proof that a normal Android app may inspect any other process. JL-Mod Plus has a narrower and potentially useful case: `:memory_engine` and `:midlet` are sibling processes of the same APK/UID.

Before migrating the working scanner, the debug build proves what the actual Android 16 device permits.

On MIDlet creation:

1. `libjlprobe.so` exposes a disposable 64-bit native value and its address in `:midlet`.
2. `:memory_engine` reads `/proc/<midletPid>/maps` and confirms that address belongs to a readable/writable mapping.
3. `libjlremote.so` reads the exact value with `process_vm_readv`.
4. It writes a temporary value with `process_vm_writev`.
5. It reads that value back independently.
6. It restores the original value and verifies the restore.
7. It records engine/target PID and UID.

Success requires all of:

```text
remoteSameProcess=false
remoteSameUid=true
remoteMaps=PASS
remoteRead=PASS
remoteWrite=PASS
remoteReadback=PASS
remoteRestore=PASS
remoteEngineSupported=true
```

The in-game status badge starts as `ENG…`, becomes `ENG✓` on success or `ENG×` on failure. Tap the badge to copy full remote-engine diagnostics, including errno when access is denied.

Until this gate passes on the physical device, Search/Next Scan/candidate ownership stays on the existing in-process fallback. This prevents a speculative architecture rewrite from breaking a scanner that is already known to find and edit real game values.

## Target bridge and resident ART ranges

The remote process cannot use `mincore()` directly on virtual addresses owned by `:midlet`. Blindly reading the target's large ART reservations would also be wasteful and may increase page pressure.

Therefore `MemoryTargetBridgeService` exposes compressed **resident Java/ART runs** generated target-side:

```text
[count, flags, start0, end0, start1, end1, ...]
```

Implementation rules:

- target-local `/proc/self/maps` selects Java Fast or Java Thorough ART mappings;
- target-local `mincore()` tests page residency;
- consecutive resident pages are merged into `[start,end)` runs;
- maximum 2048 runs are returned (~32 KiB payload);
- `flags & 1` means the run list was truncated;
- no target-side candidate DB or scan loop is created by this bridge.

If the remote capability gate passes, `:memory_engine` can later consume these runs and use `process_vm_readv(midletPid, ...)` only on memory the target reports resident.

## Existing scanner fallback

The current functional scanner remains available during this experiment:

- Java Fast / Java Thorough;
- Auto, Int8, Int16, UInt16, Int32, Int64, Float32, Float64;
- native candidate buckets;
- dynamic OS page size;
- typed aliases;
- exact typed edit with expected-current guard and independent readback;
- GC-aware relocation fallback;
- Next Scan valid no-match => **0 candidates**;
- large untracked sets direct-refine rather than sticking on previous results;
- runtime-generation invalidation when the MIDlet target is lost.

This fallback still lives in `:midlet` for the capability build. Moving it is the next phase only after `ENG✓` is proven.

## Overlay behavior

The Memory Editor is a classic View hierarchy attached directly to `MicroActivity`.

Properties:

- no overlay permission;
- no second Activity;
- no `:memory_ui` process;
- transparent/semi-transparent panel over the running game;
- created before a New Search can bind addresses;
- reused with visibility changes rather than recreated on every round-trip;
- automatically removed when `MicroActivity` is destroyed.

Because the UI shares the MIDlet ART heap again, it remains deliberately classic/reusable rather than Compose-heavy. The long-term goal is that the expensive scanner/candidate/tracker state moves out to `:memory_engine`, leaving only the UI and tiny target bridge in `:midlet`.

## ANR finding and hardening

The Android 16 ANR trace from the previous external-overlay build showed an input-dispatch timeout, not a Binder deadlock.

At the captured point:

- Android main thread was blocked posting a pointer event to the MIDlet EventQueue;
- a game thread held the EventQueue callback monitor while inside game `paint()` and recursive `repaint()` flow;
- `memory-scan-worker` was parked/idle;
- Binder threads were waiting in the driver;
- ART HeapTaskDaemon was waiting.

The branch retains two hardenings from that investigation:

1. Android UI input is not allowed to synchronously enter the immediate MIDlet callback path that can wait behind a long paint callback.
2. Existing live-address recovery is asynchronous and unresolved recovery is backed off rather than performing a full resident-heap recovery scan on every UI tick.

The new remote-engine architecture should further reduce coupling once the full scanner leaves the target process.

## Physical-device test gate

### 1. Remote engine capability

Start a MIDlet. Expected UI:

```text
MEM    ENG…
```

No Android overlay-permission prompt should appear.

After the one-shot probe:

- `ENG✓` + toast `Memory engine remote access: PASS` is the desired result.
- `ENG×` means the kernel/SELinux/ptrace policy rejected some part of the path. Tap `ENG×` and paste the copied diagnostics.

The most useful failure fields are:

```text
remoteEnginePid=
remoteTargetPid=
remoteEngineUid=
remoteTargetUid=
remoteSameUid=
remoteMaps=
remoteRead=
remoteReadErrno=
remoteWrite=
remoteWriteErrno=
remoteReadback=
remoteRestore=
remoteEngineSupported=
```

### 2. Existing scanner regression

Regardless of `ENG✓/ENG×`, verify the fallback scanner still works:

- Green Farm Java Fast + Int32 authoritative coin candidate;
- typed edit changes the game;
- Next Scan no-match becomes 0;
- Auto can be narrowed over several scans;
- >25k result sets no longer stick on the previous count.

### 3. ANR regression

Interact rapidly with the running game before and after opening/closing the Memory Editor several times. There must be no 5-second input timeout. If another ANR occurs, retain the complete process trace again.

### 4. Address stability

For Green Farm explicit Int32:

1. search the known coin value;
2. record the raw address and GC total;
3. return to gameplay without editing it;
4. change coins normally;
5. reopen Memory Editor before Next Scan;
6. compare address/value/GC behavior.

This tells us whether removing the external overlay process and excluding the emulation-speed commit changes the Android 16 address-relocation pattern.

## Next phase after `remoteEngineSupported=true`

Once the physical Android 16 device proves remote read/write, migrate in this order:

1. introduce a target-PID/resident-run memory-source abstraction in the native scanner;
2. move New Search and candidate buckets to `:memory_engine`;
3. move Next Scan and relocation context DB;
4. move live value reads;
5. move logical-candidate/live-address tracking;
6. leave only the classic overlay + `MemoryTargetBridgeService` + `libjlprobe.so` in `:midlet`;
7. then design Watch;
8. implement Freeze only after live-address rebinding is physically validated.

Search and live-address tracking should eventually share one logical candidate identity, but the migration must not let a heuristic live rebind silently corrupt Search/Next Scan semantics.

## Safety

Still out of scope for this prototype:

- Freeze;
- Edit All / mass blind writes;
- reference/pointer editing;
- stack/register scanning;
- private ART/JVMTI object pinning;
- root daemon;
- encoded/encrypted-value discovery;
- RMS/save editing.
