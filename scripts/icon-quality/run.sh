#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

root="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
base="$root/baseline"
head="$root/candidate"
out="$root/ci-artifacts/icon-quality"
probe="app/src/androidTest/java/io/github/h3nb/jlmodplus/applist/LibraryIconBenchmarkProbeTest.kt"
test_class="io.github.h3nb.jlmodplus.applist.LibraryIconBenchmarkProbeTest"
package="io.github.h3nb.jlmodplus.debug"
mkdir -p "$out"

test -f "$head/$probe"
test -f "$base/app/build.gradle.kts"
baseline_sha="$(git -C "$base" rev-parse HEAD)"
candidate_sha="$(git -C "$head" rev-parse HEAD)"

{
  printf 'Baseline commit: %s\nCandidate commit: %s\n' "$baseline_sha" "$candidate_sha"
  printf 'Device ID: %s\n' "$(adb shell getprop ro.product.model | tr -d '\r')"
  printf 'Android API: %s\n' "$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  printf 'ABI: %s\n' "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  printf 'Runner: %s\n' "$(uname -a)"
  printf 'UTC: %s\n' "$(date -u +%FT%TZ)"
} | tee "$out/environment.txt"

# The baseline code stays untouched; only the identical experiment probe is overlaid
# into the disposable baseline checkout. Reflection supports the original private API.
mkdir -p "$(dirname "$base/$probe")"
cp "$head/$probe" "$base/$probe"
chmod +x "$base/gradlew" "$head/gradlew"

# On a cold dedicated runner Gradle Plugin Portal can publish a POM whose transitive
# artifacts are absent from its mirror. Resolve Maven coordinates from Maven Central
# first, identically in both disposable checkouts; never touch committed settings.
for settings in "$base/settings.gradle.kts" "$head/settings.gradle.kts"; do
  python3 - "$settings" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
original = p.read_text()
old = "        gradlePluginPortal()\n        google()\n        mavenCentral()"
new = "        mavenCentral()\n        google()\n        gradlePluginPortal()"
if original.count(old) != 1:
    raise SystemExit("Unexpected plugin repository configuration in " + str(p))
p.write_text(original.replace(old, new))
PY
done


for variant in baseline candidate; do
  checkout="$root/$variant"
  directory="$out/$variant"
  mkdir -p "$directory"

  echo "Building and running icon normalization probe: $variant"
  (
    cd "$checkout"
    JLMOD_BUILD_COMMIT="$(git rev-parse HEAD)" ./gradlew --daemon --stacktrace --profile \
      -PjlmodRuntimeTestAbi=x86_64 \
      :app:installEmulatorDebug :app:assembleEmulatorDebugAndroidTest
  )
  mapfile -t test_apks < <(find "$checkout/app/build/outputs/apk/androidTest" \
    -type f -name '*.apk' -print | sort)
  if (( ${#test_apks[@]} != 1 )); then
    echo "Expected one instrumentation APK for $variant, got ${#test_apks[@]}." >&2
    exit 1
  fi
  adb install -r -t "${test_apks[0]}"
  mapfile -t runners < <(
    adb shell pm list instrumentation |
      tr -d '\r' |
      sed -n 's/^instrumentation:\([^ ]*\) (target=io\.github\.h3nb\.jlmodplus\.debug)$/\1/p'
  )
  if (( ${#runners[@]} != 1 )); then
    echo "Missing or ambiguous AndroidJUnitRunner for $variant." >&2
    exit 1
  fi
  adb shell am instrument -w -r -e class "$test_class" "${runners[0]}" \
    > "$directory/instrumentation.txt" 2>&1 || {
      cat "$directory/instrumentation.txt"
      exit 1
    }
  cat "$directory/instrumentation.txt"
  python3 "$head/scripts/runtime-smoke-results.py" \
    --log "$directory/instrumentation.txt" \
    --output "$directory/selectors.txt" "$test_class"

  remote="/sdcard/Android/data/$package/files/icon-benchmark"
  adb pull "$remote/metrics.json" "$directory/metrics.json"
  adb pull "$remote/images" "$directory/images"
  test -s "$directory/metrics.json"
done

python3 "$head/scripts/icon-quality/compare.py" \
  --root "$out" \
  --mode "${ICON_PARITY_MODE:-strict}" \
  --expected-changes "$head/scripts/icon-quality/expected-visual-changes.json" \
  --baseline-sha "$baseline_sha" \
  --candidate-sha "$candidate_sha"
