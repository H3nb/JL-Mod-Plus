#!/usr/bin/env bash
# Temporary Android runtime contract runner. Remove together with its workflow before PR merge.
set -euo pipefail

./gradlew --daemon --stacktrace --profile -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
adb shell appops set io.github.h3nb.jlmodplus.debug MANAGE_EXTERNAL_STORAGE allow

mapfile -t apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort)
if (( ${#apks[@]} != 1 )); then
  printf 'Expected one test APK, found %s\n' "${#apks[@]}" >&2
  exit 1
fi
adb install -r -t "${apks[0]}"

mapfile -t runners < <(
  adb shell pm list instrumentation |
  tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p'
)
if (( ${#runners[@]} != 1 )); then
  printf 'Expected one instrumentation runner, found %s\n' "${#runners[@]}" >&2
  exit 1
fi
runner="${runners[0]}"
mkdir -p ci-artifacts/lcdui
: > ci-artifacts/lcdui/verified.txt
selectors=(
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#canvasCanBecomeShownAndPaintWhileStartAppIsStillRunning'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#androidHomeKeepsLiveRuntimeSelectedForLauncherReturn'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#liveMidletCanYieldLibraryAndReattachSameRuntime'
)
for index in "${!selectors[@]}"; do
  log="ci-artifacts/lcdui/case-$((index + 1)).txt"
  printf 'Running %s\n' "${selectors[index]}"
  if ! timeout 180s adb shell am instrument -w -r \
      -e class "${selectors[index]}" "$runner" > "$log" 2>&1; then
    cat "$log"
    exit 1
  fi
  cat "$log"
  python3 scripts/runtime-smoke-results.py --log "$log" \
    --output ci-artifacts/lcdui/verified.txt "${selectors[index]}"
done
test "$(wc -l < ci-artifacts/lcdui/verified.txt)" -eq "${#selectors[@]}"
