# Virtual filesystem and MIDlet permissions — implementation plan

Status: design and implementation checklist. This document does not claim that the proposed behavior has shipped.

## Scope and constraints

Deliver a shared virtual filesystem (VFS) for Java ME file APIs and a per-installed-MIDlet permission manager. Keep Android app permissions, runtime process topology, guest DEX conversion, ClassLoader behavior, camera implementation, and other unrelated features unchanged.

The purpose is to prevent normal file API operations from reaching arbitrary Android storage and to let users control Java ME permissions without relying on historical certificate trust. This is **not an OS-enforced sandbox** for deliberately hostile DEX code. Host Java/Android API bypasses are outside this PR; do not describe the VFS as preventing them.

Follow [Java ME compatibility](java-me-compatibility.md), [preset and installed identity contracts](preset-config-contract.md), [library schema guidance](library-data-contract.md), and [test strategy](development.md#testing-strategy). Preserve existing license notices in modified files.

## Current implementation evidence

- `org.microemu.cldc.file.FileSystemFileConnection` maps logical roots `c:/`, `e:/`, `0:/`, `1:/`, `a:/`, `b:/`, and `fs/MyStuff/` to actual Android storage volumes. Unknown roots fall back to the first physical volume. The connection constructor does not preserve `Connector` mode.
- `org.microemu.cldc.file.Connection.openConnection` receives the mode but does not pass it to `FileSystemFileConnection`.
- `com.siemens.mp.io.File` resolves relative paths under installed data but colon-prefixed paths against Android `user.home` and operates on `java.io.File` directly.
- `MicroLoader.setProperties` publishes `fileconn.dir.*` URLs derived from physical Android paths, including `fileconn.dir.private` and `fileconn.dir.music`.
- `MIDlet.checkPermission` currently returns `1` unconditionally.
- Per [installed identity contract](preset-config-contract.md), Library `appId` identifies an installation; `storageKey` identifies its location, and a live runtime holds a storage reservation. Permissions must not be inherited from a reusable UI preset.

Treat these as migration inputs, not compatibility requirements.

## Filesystem contract

### Mounts and ownership

- Introduce workdir-backed shared mounts `storage/c/` and `storage/e/`, independent of actual Android storage volumes. Both exist without removable SD storage.
- Keep private/cache data owned by the installed MIDlet, under its existing workdir and `storageKey` identity. RMS remains outside guest-visible shared roots. No MIDlet may enumerate or open another MIDlet's private area through VFS.
- Keep vendor-specific storage behind the same VFS service. Introduce `storage/vendor/...` only when a verified vendor API needs an independent backing store. Do not duplicate C/E contents for aliases.
- Use a defined mount table and optional compatibility-profile aliases. Logical roots are not physical paths. Only configured, supported roots may open; never silently redirect unknown roots to C.
- `FileSystemRegistry.listRoots()` publishes precisely the mounted *enumerable* roots for the selected profile, not all accepted aliases or Android volumes. Document default and profile-specific root mappings with tests.
- Resolve all `fileconn.dir.*` properties to **usable virtual FileConnection URLs**. `fileconn.dir.private` must resolve exclusively to the current installed identity. Choose its URI presentation from compatibility evidence and test it through `Connector.open`; do not leak a host filesystem path in a Java ME system property.
- Preserve meaningful filesystem behavior for available/total/used size, create, mkdir, list, hidden, rename, truncate, streams, and metadata without inventing quotas unless verified game behavior requires them.

### Path handling and access operations

- One resolver owns logical-root matching, URL parsing and percent-decoding, separators, normalization, mount-boundary checking, and host path resolution.
- Enforce containment on create/open/delete/rename/list/truncate and any path-changing operation, including existing symlinks and destination parents. Where platform APIs cannot atomically prevent a race, document and test the residual risk rather than claiming a hardened sandbox.
- Reject unknown roots, root-escaping traversal, invalid URI components, and attempts to cross into workdir internals. Preserve JSR-75 `setFileConnection("..")` semantics *within* a root and prohibit ascent above the root.
- Respect `Connector.READ`, `WRITE`, `READ_WRITE` on every relevant operation; mode checks and per-MIDlet grants are independent. Preserve JSR-75 URL/path/name, stream, lifecycle, and exception contracts, including correct refusal of illegal operations.
- Do not globally case-fold filenames. Root-name interpretation and case-sensitivity compatibility should be grounded in device or game evidence.
- Keep filesystem operations on ordinary files; no new database, virtual disk image, or filesystem daemon.

### Vendor adapters

- Route `com.siemens.mp.io.File` and its relative/absolute paths through the same VFS/permission decisions. Relative vendor paths remain installation-scoped. Resolve `a:/` or other device paths from documented compatibility maps, not from Android `user.home`.
- Inventory other existing vendor filesystem entry points before changing behavior; add adapters only for actual API usages. Preserve vendor-specific return values, descriptor behavior, error conventions, and path forms.
- Avoid duplicating VFS resolution or permission decisions inside each vendor API.

## Permission contract

### Authority and policy

- One permission authority uses durable Library `appId` to bind a grant to a specific installation. `storageKey` scopes backing data only. A copied/linked/shared preset cannot carry installed permission grants to another MIDlet.
- Persistent decisions: `ASK`, `ALLOW`, `DENY`. Temporary decisions: once and current runtime session; clearing/expiry must be well-defined. Defaults: private installation-scoped operations allowed; shared VFS READ and WRITE separately `ASK`.
- The first executable integration is JSR-75 and vendor file operations. Other Java ME capabilities can be enumerated in a centralized registry for later integration **without presenting them as implemented**.
- Recognize `MIDlet-Permissions` and `MIDlet-Permissions-Opt` as declarations, not as proof of trust and not as user approval. No certificate validity check controls the runtime grants.
- Define behavior when the retained JAR contents change: retain explicit denials and installation ownership; do not silently transfer sensitive permanent grants to changed executable content. Avoid resetting permissions merely because a display name or storage location changes.
- `MIDlet.checkPermission(name)` queries effective policy without displaying UI and uses MIDP-compatible return values: `1` granted, `0` denied or unknown permission, `-1` undecided/interaction required. API entry points must enforce permission independently of `checkPermission`.
- The filesystem restriction is not merely a permission: even if file READ or WRITE is granted, the API never accepts arbitrary Android paths.

### Runtime and UI

- The permission manager serializes simultaneous prompts for the same installation/capability and avoids flooding users with repeated file operations.
- Never block the Android main thread waiting for user input. Define cancellation on runtime exit, Activity destruction, workdir switch, deletion/reinstall, and stale installation identity.
- Revoke a grant consistently: new operations must observe the new decision; document and test existing-open-stream behavior rather than leaving stale silent authority.
- Permissions appear under the individual MIDlet configuration, **not in shared or linked presets**. Use human-readable English/Indonesian strings. Explain access to *virtual shared storage* rather than all Android files. Do not imply OS-level isolation.
- Do not request Android permissions as part of Java ME permission dialogs. Host Android permission handling remains unchanged for this PR.

## Existing-data migration

- No bulk copy of Android primary storage, no deletion or in-place relocation of old data based solely on legacy URLs, and no eager creation of every possible vendor root.
- Preserve RMS, save data, app configuration, installed identity, and the current workdir. Treat old `fileconn.dir.private` references, including persisted absolute URLs in RMS, as a compatibility investigation with focused fixtures.
- Introduce a bounded, opt-in import mechanism for files users need in C/E if justified by tests. Never use a fallback that restores unrestricted physical-root access.
- On failure, retain the original file and a recoverable state; do not silently report a successful migration.

## Delivery order and acceptance gates

1. **Characterize current behavior**: collect JSR-75 and Siemens fixtures; tests for root reporting, mode, path, URI properties, private data, and failures before changing implementation.
2. **Implement mount resolution and VFS**: shared C/E, private ownership, alias handling, path containment, and vendor adapters; keep API wrappers thin.
3. **Implement permission authority and UI**: installation-scoped persistent policy; once/session grants; single-flight prompts; `checkPermission`; English/Indonesian text; lifecycle cancellation.
4. **Migrate safely and validate**: unchanged RMS and user data, deterministic compatibility tests, Android API 23+ behavior where relevant, and representative MIDlets on device.

Blocking acceptance checks:

- C/E exist as separate shared virtual directories even on Android devices without a removable card.
- A MIDlet reading/writing `file:///c:/` or `file:///e:/` through supported Java ME APIs can never reach `/storage/emulated/0` outside its configured VFS backing directories.
- A MIDlet cannot open another installation's private data through VFS, including through alias, traversal, unknown root, symlink, or vendor API.
- Connector access mode and file READ/WRITE permission are both enforced, with correct Java ME error behavior.
- Root listings and `fileconn.dir.*` URLs agree with the active mount configuration.
- An `ASK` permission never becomes an implicit `ALLOW` because a MIDlet calls an API without first calling `checkPermission`.
- Permission decisions never propagate through presets or a reused storage path to a different installation.
- Denied/aborted prompts do not deadlock or accidentally authorize subsequent operations.
- Existing user files and RMS are preserved. Any explicitly imported file has a checked destination and non-destructive failure behavior.
- Documentation and user-facing wording make clear that this is a Java ME API boundary, **not** proof of sandboxing for hostile DEX code.

## Explicit exclusions

No isolated process migration, DEX/ASM/ClassLoader hardening, Android permission removal or restructuring, new camera/Bluetooth/location functionality, unrelated UI refactoring, automatic physical-storage migration, or merge until functional implementation and relevant validation are complete.
