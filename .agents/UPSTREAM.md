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

### Known upstream caveat: R8 analyzer scripts

At the pinned revision, `r8-analyzer/SKILL.md` refers to `.agents/skills/r8-analyzer/scripts/convert_pb_to_json.py` and `scripts/analyze.py`, but the `performance/r8-analyzer` source tree does not contain those scripts.

For JL-Mod Plus, the missing scripts are a failed prerequisite, not an instruction to recreate them. If those exact scripts are still absent from the verified upstream revision in use:

- do not fabricate or approximate the scripts;
- do not claim that the scripted Path A analysis was completed;
- use the skill's non-scripted heuristic analysis path instead, based on the available Gradle/R8 configuration and vendored references;
- if a future verified upstream revision supplies the scripts, review that revision first and vendor or retrieve the exact upstream files before using the scripted path.

## Project interpretation for coding agents

Apply [AGENTS.md](../AGENTS.md) before generic upstream workflow defaults. These local interpretations do not change the pinned upstream files:

- XML-to-Compose Step 3 requests plan approval. An explicit request to migrate a bounded UI already authorizes the local implementation; present the approach and continue within that scope. Choose an unspecified candidate from current evidence when the choice is routine. For Step 4, use available repository screenshots or emulator captures before requesting an upload. Report unavailable visual evidence.
- XML-to-Compose baseline guidance says to fix pre-existing build failures before migration. Diagnose whether a failure affects the requested change; repair it only when necessary and within scope, otherwise record the limitation and use the relevant validation that remains available.
- AGP 9's Android Studio Upgrade Assistant prerequisite does not override the project's CLI-first workflow. An AGP migration request authorizes the scoped version and compatibility changes; inspect current Gradle, AGP, JDK, and Kotlin constraints, then perform and validate the migration without pausing for Android Studio.
- Adaptive Step 3.3 requests user verification of screenshots. Inspect the rendered output yourself and provide reviewable artifacts; ask for a product decision when visual intent remains ambiguous. Do not accept references without inspection.
- Adaptive instructions to add Navigation 3 scenes or experimental Grid apply only when those choices solve the requested layout problem within the current stack. Prefer a stable, simpler layout when it meets the requirement; ask about an experimental API only if its use is necessary and materially changes the product or compatibility decision.
- Navigation 3's migration guide assumes a specific Navigation 2 architecture and requests confirmation when its assumptions or recipes differ. Inspect the actual app structure and supported contracts, choose a suitable incremental or atomic approach within the user's request, and ask only when an unresolved product or compatibility choice materially changes the result.
- Android Profiler's intent and workflow-selection prompts should be read alongside the user's actual request and current evidence. Infer routine targets, locate existing traces before requesting uploads, and execute authorized composite work without renewed confirmation; ask only when ambiguity materially changes the recording, target, or result.
- Testing setup describes a broad installation sequence and asks whether to document its findings. Apply only the infrastructure needed for the requested task, retain the existing stack, and document relevant changes in dedicated project documentation. Do not put transient testing state into AGENTS.md.
- Compose, target-SDK, and Navigation 3 prerequisites remain scope constraints. A missing prerequisite does not authorize an unrelated migration. The R8 script fallback above remains applicable.

## Updating these skills

Update skills intentionally, not as background dependency churn:

- review upstream changes first;
- update only skills relevant to the current work;
- update this pinned revision when vendored entry points or reference files change;
- preserve the upstream license and attribution;
- audit the skill diff for new prerequisites, experimental APIs, or instructions that conflict with `AGENTS.md`.
