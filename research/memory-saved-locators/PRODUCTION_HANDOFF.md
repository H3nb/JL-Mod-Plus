# Saved Variables — Production implementation handoff

**Decision (2026-11-02): GO to start a new, focused production PR; NO-GO to merge/cherry-pick the R&D PR wholesale.**
This is the current decision reference for agents implementing Saved Variables in JL-Mod Plus.
Read root `AGENTS.md` and current `alpha` contracts/source first. This document overrides *historical R&D recommendations*, not production repository contracts. The R&D history is in [EVIDENCE_ARCHIVE.md](EVIDENCE_ARCHIVE.md). PR #215 stays Draft; make no production merge from this branch.

## Product outcome and fixed scope

A user searches memory, selects **one verified candidate**, saves a *named definition*, relaunches the same installed MIDlet, and views the latest value in the **Saved** tab without repeating Search. The user can request Edit directly there. **Saved is not Watch**, and loading a definition **never writes/freeze-applies a value**. The list can be renamed/deleted, and failures must be legible.

- Initial core: primitive **static** fields and instance fields with a complete, bounded, verified route from an initialized static, MIDlet, or Displayable root.
- Already qualified extension: direct **static primitive-array element** (field + index) and bounded rooted `Object[]` hops used to reach a final primitive instance field. Carry these only if integrated coherently, with explicit limitations/identity checks. They are *not* a mandate to generalize array traversal.
- **Deferred by maintainer:** new array coverage, multidimensional paths, untraced/arbitrary arrays, `Vector`/`Hashtable` selectors, generic on-demand heap provenance recovery, scanner 2, automatic Freeze/auto-apply, and selectable bundle integration in this PR. Research for these is not a prerequisite to production.
- The existing Search/Refine/Watch/Freeze/Inspector implementations and their semantics outside this feature remain unchanged. No raw addresses, classfile/VM hooks, or new Room database.

## Authoritative architecture

1. **Persistent definition**: label + unique record ID + versioned typed root-relative locator + source-JAR SHA-256 + selected MIDlet entry class. A definition contains *no* runtime object handle, reflective field slot, discovered array ID, last-seen value as identity, or automatic mutation instruction.
2. **Local association**: installed app identity (captured `appId` *and* canonical installed workdir/storageKey), distinct from portable source identity. Equal `appId` values can occur in separate workdirs. Reused paths/JARs after uninstall/reinstall are *not* proof of the old installed identity.
3. **Storage authority**: one authoritative main-process writer using the existing installed-app write guard/lifetime protocol; bounded versioned records and atomic publication under the per-installed-game config namespace. Do not write the same file independently from `:midlet`. Prefer existing services/IPC ownership rather than copying a new service just because R&D used one.
4. **Guest runtime**: `:midlet` owns class initialization checks, Capture, typed Resolve, session-only binding, Read and checked Edit. A new launch creates new bindings. Only bind an initialized static root; never force class initialization while resolving.
5. **UI/controller**: app-owned Memory Editor owns user actions and presentation states, not guest references or persistent file ownership. Saved tab reflects Resolved / Not ready / Changed / Incompatible / Failed separately. A resolved value is an observation, not a promise that the game will not change it concurrently.

Concise flow:
`Search candidate --capture verified typed path--> Save definition [main process]
New runtime --validate installed app, JAR, entry class--> Resolve in :midlet
Resolve --verify every reference edge--> session-only binding
Saved tab --Read--> current value; user Edit --recheck ancestry--> write + readback`

**Transport/data design:** prefer one typed locator model with ordered typed segments (field signature, optional qualified index) over the R&D's parallel `classes[]` / `names[]` / `types[]` / `indices[]` plus `arrayIndex` representation. Keep Android `Bundle`/AIDL if it is the simplest existing IPC; do not add a serialization framework or layers solely for aesthetic purity. Version and validate the on-disk representation independently from IPC.

## Correctness invariants — never relax

