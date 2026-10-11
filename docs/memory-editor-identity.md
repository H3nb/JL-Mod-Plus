# Managed Memory Editor candidate identity

This document records the currently supported candidate-identity contract and the boundary
for future saved-cheat work. It does not define a saved-cheat format or enable persistence.

## Runtime target identity

The managed engine addresses a candidate by the active runtime generation, a logical owner
handle and kind, and a primitive field slot or array index. The handle is meaningful only
during the current MIDlet execution. A Java object is weakly retained by its owner bucket;
GC or a changed runtime makes its old target unavailable. Never rebind a missing owner by
matching the value, class name, slot, or another array with equivalent contents.

The `FieldSlot` number is derived from reflection enumeration, whose order is not a
persistent contract. Array numbers are also allocated by runtime discovery order.
Neither is suitable for a cross-restart descriptor or for comparing DX/D8 output.

Search, Refine, Results, Watch, Freeze, Inspector, and edits continue using the checked
runtime ID rather than a field-path string. Provenance never authorizes a write.

## Direct-reference observation

Results and Watch pages optionally include a structured source record with a kind
(static field or instance field), a fully qualified declaring class, an exact field
name, and a Java field type name. For array elements, this is one observed field
that directly points to the array, **not** a unique owner or a full path from a
trusted root. Source verification compares that field's current reference with
the exact array object at page/inspection read time; an instance parent is kept
weakly. Metadata is bounded and complete or absent; a class/field/type name
must not be truncated and treated as a valid identity.

A typed direct-field source is separate from the verified root-relative path.
During scanning, one weak predecessor is captured per queued object and shared by
all numeric candidates in that object or array. Inspector materializes at most
24 steps and checks the current MIDlet, Displayable or initialized static root,
every intermediate field or `Object[]` index, and the exact candidate owner.
A changed edge is reported as changed; a chain beyond the depth/display limit
is incomplete. There is no automatic rebinding by value or by path.
For supported direct arrays, many numeric elements reuse the same ancestry.

An array discovered through a container or an unsupported route may have no
verified root path even when it has an observed direct-field source. Container
elements do not acquire invented keys or positional identities; a `Vector`/
`Stack`/`Hashtable` entry is not a persistent locator. This means **not
traced**, not **no source exists**. Multiple fields may point to the same object;
the first observed one is not guaranteed to be the preferred future route.
An instance field's name alone never identifies the particular instance.

The presentation renders a compact summary from typed source metadata while
Inspector exposes the full declaring class, field name, type and, when verified,
the root-relative path. A checked path is only an observation of the current
runtime, not a stable identity of a game entity after restart. The existing
diagnostic location text remains available independently, but must not be parsed
to construct structural identity. An observation may become stale immediately
after it is read because the guest is concurrent; refreshing revalidates it.

## Boundary for future resumable targets

A future resolver may investigate a selected candidate on demand with bounded
memory, traversal depth, cancellation and identity-based visited tracking.
It must establish a complete, unambiguous root-to-target path, not infer one
from coincidentally equal values or the order in which objects were discovered.
Static paths need declaring class, field name, type and array index; instance
paths also need a route from a trusted root to the specific instance. No
candidate is claimed portable until tested in a new runtime. Such a resolver
must not force initialization of a guest class that is not ready.

A future persistent record will need independent installed-MIDlet identity
and compatible source-JAR identity, plus structural path verification. Nothing
in the current contract serializes candidates, auto-applies writes or introduces
raw-address scanning, JVM instrumentation, or additional MIDlet bytecode hooks.

## Accounting and resource bounds

Result staging now tracks owner allocation and buffer-capacity growth
incrementally rather than rescanning all owner buckets for each numeric
candidate. Finish-copy overlap, history, and traversal budgets still use
saturating checked arithmetic. One retained predecessor is charged per
distinct shared ancestor node, not per numeric element; temporary traversal
entries are charged while the scanner is active. Captured references are
weak and do not keep guest owners alive.

No extra traversal, worker, timer, or bytecode hook runs when the editor
is idle. Root-path validation is lazy on Inspector requests. A second
on-demand graph traversal is intentionally not implemented: untraced
containers remain untraced until a specific safe selector is supported.
