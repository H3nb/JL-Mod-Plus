# Diagnostic bundles

JL-Mod Plus diagnostic records remain the source of truth. A public diagnostic bundle is a
derived, regenerable export created only when the user chooses **Report on GitHub**.

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
