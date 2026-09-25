# Vendored Android Skills

Source repository: `https://github.com/android/skills`

Pinned upstream revision: `bac232fd02b0855df9275281a2a7a47643768719` (2026-09-07)

The selected `SKILL.md` entry points and their vendored local reference files under `.agents/skills/` are sourced from that revision. No project-specific edits to the vendored upstream content are intended; Markdown whitespace may be normalized while vendoring. The pinned upstream revision is the normative source for auditing. Android Skills is licensed under Apache License 2.0; see `.agents/LICENSE.txt`.

## Selected skills

| Local skill | Upstream path |
| --- | --- |
| `agp-9-upgrade` | `build-system/agp/agp-9-upgrade/SKILL.md` |
| `navigation-3` | `navigation/navigation-3/SKILL.md` |
| `migrate-xml-views-to-jetpack-compose` | `jetpack-compose/migration/migrate-xml-views-to-jetpack-compose/SKILL.md` |
| `adaptive` | `jetpack-compose/adaptive/SKILL.md` |
| `r8-analyzer` | `performance/r8-analyzer/SKILL.md` |
| `edge-to-edge` | `system/edge-to-edge/SKILL.md` |
| `testing-setup` | `testing/testing-setup/SKILL.md` |
| `android-profiler` | `profilers/android-profiler/SKILL.md` |
| `camerax` | `camera/camerax/SKILL.md` |

The vendored set intentionally includes the reference documents needed by these selected skills where those files exist at the pinned revision. It is not a complete mirror of the upstream repository, and upstream assets or scripts that are not needed or do not exist upstream are not fabricated locally.

When a selected skill references a missing `references/...` or `scripts/...` file:

1. Resolve the path relative to the skill's upstream directory above.
2. Check that exact path at the pinned revision before looking anywhere else.
3. If the file exists upstream and is needed for the current task, retrieve that exact file rather than substituting an unpinned or remembered version.
4. Do not invent missing references, scripts, commands, or generated results.
5. If the resource is also missing or inconsistent at the pinned upstream revision, report that fact and use the documented project fallback below.

## Project interpretation for coding agents

These sections record JL-Mod Plus interpretations of the corresponding vendored skills. [AGENTS.md](../AGENTS.md) and [Agent development workflow](../docs/agent-workflow.md#repository-skills) define precedence and routing.

### migrate-xml-views-to-jetpack-compose

- Step 3 requests plan approval. An explicit request to migrate a bounded UI already authorizes the local implementation; present the approach and continue within that scope. Choose an unspecified candidate from current evidence when the choice is routine.
- For Step 4, use available repository screenshots or emulator captures before requesting an upload. Report unavailable visual evidence.
- Baseline guidance says to fix pre-existing build failures before migration. Diagnose whether a failure affects the requested change; repair it only when necessary and within scope, otherwise record the limitation and use the relevant validation that remains available.

### agp-9-upgrade

- The Android Studio Upgrade Assistant prerequisite does not override the project's CLI-first workflow. An AGP migration request authorizes the scoped version and compatibility changes; inspect current Gradle, AGP, JDK, and Kotlin constraints, then perform and validate the migration without pausing for Android Studio.

### adaptive

- Step 3.3 requests user verification of screenshots. Inspect rendered output yourself and provide reviewable artifacts; ask for a product decision only when visual intent remains materially ambiguous.
- JL-Mod Plus intentionally mixes app-owned Compose surfaces with protected View/Java ME/runtime boundaries. The skill's whole-app Compose and Navigation 3 prerequisites do not authorize migration of unrelated or compatibility-sensitive boundaries. Apply adaptive guidance only to the app-owned surface in scope.
- Instructions to add Navigation 3 scenes or experimental Grid apply only when those choices solve the requested layout problem within the current stack. Prefer a stable, simpler layout when it meets the requirement; ask about an experimental API only if its use is necessary and materially changes the product or compatibility decision.

### edge-to-edge

- Workflow steps that scan or migrate every Activity apply only to an app-wide edge-to-edge task. For a scoped UI fix, inspect and change only the affected host and the boundaries needed for correctness.

### navigation-3

- The migration guide assumes a specific Navigation 2 architecture and requests confirmation when its assumptions or recipes differ. Inspect the actual app structure and supported contracts, choose a suitable incremental or atomic approach within the user's request, and ask only when an unresolved product or compatibility choice materially changes the result.

### android-profiler

- Intent and workflow-selection prompts should be read alongside the user's actual request and current evidence. Infer routine targets, locate existing traces before requesting uploads, and execute authorized composite work without renewed confirmation; ask only when ambiguity materially changes the recording, target, or result.

### testing-setup

- This skill is for creating or materially changing testing infrastructure, not for ordinary regression tests that already fit the current stack. Its broad framework/DI installation sequence is not a default migration plan; add infrastructure only when the requested testing capability genuinely requires it. Follow [Testing strategy](../docs/development.md#testing-strategy).

### camerax

- Blueprint examples describe possible decomposition, not mandatory ViewModel/controller/layering. Fit camera work to the existing architecture and add layers only when they establish a needed boundary.

### r8-analyzer

- The report-only/no-code constraint applies to analysis-only requests. If the user explicitly asks to analyze and implement R8 fixes, use the analyzer guidance for diagnosis and then make the authorized scoped changes under [AGENTS.md](../AGENTS.md).
- At the pinned revision, the referenced conversion/analysis scripts are absent. Do not fabricate them or claim scripted analysis completed; use the non-scripted heuristic path. If a future verified upstream revision supplies the scripts, review and vendor the exact upstream files before using that path.

### Shared prerequisite rule

- A missing Compose, target-SDK, Navigation 3, tool, or framework prerequisite is a scope constraint, not authorization for an unrelated migration. Follow the project's current stack and the user's requested scope.

## Updating these skills

Update skills intentionally, not as background dependency churn:

- review upstream changes first;
- update only skills relevant to the current work;
- update this pinned revision when vendored entry points or reference files change;
- preserve the upstream license and attribution;
- audit the skill diff for new prerequisites, experimental APIs, or instructions that conflict with `AGENTS.md`.
