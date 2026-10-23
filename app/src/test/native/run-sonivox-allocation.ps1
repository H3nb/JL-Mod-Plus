# SPDX-License-Identifier: Apache-2.0
param([string[]]$Banks, [string]$Compiler = 'gcc.exe')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$cpp = Join-Path $repo 'app/src/main/cpp'
$makefile = Get-Content -LiteralPath "$cpp/sonivox/Android.mk" -Raw
$sourceList = [regex]::Match($makefile, '(?s)LOCAL_SRC_FILES := (.*?)\r?\nLOCAL_CFLAGS').Groups[1].Value
$sources = [regex]::Matches($sourceList, '(?:host_src|lib_src)/[a-zA-Z0-9_]+\.c') |
    ForEach-Object { Join-Path "$cpp/sonivox" $_.Value }
$testOutput = Join-Path ([IO.Path]::GetTempPath()) ('jl-sonivox-allocation-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testOutput | Out-Null
$executable = Join-Path $testOutput 'sonivox_allocation_test.exe'
& $Compiler -std=c11 -O2 -Wno-attributes -DJL_EAS_TEST_ALLOCATOR "-I$cpp/sonivox/host_src" `
    "-I$cpp/sonivox/lib_src" "-I$cpp/sonivox/fakes" $sources "$cpp/mmapi_eas/eas_host.c" `
    (Join-Path $PSScriptRoot 'sonivox_allocation_test.c') -lm -o $executable
if ($LASTEXITCODE) { throw 'Allocation fault test compilation failed.' }
& $executable @Banks
if ($LASTEXITCODE) { throw 'Allocation fault sweep failed.' }
Write-Output "Verified $executable"
