#!/usr/bin/env bash
# Install and launch release APKs on a disposable x86_64 16 KB emulator.
# Test signing happens on copies; staged APKs and their checksums stay unchanged.
set -euo pipefail

package='io.github.h3nb.jlmodplus'
output='ci-artifacts/release-smoke'
mkdir -p "$output"
collect_diagnostics() {
  local result=$?
  adb logcat -d > "$output/logcat.txt" || true
  if (( result != 0 )); then
    echo 'Release startup failed; last crash-buffer records:' >&2
    adb logcat -b crash -d | tail -n 200 >&2 || true
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
  adb shell am force-stop "$package"
  adb shell am start -W -n "$package/ru.playsoftware.j2meloader.LauncherActivity" \
    | tee "$output/$variant-launch.txt"
  grep -q '^Status: ok' "$output/$variant-launch.txt"
  grep -q "^Activity: $package/" "$output/$variant-launch.txt"
  sleep 5
  if ! adb shell pidof "$package"; then
    echo "$variant app process exited after launch" >&2
    exit 1
  fi
  adb shell am force-stop "$package"
  adb uninstall "$package"
  rm "$apk"
done
rm "$output/test.jks"
echo 'x86_64 and universal release APKs installed, extracted native libraries and stayed alive after launch on a 16 KB emulator.' \
  | tee "$output/result.txt" >> "$GITHUB_STEP_SUMMARY"
