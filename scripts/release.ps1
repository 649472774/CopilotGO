# CopilotGo 一键发版脚本
# 用法：
#   ./scripts/release.ps1                # 默认 -Patch，bump 0.1.2 → 0.1.3 + versionCode+1
#   ./scripts/release.ps1 -Minor         # 0.1.x → 0.2.0
#   ./scripts/release.ps1 -Major         # 0.x.x → 1.0.0
#   ./scripts/release.ps1 -SkipBuild     # 只 bump 版本号，不编译
#   ./scripts/release.ps1 -SkipInstall   # 不装到模拟器
#   ./scripts/release.ps1 -SkipCopy      # 不复制到 D:\APK 和桌面
#   ./scripts/release.ps1 -Push          # 编译成功后自动 git commit + push
#   ./scripts/release.ps1 -SkipBump      # 不 bump（沿用 build.gradle 当前版本）

[CmdletBinding(DefaultParameterSetName = 'Patch')]
param(
    [Parameter(ParameterSetName = 'Patch')] [switch]$Patch,
    [Parameter(ParameterSetName = 'Minor')] [switch]$Minor,
    [Parameter(ParameterSetName = 'Major')] [switch]$Major,
    [switch]$SkipBump,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SkipCopy,
    [switch]$Push,
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr",
    [string]$AdbPath  = "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$ApkOut   = "D:\APK\CopilotGo-debug.apk",
    [string]$DesktopOut = "$([Environment]::GetFolderPath('Desktop'))\CopilotGo-debug.apk"
)

$ErrorActionPreference = 'Stop'

# 定位项目根（脚本在 scripts/ 下）
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
Write-Host "Project root: $root" -ForegroundColor Cyan

# === 1. bump 版本号 ===
$gradle = Join-Path $root 'app\build.gradle.kts'
if (-not (Test-Path $gradle)) { throw "build.gradle.kts not found at $gradle" }
$content = Get-Content $gradle -Raw

$mCode = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
$mName = [regex]::Match($content, 'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"')
if (-not ($mCode.Success -and $mName.Success)) { throw "Failed to parse versionCode/versionName" }

$oldCode = [int]$mCode.Groups[1].Value
$vMajor = [int]$mName.Groups[1].Value
$vMinor = [int]$mName.Groups[2].Value
$vPatch = [int]$mName.Groups[3].Value
$oldName = "$vMajor.$vMinor.$vPatch"

if ($SkipBump) {
    $newCode = $oldCode
    $newName = $oldName
    Write-Host "[-SkipBump] keeping versionCode $oldCode, versionName $oldName" -ForegroundColor Yellow
} else {
    if ($Major)     { $vMajor++; $vMinor = 0; $vPatch = 0 }
    elseif ($Minor) { $vMinor++; $vPatch = 0 }
    else            { $vPatch++ }   # 默认 Patch

    $newCode = $oldCode + 1
    $newName = "$vMajor.$vMinor.$vPatch"

    $content = [regex]::Replace($content, 'versionCode\s*=\s*\d+', "versionCode = $newCode")
    $content = [regex]::Replace($content, 'versionName\s*=\s*"[\d.]+"', "versionName = `"$newName`"")
    Set-Content -Path $gradle -Value $content -Encoding UTF8 -NoNewline
    Write-Host "Bumped: versionCode $oldCode -> $newCode, versionName $oldName -> $newName" -ForegroundColor Green
}

if ($SkipBuild) {
    Write-Host "[-SkipBuild] only bumped version, exiting." -ForegroundColor Yellow
    return
}

# === 2. 编译 ===
$env:JAVA_HOME = $JavaHome
Write-Host "Building (JAVA_HOME=$JavaHome) ..." -ForegroundColor Cyan
& "$root\gradlew.bat" --no-daemon --console=plain "-Dorg.gradle.java.home=$JavaHome" assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build FAILED" }
Write-Host "Build OK" -ForegroundColor Green

$apk = "$root\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }

# === 3. 装机 ===
if (-not $SkipInstall) {
    if (Test-Path $AdbPath) {
        $devices = & $AdbPath devices | Select-String 'device$' | Where-Object { $_ -notmatch 'List of' }
        if ($devices) {
            Write-Host "Installing to emulator-5554 ..." -ForegroundColor Cyan
            & $AdbPath -s emulator-5554 install -r $apk
        } else {
            Write-Host "[skip] no device attached" -ForegroundColor Yellow
        }
    } else {
        Write-Host "[skip] adb not found at $AdbPath" -ForegroundColor Yellow
    }
}

# === 4. 拷贝 ===
if (-not $SkipCopy) {
    $dir = Split-Path -Parent $ApkOut
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Copy-Item $apk $ApkOut -Force
    Copy-Item $apk $DesktopOut -Force
    Write-Host "Copied to: $ApkOut" -ForegroundColor Green
    Write-Host "Copied to: $DesktopOut" -ForegroundColor Green
}

# === 5. git commit + push（可选）===
if ($Push) {
    Write-Host "git commit + push ..." -ForegroundColor Cyan
    git add -A
    git commit -m "chore: release v$newName (versionCode $newCode)"
    git push
    Write-Host "Pushed v$newName" -ForegroundColor Green
}

Write-Host "`n=== Release v$newName done ===" -ForegroundColor Magenta
