# SPDX-License-Identifier: Apache-2.0
# Host regression for the smoke wrapper's failure/cleanup ordering; never contacts a device.
$ErrorActionPreference = 'Stop'
$smokeScript = Join-Path $PSScriptRoot '../runtime-shutdown-smoke.ps1'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$allowedPrefix = $tempBase.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$taskDirectory = [IO.Path]::GetFullPath((Join-Path $tempBase ('jlmod-runtime-smoke-tests-' + [guid]::NewGuid())))
if (-not $taskDirectory.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Test directory must remain within the system temporary directory'
}
$null = [IO.Directory]::CreateDirectory($taskDirectory)
$previousMode = $env:JLMOD_RUNTIME_SMOKE_TEST_MODE
$previousLog = $env:JLMOD_RUNTIME_SMOKE_TEST_LOG
try {
    $fakeAdb = Join-Path $taskDirectory 'fake-adb.ps1'
    [IO.File]::WriteAllText($fakeAdb, @'
$global:LASTEXITCODE = 0
if ($args -contains 'instrument') {
    $command = $args[-2]
    [IO.File]::AppendAllText($env:JLMOD_RUNTIME_SMOKE_TEST_LOG, "$command`n")
    if ($command -eq 'remove') {
        if ($env:JLMOD_RUNTIME_SMOKE_TEST_MODE -eq 'setup-failure') {
            Write-Output 'INSTRUMENTATION_FAILED: setup failed before checkpoint'
        } else {
            Write-Output 'INSTRUMENTATION_STATUS: shutdownRequested=remove'
            if ($env:JLMOD_RUNTIME_SMOKE_TEST_MODE -eq 'fixture-failure') {
                Write-Output 'FAILURES!!!'
            }
        }
        exit 1
    }
    if ($command -eq 'cleanup' -and $env:JLMOD_RUNTIME_SMOKE_TEST_MODE -eq 'both-fail') {
        Write-Output 'FAILURES!!!'
        exit 1
    }
    Write-Output 'OK (1 test)'
    exit 0
}
if ($args -contains 'ps') {
    [IO.File]::AppendAllText($env:JLMOD_RUNTIME_SMOKE_TEST_LOG, "observe`n")
    Write-Output 'process query refused'
    exit 1
}
throw ('Unexpected fake ADB arguments: ' + ($args -join ' '))
'@)
    foreach ($mode in @('observer-failure', 'setup-failure', 'fixture-failure', 'both-fail')) {
        $env:JLMOD_RUNTIME_SMOKE_TEST_MODE = $mode
        $env:JLMOD_RUNTIME_SMOKE_TEST_LOG = Join-Path $taskDirectory "$mode.log"
        $transcript = [Collections.Generic.List[object]]::new()
        $failure = $null
        try {
            & $smokeScript -Serial 'emulator-9999' -Adb $fakeAdb *>&1 |
                ForEach-Object { $transcript.Add($_) }
        } catch {
            $failure = $_
        }
        $expected = if ($mode -eq 'setup-failure') {
            'Fixture did not request remove'
        } elseif ($mode -eq 'fixture-failure') {
            'Fixture remove failed'
        } else {
            'Unable to query emulator processes'
        }
        if ($null -eq $failure -or $failure.Exception.Message -ne $expected) {
            throw "Original failure was not preserved for $mode`: $failure"
        }
        $calls = @(Get-Content -LiteralPath $env:JLMOD_RUNTIME_SMOKE_TEST_LOG)
        $expectedCalls = if ($mode -in @('setup-failure', 'fixture-failure')) {
            'remove,cleanup'
        } else { 'remove,observe,cleanup' }
        if (($calls -join ',') -ne $expectedCalls) {
            throw "Unexpected cleanup/observation order for $mode`: $calls"
        }
        $text = ($transcript | ForEach-Object { $_.ToString() }) -join "`n"
        if ($text -match '(?m)^PASS:') { throw "Failed observation claimed PASS for $mode" }
        if ($mode -eq 'both-fail' -and $text -notmatch 'Cleanup also failed: Fixture cleanup failed') {
            throw 'Cleanup failure was not surfaced alongside the original failure'
        }
        Write-Output "PASS: smoke wrapper cleanup preserves $mode"
    }
} finally {
    $env:JLMOD_RUNTIME_SMOKE_TEST_MODE = $previousMode
    $env:JLMOD_RUNTIME_SMOKE_TEST_LOG = $previousLog
    $resolvedCleanup = (Resolve-Path -LiteralPath $taskDirectory).Path
    if ($resolvedCleanup -ne $taskDirectory -or
        -not $resolvedCleanup.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside the verified test directory'
    }
    Remove-Item -LiteralPath $resolvedCleanup -Recurse -Force
}
