# Removes "ghost" virtual controllers left behind when lemoGamePad-server was
# killed instead of closed. A ghost shows up in Device Manager as
# "Xbox 360 Controller for Windows" with status "Unknown" (not present).
#
# Run as Administrator:
#   powershell -ExecutionPolicy Bypass -File .\clean_pads.ps1
#   powershell -ExecutionPolicy Bypass -File .\clean_pads.ps1 -ListOnly

param([switch]$ListOnly)

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "This script needs Administrator rights (right-click > Run as administrator)." -ForegroundColor Yellow
    exit 1
}

# Only devices that are NOT currently working: a live pad must never be
# unplugged, because a phone may be using it right now.
$ghosts = @(Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object {
    $_.Status -ne "OK" -and (
        $_.FriendlyName -like "*Xbox 360 Controller for Windows*" -or
        $_.FriendlyName -like "*Wireless Controller*"
    )
})

if ($ghosts.Count -eq 0) {
    Write-Host "No ghost controllers found." -ForegroundColor Green
    exit 0
}

Write-Host "Found $($ghosts.Count) ghost controller(s):" -ForegroundColor Cyan
foreach ($g in $ghosts) {
    Write-Host ("  {0}  [{1}]  {2}" -f $g.FriendlyName, $g.Status, $g.InstanceId)
}

if ($ListOnly) {
    Write-Host "List only - nothing removed." -ForegroundColor Yellow
    exit 0
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
