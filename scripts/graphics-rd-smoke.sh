#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Disposable graphics R&D only. Bounded tests; never a release gate.
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
  echo "Expected one instrumentation runner" >&2
  exit 1
fi

capture_case_logcat() {
  local label="$1"
  adb logcat -d -v threadtime > "ci-artifacts/graphics-rd/$label-logcat.txt" 2>&1 || true
}

diagnose_hang() {
  local label="$1"
  adb shell ps -A > "ci-artifacts/graphics-rd/$label-processes.txt" 2>&1 || true
  # SIGQUIT requests Java thread stacks. Permission/driver behavior may vary.
  local app_pid
  app_pid=$(adb shell pidof io.github.h3nb.jlmodplus.debug 2>/dev/null | tr -d '\r' || true)
  for pid in $app_pid; do adb shell kill -3 "$pid" 2>/dev/null || true; done
  sleep 2
  capture_case_logcat "$label"
  adb shell am force-stop io.github.h3nb.jlmodplus.debug || true
}

run_case() {
  local selector="$1"
  local label="$2"
  local log="ci-artifacts/graphics-rd/$label.txt"
  echo "Executing $selector"
  adb logcat -c || true
  # An isolated case must not strand a CI runner or hide the remaining evidence.
  if ! timeout --signal=TERM --kill-after=5s 75s \
      adb shell am instrument -w -r -e class "$selector" "$runner" > "$log" 2>&1; then
    echo "Failure or timeout during $selector" >&2
    cat "$log"
    diagnose_hang "$label"
    return 1
  fi
  cat "$log"
  if ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$log" ||
     ! grep -q 'INSTRUMENTATION_CODE: -1' "$log"; then
    echo "Missing actual successful instrumentation evidence for $selector" >&2
    capture_case_logcat "$label"
    return 1
  fi
  capture_case_logcat "$label"
}

run_case io.github.h3nb.jlmodplus.research.GraphicsPipelineResearchTest fbo-two-pass
run_case javax.microedition.lcdui.GraphicsCanvasStateTest lcd-ui-canvas-state
run_case javax.microedition.lcdui.GraphicsBitmapRebindTest lcd-ui-rebind
# Isolate native cases to avoid contaminating one test with a prior EGL state.
run_case 'javax.microedition.m3g.M3GRuntimeTest#rejectsInvalidBindTargetHintsAtPublicApiBoundary' m3g-invalid-flags
run_case 'javax.microedition.m3g.M3GRuntimeTest#translatedGraphicsTargetPreservesViewportAndClearOutput' m3g-viewport-clear
run_case 'javax.microedition.m3g.M3GRuntimeTest#image2DTargetClearRoundTripsThroughBackgroundRendering' m3g-image2d
run_case javax.microedition.m3g.M3GEglOwnershipTest m3g-egl-ownership
# The original all-class run stalled after entering this method. Probe last.
run_case 'javax.microedition.m3g.M3GRuntimeTest#immediateRenderingExecutesOn64BitNativeBackend' m3g-immediate

echo "All selected graphics R&D cases executed successfully."
