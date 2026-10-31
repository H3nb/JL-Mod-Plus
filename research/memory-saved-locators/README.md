# Saved Memory Variables — Structural Locator R&D

Status (2026-11-01): **CONDITIONAL GO** for a constrained persistent-variable resolver;
**NO-GO** for the claim that matching source JAR alone guarantees the same semantic variable.
Base examined: \`alpha\` at \`b51babf3ae1f903ddce64e25d9b91b128f28804b\`.

## Problem and evidence

Current \`ManagedJavaMemoryEngine\` owns runtime-scoped candidate IDs; its
\`OwnerBucket\` keeps object identities weakly, while \`FieldSlot\` is derived
from reflection enumeration order. \`inspectPath()\` observes a bounded path
from MIDlet / Displayable / initialized static roots, but returns display text
and never creates persistent location definitions. No raw memory address,
stable array-number assignment, or cross-runtime rebinding contract exists.

Normative Android/Java reflection references:
- \`Class.getDeclaredFields()\` has no order guarantee.
- \`Class.forName(name, false, loader)\` does not itself initialize the class.
- Reflective reading of a static field can initialize its declaring class; gate
  root selection on the existing initialized-guest-class registry.

Source:
- \`AGENTS.md\`, \`docs/memory-editor-identity.md\`,
  \`docs/preset-config-contract.md\`, \`docs/testing.md\`
- \`app/src/main/java/io/github/h3nb/jlmodplus/memory/ManagedJavaMemoryEngine.java\`
- \`app/src/main/java/javax/microedition/shell/MemoryDiscoveryBridge.java\`
- \`app/src/main/java/io/github/h3nb/jlmodplus/installer/AppInstaller.java\`
- \`app/src/main/java/io/github/h3nb/jlmodplus/librarydb/LibraryAppBundleExporter.kt\`

## Isolated host-JVM experiment

\`bash research/memory-saved-locators/run.sh\` (JDK with \`javac --release 8\`).

\`fixture/Game.java\` is built as an actual JAR. The standalone
\`probe/LocatorProbe.java\` loads the same JAR through **two different class
loaders**, then uses only the JAR SHA-256, trusted root choice, declaring
class name, field name/type, and bounded object-graph steps to re-resolve the
target. The probe intentionally does not use JL-Mod Plus production code.

Observed locally with OpenJDK 21: \`PASS checks=15\`.
- A field locator re-resolves in the second loader; a write to the second
  runtime leaves the first untouched.
- An already initialized static field resolves; an unregistered static root,
  mismatched checksum, wrong field name/type and invalid traversal fail.
- A replaced owner with the **same value** is not accepted as the original
  previously bound object at the checked-write boundary.
- Swapping two equal-valued \`Object[]\` entries leaves a structural array-index
  path valid but changes the semantic target. This disproves unconditional
  portability of indexed dynamic objects.

Limitations: simulated Java class loaders rather than JL-Mod Plus dex guest,
reflection on a desktop JVM, an in-memory descriptor (no JSON/persistence),
no process IPC, no guest concurrency stress, no real-game/device evidence.
\`checkedWrite()\` is a research guard, not an atomic synchronization protocol
for guest writes. Do **not** interpret the host success as Android integration
or production-readiness.

## Architecture decision

Prefer **typed root-relative structural descriptors**:
1. On explicit Save, inspect the selected existing candidate; materialize the
   already available bounded provenance into typed root/field/index components,
   reject changed or incomplete paths. Do not parse its display text.
2. Separate local installed-MIDlet ownership (\`appId\`, \`storageKey\`) from
   portable compatibility (SHA-256 of retained \`res.jar\`, selected MIDlet entry
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
   qualification. Dynamic \`Object[]\` entries, Vector/Hashtable positions, and
   unrooted targets should not be claimed generally portable.

A second, on-demand bounded root-to-target traversal at *save time* may expand
coverage if existing best-effort single-predecessor provenance is insufficient;
do not build it until real game samples prove that benefit. Avoid bytecode
instrumentation, heap-wide signature scans, and auto-apply writes without
specific demonstrated need.

## Production boundaries / next high-value validation

- Implement a disposable integration spike on this research branch using the
  real \`MemoryDiscoveryBridge\`, \`ManagedJavaMemoryEngine\`, and :midlet IPC.
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

No production files or branch \`alpha\` were edited. No merge.
