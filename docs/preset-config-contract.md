# Preset, configuration, and installed-identity contracts

This document records durable correctness boundaries for preset/configuration work. Class names and internal decomposition may change; preserve these invariants rather than the current implementation shape.

## Authority and identity

- The default/main process is the persistent authority for preset ownership and linked-preset metadata. The isolated `:midlet` process consumes prepared state and uses the narrow preset-authority IPC boundary for runtime preset/config persistence that requires main-process authority.
- Library `appId` is the durable identity of an installed MIDlet. `storageKey` identifies its filesystem location and is not a substitute for installed identity.
- Installed-app writes capture the expected positive `appId` and reject stale delete/reinstall reuse before publishing config, layout, ownership, or named-source changes. Workdir/root identity is part of the operation and must not silently retarget while an editor or runtime is open.
- Named-preset source editing is distinct from installed-app editing. A stale editor must not become valid merely because the same name or filesystem path is later reused.

## Preset ownership

- LINKED ownership is explicit metadata. File/config equality may detect a no-op but must never create, restore, or promote linked ownership.
- A user-owned local change detaches linked ownership before its local publication becomes authoritative. Provenance may describe where state came from without implying a live link.
- Whole-preset activation may establish LINKED ownership only after the complete source snapshot is valid and the local snapshot has been durably materialized.
- Global default changes are prospective. Existing installed identities keep their current Built-in, CUSTOM, or LINKED state; fresh installation binds the then-current eligible default or a Built-in fallback.

## Persistence and recovery

- Preset source and installed snapshot publication must not expose a partially written state as committed. Recover interrupted transactions before reading, replacing, renaming, or synchronizing the affected source or local snapshot.
- Publish durable content before ownership metadata that depends on it. When recovery or identity cannot be proven safe, prefer the existing conservative CUSTOM/fail-closed outcome over inventing ownership.
- Serialize source mutations through the existing preset-source transaction boundary. Installed-app mutations must also respect installer/identity coordination before publishing filesystem or ownership changes.
- Preserve the distinction between primary committed content and optional secondary state: failure of an optional follow-up must not masquerade as failure of content that was already durably committed.

## Runtime storage

- A live MIDlet runtime owns an OS-backed storage lease for its launched workdir/storage identity. A leftover lock file without an active OS lock is not a live lease.
- A deleted/replaced installed identity must not let an old runtime gain authority over the replacement. Fresh allocation must avoid filesystem identities that are still reserved by active runtime state or surviving side data.
- Runtime private/cache paths are derived from the launched workdir and storage identity so runtime writes stay inside the same namespace whose lifetime is protected by the lease.

When changing these areas, inspect the current authority, linkage, installer-identity, source-transaction, and runtime-lease tests for executable details. Update this contract only when the intended invariant changes, not for transient task sequencing or PR status.
