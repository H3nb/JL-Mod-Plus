# SPDX-License-Identifier: Apache-2.0
param(
 [Parameter(Mandatory)][string]$OutRoot,
 [string]$Manifest="$PSScriptRoot/native-sources.json",
 [switch]$ForceExtract
)
$ErrorActionPreference='Stop'
$OutRoot=[IO.Path]::GetFullPath($OutRoot).Replace('\\','/')
$Manifest=[IO.Path]::GetFullPath($Manifest).Replace('\\','/')
$tar=(Get-Command tar).Source
$spec=Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
$archiveRoot="$OutRoot/archives"
New-Item -ItemType Directory -Force -Path $OutRoot,$archiveRoot | Out-Null

function FetchPinned($entry){
 $archive="$archiveRoot/$($entry.archive)"
 $expected=$entry.sha256.ToUpperInvariant()
 if(Test-Path -LiteralPath $archive){
  $actual=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToUpperInvariant()
  if($actual -eq $expected){return $archive}
  Remove-Item -Force -LiteralPath $archive
 }
 $temporary="$archive.download-$PID"
 if(Test-Path -LiteralPath $temporary){Remove-Item -Force -LiteralPath $temporary}
 try{
  Invoke-WebRequest -Uri $entry.url -OutFile $temporary
  $actual=(Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToUpperInvariant()
  if($actual -ne $expected){throw "Checksum mismatch: $temporary"}
  Move-Item -LiteralPath $temporary -Destination $archive
 }finally{
  if(Test-Path -LiteralPath $temporary){Remove-Item -Force -LiteralPath $temporary}
 }
 return $archive
}

function ExpandPinned($archive,$destination,$hash){
 $stamp="$destination/.jlmod-source-sha256"
 if(!$ForceExtract -and (Test-Path -LiteralPath $stamp) -and ([IO.File]::ReadAllText($stamp).Trim() -eq $hash)){return}
 $temporary="$destination.tmp"
 foreach($path in @($temporary,$destination)){if(Test-Path -LiteralPath $path){Remove-Item -Recurse -Force -LiteralPath $path}}
 New-Item -ItemType Directory -Path $temporary | Out-Null
 & $tar -xf $archive --strip-components=1 -C $temporary
 if($LASTEXITCODE){throw "Source extraction failed: $archive"}
 Set-Content -NoNewline -LiteralPath "$temporary/.jlmod-source-sha256" -Value $hash
 Move-Item -LiteralPath $temporary -Destination $destination
}

$lockPath="$OutRoot/.prepare.lock"
$preparationLock=$null
$deadline=[DateTime]::UtcNow.AddMinutes(5)
while($null -eq $preparationLock){
 try{
  $preparationLock=[IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
 }catch [IO.IOException]{
  if([DateTime]::UtcNow -ge $deadline){throw "Timed out waiting for native source preparation lock: $lockPath"}
  Start-Sleep -Milliseconds 200
 }
}
try{
 $ffmpegArchive=FetchPinned $spec.ffmpeg
 $openCoreArchive=FetchPinned $spec.opencore
 ExpandPinned $ffmpegArchive "$OutRoot/ffmpeg" $spec.ffmpeg.sha256.ToUpperInvariant()
 ExpandPinned $openCoreArchive "$OutRoot/opencore-amr" $spec.opencore.sha256.ToUpperInvariant()

 $expectedArchives=@($spec.ffmpeg.archive,$spec.opencore.archive)
 Get-ChildItem -LiteralPath $archiveRoot -File | Where-Object {$_.Name -notin $expectedArchives} | Remove-Item -Force
 $context=[ordered]@{
  manifestSha256=(Get-FileHash -LiteralPath $Manifest -Algorithm SHA256).Hash
  ffmpegVersion=$spec.ffmpeg.version
  ffmpegSha256=$spec.ffmpeg.sha256.ToUpperInvariant()
  openCoreVersion=$spec.opencore.version
  openCoreSha256=$spec.opencore.sha256.ToUpperInvariant()
 }
 $context | ConvertTo-Json | Set-Content -LiteralPath "$OutRoot/source-context.json"
 Write-Output "PASS prepared pinned native audio sources => $OutRoot"
}finally{
 if($null -ne $preparationLock){$preparationLock.Dispose()}
}
