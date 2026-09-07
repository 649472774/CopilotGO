# Run existing instrumentation on an already installed, isolated fixture device.
# This script never launches an emulator, installs an APK, seeds data, or resets settings.
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('emulator-5582', 'emulator-5584')] [string]$Serial,
    [Parameter(Mandatory)] [ValidatePattern('^[a-z0-9-]+$')] [string]$Label,
    [Parameter(Mandatory)] [string]$EvidenceRoot,
    [Parameter(Mandatory)] [string]$Classes,
    [Parameter(Mandatory)] [ValidateRange(1, 10000)] [int]$ExpectedTests,
    [Parameter(Mandatory)] [ValidateRange(35, 2100000000)] [int]$ExpectedVersionCode,
    [string[]]$RunnerArguments = @(),
    [string]$ExpectedAppApk,
    [ValidateSet('portrait', 'landscape')] [string]$ExpectedOrientation
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$apksigner = Join-Path $sdkRoot 'build-tools\36.0.0\apksigner.bat'
$package = 'com.tongxie.copilotgo.debug'
$expectedAvd = @{
    'emulator-5582' = 'CopilotGO_Upgrade_AOSP_API31'
    'emulator-5584' = 'CopilotGO_Upgrade_AOSP_API36'
}[$Serial]
$expectedSdk = @{ 'emulator-5582' = '31'; 'emulator-5584' = '36' }[$Serial]

function Invoke-Device {
    param([string[]]$Arguments)
    $output = @(& $adb -s $Serial @Arguments 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) { throw "Explicit-device adb failed: $($Arguments -join ' ')" }
    return $output
}

function Get-InstalledApkHash {
    param([string]$PackageName)
    $paths = @(Invoke-Device @('shell', 'pm', 'path', $PackageName))
    if ($paths.Count -ne 1 -or $paths[0] -notmatch '^package:/data/app/.+\.apk$') {
        throw "Expected one installed fixture APK for $PackageName."
    }
    $path = $paths[0] -replace '^package:', ''
    $line = (Invoke-Device @('shell', 'sha256sum', $path)) -join ''
    if ($line -notmatch '^([a-f0-9]{64})\s') { throw "Cannot attest installed APK bytes for $PackageName." }
    return $Matches[1]
}

if (-not [IO.Path]::IsPathFullyQualified($EvidenceRoot) -or -not (Test-Path -LiteralPath $EvidenceRoot -PathType Container)) {
    throw 'EvidenceRoot must be an existing absolute session-artifact directory.'
}
if (-not (Test-Path -LiteralPath $adb -PathType Leaf) -or -not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw 'The existing Android SDK platform-tools and build-tools 36.0.0 are required.'
}
$source = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify the integrated source commit.' }
$changes = @(& git -C $root status --porcelain)
if ($LASTEXITCODE -ne 0 -or $changes.Count -gt 0) { throw 'Native acceptance requires clean committed sources.' }

$avd = (Invoke-Device @('emu', 'avd', 'name') | Select-Object -First 1).Trim()
$sdk = (Invoke-Device @('shell', 'getprop', 'ro.build.version.sdk') | Select-Object -First 1).Trim()
$currentUser = (Invoke-Device @('shell', 'am', 'get-current-user') | Select-Object -First 1).Trim()
$unlocked = (Invoke-Device @('shell', 'cmd', 'user', 'is-user-unlocked', '0') | Select-Object -First 1).Trim()
if ($avd -ne $expectedAvd -or $sdk -ne $expectedSdk -or $currentUser -ne '0' -or $unlocked -ne 'true') {
    throw "Device identity/unlock attestation failed for $Serial; no instrumentation was started."
}
$focusBefore = (Invoke-Device @('shell', 'dumpsys', 'window') |
    Where-Object { $_ -match 'mCurrentFocus=' }) -join ''
if ($focusBefore -match 'Application Not Responding') {
    throw 'An ANR dialog obscures the fixture. Preserve and resolve that evidence before instrumentation.'
}
$packageVersion = @(Invoke-Device @('shell', 'dumpsys', 'package', $package) |
    Where-Object { $_ -match 'versionCode=|versionName=' })
