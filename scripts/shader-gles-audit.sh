#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

mkdir -p ci-artifacts/shader-audit
collect() {
  adb logcat -d -v threadtime > ci-artifacts/shader-audit/logcat.txt 2>&1 || true
  if ! adb exec-out run-as io.github.h3nb.jlmodplus.debug tar -C files -cf - shader-audit \
      > ci-artifacts/shader-audit/renderer-images.tar; then
    rm -f ci-artifacts/shader-audit/renderer-images.tar
    echo 'Unable to collect shader screenshots; inspect instrumentation logs.' >&2
  fi
}
trap collect EXIT

chmod +x gradlew
./gradlew --daemon --stacktrace --console=plain \
  -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest \
  2>&1 | tee ci-artifacts/shader-audit/gradle.txt

test_apk="$(find app/build/outputs/apk/androidTest -type f -name '*.apk' | sort | head -n 1)"
test -n "$test_apk"
adb install -r -t "$test_apk"

runner="$(adb shell pm list instrumentation | tr -d '\r' | sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')"
test -n "$runner"
selector='io.github.h3nb.jlmodplus.gles.BuiltinShaderAuditTest'
log='ci-artifacts/shader-audit/instrumentation.txt'
adb shell am instrument -w -r -e class "$selector" "$runner" > "$log" 2>&1 || {
  cat "$log"
  exit 1
}
cat "$log"
python3 scripts/runtime-smoke-results.py \
  --log "$log" --output ci-artifacts/shader-audit/verified.txt "$selector"
