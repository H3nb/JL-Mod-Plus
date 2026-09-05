# Installer and retained-source contracts

Installation, exact-target reinstall, automatic compatibility reconversion, deletion, and
bundle restore share the process-wide installer permit for filesystem mutations. Generation
leases cover synchronous filesystem operations only; they must not span coroutine suspension.
Cancellation of a Library owner must still deliver the install completion callback exactly
once so that the permit can be released.

## Work directories and publishing

- A fresh Library may have no `converted` directory. The first installer creates it after
  confirming that the active catalog is empty.
- A missing or unreadable `converted` directory for a nonempty catalog is a storage failure,
  not evidence that every game was deleted. Reconciliation preserves the catalog and user state.
- Each request owns its source scratch directory. Conversion staging is shared only while
  holding the permit, and the owner relinquishes its staging reference after publish.
- Reviewed local JAR/JAD sources are copied before confirmation. Revalidate the catalog
  decision before conversion, and recheck the active workdir before publishing.
- Replacement uses the existing backup/recovery journal. A database failure after publish
  retains recovery evidence and asks the user to refresh the Library.

## Original JAR and JAD

`res.jar` remains the retained JAR. When installation uses a JAD, its original bytes are
retained alongside it as optional `source.jad`, including JADs extracted from KJX or downloaded.
The merged `converted.dex.conf` remains the installed descriptor.

Exact-target reinstall and automatic reconversion preserve the optional JAD. They merge the
installed descriptor over the retained JAR manifest, preserving existing descriptor precedence
and vendor properties. If the installed descriptor is missing, `source.jad` supplies those
properties; if both are absent, the JAR manifest remains usable. An existing malformed descriptor
fails rather than silently losing settings. Existing games do not require a synthetic JAD.

A new JAR-only installation/update, including an explicitly selected JAR-only mismatch fallback,
does not carry forward the old JAD. Config, saves, catalog identity and custom metadata remain
associated with the existing storage key when replacing an identified installed app.

This preserves existing Java ME property behavior; it does not introduce a new signed-suite
trust policy. The MIDP `MIDlet.getAppProperty` documentation was checked against the local
J2ME documentation supplied by the maintainer.

Existing app-bundle formats retain their merged descriptor contract. They do not export the
optional raw `source.jad`; adding it to strict bundle namespaces requires a separately versioned
format change. Importing an old bundle remains supported.

## Downloads and presentation

Downloads resolve JAR references against the final JAD URL, follow bounded HTTP redirects,
reject loops and incomplete responses, and publish request scratch only after completion.
Cancellation is checked between reads and before publish, with bounded network timeouts.
User-copyable diagnostics mask complete HTTP URLs.

Single and bulk installation both require an explicit separate-copy choice when source identity
matches multiple installed games. Bulk results distinguish failures, unfinished items and games
whose conversion succeeded but bundle restore failed. Retrying a partial bundle result restores
only its remaining payload after checking the installed ID and storage key.

Popups use the existing adaptive margins and maximum width. Height wraps content up to the
adaptive maximum; overflowing content scrolls with the shared scroll hint. Short-window fallback
keeps actions reachable at large font sizes. Cancellation is cooperative; batch cancellation
finishes the current item, and published files are not undone by closing a dialog.

Shared dialogs measure their title and actions before allocating body space; they no longer
subtract guessed fixed header/footer heights. An absent action footer consumes no space.
Installer, configuration and Library popups share the Material type scale and themed surfaces.
Descriptions use start alignment, while compact progress statuses may be centered. See
[UI copy and interaction style](ui-copy-style.md) for capitalization, spacing and overflow rules.

## Focused verification

- Unit tests: missing-workdir catalog preservation, staging parent creation, cancellation
  completion/permit release, retained descriptor fallback, HTTP resolution/redirects/truncation.
- `InstallerFilesystemTest`: fresh JAD install after external source mutation, exact-target
  reinstall, reconversion without the merged descriptor, JAR-only update, save/config preservation.
- `InstallerComposeTest`: cancellation, retry/close reachability, adaptive content height and
  visible scroll hints in a short window at 200% font scale.
- Installer and bulk-result preview screenshot tests cover normal and short/large-text layouts.
- `AdaptiveDialogComposeTest`: content-wrapped action menus, measured body overflow, visible
  hints, and reaching the last font-size field and confirmation at 200% text.
- Shared popup previews cover short/portrait/expanded windows and light/dark non-default accents.

No Room schema, transformer version, dependency, or toolchain migration is needed for these fixes.
