# Saved Memory Variables — Structural Locator R&D

Status (2026-11-01): **CONDITIONAL GO** for a constrained persistent-variable resolver;
**NO-GO** for the claim that matching source JAR alone guarantees the same semantic variable.
Base examined: `alpha` at `b51babf3ae1f903ddce64e25d9b91b128f28804b`.

## Current research conclusion: identity fencing and stale owner edits

[Research CI run 41291077208](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41291077208)
passed emulator Android API 23 and 35 plus JVM 17 and 21.
[Android CI run 41291077141](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41291077141)
passed build/tests, screenshot validation and lint.
[Focused engine research run 41291073161](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41291073161)
passed the actual Managed Memory Engine test plus standalone graph/locator probes.

**Installed identity experiment** (`SavedLocatorIdentityResearchAndroidTest`):
- A main-process `InstalledAppWriteGuard.run(canonicalAppPath, capturedAppId, write)`
  plus JAR digest precondition and Android `AtomicFile` can publish a research
  definition in the per-install config directory.
- Simulating uninstall/reinstall by deleting and reinserting the Library row
  at the *same path* with the *same source JAR* produces a different `appId`.
  This exercises the installed-identity fence, **not** the full installer. A request carrying the stale `appId` fails
  without touching the saved file.
- Modifying the JAR while retaining the current `appId` makes the added
  caller-side SHA-256 comparison fail before publication. The existing guard
  itself does **not** know the source JAR hash.
- Independent workdirs can both allocate `appId = 1`. The guard validates
  the **caller-supplied path**; it cannot detect substitution of a different
  canonical workdir if its current appId happens to be the same. A Saved
  Variables local record must preserve/capture canonical workdir + storageKey
  together with the installed ID. Portable exports must instead omit local
  identifiers and require reassociation with an installed game after import.
- `AtomicFile` supplies atomic publication but **no concurrent writer
  serialization**. Use one main-process write authority and the existing
  installation permit; do not let the `:midlet` process independently write
  the same Saved Variables file. The test is not an implemented service.

**Stale-reference experiment** (`SavedLocatorEngineResearchTest`): after
restoring a field-only descriptor into Watch and confirming a VERIFIED path,
replacing the game root's Player changes the path to CHANGED. While the old
object remains strongly reachable in the test, ordinary `editTyped` can
still write its numeric field without affecting the new Player. This respects
the existing runtime Watch ownership semantics but is **not a safe default
for an imported Saved Variable**. Restrict the eventual saved-binding Edit/
Freeze authority when the verified path is lost or changed, without
retroactively changing ordinary Watch behavior. A pre-write path check still
cannot eliminate races with concurrent guest threads.

**Integration direction**: keep versioned typed descriptors and source-JAR
SHA-256 in `configs/<storageKey>`, which is already included in full Library
bundle export. Separate the portable definition from the local installation
association and never auto-Edit or auto-Freeze on import/load. One explicitly
selected game and a successful Resolve precede any user-requested mutation.


## Verified checkpoint: transformed-JAR restart on Android 6 and 15

[Memory locator R&D run 41283720771](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41283720771)
**passed all four jobs**: emulator API 23 and API 35 plus structural JVM tests
on JDK 17 and JDK 21. [Android CI run 41283720802](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41283720802)
also passed build/tests, lint and screenshot validation on the same source revision.

The Android instrumented experiment:
1. Converts the actual `Locator-Lab.jar` with AppInstaller's DX transform and
   registers an installed Library identity.
2. Searches the guest's `health = 89` once in the first `:midlet` process,
   captures a typed field path via Binder and atomically saves a test-only
   JSON record containing the source-JAR SHA-256.
3. Terminates **only the old guest process** (not its Android task), relaunches,
   confirms a new runtime token, and checks the persisted JAR fingerprint.
4. Resolves the saved descriptor into one fresh Watch item **without Search**,
   reads its value, edits it from `89` to `90` using the existing managed
   Edit path, verifies the Watch readback, then rejects a wrong-type descriptor
   without losing the valid Watch.

Root cause of earlier intermittent `TARGET_LOST`: the test's restart helper
called `finishAndRemoveTask()` before launching the next MIDlet. The app's
`MidletKeepAliveService.onTaskRemoved()` intentionally invokes an asynchronous
**user-stop/emulator-shutdown** path; this could close the newly launched runtime.
The revised experiment separates process-death recovery from user-initiated
task removal. This is a **test-fixture lifecycle correction**, not evidence
that production runtime safety checks were wrong.

