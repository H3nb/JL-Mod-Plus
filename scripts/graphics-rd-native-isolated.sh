#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Disposable R&D: one native test per isolated emulator. Never a production gate.
set -euo pipefail
test -n "$M3G_CASE"
test -n "$M3G_SELECTOR"
mkdir -p ci-artifacts/graphics-native-isolated
dest=ci-artifacts/graphics-native-isolated
git rev-parse HEAD > "$dest/sha.txt"
printf '%s\n' "$M3G_SELECTOR" > "$dest/selector.txt"
./gradlew --daemon --stacktrace -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest

apk=$(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print)
if [ "$(printf '%s\n' "$apk" | sed '/^$/d' | wc -l)" -ne 1 ]; then
  echo "Instrumentation APK count != 1" >&2; exit 2
fi
timeout --signal=TERM --kill-after=2s 15s adb install -r -t "$apk"
runner=$(timeout --signal=TERM --kill-after=2s 8s adb shell pm list instrumentation |
  tr -d '\r' | sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
if [ "$(printf '%s\n' "$runner" | sed '/^$/d' | wc -l)" -ne 1 ]; then
  echo "Expected one instrumentation runner" >&2; exit 2
fi

# Stream logcat BEFORE invoking potentially crash-prone native code.
# Post-failure logcat -d is unreliable when emulator goes offline.
adb logcat -v threadtime -b main -b system -b crash > "$dest/device-stream.log" 2>&1 &
logcat_pid=$!
cleanup() { kill "$logcat_pid" 2>/dev/null || true; wait "$logcat_pid" 2>/dev/null || true; }
trap cleanup EXIT

echo "Start $M3G_CASE at $(date -u +%FT%TZ)"
set +e
timeout --signal=TERM --kill-after=5s 80s \
  adb shell am instrument -w -r -e class "$M3G_SELECTOR" "$runner" > "$dest/instrumentation.txt" 2>&1
code=$?
set -e
echo "Instrumentation exit $code at $(date -u +%FT%TZ)" | tee "$dest/exit.txt"
cat "$dest/instrumentation.txt"
sleep 2
if [ "$code" -ne 0 ] ||
   ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$dest/instrumentation.txt" ||
   ! grep -q 'INSTRUMENTATION_CODE: -1' "$dest/instrumentation.txt"; then
  timeout --signal=TERM --kill-after=2s 5s adb devices -l > "$dest/adb-state.txt" 2>&1 || true
  echo "M3G isolated probe FAILED; examine stream logcat and invocation markers" >&2
  exit 1
fi
echo "M3G isolated probe PASSED"
