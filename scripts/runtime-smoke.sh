#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

# This invocation owns its evidence, including failures before instrumentation.
rm -rf ci-artifacts/runtime-smoke

stage='build'
mkdir -p ci-artifacts/runtime-smoke
collect_diagnostics() {
  local result=$?
  {
    echo "UTC: $(date -u +%FT%TZ)"
    echo "Stage: $stage"
    echo 'Variant: emulatorDebug/x86_64'
    echo "Source: ${JLMOD_BUILD_COMMIT:-$(git rev-parse HEAD)}"
    echo "Run/attempt: ${GITHUB_RUN_ID:-local}/${GITHUB_RUN_ATTEMPT:-local}"
    adb shell getprop ro.build.fingerprint
    adb shell getprop ro.product.cpu.abi
    adb shell getconf PAGE_SIZE
  } > ci-artifacts/runtime-smoke/context.txt 2>&1 || true
  adb logcat -d -v threadtime > runtime-logcat.txt 2>&1 || true
  adb logcat -b crash -d -v threadtime > ci-artifacts/runtime-smoke/crash.txt 2>&1 || true
  adb shell dumpsys activity exit-info io.github.h3nb.jlmodplus.debug > ci-artifacts/runtime-smoke/exit-info.txt 2>&1 || true
  if (( result != 0 )); then
    cat ci-artifacts/runtime-smoke/context.txt >&2
    tail -n 200 ci-artifacts/runtime-smoke/crash.txt >&2 || true
    cat ci-artifacts/runtime-smoke/exit-info.txt >&2
    tail -n 200 runtime-logcat.txt >&2 || true
  fi
  return "$result"
}
trap collect_diagnostics EXIT

selectors=(
  'javax.microedition.lcdui.graphics.AmbientColorSamplerTest'
  'javax.microedition.lcdui.overlay.PerformanceOverlayRenderingTest'
  'io.github.h3nb.jlmodplus.config.PerformanceOverlayOptionsComposeTest'
  'javax.microedition.media.MediaBoundaryRuntimeTest'
  'io.github.h3nb.jlmodplus.mmapi.camera.CameraCallTest'
  'io.github.h3nb.jlmodplus.mmapi.camera.CameraCapturePlayerTest'
  'io.github.h3nb.jlmodplus.mmapi.camera.CameraSnapshotEncoderTest'
  'io.github.h3nb.jlmodplus.librarydb.LibraryDatabaseAndroidTest'
  'io.github.h3nb.jlmodplus.crashes.CrashReportsComposeTest'
  'io.github.h3nb.jlmodplus.crashes.DiagnosticBundleStoreRuntimeTest'
  'io.github.h3nb.jlmodplus.config.PresetAuthorityIpcRuntimeTest#staleRuntimeIdentityCannotModifyReplacementApp'
  'io.github.h3nb.jlmodplus.memory.MemoryIpcRuntimeTest#missingRuntimeCompletionCarriesStructuredReason'
  'io.github.h3nb.jlmodplus.applist.LibraryViewportNavigationTest'
  'io.github.h3nb.jlmodplus.applist.LibraryIconRefreshTest'
  'io.github.h3nb.jlmodplus.applist.LibraryComposeTest#compactHeightAppActionsKeepLastActionReachable'
  'io.github.h3nb.jlmodplus.applist.LibraryComposeTest#selectionSurvivesFilteredProjectionUntilAppLeavesLibrary'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#selectedCollectionRestoresAndBackReturnsToOverview'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#inactiveCollectionPagePreservesSelectedDetailForPagerReturn'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionRouteDoesNotCrossLibraryWorkdirWithReusedDatabaseId'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionScopeMismatchClearsManagementRoute'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#offscreenAppsLayoutChangeCannotRevealCollectionNavigationChrome'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#expandedWindowShowsListAndDetailWithoutDetailBack'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionSelectionBackExitsSelectionBeforeLeavingCollection'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionSelectionShowsCheckboxesSelectAllAndContextualBulkActions'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionSelectionBulkRemoveUsesCurrentCollection'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionLongPressRemoveKeepsCurrentCollectionContextAfterDialogDismiss'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionManageAppsReassertsNavigationChromeAfterPagerReturn'
  'io.github.h3nb.jlmodplus.applist.LibraryCollectionsNavigationTest#collectionManageAppsShowsPendingDesiredStateAndSerializesRowMutation'
  'io.github.h3nb.jlmodplus.installer.InstallerComposeTest#compactHeightConfirmationScrollsToAllActions'
  'io.github.h3nb.jlmodplus.installer.InstallerComposeTest#recoveryActionsRemainReachableInShortWindowWithLargeText'
  'io.github.h3nb.jlmodplus.installer.InstallerComposeTest#successWarningExpandsDetailsAndKeepsActionsReachable'
  'io.github.h3nb.jlmodplus.installer.InstallerComposeTest#bulkFooterActionsRemainReachableInShortWindowWithLargeText'
  'io.github.h3nb.jlmodplus.ui.AdaptiveDialogComposeTest#shortLargeTextDialogKeepsActionsVisibleAndBodyScrollable'
  'io.github.h3nb.jlmodplus.ui.AdaptiveDialogComposeTest#largeTextFontFormCanReachItsLastFieldAndConfirm'
  'io.github.h3nb.jlmodplus.settings.SettingsComposeTest#compactHeightLanguageDialogUsesAdaptiveScrollableBounds'
  'javax.microedition.shell.RuntimeMenuComposeTest#compactHeightBackMenuKeepsLastActionReachable'
  'javax.microedition.shell.RuntimeMenuComposeTest#runtimeDialogPreservesInheritedHiddenSystemBars'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#repeatedRemoteSessionCrashesKeepMainProcessAndPersistExactReports'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#launchingDifferentMidletReplacesBackgroundRuntimeWithoutCrashOrGuestTeardown'
  'io.github.h3nb.jlmodplus.crashes.CrashRuntimeIsolationTest#hungUserExitReturnsToLibraryAndTerminatesWithoutCrashReport'
  'io.github.h3nb.jlmodplus.crashes.ProcessExitRuntimeTest#abruptRemoteSigkillDoesNotBecomeAFatalReport'
  'io.github.h3nb.jlmodplus.crashes.ProcessExitRuntimeTest#newerNonfatalEvidenceStaysQueryableWithoutHidingFatalNotice'
  'io.github.h3nb.jlmodplus.runtime.EmulatorShutdownOrderTest'
  'io.github.h3nb.jlmodplus.crashes.RuntimeShutdownFixtureTest#cleanupWithoutCommittedOwnershipDoesNotTouchState'
  'io.github.h3nb.jlmodplus.crashes.RuntimeShutdownFixtureTest#earlySetupCleanupRestoresPreferenceAndPreservesUnrelatedRuntime'
  'io.github.h3nb.jlmodplus.crashes.RuntimeShutdownFixtureTest#failedPreferenceRestorationPreservesOwnedFixtureForRetry'
)

