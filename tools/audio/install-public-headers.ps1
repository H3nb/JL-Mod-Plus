# SPDX-License-Identifier: Apache-2.0
param([Parameter(Mandatory)][string]$SourceRoot,[Parameter(Mandatory)][string]$BuildRoot,[Parameter(Mandatory)][string]$InstallRoot)
$ErrorActionPreference='Stop'
foreach($module in @('libavcodec','libavformat','libavutil','libswresample')){
 $makefile=[IO.File]::ReadAllText("$SourceRoot/$module/Makefile") -replace '\\\r?\n',' '
 $headerText=[regex]::Match($makefile,'(?m)^HEADERS\s*=\s*([^\r\n]+)').Groups[1].Value
 $builtText=[regex]::Match($makefile,'(?m)^BUILT_HEADERS\s*=\s*([^\r\n]+)').Groups[1].Value
 $destination="$InstallRoot/include/$module"
 New-Item -ItemType Directory -Force -Path $destination | Out-Null
 foreach($header in @($headerText -split '\s+' | Where-Object {$_ -match '\.h$'})){Copy-Item -LiteralPath "$SourceRoot/$module/$header" -Destination $destination}
 foreach($header in @($builtText -split '\s+' | Where-Object {$_ -match '\.h$'})){Copy-Item -LiteralPath "$BuildRoot/$module/$header" -Destination $destination}
}
