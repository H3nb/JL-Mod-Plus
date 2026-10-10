# Memory Editor overhaul — PR 161

Status: **Draft / incomplete**. This document is the implementation checklist for the whole Memory Editor improvement effort, not only encoded search. Work in cohesive checkpoints on the existing PR branch, with no merge until all scoped contracts and relevant validations pass.

## Product goals and exclusions

Make the managed-Java Memory Editor more capable while preserving normal MIDlet performance and compatibility:

- Scan only reachable managed Java heap using validated object/class owners. Do not return to unstable raw-address scans.
- No new instrumentation/hooking of MIDlet bytecode, no RMS reading or editing, no hooking guest field reads/writes, and no extra polling during normal gameplay.
- Standard Known, Unknown/Fuzzy, Group, Inspector, Watch and Freeze remain functional. New encoded search is **opt-in during New Search, off by default**, and its selection persists only within the search session.
- Report distinct states for **match/correlation**, **immediate write readback**, and **gameplay-observed effect**. We cannot guarantee edits will affect every game.
- Saved cheats persist editor-owned *target definitions and desired values*; they do not edit a game's persistent save data.

## Ordered implementation — one integrated PR

### 0. Correctness and efficient scanner foundation
- [x] Replace repeated owner-bucket storage summation with incremental staged accounting in `RevisionBuilder`.
- [x] Add standalone signed LE/BE numeric codecs (16/32/64), overlap checks and modular-Int32 additive/subtractive/XOR correlation primitives, with focused JVM fixtures.
- [ ] Validate resource accounting including retained revisions, transient snapshots, finished storage and owner churn; benchmark representative sparse/dense heap shapes.
- [ ] Re-check object/array ownership, cancellation, freeze/watch isolation and branch integration against latest `alpha`.

### 1. Target identity and mutation journal, shared by all features
- [ ] Make runtime candidate identity distinguish array storage location, value type, numeric view (width/order/offset), and transform hypothesis while retaining a validated object reference (not a physical address).
- [ ] Detect overlapping views and conflicting edits; never silently treat separate views of the same bytes as independent write targets.
- [ ] Introduce a bounded, runtime-token-scoped mutation journal *separate from search Undo*. Capture typed old bits immediately before each write, written bits, target identity and partial-write status.
- [ ] Conditional revert: restore only while owner identity and current value match the journal's written value; leave conflicting/lost targets untouched and report each outcome.

### 2. Batch editing workflow
- [ ] Add **Incremental Fill** for selected candidates: `value[i] = base + i * step`, default `step = 1`, stable explicit result order, typed bounds, preview and one bounded target-process batch.
- [ ] Keep existing "set all to same value" workflow unchanged; prevent accidental overflow and alias conflicts.
- [ ] Support **Revert Selected + Remove** (with clear handling of partial/conflicted revert), and **Undo Last Edit** distinct from Undo Search. Do not let a failed restore remove its candidate silently.
- [ ] Cover selection, repeated writes, stale revision, concurrent guest updates, cancellation and runtime termination with tests.

### 3. Known/Unknown encoded and packed search
- [ ] Capture a bounded, resource-accounted snapshot of eligible primitive values and reachable `byte[]` while preserving Java owner identity. Avoid materializing every possible packed numeric window as a baseline result.
- [ ] Use two or more *different visible-value observations* for known-value correlation. Maintain multiple additive/subtractive/XOR hypotheses with modular 32-bit semantics, further narrowing after each refine.
- [ ] Include unaligned Int32LE/BE views; extend to Int16/Int64 and further transforms based on corpus/game evidence and measured cost.
- [ ] Extend Unknown/Fuzzy only where it adds value (not a duplicate "encrypted" checkbox); `Changed/Unchanged` may operate on raw packed views without knowing a key.
- [ ] Integrate view-aware results into existing revision history, Inspector and cancellation; prevent baseline leaks after New Search, Undo, reset, GC or MIDlet restart.
- [ ] Add explicit opt-in Compose UI and IPC contracts, localized EN/ID copy, progress, error and candidate provenance.
- [ ] Implement inverse encoded writes with optimistic validation and journal-backed rollback; initial packed writes are single-target and no automatic Freeze until safe.
- [ ] Distinguish decoded caches, derived views and live backing candidates where observable; never label correlation as proof of authority.

