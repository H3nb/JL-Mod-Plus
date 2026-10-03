# SPDX-License-Identifier: Apache-2.0
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,
    [switch]$AllowPhysicalDevice,
    [switch]$IncludeDispatchFailure,
    [string]$Adb = 'adb',
    [string]$Package = 'io.github.h3nb.jlmodplus.debug'
)

$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$' -and -not $AllowPhysicalDevice) {
    throw 'Physical-device validation requires -AllowPhysicalDevice and an explicit serial'
}
$fixture = 'io.github.h3nb.jlmodplus.crashes.RuntimeShutdownFixtureTest#externalShutdownFixture'
$runner = "$Package.test/androidx.test.runner.AndroidJUnitRunner"

function Invoke-Fixture([string]$Command) {
    $previousErrorPreference = $ErrorActionPreference
    try {
        # A successful shutdown may report instrumentation process death on stderr.
        $ErrorActionPreference = 'Continue'
        $output = & $Adb -s $Serial shell am instrument -w -r -e class $fixture `
            -e runtimeShutdownFixture $Command $runner 2>&1 | Out-String
        $fixtureExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    Write-Output $output
    if ($Command -in @('verify', 'cleanup')) {
        if ($fixtureExitCode -ne 0 -or $output -notmatch 'OK \(1 test\)') {
            throw "Fixture $Command failed"
        }
    } elseif ($output -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2') {
        # A failed test can print its checkpoint before AndroidJUnitRunner force-stops the app.
        throw "Fixture $Command failed"
    } elseif ($output -notmatch "shutdownRequested=$Command") {
        throw "Fixture did not request $Command"
    }
}

function Get-EmulatorProcesses {
    $listing = & $Adb -s $Serial shell ps -A -o PID,NAME
    if ($LASTEXITCODE -ne 0) { throw 'Unable to query emulator processes' }
    $pattern = '\s' + [regex]::Escape($Package) + '(?::\S+)?\s*$'
    return @($listing | Where-Object { $_ -match $pattern })
}

# Both debug APKs must already be installed. Only the explicitly selected device is touched.
$actions = @('remove', 'exit')
if ($IncludeDispatchFailure) { $actions += 'fallback' }
foreach ($action in $actions) {
    # A new instrumentation invocation force-stops the target package. Prepare and trigger in
    # one invocation; its checkpoint proves all three processes were live after Android Home.
    $validationFailure = $null
    try {
        Invoke-Fixture $action
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $running = Get-EmulatorProcesses
            if ($running.Count -eq 0) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($running.Count -ne 0) { throw "Processes survived $action`: $running" }
        # Detect a service/provider restart after the initial processes have disappeared.
        for ($check = 0; $check -lt 10; $check++) {
            Start-Sleep -Milliseconds 300
            if ((Get-EmulatorProcesses).Count -ne 0) { throw "Emulator restarted after $action" }
        }
        $services = & $Adb -s $Serial shell dumpsys activity services $Package | Out-String
        if ($LASTEXITCODE -ne 0) { throw 'Unable to query emulator services' }
        $servicePattern = 'ServiceRecord\{[^\r\n]*\s' + [regex]::Escape($Package) + '/'
        if ($services -match $servicePattern) { throw "Service survived $action" }
        Invoke-Fixture 'verify'
    } catch {
        $validationFailure = $_
    } finally {
        # Instrumentation force-stops the target. Enter cleanup only after all observer assertions
        # have completed or their failure has been captured; it must never make observation pass.
        try {
            Invoke-Fixture 'cleanup'
        } catch {
            if ($null -eq $validationFailure) {
                $validationFailure = $_
            } else {
                Write-Warning "Cleanup also failed: $($_.Exception.Message). Original validation failure preserved."
            }
        }
    }
    if ($null -ne $validationFailure) { throw $validationFailure }
    Write-Output "PASS: $action stopped all emulator processes and reopened Library without a report"
}
