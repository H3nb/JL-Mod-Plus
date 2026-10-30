#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Fast GLES-only research: no J2ME native renderer required.
set -euo pipefail
mkdir -p ci-artifacts/graphics-rd-fast
trap 'adb logcat -d -v threadtime > ci-artifacts/graphics-rd-fast/logcat-final.txt 2>&1 || true' EXIT
git rev-parse HEAD > ci-artifacts/graphics-rd-fast/source-commit.txt

./gradlew --daemon --stacktrace \
  -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 \
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
for spec in \
  'io.github.h3nb.jlmodplus.research.GraphicsPipelineResearchTest|fbo' \
  'io.github.h3nb.jlmodplus.experiments.ShaderAbiResearchTest|shader-abi' \
  'io.github.h3nb.jlmodplus.research.LegacyEs1ContextResearchTest|es1-fixed-function'; do
  class=$(printf '%s' "$spec" | cut -d'|' -f1)
  label=$(printf '%s' "$spec" | cut -d'|' -f2)
  echo "Executing $class"
  log="ci-artifacts/graphics-rd-fast/$label.txt"
  if ! adb shell am instrument -w -r -e class "$class" "$runner" > "$log" 2>&1; then
    cat "$log"
    exit 1
  fi
  cat "$log"
  grep -Eq 'OK \([1-9][0-9]* tests?\)' "$log" || exit 1
  grep -q 'INSTRUMENTATION_CODE: -1' "$log" || exit 1
  adb logcat -d -v threadtime > "ci-artifacts/graphics-rd-fast/$label-logcat.txt" 2>&1
done
echo "Both GLES2 probes and the independent ES1 fixed-function probe executed successfully."
