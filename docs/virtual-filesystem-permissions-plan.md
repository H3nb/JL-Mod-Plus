# Virtual filesystem and MIDlet permissions — implementation plan

Status: Draft implementation. Not merged; on-device smoke testing was reported successful; automated connected-device qualification remains optional.

## Scope and constraints

Deliver a shared virtual filesystem (VFS) for Java ME file APIs and a per-installed-MIDlet permission manager. Keep Android app permissions, runtime process topology, guest DEX conversion, ClassLoader behavior, camera implementation, and other unrelated features unchanged.

The default Shared mode prevents normal file API operations from reaching arbitrary Android storage and to let users control Java ME permissions without relying on historical certificate trust. This is **not an OS-enforced sandbox** for deliberately hostile DEX code. Host Java/Android API bypasses are outside this PR; do not describe the VFS as preventing them.

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
- Keep vendor-specific storage behind the same VFS service. Keep Siemens relative files and its A/B drive mounts in the installation-private `data/<storageKey>/vendor/siemens/` namespace. Only C/E and their aliases are shared. Do not duplicate C/E contents for aliases.
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
- This PR controls only JSR-75 and vendor file storage operations. Other MIDlet permissions retain alpha's existing behavior; no non-storage permission registry or policy is introduced.
- Recognize `MIDlet-Permissions` and `MIDlet-Permissions-Opt` as declarations, not as proof of trust and not as user approval. No certificate validity check controls the runtime grants.
- Define behavior when the retained JAR contents change: retain explicit denials and installation ownership; do not silently transfer sensitive permanent grants to changed executable content. Avoid resetting permissions merely because a display name or storage location changes.
- For the exact file READ/WRITE permission names, `MIDlet.checkPermission(name)` returns `1` granted, `0` denied or no active session, and `-1` undecided, without displaying UI. All other inputs retain alpha's unconditional `1`, including unknown names and null; their permission behavior is outside this PR. Storage API entry points enforce their policy independently of `checkPermission`.
- In Shared mode, granting file READ or WRITE never expands the workdir-backed mounts. Android mode is an explicit per-installation compatibility opt-in; its grants are scoped separately.

### Runtime and UI

- The permission manager serializes simultaneous prompts for the same installation/capability and avoids flooding users with repeated file operations.
- Never block the Android main thread waiting for user input. Define cancellation on runtime exit, Activity destruction, workdir switch, deletion/reinstall, and stale installation identity.
- Revoke a grant consistently: new operations must observe the new decision; document and test existing-open-stream behavior rather than leaving stale silent authority.
- Permissions appear under the individual MIDlet configuration, **not in shared or linked presets**. Use human-readable English/Indonesian strings. Use short read/write descriptions and show the selected storage backend in System settings. Do not imply OS-level isolation.
- Do not request Android permissions as part of Java ME permission dialogs. Host Android permission handling remains unchanged for this PR.

## Existing-data migration

- No bulk copy of Android primary storage, no deletion or in-place relocation of old data based solely on legacy URLs, and no eager creation of every possible vendor root.
- Preserve RMS, save data, app configuration, installed identity, and the current workdir. Treat old `fileconn.dir.private` references, including persisted absolute URLs in RMS, as a compatibility investigation with focused fixtures.
- Introduce a bounded, opt-in import mechanism for files users need in C/E if justified by tests. Never silently fall back to physical-root access; only an explicit Android mode selection enables physical volumes.
- On failure, retain the original file and a recoverable state; do not silently report a successful migration.

## Delivery order and acceptance gates

1. **Characterize current behavior**: collect JSR-75 and Siemens fixtures; tests for root reporting, mode, path, URI properties, private data, and failures before changing implementation.
2. **Implement mount resolution and VFS**: shared C/E, private ownership, alias handling, path containment, and vendor adapters; keep API wrappers thin.
3. **Implement permission authority and UI**: installation-scoped persistent policy; once/session grants; single-flight prompts; `checkPermission`; English/Indonesian text; lifecycle cancellation.
4. **Migrate safely and validate**: unchanged RMS and user data, deterministic compatibility tests, Android API 23+ behavior where relevant, and representative MIDlets on device.

Blocking acceptance checks:

- In default Shared mode, C/E exist as separate directories even without a removable card.
- In Shared mode, C/E never reach physical storage outside their configured backing directories. Android mode requires explicit selection and separately scoped grants.
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

## Review corrections — 18 October 2026

The default Shared-mode boundary is file access inside the working directory. C/E are
shared storage for intentional file exchange; RMS, private/cache files and
Siemens save storage remain installation-scoped. This does not change Android
host permissions or claim protection against arbitrary guest DEX/host API calls.

- Resolved integration conflicts with alpha `d7b82c6b`; preserved its guest locale
  resolution, diagnostics provider roots and runtime toolbar changes.
