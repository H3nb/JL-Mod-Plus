#!/usr/bin/env bash
# Temporary runtime validation for PR #247; remove after approval.
set -euo pipefail
mkdir -p ci-artifacts/pr247-runtime
trap 'adb logcat -d -v threadtime > runtime-logcat.txt 2>&1 || true' EXIT
chmod +x gradlew
./gradlew --daemon --stacktrace --profile \
  -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
adb shell appops set io.github.h3nb.jlmodplus.debug MANAGE_EXTERNAL_STORAGE allow

mapfile -t test_apks < <(
  find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort
)
if (( ${#test_apks[@]} != 1 )); then
  echo "Expected one instrumentation APK, found ${#test_apks[@]}" >&2
  exit 1
fi
adb install -r -t "${test_apks[0]}"
mapfile -t runners < <(
  adb shell pm list instrumentation | tr -d '\r' |
    sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p'
)
if (( ${#runners[@]} != 1 )); then
  echo "Expected one AndroidJUnitRunner, found ${#runners[@]}" >&2
  exit 1
fi
runner="${runners[0]}"
evidence=ci-artifacts/pr247-runtime/verified-selectors.txt
: > "$evidence"

run_batch() {
  local label="$1"
  shift
  local selectors=("$@")
  local logfile="ci-artifacts/pr247-runtime/${label}.txt"
  local IFS=,
  echo "Running ${#selectors[@]} selectors: $label"
  if ! adb shell am instrument -w -r -e class "${selectors[*]}" "$runner" > "$logfile" 2>&1; then
    cat "$logfile"
    return 1
  fi
  cat "$logfile"
  python3 scripts/runtime-smoke-results.py --log "$logfile" --output "$evidence" "${selectors[@]}"
}

run_batch external-jad \
  'io.github.h3nb.jlmodplus.installer.ExternalContentJadImportTest#externalJadRequiresExplicitJarAndPreservesDescriptorValidation'
run_batch installer-filesystem \
  'io.github.h3nb.jlmodplus.installer.InstallerFilesystemTest#freshJadInstallReinstallAndReconversionPreserveSourcesAndUserFiles'
run_batch runtime-storage-lease \
  'io.github.h3nb.jlmodplus.installer.RuntimeStorageLeaseInstrumentedTest#deletedLiveRuntimeCannotShareFreshInstallStorage'
run_batch library-room \
  'io.github.h3nb.jlmodplus.librarydb.LibraryDatabaseAndroidTest#absolutePathAndroidSqliteDriverRoundTripsLibraryData'
run_batch installer-ui \
  'io.github.h3nb.jlmodplus.installer.InstallerComposeTest#loadingStateCanRequestCancellation' \
  'io.github.h3nb.jlmodplus.installer.InstallerComposeTest#conversionStateCanRequestCancellation'
run_batch screenshot-pipeline \
  'javax.microedition.shell.CoroutineScreenshotRuntimeTest#glPixelsAreConvertedSavedAndReturnedOnTheCorrectThreads' \
  'javax.microedition.shell.CoroutineScreenshotRuntimeTest#softwarePixelsAreSavedOffTheMainThread'
run_batch gles-isolated \
  'io.github.h3nb.jlmodplus.micro3d.EnvironmentTextureGlesTest'

test "$(wc -l < "$evidence")" -eq 9
echo 'All 9 targeted Android instrumentation selectors passed.'
