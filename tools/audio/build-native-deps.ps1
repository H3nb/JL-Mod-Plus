# SPDX-License-Identifier: Apache-2.0
# PowerShell 7; use the project's selected NDK rather than another compiler.
param([Parameter(Mandatory)][string]$OutRoot,[string[]]$Abis=@('arm64-v8a'),
 [string]$Sdk=$env:ANDROID_HOME,[string]$NdkVersion='28.2.13676358',
 [string]$GitBash)
$ErrorActionPreference='Stop'
$OutRoot=[IO.Path]::GetFullPath($OutRoot).Replace('\','/')
$Sdk=[IO.Path]::GetFullPath($Sdk).Replace('\','/')
$ndkRoot="$Sdk/ndk/$NdkVersion"
$hostTag=if($IsWindows){'windows-x86_64'}elseif($IsMacOS){'darwin-x86_64'}else{'linux-x86_64'}
$llvmRoot="$ndkRoot/toolchains/llvm/prebuilt/$hostTag/bin"
$exe=if($IsWindows){'.exe'}else{''}
$make=if($IsWindows){"$ndkRoot/prebuilt/$hostTag/bin/make.exe"}else{(Get-Command make).Source}
$tar=(Get-Command tar).Source
if(!$GitBash){$GitBash=if($IsWindows){"$env:ProgramFiles/Git/usr/bin/bash.exe"}else{(Get-Command bash).Source}}
$shell=if($IsWindows){Join-Path (Split-Path $GitBash) 'sh.exe'}else{(Get-Command sh).Source}
foreach($required in @($GitBash,$make,"$llvmRoot/clang$exe")){if(!(Test-Path -LiteralPath $required)){throw "Missing native build tool: $required"}}
$recipeHash=(@('build-native-deps.ps1','build-opencore.ps1','install-public-headers.ps1') | ForEach-Object {
 (Get-FileHash -Algorithm SHA256 -LiteralPath "$PSScriptRoot/$_").Hash
}) -join ':'
$sourceRoot="$OutRoot/ffmpeg-8.1.3"
$amrRoot="$OutRoot/opencore-amr-0.1.6"
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
function FetchPinned($url,$file,$hash){
 if(!(Test-Path -LiteralPath $file)){Invoke-WebRequest -Uri $url -OutFile $file}
 if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $hash){throw "Checksum mismatch: $file"}
}
FetchPinned 'https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz' "$OutRoot/ffmpeg-8.1.3.tar.xz" '7138D28C96D9D3E3AF4EE3D8CAD72741F8FFB40DA90C1112235DEA3ECD3178A3'
FetchPinned 'https://codeload.github.com/arthenica/opencore-amr/tar.gz/7dba8c32238418ce0b316a852b2224df586ca896' "$OutRoot/opencore-amr-7dba8c3.tar.gz" 'FC302CEA3B65072F87D950D77EE5A7014347536039A7DE7668DA2891D386147E'
foreach($spec in @(@($sourceRoot,"$OutRoot/ffmpeg-8.1.3.tar.xz"),@($amrRoot,"$OutRoot/opencore-amr-7dba8c3.tar.gz"))){
 if(!(Test-Path -LiteralPath $spec[0])){New-Item -ItemType Directory -Path $spec[0] | Out-Null; & $tar -xf $spec[1] --strip-components=1 -C $spec[0];if($LASTEXITCODE){throw 'Source extraction failed'}}
}
$profiles=@{
 'arm64-v8a'=@('aarch64-linux-android23','aarch64','armv8-a','')
 'armeabi-v7a'=@('armv7a-linux-androideabi23','arm','armv7-a','--build-suffix=_neon')
 'x86'=@('i686-linux-android23','x86','i686','')
 'x86_64'=@('x86_64-linux-android23','x86_64','generic','')
}
foreach($abi in (($Abis -join ',') -split ',')){
 $profile=$profiles[$abi];if(!$profile){throw "Unknown ABI: $abi"}
 # A new upstream version must never reuse objects from an older source tree.
 $buildRoot="$OutRoot/build-ffmpeg-8.1.3-$abi"
 $installRoot="$OutRoot/install-$abi"
 $stamp="$installRoot/recipe.txt"
 $signature="$recipeHash`:$NdkVersion`:$abi`:$hostTag"
 if((Test-Path -LiteralPath $stamp) -and ([IO.File]::ReadAllText($stamp).Trim() -eq $signature) -and
     (Get-ChildItem "$installRoot/lib" -Filter '*.so').Count -eq 4){Write-Output "Cached native audio dependencies: $abi";continue}
 $amrOut="$OutRoot/opencore-$abi"
 & "$PSScriptRoot/build-opencore.ps1" -Target $profile[0] -DecoderOnly -SourceRoot $amrRoot -OutputRoot $amrOut -LlvmRoot $llvmRoot -ToolSuffix $exe
 New-Item -ItemType Directory -Force -Path $buildRoot,$installRoot | Out-Null
 # Windows paths avoid rewriting SRC_PATH/SRC_LINK after POSIX configure.
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
 $script="#!/bin/sh`nset -eu`nexport PATH=/usr/bin:/bin:`$PATH`ncd '$buildRoot'`n'$sourceRoot/configure' " + ($quoted -join ' ') + "`n"
 [IO.File]::WriteAllText("$buildRoot/configure.sh",$script,[Text.UTF8Encoding]::new($false))
 & $GitBash "$buildRoot/configure.sh" > "$buildRoot/configure.txt" 2>&1
 if($LASTEXITCODE){throw "Configure failed: $abi"}
 # Native Windows GNU make needs DOS-style source paths; configure's cwd is POSIX.
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
 # Retire only the old, recipe-owned libraries that are no longer configured.
 $suffix=if($abi -eq 'armeabi-v7a'){'_neon'}else{''}
 foreach($retired in @('avdevice','avfilter','swscale')){
  $retiredPath=Join-Path $installRoot "lib/lib$retired$suffix.so"
  if(Test-Path -LiteralPath $retiredPath){Remove-Item -LiteralPath $retiredPath}
 }
 if((Get-ChildItem "$installRoot/lib" -Filter '*.so').Count -ne 4){throw "Unexpected native audio library set: $abi"}
 Get-ChildItem "$installRoot/lib" -Filter '*.so' | ForEach-Object {[pscustomobject]@{name=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}} | ConvertTo-Json | Set-Content "$installRoot/checksums.json"
 Set-Content -LiteralPath $stamp -Value $signature
 Write-Output "PASS build $abi => $installRoot"
}
