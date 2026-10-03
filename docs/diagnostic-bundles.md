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

## Ownership

JL-Mod Plus tracks the exact URI and identity of the bundle it generated for a diagnostic record.
The logical filename comes from the stable incident identity, while an internal content fingerprint
tracks the evidence represented by that ZIP. Retry reuses the tracked bundle only when both still
match; newer correlated evidence regenerates the ZIP with the same logical filename.

Ownership is persisted before the bundle is written or published. Interrupted legacy writes can
therefore clean their deterministic partial file on retry, and failed cleanup keeps the ownership
mapping instead of guessing by filename. Deleting the diagnostic attempts to remove only that
tracked artifact. Files already uploaded elsewhere are outside JL-Mod Plus ownership.
