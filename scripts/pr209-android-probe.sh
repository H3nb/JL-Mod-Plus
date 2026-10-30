#!/usr/bin/env bash
# One-off diagnostic, removed before merge. Capture the cause of the test-runner crash.
set -euo pipefail
mkdir -p ci-artifacts/pr209-diagnostics
out=ci-artifacts/pr209-diagnostics
capture() {
  adb logcat -d -v threadtime -b all > "$out/logcat.txt" 2>&1 || true
  adb shell dumpsys activity exit-info io.github.h3nb.jlmodplus.debug > "$out/exit-info.txt" 2>&1 || true
  adb shell dumpsys activity processes > "$out/processes.txt" 2>&1 || true
  echo '===== crash-oriented logcat excerpts ====='
  grep -E -i -A 30 'FATAL EXCEPTION|AndroidRuntime|Fatal signal|Process .* has died|force finishing|crash|assertionfailed|uncaught|instrumentation' "$out/logcat.txt" | tail -n 250 || true
}
trap capture EXIT
chmod +x gradlew
./gradlew --daemon --stacktrace -PjlmodRuntimeTestAbi=x86_64 :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
mapfile -t apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort)
if (( ${#apks[@]} != 1 )); then
  printf 'Expected one test APK, found %d\n' "${#apks[@]}" >&2
  exit 1
fi
adb install -r -t "${apks[0]}"
adb shell pm list instrumentation | tr -d '\r' > "$out/registered-runners.txt"
mapfile -t runners < <(sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p' "$out/registered-runners.txt")
if (( ${#runners[@]} != 1 )); then
  cat "$out/registered-runners.txt"
  exit 1
fi
adb logcat -c || true
runner="${runners[0]}"
clazz=javax.microedition.lcdui.keyboard.VirtualControlsKeyboardResizeTest
run_case() {
  local name="$1" selector="$2" logfile="$out/$1.txt"
  echo "===== Starting $name: $selector ====="
  local rc=0
  timeout 240s adb shell am instrument -w -r -e class "$selector" "$runner" > "$logfile" 2>&1 || rc=$?
  cat "$logfile"
  if (( rc != 0 )) || ! grep -Eq '^OK \([0-9]+ tests?\)' "$logfile"; then
    echo "Instrumentation $name unsuccessful (exit $rc)" >&2
    return 1
  fi
}
run_case baseline "$clazz#newProfileKeepsGroupedControlsDisabled"
run_case editor "$clazz"
