[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$')]
    [string]$VersionName
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$versionFile = Join-Path $repositoryRoot 'version.properties'

if (-not (Test-Path -LiteralPath $versionFile)) {
    throw "Version file not found: $versionFile"
}

$properties = @{}
Get-Content -LiteralPath $versionFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
        $properties[$matches[1]] = $matches[2]
    }
}

$currentName = $properties['VERSION_NAME']
$currentCode = 0
if (-not [int]::TryParse($properties['VERSION_CODE'], [ref]$currentCode) -or $currentCode -lt 1) {
    throw 'VERSION_CODE must be a positive integer.'
}

if ($VersionName -eq $currentName) {
    throw "Version is already $VersionName. Choose a different release version."
}

$newCode = $currentCode + 1
$content = @(
    '# Public version shown to users. Use scripts/set-version.ps1 to change it.'
    "VERSION_NAME=$VersionName"
    ''
    '# Positive, monotonically increasing Android release number.'
    "VERSION_CODE=$newCode"
    ''
) -join [Environment]::NewLine

[System.IO.File]::WriteAllText($versionFile, $content, [System.Text.UTF8Encoding]::new($false))
Write-Output "Version updated: $currentName ($currentCode) -> $VersionName ($newCode)"