### 4. Portable identities and Saved Cheats
- [ ] Define a stable symbolic target spec, distinct from runtime IDs such as `int[]#23`. Model class/field descriptor and staticness, owner-root-relative access path/array index or numeric view, transforms and optional source/version guard; do not assume array/owner handles remain stable across launches.
- [ ] Keep a runtime-local **Binding** to the current concrete owner, with statuses Pending, Bound, Ambiguous, Lost, Mismatch and Unsupported; never rebind silently to unrelated objects. Do not force class initialization.
- [ ] Store user-approved named saved targets/cheats in the installed MIDlet's editor-owned storage folder; use installed Library `appId`, launch/runtime lifetime fencing and separate JAR source identity where needed. Keep support for compatible upgrades and DX→D8 changes by not using transient DEX IDs.
- [ ] Restore saved definitions across app/device restarts; support explicit enable/disable, editable logical value, watch-only actions and optional user-enabled auto-apply *after unique safe rebind*. Pause and explain ambiguous/unavailable/dynamic-key targets.
- [ ] Add safe import/export of shareable cheat definitions with versioned serialization and validation (never import ephemeral owner handles).
- [ ] Validate multi-instance install, delete/reinstall, source changes, stale runtime, restart, data preservation and unsupported target cases. Do not modify the game's RMS.

### 5. Product polish and acceptance
- [ ] Consolidate existing scanner/edit flows, remove obsolete experiments and accidental duplicate state owners; keep architecture as simple as the correctness contract permits.
- [ ] Focused JVM tests, Binder/IPC tests, Compose/UI tests, GC/owner replacement and cancellation tests, bounded memory/performance comparisons, relevant CI and review of final diff.
- [ ] Real-game trials: **Kingdoms & Lords** coins/diamonds (detection → inverse write → observed gameplay effect), **Heroes Lore** XOR/BE32, **Dungeon Hunter 3** packed LE16, and a negative/control case for temporary copied buffers.
- [ ] Verify batch increment/revert, Undo Search vs Undo Edit, saved definitions and optional safe auto-apply on actual device. Track results separately from synthetic or bytecode-only evidence.
- [ ] No merge before scoped functionality is implemented, relevant CI passes or known unrelated failures are explicitly diagnosed, and outstanding gameplay/compatibility limitations are documented.

## Implementation dependencies

1. Account for memory and unify **Target / Binding / Mutation** semantics before adding additional edit modes.
2. Deliver Incremental Fill and Conditional Revert as one coherent end-to-end operation.
3. Reuse the same identity and mutation journal in packed/encoded correlation and inverse write; do not build a second scanner/history.
4. Add persistence after symbolic rebinding is trustworthy; Saved Cheats must not serialize ephemeral Java owner handles.
5. Qualify using both deterministic synthetic fixtures and real games; preserve explicit limitations if particular games remain unsupported.

## Evidence and validation status

The existing commits only implement the first two items of checkpoint 0. CI on the initial foundation reported successful Build/tests and Lint; screenshot validation reported unrelated-looking Library/About/Donation failures requiring baseline comparison. Subsequent source changes require checking their own latest CI. No encoded UI/IPC, batch increment, revert, saved cheats or successful real-game write has been delivered yet.

Follow [AGENTS.md](../AGENTS.md), [agent-workflow.md](agent-workflow.md), [development.md](development.md), [preset-config-contract.md](preset-config-contract.md) and [library-data-contract.md](library-data-contract.md).
