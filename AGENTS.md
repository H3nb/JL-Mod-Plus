# JL-Mod Plus Agent Guidelines

## Core priorities

- Follow the user's explicit task and constraints first.
- Preserve emulator behavior and compatibility unless the task intentionally changes them.
- Prefer the smallest coherent change that solves the problem.
- Treat the current source, Gradle configuration, workflows, tests, and verified specifications as the source of truth. Do not encode transient project state into this file.
- Route matching tasks through the repository skills described below.
- Project workflows are CLI-first; do not require Android Studio unless the user explicitly asks for it.

## Repository skills

The repository contains task-specific guidance under `.agents/skills/`. When a task matches a skill, read that skill's `SKILL.md` before planning or editing and follow the parts that are relevant to the current task.

- User instructions, this `AGENTS.md`, verified project behavior, and the current repository state take precedence over generic defaults in a skill.
- Use only skills that match the current task. A skill prerequisite is a planning constraint, not permission to widen the PR or perform unrelated migrations.
- Do not install every framework, dependency, test tool, or architectural pattern suggested by a general skill. Prefer the current project stack and add only what the current task concretely requires.
- Follow a skill's local `references/`, scripts, and validation instructions when they apply; do not substitute remembered or historical guidance for repository-provided material.
- If a skill's assumptions do not match the project, preserve the task scope and current behavior, then adapt or defer the incompatible part rather than forcing the project to fit the skill.

Available skill routing:

- `agp-9-upgrade`: AGP 9 migration, built-in Kotlin/new DSL work, AGP compatibility, or AGP-specific troubleshooting.
- `testing-setup`: analysis or changes to unit, UI, screenshot, instrumentation, end-to-end, or coverage infrastructure. Respect the existing test stack and introduce new frameworks only when the task needs them.
- `migrate-xml-views-to-jetpack-compose`: bounded XML/View-to-Compose migrations. Preserve visual and functional behavior and keep Android/emulator boundaries native/View when they still serve a concrete purpose.
- `edge-to-edge`: Compose edge-to-edge, system-bar, WindowInsets, cutout, or IME work. Check its Compose and target-SDK prerequisites; do not silently widen an unrelated task to satisfy them.
- `navigation-3`: Navigation 3 installation or migration, back stacks, deep links, scenes, navigation state, and related View/Compose interoperability.
- `adaptive`: adaptive/multi-pane Compose UI across window sizes and device classes. Check its Compose and Navigation 3 prerequisites before applying it.
- `r8-analyzer`: R8/keep-rule analysis and app-size optimization investigation. Treat the skill as analysis-only unless a separate implementation task is explicitly in scope.

## Change discipline

- Do not add abstractions, modules, dependencies, frameworks, generic helpers, or configuration layers without a concrete need in the current task.
- Do not mix unrelated cleanup, formatting churn, dependency upgrades, toolchain upgrades, renames, or broad refactors into a focused change.
- Prefer existing project patterns and dependencies when they are adequate.
- Treat historical or experimental branches as reference material rather than implementation authority; do not merge them wholesale or replay broad commit ranges unless explicitly requested.
- Reconstruct desired behavior against the current architecture, carrying forward verified contracts and tests rather than obsolete implementation structure.
- Keep migration status, roadmap state, and branch-specific reconstruction decisions in dedicated tracking artifacts rather than this file.
- Preserve application behavior during UI migration. Do not change Java ME API behavior merely to facilitate Compose.
- Application-owned UI may migrate to Compose incrementally, but emulator, rendering, input, or Android-platform boundaries may remain native/View when they serve a concrete purpose.
- For internal app icons, prefer official Material Symbols when suitable; use a repository-provided helper when available before creating a custom icon.

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

- Use the repository's current default/integration branch as the base. Do normal development on a dedicated branch and integrate through a PR rather than working directly on the integration branch unless explicitly requested.
- Start unrelated work from the latest integration branch on a fresh branch. Use scratch/staging branches only when they are actually useful or explicitly requested.
- Keep one PR centered on one coherent concern. Intermediate experiment/fixup commits may remain when they are useful to the development process.
- Use `[skip ci]` on intermediate commits when CI would provide little additional value.
- Do not use `[skip ci]` to hide a known failure or bypass relevant validation.
- Before merging changes that can affect build, runtime behavior, tests, or CI, validate the final relevant state with CI without a skip instruction. Documentation-only or policy-only changes need only validation relevant to those changes.
- Do not create empty/no-op commits solely to manipulate CI; use a real follow-up change, rerun, or manual dispatch instead.
- Prefer Squash Merge when PR history is mostly WIP, experiments, fixups, or reversions. Preserve individual commits only when they are intentionally useful for history, revert, or bisect.
- Do not carry `[skip ci]` into the final squash commit message.

## Versioning

- `versionName` follows Semantic Versioning; apply the semantics appropriate to the current major version.
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
