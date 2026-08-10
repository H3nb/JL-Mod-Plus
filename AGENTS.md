# JL-Mod Plus Agent Guidelines

## Core priorities

- Follow the user's explicit task and constraints first.
- Preserve emulator behavior and compatibility unless the task intentionally changes them.
- Prefer the smallest coherent change that solves the problem.
- Treat the current source, Gradle configuration, workflows, tests, and verified specifications as the source of truth. Do not encode transient project state into this file.
- Use relevant guidance under `.agents/skills/` only when the task matches it.
- Project workflows are CLI-first; do not require Android Studio unless the user explicitly asks for it.

## Change discipline

- Do not add abstractions, modules, dependencies, frameworks, generic helpers, or configuration layers without a concrete need in the current task.
- Do not mix unrelated cleanup, formatting churn, dependency upgrades, toolchain upgrades, renames, or broad refactors into a focused change.
- Prefer existing project patterns and dependencies when they are adequate.
- Preserve application behavior during UI migration. Do not change Java ME API behavior merely to facilitate Compose.
- Application-owned UI may migrate to Compose incrementally, but emulator, rendering, input, or Android-platform boundaries may remain native/View when they serve a concrete purpose.
- For internal app icons, prefer official Material Symbols when suitable; use `scripts/material-symbols.py` or the official source before creating a custom icon.

## Java ME compatibility

For changes to Java ME APIs, JSRs, vendor APIs, or compatibility behavior:

- Consult `https://github.com/shinovon/J2ME_Docs` before editing the implementation.
- Verify the relevant API contract, constants, state transitions, return values, exceptions, and edge cases.
- Compare the specification with existing JL-Mod/JL-Mod Plus behavior before changing compatibility-sensitive code.
- Do not silently "correct" known-compatible behavior just because Android or desktop Java behaves differently.
- If documentation is incomplete or ambiguous, prefer preserving known behavior and add a focused characterization/regression test instead of guessing.

## Licensing and attribution

- Preserve inherited copyright, license, patent, trademark, and attribution notices.
- Keep the root Apache-2.0 `LICENSE` unchanged unless a concrete licensing requirement says otherwise.
- Mark inherited Apache-2.0 files that are modified with a neutral modification notice such as `Modified for JL-Mod Plus.` without implying ownership of upstream work.
- Do not mass-add project or maintainer copyright claims to inherited files.
- Keep `NOTICE` limited to meaningful or required attribution.
- If ownership or licensing is uncertain, preserve existing rights and attribution rather than inventing a legal conclusion.

## Git, PR, and CI workflow

- `alpha` is the integration branch. Do normal development on a dedicated branch and integrate through a PR to `alpha`.
- Start unrelated work from the latest `alpha` on a fresh branch. `temp` may be used only as optional scratch/staging when explicitly requested.
- Keep one PR centered on one coherent concern. Intermediate experiment/fixup commits may remain when they are useful to the development process.
- Use `[skip ci]` on intermediate commits when CI would provide little additional value.
- Do not use `[skip ci]` to hide a known failure or bypass relevant validation.
- Before merging changes that can affect build, runtime behavior, tests, or CI, validate the final relevant state with CI without a skip instruction. Documentation-only or policy-only changes need only validation relevant to those changes.
- Do not create empty/no-op commits solely to manipulate CI; use a real follow-up change, rerun, or manual dispatch instead.
- Prefer Squash Merge when PR history is mostly WIP, experiments, fixups, or reversions. Preserve individual commits only when they are intentionally useful for history, revert, or bisect.
- Do not carry `[skip ci]` into the final squash commit message.

## Versioning

- `versionName` follows Semantic Versioning.
- During `0.x`, use MINOR for substantial or incompatible changes and PATCH for backward-compatible fixes.
- Android `versionCode` must increase monotonically for published application IDs.
- Do not bump versions as an unrelated side effect.

## Validation and handoff

- Use the narrowest relevant test/build while iterating; derive exact commands and ABI scope from the current repository configuration rather than this file.
- Prefer debug validation unless release, signing, shrinking, R8, or distribution behavior is specifically under test.
- Do not run `clean` routinely.
- Add focused regression or characterization tests for compatibility-sensitive changes when practical.
- If validation cannot be run, state exactly what remains unverified.
- Before handoff, review the final diff for unrelated changes, dead code, temporary workarounds, licensing issues, and unintended behavior changes.
- Report concisely what changed, what was validated, and any remaining limitation or uncertainty.
