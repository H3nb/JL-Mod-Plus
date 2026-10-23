#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Install and launch release APKs on a disposable x86_64 16 KB emulator.
# Test signing happens on copies; staged APKs and their checksums stay unchanged.
set -euo pipefail

package='io.github.h3nb.jlmodplus'
output='ci-artifacts/release-smoke'
mkdir -p "$output"
stage='emulator-preflight'
variant='setup'
capture_context() {
  local prefix="$1"
  {
    echo "UTC: $(date -u +%FT%TZ)"
    echo "Stage: $stage"
    echo "Variant: $variant"
    echo "Source: ${JLMOD_BUILD_COMMIT:-$(git rev-parse HEAD)}"
    echo "Run/attempt: ${GITHUB_RUN_ID:-local}/${GITHUB_RUN_ATTEMPT:-local}"
    adb shell getprop ro.build.fingerprint
    adb shell getprop ro.product.cpu.abi
    adb shell getconf PAGE_SIZE
  } > "$output/$prefix-context.txt" 2>&1 || true
  adb logcat -d -v threadtime > "$output/$prefix-logcat.txt" 2>&1 || true
  adb logcat -b crash -d -v threadtime > "$output/$prefix-crash.txt" 2>&1 || true
  adb shell dumpsys activity exit-info "$package" > "$output/$prefix-exit-info.txt" 2>&1 || true
}
collect_diagnostics() {
  local result=$?
  capture_context "$variant-exit"
  if (( result != 0 )); then
    cat "$output/$variant-exit-context.txt" >&2
    echo 'Release startup failed; last crash-buffer records:' >&2
    tail -n 200 "$output/$variant-exit-crash.txt" >&2 || true
    echo 'Application exit history:' >&2
    cat "$output/$variant-exit-exit-info.txt" >&2
    echo 'Last system log records:' >&2
    tail -n 200 "$output/$variant-exit-logcat.txt" >&2
  fi
  return "$result"
}
trap collect_diagnostics EXIT
[[ "$(adb shell getconf PAGE_SIZE | tr -d '\r')" == 16384 ]]
[[ "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')" == x86_64 ]]

build_tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -n 1)"
apksigner="$ANDROID_HOME/build-tools/$build_tools/apksigner"
zipalign="$ANDROID_HOME/build-tools/$build_tools/zipalign"
keytool -genkeypair -keystore "$output/test.jks" -storepass android -keypass android \
  -alias test -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Release check' >/dev/null 2>&1

for variant in x86_64 universal; do
  stage='signing-and-install'
  source="dist/JL-Mod-Plus-v0.0.0-ci-$variant.apk"
  apk="$output/$variant.apk"
  "$zipalign" -c -P 16 4 "$source"
  "$apksigner" sign --ks "$output/test.jks" --ks-pass pass:android \
    --key-pass pass:android --out "$apk" "$source"
  "$apksigner" verify "$apk"
  adb install --no-streaming "$apk"
  adb shell dumpsys package "$package" > "$output/$variant-package.txt"
  native_root="$(sed -nE 's/^[[:space:]]*legacyNativeLibraryDir=(.*)/\1/p' "$output/$variant-package.txt" | tr -d '\r')"
  if [[ -z "$native_root" ]]; then
    echo "Missing installed native library directory for $variant" >&2
    exit 1
  fi
  native_dir="$native_root/x86_64"
  echo "Checking extracted $variant native libraries in $native_dir"
  adb shell "test -f '$native_dir/libc++_shared.so'"
  # Match runtime-smoke setup so startup reaches the app instead of Settings.
  adb shell appops set "$package" MANAGE_EXTERNAL_STORAGE allow
  capture_context "$variant-before-launch-stop"
  adb shell am force-stop "$package"
  stage='startup'
  adb shell am start -W -n "$package/ru.playsoftware.j2meloader.LauncherActivity" \
    | tee "$output/$variant-launch.txt"
  grep -q '^Status: ok' "$output/$variant-launch.txt"
  grep -q "^Activity: $package/" "$output/$variant-launch.txt"
  sleep 5
  if ! adb shell pidof "$package"; then
    echo "$variant app process exited after launch" >&2
    exit 1
  fi
  stage='startup-verified-before-cleanup'
  # Preserve application evidence before force-stop/uninstall changes exit history.
  capture_context "$variant-startup"
  adb shell am force-stop "$package"
  stage='uninstall'
  adb uninstall "$package"
  rm "$apk"
done
stage='complete'
rm "$output/test.jks"
echo 'x86_64 and universal release APKs installed, extracted native libraries and stayed alive after launch on a 16 KB emulator.' \
  | tee "$output/result.txt"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$output/result.txt" >> "$GITHUB_STEP_SUMMARY"
fi
