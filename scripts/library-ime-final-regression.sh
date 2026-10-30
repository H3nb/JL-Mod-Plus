#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-artifacts/ime-final
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
apk="app/build/outputs/apk/emulator/debug/app-emulator-${abi}-debug.apk"
test -f "$apk"
adb install -r "$apk"
mapfile -t tests < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' | sort)
if (( ${#tests[@]} != 1 )); then
  printf 'Expected exactly one test APK, found %s\n' "${#tests[@]}" >&2
  exit 1
fi
adb install -r -t "${tests[0]}"
mapfile -t runners < <(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')
if (( ${#runners[@]} != 1 )); then
  printf 'Expected one test runner, found %s\n' "${#runners[@]}" >&2
  exit 1
fi
selectors=(
  'io.github.h3nb.jlmodplus.applist.LibraryComposeTest#loadingLibraryScopeChangeKeepsSearchUnfocused'
  'io.github.h3nb.jlmodplus.applist.LibraryComposeTest#searchDoneTransfersFocusAfterTouchAndCanBeReopened'
  'io.github.h3nb.jlmodplus.applist.LibraryViewportNavigationTest#searchRevealsCompleteHeaderAndStartsResultsAtTop'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionPickerSearchReturnsToFirstResultAfterDeepScroll'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionSearchReleasesFocusOnPagerExitWithoutLosingQuery'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionPickerSearchReleasesFocusAcrossTabs'
)
select="$(IFS=,; printf '%s' "${selectors[*]}")"
log='ci-artifacts/ime-final/instrumentation.txt'
if ! adb shell am instrument -w -r -e class "$select" "${runners[0]}" > "$log" 2>&1; then
  cat "$log"
  exit 1
fi
cat "$log"
python3 scripts/runtime-smoke-results.py --log "$log" \
  --output ci-artifacts/ime-final/verified.txt "${selectors[@]}"
