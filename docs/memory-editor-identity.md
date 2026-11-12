# Managed Memory Editor candidate identity

This contract separates the durable Bookmark catalog from exact session candidate
identity. The internal Saved and Watch APIs remain compatibility/runtime mechanisms,
not two user-facing List entry types.

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
snapshot. Missing or changed Inspector targets return to Search or List, never to an old snapshot. A new MIDlet token starts at Search. No tab
preference, Inspector value, or guest object is written to persistent storage.

## Bookmark ownership and session linkage

A Bookmark has one persistent main-process-owned ID, label and optional typed
root-relative locator. A verified locator can be resolved during a later MIDlet
session. An untraced candidate can still be bookmarked, observed and frozen in the
current session, but requires manual relink after restart; it is never rebound by
matching values, names or diagnostic locations.

The target's bounded Watch records remain an internal way to track a live candidate.
Their `savedSourceId` records the explicit Bookmark association after the authority
has durably stored the record and the engine verifies the current owner/slot.
No Freeze action should start before that step. An unavailable target cannot be
edited but its persistent Bookmark remains listed and removable. A temporary
legacy orphan active Freeze may remain visible solely so it can be stopped.

Relink first detaches the old session target (and any Freeze), then updates the
persistent locator and attaches the selected new candidate. Failure after detach
can leave the original Bookmark present and unfrozen; this is preferable to
silently writing through an obsolete reference. Passive re-resolution never
starts Freeze or restores a previous value.

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

## Persistent Bookmark locator contract

A rooted locator is a bounded ordered sequence of exact declaring-class field
names/types and array-type/index steps, with a supported primitive terminal.
Capture checks ancestry from the current MIDlet, Displayable or initialized
static class. Resolving never initializes guest classes or invents container
selectors. Reflection enumeration slots, runtime owner IDs, object references,
search revisions and last-seen values are never serialized.

The existing `configs/<storageKey>/saved-variables.json` name remains for
backward-compatible bundle transfers. Its schema-4 AtomicFile document permits
explicit null locators (needs relink). Schemas 2 and 3 remain readable and are
converted to canonical typed steps on an authorized mutation. The maximum is
64 records and 64 KiB; corruption and unsupported versions are not overwritten.
Every authority mutation checks installed identity through
`InstalledAppWriteGuard`. The R&D prototype format is not migrated.

A bookmarked target can be listed and renamed/removed when unresolved.
Foreground polling retries rooted locators and reads active values without
reloading the catalog on each frame. An open edit pins the original checked
owner; replacement during that edit rejects the write. Rebinding by a structurally
equal path never writes memory or re-enables a prior Freeze. The same array slot
may represent a different semantic game entity after a scene change.

The selective Library bundle's existing Saved component is presented as
**Bookmarks** but retains its wire identity. Bookmark-only import merges by
canonical rooted locator; local labels win and duplicate paths are skipped.
Untraced entries merge by persistent ID, not null locator or label. An imported
untraced record with an existing ID is skipped; a traced record with a different
locator receives a fresh ID when required. Configuration+Bookmark restore
replaces the catalog within the existing recoverable transaction. Imports do
not persist guest runtime handles or Freeze states.

No automatic state restore, guest VM hook, extra idle traversal or persistent
Freeze is introduced.

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
