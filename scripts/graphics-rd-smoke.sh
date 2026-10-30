#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Disposable graphics research. Never make this a release gate.
set -euo pipefail

mkdir -p ci-artifacts/graphics-rd
trap 'adb logcat -d -v threadtime > ci-artifacts/graphics-rd/logcat-final.txt 2>&1 || true' EXIT
git rev-parse HEAD | tee ci-artifacts/graphics-rd/source-commit.txt

./gradlew --daemon --stacktrace -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
adb shell appops set io.github.h3nb.jlmodplus.debug MANAGE_EXTERNAL_STORAGE allow

test_apk=$(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print)
if [ "$(printf '%s\n' "$test_apk" | sed '/^$/d' | wc -l)" -ne 1 ]; then
  echo "Expected exactly one instrumentation APK" >&2
  exit 1
fi
adb install -r -t "$test_apk"

runner=$(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
if [ "$(printf '%s\n' "$runner" | sed '/^$/d' | wc -l)" -ne 1 ]; then
  echo "Expected exactly one instrumentation runner" >&2
  exit 1
fi

run_case() {
  local selector="$1"
  local label="$2"
  local log="ci-artifacts/graphics-rd/$label.txt"
  echo "Running $selector"
  if ! adb shell am instrument -w -r -e class "$selector" "$runner" > "$log" 2>&1; then
    cat "$log"
    return 1
  fi
  cat "$log"
  if ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$log" ||
     ! grep -q 'INSTRUMENTATION_CODE: -1' "$log"; then
    echo "No positive executed-test evidence for $selector" >&2
    return 1
  fi
  adb logcat -d -v threadtime > "ci-artifacts/graphics-rd/$label-logcat.txt" 2>&1
}
# Keep each native/GL context test in a separate instrumented process.
run_case io.github.h3nb.jlmodplus.research.GraphicsPipelineResearchTest fbo-two-pass
run_case javax.microedition.lcdui.GraphicsCanvasStateTest lcd-ui-state
run_case javax.microedition.lcdui.GraphicsBitmapRebindTest lcd-ui-rebind
run_case javax.microedition.m3g.M3GRuntimeTest m3g-runtime
run_case javax.microedition.m3g.M3GEglOwnershipTest m3g-egl-ownership
echo "All selected graphics R&D classes executed and passed."
