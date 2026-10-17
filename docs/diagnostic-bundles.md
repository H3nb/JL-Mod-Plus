# Diagnostic bundles

JL-Mod Plus diagnostic records remain the source of truth. A public diagnostic bundle is a
derived, regenerable export created only when the user chooses **Report on GitHub**.

## Incident admission

The diagnostic inbox distinguishes fatal evidence from selected actionable Android process exits.
Fatal evidence includes terminal Java/MIDlet failures, Android-reported Java or native crashes,
ANRs, and crash-class signals (SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, or SIGSEGV). Selected
Android-classified initialization failures, excessive-resource terminations, and memory-limiter
terminations are retained as non-crash `PROCESS_EXIT` diagnostics. Low-memory kills are retained
only when Android records foreground, foreground-service, visible, or perceptible importance.
Foreground-service importance can apply while the MIDlet is minimized. These selected nonfatal
exits remain available for explicit inspection in Diagnostic Reports, but never trigger an automatic
recovery notice. Only fatal evidence triggers that notice; skipping newer nonfatal records does not
hide an older unacknowledged fatal incident.

Low-memory kills at every other recorded importance, including service, cached, and gone, are not
retained. Unknown disappearance, ordinary SIGKILL or SIGTERM, generic `REASON_OTHER`, task removal, and
intentional user/package/permission exits do not create standalone incidents. The documented
memory-limiter marker under `REASON_OTHER` is the narrow exception. An exact controlled MIDlet
SIGKILL may corroborate an already-proven `UNEXPECTED_FAILURE` session, but cannot establish a crash
by itself. Caught installer/conversion errors are not crash reports. This admission policy also
applies when reading stored records; previously exported bundles remain user-owned and are not
automatically removed. Android 6-10 has no reliable OS exit reason, so orphan session journals remain
useful for play-stat reconciliation without being promoted into guessed crash reports.

## Public location and identity

Bundles are written to:

`Downloads/JL-Mod Plus Diagnostics/`

The configured emulator working directory does not affect this location. A bundle name is derived
from the incident timestamp and stable diagnostic fingerprint:

`yyyyMMdd-HHmmss-SSS-<fingerprint>.zip`

The timestamp is formatted in UTC so retrying the same retained incident does not change the
logical filename when the device time zone changes.

## Format version 2

Bundles are ordinary, unencrypted ZIP files containing:

- `report.md` — canonical-English human-readable evidence and the useful Java stack trace.
- `incident.json` — stable machine-readable incident facts with `formatVersion: 2`.
- `evidence/anr-trace.txt` — only when a retained text ANR trace is available.
- `evidence/tombstone-summary.txt` — only when a retained native tombstone can be represented by
  the bounded structured tombstone parser.

The opaque protobuf tombstone remains local evidence and is not copied into the standard public
bundle.

The inbox also supports **Share selected reports**: selected incidents are prepared as the same
sanitized ZIP bundles on a background thread, then offered together through Android's share sheet.
Only the selected ZIP URIs receive temporary read grants. The inbox selection is locked during
preparation so the visible selection cannot diverge from the ZIPs being prepared. If any bundle
fails preparation, the share sheet does not open with an incomplete selection. ZIPs successfully prepared before a
failure remain user-owned exports and can be reused or deleted with their source report.
Individual report copying remains available in the detail screen; batch clipboard copying is
intentionally not offered.

## Privacy contract

Public export redacts the actual app-private data root, including credential- and
device-protected Android app storage, the configured emulator-storage root, user-controlled
private storage paths, and non-public URI values. HTTP(S) values retain only scheme, host, and
port; arbitrary path, credentials, query, and fragment data are not exported. Useful technical
evidence is intentionally preserved, including Java frames, build and correlation metadata, and
Android system/module paths under `/system`, `/apex`, `/vendor`, and `/product`.

Sanitization targets known high-risk structures; it cannot prove that arbitrary exception messages
or guest-generated text contain no personal or secret values. Raw evidence stays local, bundles
are created only by explicit **Report on GitHub** action, and JL-Mod Plus never uploads diagnostics
automatically.

The bundle is not encrypted. Privacy is enforced by selecting and sanitizing the exported
evidence, rather than by embedding a client-side decryption secret.

## Interpreting failures

The failure location (such as `Graphics.drawImage()`) is recorded separately from
the execution context (process, callback, and verified MIDlet session). A top frame in
an emulator API does **not** prove that the emulator caused the exception: the API
may have been called by the MIDlet. A direct fatal handler outside a known
guest callback can identify host execution without proving the root cause.
When there is insufficient evidence, cause attribution remains unknown. Java and OS exit evidence enrich a report without
changing the original raw records. A manually saved log is not evidence of a fatal
incident and does not trigger a recovery notice.

## Ownership and regeneration

JL-Mod Plus tracks the exact URI/path, verified physical filename and content
fingerprint of an exported ZIP. Existing ownership records without the newer
logical-name field remain readable. When Java and Android process-exit evidence
become one record, original source-ID aliases keep earlier owned exports
reachable without a separate identity registry. Logical incident filenames are derived from
timestamp and interpretation fingerprint; **grouping fingerprints may evolve** when
more evidence arrives and must not be confused with durable source event IDs.

If an export must change, JL-Mod Plus writes a candidate under separately persisted
ownership while the prior active ZIP remains available. The candidate is written,
published and verified before ownership changes; previous ownership is recorded
for retirement. Failed candidate or retirement cleanup remains discoverable for a
later retry or maintenance pass. On older Android filesystems, the replacement may
need a distinct physical revision filename to preserve the previous ZIP. MediaStore
may also assign a different physical display name: the file reported to the user
comes from its verified metadata, not merely the desired logical name. Only exact
owned URIs/paths can be deleted, never files that merely look similar by name.
Files already uploaded elsewhere are outside JL-Mod Plus ownership.

Deleting a diagnostic cleans exact owned public exports before removing source
evidence. If cleanup fails, the source remains visible for retry. Export and
deletion are serialized in-process so another ZIP cannot be published between
cleanup and source deletion.

## Save log

The runtime menu and the Diagnostic reports screen both offer **Save log**, even if
the report inbox is empty or the MIDlet process has already ended. It is an explicit
snapshot of Android logcat buffers accessible to this app, **not** a guarantee of
capturing the moment of a previous crash. The collector requests recent records
from the `main` and `crash` buffers; it retains up to 500 parsed records
(including multiline continuations) within a 256 KiB UTF-8 log payload, subject
to an approximately three-second subprocess deadline. Android versions and ROMs
can restrict the accessible history.

The output is a unique `.txt` file in `Downloads/JL-Mod Plus Diagnostics/`.
Status and truncation are recorded; no file is claimed as successful when the
capture is empty or fails. The same path/URI sanitizer as ZIP exports is used.
Sanitization cannot guarantee that arbitrary logged messages contain no secrets.
Review the exported text before sharing. There is no automatic upload.
