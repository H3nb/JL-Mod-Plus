# SPDX-License-Identifier: Apache-2.0
param(
    [string]$Device,
    [string]$Bank,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$NdkVersion = '28.2.13676358'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$cpp = Join-Path $repo 'app/src/main/cpp'
$archive = Get-ChildItem (Join-Path $repo 'app/build/intermediates/cxx/Debug') -Filter libsonivox.a -Recurse |
    Where-Object { $_.FullName -match 'arm64-v8a' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $archive) { throw 'Assemble EmulatorDebug ARM64 before running this native test.' }
$nativeLibs = $archive.DirectoryName
$buildId = Split-Path (Split-Path (Split-Path (Split-Path $nativeLibs))) -Leaf
$prefab = Join-Path $repo "app/build/.cxx/Debug/$buildId/prefab/arm64-v8a/prefab/oboe/Android.mk"
$includeMatch = Select-String -LiteralPath $prefab -Pattern '^LOCAL_EXPORT_C_INCLUDES := (.+)$'
$oboeInclude = $includeMatch.Matches[0].Groups[1].Value.Trim()
$toolchain = Join-Path $Sdk "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin"
$tempOutput = Join-Path ([IO.Path]::GetTempPath()) ('jl-sonivox-native-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tempOutput | Out-Null
$hostObject = Join-Path $tempOutput 'host.o'
$executable = Join-Path $tempOutput 'sonivox_runtime_test'
& (Join-Path $toolchain 'clang.exe') --target=aarch64-linux-android23 -std=c11 -O2 `
    "-I$cpp/sonivox/host_src" -c "$cpp/mmapi_eas/eas_host.c" -o $hostObject
if ($LASTEXITCODE) { throw 'Native memory host compilation failed.' }
& (Join-Path $toolchain 'clang++.exe') --target=aarch64-linux-android23 -std=c++17 -O2 `
    "-I$cpp/mmapi_eas" "-I$cpp/sonivox/host_src" "-I$cpp/sonivox/lib_src" "-I$oboeInclude" `
    (Join-Path $PSScriptRoot 'sonivox_runtime_test.cpp') "$cpp/mmapi_eas/eas_player.cpp" `
    "$cpp/mmapi_eas/eas_file.cpp" $hostObject $archive.FullName "-L$nativeLibs" -loboe -llog -landroid -o $executable
if ($LASTEXITCODE) { throw 'Native runtime test compilation failed.' }
Write-Output "Built $executable"
if ($Device) {
    $adb = Join-Path $Sdk 'platform-tools/adb.exe'
    $remote = '/data/local/tmp/jl-sonivox-native-test'
    function Invoke-TestAdb([string[]]$ToolArguments) {
        & $adb -s $Device @ToolArguments
        if ($LASTEXITCODE) { throw 'ADB native test preparation or execution failed.' }
    }
    Invoke-TestAdb @('shell', 'mkdir', '-p', $remote)
    Invoke-TestAdb @('push', $executable, "$remote/sonivox_runtime_test")
    Invoke-TestAdb @('push', (Join-Path $nativeLibs 'liboboe.so'), "$remote/liboboe.so")
    Invoke-TestAdb @('push', (Join-Path $nativeLibs 'libc++_shared.so'), "$remote/libc++_shared.so")
    if ($Bank) {
        Invoke-TestAdb @('push', $Bank, "$remote/test-bank")
        Invoke-TestAdb @('shell', "chmod 700 $remote/sonivox_runtime_test && LD_LIBRARY_PATH=$remote $remote/sonivox_runtime_test $remote/test-bank")
    } else {
        Invoke-TestAdb @('shell', "chmod 700 $remote/sonivox_runtime_test && LD_LIBRARY_PATH=$remote $remote/sonivox_runtime_test")
    }
}
