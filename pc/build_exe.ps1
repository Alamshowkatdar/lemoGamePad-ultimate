# Rebuilds the standalone lemoGamePad-server.exe with PyInstaller.
#   powershell -ExecutionPolicy Bypass -File .\build_exe.ps1

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# PyInstaller logs to stderr, which PowerShell treats as an error under "Stop".
$ErrorActionPreference = "Continue"

python -m pip install --quiet --upgrade pyinstaller vgamepad mss pillow qrcode
if ($LASTEXITCODE -ne 0) { throw "pip install failed" }

Push-Location $here
try {
    python -m PyInstaller `
        --noconfirm `
        --onefile `
        --console `
        --name lemoGamePad-server `
        --collect-all vgamepad `
        --collect-all mss `
        --collect-all PIL `
        --collect-all qrcode `
        --distpath dist `
        --workpath build `
        vpad_server.py
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed with exit code $LASTEXITCODE" }

    $exe = Join-Path $here "dist\lemoGamePad-server.exe"
    $out = Join-Path (Split-Path -Parent $here) "release"
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $target = Join-Path $out "lemoGamePad-server.exe"

    # Windows keeps the previous EXE locked while it is still running or while
    # antivirus scans it, so retry briefly before giving up.
    $copied = $false
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        try {
            Copy-Item $exe $target -Force -ErrorAction Stop
            $copied = $true
            break
        } catch {
            Start-Sleep -Milliseconds 700
        }
    }
    if (-not $copied) {
        Write-Host ""
        Write-Host "Could not replace $target." -ForegroundColor Red
        Write-Host "Close any running lemoGamePad-server window and run this script again." -ForegroundColor Red
        exit 1
    }
    Write-Host ""
    Write-Host "EXE ready: $target" -ForegroundColor Green
} finally {
    Pop-Location
}
exit 0
