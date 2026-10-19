#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Temporary standalone, production-native callback qualification for PR 179.
# Uses a fake Oboe device *opener* only; invokes the real Player/Engine callback.
set -euo pipefail
out="ci-artifacts/pr179-audio"
mkdir -p "$out"
exec > >(tee "$out/native-standalone.txt") 2>&1
printf '=== PR179 native callback/hazard runtime on %s ===\n' "$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

repo="$PWD"
cpp="$repo/app/src/main/cpp"
abi="x86_64"
prefix="$repo/app/build/audio-deps/install-$abi"
ndk="$ANDROID_HOME/ndk/30.0.16248370"
tools="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT

test -f "$prefix/lib/libavformat.so"
mapfile -t sonivox < <(find "$repo/app/build/intermediates/cxx/Debug" \
  -type f -name libsonivox.a -path "*/$abi/*" -print | sort)
if (( ${#sonivox[@]} == 0 )); then
  echo 'ERROR: native x86_64 libsonivox.a was not produced.' >&2
  exit 1
fi
archive="${sonivox[0]}"
native_dir="$(dirname "$archive")"
mapfile -t prefabs < <(find "$repo/app/build/.cxx/Debug" \
  -type f -path "*/prefab/$abi/prefab/oboe/Android.mk" -print | sort)
if (( ${#prefabs[@]} == 0 )); then
  echo 'ERROR: generated Oboe prefab include manifest not found.' >&2
  exit 1
fi
include="$(sed -n 's/^LOCAL_EXPORT_C_INCLUDES := //p' "${prefabs[0]}" | head -1)"
if [[ -z "$include" ]]; then
  echo 'ERROR: Oboe include path is absent.' >&2
  exit 1
fi
printf 'Sonivox archive: %s\nAudio dependency prefix: %s\nOboe includes: %s\n' "$archive" "$prefix" "$include"

"$tools/clang" --target=x86_64-linux-android23 -std=c11 -O2 \
  "-I$cpp/sonivox/host_src" -c "$cpp/mmapi_eas/eas_host.c" -o "$temp/host.o"
"$tools/clang++" --target=x86_64-linux-android23 -std=c++17 -O2 \
  -DJL_EAS_OUTPUT_TEST=1 \
  "-I$prefix/include" "-I$cpp/mmapi_eas" \
  "-I$cpp/sonivox/host_src" "-I$cpp/sonivox/lib_src" "-I$include" \
  "$repo/app/src/test/native/sonivox_runtime_test.cpp" \
  "$cpp/mmapi_eas/eas_player.cpp" "$cpp/mmapi_pcm/pcm_decoder.cpp" \
  "$cpp/mmapi_eas/audio_engine.cpp" "$cpp/mmapi_eas/eas_file.cpp" \
  "$temp/host.o" "$archive" \
  "-L$prefix/lib" -lavformat -lavcodec -lswresample -lavutil \
  "-L$native_dir" -loboe -llog -landroid \
  -o "$temp/sonivox_runtime_test"

remote='/data/local/tmp/jl-pr179-native-audio'
adb shell mkdir -p "$remote"
adb push "$temp/sonivox_runtime_test" "$remote/sonivox_runtime_test"
adb push "$repo/app/src/androidTest/assets/audio/pcm.wav" "$remote/pcm.wav"
adb push "$native_dir/liboboe.so" "$remote/liboboe.so"
if [[ -f "$native_dir/libc++_shared.so" ]]; then
  adb push "$native_dir/libc++_shared.so" "$remote/libc++_shared.so"
else
  adb push "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so" "$remote/libc++_shared.so"
fi
for library in "$prefix"/lib/*.so; do
  adb push "$library" "$remote/$(basename "$library")"
done

adb shell chmod 700 "$remote/sonivox_runtime_test"
adb shell "LD_LIBRARY_PATH=$remote $remote/sonivox_runtime_test - $remote/pcm.wav"
echo "PASS: Standalone native callback/hazard tests executed on Android emulator."