**Scope of proof**: a small, controlled converted JAR and one field-only
instance descriptor on two Android API levels. The prototype still lacks a
user-facing Save/Load feature, app-owned persistence API and installed-app
write fencing, portable import/export and real-game semantic qualification.
The test-side fingerprint is checked before Binder binding, but the Binder
spike is not a production trust boundary. Do not claim that matching JAR
guarantees the same semantic game variable.


## Problem and evidence

Current `ManagedJavaMemoryEngine` owns runtime-scoped candidate IDs; its
`OwnerBucket` keeps object identities weakly, while `FieldSlot` is derived
from reflection enumeration order. `inspectPath()` observes a bounded path
from MIDlet / Displayable / initialized static roots, but returns display text
and never creates persistent location definitions. No raw memory address,
stable array-number assignment, or cross-runtime rebinding contract exists.

Normative Android/Java reflection references:
- `Class.getDeclaredFields()` has no order guarantee.
- `Class.forName(name, false, loader)` does not itself initialize the class.
- Reflective reading of a static field can initialize its declaring class; gate
  root selection on the existing initialized-guest-class registry.

Source:
- `AGENTS.md`, `docs/memory-editor-identity.md`,
  `docs/preset-config-contract.md`, `docs/testing.md`
- `app/src/main/java/io/github/h3nb/jlmodplus/memory/ManagedJavaMemoryEngine.java`
- `app/src/main/java/javax/microedition/shell/MemoryDiscoveryBridge.java`
- `app/src/main/java/io/github/h3nb/jlmodplus/installer/AppInstaller.java`
- `app/src/main/java/io/github/h3nb/jlmodplus/librarydb/LibraryAppBundleExporter.kt`

## Isolated host-JVM experiment

`bash research/memory-saved-locators/run.sh` (JDK with `javac --release 8`).

`fixture/Game.java` is built as an actual JAR. The standalone
`probe/LocatorProbe.java` loads the same JAR through **two different class
loaders**, then uses only the JAR SHA-256, trusted root choice, declaring
class name, field name/type, and bounded object-graph steps to re-resolve the
target. The probe intentionally does not use JL-Mod Plus production code.

Observed locally with OpenJDK 21: `PASS checks=15`.
- A field locator re-resolves in the second loader; a write to the second
  runtime leaves the first untouched.
- An already initialized static field resolves; an unregistered static root,
  mismatched checksum, wrong field name/type and invalid traversal fail.
- A replaced owner with the **same value** is not accepted as the original
  previously bound object at the checked-write boundary.
- Swapping two equal-valued `Object[]` entries leaves a structural array-index
  path valid but changes the semantic target. This disproves unconditional
  portability of indexed dynamic objects.

Limitations: simulated Java class loaders rather than JL-Mod Plus dex guest,
reflection on a desktop JVM, an in-memory descriptor (no JSON/persistence),
no process IPC, no guest concurrency stress, no real-game/device evidence.
`checkedWrite()` is a research guard, not an atomic synchronization protocol
for guest writes. Do **not** interpret the host success as Android integration
or production-readiness.

## Architecture decision

Prefer **typed root-relative structural descriptors**:
1. On explicit Save, inspect the selected existing candidate; materialize the
   already available bounded provenance into typed root/field/index components,
   reject changed or incomplete paths. Do not parse its display text.
2. Separate local installed-MIDlet ownership (`appId`, `storageKey`) from
   portable compatibility (SHA-256 of retained `res.jar`, selected MIDlet entry
   class). Descriptor names/types must refer to guest-owned classes.
3. Persist a versioned, bounded, atomic per-MIDlet file under the config
   namespace, with the main/default process as authoritative writer under an
   installed-identity fence. No special database is justified.
4. On demand, check exact JAR, active runtime, class initialization, and each
   structural edge. Resolve **inside the :midlet process** and register a fresh
   runtime-scoped candidate/Watch target, reusing existing write checks and
   cancellation rather than introducing a separate reflective writer.
5. Once bound in one session, Watch should keep its existing owner identity
   and LOST behavior; no silent rebinding to a same-valued replacement. Rebind
   for a new session or when explicitly requested.
