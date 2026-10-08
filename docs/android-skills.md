# Android skills: upstream routing and JL-Mod Plus interpretation

This is a task-focused catalog, **not** a local copy of Android skills. The
upstream source is [android/skills](https://github.com/android/skills), which
maintains modular `SKILL.md` instructions, references and workflows. The
repository does not vendor these files or need to synchronize their revisions.

## When and how to use a skill

1. Read [AGENTS.md](../AGENTS.md), the relevant project contract and current
   code first. Only select a skill when its specialized workflow would help
   the requested task; ordinary changes do not require loading a skill.
2. Prefer an already available installed skill or Android CLI integration.
   Otherwise fetch the **exact** upstream `SKILL.md` via GitHub connector,
   web/CLI, or another permitted tool, and follow only its relevant supporting
   references/scripts. Do not fabricate missing resources or assume that
   linking to a skill makes it available offline.
3. For a substantial or reproducibility-sensitive change, record the upstream
   commit SHA used in the task notes or PR evidence. Keep one known reference
   during a controlled investigation; review newer revisions intentionally,
   not silently mid-experiment. Do not create a repository-wide permanent pin.
4. If the tool or network cannot access the skill, continue using project
   contracts, current source, and relevant official Android documentation.
   Report the specific verification gap when it matters. No skill lookup
   should block routine work whose correctness can be established otherwise.
5. Treat upstream skills as **advice**, not authority to adopt dependencies,
   upgrade minSdk/targetSdk, migrate Java ME/Android View ownership, add
   navigation layers, expand test matrices, or pause an already authorized
   task. Project policy and the user's requested scope prevail.

Official installation/update details: [Android skills README](https://github.com/android/skills#install-android-skills)
and [Android developer documentation](https://developer.android.com/tools/agents/android-skills).
Do not install all skills into this repository or commit generated skill copies.

## Task routing

The entries below link to the authoritative upstream skill entry points.
Check the current upstream tree if any link moves.

| Skill | Use when | Official `SKILL.md` |
| --- | --- | --- |
| `agp-9-upgrade` | Gradle/AGP 9 compatibility, built-in Kotlin, migration diagnostics | [Open](https://github.com/android/skills/blob/main/build-system/agp/agp-9-upgrade/SKILL.md) |
| `testing-setup` | Create or materially change testing infrastructure, not ordinary tests | [Open](https://github.com/android/skills/blob/main/testing/testing-setup/SKILL.md) |
| `migrate-xml-views-to-jetpack-compose` | Scoped View/XML-to-Compose migration | [Open](https://github.com/android/skills/blob/main/jetpack-compose/migration/migrate-xml-views-to-jetpack-compose/SKILL.md) |
| `edge-to-edge` | Insets, system bars, cutouts, IME, edge-to-edge | [Open](https://github.com/android/skills/blob/main/system/edge-to-edge/SKILL.md) |
| `navigation-3` | Evaluate or migrate genuinely complex navigation/back stacks | [Open](https://github.com/android/skills/blob/main/navigation/navigation-3/SKILL.md) |
| `adaptive` | Adaptive/multi-pane Compose layouts | [Open](https://github.com/android/skills/blob/main/jetpack-compose/adaptive/SKILL.md) |
| `r8-analyzer` | R8 rules, shrinking, and app-size investigations | [Open](https://github.com/android/skills/blob/main/performance/r8-analyzer/SKILL.md) |
| `android-profiler` | Android profiling, trace capture and Perfetto analysis | [Open](https://github.com/android/skills/blob/main/profilers/android-profiler/SKILL.md) |
| `camerax` | Host Android camera/CameraX integration | [Open](https://github.com/android/skills/blob/main/camera/camerax/SKILL.md) |

This is a curated discoverability list, not an exhaustive list of Android
skills. Find additional official skills when the requested task merits them.

## Project-specific interpretations

- **All skills:** Select only relevant steps and confirm prerequisites against
  the current stack. Missing Compose, Navigation 3, target-SDK, toolchain, DI,
  or test-tool prerequisites do not authorize unrelated migrations.
- **Compose migration:** Preserve runtime/Java ME input, renderer and lifecycle
  boundaries; read [UI ownership](ui-ownership-map.md). Existing approval of a
  bounded migration is sufficient to perform its ordinary steps. Use available
  renderer evidence and diagnose pre-existing build failures proportionately.
- **AGP 9:** CLI-first development is supported; Android Studio Upgrade
  Assistant is not a mandatory prerequisite. Validate the actual Gradle/AGP,
  JDK, Kotlin and dependency constraints.
- **Adaptive:** Never require whole-app Compose conversion or Navigation 3
  merely to make a scoped window-size adjustment. Choose experimental panes,
  Grid, or scenes only when justified. Screen coverage and screenshot
  acceptance follow [Visual verification](development.md#visual-verification-and-screenshot-references).
- **Edge-to-edge:** An affected screen does not imply permission to migrate
  every Activity. Bound changes to the affected host and relevant insets.
- **Navigation 3:** Generic Navigation 2 migration assumptions may not match
  JL-Mod Plus. Do not introduce a second back stack for existing pager/filter
  state; preserve observable navigation contracts.
- **Android profiler:** Use the requested scenario and current evidence to
  infer routine targets; find existing recordings first. Report measurement
  scope and distinguish diagnostic traces from device FPS claims.
- **Testing setup:** Choose tests by failure mode and the lowest effective
  layer under [Testing strategy](development.md#testing-strategy). Do not add
  Hilt/DI, interface/default pairs, Dropshots, JaCoCo, exhaustive screenshot
  matrices, or target coverage percentages merely because a recipe suggests
  them. Document material testing infrastructure changes.
- **CameraX:** Treat layering and controller/ViewModel blueprints as examples.
  Use only boundaries justified by the actual Android host feature and
  preserve Java ME API/JSR semantics independently.
- **R8 analyzer:** Analysis-only tasks produce findings; an explicitly
  authorized implementation may include fixes. Verify any referenced
  upstream scripts exist in the actual revision before claiming or using
  scripted analysis; do not invent them.

## Provenance and maintenance

Upstream content remains under its own license and attribution, documented by
[android/skills](https://github.com/android/skills/blob/main/LICENSE.txt).
Links to upstream instructions are not bundled project dependencies. Review
relevant upstream changes as needed for an actual task; do not repeatedly update
a local snapshot or turn a general skill recipe into permanent project policy.
