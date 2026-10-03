# SPDX-License-Identifier: Apache-2.0
param(
    [Parameter(Mandatory)][string]$Device,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$NdkVersion = '28.2.13676358',
    [string]$AudioDeps
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
if (!$AudioDeps) { $AudioDeps = Join-Path $repo 'app/build/audio-deps' }
$prefix = Join-Path $AudioDeps 'install-arm64-v8a'
$toolchain = Join-Path $Sdk "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin"
if (!(Test-Path -LiteralPath "$prefix/include/libavcodec/avcodec.h")) {
    throw 'Build ARM64 audio dependencies with tools/audio/build-native-deps.ps1 first.'
}
$testOutput = Join-Path $AudioDeps 'pcm_decoder_test'
& "$toolchain/clang++.exe" --target=aarch64-linux-android23 -std=c++17 -O2 -Wall -Wextra -Werror `
    "-I$prefix/include" "-I$repo/app/src/main/cpp/mmapi_pcm" `
    "$PSScriptRoot/pcm_decoder_test.cpp" "$repo/app/src/main/cpp/mmapi_pcm/pcm_decoder.cpp" `
    "-L$prefix/lib" -lavformat -lavcodec -lswresample -lavutil -o $testOutput
if ($LASTEXITCODE) { throw 'PCM decoder test compilation failed.' }
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$remote = '/data/local/tmp/jl-pcm-native-test'
function Invoke-TestAdb([string[]]$ToolArguments) {
    & $adb -s $Device @ToolArguments
    if ($LASTEXITCODE) { throw 'ADB PCM test preparation or execution failed.' }
}
Invoke-TestAdb @('shell', 'mkdir', '-p', $remote)
Invoke-TestAdb @('push', $testOutput, "$remote/pcm_decoder_test")
foreach ($library in Get-ChildItem "$prefix/lib" -Filter '*.so') {
    Invoke-TestAdb @('push', $library.FullName, "$remote/$($library.Name)")
}
Invoke-TestAdb @('push', (Join-Path $Sdk "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"), "$remote/libc++_shared.so")
Invoke-TestAdb @('push', "$repo/app/src/androidTest/assets/audio/.", "$remote/")
Invoke-TestAdb @('push', "$repo/app/src/androidTest/assets/video/offset.mp4", "$remote/offset.mp4")
Invoke-TestAdb @('shell', "chmod 700 $remote/pcm_decoder_test && LD_LIBRARY_PATH=$remote $remote/pcm_decoder_test $remote/pcm.wav $remote/adpcm.wav $remote/alaw.wav $remote/gsm.wav $remote/effect.mp3 $remote/effect.aac $remote/generated-tone-dtx-nb.amr --sid-nb $remote/generated-dtx-nb.amr --sid-wb $remote/generated-sid-wb.awb --corrupt $remote/corrupt.wav --video $remote/offset.mp4")
