#!/usr/bin/env bash
# Temporary PR248 targeted on-emulator evidence. Remove once verified.
set -euo pipefail
./gradlew --daemon --stacktrace --console=plain -PjlmodRuntimeTestAbi=x86_64 :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
adb shell appops set io.github.h3nb.jlmodplus.debug MANAGE_EXTERNAL_STORAGE allow
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort)
if (( ${#test_apks[@]} != 1 )); then
  echo "Expected one Android test APK, got ${#test_apks[@]}" >&2
  exit 1
fi
adb install -r -t "${test_apks[0]}"
runner="$(adb shell pm list instrumentation | tr -d '\r' |
  sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p')"
if [[ -z "$runner" ]]; then
  echo 'Android instrumentation runner was not available' >&2
  exit 1
fi
selectors=(
  'io.github.h3nb.jlmodplus.config.ControlSkinArchiveTest#exportImportPreservesArtworkMetadataAndAvoidsDuplicateCopies'
  'io.github.h3nb.jlmodplus.config.ControlSkinArchiveTest#collisionCreatesIndependentSetAndRepeatedImportReusesIt'
  'io.github.h3nb.jlmodplus.config.ControlSkinArchiveTest#invalidZipCannotCreateOrModifyGlobalSets'
  'io.github.h3nb.jlmodplus.config.ControlSkinArchiveTest#exportRefusesDestinationsInsideManagedSkinDirectory'
  'io.github.h3nb.jlmodplus.config.ControlSkinArchiveTest#exportNormalizesLegacyMetadataWithoutChangingTheSource'
  'io.github.h3nb.jlmodplus.config.ControlSkinManagerComposeTest#skinLibraryOffersZipImportAndSetExport'
)
selector_arg="$(IFS=,; echo "${selectors[*]}")"
adb shell am instrument -w -r -e class "$selector_arg" "$runner" | tee skin-test-results.txt
python3 scripts/runtime-smoke-results.py --log skin-test-results.txt --output validated-skin-selectors.txt "${selectors[@]}"
