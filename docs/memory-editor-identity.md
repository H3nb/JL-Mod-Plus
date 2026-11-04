# Managed Memory Editor candidate identity

This document records runtime candidate identity and the separately supported Saved Variables contract. Saved definitions are not Watch entries.

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

Closing the bubble editor remembers its last tab and Search page only in the
`:memory_engine` process for the current MIDlet runtime token. Inspector remembers
only its target identity and origin tab; reopening fetches a fresh, checked
snapshot. Missing or changed Inspector targets fall back to Search, Watch or
Saved, never to an old snapshot. A new MIDlet token starts at Search. No tab
preference, Inspector value, or guest object is written to persistent storage.

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

Predecessor capture is best-effort and bounded independently from numeric search
results. When memory pressure is high or the configured byte budget is small, the
scanner may omit optional ancestry rather than reject an otherwise valid numeric
search. Inspector then reports an unknown path, not a false verified one. Retained
paths are charged once to the owning result revision; transient paths are also
accounted during scanning without double-charging them at the commit boundary.

The presentation renders a compact summary from typed source metadata while
Inspector exposes the full declaring class, field name, type and, when verified,
the root-relative path. When direct-field source metadata is unavailable but a
root-relative path is verified, Inspector identifies that verified path rather
than reporting the source as untraced. A checked path is only an observation of the current
runtime, not a stable identity of a game entity after restart. The existing
diagnostic location text remains available independently, but must not be parsed
to construct structural identity. An observation may become stale immediately
after it is read because the guest is concurrent; refreshing revalidates it.

## Persistent Saved locator contract

Saved definitions contain a label, stable record ID and versioned root-relative locator.
The canonical locator is a bounded ordered sequence of **field** steps (exact declaring
class, name, type and staticness checked at runtime) and **array index** steps (exact
Java array type and index). Terminal targets are editable numeric primitive fields
or primitive-array elements, including nested arrays reached from trusted roots.
No field slot, owner handle, object reference, search revision or last-seen value
is persisted. Search/Watch runtime candidate IDs remain independent.

Capture requires a current verified ancestry from the MIDlet, Displayable or an
already-initialized static class; resolving never initializes guest classes. It
checks every field signature and array bound and retains weak, pinned runtime
ancestry. The Saved tab may release and resolve a changed binding again while no
Saved edit is open, displaying a nonblocking **Target updated** hint for a few seconds during foreground polling. An open edit
pins its original binding: replacement during the dialog rejects the write, and
the user can reopen the editor. Rebinding never writes or activates Watch/Freeze.
An indexed party slot can refer to a different character without any structural
error, so the displayed current value remains authoritative to the user.

Saved storage is owned by the main process under
`configs/<storageKey>/saved-variables.json`, as a schema-3 AtomicFile document
capped at 64 records/64 KiB. Schema-2 typed field/index locators are accepted and
converted to the canonical steps model at the read boundary; the next authorized
mutation publishes schema 3 atomically. Corrupt, unsupported-version or oversized
documents are not overwritten. The document remains portable: installed Library
IDs, workdirs, JAR fingerprints and runtime handles are never serialized.
Every Saved authority mutation still validates active installed identity and
uses `InstalledAppWriteGuard`. The R&D prototype format is not migrated.

Definitions can be listed and renamed/deleted without a running guest; resolving,
reading, editing and Inspector require a live MIDlet. A committed delete removes
the definition from the active polling catalog and releases its runtime Saved
binding; it does not remove or reactivate a separate Watch/Freeze. Saved selection is separate
from Search/Watch selection. Foreground Saved polling reads active values and
retries unresolved paths without reloading the document on every frame. Opening
an edit drains preceding IPC, snapshots the bound value and pauses automatic Saved
rebind until dismissal. Normal guest value changes during a manual edit are not
a reason to reject the write; the engine checks pinned ancestry/type and readback.
Saved Inspector sibling edits retain their stronger expected-snapshot comparison.

A user-initiated Saved **Freeze after edit** promotes the verified current owner
to Watch. It does not start merely because a bookmark re-resolved. If the owner
changes, the old Watch Freeze pauses; an explicit new Freeze updates that Watch
to the current binding rather than reactivating the old owner or creating a
duplicate. An edit can succeed while a subsequent Freeze promotion fails; report
the two outcomes separately. Inspector's checked sibling Watch/Freeze behavior
remains session-bound.

Selective bundle export has a separate Saved component. Saved-only imports
**merge** bookmarks into an existing collection by canonical locator: local
records and labels win, duplicate targets are skipped, imported ID collisions
are assigned fresh local IDs, and size violations reject the entire import.
A configuration+Saved restore **replaces** Saved as part of the existing
transactional backup restore. Selected unrelated config and save data remain
independent. Imports and migrations never partially publish Saved data.

Unsupported routes through arbitrary containers, untraced ancestry or fabricated
roots remain unavailable. No automatic value restore, VM hook, raw scanner,
additional idle traversal or persistent Freeze is introduced.

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
