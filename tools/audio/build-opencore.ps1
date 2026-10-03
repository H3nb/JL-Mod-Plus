# SPDX-License-Identifier: Apache-2.0
param([string]$Target='aarch64-linux-android23', [switch]$DecoderOnly,
 [string]$SourceRoot="$PSScriptRoot/opencore-amr-0.1.6",
 [string]$OutputRoot="$PSScriptRoot/opencore-build-$Target",
 [Parameter(Mandatory)][string]$LlvmRoot,[string]$ToolSuffix='.exe')
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path "$outputRoot/obj","$outputRoot/lib","$outputRoot/include/opencore-amrnb","$outputRoot/include/opencore-amrwb" | Out-Null
foreach($mode in @('nb','wb')){
 $amrRoot="$sourceRoot/opencore/codecs_v2/audio/gsm_amr"
 $variables=@{DEC_SRC_DIR="$amrRoot/amr_$mode/dec/src";ENC_SRC_DIR="$amrRoot/amr_$mode/enc/src";COMMON_SRC_DIR="$amrRoot/amr_$mode/common/src"}
 $amFile=[IO.File]::ReadAllText("$sourceRoot/amr$mode/Makefile.am") -replace '(?m)^#.*$',''
 if($DecoderOnly -and $mode -eq 'nb'){$amFile=$amFile -replace '(?s)if AMRNB_ENCODER.*?endif',''}
 $sources=@("$sourceRoot/amr$mode/wrapper.cpp")
 foreach($match in [regex]::Matches($amFile,'\$\((DEC_SRC_DIR|ENC_SRC_DIR|COMMON_SRC_DIR)\)/([A-Za-z0-9_]+\.cpp)')){$sources+=Join-Path $variables[$match.Groups[1].Value] $match.Groups[2].Value}
 $sources=@($sources | Select-Object -Unique)
 $includes=@("$sourceRoot/oscl","$sourceRoot/amr$mode","$amrRoot/amr_$mode/dec/src","$amrRoot/amr_$mode/dec/include","$amrRoot/common/dec/include")
 if($mode -eq 'nb'){$includes+=@("$amrRoot/amr_nb/common/include","$amrRoot/amr_nb/enc/src")}
 $jobs=for($i=0;$i -lt $sources.Count;$i++){[pscustomobject]@{source=$sources[$i];obj="$outputRoot/obj/$mode-$i.o"}}
 $failures=@($jobs | ForEach-Object -Parallel {
   $flags=@("--target=$using:Target",'-x','c','-std=c99','-O2','-fPIC','-Wno-deprecated-non-prototype','-Wno-incompatible-pointer-types')
   foreach($include in $using:includes){$flags+="-I$include"}
   if($using:DecoderOnly -and $using:mode -eq 'nb'){$flags+='-DDISABLE_AMRNB_ENCODER'}
   & "$using:llvmRoot/clang$using:ToolSuffix" @flags -c $_.source -o $_.obj 2>&1 | Out-Null
   if($LASTEXITCODE -ne 0){$_.source}
 } -ThrottleLimit 8)
 if($failures.Count){throw "Failed: $($failures -join ', ')"}
 $objects=@($jobs.obj)
 & "$llvmRoot/llvm-ar$ToolSuffix" rcs "$outputRoot/lib/libopencore-amr$mode.a" @objects
 if($LASTEXITCODE -ne 0){throw 'Archive failed'}
 Copy-Item "$sourceRoot/amr$mode/*.h" "$outputRoot/include/opencore-amr$mode/"
 Write-Output "amr$mode compiled sources=$($sources.Count)"
}
