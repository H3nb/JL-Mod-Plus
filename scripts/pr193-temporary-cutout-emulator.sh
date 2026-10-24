#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-artifacts/pr193-cutout
trap 'adb logcat -d -v threadtime > ci-artifacts/pr193-cutout/logcat.txt 2>&1 || true; adb shell cmd overlay disable --user 0 com.android.internal.display.cutout.emulation.tall >/dev/null 2>&1 || true; adb shell cmd window user-rotation free >/dev/null 2>&1 || true' EXIT
./gradlew --daemon --stacktrace --profile -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
mapfile -t apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort)
[[ "${#apks[@]}" -eq 1 ]] || { printf 'Expected one Android test APK, got %s\\n' "${#apks[@]}" >&2; exit 1; }
adb install -r -t "${apks[0]}"
mapfile -t runners < <(adb shell pm list instrumentation | tr -d '\r' | sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
[[ "${#runners[@]}" -eq 1 ]] || { echo 'Expected exactly one AndroidJUnitRunner' >&2; exit 1; }
runner="${runners[0]}"
selector='io.github.h3nb.jlmodplus.config.TemporaryCutoutQualificationTest#realWindowInsetsControlSettingVisibilityAndOptOut'
: > ci-artifacts/pr193-cutout/verified.txt
# Reset the simulated cutout before taking a true no-cutout baseline.
overlay='com.android.internal.display.cutout.emulation.tall'
adb shell cmd overlay list --user 0 | tee ci-artifacts/pr193-cutout/overlays.txt
grep -F "$overlay" ci-artifacts/pr193-cutout/overlays.txt >/dev/null || {
    echo "The chosen emulator image does not support the required cutout overlay: $overlay" >&2; exit 1;
}
adb shell cmd overlay disable --user 0 "$overlay"
run_case() {
    local label="$1" expected="$2" orientation="$3"
    echo "Running real-window cutout test: $label ($expected, $orientation)"
    local log="ci-artifacts/pr193-cutout/${label}.txt"
    # Set physical display rotation *before* launching the Activity, avoiding test-host recreation.
    if [[ "$orientation" == "landscape" ]]; then
        adb shell cmd window user-rotation lock 1
    else
        adb shell cmd window user-rotation lock 0
    fi
    sleep 3
    if ! adb shell am instrument -w -r \
        -e class "$selector" \
        -e cutoutMode "$expected" \
        -e orientation "$orientation" \
        "$runner" > "$log" 2>&1; then
        cat "$log"; exit 1
    fi
    cat "$log"
    python3 scripts/runtime-smoke-results.py --log "$log" \
        --output ci-artifacts/pr193-cutout/verified.txt "$selector"
}
run_case no-cutout-portrait none portrait
adb shell cmd overlay enable --user 0 "$overlay"
sleep 7
run_case notch-portrait notch portrait
run_case notch-landscape notch landscape
# Exercise on-device Canvas/graphics lifecycle independently of the cutout UI probe.
graphics='javax.microedition.lcdui.GraphicsCanvasLifecycleStressTest#repeatedResetAndClipKeepsCanvasStable'
log='ci-artifacts/pr193-cutout/graphics-canvas.txt'
adb shell am instrument -w -r -e class "$graphics" "$runner" > "$log" 2>&1 || { cat "$log"; exit 1; }
cat "$log"
python3 scripts/runtime-smoke-results.py --log "$log" \
    --output ci-artifacts/pr193-cutout/verified.txt "$graphics"
[[ "$(wc -l < ci-artifacts/pr193-cutout/verified.txt)" -eq 4 ]] || { echo 'Incomplete executed selector evidence' >&2; exit 1; }
cat ci-artifacts/pr193-cutout/verified.txt
