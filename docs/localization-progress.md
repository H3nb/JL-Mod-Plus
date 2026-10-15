# Localization implementation progress — PR #169

**Current checkpoint: 15 October 2026.** Work remains on the existing `localization/ai-matrix-workflow` branch targeting `alpha`. This document supersedes older checkpoint counts recorded in Git history.

## Current delivery

- **38 supported locale catalogs** plus canonical English. Santali (`values-sat`) has been deliberately removed from the supported-language set because its language-quality evidence was not adequate for inclusion.
- All 38 remaining catalogs retain **931 strings + 34 plurals = 965** canonical translatable resource identities each. The earlier 39-language and 977-resource milestones are historical: 12 unused keys were removed in a prior cleanup.
- Updated `memory_editor_remove_candidates_description` in English and every retained locale. The text now distinguishes removing search results from changing game memory, and describes conflict-safe restoration during **Revert & remove**. Each translation reuses its existing action labels.
- This is a resource/catalog/documentation change. The Memory Editor's operations, Android locale-handling architecture, and Java ME guest locale were not changed.

## Validation boundary

- Structural review must confirm no missing/extra keys, duplicate names, incompatible formatting, invalid XML, or missing plural `other` forms. Confirm that the generated app-language choices no longer include `sat`.
- Run one final Android CI on the completed resource revision rather than repeatedly on intermediate documentation commits. The earlier passing [CI run #38702731109](https://github.com/H3nb/JL-Mod-Plus/actions/runs/38702731109) validated `c5fbf224`, not the rewritten dialog copy.
- The new two-paragraph help message needs visual and accessibility checks on small devices and RTL scripts. Fluent-linguistic acceptance for languages without qualified reviewers cannot be inferred merely from successful builds.
- Keep this PR in Draft while these final review gates are outstanding. Do not squash merge as part of this localization-scope update.

## History and guidance

The previous 39-locale recovery (including Santali trials, machine-reference limitations and 76/85 Santali review queues) remains traceable at [the former progress checkpoint](https://github.com/H3nb/JL-Mod-Plus/blob/6e3e79caca46c766d4252b187069d1053324ee36/docs/localization-progress.md) and earlier commits. Those Santali queues no longer apply to the current supported languages.

Continue to follow the [localization contract](localization.md), [translation workflow](localization-workflow.md), and [current language review](localization-language-review.md). Do not edit MIDlet-owned text or normalize user-supplied content.
