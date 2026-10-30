#!/usr/bin/env bash
set -euo pipefail
package=io.github.h3nb.jlmodplus.debug
activity=io.github.h3nb.jlmodplus.MainActivity
mapfile -t apks < <(find app/build/outputs/apk/emulator/debug -maxdepth 1 -type f -name '*.apk' | sort)
if (( ${#apks[@]} != 1 )); then
  echo "Expected exactly one emulator APK, found ${#apks[@]}." >&2
  exit 1
fi
adb install -r "${apks[0]}"
mkdir -p ci-artifacts/ime
adb shell mkdir -p '/sdcard/JL-Mod Plus'
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set "$package" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
adb shell settings put system screen_off_timeout 600000
adb shell input keyevent KEYCODE_HOME
sample_case() {
  local name="$1"
  local action="$2"
  local report="ci-artifacts/ime/${name}.tsv"
  adb logcat -c
  adb shell input keyevent KEYCODE_HOME
  if [[ "$action" == cold ]]; then
    adb shell am force-stop "$package"
  fi
  printf 'iteration\tuptime\tinput_shown\tshow_requested\tfocused_window\n' > "$report"
  (
    sleep 0.2
    adb shell am start -W -n "$package/$activity" > "ci-artifacts/ime/${name}-start.txt" 2>&1
  ) &
  local launching=$!
  for i in $(seq 1 32); do
    local output shown requested focused uptime
    output="$(adb shell dumpsys input_method 2>&1 | tr -d '\r')"
    shown="$(printf '%s\n' "$output" | grep -Eo 'mInputShown=(true|false)' | head -1 | cut -d= -f2 || true)"
    requested="$(printf '%s\n' "$output" | grep -Eo 'mShowRequested=(true|false)' | head -1 | cut -d= -f2 || true)"
    focused="$(printf '%s\n' "$output" | grep -E 'mCurFocusedWindow|mCurFocusedWindowName' | head -1 | tr '\t' ' ' || true)"
    uptime="$(adb shell cat /proc/uptime | cut -d' ' -f1)"
    printf '%s\t%s\t%s\t%s\t%s\n' "$i" "$uptime" "${shown:-unknown}" "${requested:-unknown}" "$focused" >> "$report"
    sleep 0.12
  done
  wait "$launching"
  adb shell dumpsys input_method > "ci-artifacts/ime/${name}-input-method.txt" || true
  adb shell dumpsys window windows > "ci-artifacts/ime/${name}-windows.txt" || true
  adb shell dumpsys activity activities > "ci-artifacts/ime/${name}-activity.txt" || true
  adb logcat -d -v threadtime > "ci-artifacts/ime/${name}-logcat.txt" || true
  adb shell uiautomator dump /sdcard/library-ui.xml > "ci-artifacts/ime/${name}-ui-dump-status.txt" 2>&1 || true
  adb exec-out cat /sdcard/library-ui.xml > "ci-artifacts/ime/${name}-ui.xml" 2>/dev/null || true
  adb exec-out screencap -p > "ci-artifacts/ime/${name}.png" || true
  echo "=== ${name}: IME observations ==="
  awk -F '\t' 'NR>1 {a[$3]++} END {for (v in a) printf "%s = %d samples\n",v,a[v]}' "$report"
  if ! awk -F '\t' 'NR>1 && $3 != "unknown" {seen=1} END {exit !seen}' "$report"; then
    echo "No mInputShown evidence; input method sampler unsupported on this system." >&2
    return 1
  fi
  if ! grep -Eq 'Search|Cari|Apps|Aplikasi' "ci-artifacts/ime/${name}-ui.xml"; then
    echo "Could not verify the actual Library UI, rather than a permission/startup dialog." >&2
    return 1
  fi
  if awk -F '\t' 'NR>1 && $3 == "true" {seen=1} END {exit !seen}' "$report"; then
    echo "An IME-visible sample was observed in ${name}; inspect raw trace and focused window." >&2
    return 2
  fi
}
sample_case cold-launch-1 cold
sample_case cold-launch-2 cold
sample_case home-resume resume
./gradlew --daemon --stacktrace -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 '-Pandroid.testInstrumentationRunnerArguments.class=io.github.h3nb.jlmodplus.applist.LibraryComposeTest#loadingLibraryScopeChangeKeepsSearchUnfocused,io.github.h3nb.jlmodplus.applist.LibraryComposeTest#searchDoneTransfersFocusAfterTouchAndCanBeReopened,io.github.h3nb.jlmodplus.applist.LibraryViewportNavigationTest#searchRevealsCompleteHeaderAndStartsResultsAtTop,io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionPickerSearchReturnsToFirstResultAfterDeepScroll' :app:connectedEmulatorDebugAndroidTest
