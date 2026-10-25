#!/usr/bin/env bash
set -euo pipefail
chmod +x gradlew
./gradlew --daemon --stacktrace -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print)
[[ ${#test_apks[@]} -eq 1 ]]
adb install -r -t "${test_apks[0]}"
mapfile -t runners < <(adb shell pm list instrumentation | tr -d '\r' | sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
[[ ${#runners[@]} -eq 1 ]]
mkdir -p ci-artifacts/color-picker ci-artifacts/color-picker/screenshots
selectors=(
  'io.github.h3nb.jlmodplus.config.ColorPickerVisualProbeTest#capturePickerInBothDynamicStates'
  'io.github.h3nb.jlmodplus.config.ConfigComposeTest#dynamicBackgroundPickerPreservesManualHex'
  'io.github.h3nb.jlmodplus.config.ConfigComposeTest#dynamicBackgroundPickerCanReturnToManualEditingWithoutLosingColor'
)
for orientation in portrait landscape; do
  # Match the logical viewport, not just the display rotation setting.
  # The headless emulator can ignore a rotation request and remain portrait.
  viewport="360x720"
  if [[ "$orientation" == landscape ]]; then viewport="720x360"; fi
  adb shell cmd window user-rotation lock 0
  adb shell wm size "$viewport"
  adb shell wm size
  sleep 3
  log="ci-artifacts/color-picker/${orientation}.txt"
  IFS=, selector_arg="${selectors[*]}"
  adb shell am instrument -w -r -e class "$selector_arg" "${runners[0]}" > "$log" 2>&1 || { cat "$log"; exit 1; }
  cat "$log"
  python3 scripts/runtime-smoke-results.py --log "$log" --output ci-artifacts/color-picker/passed.txt "${selectors[@]}"
done
adb pull /sdcard/Android/data/io.github.h3nb.jlmodplus.debug/files/color-picker-probe/ ci-artifacts/color-picker/screenshots/
test -f ci-artifacts/color-picker/screenshots/color-picker-probe/portrait-manual.png
test -f ci-artifacts/color-picker/screenshots/color-picker-probe/portrait-dynamic.png
test -f ci-artifacts/color-picker/screenshots/color-picker-probe/landscape-manual.png
test -f ci-artifacts/color-picker/screenshots/color-picker-probe/landscape-dynamic.png
