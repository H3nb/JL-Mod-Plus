#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
mkdir -p ci-artifacts/glslp-prototype
trap 'adb logcat -d -v threadtime > ci-artifacts/glslp-prototype/logcat.txt 2>&1 || true' EXIT
git rev-parse HEAD > ci-artifacts/glslp-prototype/source-commit.txt

./gradlew --daemon --stacktrace -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
test_apk=$(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print)
test "$(printf '%s\n' "$test_apk" | sed '/^$/d' | wc -l)" -eq 1
adb install -r -t "$test_apk"
runner=$(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
test -n "$runner"
log=ci-artifacts/glslp-prototype/gpu-smoke.txt
adb shell am instrument -w -r -e class \
  io.github.h3nb.jlmodplus.gles.GlslpGpuSmokeTest "$runner" > "$log" 2>&1 || {
  cat "$log"; exit 1;
}
cat "$log"
grep -Eq 'OK \(8 tests?\)' "$log"
grep -q 'INSTRUMENTATION_CODE: -1' "$log"
