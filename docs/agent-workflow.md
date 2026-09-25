# Agent development workflow

Read the sections relevant to the task. [AGENTS.md](../AGENTS.md) states the project-wide priorities; current source, configuration, tests, and verified specifications remain the authority for implementation details.

## Task continuity and delegation

- Treat follow-up corrections and status questions as part of the active task unless the user changes the objective. Preserve completed work and outstanding checks across context compaction.
- When collaboration tools are available, use subagents for independent, bounded investigations when parallel work materially improves coverage or speed. Do not delegate small sequential work, and review delegated results before integration.

## Repository skills

The repository contains task-specific guidance under `.agents/skills/`. When a task matches a skill, read that skill's `SKILL.md` before planning or editing and follow the parts that are relevant to the current task.

- User instructions take precedence over skill guidance. Apply [AGENTS.md](../AGENTS.md) and verified current project behavior when interpreting a matching skill; do not let unclear or conflicting skill steps silently redirect the task.
- Use only skills that match the current task. A skill prerequisite is a planning constraint, not permission to widen the PR or perform unrelated migrations.
- Do not install every framework, dependency, test tool, or architectural pattern suggested by a general skill. Prefer the current project stack and add only what the current task concretely requires.
- Follow a skill's local `references/`, scripts, and validation instructions when they apply; do not substitute remembered or historical guidance for repository-provided material.
- If a skill's assumptions do not match the project, preserve the task scope and current behavior, then adapt or defer the incompatible part rather than forcing the project to fit the skill.
- Skill approval steps do not override authorization already given under [AGENTS.md](../AGENTS.md). If a skill would require a material pause, scope change, or divergence from the user's request, identify the exact instruction and explain why it applies.
- Keep vendored skills unchanged for project policy adaptations; record provenance and local interpretation in [.agents/UPSTREAM.md](../.agents/UPSTREAM.md).

Available skill routing:

- `agp-9-upgrade`: AGP 9 migration, built-in Kotlin/new DSL work, AGP compatibility, or AGP-specific troubleshooting.
- `testing-setup`: creating, replacing, or materially changing test infrastructure, frameworks, harnesses, or coverage setup. Do not load it merely to add ordinary tests with the existing stack.
- `migrate-xml-views-to-jetpack-compose`: bounded XML/View-to-Compose migrations. Preserve visual and functional behavior and keep Android/emulator boundaries native/View when they still serve a concrete purpose.
- `edge-to-edge`: Compose edge-to-edge, system-bar, WindowInsets, cutout, or IME work. Check its Compose and target-SDK prerequisites; do not silently widen an unrelated task to satisfy them.
- `navigation-3`: Navigation 3 installation or migration, back stacks, deep links, scenes, navigation state, and related View/Compose interoperability.
- `adaptive`: adaptive/multi-pane Compose UI across window sizes and device classes. Check its Compose and Navigation 3 prerequisites before applying it.
- `r8-analyzer`: R8/keep-rule analysis and app-size optimization investigation. Treat the skill as analysis-only unless a separate implementation task is explicitly in scope.
- `android-profiler`: Android performance traces, memory profiling, bottlenecks, jank, and related Perfetto analysis.
- `camerax`: CameraX feature work or CameraX-specific camera integration.

## Current-state evidence

- Inspect current source, configuration, tests, workflows, and current documentation first when determining what exists or how it behaves.
- Treat PRs, commits, deleted branches, old issues, discussions, and other historical artifacts as context rather than implementation authority. A merged change can also be obsolete if current code superseded or reverted it.
- Search history only when the user asks for it or current-state evidence cannot resolve a necessary question. Search narrowly around the relevant subsystem, path, symbol, behavior, regression, or provenance question, and stop once sufficient evidence exists.
- Revalidate conclusions from history against current repository state before recommending or changing implementation. Exclude work the user identifies as obsolete or abandoned unless they explicitly ask to revisit it.

## Change discipline

- Add abstractions, modules, dependencies, frameworks, helpers, state, or configuration layers only when they enforce a real boundary, remove meaningful duplication or complexity, or are required for correctness.
- Do not mix unrelated cleanup, formatting churn, dependency or toolchain upgrades, renames, or broad refactors into a focused change.
- Prefer existing project patterns and dependencies when they fit, but do not preserve an incorrect boundary or duplicated ownership merely for consistency.
- When replacing an existing flow, identify its active entry points, state owners, persistence paths, and compatibility obligations. Route retained behavior through one authoritative implementation and remove superseded paths once their callers and contracts are accounted for.
- Treat historical or experimental branches as reference material rather than structures to replay wholesale. Reconstruct required behavior against the current architecture.
- Keep migration status, roadmap state, and branch-specific reconstruction decisions in dedicated tracking artifacts rather than this file.

## Source language policy

