#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Disposable PR #181 runtime verification; remove this file with its workflow after success.
set -euo pipefail

evidence_dir="ci-artifacts/pr181-runtime"
mkdir -p "$evidence_dir"
trap 'adb logcat -d -v threadtime > "$evidence_dir/logcat.txt" 2>&1 || true' EXIT

./gradlew --daemon --stacktrace --profile -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest

adb shell appops set io.github.h3nb.jlmodplus.debug MANAGE_EXTERNAL_STORAGE allow

mapfile -t apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort)
if (( ${#apks[@]} != 1 )); then
  printf 'Expected one instrumentation APK, found %s\n' "${#apks[@]}" >&2
  exit 1
fi
adb install -r -t "${apks[0]}"

mapfile -t runners < <(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
if (( ${#runners[@]} != 1 )); then
  printf 'Expected one debug instrumentation runner, found %s\n' "${#runners[@]}" >&2
  printf '%s\n' "${runners[@]}" >&2
  exit 1
fi
runner="${runners[0]}"
printf 'Source commit: %s\nAndroid API: %s\n' "$(git rev-parse HEAD)" "$(adb shell getprop ro.build.version.sdk)" |
  tee "$evidence_dir/environment.txt"

# Isolate each lifecycle case: several tests intentionally kill the isolated MIDlet process.
selectors=(
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#canvasCanBecomeShownAndPaintWhileStartAppIsStillRunning'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#hungStartAppCanExitWithoutExecutingDestroyAppConcurrently'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#hungUserExitReturnsToLibraryAndTerminatesWithoutCrashReport'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#androidHomeKeepsLiveRuntimeSelectedForLauncherReturn'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#liveMidletCanYieldLibraryAndReattachSameRuntime'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#explicitDestinationSurvivesKeyEndBackgroundDuringDestroy'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#csiStyleBackgroundThenDestroyReturnsToLibraryOnceAndTerminatesCleanly'
)

: > "$evidence_dir/executed-methods.txt"
for selector in "${selectors[@]}"; do
  class_name="${selector%%#*}"
  method="${selector#*#}"
  report="$evidence_dir/$method.txt"
  printf 'RUN %s\n' "$selector"
  if ! adb shell am instrument -w -r -e class "$selector" "$runner" > "$report" 2>&1; then
    cat "$report"
    printf 'Instrumentation command failed: %s\n' "$selector" >&2
    exit 1
  fi
  cat "$report"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|No tests found' "$report" ||
    ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$report" ||
    ! grep -Fq "INSTRUMENTATION_STATUS: class=$class_name" "$report" ||
    ! grep -Fq "INSTRUMENTATION_STATUS: test=$method" "$report"; then
    printf 'Selected method did not execute successfully: %s\n' "$selector" >&2
    exit 1
  fi
  printf 'PASS\t%s\n' "$selector" | tee -a "$evidence_dir/executed-methods.txt"
done

if (( $(wc -l < "$evidence_dir/executed-methods.txt") != ${#selectors[@]} )); then
  echo 'Executed method count mismatch' >&2
  exit 1
fi
echo 'All selected Android runtime lifecycle methods executed and passed.'
