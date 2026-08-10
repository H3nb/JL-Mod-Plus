# Vendored Android Skills

Source repository: `https://github.com/android/skills`

Pinned upstream revision: `1e5e7ae6138bebd0835d0d5854b0b9adfeed3181` (2026-08-07)

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

## Updating these skills

Update skills intentionally, not as background dependency churn:

- review upstream changes first;
- update only skills relevant to the current work;
- update this pinned revision when vendored entry points or reference files change;
- preserve the upstream license and attribution;
- audit the skill diff for new prerequisites, experimental APIs, or instructions that conflict with `AGENTS.md`.
