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

## Reconstruction policy

- `dev` and `dev_backup` are historical/reference branches, not units of truth to replay blindly.
- Do not blindly cherry-pick experimental history. Reconstruct the intended final behavior from relevant source history, current code, tests, and specifications.
- A feature that took many experimental commits in `dev` may become one coherent implementation in `alpha`.
- A mixed or superseded `dev` commit may be partially incorporated, superseded, or dropped.
- Keep each `temp -> alpha` change focused on one logical concern and keep every stage buildable.
- When reconstructing from `dev`, be able to explain which historical behavior was incorporated and which was intentionally omitted.

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
- `r8-analyzer`: R8/ProGuard configuration analysis. Respect the skill's analysis-only scope unless the user separately requests implementation changes.
- `edge-to-edge`: system bars, cutouts, IME, WindowInsets, and inset ownership.
- `testing-setup`: testing strategy and infrastructure. Preserve the existing testing stack by default; do not install Hilt, Robolectric, Jacoco, mocking frameworks, screenshot frameworks, or other test infrastructure solely because the skill lists them. Add infrastructure only when the current task needs it and the existing stack is inadequate.

The vendored `SKILL.md` files are intentionally lightweight entry points. If a skill references a local `references/` or `scripts/` file that is not vendored, retrieve the exact corresponding file from the pinned upstream revision documented in `.agents/UPSTREAM.md`. Never invent missing skill content from memory.

## Android/UI constraints

- Migrate application-owned UI toward Compose Material 3 incrementally and keep each stage buildable.
- Preserve a native/View boundary where emulator rendering, platform interop, or input behavior concretely requires it.
- For edge-to-edge, keep one clear owner for each inset. Avoid double-padding and duplicated inset consumption.
- Do not introduce Navigation 3, adaptive APIs, or other architectural migrations before the task and prerequisites justify them.
- Preserve behavior first during UI migration; redesign and behavior changes should be explicit separate concerns when practical.

## Validation

Use the narrowest relevant tests while iterating. For the current alpha baseline, the default CI-level verification is:

```bash
./gradlew --no-daemon :app:testEmulatorDebugUnitTest :app:assembleEmulatorDebug
```

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
