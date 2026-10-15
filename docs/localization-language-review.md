# Localization language-review status

**Current scope (15 October 2026, PR #169): 38 Android locale catalogs.** This record replaces the former 39-locale review tracker at commit `6e3e79c`. The prior detailed analysis remains accessible in Git history; its Santali work queues are historical and no longer active merge blockers.

## Scope decision

- Santali (`sat`, Ol Chiki) was removed from the supported app locales at the owner's request because its high-consequence translations lacked sufficiently reliable linguistic validation. `app/src/main/res/values-sat/strings.xml` was deleted.
- Android's generated locale list comes from shipped resources, so removing the only `values-sat` catalog removes Santali from the offered app-language list. No Java ME guest-locale rules or MIDlet content were changed.
- The previous 76 existing-case and 85 recently added Santali quality queues, along with its machine-assisted translation review, apply only to the retired catalog. Their existence does not certify or discredit the 38 retained languages.

## Current inventory

- English contains **931 translatable single-string** and **34 plural** resources: **965** translatable identities.
- Each of the **38 retained locale catalogs** has the same 965 identities. All locale names, quantity forms, format arguments, and newline escapes must continue to follow the [localization contract](localization.md).
- No additional localization runtime, locale metadata registry, or custom plural engine has been added. The Android XML resources remain authoritative.

## Memory Editor: remove and revert

The `memory_editor_remove_candidates_description` message has been rewritten in canonical English and all 38 retained catalogs. It uses the locale's existing `memory_editor_remove` and `memory_editor_revert_and_remove` button labels, with two short action-specific paragraphs:

- **Remove** removes the selected results from the current search results. It does **not** change game-memory values.
- **Revert & remove** attempts to restore values from before the last confirmed explicit edit. Restoration is conditional on current-value matching, valid type/state, and no conflicting live freeze; results that cannot safely be restored remain in the result list. Unedited candidates can be removed without memory writes.

This wording reflects `ManagedJavaMemoryEngine.revertAndRemove` and its readback safeguards. It must never suggest that removing results deletes game data, or that a conflicted/failed restore succeeded. Localized action names must stay consistent with the actual dialog buttons.

## Review and release gates

- Structural checks and targeted source-meaning review are necessary, **not** proof of native-speaker fluency across every retained language.
- Check Android XML compilation, formatting, dialog layout on narrow screens and larger text, RTL locales, and representative Indic/CJK strings. The two-paragraph explanation is longer than its predecessor.
- Recheck the final branch revision with CI once resource and documentation changes are complete; do not treat earlier passing runs as validation of later revisions.
- Historical feature-review documents (`localization-unresolved-*.md`) retain their archival evidence and point here for current scope. Do not use their old counts as active translation queues.

Historical full review at previous 39-language checkpoint: [commit `6e3e79c`](https://github.com/H3nb/JL-Mod-Plus/blob/6e3e79caca46c766d4252b187069d1053324ee36/docs/localization-language-review.md).