6. Initially prioritize static primitive fields and deterministic guest-owned
   instance-field chains. Primitive-array indices require explicit stability
   qualification. Dynamic `Object[]` entries, Vector/Hashtable positions, and
   unrooted targets should not be claimed generally portable.

A second, on-demand bounded root-to-target traversal at *save time* may expand
coverage if existing best-effort single-predecessor provenance is insufficient;
do not build it until real game samples prove that benefit. Avoid bytecode
instrumentation, heap-wide signature scans, and auto-apply writes without
specific demonstrated need.

## Production boundaries / next high-value validation

- Implement a disposable integration spike on this research branch using the
  real `MemoryDiscoveryBridge`, `ManagedJavaMemoryEngine`, and :midlet IPC.
  Verify one field-based fixture survives two separate MIDlet launches.
- Exercise a **real converted JAR on Android** (at least one static field,
  one instance chain, and one unstable/dynamic object). Compare source JAR
  checksum before attempting resolution; document converter/version effects.
- Test mid-run owner replacement and mutation races: stale/ambiguous targets
  must fail closed, without write. Existing candidate-ID authority remains
  the only mutation path.
- Verify lifecycle, install-delete-reinstall, workdir switch, bundle export,
  read-only/bad imported files, and atomic file publication.
- Assess a few representative real games before claiming a useful support
  rate. Report unsupported location types honestly; identical JAR does not
  imply identical game saves, chosen MIDlet entry point or semantic object.

No production files or branch `alpha` were edited. No merge.


## Follow-up: actual engine and alternatives (2026-11-01)

Research-only GitHub Actions run 41260056197 **passed all three jobs**:
- Java 8 JAR across two JDK classloaders: direct locator pass.
- Alias-preserving graph traversal: 3 paths to the same target; 2 retained
  after one equal-valued alias is redirected; bounded traversal pass.
- The actual `ManagedJavaMemoryEngine` JVM test bound a new target directly
  into its private OwnerBucket/Watch machinery after a runtime restart,
  without starting a second search, then edited via the **existing** typed
  edit method. Pass. The test intentionally uses reflective access to
  private internals: this is proof of reuse feasibility, not a production API.

**Falsified hypothesis:** a predecessor DAG storing only shortest-depth
incoming edges is sufficient to retain useful alternative routes. It lost
both two-hop paths when a one-hop route reached the same target first.
The corrected `DagProbe` expands each object once but records bounded
incoming edges even when their routes are longer; path enumeration detects
cycles and caps depth/results. Local JDK 21 result: 3 routes before one
alias replacement, 2 afterward, 5 nodes and 5 edges; bounded search pass.
This is a tiny fixture, not an Android performance benchmark.

Code inspection found the source transformer already handles static
primitive/reference fields without a source `<clinit>`: the
`AndroidClassVisitor` synthesizes the initializer tail publication for
managed static roots. Avoid duplicating this hook. Also, do not bypass
initialization checks by reflecting an unregistered static class.

**Updated recommendation:** start with the *existing single verified
provenance route* and direct-binding API for static/instance targets; retain
the multi-parent on-demand traversal as a measured expansion path only if
real JAR samples prove an actual coverage gap. Multi-path identity
agreement is useful evidence but cannot establish game-semantic identity
when the game intentionally changes which entity a stable field/index
refers to. No auto-apply on imported definitions.

Remaining production gates: converted-JAR Save/Reload through Android :midlet IPC,
concurrent mutation safety, file persistence and import,
install/delete/reinstall ownership fencing, and representative-game
coverage measurements.

All commits remain disposable R&D. Do not merge this branch.


## Handset evidence: converted Locator Lab across two launches (2026-11-01)

The maintainer tested the separately compiled `Locator-Lab.jar` through JL-Mod
Plus on an Android device and supplied ten Memory Editor/Inspector screenshots:
five from launch 1 and the corresponding five from launch 2. The screenshots
are retained in the conversation; no device identifiers or screenshot images
are committed here.

| Field / observed value | Launch 1 label | Launch 2 label | Observed reference |
| --- | --- | --- | --- |
| `stable.coins` / 1247 | target #4 | target #1 | `MIDlet -> LocatorLabMIDlet.stable -> Player.coins` |
| `replaceable.coins` / 2468 | target #5 | target #2 | `MIDlet -> LocatorLabMIDlet.replaceable -> Player.coins` |
| `globalCoins` / 3579 | static | static | `research.locator.LocatorLabMIDlet.globalCoins` |
| `health` / 89 | target #7 | target #4 | `MIDlet -> LocatorLabMIDlet.health` |
| `chest[0]` / 4681 | target #8 | target #5 | `MIDlet -> LocatorLabMIDlet.chest[0]` |

