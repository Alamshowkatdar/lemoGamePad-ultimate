# Adds inbound firewall rules so phones can reach lemoGamePad-server.
# Run in PowerShell as Administrator:
#   powershell -ExecutionPolicy Bypass -File .\allow_firewall.ps1
# If you use vpad_server.py instead of the .exe, pass its path:
#   .\allow_firewall.ps1 -Program "C:\Path\To\python.exe"
#
# Rules use -Profile Any on purpose: Windows often classifies a USB-tethering
# (RNDIS) or Bluetooth-PAN interface as "Public", and a Private-only rule
# silently blocks those links.

param(
    [string]$Program = "",
    [ValidateRange(1, 65534)]
    [int]$Port = 9876,
    # Control port + 1 by default; pass -VideoPort 0 to skip the screen rule.
    [ValidateRange(0, 65535)]
    [int]$VideoPort = 0
)

if ($Port -lt 1 -or $Port -gt 65534) {
    Write-Host "Port must be between 1 and 65534 (the video port is Port + 1)." -ForegroundColor Red
    exit 1
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "This script needs Administrator rights (right-click > Run as administrator)." -ForegroundColor Yellow
    exit 1
}

# Prefer the shipped EXE in release\, then the PyInstaller output in dist\.
if ([string]::IsNullOrWhiteSpace($Program)) {
    $candidates = @(
        (Join-Path (Split-Path -Parent $PSScriptRoot) "release\lemoGamePad-server.exe"),
        (Join-Path $PSScriptRoot "dist\lemoGamePad-server.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            $Program = $candidate
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($Program)) {
        $Program = $candidates[0]
    }
}

if (-not (Test-Path $Program)) {
    Write-Host "Program not found: $Program" -ForegroundColor Red
    Write-Host "Build it with pc\build_exe.ps1, or pass -Program <path>." -ForegroundColor Red
    exit 1
}

$Program = (Resolve-Path $Program).Path

# The PC screen preview uses TCP on the control port + 1, so 65535 is not a
# valid control port. -VideoPort 0 disables the second rule entirely.
$withVideo = $false
if ($VideoPort -eq 0 -and $Port -lt 65535) {
    $VideoPort = $Port + 1
}
if ($VideoPort -gt 0) {
    if ($VideoPort -lt 1 -or $VideoPort -gt 65535 -or $VideoPort -eq $Port) {
        Write-Host "Invalid -VideoPort $VideoPort." -ForegroundColor Red
        exit 1
    }
    $withVideo = $true
}

function Add-Rule {
    param([string]$Name, [string]$Protocol, [int]$LocalPort)

    Get-NetFirewallRule -DisplayName $Name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule `
        -DisplayName $Name `
        -Direction Inbound `
        -Action Allow `
        -Protocol $Protocol `
        -LocalPort $LocalPort `
        -Program $Program `
        -Profile Any | Out-Null
}

Add-Rule "lemoGamePad ultimate (UDP $Port)" "UDP" $Port
# The PC screen preview uses TCP on the control port + 1.
if ($withVideo) {
    Add-Rule "lemoGamePad ultimate (TCP $VideoPort)" "TCP" $VideoPort
}

Write-Host "Firewall rules added for:" -ForegroundColor Green
Write-Host "  $Program"
if ($withVideo) {
    Write-Host "  UDP $Port (controller)  +  TCP $VideoPort (PC screen preview)"
} else {
    Write-Host "  UDP $Port (controller)"
}
Write-Host "  Profile: Any (Wi-Fi, Bluetooth PAN and USB tethering)"
