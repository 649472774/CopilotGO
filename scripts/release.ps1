# Requires PowerShell 7. Safe defaults: build committed sources; no bump, install, copy or push.
# Prepare a version separately: .\scripts\release.ps1 -Minor -SkipBuild
# After reviewing and committing it: .\scripts\release.ps1
# Explicit delivery: .\scripts\release.ps1 -Install -DeviceSerial <serial> -Copy -Push
[CmdletBinding(DefaultParameterSetName = 'Build')]
param(
    [Parameter(ParameterSetName = 'Patch')] [switch]$Patch,
    [Parameter(ParameterSetName = 'Minor')] [switch]$Minor,
    [Parameter(ParameterSetName = 'Major')] [switch]$Major,
    [switch]$SkipBump,
    [switch]$SkipBuild,
    [switch]$Install,
    [switch]$SkipInstall,
    [string]$DeviceSerial,
    [switch]$Copy,
    [switch]$SkipCopy,
    [switch]$Push,
    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Android\Android Studio\jbr' }),
    [string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }),
    [string]$ApkOut = 'D:\APK\CopilotGo-debug.apk',
    [string]$DesktopOut = "$([Environment]::GetFolderPath('Desktop'))\CopilotGo-debug.apk",
    [ValidatePattern('^v\d+\.\d+\.\d+$')] [string]$RollbackTag = 'v0.1.33'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Native {
    param([string]$File, [string[]]$Arguments, [switch]$ShowOutput)
    $PSNativeCommandUseErrorActionPreference = $false
    $output = @(& $File @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($ShowOutput -or $exitCode -ne 0) {
        $output | ForEach-Object { Write-Host "$_" }
    }
    if ($exitCode -ne 0) {
        throw "$([IO.Path]::GetFileName($File)) failed (exit $exitCode). Nothing will be published."
    }
    if (-not $ShowOutput) {
        $output | ForEach-Object { "$_" }
    }
}

function Assert-CleanSource {
    $changes = @(Invoke-Native git @('status', '--porcelain', '--untracked-files=normal'))
    if ($changes.Count -ne 0) {
        throw 'Release sources must be committed and clean. Review and stage only intended files; this script never stages or commits for you.'
    }
}

function Write-Utf8 {
    param([string]$Path, [string]$Content)
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Copy-PreservingPrevious {
    param([string]$Source, [string]$Destination, [string]$ExpectedHash)
    if (-not [IO.Path]::IsPathFullyQualified($Destination)) {
        throw 'Delivery destinations must be absolute paths; no Desktop/root fallback is allowed.'
    }
    $directory = Split-Path -Parent $Destination
    if ([string]::IsNullOrWhiteSpace($directory)) {
        throw "Delivery path must include a directory: $Destination"
    }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    if (Test-Path -LiteralPath $Destination) {
        $previousHash = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($previousHash -ne $ExpectedHash) {
            $previous = Join-Path $directory "$([IO.Path]::GetFileNameWithoutExtension($Destination)).$previousHash.apk"
            if (-not (Test-Path -LiteralPath $previous)) {
                Copy-Item -LiteralPath $Destination -Destination $previous
            }
            if ((Get-FileHash -LiteralPath $previous -Algorithm SHA256).Hash.ToLowerInvariant() -ne $previousHash) {
                throw "Cannot preserve the previous APK at $previous"
            }
        }
    }
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
    if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedHash) {
        throw "Delivery checksum mismatch at $Destination"
    }
    Write-Utf8 "$Destination.sha256" "$ExpectedHash  $([IO.Path]::GetFileName($Destination))`n"
}

$root = Split-Path -Parent $PSScriptRoot
$oldLocation = Get-Location
$oldJavaHome = $env:JAVA_HOME
$oldAndroidHome = $env:ANDROID_HOME
try {
    Set-Location -LiteralPath $root
    if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Run this script with PowerShell 7 (pwsh).' }
    if (($Install -and $SkipInstall) -or ($Copy -and $SkipCopy)) {
        throw 'Do not combine an action with its Skip switch.'
    }
    if ($DeviceSerial -and -not $Install) { throw 'DeviceSerial requires an explicit -Install action.' }
    $bumpRequested = $Patch -or $Minor -or $Major
    if ($bumpRequested -and $SkipBump) { throw 'Choose a version bump or SkipBump, not both.' }
    if ($bumpRequested -and -not $SkipBuild) {
        throw 'Prepare versions with -Patch/-Minor/-Major -SkipBuild, then review and commit before building a deliverable.'
    }
    if ($SkipBuild -and ($Install -or $Copy -or $Push)) {
        throw 'A version-only operation cannot install, copy or push a deliverable.'
    }
    Assert-CleanSource

    $gradlePath = Join-Path $root 'app\build.gradle.kts'
    $content = Get-Content -LiteralPath $gradlePath -Raw
    $codes = [regex]::Matches($content, 'versionCode\s*=\s*(\d+)')
    $names = [regex]::Matches($content, 'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"')
    if ($codes.Count -ne 1 -or $names.Count -ne 1) { throw 'Expected one numeric versionCode and one stable versionName.' }
    $versionCode = [int]$codes[0].Groups[1].Value
    $majorNumber = [int]$names[0].Groups[1].Value
    $minorNumber = [int]$names[0].Groups[2].Value
    $patchNumber = [int]$names[0].Groups[3].Value

    if ($bumpRequested) {
        if ($versionCode -ge 2100000000) { throw 'Android versionCode limit reached.' }
        if ($Major) { $majorNumber++; $minorNumber = 0; $patchNumber = 0 }
        elseif ($Minor) { $minorNumber++; $patchNumber = 0 }
        else { $patchNumber++ }
        $versionCode++
        $versionName = "$majorNumber.$minorNumber.$patchNumber"
        $content = [regex]::Replace($content, 'versionCode\s*=\s*\d+', "versionCode = $versionCode")
        $content = [regex]::Replace($content, 'versionName\s*=\s*"[\d.]+"', "versionName = `"$versionName`"")
        Write-Utf8 $gradlePath $content
        Write-Host "Prepared v$versionName (code $versionCode). Review and commit app\build.gradle.kts before delivery."
        return
    }
    if ($SkipBuild) {
        Write-Host 'No version bump or build requested; nothing changed.'
        return
    }

    $versionName = "$majorNumber.$minorNumber.$patchNumber"
    $sourceSha = (Invoke-Native git @('rev-parse', '--verify', 'HEAD')).Trim()
    $rollbackSha = (Invoke-Native git @('rev-parse', '--verify', "refs/tags/$RollbackTag^{commit}")).Trim()
    Invoke-Native git @('merge-base', '--is-ancestor', $rollbackSha, $sourceSha) -ShowOutput
    $branch = (Invoke-Native git @('symbolic-ref', '--quiet', '--short', 'HEAD')).Trim()
    $signing = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-signing.json') -Raw | ConvertFrom-Json
    if ($signing.certificateSha256 -notmatch '^[a-f0-9]{64}$') { throw 'The recorded signing fingerprint is invalid.' }
    if ($RollbackTag -eq $signing.rollbackTag -and $rollbackSha -ne $signing.rollbackCommit) {
        throw 'The recorded rollback tag has moved. Restore its original history before delivery.'
    }

    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'A valid JDK home is required.' }
    $javaVersion = @(Invoke-Native (Join-Path $JavaHome 'bin\java.exe') @('-version')) -join "`n"
    $javaMajor = [regex]::Match($javaVersion, '(?:openjdk|java) version "(\d+)')
    if (-not $javaMajor.Success -or [int]$javaMajor.Groups[1].Value -ne 21) {
        throw 'Signed delivery uses JDK 21. Select the Android Studio JBR with -JavaHome.'
    }
    if (-not (Test-Path -LiteralPath $AndroidHome -PathType Container)) { throw 'Android SDK directory not found.' }
    $androidUserHome = if ($env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME } else { Join-Path $env:USERPROFILE '.android' }
    $keyFile = if ($env:COPILOTGO_SIGNING_STORE_FILE) { $env:COPILOTGO_SIGNING_STORE_FILE } else { Join-Path $androidUserHome 'debug.keystore' }
    if (-not [IO.Path]::IsPathFullyQualified($keyFile)) { throw 'Signing store paths must be absolute.' }
    if (-not (Test-Path -LiteralPath $keyFile -PathType Leaf)) {
        throw 'Existing signing keystore is missing. Refusing to generate a replacement. Restore it through your private key-backup process.'
    }
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidHome
    $buildTools = Join-Path $AndroidHome 'build-tools\36.0.0'
    $apksigner = Join-Path $buildTools 'apksigner.bat'
    $aapt = Join-Path $buildTools 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $apksigner) -or -not (Test-Path -LiteralPath $aapt)) {
        throw 'Install Android SDK build-tools;36.0.0 before release delivery.'
    }

    $targetSerial = $null
    $adb = Join-Path $AndroidHome 'platform-tools\adb.exe'
    if ($Install) {
        if (-not (Test-Path -LiteralPath $adb)) { throw 'adb is required when -Install is selected.' }
        $devices = @(Invoke-Native $adb @('devices') | ForEach-Object {
            if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
        })
        if ($DeviceSerial) {
            if ($DeviceSerial -notin $devices) { throw 'The requested adb device is not online and authorized.' }
            $targetSerial = $DeviceSerial
        } elseif ($devices.Count -eq 1) {
            $targetSerial = $devices[0]
        } else {
            throw 'Select one online, authorized device with -DeviceSerial; zero or multiple devices were found.'
        }
    }

    Invoke-Native (Join-Path $root 'gradlew.bat') @(
        "-Dorg.gradle.java.home=$JavaHome", '--no-daemon', '--console=plain', '--max-workers=2',
        'assembleDebug', 'testDebugUnitTest', 'lintDebug', 'compileDebugAndroidTestKotlin'
    ) -ShowOutput
    Assert-CleanSource
    if ((Invoke-Native git @('rev-parse', 'HEAD')).Trim() -ne $sourceSha) { throw 'Source commit changed during the build.' }

    $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Gradle did not produce the expected APK.' }
    $certificateOutput = @(Invoke-Native $apksigner @('verify', '--verbose', '--print-certs', $apk))
    $certificates = @($certificateOutput | ForEach-Object {
        if ($_ -match '^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})$') { $Matches[1].ToLowerInvariant() }
    })
    if ($certificates.Count -ne 1 -or $certificates[0] -ne $signing.certificateSha256) {
        throw 'APK signing identity differs from the published/local rollback certificate. Do not uninstall or wipe data to bypass this failure.'
    }
    $badging = @(Invoke-Native $aapt @('dump', 'badging', $apk))
    $packageLine = $badging | Where-Object { $_.StartsWith('package: ') } | Select-Object -First 1
    $packagePattern = "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'"
    if (-not $packageLine -or $packageLine -notmatch $packagePattern) { throw 'Cannot read the built APK identity.' }
    if ($Matches[1] -cne $signing.applicationId -or [int]$Matches[2] -ne $versionCode -or $Matches[3] -cne "$versionName-debug") {
        throw 'Built APK package/version does not match the committed debug release configuration.'
    }
    $minimumSdk = [regex]::Match(($badging -join "`n"), "(?m)^(?:minSdkVersion|sdkVersion):'(\d+)'")
    $targetSdk = [regex]::Match(($badging -join "`n"), "(?m)^targetSdkVersion:'(\d+)'")
    if (-not $minimumSdk.Success -or -not $targetSdk.Success -or
        [int]$minimumSdk.Groups[1].Value -ne 31 -or [int]$targetSdk.Groups[1].Value -ne 36) {
        throw 'Built APK must preserve minSdk 31 and the validated targetSdk 36 contract.'
    }
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $artifactName = "CopilotGo-v$versionName-debug.apk"
    $output = Join-Path $root "app\build\delivery\v$versionName-$($sourceSha.Substring(0, 12))"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $artifact = Join-Path $output $artifactName
    if ((Test-Path -LiteralPath $artifact) -and (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
        throw 'A different APK already exists for this source/version. Preserve and investigate it rather than overwrite provenance.'
    }
    Copy-Item -LiteralPath $apk -Destination $artifact -Force
    if ((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
        throw 'Delivery bundle copy failed checksum verification.'
    }
    Write-Utf8 (Join-Path $output 'SHA256SUMS') "$hash  $artifactName`n"
    Write-Utf8 "$artifact.sha256" "$hash  $artifactName`n"
    $manifest = [ordered]@{
        versionName = $versionName
        versionCode = $versionCode
        applicationId = $signing.applicationId
        minSdk = [int]$minimumSdk.Groups[1].Value
        targetSdk = [int]$targetSdk.Groups[1].Value
        sourceCommit = $sourceSha
        sourceBranch = $branch
        rollbackTag = $RollbackTag
        rollbackCommit = $rollbackSha
        apk = $artifactName
        apkSha256 = $hash
        certificateSha256 = $certificates[0]
        provenance = 'local-compatible-debug'
        builtAtUtc = [DateTime]::UtcNow.ToString('o')
        installedDeviceVerified = $false
    }
    Write-Utf8 (Join-Path $output 'build-provenance.json') ($manifest | ConvertTo-Json)
    $changes = @(Invoke-Native git @('log', '--format=- %s', '--max-count=200', "$rollbackSha..$sourceSha"))
    $notes = @(
        "# CopilotGo v$versionName",
        '',
        "- Package: $($signing.applicationId), versionCode $versionCode",
        "- Source: $sourceSha",
        "- Rollback: $RollbackTag ($rollbackSha)",
        "- APK SHA-256: $hash",
        "- Signing certificate SHA-256: $($certificates[0])",
        '',
        '## Changes since rollback',
        ($changes -join "`n"),
        '',
        '## Acceptance',
        'This bundle has not been published. Integration must record device/UI acceptance and known limitations before release.',
        'The signing certificate matches the recorded rollback APK; Android still makes the final per-device installation decision.',
        'A rollback tag preserves source history. Android may refuse a lower versionCode; never uninstall or clear app data to force a downgrade.',
        ''
    ) -join "`n"
    Write-Utf8 (Join-Path $output 'release-notes.md') $notes

    if ($Install) {
        Invoke-Native $adb @('-s', $targetSerial, 'install', '-r', $artifact) -ShowOutput
        Write-Host "adb install completed on $targetSerial; no uninstall, data clear or downgrade was requested."
    }
    if ($Copy) {
        Copy-PreservingPrevious $artifact $ApkOut $hash
        Copy-PreservingPrevious $artifact $DesktopOut $hash
    }
    if ($Push) {
        Assert-CleanSource
        if ((Invoke-Native git @('rev-parse', 'HEAD')).Trim() -ne $sourceSha -or
            (Invoke-Native git @('symbolic-ref', '--quiet', '--short', 'HEAD')).Trim() -cne $branch) {
            throw 'Source branch changed during delivery. Refusing to push unrelated work.'
        }
        Invoke-Native git @('push', '--porcelain', 'origin', "HEAD:refs/heads/$branch") -ShowOutput
    }
    Write-Host "Prepared local signed delivery bundle: $output"
    Write-Host "Source $sourceSha; certificate $($certificates[0]); no GitHub release was created."
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:ANDROID_HOME = $oldAndroidHome
    Set-Location -LiteralPath $oldLocation.Path
}
