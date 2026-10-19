# Preset, configuration, and installed-identity contracts

This document records durable semantic correctness boundaries for preset/configuration work. Current classes, process placement, IPC, locking, and transaction mechanisms are implementation details unless explicitly identified as part of a compatibility contract.

## Authority and identity

- Persistent preset ownership and linkage must have one authoritative serialization domain across processes. A runtime process must not independently race or invent persistent ownership state. The current implementation places this authority in the default/main process and exposes a narrow preset-authority IPC boundary to the isolated `:midlet` process.
- Library `appId` is the durable identity of an installed MIDlet. `storageKey` identifies its filesystem location and is not a substitute for installed identity.
- Installed-app writes must be fenced by the installed identity that opened or launched the operation and reject stale delete/reinstall reuse before publishing config, layout, or ownership changes. Workdir/root identity must not silently retarget while an editor or runtime is open.
- Named-preset source identity is distinct from installed-app identity. A stale source editor must not become valid merely because the same name or filesystem path is later reused.

## Preset ownership

- LINKED ownership is explicit metadata. File/config equality may detect a no-op but must never create, restore, or promote linked ownership.
- A user-owned local change detaches linked ownership before its local publication becomes authoritative. Provenance may describe where state came from without implying a live link.
- Whole-preset activation may establish LINKED ownership only after the complete source snapshot is valid and the local snapshot has been durably materialized.
- Changes to the global named-preset default are prospective. Existing installed identities retain their Built-in, CUSTOM, or LINKED ownership; fresh installation binds the then-current eligible default or a Built-in fallback.
- System properties use one factory source plus per-profile overrides. Missing override keys inherit current factory values, explicit string values override them, and null overrides remove factory keys. Built-in, CUSTOM, and named presets use the same property inheritance rules without changing preset ownership.
- Historical full-system-property snapshots are interpreted against the frozen pre-overrides factory and converted to differences when the version-8 schema is durably published. Older serialized configurations never silently masquerade as an override-schema config. The effective text is transient; a manual reset edits the draft and ordinary save/ownership rules apply.

## Additive configuration fields

`AllowDisplayCutout` is a profile-owned boolean with default false. A missing
field in an older profile is equivalent to explicit false. Reading the field
must not force publication, bump the configuration version, detach LINKED
ownership or replace Built-in ownership with CUSTOM. Normal serialization writes
the field; a real user change follows the existing effective-change rules.

`CameraDefaultFacing` (rear 1, front 0) and `CameraResolution` (automatic 0,
portrait 1, landscape 2) are profile-owned values. Missing or invalid values use
rear/automatic without requiring publication or a configuration-version change.
They participate in form drafts, preset copies and effective-change comparison.
Runtime swaps never persist those temporary lens overrides to config or preset.

## Persistence and recovery

- Preset source and installed-snapshot publication must not expose a partially written state as committed. Recover interrupted publication before consuming, replacing, renaming, deleting, or synchronizing the affected snapshot.
- Publish durable content before ownership metadata that depends on it. When recovery or identity cannot be proven safe, prefer the conservative CUSTOM/fail-closed outcome over inventing ownership.
- Competing source mutations and recovery must be serialized so readers, rename/delete operations, and followers cannot observe a mismatched or partially committed snapshot. The current implementation provides this through the preset-source transaction/locking boundary and installer identity coordination; equivalent mechanisms may replace it if the invariant remains intact.
- Preserve the distinction between primary committed content and optional secondary state: failure of an optional follow-up must not masquerade as failure of content that was already durably committed.

## Runtime storage

- A live runtime must hold an exclusive lifetime reservation for its installed storage identity that is meaningful across process concurrency and distinguishes an active owner from stale filesystem residue. The current implementation uses an OS-backed file lock.
- A deleted or replaced installed identity must not let an old runtime gain authority over its replacement. Fresh allocation must avoid filesystem identities still reserved by active runtime state or surviving side data.
- Runtime private/cache paths must remain scoped to the launched workdir/storage identity whose lifetime is reserved.

When changing these areas, use current source and focused tests to determine implementation details. Change this contract only when intended behavior changes; do not promote transient PR sequencing or current class structure into a permanent requirement.
