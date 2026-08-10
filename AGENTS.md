# JL-Mod Plus Agent Guidelines

## Mission

Evolve JL-Mod Plus with the smallest safe change that solves the current task. This is an emulator compatibility project: behavioral correctness, compatibility, auditability, and maintainability take priority over architectural novelty.

## Priority order

When guidance conflicts, use this order:

1. The user's explicit task and constraints.
2. Existing emulator behavior and compatibility contracts unless the task intentionally changes them.
3. Repository-specific rules in this file and verified source/specification behavior.
4. Relevant project skills under `.agents/skills/`.
5. General Android or software-engineering best practices.

A skill is guidance, not permission to ignore repository constraints or expand scope.

Project workflows are CLI-first and must not require Android Studio. If a skill recommends an IDE-only assistant, use the equivalent documented Gradle/source migration instead unless the user explicitly asks for Android Studio.

## Anti-over-engineering policy

- Solve the exact problem. Prefer the smallest coherent diff that is equally effective and stable.
- Do not add an abstraction, module, layer, dependency, framework, generic helper, or configuration system without a concrete need in the current task.
- Do not future-proof for hypothetical requirements.
- Do not mix unrelated cleanup, dependency upgrades, formatting churn, renames, or broad refactors into a focused change.
- Prefer existing project patterns and dependencies when they are adequate.
- Do not replace stable behavior merely because another design looks more modern.
- Do not migrate emulator/platform boundaries to Compose merely to claim that everything is Compose. Views or native surfaces may remain when they are a concrete emulator or Android-platform boundary.
- Do not upgrade AGP, Gradle, Kotlin, JDK, NDK, SDK levels, or libraries as a side effect unless the task requires it.
- Do not add blanket H3NB copyright headers to inherited/upstream files. Preserve upstream copyright, license, and attribution notices. Add new attribution only where it is actually appropriate for genuinely new work.

## License and copyright policy

Treat licensing and copyright as correctness constraints, not decoration.

- Before changing `LICENSE`, `NOTICE`, source headers, attribution, or bundled third-party notices, inspect the relevant upstream provenance and license terms first.
- Keep the standard Apache License 2.0 text in the root `LICENSE` unchanged unless a concrete licensing requirement explicitly requires otherwise.
- Preserve inherited copyright, license, patent, trademark, and attribution notices. Do not remove or replace them merely to rebrand the fork.
- For inherited Apache-2.0 files that are modified, satisfy the license requirement to identify modified files without turning that notice into a blanket ownership claim.
- Do not mass-insert project or maintainer copyright statements into inherited files.
- Do not assume AI-assisted output is automatically copyrightable by the project maintainer. Make a copyright claim only when there is a clear basis for human authorship and the user explicitly wants that claim.
- Keep `NOTICE` focused on meaningful or required attribution. Do not use it as a dependency changelog or as a place for speculative ownership claims.
- When legal attribution needs the maintainer's name, use `Hendra Bara'langi'`. Use `h3nb` for technical identifiers, usernames, package-related naming, and similar non-legal identifiers.
- If ownership or licensing is genuinely uncertain, preserve existing rights and attribution, state the uncertainty, and avoid inventing a legal conclusion.

## Reconstruction policy

- `dev` and `dev_backup` are historical/reference branches, not units of truth to replay blindly.
- Do not blindly cherry-pick experimental history. Reconstruct the intended final behavior from relevant source history, current code, tests, and specifications.
- A feature that took many experimental commits in `dev` may become one coherent implementation in `alpha`.
- A mixed or superseded `dev` commit may be partially incorporated, superseded, or dropped.
- Keep each PR focused on one logical concern. Intermediate commits may be exploratory, fixup-oriented, or temporarily fail validation when that is part of the investigation, but the final PR state must be coherent and validated.
- When reconstructing from `dev`, be able to explain which historical behavior was incorporated and which was intentionally omitted.

## Git and pull request workflow

`alpha` is the default integration branch. Normal development happens on a dedicated branch and is integrated through a pull request to `alpha`.

