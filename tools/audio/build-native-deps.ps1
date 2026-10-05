# SPDX-License-Identifier: Apache-2.0
# PowerShell 7; use the project's selected NDK rather than another compiler.
param(
 [Parameter(Mandatory)][string]$OutRoot,
 [string]$WorkRoot=$OutRoot,
 [string]$SourcesRoot="$OutRoot/sources",
 [string[]]$Abis=@('arm64-v8a'),
 [string]$Sdk=$env:ANDROID_HOME,
 [string]$NdkVersion='30.0.16248370',
 [int]$AndroidApi=23,
 [string]$GitBash
)
$ErrorActionPreference='Stop'
$OutRoot=[IO.Path]::GetFullPath($OutRoot).Replace('\\','/')
$WorkRoot=[IO.Path]::GetFullPath($WorkRoot).Replace('\\','/')
$SourcesRoot=[IO.Path]::GetFullPath($SourcesRoot).Replace('\\','/')
$Sdk=[IO.Path]::GetFullPath($Sdk).Replace('\\','/')
$ndkRoot="$Sdk/ndk/$NdkVersion"
$hostTag=if($IsWindows){'windows-x86_64'}elseif($IsMacOS){'darwin-x86_64'}else{'linux-x86_64'}
$llvmRoot="$ndkRoot/toolchains/llvm/prebuilt/$hostTag/bin"
$exe=if($IsWindows){'.exe'}else{''}
$make=if($IsWindows){"$ndkRoot/prebuilt/$hostTag/bin/make.exe"}else{(Get-Command make).Source}
if(!$GitBash){$GitBash=if($IsWindows){"$env:ProgramFiles/Git/usr/bin/bash.exe"}else{(Get-Command bash).Source}}
$shell=if($IsWindows){Join-Path (Split-Path $GitBash) 'sh.exe'}else{(Get-Command sh).Source}
foreach($required in @($GitBash,$make,"$llvmRoot/clang$exe")){if(!(Test-Path -LiteralPath $required)){throw "Missing native build tool: $required"}}
$recipeHash=(@('build-native-deps.ps1','build-opencore.ps1','install-public-headers.ps1','prepare-native-sources.ps1','native-sources.json') | ForEach-Object {
 (Get-FileHash -Algorithm SHA256 -LiteralPath "$PSScriptRoot/$_").Hash
}) -join ':'
$sourceRoot="$SourcesRoot/ffmpeg"
$amrRoot="$SourcesRoot/opencore-amr"
if(!(Test-Path -LiteralPath "$sourceRoot/.jlmod-source-sha256") -or !(Test-Path -LiteralPath "$amrRoot/.jlmod-source-sha256")){
 & "$PSScriptRoot/prepare-native-sources.ps1" -OutRoot $SourcesRoot -Manifest "$PSScriptRoot/native-sources.json"
}
$sourceSignature=([IO.File]::ReadAllText("$sourceRoot/.jlmod-source-sha256").Trim()+':'+[IO.File]::ReadAllText("$amrRoot/.jlmod-source-sha256").Trim())
New-Item -ItemType Directory -Force -Path $OutRoot,$WorkRoot | Out-Null
$profiles=@{
 'arm64-v8a'=@("aarch64-linux-android$AndroidApi",'aarch64','armv8-a','')
 'armeabi-v7a'=@("armv7a-linux-androideabi$AndroidApi",'arm','armv7-a','--build-suffix=_neon')
 'x86'=@("i686-linux-android$AndroidApi",'x86','i686','')
 'x86_64'=@("x86_64-linux-android$AndroidApi",'x86_64','generic','')
}
foreach($abi in (($Abis -join ',') -split ',')){
 $profile=$profiles[$abi];if(!$profile){throw "Unknown ABI: $abi"}
 $buildRoot="$WorkRoot/build-ffmpeg-$abi"
 $installRoot="$OutRoot/install-$abi"
 $amrOut="$WorkRoot/opencore-$abi"
 $stamp="$installRoot/recipe.txt"
 $signature='{0}:{1}:{2}:{3}:{4}:{5}' -f $recipeHash,$sourceSignature,$NdkVersion,$AndroidApi,$abi,$hostTag
 if((Test-Path -LiteralPath $stamp) -and ([IO.File]::ReadAllText($stamp).Trim() -eq $signature) -and
     (Get-ChildItem "$installRoot/lib" -Filter '*.so').Count -eq 4){Write-Output "Cached native audio dependencies: $abi";continue}
 foreach($path in @($buildRoot,$amrOut,$installRoot)){if(Test-Path -LiteralPath $path){Remove-Item -Recurse -Force -LiteralPath $path}}
 & "$PSScriptRoot/build-opencore.ps1" -Target $profile[0] -DecoderOnly -SourceRoot $amrRoot -OutputRoot $amrOut -LlvmRoot $llvmRoot -ToolSuffix $exe
 New-Item -ItemType Directory -Force -Path $buildRoot,$installRoot | Out-Null
 $configureArgs=@("--prefix=$installRoot",'--target-os=android',"--arch=$($profile[1])","--cpu=$($profile[2])",'--enable-cross-compile',
  "--cc=$llvmRoot/clang$exe","--cxx=$llvmRoot/clang++$exe","--ar=$llvmRoot/llvm-ar$exe","--ranlib=$llvmRoot/llvm-ranlib$exe","--nm=$llvmRoot/llvm-nm$exe","--strip=$llvmRoot/llvm-strip$exe",
  "--extra-cflags=--target=$($profile[0]) --sysroot=$ndkRoot/toolchains/llvm/prebuilt/$hostTag/sysroot -O2 -fPIC -I$amrOut/include",
  "--extra-ldflags=--target=$($profile[0]) --sysroot=$ndkRoot/toolchains/llvm/prebuilt/$hostTag/sysroot -Wl,-z,max-page-size=16384 -L$amrOut/lib",
  '--disable-autodetect','--disable-doc','--disable-programs','--disable-everything','--disable-avdevice','--disable-avfilter','--disable-swscale','--disable-network','--enable-version3','--enable-libopencore-amrnb','--enable-libopencore-amrwb',
  '--disable-asm','--disable-debug','--enable-small','--enable-pic','--enable-pthreads','--disable-static','--enable-shared','--enable-swresample',
  '--enable-demuxer=wav,mp3,aac,mov,amr,mmf',
  '--enable-parser=mpegaudio,aac,ac3,amr','--enable-decoder=pcm_u8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_alaw,pcm_mulaw,gsm_ms,adpcm_ima_wav,adpcm_yamaha,mp3float,aac,libopencore_amrnb,libopencore_amrwb')
 if($profile[3]){$configureArgs+=$profile[3]}
 $quoted=@($configureArgs | ForEach-Object {"'"+$_.Replace("'","'\''")+"'"})
 $scriptLines=@("#!/bin/sh","set -eu",'export PATH=/usr/bin:/bin:$PATH',"cd '$buildRoot'","'$sourceRoot/configure' " + ($quoted -join ' '))
 $script=$scriptLines -join [Environment]::NewLine
 [IO.File]::WriteAllText("$buildRoot/configure.sh",$script+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
 & $GitBash "$buildRoot/configure.sh" > "$buildRoot/configure.txt" 2>&1
 if($LASTEXITCODE){
  Get-Content -LiteralPath "$buildRoot/configure.txt" | Write-Output
  throw "Configure failed: $abi"
 }
 if($IsWindows){foreach($generated in @('Makefile','ffbuild/config.mak')){
  $path="$buildRoot/$generated";$text=[IO.File]::ReadAllText($path)
  $text=[regex]::Replace($text,'/([a-zA-Z])/',{param($m) "$($m.Groups[1].Value.ToUpper()):/"})
  [IO.File]::WriteAllText($path,$text,[Text.UTF8Encoding]::new($false))
 }}
 $oldPath=$env:PATH
 try{
  $env:PATH=(Split-Path $GitBash)+[IO.Path]::PathSeparator+$oldPath
  Push-Location $buildRoot
  try{& $make -j8 "SHELL=$shell" > build.txt 2>&1;if($LASTEXITCODE){throw "Build failed: $abi (see $buildRoot/build.txt)"}
   & $make install-libs "SHELL=$shell" > install.txt 2>&1;if($LASTEXITCODE){throw "Install failed: $abi (see $buildRoot/install.txt)"}
  }finally{Pop-Location}
 }finally{$env:PATH=$oldPath}
 & "$PSScriptRoot/install-public-headers.ps1" -SourceRoot $sourceRoot -BuildRoot $buildRoot -InstallRoot $installRoot
 $suffix=if($abi -eq 'armeabi-v7a'){'_neon'}else{''}
 foreach($retired in @('avdevice','avfilter','swscale')){
  $retiredPath=Join-Path $installRoot "lib/lib$retired$suffix.so"
  if(Test-Path -LiteralPath $retiredPath){Remove-Item -LiteralPath $retiredPath}
 }
 if((Get-ChildItem "$installRoot/lib" -Filter '*.so').Count -ne 4){throw "Unexpected native audio library set: $abi"}
 Get-ChildItem "$installRoot/lib" -Filter '*.so' | Sort-Object Name | ForEach-Object {[pscustomobject]@{name=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}} | ConvertTo-Json | Set-Content "$installRoot/checksums.json"
 Set-Content -NoNewline -LiteralPath $stamp -Value $signature
 Write-Output "PASS build $abi => $installRoot"
}
