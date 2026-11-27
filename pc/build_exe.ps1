# Rebuilds the native Rust receiver and desktop panel on Windows.
#   powershell -ExecutionPolicy Bypass -File .\build_exe.ps1

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$names = @("lemoGamePad-server", "lemoGamePad-app")

Push-Location (Join-Path $root "rust")
try {
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
} finally {
    Pop-Location
}

$dist = Join-Path $here "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$out = Join-Path $root "release"
New-Item -ItemType Directory -Force -Path $out | Out-Null

foreach ($name in $names) {
    $built = Join-Path $root "rust/target/release/$name.exe"
    if (-not (Test-Path $built)) { throw "Missing build output: $built" }
    Copy-Item $built (Join-Path $dist "$name.exe") -Force

    $target = Join-Path $out "$name.exe"
    $copied = $false
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        try {
            Copy-Item $built $target -Force -ErrorAction Stop
            $copied = $true
            break
        } catch {
            Start-Sleep -Milliseconds 700
        }
    }
    if (-not $copied) {
        Write-Host "Could not replace $target. Close any running lemoGamePad window and try again." -ForegroundColor Red
        exit 1
    }
    Write-Host "EXE ready: $target" -ForegroundColor Green
}

$firewall = Join-Path $here "allow_firewall.ps1"
Copy-Item $firewall (Join-Path $dist "allow_firewall.ps1") -Force
Copy-Item $firewall (Join-Path $out "allow_firewall.ps1") -Force
Write-Host "Firewall helper ready: $out\allow_firewall.ps1" -ForegroundColor Green
exit 0
