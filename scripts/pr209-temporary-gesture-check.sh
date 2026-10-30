#!/usr/bin/env bash
set -euo pipefail
chmod +x gradlew
./gradlew --daemon --stacktrace -PjlmodRuntimeTestAbi=x86_64 :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
mapfile -t apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort)
if (( ${#apks[@]} != 1 )); then
  printf 'Expected one Android test APK, found %d\n' "${#apks[@]}" >&2
  exit 1
fi
adb install -r -t "${apks[0]}"
mapfile -t runners < <(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
if (( ${#runners[@]} != 1 )); then
  printf 'Expected one test runner, found %d\n' "${#runners[@]}" >&2
  exit 1
fi
log=pr209-gesture-instrumentation.log
if ! adb shell am instrument -w -r -e class javax.microedition.lcdui.keyboard.VirtualControlsKeyboardResizeTest "${runners[0]}" > "$log" 2>&1; then
  cat "$log"
  exit 1
fi
cat "$log"
grep -Eq '^OK \([0-9]+ tests?\)' "$log"
