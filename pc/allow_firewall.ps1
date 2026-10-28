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
    [int]$Port = 9876
)

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
$videoPort = $Port + 1

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
Add-Rule "lemoGamePad ultimate (TCP $videoPort)" "TCP" $videoPort

Write-Host "Firewall rules added for:" -ForegroundColor Green
Write-Host "  $Program"
Write-Host "  UDP $Port (controller)  +  TCP $videoPort (PC screen preview)"
Write-Host "  Profile: Any (Wi-Fi, Bluetooth PAN and USB tethering)"