# Tests that mutate process-global/application-global state run in their own instrumentation
# session so their lifecycle side effects cannot contaminate unrelated runtime contracts.
isolated_selectors=(
  'io.github.h3nb.jlmodplus.platform.MidletLocaleRuntimeTest#coldSecondaryProcessReceivesApplicationLocaleAndJavaMeIdentity'
)

./gradlew --daemon --stacktrace --profile \
  -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug \
  :app:assembleEmulatorDebugAndroidTest

stage='grant-permissions'
adb shell appops set io.github.h3nb.jlmodplus.debug MANAGE_EXTERNAL_STORAGE allow
stage='install-instrumentation'

mapfile -t test_apks < <(
  find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort
)
if (( ${#test_apks[@]} != 1 )); then
  echo "Expected exactly one runtime-smoke test APK; found ${#test_apks[@]}." >&2
  printf '%s\n' "${test_apks[@]}" >&2
  exit 1
fi
adb install -r -t "${test_apks[0]}"

mapfile -t runners < <(
  adb shell pm list instrumentation |
    tr -d '\r' |
    sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p'
)
if (( ${#runners[@]} != 1 )); then
  echo "Expected exactly one instrumentation runner for the debug app; found ${#runners[@]}." >&2
  printf '%s\n' "${runners[@]}" >&2
  exit 1
fi
runner="${runners[0]}"

mkdir -p ci-artifacts/runtime-smoke
verified_selectors="ci-artifacts/runtime-smoke/selectors.txt"
: > "$verified_selectors"

run_selector_batch() {
  local batch_name="$1"
  shift
  local batch=("$@")
  local selector_arg
  local instrumentation_log="ci-artifacts/runtime-smoke/${batch_name}.txt"

  local IFS=,
  selector_arg="${batch[*]}"

  echo "Running ${#batch[@]} Android runtime contracts in batch: $batch_name."
  stage="instrumentation-$batch_name"
  if ! adb shell am instrument -w -r -e class "$selector_arg" "$runner" > "$instrumentation_log" 2>&1; then
    cat "$instrumentation_log"
    echo "Instrumentation batch failed: $batch_name." >&2
    exit 1
  fi
  cat "$instrumentation_log"

  python3 scripts/runtime-smoke-results.py \
    --log "$instrumentation_log" --output "$verified_selectors" "${batch[@]}"

}

run_selector_batch main "${selectors[@]}"
run_selector_batch locale-isolated "${isolated_selectors[@]}"

expected_selector_count=$((${#selectors[@]} + ${#isolated_selectors[@]}))
if (( $(wc -l < "$verified_selectors") != expected_selector_count )); then
  echo "Runtime-smoke selector evidence is incomplete." >&2
  exit 1
fi
stage='complete'
