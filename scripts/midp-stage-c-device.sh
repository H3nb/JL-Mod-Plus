#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Temporary virtual-Android validation for PR #185.
set -euo pipefail
mkdir -p ci-artifacts
trap 'adb logcat -d -t 400 > ci-artifacts/logcat-tail.txt 2>&1 || true' EXIT
chmod +x gradlew
./gradlew --daemon -PjlmodRuntimeTestAbi=x86_64 -PjlmodNativeBuild=false \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest -name '*.apk' -type f -print)
if (( ${#test_apks[@]} != 1 )); then
  echo "Expected one Android test APK, found ${#test_apks[@]}" >&2
  exit 1
fi
adb install -r -t "${test_apks[0]}"
runner=$(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
if [[ -z "$runner" ]]; then echo 'Instrumentation runner not available' >&2; exit 1; fi
adb shell am instrument -w -r -e class 'javax.microedition.lcdui.MidpStageCTextDeviceTest' "$runner" |
  tee ci-artifacts/stage-c-device.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -2|Process crashed|No tests found' ci-artifacts/stage-c-device.txt; then
  echo 'Android instrumentation reported failures.' >&2
  exit 1
fi
grep -Fq 'OK (5 tests)' ci-artifacts/stage-c-device.txt
grep -Fq 'INSTRUMENTATION_CODE: -1' ci-artifacts/stage-c-device.txt
grep -Fq 'INSTRUMENTATION_STATUS: class=javax.microedition.lcdui.MidpStageCTextDeviceTest' ci-artifacts/stage-c-device.txt