- **Exact fields**: lookup by declaring class + name + declared type/descriptor + staticness; `Class.getDeclaredField(name)` alone is wrong for obfuscated JARs. Paris Nights had three `g.aa` fields with different types; a real-world `b.f` Save failed even in the same session until the full-signature fix. Do not rely on reflection enumeration order.
- **Owner ancestry**: verify every root-to-target reference and index, with identity equality, current class loader, permitted type and bounds. Weak provenance is best-effort and can be incomplete. `PATH_UNKNOWN` means *not traced*, not *safe*. Reject unsupported/incomplete/ambiguous routes; don't manufacture a route by scanning for equal values, owner ID, display string or class name.
- **Pinned session binding**: a bound target does **not** silently follow a new player/array object within the same runtime. If ancestry changed or owner collected, stop Edit (and any future Freeze). An explicit new-runtime Resolve may choose the same structural route; **matching structure != same semantic character** when party order/state changed.
- **Checked mutations**: on user Edit, gate by exact runtime token and active binding, revalidate ancestry and typed input, then write and read back. This is best-effort against concurrent guest threads, not a transaction; don't claim absolute atomic identity protection.
- **Installed identity**: bind Save/Delete/rename against captured installed identity plus canonical workdir, source-JAR SHA-256 and selected entry class. A routing record (`MidletSessionStore`) is not independent proof of runtime liveness. Do not allow the wrong installation, stale session, or another MIDlet selector to adopt definitions.
- **Cleanup**: invalidation on runtime close; deleting/replacing a definition must release/invalidate any matching runtime binding so a bounded table cannot fill with deleted entries. No stale asynchronous response may repopulate UI state after runtime/game switch, tab change, delete, or a newer request.
- **Persistence failure**: invalid schema, corrupt/truncated JSON, mismatched fingerprint, and insufficient storage must never silently erase definitions or write to the wrong game. Do not conflate `Unresolved` with an empty saved list. Same-version supported data must be preserved across app updates.

## Known R&D prototype traps — DO NOT COPY

| R&D location | Why it is *not* the production pattern | Production direction |
| --- | --- | --- |
| `researchBindFieldLocator` / restored-to-Watch experiment | Obsolete once direct Saved binding was introduced; adds two competing states and unwanted Watch side effects | Keep only direct Saved binding; regular Watch untouched |
| `SavedVariablesPrototypeService.java` + `ISavedVariablesPrototype.aidl` | Disposable service and JSON-over-AIDL; it also relies on the active routing record even for `list`, tying file operations to a running MIDlet | Consolidate into existing suitable main-process storage authority; keep persistent association separate from active runtime |
| `SavedVariablesPrototypeClient.kt` | Multiple JSON / Bundle / AIDL translations; temporary blocking `bindService` + latch on executor; no durable model | A small typed repository interface + explicit state/error result, IPC only across real process boundary |
| `ManagedJavaMemoryEngine` research path / `ResearchFieldLocator` | Parallel arrays and combined legacy Watch branch; valuable algorithm and safety evidence, not polished public contract | One coherent descriptor + resolver + session binding, bounded by existing engine infrastructure |
| `MemoryEditorController.kt` `refreshSavedVariables` | Up to 64 sequential per-record Binder reads/resolves on tab open; possible slow UI and mixed old/new results | Bounded incremental/batch/lazy read as justified by measured device latency; check request identity on completion |
| `resolveSavedVariable` / `editSavedVariable` / `deleteSavedVariable` | Their posted callbacks do not consistently fence runtime/session/connection/request generation; delete only removes UI row, not the engine binding | Gate *every* async result by current runtime + request; unbind on delete |
| `MemoryEditorRuntimeCompose.kt` Saved edit dialog | Small independent dialog; bypasses broader existing edit validation/feedback UX and uses ad-hoc error copy | Reuse fitting existing UI/validation behavior without coupling Saved to Watch |
| `saved-variables-prototype.json` | Temporary name and schema 1 with experimental array fields; auto-included by legacy full config export | Define production-owned format/version and an explicit migration/retention policy before shipping |
| R&D workflows/fixtures/test JARs | Diagnostic experiments; three temporary CI workflows plus large research test collection | Retain a small set of discriminating regression tests; no permanent research CI in production |

These are observed code/design issues, not a claim that all tests or devices failed. Do not refactor unrelated Memory Editor internals merely to accommodate Saved Variables.

## Data lifecycle and compatibility

