# Removes "ghost" VIRTUAL controllers left behind when lemoGamePad-server was
# killed instead of closed. A ghost shows up in Device Manager as
# "Xbox 360 Controller for Windows" with status "Unknown" (not present).
#
# Safety: only root-enumerated devices whose driver service is ViGEmBus are
# touched. A physically disconnected or disabled REAL controller (USB/BT) is
# never matched, so this cannot uninstall actual hardware.
#
# Run as Administrator:
#   powershell -ExecutionPolicy Bypass -File .\clean_pads.ps1
#   powershell -ExecutionPolicy Bypass -File .\clean_pads.ps1 -ListOnly
#   powershell -ExecutionPolicy Bypass -File .\clean_pads.ps1 -Force

param(
    [switch]$ListOnly,
    [switch]$Force
)

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "This script needs Administrator rights (right-click > Run as administrator)." -ForegroundColor Yellow
    exit 1
}

function Get-DeviceService {
    param([string]$InstanceId)
    try {
        $prop = Get-PnpDeviceProperty -InstanceId $InstanceId -KeyName "DEVPKEY_Device_Service" -ErrorAction Stop
        if ($null -ne $prop -and $null -ne $prop.Data) { return [string]$prop.Data }
    } catch {
        # Older builds may not expose the property; treat as unknown so the
        # device is skipped rather than removed by mistake.
    }
    return ""
}

# Candidate names only. The real filter is applied below: the device must be
# root-enumerated (virtual) and driven by ViGEmBus.
$candidates = @(Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object {
    $_.Status -ne "OK" -and (
        $_.FriendlyName -like "*Xbox 360 Controller for Windows*" -or
        $_.FriendlyName -like "*Wireless Controller*" -or
        $_.FriendlyName -like "*DualShock 4*"
    )
})

$ghosts = @($candidates | Where-Object {
    $id = [string]$_.InstanceId
    if ($id -notlike "ROOT\*") { return $false }
    if ($id -like "*VIGEM*") { return $true }
    $service = Get-DeviceService -InstanceId $id
    return ($service -match "(?i)vigembus|vigem")
})

if ($ghosts.Count -eq 0) {
    Write-Host "No ViGEm ghost controllers found (real controllers are ignored)." -ForegroundColor Green
    exit 0
}

Write-Host "Found $($ghosts.Count) ViGEm ghost controller(s):" -ForegroundColor Cyan
foreach ($g in $ghosts) {
    Write-Host ("  {0}  [{1}]  {2}" -f $g.FriendlyName, $g.Status, $g.InstanceId)
}

if ($ListOnly) {
    Write-Host "List only - nothing removed." -ForegroundColor Yellow
    exit 0
}

if (-not $Force) {
    $answer = Read-Host "Remove these $($ghosts.Count) virtual device(s)? Type YES to continue"
    if ($answer -ne "YES") {
        Write-Host "Cancelled - nothing removed." -ForegroundColor Yellow
        exit 0
    }
}

$removed = 0
$failed = 0
foreach ($g in $ghosts) {
    $ok = $false
    try {
        Remove-PnpDevice -InstanceId $g.InstanceId -Confirm:$false -ErrorAction Stop
        $ok = $true
    } catch {
        try {
            pnputil /remove-device "$($g.InstanceId)" | Out-Null
            $ok = ($LASTEXITCODE -eq 0)
        } catch {
            $ok = $false
        }
    }
    if ($ok) {
        $removed++
    } else {
        $failed++
        Write-Host ("  could not remove {0}" -f $g.InstanceId) -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "Removed $removed, failed $failed." -ForegroundColor Green
if ($failed -gt 0) {
    Write-Host "Remaining ghosts can be deleted in Device Manager:" -ForegroundColor Yellow
    Write-Host "  View > Show hidden devices > right-click the greyed controller > Uninstall device"
}
