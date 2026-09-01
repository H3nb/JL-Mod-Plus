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

## Current-state evidence

- When determining whether a feature, behavior, setting, dependency, architecture, or workaround currently exists, inspect the current repository state first: source, configuration, tests, workflows, and current documentation. Do not search GitHub history by default.
- Pull requests, commits, deleted or historical branches, old issues, discussions, and other historical artifacts are context, not implementation authority. They may describe experiments, reverted work, abandoned designs, or code that no longer exists.
- Treat closed or unmerged PRs, deleted branches, reverted commits, and explicitly experimental work as non-current unless the user specifically asks to recover, compare, or investigate them.
- A merged PR is not automatically current. Treat it as obsolete when its implementation was later reverted, superseded, substantially reconstructed, or no longer exists in the current repository state.
- Issues and roadmaps describe intent or planned work unless the current repository state independently confirms that the implementation exists.
- Do not enumerate or bulk-read PRs, commits, issues, discussions, or branches as a discovery step. Search history narrowly using the exact subsystem, paths, symbols, behavior, or provenance question relevant to the task.
- A historical PR is relevant only when at least one of these applies: the user explicitly names or requests it; a current source file, current document, or active tracking artifact directly points to it; its surviving implementation affects the same active code path under investigation; or it is necessary to explain a current regression or provenance question.
- Before reading a historical PR body or diff in depth, use lightweight metadata or changed-file information when available to confirm that it overlaps the current task. Skip it when the affected subsystem or files are unrelated.
- For ordinary implementation or analysis tasks, inspect no more than 3 historical PRs and no more than 5 individual historical commits by default. Exceed this budget only when the user explicitly requests broader history or when a necessary ambiguity cannot be resolved from current-state evidence and the initial history budget; state why additional history is needed.
- The historical-artifact budget does not apply when the user's explicit task is to review a specific PR, compare named historical changes, or perform a dedicated repository-history audit.
- Stop historical exploration as soon as sufficient evidence exists to answer or implement the current task; do not continue reading history merely for completeness.
- Consult history only when the task explicitly requires historical/provenance analysis or when current-state evidence is insufficient to answer a necessary question. When history is used, label it as historical and revalidate relevant conclusions against the current repository state before recommending or editing anything.
- If the user identifies prior work as obsolete, abandoned, experimental, or intentionally removed, exclude it from current-state reasoning unless the user explicitly asks to revisit it.

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

## App-owned UI, adaptation, and navigation

- Build new or materially changed app-owned presentation with Jetpack Compose Material 3 and the project theme/shared components. Keep intentional Android View, renderer, input, emulator, and Java ME API boundaries native when they still own platform or compatibility behavior.
- Derive layout decisions from the current container constraints, window size, and insets rather than device names or orientation alone. A compact landscape window remains compact; a portrait tablet may already have room for a larger layout.
- Verify every affected UI path from the supported minimum through target Android behavior, including compact, medium, and expanded widths; short and tall windows; portrait and landscape; light and dark themes; large text; system bars, cutouts, gesture navigation, and the IME where relevant.
- Use adaptive components or multiple panes only when simultaneous content provides a concrete usability benefit. Preserve a clear single-pane fallback, readable line lengths, and stable selection when the window changes size.
- Use Navigation 3 only for real destinations with distinct content and meaningful back history, deep links, results, or adaptive scenes. Do not add it for pager tabs, filter/sort state, dialog visibility, or a controller-owned hierarchy whose current state already defines the screen.
- Give each Navigation 3 route key-specific content and one authoritative owner for navigation state. Route keys must be stable and saveable; avoid parallel mutable back stacks, duplicate destination state, and multiple stacks unless the product flow actually requires them.
- Use Material 3 adaptive Navigation 3 scenes for verified list-detail or supporting-pane behavior. Test compact back navigation, selection restoration, process recreation, and the transition between single- and multi-pane windows.
- Use `AdaptiveAlertDialog` for short Material 3 decisions and `adaptiveDialogLayout()` for custom Compose modal surfaces. Keep the shared adaptive width policy, let height wrap content, and bound only overflowing bodies with a lazy container or `verticalScroll` so titles and actions remain reachable.
- Keep `DropdownMenu` for short anchored choices. Use a custom constrained dialog or a full-screen destination for long forms; do not change presentation type merely because orientation changes. Modal scrims must cover the owning surface, hide underlying semantics, preserve Back/outside-dismiss policy, and remain usable with the IME and 200% text.
- Keep touch targets at least 48dp where practical, expose labels/state/actions to accessibility services, and avoid duplicate focus targets by putting selection/toggle semantics on the containing row while making its indicator inert.
- Keep UI work economical on low-end devices: move I/O and expensive transforms off the main thread, use lazy containers and stable keys for long data sets, remember derived work with complete keys, and avoid per-frame persistence, duplicate state, or decorative effects that trigger broad recomposition.
- Add the narrowest regression coverage for every changed branch. Include screenshots for materially different size/theme layouts and focused interaction tests for navigation Back/restore, modal overflow, dismissal, action reachability, and accessibility semantics. Inspect rendered output before accepting new screenshot references.

## Library database evolution

For Room Library schema changes, follow `docs/library-schema-evolution.md` and preserve the migration contract:

- Keep every exported historical schema snapshot and add an adjacent `N -> N+1` migration to `LibraryMigrations.ALL` whenever `LibraryDatabase.SCHEMA_VERSION` changes.
- Never use destructive migration as a shortcut for the Library database. Favorites, custom metadata, Collections, play stats, receipts, and future Library-only state may not be reconstructible from the workdir.
- Adding, renaming, removing, or merging columns/tables must migrate existing user-owned state deterministically. For destructive structural edits, use a table-copy migration compatible with the project's minimum Android/platform SQLite baseline rather than assuming modern `DROP COLUMN`/`RENAME COLUMN` support.
- Run the migration tests from historical schemas to latest. A schema version bump without a complete tested migration chain is not ready to merge.
- A missing/reset database may rebuild reconstructible catalog data from the workdir, but database deletion is not an upgrade/migration mechanism and must never delete or rewrite app/config/save files.

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
- Mark inherited Apache-2.0 files that are modified with a neutral modification notice without implying ownership of upstream work.
- Do not mass-add project or maintainer copyright claims to inherited files.
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

- Use the narrowest relevant test/build while iterating; derive exact commands and ABI scope from the current repository configuration rather than this file.
- Prefer debug validation unless release, signing, shrinking, R8, or distribution behavior is specifically under test.
- Do not run `clean` routinely.
- Add focused regression or characterization tests for compatibility-sensitive changes when practical.
- If validation cannot be run, state exactly what remains unverified.
- Before handoff, review the final diff for unrelated changes, dead code, temporary workarounds, licensing issues, and unintended behavior changes.
- Report concisely what changed, what was validated, and any remaining limitation or uncertainty.