- Decide explicitly whether to **read/migrate** old R&D prototype definitions or keep them isolated. Never reinterpret incompatible schema silently, never destroy users' other save/config data, and never report a corrupt/mismatched file as 'no saved variables'.
- The config directory is included in current full Library bundles; prototype definitions may therefore be carried by existing exports *incidentally*. Do not mistake this for implemented selective Saved Variables sharing. Future selectable export/import is a separate change: definitions-only import must attach to a user-chosen existing installed compatible game, with new local association and conflict rules; never copy source appId/storage paths to recipient.
- Installed selection and source JAR match are separate from gameplay save-state semantics. Equal SHA-256 proves byte-identical source JAR, not identical party/character across game saves.
- Listing/renaming/deleting saved definitions should not require starting guest code solely to access local metadata; Resolve/Read/Edit necessarily require a valid live guest runtime. Respect ownership of the installed Library record.

## UX expectations

- Save a selected candidate with a name; only allow when capture proves a supported typed path. Make unavailable reasons actionable.
- Saved tab: show name, current value/type when resolved, and distinct not-ready/changed/incompatible statuses. Attempt resolve on entering the tab if appropriate; manual retry for not-ready roots. A changed binding must not silently rebind while the same runtime is active.
- Edit directly within Saved; do not create Watch entries, auto-apply values, or auto-freeze. After Edit, show confirmed readback/error.
- Rename/Delete need deterministic persistence and visible feedback; deletion must not touch guest memory. Keep layout adaptive portrait/landscape, safe insets, centered dialogs, English canonical and Indonesian localization.

## Acceptance gates (proportionate)

1. Focused engine tests: exact duplicate-name typed field; static and rooted instance field Capture/Resolve after new runtime; reference replacement rejects Edit; index bounds and explicit unsupported path; Watch behavior unchanged.
2. Persistence integration: save/list/rename/delete, atomic read/write and corrupt-file preservation, SHA-256 / MIDlet selector mismatch, same-path uninstall/reinstall, different workdirs with coincidentally equal appId, process restart.
3. Real IPC/lifecycle: no-Search Resolve after normal Exit/relaunch and process-death/relaunch (Android API 23 and representative current API); stale UI callback ignored and deleted Saved binding released. The R&D external-host normal-exit test passed API 23/35, but that does not validate every later UI/prototype revision.
4. Device sanity: game examples that previously exposed bugs (obfuscated duplicate field, static primitive, rooted instance/party array), and orientation/safe insets. Confirm a structurally valid indexed path against the intended character manually after restart.
5. Check final *effective diff* against current `alpha` for duplicate owner state, discarded research branches, unused tests, accidental bundle changes, and regressions. Run relevant JVM + targeted Android + UI checks; do not bulk-port every R&D test/CI fixture.

**Decision gate:** GO for production implementation, **not** for merging R&D source or releasing without production qualification. If a missing safe provenance route is observed, fail closed and report unsupported; defer new graph recovery and untraced/multidimensional array support.

## Reference map (read selectively)

- R&D code: `app/src/main/java/io/github/h3nb/jlmodplus/memory/{ManagedJavaMemoryEngine.java,MemoryTargetBridgeService.java,MemoryEngineService.java,MemoryEditorController.kt,MemoryEditorRuntimeCompose.kt}`.
- Disposable storage spike: `app/src/main/java/io/github/h3nb/jlmodplus/memory/{SavedVariablesPrototypeService.java,SavedVariablesPrototypeClient.kt}`.
- R&D engine tests: `app/src/test/java/io/github/h3nb/jlmodplus/memory/SavedLocatorEngineResearchTest.java`; process tests: `app/src/androidTest/java/io/github/h3nb/jlmodplus/memory/`.
- Current integration contracts in `alpha`: `docs/memory-editor-identity.md`, `docs/preset-config-contract.md`, `docs/library-data-contract.md`, `docs/testing.md`, `docs/app-ui-development.md`, `docs/localization.md`.
- Evidence/history: [EVIDENCE_ARCHIVE.md](EVIDENCE_ARCHIVE.md) and [PR #215](https://github.com/H3nb/JL-Mod-Plus/pull/215).
- Qualification CI: [normal-exit API 23/35 run](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41369006482), [direct Saved field build](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41377029749), [primitive-array research](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41384445130), [rooted instance research](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41387794879).