- Do not develop directly on `alpha` unless the user explicitly requests it.
- Start each PR-sized logical concern from the latest `alpha`, then create a fresh descriptive branch for that work.
- `temp` is not a required workflow branch. It may be used as temporary staging or scratch space when explicitly requested, but normal implementation work should use a dedicated PR branch.
- Keep one PR centered on one coherent logical concern. Split unrelated work instead of allowing opportunistic cleanup to expand the PR.
- A PR may contain multiple implementation, experiment, fixup, cleanup, and validation commits. Commit history inside the PR does not need to be cosmetically rewritten merely to look clean.
- Commits that exist to test whether an approach works, capture a useful checkpoint, fix a discovered failure, or prepare the branch for merge or squash merge may remain in the PR history.
- Use `[skip ci]` on intermediate commits when running CI would provide little or no additional validation and would only create an unnecessary GitHub Actions run.
- Intentionally omit `[skip ci]` on meaningful validation checkpoints so CI verifies the branch when the result is useful.
- Never use `[skip ci]` to conceal a known CI failure, bypass relevant validation, or make a broken PR appear mergeable.
- Before merging, ensure the final PR state has been validated successfully by CI without a skip instruction. If the PR head has changed since the last successful relevant run, validate the current head again.
- Do not create empty or no-op commits solely to trigger, suppress, or manipulate CI. Use an actual follow-up change or the workflow rerun/manual-dispatch mechanism instead.
- Prefer Squash Merge when the PR history contains WIP, experimentation, fixups, reversions, or other development-only commits that are not useful in `alpha` history.
- A Merge commit is acceptable when the individual PR commits were intentionally designed as atomic, understandable units worth preserving for history, revert, or bisect purposes.
- Rebase Merge is not the default strategy; use it only when there is a concrete reason or the user explicitly requests it.
- For Squash Merge, make the final squash title/message describe the completed logical change rather than the experimental path taken to reach it. Do not carry `[skip ci]` from intermediate commits into the final squash commit.
- PR descriptions should summarize the final change: what changed, why, important intentional exclusions, relevant validation, and historical `dev` behavior or commits used during reconstruction when applicable. They do not need to narrate every intermediate experiment.
- After a PR is merged, start unrelated new work from the latest `alpha` on a fresh branch rather than continuing development on the already-merged branch.

## Versioning

- JL-Mod Plus `versionName` follows Semantic Versioning (`MAJOR.MINOR.PATCH`). The independent JL-Mod Plus release line starts at `0.1.0`.
- During `0.x`, use MINOR for incompatible or substantial behavior changes while the project is still stabilizing, and PATCH for backward-compatible fixes. Starting with `1.0.0`, increment MAJOR for incompatible changes, MINOR for backward-compatible features, and PATCH for backward-compatible fixes.
- Android `versionCode` is a separate monotonically increasing integer. Never reuse or decrease a `versionCode` that has already been published.
- Do not bump versions incidentally in an unrelated change unless release/versioning work is part of the task.

## Repeated audit loop

Do not treat a successful build as the end of the task. Audit repeatedly.

### Before editing: scope audit

- Identify the exact requested behavior and the files that should plausibly change.
- Inspect the current implementation before proposing a new architecture.
- Inspect relevant tests and relevant source history when reconstructing behavior from `dev`.
- Read only the relevant skill(s) under `.agents/skills/`.
- For Java ME API/JSR work, perform the mandatory specification audit described below.

### During implementation: implementation audit

After each meaningful implementation step, re-check:

- Is every new abstraction or dependency necessary now?
- Did the change spread beyond the requested concern?
- Is there a simpler implementation with the same correctness and stability?
- Did behavior change unintentionally?
- Did platform/emulator boundaries move without a concrete reason?

### Before handoff: final diff audit

Review the complete diff again, even if tests pass. Verify:

- Every changed line has a reason tied to the task.
- No unrelated AI-generated cleanup or speculative code remains.
- No temporary compatibility flags, dead code, duplicate paths, or superseded implementation remain unless explicitly required.
- Tests cover the behavior that is most likely to regress.
- Copyright, license, and attribution remain correct.
- Documentation and comments describe the final architecture, not the experimentation that led to it.

Repeat the audit if the final diff still contains unexplained or questionable changes.

## Mandatory Java ME API / JSR reference

For any change that implements, modifies, fixes, or interprets a Java ME API, JSR, vendor API, or compatibility contract, consult `https://github.com/shinovon/J2ME_Docs` before designing or editing the implementation.

This includes, but is not limited to, `javax.microedition.*`, Nokia/vendor APIs, MMAPI/JSR-135, M3G/JSR-184, Bluetooth/JSR-82, PIM, location, messaging, media controls, device capabilities, lifecycle contracts, and API-specific file/encoding behavior.

Required procedure:

1. Locate the relevant API/JSR/vendor documentation in J2ME_Docs.
2. Verify signatures, constants, state transitions, return values, exceptions, capability reporting, and edge cases relevant to the change.
3. Compare the documented contract with existing JL-Mod/JL-Mod Plus behavior and compatibility tests.
4. Do not silently "correct" intentional compatibility behavior just because Android or desktop Java behaves differently.
5. If emulator compatibility intentionally differs from the formal contract, document the reason and add or preserve a targeted regression test.
6. If the documentation is incomplete or ambiguous, state that uncertainty; prefer preserving known-compatible behavior and add characterization tests rather than guessing.