- Prefer Kotlin for new project-owned source files when it is a sound fit, including Android app code and code adjacent to existing Java subsystems.
- Language choice does not imply introducing ViewModels, repositories, interfaces, wrappers, or other architectural layers. Add them only when the task has a concrete need.
- Use Java when required or when it is the simpler or safer fit for an existing Java subsystem, compatibility contract, or performance-sensitive path. Do not migrate stable Java code to Kotlin solely for consistency or modernization.
- Native code is allowed when it fits an existing native boundary or has a concrete performance, latency, or interoperability benefit. Preserve JNI, ABI, lifecycle, and compatibility contracts, and measure non-obvious performance tradeoffs.
- Kotlin and Java interoperability is an accepted project architecture. Do not add wrapper or adapter layers solely to hide a mixed-language boundary.
- Keep Jetpack Compose implementation in Kotlin.

## Performance discipline

- Write Kotlin and Java that are efficient by default without sacrificing correctness, readability, or maintainability for speculative micro-optimizations. Prefer better algorithms, data structures, and less unnecessary work before low-level tuning.
- Avoid avoidable allocation, copying, boxing, temporary collections, and repeated computation when a comparably clear implementation can avoid them, especially in frequently executed code.
- In hot loops or performance-sensitive paths, prefer straightforward loops and primitive-friendly representations when they avoid meaningful overhead. Do not replace a simple loop with chained collection operations, sequences, lambdas, or abstractions merely because they are more idiomatic.
- Do not assume `Sequence`, collection pipelines, coroutines, `inline`, or other Kotlin features are inherently faster. Choose them for appropriate semantics or demonstrated benefit, considering the workload and generated overhead.
- Keep expensive work, blocking I/O, parsing, decoding, persistence, and other unsuitable operations off latency-sensitive UI or rendering paths.
- Do not introduce caching, object pooling, custom collections, manual inlining, or other complexity without a concrete performance reason.
- Treat interpreter/VM loops, rendering and audio pipelines, memory scanning, JNI-adjacent code, and other high-frequency paths as performance-sensitive. Preserve efficient existing implementations unless a change has a concrete benefit.
- When two implementations are similarly clear, prefer the one that performs less work and creates less garbage. When the performance tradeoff is non-obvious or material, measure with an appropriate benchmark or profiler rather than relying on assumptions about Kotlin versus Java.

## Licensing and attribution

- Preserve inherited copyright, license, patent, trademark, and attribution notices.
- Keep the root Apache-2.0 `LICENSE` unchanged unless a concrete licensing requirement says otherwise.
- Ensure modified upstream files carry `Modified for JL-Mod Plus.` as their sole project modification notice, without explanatory qualifiers, duplicates, or a new ownership claim. Keep the upstream copyright and other inherited notices intact, and meet any additional requirements of the applicable license.
- For new project-owned files, do not add a project or maintainer copyright claim by default. Apply the project's Apache-2.0 license identifier or header when applicable; preserve the applicable license for code derived from elsewhere.
- Keep `NOTICE` limited to meaningful or required attribution.
- If ownership or licensing is uncertain, preserve existing rights and attribution rather than inventing a legal conclusion.

## Git, PR, and CI workflow

- Use the repository's current default/integration branch as the base. Do normal development on a dedicated branch and integrate through a PR rather than working directly on the integration branch unless explicitly requested.
- Start unrelated work from the latest integration branch on a fresh branch. Use scratch/staging branches only when they are actually useful or explicitly requested.
- Keep one PR centered on one coherent concern. Intermediate experiment/fixup commits may remain when they are useful to the development process.
- Treat GitHub autolinks as repository-sensitive data, not harmless formatting. Never publish an ambiguous shorthand that could resolve to the wrong repository, fork, issue, PR, workflow run, commit, release, discussion, or other object.
- Do not use a bare `#N` unless it intentionally refers to an issue or PR in the current repository and that target has been verified. When repository identity matters, use an explicit repository-qualified reference such as `owner/repository#N`.
- For objects that are not issues or PRs, use an unambiguous label or direct Markdown link to the intended object. In particular, do not write an Actions run number as bare `#N`; link the run using the repository URL and run ID, or render the number as non-autolink text when no link is intended.
- Before publishing PR bodies, comments, release notes, documentation, or other GitHub-rendered text, check that generated references cannot silently resolve through fork-network or cross-repository context to an unrelated project.
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

- See [Build and validation](development.md) for the current CLI workflow and test source sets.
- Use the narrowest relevant test/build while iterating; derive exact commands and ABI scope from the current repository configuration rather than this file.
- Prefer debug validation unless release, signing, shrinking, R8, or distribution behavior is specifically under test.
- Do not run `clean` routinely.
- For documentation-only edits, check the diff, links, and instruction consistency; Android builds are unnecessary. For code changes, stop after relevant checks pass unless new edits, failures, or unresolved risks justify more testing. Apply relevant checks in [app-owned UI development](app-ui-development.md), [Java ME compatibility](java-me-compatibility.md), and the Library migration source and tests linked from [AGENTS.md](../AGENTS.md) when those areas change.
- Add focused regression or characterization tests for compatibility-sensitive changes when practical.
- If validation cannot be run, state exactly what remains unverified.
- Before handoff, review the final diff for unrelated changes, dead code, temporary workarounds, licensing issues, and unintended behavior changes.
- Lead with the result in the user's language. Include changed files, validation evidence, and material limitations; use plain paragraphs or short lists without filler. Brevity must not hide missing checks or unfinished work.
