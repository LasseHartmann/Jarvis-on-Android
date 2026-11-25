<#
.SYNOPSIS
    Zero-clone installer: downloads the latest Jarvis.apk from GitHub Releases and installs it via ADB.
.EXAMPLE
    irm https://raw.githubusercontent.com/LasseHartmann/Jarvis-on-Android/main/scripts/install_from_github.ps1 | iex
#>
$ErrorActionPreference = "Stop"

$Repo = "LasseHartmann/Jarvis-on-Android"
$Pkg = "com.jarvis.assistant"
$DownloadUrl = "https://github.com/$Repo/releases/latest/download/Jarvis.apk"
$TempApk = Join-Path $env:TEMP "Jarvis-latest.apk"

function Resolve-AdbPath {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @(
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" })
    ) | Where-Object { $_ -and (Test-Path $_) }

    if ($candidates.Count -gt 0) { return $candidates[0] }
    throw "adb.exe not found. Install Android Platform-Tools (winget install Google.PlatformTools) or add adb to PATH."
}

$Adb = Resolve-AdbPath

Write-Host "[Jarvis] Downloading latest Release APK from GitHub..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $DownloadUrl -OutFile $TempApk -UseBasicParsing

Write-Host "[Jarvis] Checking connected Android device..." -ForegroundColor Cyan
& $Adb devices

Write-Host "[Jarvis] Installing Jarvis.apk via ADB (-r)..." -ForegroundColor Cyan
& $Adb install -r $TempApk
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Install failed. On Xiaomi HyperOS, ensure BOTH 'USB debugging' AND 'Install via USB' are enabled in Developer Options."
    exit 1
}

Write-Host "[Jarvis] Applying optional baseline AppOps & Doze exemption..." -ForegroundColor Cyan
$ErrorActionPreference = "Continue"
& $Adb shell appops set $Pkg WRITE_SETTINGS allow 2>$null
& $Adb shell appops set $Pkg ACCESS_RESTRICTED_SETTINGS allow 2>$null
& $Adb shell dumpsys deviceidle whitelist +$Pkg 2>$null
$ErrorActionPreference = "Stop"

Write-Host "[Jarvis] Launching Jarvis..." -ForegroundColor Green
& $Adb shell am start -n "$Pkg/.MainActivity"
Remove-Item $TempApk -Force -ErrorAction SilentlyContinue

Write-Host "`nDone! Open Phone permissions in Jarvis to enable Accessibility Pilot, Notifications & HyperOS Autostart." -ForegroundColor Green
