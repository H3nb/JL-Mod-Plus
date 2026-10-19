#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Temporary AndroidJUnitRunner qualification for H3nb/JL-Mod-Plus#179.
set -euo pipefail

out="ci-artifacts/pr179-audio"
mkdir -p "$out"
trap 'adb logcat -d -v threadtime > ci-artifacts/pr179-audio/logcat.txt 2>&1 || true' EXIT

adb wait-for-device
adb logcat -c || true
adb shell getprop ro.build.version.sdk | tr -d '\r' > "$out/android-api.txt"

# Use the repository's existing isolated application ID, which is required
# for whole-MIDlet tests and protects the normal debug installation.
./gradlew --daemon --stacktrace --profile \
  -I scripts/audio-qualification.init.gradle -PjlmodRuntimeTestAbi=x86_64 \
  :app:installEmulatorDebug
app_id="io.github.h3nb.jlmodplus.audioqualification.debug"
adb shell appops set "$app_id" MANAGE_EXTERNAL_STORAGE allow

mapfile -t test_apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort)
if (( ${#test_apks[@]} != 1 )); then
  printf 'Expected one test APK, found %d\n' "${#test_apks[@]}" >&2
  exit 1
fi
adb install -r -t "${test_apks[0]}"

mapfile -t runners < <(
  adb shell pm list instrumentation |
    tr -d '\r' |
    sed -n "s/^instrumentation:\\([^ ]*\\) (target=${app_id//./\\.})$/\\1/p"
)
if (( ${#runners[@]} != 1 )); then
  printf 'Expected one instrumentation runner, found %d\n' "${#runners[@]}" >&2
  exit 1
fi
runner="${runners[0]}"
printf 'Runner: %s\n' "$runner"
printf 'batch\tresult\texecuted-tests\n' > "$out/summary.tsv"
failures=0

run_batch() {
  local name="$1"
  shift
  local expected=("$@")
  local selectors
  local log="$out/$name.txt"
  local rc=0
  local bad=0
  local count=0
  selectors=$(IFS=,; echo "${expected[*]}")
  printf '\n=== %s (%d selectors) ===\n' "$name" "${#expected[@]}"
  if [[ "$name" == "midlet-bounded" ]]; then
    timeout --signal=TERM 900 adb shell am instrument -w -r \
      -e sonivoxSessionSeconds 30 -e class "$selectors" "$runner" > "$log" 2>&1 || rc=$?
  else
    timeout --signal=TERM 900 adb shell am instrument -w -r \
      -e class "$selectors" "$runner" > "$log" 2>&1 || rc=$?
  fi
  cat "$log"
  if (( rc != 0 )); then
    printf '%s: instrumentation exited %d\n' "$name" "$rc" >&2
    bad=1
  fi
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|No tests found|INSTRUMENTATION_STATUS_CODE: -2' "$log"; then
    bad=1
  fi
  if ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$log"; then
    printf '%s: instrumentation did not return completion code -1\n' "$name" >&2
    bad=1
  fi
  count=$(grep -c 'INSTRUMENTATION_STATUS_CODE: 0' "$log" || true)
  printf 'Successful test completions: %d\\n' "$count"
  if grep -Fq 'INSTRUMENTATION_STATUS_CODE: -4' "$log"; then
    echo "Selected instrumentation skipped a test; cannot claim full coverage." >&2
    bad=1
  fi
  if (( count < ${#expected[@]} )); then
    printf '%s: too few test starts (%d)\n' "$name" "$count" >&2
    bad=1
  fi
  for selector in "${expected[@]}"; do
    local class_name="${selector%%#*}"
    if ! grep -Fq "INSTRUMENTATION_STATUS: class=$class_name" "$log"; then
      printf 'Missing selected class: %s\n' "$selector" >&2
      bad=1
    fi
    if [[ "$selector" == *'#'* ]]; then
      local method="${selector#*#}"
      if ! grep -Fq "INSTRUMENTATION_STATUS: test=$method" "$log"; then
        printf 'Missing selected method: %s\n' "$selector" >&2
        bad=1
      fi
    fi
  done
  adb shell dumpsys meminfo "$app_id" > "$out/$name-meminfo.txt" 2>&1 || true
  if (( bad == 0 )); then
    printf '%s\tPASS\t%d\n' "$name" "$count" >> "$out/summary.tsv"
  else
    printf '%s\tFAIL\t%d\n' "$name" "$count" >> "$out/summary.tsv"
    failures=$((failures + 1))
  fi
}

ns='io.github.h3nb.jlmodplus.mmapi.synth'
run_batch scalability "$ns.AudioScalabilityEmulatorTest" "$ns.SonivoxRuntimeTest#manyLogicalPlayersAreCheapAndPreparedSourcesShareOneOutput"
# The FileConnection method depends on an active guest filesystem and cannot
# meaningfully execute in a host-only AudioQualificationActivity.
# Its coverage remains a separate guest-filesystem integration task.
run_batch synthesis \
  "$ns.SonivoxRuntimeTest#stopFreezesTimeResumeAndPausedSeekPreservePlayback" \
  "$ns.SonivoxRuntimeTest#ultraShortMidiOrdersStartBeforeEndAndFiniteLoopsComplete" \
  "$ns.SonivoxRuntimeTest#twoPlayersHaveIndependentSequencersAndCloseDoesNotStopPeer" \
  "$ns.SonivoxRuntimeTest#interactiveMidiBeforeStartAndToneSequenceUseProductionBackend" \
  "$ns.SonivoxRuntimeTest#ringtoneParsersRecognizeContentsDespiteMidiMimeHint" \
  "$ns.SonivoxRuntimeTest#recognizedCorruptMediaAndMissingBankFailWithoutSilentFallback" \
  "$ns.SonivoxRuntimeTest#generatedSf2AndDlsBanksBothRenderWithIsolatedContexts" \
  "$ns.SonivoxRuntimeTest#malformedSf2AfterMetadataParsingReleasesNativeAllocations" \
  "$ns.SonivoxRuntimeTest#malformedSf2IndicesAndRiffLengthsFailWithoutOpeningPlayers" \
  "$ns.SonivoxRuntimeTest#sameSessionCustomBankSurvivesIndependentPlayerClose"
run_batch mmapi-contract "$ns.SynthPlayerContractTest" "$ns.MidiDeliveryRuntimeTest"
run_batch sampled "$ns.UnifiedAudioRuntimeTest" 'javax.microedition.media.SampledAudioRuntimeTest'
run_batch midi-containers \
  "$ns.RmidRuntimeTest#corruptAndEmbeddedBankContainersFailClearlyAndReleaseSource" \
  "$ns.RmidRuntimeTest#riffWaveRemainsSampled"
run_batch focus "$ns.SonivoxFocusRuntimeTest"
# Whole-MIDlet and video tests require the dedicated qualification package.
# Optional private-bank tests cannot run without a supplied bank.
run_batch midlet "$ns.AudioMidletRuntimeTest#realMidletRetainsAudioAcrossHostReturnAndRecreationAndGuestStop"
run_batch midlet-bounded "$ns.AudioMidletRuntimeTest#boundedLoopingMidletSession"
run_batch video "$ns.VideoMidletRuntimeTest"

cat "$out/summary.tsv"
if (( failures != 0 )); then
  printf '%d audio instrumentation batch(es) failed\n' "$failures" >&2
  exit 1
fi
