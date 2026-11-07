# SPDX-License-Identifier: Apache-2.0
# Self-contained regression test: no network, NDK, or Pester dependency.
$ErrorActionPreference='Stop'
$preparer=Join-Path $PSScriptRoot '../prepare-native-sources.ps1'
$root=Join-Path ([IO.Path]::GetTempPath()) ("jlmod-native-fetch-$PID-$([guid]::NewGuid().ToString('N'))")
New-Item -ItemType Directory -Force -Path $root | Out-Null

function Assert-True([bool]$condition,[string]$message){
 if(!$condition){throw "FAIL: $message"}
}

# The child script resolves these test-local functions instead of the network
# cmdlet and wait. This exercises its real archive/checksum/extraction boundary.
$global:FetchAttempts=@{}
$global:FetchFailure='none'
$global:FetchFixtures=@{}
function Invoke-WebRequest {
 [CmdletBinding()]
 param([Parameter(Mandatory)][string]$Uri,[Parameter(Mandatory)][string]$OutFile)
 $count=1
 if($global:FetchAttempts.ContainsKey($Uri)){$count=$global:FetchAttempts[$Uri]+1}
 $global:FetchAttempts[$Uri]=$count
 if($global:FetchFailure -eq 'reset-always' -or ($global:FetchFailure -eq 'reset-once' -and $count -eq 1 -and $Uri -like '*/ffmpeg')){
  [IO.File]::WriteAllText($OutFile,'partial')
  throw [System.IO.IOException]::new('Connection reset by peer')
 }
 if($global:FetchFailure -eq 'not-found'){
  throw [System.Net.Http.HttpRequestException]::new('Not found',$null,[System.Net.HttpStatusCode]::NotFound)
 }
 Copy-Item -LiteralPath $global:FetchFixtures[$Uri] -Destination $OutFile
}
function Start-Sleep {
 [CmdletBinding()]
 param([int]$Seconds)
}

try{
 $ffUri='https://fixture.invalid/ffmpeg'
 $amrUri='https://fixture.invalid/opencore'
 foreach($name in @('ffmpeg','opencore')){
  $src=Join-Path $root "fixture-$name"
  $nested=Join-Path $src $name
  New-Item -ItemType Directory -Force -Path $nested | Out-Null
  [IO.File]::WriteAllText((Join-Path $nested 'marker.txt'),"pinned $name")
  $archive=Join-Path $root "$name.tar"
  & tar -cf $archive -C $src $name
  if($LASTEXITCODE -ne 0){throw "Fixture tar failed: $name"}
  $uri=if($name -eq 'ffmpeg'){$ffUri}else{$amrUri}
  $global:FetchFixtures[$uri]=$archive
 }
 $manifest=@{
  ffmpeg=@{archive='ffmpeg.tar';url=$ffUri;sha256=(Get-FileHash $global:FetchFixtures[$ffUri] -Algorithm SHA256).Hash}
  opencore=@{archive='opencore.tar';url=$amrUri;sha256=(Get-FileHash $global:FetchFixtures[$amrUri] -Algorithm SHA256).Hash}
 }
 $manifestFile=Join-Path $root 'manifest.json'
 $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifestFile

 # A mid-stream reset must discard partial bytes before a fresh retry.
 $global:FetchFailure='reset-once'
 $global:FetchAttempts=@{}
 $out=Join-Path $root 'recover'
 & $preparer -OutRoot $out -Manifest $manifestFile
 Assert-True ($global:FetchAttempts[$ffUri] -eq 2) 'connection reset must trigger exactly one retry'
 Assert-True ($global:FetchAttempts[$amrUri] -eq 1) 'successful archive needs no retry'
 Assert-True (([IO.File]::ReadAllText((Join-Path $out 'ffmpeg/marker.txt'))) -eq 'pinned ffmpeg') 'verified archive must extract'
 Assert-True ((Get-ChildItem (Join-Path $out 'archives') -Filter '*.download-*').Count -eq 0) 'partial download must be removed'

 # Verified archives must be reused without contacting the network.
 $global:FetchFailure='reset-always'
 $global:FetchAttempts=@{}
 & $preparer -OutRoot $out -Manifest $manifestFile
 Assert-True ($global:FetchAttempts.Count -eq 0) 'verified cached archives must not be downloaded again'

 # Terminal transport errors have a hard cap; no partial archive is published.
 $global:FetchFailure='reset-always'
 $global:FetchAttempts=@{}
 $failed=$false
 $failedOut=Join-Path $root 'transport-failure'
 try{& $preparer -OutRoot $failedOut -Manifest $manifestFile}catch{$failed=$true}
 Assert-True $failed 'persistent transport error must fail'
 Assert-True ($global:FetchAttempts[$ffUri] -eq 3) 'must stop after three download attempts'
 Assert-True (!(Test-Path (Join-Path $failedOut 'archives/ffmpeg.tar'))) 'failed download must not be cached'
 Assert-True ((Get-ChildItem (Join-Path $failedOut 'archives') -Filter '*.download-*').Count -eq 0) 'partial file must be cleaned on final failure'

 # Nonretryable HTTP errors fail on the first request.
 $global:FetchFailure='not-found'
 $global:FetchAttempts=@{}
 $failed=$false
 try{& $preparer -OutRoot (Join-Path $root 'http-failure') -Manifest $manifestFile}catch{$failed=$true}
 Assert-True $failed 'HTTP 404 must fail'
 Assert-True ($global:FetchAttempts[$ffUri] -eq 1) 'HTTP 404 must not retry'

 # A successfully downloaded file with incorrect SHA256 must never be promoted
 # or retried as a transport failure.
 $global:FetchFailure='none'
 $global:FetchAttempts=@{}
 $bad=@{
  ffmpeg=@{archive='ffmpeg.tar';url=$ffUri;sha256=('0'*64)}
  opencore=$manifest.opencore
 }
 $badManifest=Join-Path $root 'bad-manifest.json'
 $bad | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $badManifest
 $badOut=Join-Path $root 'checksum-failure'
 $failed=$false
 try{& $preparer -OutRoot $badOut -Manifest $badManifest}catch{$failed=$true}
 Assert-True $failed 'SHA256 mismatch must fail'
 Assert-True ($global:FetchAttempts[$ffUri] -eq 1) 'SHA256 mismatch must not trigger a download retry'
 Assert-True (!(Test-Path (Join-Path $badOut 'archives/ffmpeg.tar'))) 'invalid archive must not be published'
 Assert-True ((Get-ChildItem (Join-Path $badOut 'archives') -Filter '*.download-*').Count -eq 0) 'invalid download must be cleaned'

 Write-Output 'PASS native-source download retry, cached reuse, HTTP failure, and checksum isolation'
}finally{
 Remove-Item -Recurse -Force -LiteralPath $root -ErrorAction SilentlyContinue
 Remove-Item Function:Invoke-WebRequest,Function:Start-Sleep -ErrorAction SilentlyContinue
 Remove-Variable FetchAttempts,FetchFailure,FetchFixtures -Scope Global -ErrorAction SilentlyContinue
}
