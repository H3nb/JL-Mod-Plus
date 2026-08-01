# Copyright 2026 H3NB
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

<#
.SYNOPSIS
Downloads one official Material Symbol as an Android VectorDrawable.

.DESCRIPTION
Fetches a 24 px Android VectorDrawable from Google's material-design-icons
repository, validates the XML, writes it to a drawable directory, and records
reproducible source and checksum information in a local lock file.

The script does not select icons, edit application code, or require Android
Studio. Use the Google Design MCP icon search first, then pass the selected
symbol name explicitly.

.EXAMPLE
.\scripts\fetch-material-symbol.ps1 -Name database_search

.EXAMPLE
.\scripts\fetch-material-symbol.ps1 -Name speed_2 -Style Rounded -DryRun
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9]+(?:_[a-z0-9]+)*$')]
    [string]$Name,

    [ValidateSet('Outlined', 'Rounded', 'Sharp')]
    [string]$Style = 'Outlined',

    [ValidatePattern('^ic_[a-z0-9]+(?:_[a-z0-9]+)*$')]
    [string]$ResourceName,

    [string]$DestinationDirectory = 'app/src/main/res/drawable',

    [string]$LockFile = 'scripts/material-symbols-lock.json',

    [ValidatePattern('^(master|[0-9a-fA-F]{40})$')]
    [string]$Revision = 'master',

    [switch]$DryRun,

    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repositoryRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$sourceRepository = 'https://github.com/google/material-design-icons'
$sourceGitRepository = "$sourceRepository.git"
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)

function Resolve-RepositoryPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $candidate = if ([System.IO.Path]::IsPathRooted($Path)) {
        [System.IO.Path]::GetFullPath($Path)
    }
    else {
        [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Path))
    }

    $rootPrefix = $repositoryRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar

    if (-not $candidate.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description must remain inside repository root: $repositoryRoot"
    }

    return $candidate
}

function Convert-ToRepositoryRelativePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $rootUri = New-Object System.Uri(
        $repositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
        [System.IO.Path]::DirectorySeparatorChar
    )
    $pathUri = New-Object System.Uri([System.IO.Path]::GetFullPath($Path))
    return [System.Uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString())
}

function Resolve-SourceRevision {
    if ($Revision -ne 'master') {
        return $Revision.ToLowerInvariant()
    }

    if ($null -eq (Get-Command git -ErrorAction SilentlyContinue)) {
        throw 'Git is required to resolve the official master branch to an immutable commit.'
    }

    $remoteResult = @(& git ls-remote --exit-code $sourceGitRepository refs/heads/master 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not resolve the official Material Symbols revision: $($remoteResult -join ' ')"
    }

    foreach ($line in $remoteResult) {
        if ($line -match '^([0-9a-fA-F]{40})\s+refs/heads/master$') {
            return $Matches[1].ToLowerInvariant()
        }
    }

    throw 'The official Material Symbols repository returned an unexpected revision response.'
}

function Get-Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes
    )

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha256.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Assert-VectorDrawable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Content
    )

    $settings = New-Object System.Xml.XmlReaderSettings
    $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null

    $document = New-Object System.Xml.XmlDocument
    $document.XmlResolver = $null
    $stringReader = New-Object System.IO.StringReader($Content)
    $reader = $null

    try {
        $reader = [System.Xml.XmlReader]::Create($stringReader, $settings)
        $document.Load($reader)
    }
    catch {
        throw "Downloaded content is not safe, valid XML: $($_.Exception.Message)"
    }
    finally {
        if ($null -ne $reader) {
            $reader.Dispose()
        }
        $stringReader.Dispose()
    }

    if ($null -eq $document.DocumentElement -or $document.DocumentElement.LocalName -ne 'vector') {
        throw 'Downloaded XML is not an Android VectorDrawable.'
    }

    $root = $document.DocumentElement
    if ($root.GetAttribute('width', $androidNamespace) -ne '24dp' -or
        $root.GetAttribute('height', $androidNamespace) -ne '24dp') {
        throw 'Expected an official 24dp Material Symbol VectorDrawable.'
    }

    $invariant = [System.Globalization.CultureInfo]::InvariantCulture
    $numberStyle = [System.Globalization.NumberStyles]::Float
    $viewportWidth = 0.0
    $viewportHeight = 0.0
    if (-not [double]::TryParse(
            $root.GetAttribute('viewportWidth', $androidNamespace),
            $numberStyle,
            $invariant,
            [ref]$viewportWidth
        ) -or $viewportWidth -le 0 -or
        -not [double]::TryParse(
            $root.GetAttribute('viewportHeight', $androidNamespace),
            $numberStyle,
            $invariant,
            [ref]$viewportHeight
        ) -or $viewportHeight -le 0) {
        throw 'VectorDrawable viewport dimensions must be positive numbers.'
    }

    $paths = @($document.GetElementsByTagName('path'))
    if ($paths.Count -eq 0) {
        throw 'VectorDrawable does not contain any path elements.'
    }

    $hasPathData = $false
    foreach ($path in $paths) {
        if (-not [string]::IsNullOrWhiteSpace($path.GetAttribute('pathData', $androidNamespace))) {
            $hasPathData = $true
            break
        }
    }

    if (-not $hasPathData) {
        throw 'VectorDrawable does not contain any non-empty pathData.'
    }
}

