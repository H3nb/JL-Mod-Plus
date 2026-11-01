#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# R&D: isolated Java ME HD target boundary probe per Android emulator.
set -euo pipefail

case "${HD_BOUNDARY_CASE:-}" in
  lcdui) class=io.github.h3nb.jlmodplus.research.HdLogicalTargetBoundaryResearchTest ;;
  mascot) class=io.github.h3nb.jlmodplus.research.HdMascotTargetBoundaryResearchTest ;;
  *) echo "Unknown HD_BOUNDARY_CASE" >&2; exit 2 ;;
esac
dir="ci-artifacts/graphics-rd-hd-boundary/${HD_BOUNDARY_CASE}"
mkdir -p "$dir"
trap 'adb logcat -d -v threadtime -s GraphicsRD_HD_Boundary:I GraphicsRD_Mascot_HD:I TestRunner:I AndroidRuntime:E > "$dir/logcat-filtered.txt" 2>&1 || true' EXIT

git rev-parse HEAD > "$dir/source-commit.txt"
adb shell getprop ro.build.version.sdk > "$dir/android-api.txt"
./gradlew --daemon --stacktrace -PjlmodNativeBuild=false \
  -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest

test_apk=$(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print)
if [ "$(printf '%s\n' "$test_apk" | sed '/^$/d' | wc -l)" -ne 1 ]; then
  echo "Expected one androidTest APK" >&2
  exit 1
fi
adb install -r -t "$test_apk"
runner=$(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
if [ "$(printf '%s\n' "$runner" | sed '/^$/d' | wc -l)" -ne 1 ]; then
  echo "Expected one instrumentation runner" >&2
  exit 1
fi
echo "Run isolated $class"
adb shell am instrument -w -r -e class "$class" "$runner" > "$dir/instrumentation.txt" 2>&1
cat "$dir/instrumentation.txt"
grep -Eq 'OK \([1-9][0-9]* tests?\)' "$dir/instrumentation.txt"
grep -q 'INSTRUMENTATION_CODE: -1' "$dir/instrumentation.txt"
