#!/usr/bin/env bash
set -euo pipefail

cd "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"

apk="$(cat r8-release-apk.txt)"
test -n "$apk"
test -f "$apk"

adb root || true
adb wait-for-device
adb shell id | tee r8-memory-editor-shell-id.txt
adb install -r "$apk"
adb logcat -c
adb shell am force-stop io.github.h3nb.jlmodplus

set +e
start_output="$(adb shell am start -W   -n io.github.h3nb.jlmodplus/io.github.h3nb.jlmodplus.memory.MemoryEditorActivity   --el ru.playsoftware.j2meloader.memory.extra.RUNTIME_TOKEN 1 2>&1)"
start_status=$?
set -e
printf '%s\n' "$start_output" | tee r8-memory-editor-start.txt
if [[ $start_status -ne 0 ]] || grep -Eq 'SecurityException|Error type [0-9]+|Permission Denial' r8-memory-editor-start.txt; then
  echo 'Could not directly start the minified Memory Editor activity.' >&2
  exit 1
fi

sleep 8
adb logcat -d -v threadtime > r8-memory-editor-logcat.txt
adb shell dumpsys activity exit-info io.github.h3nb.jlmodplus > r8-memory-editor-exit-info.txt || true

if grep -Eiq 'VerifyError|verification failed|rejecting opcode|invalid.*register|wide.*register|FATAL EXCEPTION.*memory_engine' r8-memory-editor-logcat.txt; then
  echo 'Retained R8 workaround did not protect the minified Memory Editor.' >&2
  grep -Ein 'VerifyError|verification failed|rejecting opcode|invalid.*register|wide.*register|FATAL EXCEPTION' r8-memory-editor-logcat.txt >&2 || true
  exit 1
fi
if grep -Eiq 'REASON_CRASH|reason=crash|crash native|crash java' r8-memory-editor-exit-info.txt; then
  echo 'Minified Memory Editor recorded a crash exit.' >&2
  cat r8-memory-editor-exit-info.txt >&2
  exit 1
fi

echo 'Retained Memory Editor R8 workaround passed runtime qualification.'
