# SPDX-License-Identifier: Apache-2.0
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^emulator-\d+$')]
    [string]$Serial,
    [string]$Adb = 'adb',
    [string]$Package = 'io.github.h3nb.jlmodplus.debug'
)

$ErrorActionPreference = 'Stop'
$fixture = 'io.github.h3nb.jlmodplus.crashes.RuntimeShutdownFixtureTest#externalShutdownFixture'
$runner = "$Package.test/androidx.test.runner.AndroidJUnitRunner"

function Invoke-Fixture([string]$Command) {
    $previousErrorPreference = $ErrorActionPreference
    try {
        # A successful shutdown may report instrumentation process death on stderr.
        $ErrorActionPreference = 'Continue'
        $output = & $Adb -s $Serial shell am instrument -w -r -e class $fixture `
            -e runtimeShutdownFixture $Command $runner 2>&1 | Out-String
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    Write-Output $output
    if ($Command -eq 'verify') {
        if ($output -notmatch 'OK \(1 test\)') {
            throw "Fixture $Command failed"
        }
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

# Both APKs must already be installed. Only an explicitly selected emulator is touched.
foreach ($action in @('remove', 'exit')) {
    # A new instrumentation invocation force-stops the target package. Prepare and trigger in
    # one invocation; its checkpoint proves all three processes were live after Android Home.
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
    $servicePattern = 'ServiceRecord\{[^\r\n]*\s' + [regex]::Escape($Package) + '/'
    if ($services -match $servicePattern) { throw "Service survived $action" }
    Invoke-Fixture 'verify'
    Write-Output "PASS: $action stopped all emulator processes and reopened Library without a report"
}
