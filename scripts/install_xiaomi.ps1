<#
.SYNOPSIS
    Builds and deploys J.A.R.V.I.S. to a Xiaomi 15T Pro (or any Android 12+ phone) over USB or Wireless ADB.
.DESCRIPTION
    1. Compiles app-debug.apk (unless -SkipBuild is passed).
    2. Auto-detects adb.exe from PATH, ANDROID_HOME, ANDROID_SDK_ROOT, or LOCALAPPDATA.
    3. Installs via ADB (-r) which avoids Android 15 sideload Restricted Settings lockouts.
    4. Applies optional baseline AppOps & battery whitelist, then launches J.A.R.V.I.S.
#>
param(
    [switch]$SkipBuild,
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
$Pkg = "com.jarvis.assistant"

function Resolve-AdbPath {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @(
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" })
    ) | Where-Object { $_ -and (Test-Path $_) }

    if ($candidates.Count -gt 0) { return $candidates[0] }
    throw "adb.exe not found. Add Android SDK platform-tools to PATH or set ANDROID_HOME."
}

$Adb = Resolve-AdbPath

if (-not $SkipBuild) {
    Write-Host "[Jarvis] Compiling Debug APK..." -ForegroundColor Cyan
    Push-Location $ProjectRoot
    try {
        & .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $ApkPath)) {
    throw "APK not found at $ApkPath"
}

$AdbArgs = @()
if ($Serial -ne "") { $AdbArgs += @("-s", $Serial) }

Write-Host "[Jarvis] Checking connected Android device..." -ForegroundColor Cyan
& $Adb devices

Write-Host "[Jarvis] Installing APK (-r)..." -ForegroundColor Cyan
& $Adb @AdbArgs install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Install failed. On Xiaomi HyperOS, ensure BOTH 'USB debugging' AND 'Install via USB' are enabled in Developer Options."
    exit 1
}

Write-Host "[Jarvis] Applying optional baseline AppOps & Doze exemption..." -ForegroundColor Cyan
$ErrorActionPreference = "Continue"
& $Adb @AdbArgs shell appops set $Pkg WRITE_SETTINGS allow 2>$null
& $Adb @AdbArgs shell appops set $Pkg ACCESS_RESTRICTED_SETTINGS allow 2>$null
& $Adb @AdbArgs shell dumpsys deviceidle whitelist +$Pkg 2>$null
$ErrorActionPreference = "Stop"

Write-Host "[Jarvis] Launching Jarvis Settings..." -ForegroundColor Green
& $Adb @AdbArgs shell am start -n "$Pkg/.MainActivity"

Write-Host "`nDeployment complete! On your Xiaomi phone:" -ForegroundColor Green
Write-Host "  1. Open Phone permissions in Jarvis and enable Accessibility Pilot, Notifications & HyperOS Autostart/Background pop-ups."
Write-Host "  2. Set Jarvis as your Default Digital Assistant (Settings -> Apps -> Default apps -> Digital assistant)."
Write-Host "  3. Add your Gemini API key (or local Ollama URL) under Provider & API key."
