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
- For nontrivial changes, trace the active entry point to its behavior and state owners, affected data, process or lifecycle boundaries, and relevant contract tests before choosing a design. Broaden investigation only when evidence points beyond the initial boundary.
- For bugs, distinguish observed behavior from suspected causes. Use a focused reproduction, logs, or bounded diagnostics to test competing explanations before patching. If reproduction is unavailable, state the remaining uncertainty and validate the closest practical contract.
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
### PR and commit titles

- Use a concise, natural English action-and-subject title that lets someone scan Git history or a changelog without opening the diff. Prefer an imperative verb and the concrete component or behavior changed (e.g. `Fix Micro3D texture offsets`, `Preserve Memory Editor scroll position`). Aim for a single readable line, typically under 72 characters; clarity is more important than a rigid limit.
- Describe the **actual final change**, not the development activity. Avoid vague titles such as `Various fixes`, `Improve code quality`, `Refactor and optimize`, `Final updates`, or `Implement requested changes`. Use `Fix`, `Refactor`, or `Improve` only when the rest of the title specifies what was fixed, simplified, or improved.
- Keep PR titles aligned with the effective diff as the scope evolves; rewrite an outdated title before review or merge. Write commit subjects for their individual changes, and use the final reviewed outcome as the squash commit title. Put rationale, tests, risks, implementation details and issue links in the description/body instead of cramming them into the title.
- Do not mandate Conventional Commit prefixes, ticket numbers, hype, model attribution, stage labels or punctuation for ordinary titles. Mark intentionally experimental work clearly, but remove WIP/research labels when its final behavior is production-ready.

### CI discipline

- GitHub workflows already run automatically for matching PR changes. Check the current workflow triggers, path filters, and required checks before deciding to run or skip validation; do not assume each push must produce a fresh full build.
- Prefer existing path filters for documentation/policy-only PRs; **do not automatically add** `[skip ci]` to their commits. Before deliberately skipping a workflow, inspect the full PR diff, workflow triggers, and required checks: a workflow skipped by message or path filters can leave required checks pending. Never skip executable or validated inputs (production source, tests, screenshot baselines, other assets/resources, build dependencies/configuration, workflow behavior, or validation scripts).
- A documentation-only commit on a **code-changing PR** can retrigger CI because PR path filters consider the whole PR diff. Avoid a separate push where practical; do not add `[skip ci]` to silence that run if it could strand required checks. Prefer combining the final docs with the next executable checkpoint, or allow the required checks to complete.
- Treat a push as a **validation checkpoint**, not a progress update. Complete a coherent slice of work, run focused local checks when available, and review the effective diff before pushing. With GitHub-only access, prefer preparing a single tree/commit update and opening the PR after that initial checkpoint rather than pushing once per file. Do not enforce arbitrary commit or time quotas.
- Before superseding an in-progress PR run, ask whether the new change is substantial enough to invalidate its results. Batch minor fixups and documentation changes where feasible; push a known bug fix promptly when existing evidence already proves the run cannot succeed. `cancel-in-progress` prevents obsolete checks from running indefinitely, but cannot recover runner time already spent.
- Read the first failing step, available CI summaries and relevant diagnostic artifacts before changing code or rerunning CI. Separate compilation/test failures, screenshot drift, toolchain problems, cache misses and transient network faults. Avoid no-op commits, speculative retry loops and repeated full CI runs without a new cause or executable revision.
- Prefer the narrowest relevant test during iteration. When a run fails, classify the first failure (test assertion, product regression, infrastructure, stale baseline, or unrelated) and rerun only when the cause or changed state justifies it. A rerun cannot substitute for diagnosing an actual test failure.
- Before merge, verify that the **latest executable/testable state** has passing evidence for the relevant required checks. Documentation-only commits after that state do not change executable inputs, but GitHub skip instructions or path filters may leave required checks pending; inspect branch-protection and PR status and use the documented validation path when needed, rather than masking the missing check.
- A commit message with `[skip ci]` does not itself establish successful validation, and required check behavior depends on workflow trigger configuration. Never skip to conceal failures or bypass final review.
- For screenshot mismatches using only GitHub connector access, follow [GitHub-only reviewed screenshot promotion](development.md#github-only-reviewed-screenshot-promotion); do not redraw or force-update references.
- Prefer Squash Merge when PR history is mostly WIP, experiments, fixups, or reversions. Preserve individual commits only when they are intentionally useful for history, revert, or bisect.
- Do not carry `[skip ci]` into the final squash commit message.

## Versioning

- `versionName` follows Semantic Versioning; apply the semantics appropriate to the current major version.
- New pre-releases use `X.Y.Z-alpha`, `X.Y.Z-beta`, or `X.Y.Z-rc`, without a numeric suffix. Use a new base version for another release in the same stage; keep historical numbered versions unchanged.
- Android `versionCode` must increase monotonically for published application IDs.
- Published app versions and Android codes are allocated by the guarded release workflow. Never reuse a published tag or mutate its APK assets. The legacy `main` branch is not the Stable release source.
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
