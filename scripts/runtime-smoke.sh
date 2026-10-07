#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

trap 'adb logcat -d -v threadtime > runtime-logcat.txt 2>&1 || true' EXIT

selectors=(
  'javax.microedition.lcdui.graphics.AmbientColorSamplerTest'
  'javax.microedition.media.MediaBoundaryRuntimeTest'
  'io.github.h3nb.jlmodplus.librarydb.LibraryDatabaseAndroidTest'
  'io.github.h3nb.jlmodplus.crashes.CrashReportsComposeTest'
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

./gradlew --daemon --stacktrace --profile \
  -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug \
  :app:assembleEmulatorDebugAndroidTest

adb shell appops set io.github.h3nb.jlmodplus.debug MANAGE_EXTERNAL_STORAGE allow

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

IFS=,
selector_arg="${selectors[*]}"
unset IFS

mkdir -p ci-artifacts/runtime-smoke
instrumentation_log="ci-artifacts/runtime-smoke/instrumentation.txt"
verified_selectors="ci-artifacts/runtime-smoke/selectors.txt"
: > "$verified_selectors"

echo "Running ${#selectors[@]} selected Android runtime contracts."
if ! adb shell am instrument -w -r -e class "$selector_arg" "$runner" > "$instrumentation_log" 2>&1; then
  cat "$instrumentation_log"
  echo "Instrumentation command failed." >&2
  exit 1
fi
cat "$instrumentation_log"

if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|No tests found' "$instrumentation_log"; then
  echo "Instrumentation reported a runtime-smoke failure." >&2
  exit 1
fi
if ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$instrumentation_log"; then
  echo "Instrumentation did not report successful completion." >&2
  exit 1
fi

for selector in "${selectors[@]}"; do
  class_name="${selector%%#*}"
  if ! grep -Fq "INSTRUMENTATION_STATUS: class=$class_name" "$instrumentation_log"; then
    echo "Selected class did not execute: $selector" >&2
    exit 1
  fi

  if [[ "$selector" == *'#'* ]]; then
    method_name="${selector#*#}"
    if ! grep -Fq "INSTRUMENTATION_STATUS: test=$method_name" "$instrumentation_log"; then
      echo "Selected method did not execute: $selector" >&2
      exit 1
    fi
  fi

  printf 'PASS\t%s\n' "$selector" >> "$verified_selectors"
done

if (( $(wc -l < "$verified_selectors") != ${#selectors[@]} )); then
  echo "Runtime-smoke selector evidence is incomplete." >&2
  exit 1
fi