The printed rooted paths and source types stayed consistent, despite different
runtime target numbers. The fixture's source intentionally creates new owners
at each launch and resets all initial values; matching values alone do not
provide identity evidence. This establishes **manual converted-JAR installation,
execution and provenance display on a handset**, upgrading the prior desktop/
synthetic-ART-only evidence.

**Not established:** automatic cross-session resolution from a saved
descriptor, on-disk persistence, matching JAR checksum enforcement, IPC Save/
Load, or correct Watch/LOST behavior after `Replace player`. In particular,
two launches each showing `replaceable.coins` do *not* prove that the Watch
must silently follow a replaced object *during one launch*. Similarly,
`chest[0]` demonstrates structural indexing but not semantic index stability.

Next discriminating device experiments:
1. Add `replaceable.coins` to Watch, invoke `Replace player`, and refresh:
   the existing Watch must not silently retarget to the new same-valued
   object. It may still read the detached old object until GC; Inspector
   should mark its reference path changed, and Watch may become LOST after
   collection. A fresh Search may find the new player.
2. Search `1357` (two equal-valued `party[]` objects), inspect both,
   invoke `Swap party`, and verify owner identity vs array-position
   provenance. Value-only matching must not imply ownership.
3. Build a genuinely user-accessible Save/Reload flow under the runtime IPC
   and installed-JAR fingerprint gate, then relaunch without any new Search.

Do not infer a general game compatibility rate from this one synthetic JAR.


## Handset follow-up: replacement and equal-valued swap (2026-11-01)

The maintainer provided seven additional Inspector/Watch screenshots from the
actual converted `Locator-Lab.jar` running on their Android device.

**Replace player**:
- Before replacement: target #1 (`Player.coins`, `2468`) verifies the root
  `MIDlet -> LocatorLabMIDlet.replaceable -> Player.coins`.
- The fixture executes `replaceable = new Player(2468)`. After replacement,
  the same target #1 still reads `2468`, but Inspector reports **reference
  path changed since scan**; it does not claim that the new object is the
  original candidate.
- After leaving the app in the background and returning, the watched old
  candidate is shown as **LOST** with a safety-pause indication. This is
  consistent with collection of the detached weakly held owner, but no GC
  timing or exact causal chain is established by screenshots alone.
- Do not interpret this as proving that edit/freeze to a still-live but
  detached owner is prevented: the current mutation gate validates the
  selected owner identity, whereas provenance is diagnostic and separate.

**Swap party**:
- Before swap: target #3 is `party[0] -> Player.coins`, target #4 is
  `party[1] -> Player.coins`, with `1357` for both.
- The fixture swaps the two `Player` object references, not the coin values.
- After swap: target numbers #3/#4 and values remain the same, but **both**
  Inspector paths report that reference ancestry changed.
- This is direct evidence that `Object[]` index plus an equal numeric
  value **cannot** establish persistent semantic target identity, even when
  the structural array path remains syntactically valid.

**Architecture consequences**:
1. Preserve runtime Watch owner identity: once bound, do not automatically
   follow new field/array references in the same runtime. Only a deliberate
   new-session resolve or explicit user-requested rebind may consider a
   replacement owner.
2. A detached object's value may remain readable until collection. Changed
   reference ancestry and LOST are **different states**, not interchangeable.
3. Keep automatic persistent binding for indexed `Object[]` chains disabled
   until a semantic selector beyond position has real-game evidence. The
   research field-only capture intentionally rejects these chains.
4. Do not add value-based recovery heuristics or infer a compatibility rate
   from one lab MIDlet. The next decisive proof is a real Save -> close ->
   launch -> resolve through installed-identity/JAR fingerprint + IPC -> Watch
   with no second Search, followed by an explicit user-controlled Edit.

Screenshot contents are summarized, not checked into this repository. No
production code was changed as a consequence of these two manual tests.


## Restored Watch provenance and mutation boundary

The converted-JAR test now exercises an authentic installation/conversion path
and confirms that a versioned, source-JAR-fingerprinted field locator can be
read after an actual `:midlet` process relaunch and rebound to a fresh Watch
ID **without a second Search**. The later converted-JAR process-restart run
on API 23/35 also verified Edit and readback; see the checkpoint above.

