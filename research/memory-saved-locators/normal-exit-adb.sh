#!/usr/bin/env bash
# Disposable host-side normal-exit experiment, following runtime-shutdown-smoke.ps1.
# The production Exit must kill AndroidJUnitRunner itself; only external ADB can
# observe all processes dying and launch the independent verification phase.
set -euo pipefail
APP=io.github.h3nb.jlmodplus.debug
TEST_APP=${APP}.test
RUNNER="${TEST_APP}/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS=io.github.h3nb.jlmodplus.memory.MemoryIpcRuntimeTest

processes() {
  adb shell ps -A -o PID,NAME | tr -d '\r' |
    awk -v app="$APP" '$2 == app || index($2, app ":") == 1'
}

diagnostics() {
  local status=$?
  if (( status != 0 )); then
    echo "---- Normal-exit experiment failure diagnostics ----"
    processes || true
    adb logcat -d -b crash -v threadtime 2>/dev/null | tail -n 100 || true
    adb logcat -d -v threadtime -t 2500 2>/dev/null |
      grep -E 'SavedLocatorRND|AndroidRuntime|EmulatorShutdown|MidletKeepAlive|MidletThread|MemoryTargetBridgeService|FATAL EXCEPTION' |
      tail -n 140 || true
  fi
}
trap diagnostics EXIT

# Gradle's connectedAndroidTest can uninstall the tested application after its
# runner finishes. This host-owned phase must reinstall the exact APK it just
# built before invoking a separate instrumentation session.
target_apk=$(find app/build/outputs/apk/emulator/debug -maxdepth 1 -type f -name '*.apk' -print -quit)
if [[ -z "$target_apk" ]]; then
  echo "Missing built emulatorDebug APK for normal-exit verification" >&2
  exit 1
fi
adb install -r "$target_apk"
adb shell pm path "$APP" | grep -q 'package:'

if ! adb shell pm path "$TEST_APP" | grep -q 'package:'; then
  apk=$(find app/build/outputs/apk/androidTest -name '*.apk' -type f | head -n 1)
  test -n "$apk"
  adb install -r "$apk"
fi

echo "---- Stage 1: capture locator and invoke the production Exit in instrumented main ----"
# Normal Exit terminates AndroidJUnitRunner, so a non-zero process exit is
# expected. Trust a checkpoint + externally observed shutdown, not runner OK.
set +e
prepare_output=$(adb shell am instrument -w -r \
  -e savedLocatorNormalExit true \
  -e class "${TEST_CLASS}#researchPrepareNormalExit" "$RUNNER" 2>&1)
prepare_status=$?
set -e
printf '%s\n' "$prepare_output"
echo "Prepare instrumentation status: $prepare_status (shutdown can kill runner)"
grep -Fq 'shutdownRequested=saved-locator-exit' <<< "$prepare_output"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2' <<< "$prepare_output"; then
  echo "Preparation failed before the intended shutdown" >&2
  exit 1
fi

echo "---- Host observes the whole application process family stop ----"
stopped=0
for ((i=0; i<100; i++)); do
  active=$(processes)
  if [[ -z "$active" ]]; then stopped=1; break; fi
  sleep 0.2
done
if (( stopped != 1 )); then
  echo "Emulator processes survived intentional Exit: $active" >&2
  exit 1
fi
# Delayed service callbacks must not resurrect the app before verification.
for ((i=0; i<10; i++)); do
  sleep 0.3
  active=$(processes)
  if [[ -n "$active" ]]; then
    echo "Emulator restarted after intentional Exit: $active" >&2
    exit 1
  fi
done
services=$(adb shell dumpsys activity services "$APP")
if grep 'ServiceRecord{' <<< "$services" | grep -Fq "$APP/"; then
  echo "A service survived intentional Exit" >&2
  exit 1
fi

echo "---- Stage 2: new main + new :midlet; Watch without Search; Edit readback ----"
verify_output=$(adb shell am instrument -w -r \
  -e savedLocatorNormalExit true \
  -e class "${TEST_CLASS}#researchVerifyNormalExit" "$RUNNER" 2>&1)
printf '%s\n' "$verify_output"
grep -Fq 'OK (1 test)' <<< "$verify_output"
echo "PASS: normal Exit -> clean process family -> saved locator rebound -> Edit/readback."
