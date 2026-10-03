# SPDX-License-Identifier: Apache-2.0
param([string]$Compiler = 'g++.exe')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$cpp = Join-Path $repo 'app/src/main/cpp'
$tempOutput = Join-Path ([IO.Path]::GetTempPath()) ('jl-sonivox-rmid-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tempOutput | Out-Null
$executable = Join-Path $tempOutput 'sonivox_rmid_test.exe'
& $Compiler -std=c++17 -O2 -Wall -Wextra "-I$cpp/mmapi_eas" "-I$cpp/sonivox/host_src" `
    (Join-Path $PSScriptRoot 'sonivox_rmid_test.cpp') "$cpp/mmapi_eas/eas_file.cpp" -o $executable
if ($LASTEXITCODE) { throw 'RMID extraction test compilation failed.' }
& $executable
if ($LASTEXITCODE) { throw 'RMID extraction test failed.' }
