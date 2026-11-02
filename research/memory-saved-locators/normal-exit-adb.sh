#!/usr/bin/env bash
# Disposable external-host experiment: intentional user-stop kills the instrumented app.
set -euo pipefail
APP=io.github.h3nb.jlmodplus.debug
TEST_APP=${APP}.test
RUNNER="${TEST_APP}/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS=io.github.h3nb.jlmodplus.memory.MemoryIpcRuntimeTest

diagnostics() {
  local code=$?
  if (( code != 0 )); then
    echo "---- Normal-exit experiment: logcat / active processes ----"
    adb shell "ps -A | grep 'jlmodplus' || true" || true
    adb logcat -d -b crash -v threadtime 2>/dev/null | tail -n 100 || true
    adb logcat -d -v threadtime -t 2500 2>/dev/null |
      grep -E 'SavedLocatorRND|AndroidRuntime|EmulatorShutdown|MidletKeepAlive|MidletThread|MemoryTargetBridgeService|FATAL EXCEPTION' |
      tail -n 140 || true
  fi
}
trap diagnostics EXIT

# connectedAndroidTest normally installs both packages; do not depend on it retaining the test APK.
if ! adb shell pm path "$TEST_APP" | grep -q 'package:'; then
  apk=$(find app/build/outputs/apk/androidTest -name '*.apk' -type f | head -n 1)
  test -n "$apk"
  adb install -r "$apk"
fi

run_stage() {
  local method=$1
  local output
  output=$(adb shell am instrument -w -r \
    -e class "${TEST_CLASS}#${method}" "$RUNNER")
  printf '%s\n' "$output"
  grep -Fq 'OK (1 test)' <<< "$output"
}

echo "---- Prepare installed-game locator under the first instrumented process ----"
run_stage researchPrepareNormalExit

old_midlet=$(adb shell pidof "${APP}:midlet" | tr -d '\r' || true)
test -n "$old_midlet"
echo "Initial :midlet PID: $old_midlet"

# Run the real application's stop handler as its own UID. The service remains
# non-exported; this is not a shell-UID external service invocation.
echo "---- Request real STOP_EMULATOR from outside the app process ----"
stop_output=$(adb shell run-as "$APP" /system/bin/am startservice \
  -a runtime.STOP_EMULATOR \
  -n "${APP}/io.github.h3nb.jlmodplus.runtime.MidletKeepAliveService" 2>&1)
printf '%s\n' "$stop_output"
if grep -Eiq 'Error:|SecurityException|not exported|Permission Denial|not found' <<< "$stop_output"; then
  echo "STOP_EMULATOR could not be dispatched under app UID" >&2
  exit 1
fi

# The host must observe old guest death; otherwise it has not tested normal exit.
stopped=0
for ((i=0; i<100; i++)); do
  current=$(adb shell pidof "${APP}:midlet" | tr -d '\r' || true)
  if [[ -z "$current" ]]; then stopped=1; break; fi
  sleep 0.2
done
if (( stopped != 1 )); then echo "Old :midlet survived emulator stop" >&2; exit 1; fi
echo "Normal stop removed the prior guest process."

# A fresh am instrument call starts a fresh main process; no manual Search.
echo "---- Relaunch and resolve saved definition with fresh instrumentation ----"
run_stage researchVerifyNormalExit
echo "PASS: normal Exit -> new process -> Saved Watch -> explicit Edit/readback."