J2ME_Docs is a mandatory reference, not necessarily the only reference. Use original JSR/vendor documentation too when the question requires more detail.

## Project skills

Use a skill only when the task matches it. Do not load or apply every skill by default.

- `agp-9-upgrade`: AGP 9 migration and associated Gradle/Kotlin build changes.
- `navigation-3`: migration to or work involving Jetpack Navigation 3.
- `migrate-xml-views-to-jetpack-compose`: migration of application-owned XML/View UI to Compose. Do not use it as justification to replace a required emulator/native host boundary.
- `adaptive`: adaptive Compose layouts after its prerequisites are actually satisfied. Experimental APIs mentioned by the skill require a concrete need; do not adopt them merely because the skill describes them.
- `r8-analyzer`: R8/ProGuard configuration analysis. Respect the skill's analysis-only scope unless the user separately requests implementation changes. The pinned upstream currently lacks two scripts referenced by its scripted path; follow the fallback documented in `.agents/UPSTREAM.md` and never fabricate those scripts.
- `edge-to-edge`: system bars, cutouts, IME, WindowInsets, and inset ownership.
- `testing-setup`: testing strategy and infrastructure. Preserve the existing testing stack by default; do not install Hilt, Robolectric, Jacoco, mocking frameworks, screenshot frameworks, or other test infrastructure solely because the skill lists them. Add infrastructure only when the current task needs it and the existing stack is inadequate.

The vendored skill set includes the selected `SKILL.md` entry points and local reference files documented in `.agents/UPSTREAM.md`. If a referenced `references/` or `scripts/` file is not vendored, check the exact corresponding path at the pinned upstream revision. Retrieve it only if it exists there and is needed for the current task. If it is absent upstream too, follow the documented project fallback instead of inventing missing skill content or tooling.

## Android/UI constraints

- The standalone `midlet` product flavor for building Java ME source directly into an Android APK is intentionally disabled. Keep its source set as dormant reference material; do not re-enable or remove it unless the user explicitly requests MIDlet porting support.
- Debug development and CI are intentionally focused on `arm64-v8a`, the architecture available for device validation. Do not spend routine debug build time compiling other ABIs unless cross-ABI validation is explicitly requested.
- Release/distribution builds retain the project's configured multi-ABI support; do not narrow release ABI coverage merely because debug is ARM64-only.
- Migrate application-owned XML layouts and programmatic View UI to Jetpack Compose Material 3 incrementally when doing so does not alter Java ME API/JSR/vendor behavior or a required emulator/platform/input boundary. Keep each stage buildable.
- Do not change a Java ME API implementation merely to facilitate Compose migration. Preserve a native/View boundary where emulator rendering, platform interop, or input behavior concretely requires it.
- For internal application UI icons, prefer official Material Symbols when a suitable symbol exists. Search the official Material Symbols source directly or use `scripts/material-symbols.py`; use a custom icon only when no suitable Material Symbol exists.
- For edge-to-edge, keep one clear owner for each inset. Avoid double-padding and duplicated inset consumption.
- Do not introduce Navigation 3, adaptive APIs, or other architectural migrations before the task and prerequisites justify them.
- Preserve behavior first during UI migration; redesign and behavior changes should be explicit separate concerns when practical.

## Validation

Use the narrowest relevant tests while iterating. For the current alpha baseline, the default CI-level verification is:

```bash
./gradlew --no-daemon :app:testEmulatorDebugUnitTest :app:assembleEmulatorDebug
```

- Routine debug and CI builds should compile/package only `arm64-v8a`; other ABIs are reserved for explicit compatibility or release validation.
- Prefer debug builds during reconstruction. Do not run release/R8 builds unless the task specifically requires release behavior, shrinking, signing, or R8 validation.
- Do not run `clean` routinely; it wastes time and destroys useful incremental build state.
- Add focused regression or characterization tests for compatibility-sensitive behavior.
- If a required validation cannot be run, report exactly what was not verified and why.
- CI must reflect the toolchain and feature state that actually exists in the branch, not assumptions from a future final state.

## Handoff expectations

For completed work, report concisely:

- what changed and why;
- the relevant tests/build checks run;
- Java ME API/JSR documentation consulted when applicable;
- relevant historical `dev` behavior/commits used when reconstructing a feature;
- any intentional compatibility deviation, limitation, or remaining uncertainty.
