#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Fast GLES-only research: no J2ME native renderer required.
set -euo pipefail
mkdir -p ci-artifacts/graphics-rd-fast
trap 'adb logcat -d -v threadtime > ci-artifacts/graphics-rd-fast/logcat-final.txt 2>&1 || true' EXIT
git rev-parse HEAD > ci-artifacts/graphics-rd-fast/source-commit.txt

# Resolve real, unmodified Libretro inputs at a pinned upstream revision.
# Download only into the disposable androidTest APK; do not vendor shaders.
libretro_ref=cba01a48b0974a1232bea68d999a7c21bc4c233e
asset_dir=app/src/androidTest/assets/graphics-rd
mkdir -p "$asset_dir"
for name in stock.glsl bilinear.glslp \
  blurs/gauss_4tap.glslp \
  blurs/shaders/gauss_4tap/gauss_2tap_h.glsl \
  blurs/shaders/gauss_4tap/gauss_2tap_v.glsl; do
  mkdir -p "$asset_dir/$(dirname "$name")"
  curl --fail --show-error --silent --location --retry 2 \
    "https://raw.githubusercontent.com/libretro/glsl-shaders/$libretro_ref/$name" \
    -o "$asset_dir/$name"
  test -s "$asset_dir/$name"
done

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
  'io.github.h3nb.jlmodplus.research.LegacyEs1ContextResearchTest|es1-fixed-function' \
  'io.github.h3nb.jlmodplus.research.SupersamplingSemanticsResearchTest|lcd-ui-hd-logic' \
  'io.github.h3nb.jlmodplus.research.TextureUpdateResearchTest|texture-update' \
  'io.github.h3nb.jlmodplus.research.RealLibretroPresetResearchTest|real-glslp'; do
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
echo "All selected GLES, pixel, and texture-update probes executed successfully."
