#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Manual, isolated CI performance probe. No workflow is triggered by this script.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: bash scripts/ci-perf-experiment.sh SCENARIO
Scenarios:
  lint-native          Current lint, native build enabled
  lint-no-native       Lint with -PjlmodNativeBuild=false
  lint-workers2        Non-native lint with --max-workers=2
  lint-workers3        Non-native lint with --max-workers=3
  build-native         Current arm64 APK assembly
  screenshots          Current Compose screenshot validation

Use a fresh checkout/runner for each sample. Reusing a workspace makes Gradle
UP-TO-DATE/Configuration Cache hits invalidate a fair A/B comparison.
Each run writes logs and Gradle profile snapshots under build/reports/ci-experiments/.
USAGE
}

if [[ $# -ne 1 ]]; then
  usage >&2
  exit 2
fi
scenario="$1"
args=(--daemon --stacktrace --profile --continue --configuration-cache --configuration-cache-problems=fail)
case "$scenario" in
  lint-native)
    args+=(-PjlmodNativeBuild=true)
    task=:app:lintEmulatorDebug
    ;;
  lint-no-native)
    args+=(-PjlmodNativeBuild=false)
    task=:app:lintEmulatorDebug
    ;;
  lint-workers2)
    args+=(-PjlmodNativeBuild=false --max-workers=2)
    task=:app:lintEmulatorDebug
    ;;
  lint-workers3)
    args+=(-PjlmodNativeBuild=false --max-workers=3)
    task=:app:lintEmulatorDebug
    ;;
  build-native)
    args+=(-PjlmodNativeBuild=true)
    task=:app:assembleEmulatorDebug
    ;;
  screenshots)
    args+=(-PjlmodNativeBuild=false)
    task=:app:testEmulatorDebugScreenshotTestDefaultTestSuite
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

cd "$(dirname "$0")/.."
mkdir -p build/reports/ci-experiments
out_dir="$(mktemp -d "build/reports/ci-experiments/${scenario}-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
{
  echo "scenario=$scenario"
  echo "commit=$(git rev-parse HEAD)"
  echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "runner_image=${ImageOS:-unknown}"
  echo "runner_region=${RUNNER_REGION:-unknown}"
  echo "cpu_count=$(getconf _NPROCESSORS_ONLN)"
  if [[ -r /proc/meminfo ]]; then
    grep -m 1 '^MemTotal:' /proc/meminfo
  fi
  java -version 2>&1
} > "$out_dir/environment.txt"

touch "$out_dir/start.marker"
started="$(date +%s)"
set +e
./gradlew "${args[@]}" "$task" 2>&1 | tee "$out_dir/gradle.log"
status=${PIPESTATUS[0]}
set -e
finished="$(date +%s)"

{
  echo "scenario=$scenario"
  echo "exit_code=$status"
  echo "elapsed_seconds=$((finished - started))"
  echo "task=$task"
  echo "from_cache_count=$(grep -c ' FROM-CACHE' "$out_dir/gradle.log" || true)"
  echo "up_to_date_count=$(grep -c ' UP-TO-DATE' "$out_dir/gradle.log" || true)"
} > "$out_dir/summary.txt"

if [[ "$task" == ":app:lintEmulatorDebug" ]]; then
  report=app/build/reports/lint-results-emulatorDebug.xml
  if [[ -f "$report" && "$report" -nt "$out_dir/start.marker" ]]; then
    cp "$report" "$out_dir/lint-results.xml"
  fi
fi
if [[ -d build/reports/profile ]]; then
  find build/reports/profile -maxdepth 1 -type f -name 'profile-*.html' \
    -newer "$out_dir/start.marker" -exec cp {} "$out_dir/" \;
fi
echo "Experiment output: $out_dir"
exit "$status"