- Runtime authorization uses the existing Compose Material 3 host and modal input
  owner. Message and all grant choices appear together; cancellation/teardown
  completes the waiting request. A queued request rechecks DENY under the prompt
  lock, including a denial whose persistent write failed.
- System configuration exposes shared READ and WRITE separately. Decisions are
  saved off the UI thread outside profile serialization; changing a permission
  does not edit/detach a reusable preset. Changes affect the next MIDlet launch.
- FileConnection close leaves previously opened streams usable. WRITE-mode
  directory traversal is supported, including directory names returned by list
  and consistent URLs while ascending within a mount.
- Exact legacy URLs for this installation's private/cache folders resolve to
  those same private folders. They never expose the mixed data directory,
  another installation, or the old Android storage root.
- Configuration offers explicit import when legacy Siemens candidates exist.
  Only the selected file is copied to this installation's private Siemens area;
  originals and existing destinations remain untouched. RMS `.rsh`/`.rsr`,
  private/cache/vendor namespaces, traversal and symbolic links are excluded.
  Imports hold the installed identity guard and an exclusive runtime storage
  lease, so a running MIDlet must stop first. Inspection is bounded to 1024
  entries/16 directory levels; each copied file is bounded to 64 MiB. A failed
  import leaves the source intact and does not fall back to Android storage.
- In Shared mode, old public-storage URLs are not restored. Users may place files
  they want to share in `<workdir>/storage/c/` or `<workdir>/storage/e/` with their
  file manager. No bulk copy or automatic gallery/music scan is performed.

Validation: the VFS and legacy-import JVM suites pass locally (10 tests). A
separate JVM harness using Android stubs also executes the production Java
permission queue and FileConnection compatibility tests (13 tests total).
This is not Android/UI/device qualification. Instrumentation additionally covers
prompt choice reachability and permission changes without profile draft edits;
those require a connected Android runtime. CI build/lint evidence must refer to
the new PR revision before merge.

The storage permission UI is translated across all 39 shipped string catalogs,
including English and Indonesian. The reviewed feature keys no longer use
resource-local MissingTranslation annotations; unrelated translations are untouched.

## Storage-mode and copy revisions (18 October 2026)

- The first System section is **Storage access permissions** / **Izin akses penyimpanan**.
  **Virtual** (internally `SHARED`) is the default and retains workdir-backed C/E.
  **Android** maps C to
  primary external storage and E to a second mounted volume, or the primary when only one exists.
  A single physical volume advertises only C; aliases remain usable. Missing volumes fail closed
  and are never created by the resolver. Android access remains subject to existing host permissions.
- Mode, read and write decisions belong to an installed app identity, outside reusable presets.
  Existing shared grants keep their scope; Android grants start at ASK and never inherit them.
  The launch snapshots the selected mode and its grants. Runtime grant writes name that snapshot
  even if settings have changed meanwhile. Settings take effect on the next launch.
- Private/cache, RMS and Siemens A/B data keep the same installation-owned locations in both modes.
  No mode switch copies or relocates files. Android physical mounts exclude the current workdir's
  data, converted apps, configs, templates, host cache and catalog database files/sidecars.
  Canonical and lexical checks also reject symlink routes to those paths; mutations of their
  ancestors are blocked, and directory listings omit protected children. Exact legacy aliases
  for the current app's own private/cache remain valid. These checks do not claim an OS sandbox,
  atomic protection against host-side filesystem races, or discovery of unrelated historical workdirs.
- Android media properties use physical public directories; Shared keeps its existing virtual
  media directories. Neither mode changes private/cache URLs. Unknown roots still fail closed.
- Runtime prompts identify the MIDlet and its requested file access. Option rows have no extra
  inter-row spacing and keep 48 dp touch targets. All shipped catalogs have translated copy.

## Platform request cache lifecycle (18 October 2026)

File-based `MIDlet.platformRequest()` copies its selected file to the app's
`midlet-platform-share/` cache for the narrow `FileProvider` allowlist; it does
not expose the underlying MIDlet storage as a provider root. On each subsequent
file share, stale `midlet-` copies older than 24 hours are deleted best-effort.
New copies remain available after the handoff so receiving Android applications
can finish reading them; failed copies and URI publication failures remove their
partial copies immediately. The cache is Android-managed and can be reclaimed
under storage pressure; no hard byte limit is imposed on recently shared files
because it could break active consumers. The final copy may remain until another
file share or Android's normal cache reclamation. The cleanup routine has focused
JVM regression tests; it does not require a recurring background service.

The repository owner reported successful real-device smoke testing of MIDlet
storage and permission behavior before this follow-up. CI compiles, but does
not execute, the Android instrumentation suite on a connected device.