function Read-LockEntries {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $entries = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $entries
    }

    try {
        $lock = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    }
    catch {
        throw "Material Symbols lock file is invalid JSON: $Path"
    }

    if ($lock.schemaVersion -ne 1 -or $null -eq $lock.icons) {
        throw "Unsupported Material Symbols lock file schema: $Path"
    }

    foreach ($property in $lock.icons.PSObject.Properties) {
        $entries[$property.Name] = $property.Value
    }

    return $entries
}

function Write-TransactionalPair {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AssetPath,

        [Parameter(Mandatory = $true)]
        [string]$AssetContent,

        [Parameter(Mandatory = $true)]
        [string]$MetadataPath,

        [Parameter(Mandatory = $true)]
        [string]$MetadataContent
    )

    $id = [System.Guid]::NewGuid().ToString('N')
    $assetTemporary = Join-Path (Split-Path -Parent $AssetPath) ".$([System.IO.Path]::GetFileName($AssetPath)).$id.tmp"
    $metadataTemporary = Join-Path (Split-Path -Parent $MetadataPath) ".$([System.IO.Path]::GetFileName($MetadataPath)).$id.tmp"
    $assetBackup = "$AssetPath.$id.backup"
    $metadataBackup = "$MetadataPath.$id.backup"
    $assetExisted = Test-Path -LiteralPath $AssetPath -PathType Leaf
    $metadataExisted = Test-Path -LiteralPath $MetadataPath -PathType Leaf
    $assetInstalled = $false
    $metadataInstalled = $false

    try {
        [System.IO.File]::WriteAllText($assetTemporary, $AssetContent, $utf8WithoutBom)
        [System.IO.File]::WriteAllText($metadataTemporary, $MetadataContent, $utf8WithoutBom)

        if ($assetExisted) {
            Copy-Item -LiteralPath $AssetPath -Destination $assetBackup
        }
        if ($metadataExisted) {
            Copy-Item -LiteralPath $MetadataPath -Destination $metadataBackup
        }

        Move-Item -LiteralPath $assetTemporary -Destination $AssetPath -Force
        $assetInstalled = $true
        Move-Item -LiteralPath $metadataTemporary -Destination $MetadataPath -Force
        $metadataInstalled = $true
    }
    catch {
        if ($assetInstalled) {
            if ($assetExisted -and (Test-Path -LiteralPath $assetBackup -PathType Leaf)) {
                Copy-Item -LiteralPath $assetBackup -Destination $AssetPath -Force
            }
            elseif (Test-Path -LiteralPath $AssetPath -PathType Leaf) {
                Remove-Item -LiteralPath $AssetPath -Force
            }
        }

        if ($metadataInstalled) {
            if ($metadataExisted -and (Test-Path -LiteralPath $metadataBackup -PathType Leaf)) {
                Copy-Item -LiteralPath $metadataBackup -Destination $MetadataPath -Force
            }
            elseif (Test-Path -LiteralPath $MetadataPath -PathType Leaf) {
                Remove-Item -LiteralPath $MetadataPath -Force
            }
        }

        throw
    }
    finally {
        foreach ($temporaryPath in @($assetTemporary, $metadataTemporary, $assetBackup, $metadataBackup)) {
            if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
                Remove-Item -LiteralPath $temporaryPath -Force
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($ResourceName)) {
    $ResourceName = "ic_${Name}_24"
}

$styleDirectory = @{
    Outlined = 'materialsymbolsoutlined'
    Rounded = 'materialsymbolsrounded'
    Sharp = 'materialsymbolssharp'
}[$Style]

$destinationDirectoryPath = Resolve-RepositoryPath -Path $DestinationDirectory -Description 'Destination directory'
$lockFilePath = Resolve-RepositoryPath -Path $LockFile -Description 'Lock file'

if (-not (Test-Path -LiteralPath $destinationDirectoryPath -PathType Container)) {
    throw "Destination directory does not exist: $destinationDirectoryPath"
}

$lockDirectory = Split-Path -Parent $lockFilePath
if (-not (Test-Path -LiteralPath $lockDirectory -PathType Container)) {
    throw "Lock file directory does not exist: $lockDirectory"
}

$destinationPath = Join-Path $destinationDirectoryPath "$ResourceName.xml"
if ((Test-Path -LiteralPath $destinationPath -PathType Leaf) -and -not $Force -and -not $DryRun) {
    throw "Destination already exists. Review it and rerun with -Force to replace it: $destinationPath"
}

$resolvedRevision = Resolve-SourceRevision
$sourcePath = "symbols/android/$Name/$styleDirectory/${Name}_24px.xml"
$sourceUrl = "https://raw.githubusercontent.com/google/material-design-icons/$resolvedRevision/$sourcePath"

try {
    $response = Invoke-WebRequest -Uri $sourceUrl -UseBasicParsing -TimeoutSec 60 -Headers @{
        'Accept' = 'application/xml,text/plain;q=0.9'
        'User-Agent' = 'JL-Mod-Plus-Material-Symbol-Fetcher/1.0'
    }
}
catch {
    throw "Could not download Material Symbol '$Name' in style '$Style' from the official Google repository. Verify the MCP result and style. Source: $sourceUrl"
}

$content = [string]$response.Content
if ([string]::IsNullOrWhiteSpace($content)) {
    throw 'The official Material Symbols download was empty.'
}

$assetBytes = $utf8WithoutBom.GetBytes($content)
if ($assetBytes.Length -gt 1MB) {
    throw 'The downloaded VectorDrawable unexpectedly exceeds 1 MiB.'
}

Assert-VectorDrawable -Content $content
$sha256 = Get-Sha256 -Bytes $assetBytes
$destinationRelative = Convert-ToRepositoryRelativePath -Path $destinationPath
$lockRelative = Convert-ToRepositoryRelativePath -Path $lockFilePath

if (-not $DryRun) {
    $entries = Read-LockEntries -Path $lockFilePath
    $entries[$ResourceName] = [ordered]@{
        symbol = $Name
        style = $Style
        sourceRevision = $resolvedRevision
        sourcePath = $sourcePath
        sourceUrl = $sourceUrl
        sha256 = $sha256
        destination = $destinationRelative
    }

    $sortedEntries = [ordered]@{}
    foreach ($entryName in @($entries.Keys | Sort-Object)) {
        $sortedEntries[$entryName] = $entries[$entryName]
    }

    $lockDocument = [ordered]@{
        schemaVersion = 1
        repository = $sourceRepository
        license = 'Apache-2.0'
        icons = $sortedEntries
    }
    $lockContent = ($lockDocument | ConvertTo-Json -Depth 8) + [Environment]::NewLine

    Write-TransactionalPair `
        -AssetPath $destinationPath `
        -AssetContent $content `
        -MetadataPath $lockFilePath `
        -MetadataContent $lockContent
}

[PSCustomObject]@{
    Status = if ($DryRun) { 'Validated' } else { 'Downloaded' }
    Symbol = $Name
    Style = $Style
    Revision = $resolvedRevision
    SourceUrl = $sourceUrl
    Destination = $destinationRelative
    LockFile = $lockRelative
    Sha256 = $sha256
}
