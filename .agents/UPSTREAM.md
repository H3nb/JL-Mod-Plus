# Vendored Android Skills

Source repository: `https://github.com/android/skills`

Pinned upstream revision: `1e5e7ae6138bebd0835d0d5854b0b9adfeed3181` (2026-08-07)

The `SKILL.md` entry points under `.agents/skills/` are sourced from that revision. No project-specific instruction changes are intended; Markdown whitespace may be normalized while vendoring. The pinned upstream revision is the normative source for auditing. Android Skills is licensed under Apache License 2.0; see `.agents/LICENSE.txt`.

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

Only the skill entry points are vendored intentionally. The upstream skills include large reference collections and recipes; copying all of them would add substantial material that is not needed for every task.

When a selected skill references a missing `references/...` or `scripts/...` file:

1. Resolve the path relative to the skill's upstream directory above.
2. Retrieve that exact file from the pinned revision, not from an unpinned moving branch.
3. Use only the reference needed for the current task.
4. Do not invent the missing reference or substitute general memory for it.
5. If the upstream resource itself is missing or inconsistent, report that fact and use a valid fallback path instead of fabricating tooling.

### Known upstream caveat

At the pinned revision, `r8-analyzer/SKILL.md` refers to `.agents/skills/r8-analyzer/scripts/convert_pb_to_json.py` and `scripts/analyze.py`, but the `performance/r8-analyzer` source tree does not contain those scripts. Do not fabricate them. Verify a newer upstream revision before using that scripted path, or use another valid analysis path supported by the task and available tooling.

## Updating these skills

Update skills intentionally, not as background dependency churn:

- review upstream changes first;
- update only skills relevant to the current work;
- update this pinned revision when vendored entry points change;
- preserve the upstream license and attribution;
- audit the skill diff for new prerequisites, experimental APIs, or instructions that conflict with `AGENTS.md`.
