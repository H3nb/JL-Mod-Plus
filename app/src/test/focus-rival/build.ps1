param(
    [string] $OutputDirectory = (Join-Path ([System.IO.Path]::GetTempPath()) 'jlmodplus-production-focus-rival'),
    [int] $CompileSdk = 37
)
$ErrorActionPreference = 'Stop'
$taskFixtureRoot = $PSScriptRoot
$taskRepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $taskFixtureRoot '../../../..'))
$taskOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
if ($taskOutput.Equals($taskRepositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $taskOutput.StartsWith($taskRepositoryRoot + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Write the companion APK and signing key outside the repository.'
}
$taskSdk = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($taskSdk)) { $taskSdk = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($taskSdk)) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
$taskJavaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else {
    Split-Path -Parent (Get-Command javac.exe -ErrorAction Stop).Source
}
$taskTools = Get-ChildItem -LiteralPath (Join-Path $taskSdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version] $_.Name } -Descending | Select-Object -First 1
if ($null -eq $taskTools) { throw 'Install a stable Android SDK build-tools version.' }
$taskPlatform = Join-Path $taskSdk "platforms/android-$CompileSdk/android.jar"
if (-not (Test-Path -LiteralPath $taskPlatform)) {
    $taskPlatform = Join-Path $taskSdk "platforms/android-$CompileSdk.0/android.jar"
}
if (-not (Test-Path -LiteralPath $taskPlatform)) { throw "Install the Android SDK platform for API $CompileSdk." }
$taskStage = Join-Path $taskOutput ('stage-' + [guid]::NewGuid().ToString('N'))
$taskClasses = Join-Path $taskStage 'classes'
$taskDex = Join-Path $taskStage 'dex'
New-Item -ItemType Directory -Force -Path $taskOutput,$taskClasses,$taskDex | Out-Null
$taskKey = Join-Path $taskOutput 'qualification-debug.keystore'
if (-not (Test-Path -LiteralPath $taskKey)) {
    # A disposable, standard Android debug key; no release/application credentials are read.
    & (Join-Path $taskJavaBin 'keytool.exe') -genkeypair -noprompt -keystore $taskKey `
            -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 `
            -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE) { throw 'Companion debug-key generation failed.' }
}
& (Join-Path $taskJavaBin 'javac.exe') -source 17 -target 17 -classpath $taskPlatform -d $taskClasses `
        (Join-Path $taskFixtureRoot 'src/io/github/h3nb/sonivox/productionfocusrival/FocusRivalService.java')
if ($LASTEXITCODE) { throw 'Companion Java compilation failed.' }
$taskClassJar = Join-Path $taskStage 'classes.jar'
& (Join-Path $taskJavaBin 'jar.exe') cf $taskClassJar -C $taskClasses .
if ($LASTEXITCODE) { throw 'Companion class packaging failed.' }
& (Join-Path $taskTools.FullName 'd8.bat') --lib $taskPlatform --min-api 26 --output $taskDex $taskClassJar
if ($LASTEXITCODE) { throw 'Companion DEX compilation failed.' }
$taskUnsigned = Join-Path $taskStage 'unsigned.apk'
& (Join-Path $taskTools.FullName 'aapt2.exe') link -o $taskUnsigned -I $taskPlatform `
        --manifest (Join-Path $taskFixtureRoot 'AndroidManifest.xml') --min-sdk-version 26 --target-sdk-version 36
if ($LASTEXITCODE) { throw 'Companion APK linking failed.' }
& (Join-Path $taskJavaBin 'jar.exe') uf $taskUnsigned -C $taskDex classes.dex
if ($LASTEXITCODE) { throw 'Companion DEX packaging failed.' }
$taskAligned = Join-Path $taskStage 'aligned.apk'
& (Join-Path $taskTools.FullName 'zipalign.exe') -f 4 $taskUnsigned $taskAligned
if ($LASTEXITCODE) { throw 'Companion APK alignment failed.' }
$taskApk = Join-Path $taskOutput 'focus-rival-debug.apk'
& (Join-Path $taskTools.FullName 'apksigner.bat') sign --ks $taskKey --ks-key-alias androiddebugkey `
        --ks-pass pass:android --key-pass pass:android --out $taskApk $taskAligned
if ($LASTEXITCODE) { throw 'Companion signing failed.' }
& (Join-Path $taskTools.FullName 'apksigner.bat') verify $taskApk
if ($LASTEXITCODE) { throw 'Companion signature verification failed.' }
Write-Output "Qualification companion: $taskApk"
