#!/usr/bin/env bash
set -euo pipefail
chmod +x ./gradlew
if ./gradlew --no-daemon -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.h3nb.jlmodplus.memory.SavedLocatorIdentityResearchAndroidTest,io.github.h3nb.jlmodplus.memory.SavedVariableLocatorResearchAndroidTest,io.github.h3nb.jlmodplus.memory.MemoryIpcRuntimeTest#researchDescriptorSurvivesProcessRestartAndBindsWatchWithoutNewSearch \
  :app:connectedEmulatorDebugAndroidTest --stacktrace; then
  exit 0
fi
printf '%s\n' '---- R&D instrumentation XML failure details ----'
if [[ -d app/build/outputs/androidTest-results/connected ]]; then
  while IFS= read -r -d '' report; do
    echo "$report"
    head -c 36000 "$report"
    printf '\n'
  done < <(find app/build/outputs/androidTest-results/connected -type f -name '*.xml' -print0)
fi
printf '%s\n' '---- Selected runtime logcat diagnostics ----'
adb logcat -d -b crash -v threadtime 2>/dev/null | tail -n 140 || true
adb logcat -d -v threadtime -t 3500 2>/dev/null | grep -E 'SavedLocatorRND|TestRunner|AndroidRuntime|FATAL EXCEPTION|Fatal signal|MemoryTargetBridgeService|MemoryDiscoveryBridge|ActivityManager.*(Killing|Process|crash)' | tail -n 170 || true
exit 1
