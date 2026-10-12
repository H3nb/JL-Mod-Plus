# MIDP 3.0 localized MIDlet descriptions — implementation plan

## Contract
- Recognize `MIDlet-Description-<locale>` in installed JAD/manifest descriptors without requiring MIDP 3.0 runtime compatibility or changing the declared profile.
- Resolve the localized description against the **effective guest** `microedition.locale`, including per-MIDlet `systemProperties` overrides. Do not use a separate Library/UI-language decision.
- Match exact locale, then progressively less-specific locale (variant, region, language), then fall back to the default description.
- A user-edited Library `custom_description` replaces only the unqualified `MIDlet-Description` fallback. It never overrides a matching localized attribute.
- Do not add translation editors, new language settings, or rewrite source JARs/JADs.

## Integration and upgrade
- Extract and persist localized source metadata during install, workdir scanning, and recovery, while preserving the original unlocalized `source_description`.
- Keep the installed descriptor authoritative for reconstructible localized metadata; migrate schema non-destructively and backfill existing installs without reconversion.
- Project effective descriptions for Library display/search consistently, on the existing background work boundary; avoid descriptor or configuration I/O from Compose rendering.
- Preserve explicit metadata edits; editing a title must not accidentally promote the current localized description into a persistent custom override.
- Keep installed metadata and bundle import/recovery pathways consistent.

## Validation
- Unit tests: exact/partial locale matching, invalid localized keys, default/custom fallback, case/alias normalization, and system-property overrides.
- Library tests: upgrade/reconciliation preserves Favorites, Collections and custom metadata; existing installed apps backfill.
- CI: relevant JVM tests, Room schema/migration verification, and Android build/lint as available.

This PR is intentionally limited to localized descriptions.