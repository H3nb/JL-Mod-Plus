#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

output="ci-artifacts/library-scale"
selectors=(
  "io.github.h3nb.jlmodplus.applist.LibraryComposeTest#twentyThousandAppsRemainVirtualizedInListAndGrid"
  "io.github.h3nb.jlmodplus.librarydb.LibraryStartupRuntimeTest#existingCatalogLoadsAndProjects"
  "io.github.h3nb.jlmodplus.applist.LibraryViewportNavigationTest#endOfLibraryViewportSurvivesDifferentFooterPagerRoundTrip"
)
selector_arg="$(IFS=,; echo "${selectors[*]}")"
mkdir -p "$output"
chmod +x gradlew
./gradlew --daemon --stacktrace --profile -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest

mapfile -t apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print)
if (( ${#apks[@]} != 1 )); then
    echo "Expected one instrumentation APK; found ${#apks[@]}" >&2
    exit 1
fi
adb install -r -t "${apks[0]}"

mapfile -t runners < <(
  adb shell pm list instrumentation | tr -d '\r' |
    sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p'
)
if (( ${#runners[@]} != 1 )); then
    echo "Missing or ambiguous instrumentation runner" >&2
    exit 1
fi

if ! adb shell am instrument -w -r -e class "$selector_arg" "${runners[0]}" \
    > "$output/instrumentation.txt" 2>&1; then
    cat "$output/instrumentation.txt"
    exit 1
fi
cat "$output/instrumentation.txt"
adb logcat -d -s LibraryStartupRuntime:I > "$output/startup-logcat.txt"
python3 scripts/runtime-smoke-results.py \
  --log "$output/instrumentation.txt" \
  --output "$output/verified.txt" "${selectors[@]}"