A source inspection found a previously missing invariant: research rebinding
inserted the owner into Watch without restoring `OwnerBucket.provenancePath`.
The Inspector would therefore report `PATH_UNKNOWN` even for a verified
import. The research resolver now builds weak `ProvenanceNode` ancestry
along the exact typed field chain and verifies it before binding, reusing
the existing Inspector observation mechanism. A focused engine contract test
checks that the recovered Watch path is `PATH_VERIFIED` and becomes
`PATH_CHANGED` (without retargeting) when the MIDlet replaces its owner.

**Later R&D outcome:** the experiment now marks restored Saved Variables
per Watch row, not by shared owner, and checks exact provenance in the existing
Edit/Freeze commit path. Changed ancestry rejects a Saved Variable Edit and
pauses its Freeze tick; ordinary Watch behavior is unchanged. This does not
create an atomic transaction with concurrent guest code, and imported
variables are never automatically edited or frozen.

The current experimental instrumentation runner occasionally reports
`Process crashed` without an assertion; this alone does not establish
an engine crash. A recent API 35 attempt also failed at Gradle dependency
resolution before test execution. Evaluate precise XML/logcat and actual
test start/completion before attributing failures to application code.


## Bundle component compatibility and restored mutation experiment

**Product decision from the maintainer:** sharing Saved Variables is part of the future
selectable **Export Bundle / Import Bundle** workflow, not a parallel standalone
sharing feature. Do not modify the current exporter/importer in this research branch:
their component-selection redesign is explicitly pending.

**Observed boundaries:**
- The existing exporter includes every file under `configs/<storageKey>`, while
  Universal Bundle v2 records the retained source JAR's SHA-256 per bundled app.
  The current importer expects a game JAR in an installable app payload. A future
  **Saved Variables only** selection therefore must follow an *attach to existing
  installed game* path rather than pretending to be an installable app.
- The *portable* Saved Variables component contains an exact source-JAR SHA-256,
  MIDlet entry-class selector, versioned bounded typed descriptors, labels and
  intended display metadata. It contains **no absolute workdir, storageKey,
  local appId, runtime handles, current values requiring autoapplication, or
  Android-specific file paths**.
- A *local association* is checked against the installed workdir/storageKey/
  captured appId and source-JAR fingerprint under one main-process write
  authority. The bundle importer must establish a **fresh local association**,
  after the user chooses an installed MIDlet and compatibility checks succeed.
  Copying the exporter's original appId/path into the recipient's Library is
  not acceptable; equal numeric appIds can belong to different workdirs.
- Partial bundle import must not overwrite unrelated config, saved games,
  layouts, or existing Saved Variables without explicit component ownership
  and conflict policy. Restore of the definitions is not equivalent to
  enabling Freeze or auto-Edit. A compatible JAR does not guarantee that a
  specific game-state owner has the same semantics.

**Disposable mutation-authorization spike:** a research-only Watch membership
marker is retained per Watch row, not per OwnerBucket. On the actual engine's
Edit/Freeze precommit path, only a restored Saved Variable row must still have
`PATH_VERIFIED`; the existing Freeze tick pauses that row if ancestry changes.
Ordinary Watch rows keep their present original-owner identity behavior.
Verification is best-effort against concurrently mutating guest code, not an
atomic transaction with guest threads. The experimental code is **not** a
production security boundary and contains no user-facing controls.
The single-predecessor owner model has one more important limitation:
two typed paths may reach the same Java owner. R&D currently compares
actual weak predecessor edges and rejects a Bind when an existing owner
would lend it a **different** provenance. This deliberately sacrifices
some alias coverage instead of falsely authorizing an unrelated path.

**Normal-exit test design:** a true user-initiated emulator exit invokes
`MidletKeepAliveService` and `EmulatorShutdownService`, which intentionally
terminates `:midlet`, auxiliary processes **and the app's main process**.
An in-app instrumentation runner cannot safely claim a successful normal-exit
test if its own process is destroyed. This needs an external ADB/CI host
harness: record the saved definition, request intentional exit, wait until
the old process tree is gone, relaunch the same installed game, then start a
fresh verifier process to read and resolve the saved definition. The existing
process-death CI is a **separate** proven scenario.
