<#
.SYNOPSIS
    Launches the Jarvis PC Bridge LAN server for Jarvis on Android.
.PARAMETER Port
    TCP port to bind (default: 8765).
.PARAMETER AddFirewallRule
    If run as Administrator, creates an inbound Private-network Windows Firewall rule for the chosen port.
.PARAMETER ResetToken
    Rotates the bearer token stored in ~/.jarvis_pc_bridge_token.
#>
param(
    [int]$Port = 8765,
    [switch]$AddFirewallRule,
    [switch]$ResetToken
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BridgePy = Join-Path $ScriptDir "jarvis_pc_bridge.py"

if ($AddFirewallRule) {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator
    )
    if (-not $isAdmin) {
        Write-Warning "Skipping firewall rule creation: PowerShell is not running as Administrator. Re-run in an elevated PowerShell if your phone cannot connect on Private networks."
    } else {
        $ruleName = "Jarvis PC Bridge (TCP $Port)"
        $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
        if (-not $existing) {
            New-NetFirewallRule `
                -DisplayName $ruleName `
                -Direction Inbound `
                -Protocol TCP `
                -LocalPort $Port `
                -Profile Private `
                -Action Allow `
                -ErrorAction Stop | Out-Null
            Write-Host "Added Windows Firewall rule '$ruleName' (Private networks)." -ForegroundColor Green
        } else {
            Write-Host "Windows Firewall rule '$ruleName' already exists." -ForegroundColor Cyan
        }
    }
}

$pyArgs = @($BridgePy, "--port", "$Port")
if ($ResetToken) {
    $pyArgs += "--reset-token"
}

$pythonCmd = Get-Command python -ErrorAction SilentlyContinue
if ($pythonCmd) {
    & $pythonCmd.Source @pyArgs
} else {
    $pyLauncher = Get-Command py -ErrorAction SilentlyContinue
    if ($pyLauncher) {
        & $pyLauncher.Source -3 @pyArgs
    } else {
        Write-Error "Python 3 was not found in PATH. Install Python 3 from python.org or the Microsoft Store."
    }
}
