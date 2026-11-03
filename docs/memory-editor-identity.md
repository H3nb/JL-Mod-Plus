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
the root-relative path. A checked path is only an observation of the current
runtime, not a stable identity of a game entity after restart. The existing
diagnostic location text remains available independently, but must not be parsed
to construct structural identity. An observation may become stale immediately
after it is read because the guest is concurrent; refreshing revalidates it.

## Persistent Saved locator contract

Saved definitions contain a name, stable record ID and versioned root-relative locator. Each ordered segment identifies the exact declaring class, field name, Java type/descriptor and optional qualified `Object[]` index; the terminal target is a supported primitive field or a direct static primitive-array element. No field slot, runtime handle, object reference, search revision or last-seen value is persisted.

Capture requires a complete current verification, and Resolve checks the exact declaring class/type/staticness and every rooted reference edge in the guest process. For initialized static roots, resolving uses only the current runtime's already-initialized class registry; it never initializes a guest class. Bound runtime owners remain weak and pinned to the resolved path: a replaced reference causes a Changed failure rather than reselecting a different object. No automatic edit or Watch/Freeze creation occurs. A new launch resolves the structural path afresh, which cannot prove the same semantic character if party order has changed.

Saved storage is main-process-owned, under `configs/<storageKey>/saved-variables.json`, with a portable schema-2 AtomicFile document capped at 64 records/64 KiB. The document carries labels and typed locators, not installation paths, Library IDs, source-JAR hashes, or non-backup installation markers. Every authority access still validates the active Library app ID/workdir and guarded writes use `InstalledAppWriteGuard`. Source JAR changes do not reject definitions; runtime resolution validates each locator. Corrupt, unsupported-version or truncated files are errors and are not overwritten. The experimental `saved-variables-prototype.json` file is isolated and not migrated.

The definition can be listed without running guest code; Capture/Resolve/Read/checked Edit require a live runtime. Deleting one or several definitions performs a guarded atomic persistent mutation; corresponding guest bindings are released after success. Per-session bindings are invalidated on runtime exit. Saved checkboxes and selection belong to the Saved tab only and do not mutate Search or Watch selection.

A Saved Inspector snapshot reads bounded siblings of the pinned runtime owner without adding Search/Watch entries. Sibling capture reuses the same verified typed-path construction as ordinary Search capture. Saved-origin Inspector edits use a separate checked operation that validates pinned reference ancestry, target field type, editability, runtime epoch, and the snapshot's expected primitive value immediately before writing, rather than routing through a Search/Watch identity. The shared keypad editor is used across Search, Saved, and Inspector. Inspector's optional Freeze follows a confirmed checked edit, and creates or updates a Watch entry for the exact sibling target. Its per-row overflow actions can also add the sibling to Watch without changing its current value, or save it as a separate persistent definition. User-initiated Freeze from Saved is initiated by the checkbox in the Saved edit dialog, creates or updates a Watch identity with the verified ancestry, and every tick revalidates that ancestry before writing. It pauses if the reference chain changes even when an old object is still alive. The same checked ancestry is required before any later manual Watch edit or mutation of that Saved-origin Watch identity. The bundle exporter must handle Saved Variables independently from configuration snapshots.

Paths through unsupported containers, arbitrary untraced arrays, multidimensional arrays or a fabricated root are rejected. The saved resolver does not add a graph-recovery pass, raw-address scanner, VM hooks or automatic freeze/write behavior.

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