if (($packageVersion -join "`n") -notmatch "versionCode=$ExpectedVersionCode\s+minSdk=31\s+targetSdk=36") {
    throw 'Installed fixture package version or SDK bounds differ from the requested candidate.'
}

$testApk = Join-Path $root 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
$appApk = if ($ExpectedAppApk) { $ExpectedAppApk } else { Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk' }
$installedAppHash = Get-InstalledApkHash $package
$installedTestHash = Get-InstalledApkHash "$package.test"
if ($installedAppHash -ne (Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash.ToLowerInvariant() -or
    $installedTestHash -ne (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash.ToLowerInvariant()) {
    throw 'Installed APK bytes differ from the integrated build; replace-install the correct artifacts first.'
}
$certificateOutput = @(& $apksigner verify --verbose --print-certs $appApk 2>&1 | ForEach-Object { "$_" })
if ($LASTEXITCODE -ne 0) { throw 'The expected application APK failed signature verification.' }
$certificates = @($certificateOutput | ForEach-Object {
    if ($_ -match '^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})$') { $Matches[1].ToLowerInvariant() }
})
$signing = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-signing.json') -Raw | ConvertFrom-Json
if ($signing.applicationId -cne $package -or $certificates.Count -ne 1 -or $certificates[0] -cne $signing.certificateSha256) {
    throw 'The installed candidate does not match the published signing identity.'
}

$directory = Join-Path $EvidenceRoot $Label
if (Test-Path -LiteralPath $directory) { throw "Refusing to overwrite acceptance evidence: $directory" }
New-Item -ItemType Directory -Path $directory | Out-Null
$startedAt = [DateTimeOffset]::Now
$result = [ordered]@{
    label = $Label
    sourceSha = $source
    serial = $Serial
    avd = $avd
    sdk = [int]$sdk
    currentUser = [int]$currentUser
    userUnlocked = $true
    startedAt = $startedAt.ToString('o')
    classes = $Classes
    expectedTests = $ExpectedTests
    expectedOrientation = $ExpectedOrientation
    systemFocusBefore = $focusBefore
    appApkSha256 = $installedAppHash
    expectedAppArtifact = $appApk
    testApkSha256 = $installedTestHash
    certificateSha256 = $certificates[0]
    installedApkBytesAttested = $true
    packageVersion = $packageVersion
    display = @(Invoke-Device @('shell', 'wm', 'size')) + @(Invoke-Device @('shell', 'wm', 'density'))
    fontScale = (Invoke-Device @('shell', 'settings', 'get', 'system', 'font_scale')) -join ''
    navigationMode = (Invoke-Device @('shell', 'settings', 'get', 'secure', 'navigation_mode')) -join ''
    rotationPolicyBefore = (Invoke-Device @('shell', 'wm', 'user-rotation')) -join ''
    fixedRotationPolicy = (Invoke-Device @('shell', 'wm', 'fixed-to-user-rotation')) -join ''
    softwareImeWithHardwareKeyboard = (Invoke-Device @('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard')) -join ''
    actualConfigurationBefore = @(Invoke-Device @('shell', 'dumpsys', 'activity') |
        Where-Object { $_ -match 'mGlobalConfiguration:' } | Select-Object -First 1)
}
$arguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Classes) +
    $RunnerArguments + @("$package.test/androidx.test.runner.AndroidJUnitRunner")
$result.command = 'adb -s ' + $Serial + ' ' + ($arguments -join ' ')
$watch = [Diagnostics.Stopwatch]::StartNew()
$output = @(& $adb -s $Serial @arguments 2>&1 | ForEach-Object { "$_" })
$exitCode = $LASTEXITCODE
$watch.Stop()
$output | Set-Content -LiteralPath (Join-Path $directory 'instrumentation.log') -Encoding utf8
$text = $output -join "`n"
$completion = [regex]::Match($text, '(?m)^OK \((\d+) tests?\)')
$result.durationSeconds = [math]::Round($watch.Elapsed.TotalSeconds, 3)
$result.completedAt = [DateTimeOffset]::Now.ToString('o')
$result.adbExitCode = $exitCode
$result.systemFocusAfter = (Invoke-Device @('shell', 'dumpsys', 'window') |
    Where-Object { $_ -match 'mCurrentFocus=' }) -join ''
$result.actualConfigurationAfter = @(Invoke-Device @('shell', 'dumpsys', 'activity') |
    Where-Object { $_ -match 'mGlobalConfiguration:' } | Select-Object -First 1)
$result.executedTests = if ($completion.Success) { [int]$completion.Groups[1].Value } else { $null }
$result.outcome = if (
    $exitCode -eq 0 -and $completion.Success -and
    [int]$completion.Groups[1].Value -eq $ExpectedTests -and
    $text -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -3'
) { 'PASS' } else { 'FAIL' }
if ($result.systemFocusAfter -match 'Application Not Responding') {
    $result.outcome = 'FAIL'
    $result.environmentBlock = 'An ANR dialog obscured the device; native acceptance cannot be claimed.'
}
$result.runnerOutcome = $result.outcome
$result.outcome = 'INCOMPLETE'
$result.evidenceComplete = $false
# Preserve the runner outcome without claiming complete evidence if collection fails.
$resultPath = Join-Path $directory 'result.json'
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding utf8
$result.screenshots = @()
foreach ($kind in @('ui-acceptance', 'remote-acceptance', 'agent-acceptance')) {
    $devicePath = "/sdcard/Android/data/$package/files/$kind"
    $exists = (Invoke-Device @('shell', "if [ -d '$devicePath' ]; then echo present; else echo absent; fi")) -join ''
    if ($exists.Trim() -eq 'present') {
        Invoke-Device @('pull', '-a', $devicePath, (Join-Path $directory $kind)) | Out-Null
        $result.screenshots += @(Get-ChildItem -LiteralPath (Join-Path $directory $kind) -Filter '*.png' |
            ForEach-Object {
                $image = [Drawing.Image]::FromFile($_.FullName)
                try {
                    $width = $image.Width
                    $height = $image.Height
                } finally {
                    $image.Dispose()
                }
                [ordered]@{
                    path = $_.FullName
                    pixelWidth = $width
                    pixelHeight = $height
                    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                    deviceModifiedAtUtc = $_.LastWriteTimeUtc.ToString('o')
                    writtenDuringThisRun = $_.LastWriteTimeUtc -ge $startedAt.UtcDateTime.AddSeconds(-1)
                    contentScope = 'Controlled fixtures only; older captures are not evidence for this run. Visual inspection pending.'
                }
            })
    }
}
$result.outcome = $result.runnerOutcome
if ($ExpectedOrientation) {
    $physicalCaptures = @($result.screenshots | Where-Object {
        $_.writtenDuringThisRun -and $_.path.EndsWith('-device.png')
    })
    $wrongOrientation = @($physicalCaptures | Where-Object {
        if ($ExpectedOrientation -eq 'portrait') { $_.pixelWidth -ge $_.pixelHeight }
        else { $_.pixelHeight -ge $_.pixelWidth }
    })
    $result.orientationEvidence = if ($physicalCaptures.Count -gt 0 -and $wrongOrientation.Count -eq 0) {
        'PASS'
    } else {
        $result.outcome = 'FAIL'
        'FAIL: requested orientation was not proved by fresh full-device captures.'
    }
}
$sourceAfter = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceAfter -cne $source) {
    $result.outcome = 'FAIL'
    $result.sourceError = 'The source commit changed during native acceptance.'
}
$result.evidenceComplete = $true
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding utf8
$output | Select-Object -Last $(if ($result.outcome -eq 'PASS') { 12 } else { 140 })
Write-Output "$Label : $($result.outcome), expected $ExpectedTests tests, elapsed $($result.durationSeconds)s"
if ($result.outcome -ne 'PASS') { exit 1 }
