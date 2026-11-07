# CI efficiency experiments

Status: **research only**. This branch is not an approved CI redesign. Keep the
production `alpha` workflow and required `build` check unchanged until a measured
improvement passes review.

## Purpose and constraints

Reduce (1) time until required PR checks finish, (2) time until a testable APK is
available, and (3) total runner-minutes per accepted PR. Do not trade away
coverage, reproducibility, native ABI correctness, screenshot verification, or
the final green required check.

The current Android CI runs Build and tests, Screenshot validation, and Lint in
parallel. The build job uploads an **unverified** debug APK before its remaining
tests complete. Preserve this separation when considering changes to job layout.

## Existing evidence (7 November 2026)

| PR workflow run | Build and tests job | Screenshot job | Lint job |
| --- | ---: | ---: | ---: |
| [42357078480](https://github.com/H3nb/JL-Mod-Plus/actions/runs/42357078480) | 162 s | 203 s | 199 s |
| [42358157979](https://github.com/H3nb/JL-Mod-Plus/actions/runs/42358157979) | 222 s | 252 s | 345 s |
| [42360078522](https://github.com/H3nb/JL-Mod-Plus/actions/runs/42360078522) | 211 s | 259 s | 338 s |

These runs were on **different PR revisions and runners**. They demonstrate
variance; they do not prove the cause. Two slow lint runs took approximately
314 and 312 seconds within Gradle; one fast run took 176 seconds. KSP, Kotlin
compilation, and lint analysis all slowed on the slower runs. Restore of
dependency/build caches succeeded; no cache corruption was established.

## Run isolated manual probes

Use JDK 21, SDK platform 37 and NDK r30 when native is enabled. Use a clean,
fresh checkout/runner **per sample** so that `UP-TO-DATE` and Configuration
Cache hits from a previous scenario do not invalidate A/B comparisons.

From the repository root:

```sh
bash scripts/ci-perf-experiment.sh lint-native
bash scripts/ci-perf-experiment.sh lint-no-native
bash scripts/ci-perf-experiment.sh lint-workers2
bash scripts/ci-perf-experiment.sh lint-workers3
bash scripts/ci-perf-experiment.sh build-native
bash scripts/ci-perf-experiment.sh screenshots
```

**Do not run all six commands sequentially in one checkout and interpret them
as an A/B benchmark.** Instead select one scenario per fresh runner, repeat
2–3 times where budget allows, and compare results from the same commit.
The script records elapsed time, Gradle console output, task-cache counters,
runner metadata, XML lint results when available, and Gradle HTML profiles in
`build/reports/ci-experiments/`. It does not run automatically on push.

### Experiment A — lint without native configuration

Compare `lint-native` and `lint-no-native` at the same commit, toolchain,
cache state, and comparable runner capacity. Check both **task graph** and
report parity; do not assume native-disabled lint has identical coverage.

For example, after collecting the reports in two separate result folders:

```sh
python3 - LINT_NATIVE_XML LINT_NO_NATIVE_XML <<'PY'
import collections
import sys
import xml.etree.ElementTree as ET

def issues(path):
    result = collections.Counter()
    for issue in ET.parse(path).getroot().findall("issue"):
        locations = tuple(
            (loc.get("file"), loc.get("line"), loc.get("column"))
            for loc in issue.findall("location")
        )
        result[(issue.get("id"), issue.get("severity"),
                issue.get("message"), locations)] += 1
    return result

before, after = map(issues, sys.argv[1:3])
print(f"Native: {sum(before.values())}; non-native: {sum(after.values())}")
print("Removed:", list((before - after).elements())[:10])
print("Added:", list((after - before).elements())[:10])
sys.exit(0 if before == after else 1)
PY
```

Normalize workspace-specific path prefixes when comparing different runners.
A matching issue count **alone** is insufficient; compare signatures and
confirm relevant checks still cover the same app and library source sets.
Only then consider removing NDK provisioning from the lint job.

### Experiment B — worker count and runner variance

Compare `lint-no-native` against `lint-workers2` and `lint-workers3`.
Track elapsed wall time and Gradle task durations, not just aggregate
`Task Execution` time (tasks can overlap). Repeated measurements are needed:
GitHub-hosted runners can vary independently of source changes. Keep
`org.gradle.jvmargs` unchanged until there is evidence from CPU/heap/GC
profiling. Do not hardcode a region inferred to be faster from a small sample.

### Experiment C — compilation and native build-cache hotspots

Use `build-native` and its Gradle profile to inspect
`:app:buildNdkBuildDebug[arm64-v8a]`, native audio dependency tasks, KSP,
Kotlin compilation, and DEX. Record task outputs: executed, `FROM-CACHE`,
`UP-TO-DATE`, and non-cacheable. When required, investigate misses with Gradle
`--info` on a separate diagnostic run. **Never cache `.cxx` or arbitrary
native build outputs without proving toolchain, input, ABI and output correctness.**

### Experiment D — cache scope across PRs

The default branch is `alpha`. PR merge refs have restricted GitHub cache
visibility; an internal PR writing a cache does not automatically warm every
other PR. Examine setup-gradle summaries and cache hit/miss rates for **two
separate PRs** before adding a base-branch warming job. Compare the saved
runner time against the added run's cost, including cache storage churn.
Do not add full CI after every merge merely to warm caches. Configuration
Cache persistence on ephemeral CI is not the primary bottleneck demonstrated
by the current profiles.

### Experiment E — workflow critical path

Preserve three parallel PR jobs initially. Record:

- total run duration until the required `build` check completes;
- elapsed time from job start to early APK artifact upload;
- total runner-minutes, including cancelled runs and the final aggregation job;
- status and completeness of actual JVM tests, lint, screenshots, and
  instrumentation compilation.

A merged sequential job only qualifies if it improves the measured critical
path **and** keeps the early testable APK available at least as promptly.
Do not infer wall-clock savings from summing parallel task durations.

### Experiment F — AI agent push/skip discipline

Proposed policy to validate **before** editing production `AGENTS.md` or
`docs/agent-workflow.md`:

1. Batch coherent code edits and pre-push focused checks; push a meaningful
   validation checkpoint rather than a separate commit/push for every fix.
2. Never use `[skip ci]` for code, resources/assets, tests, build/dependency
   configuration, workflow logic, or validation scripts.
3. For a documentation-only PR, let the current path filter handle it, but
   inspect branch protection/required checks before assuming it can merge.
4. For **documentation-only commits within a code-changing PR**, do not
   automatically use `[skip ci]`: PR-level diff evaluation and skipped
   required checks can leave a PR pending. Prefer batching the docs with the
   next validated checkpoint.
5. The latest executable/testable state must have passing required checks.
   Skipped, cancelled, or merely compiled tests never count as passing.
6. Do not conceal a failure by skipping a later run or generating a no-op
   commit. Retain `cancel-in-progress` for obsolete PR revisions.

Automatic selective CI based on file categorization is **out of scope** for
the first experiments. It requires a fail-closed classifier and an always
reported required `build` status; an incorrect skip can cause false-green
merges or blocked PRs.

## Experimental budget and acceptance rule

- Work on this research branch without opening a PR until there is a bounded
  experiment worth validating. The existing `Android CI` does not run simply
  because this branch receives a push.
- For a first pass, collect one sample per lint scenario, and only repeat the
  promising comparison to account for runner variation. Avoid automatic
  matrices and unsolicited manual reruns.
- Accept only a change that preserves diagnostics/coverage/ABI behavior,
  demonstrably improves at least one objective, and does not materially worsen
  the others. Report all failed or inconclusive comparisons.
- Promote individually justified changes in a small PR; remove temporary
  probes rather than shipping a permanent experimental workflow.

## Results

No new experiment runs have been executed on this branch. The timings above
are historical baselines, not performance claims for these scenarios.
