# Agent development workflow

Read only the sections relevant to the task. [AGENTS.md](../AGENTS.md) defines the project-wide authority hierarchy and priorities; this file supplies task-specific workflow guidance.

## Task continuity and delegation

- Treat follow-up corrections and status questions as part of the active task unless the user changes the objective. Preserve completed work and outstanding checks across context compaction.
- When collaboration tools are available, use subagents for independent, bounded investigations when parallel work materially improves coverage or speed. Do not delegate small sequential work, and review delegated results before integration.

## Repository skills

Android skills are maintained upstream rather than vendored into this repository. See
[Android skills](android-skills.md) for official entry points, task routing, project
interpretation, and fallback behavior. Read only the relevant skill and its
references when the current task needs them; external guidance is not an
authorization to widen scope or change the JL-Mod Plus compatibility contract.

## Current-state evidence

- Inspect current source, configuration, tests, workflows, and current documentation first when determining what exists or how it behaves.
- Treat PRs, commits, deleted branches, old issues, discussions, and other historical artifacts as context rather than implementation authority. A merged change can also be obsolete if current code superseded or reverted it.
- Search history only when the user asks for it or current-state evidence cannot resolve a necessary question. Search narrowly around the relevant subsystem, path, symbol, behavior, regression, or provenance question, and stop once sufficient evidence exists.
- Revalidate conclusions from history against current repository state before recommending or changing implementation. Exclude work the user identifies as obsolete or abandoned unless they explicitly ask to revisit it.

## Change discipline

Apply the scope and design priorities in [AGENTS.md](../AGENTS.md).

- Prefer existing project patterns and dependencies when they fit, but do not preserve an incorrect boundary or duplicated ownership merely for consistency.
- When replacing an existing flow, identify its active entry points, state owners, persistence paths, and compatibility obligations. Route retained behavior through one authoritative implementation and remove superseded paths once their callers and contracts are accounted for.
- Treat historical or experimental branches as reference material rather than structures to replay wholesale. Reconstruct required behavior against the current architecture.
- Keep migration status, roadmap state, and branch-specific reconstruction decisions in dedicated tracking artifacts rather than this file. Define each durable behavior contract in one authoritative document; other documents should link to it. Update affected documentation when behavior changes.

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

- Follow the branch policy in [AGENTS.md](../AGENTS.md). When continuing an existing PR, use its current head branch and preserve concurrent work. Use scratch/staging branches only when they are actually useful or explicitly requested.
- Keep one PR centered on one coherent concern. Intermediate experiment/fixup commits may remain when they are useful to the development process.
- Treat GitHub autolinks as repository-sensitive data, not harmless formatting. Never publish an ambiguous shorthand that could resolve to the wrong repository, fork, issue, PR, workflow run, commit, release, discussion, or other object.
- Do not use a bare `#N` unless it intentionally refers to an issue or PR in the current repository and that target has been verified. When repository identity matters, use an explicit repository-qualified reference such as `owner/repository#N`.
- For objects that are not issues or PRs, use an unambiguous label or direct Markdown link to the intended object. In particular, do not write an Actions run number as bare `#N`; link the run using the repository URL and run ID, or render the number as non-autolink text when no link is intended.
- Before publishing PR bodies, comments, release notes, documentation, or other GitHub-rendered text, check that generated references cannot silently resolve through fork-network or cross-repository context to an unrelated project.
### CI discipline

- GitHub workflows already run automatically for matching PR changes. Check the current workflow triggers, path filters, and required checks before deciding to run or skip validation; do not assume each push must produce a fresh full build.
- Use `[skip ci]` for documentation/policy-only commits and other commits that do not alter executable or validated inputs. Do **not** use it for production source, tests, assets/resources, build dependencies/configuration, workflow behavior, or validation scripts. An intermediate commit containing such changes is not docs-only merely because a later commit will finish the work.
- Group logically related edits into meaningful checkpoints before pushing when feasible; with GitHub-only access, use a coherent Git tree/commit update rather than one separate push per file. Do not manufacture no-op commits, rerun all workflows to obtain an arbitrary green badge, or automatically retry failures before inspecting evidence.
- Prefer the narrowest relevant test during iteration. When a run fails, classify the first failure (test assertion, product regression, infrastructure, stale baseline, or unrelated) and rerun only when the cause or changed state justifies it. A rerun cannot substitute for diagnosing an actual test failure.
- Before merge, verify that the **latest executable/testable state** has passing evidence for the relevant required checks. Documentation-only commits after that state do not change executable inputs, but GitHub skip instructions or path filters may leave required checks pending; inspect branch-protection and PR status and use the documented validation path when needed, rather than masking the missing check.
- A commit message with `[skip ci]` does not itself establish successful validation, and required check behavior depends on workflow trigger configuration. Never skip to conceal failures or bypass final review.
- For screenshot mismatches using only GitHub connector access, follow [GitHub-only reviewed screenshot promotion](development.md#github-only-reviewed-screenshot-promotion); do not redraw or force-update references.
- Do not carry `[skip ci]` into the final squash commit message.
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
- For documentation-only edits, check the diff, links, and instruction consistency; Android builds are unnecessary. For code changes, apply the validation stopping rule in [AGENTS.md](../AGENTS.md) and relevant checks in [app-owned UI development](app-ui-development.md), [Java ME compatibility](java-me-compatibility.md), and the Library migration source and tests linked from [AGENTS.md](../AGENTS.md) when those areas change.
- Add focused regression or characterization tests for compatibility-sensitive changes when practical.
- When local tooling is unavailable, follow [Validation through CI](development.md#validation-through-ci); distinguish checks executed successfully from tests only written or compiled, and identify remaining verification gaps.
- Before handoff, review the final diff for unrelated changes, dead code, temporary workarounds, licensing issues, and unintended behavior changes.
- Lead with the result in the user's language. Include changed files, validation evidence, and material limitations; use plain paragraphs or short lists without filler. Brevity must not hide missing checks or unfinished work.
