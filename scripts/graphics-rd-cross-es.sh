#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Independent GLES1/GLES2 sharing capability probe, per emulator.
set -euo pipefail
dir="ci-artifacts/graphics-rd-cross-es/api${GRAPHICS_ARCH_API}"
mkdir -p "$dir"
trap 'adb logcat -d -v threadtime -s GraphicsRD_CrossES:I AndroidRuntime:E > "$dir/logcat.txt" 2>&1 || true' EXIT
git rev-parse HEAD > "$dir/commit.txt"
adb shell getprop ro.build.version.sdk > "$dir/api.txt"
./gradlew --daemon --stacktrace -PjlmodNativeBuild=false \
  -PjlmodRuntimeTestAbi=x86_64 :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
test_apk=$(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print)
if [ "$(printf '%s\n' "$test_apk" | sed '/^$/d' | wc -l)" -ne 1 ]; then echo 'APK mismatch' >&2; exit 1; fi
adb install -r -t "$test_apk"
runner=$(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
if [ "$(printf '%s\n' "$runner" | sed '/^$/d' | wc -l)" -ne 1 ]; then echo 'Runner mismatch' >&2; exit 1; fi
adb shell am instrument -w -r -e class \
  io.github.h3nb.jlmodplus.research.GlesCrossVersionShareResearchTest \
  "$runner" > "$dir/instrumentation.txt" 2>&1
cat "$dir/instrumentation.txt"
grep -Eq 'OK \([1-9][0-9]* tests?\)' "$dir/instrumentation.txt"
grep -q 'INSTRUMENTATION_CODE: -1' "$dir/instrumentation.txt"
