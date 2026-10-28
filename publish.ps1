# Stages every change, commits it and pushes it to the public repository.
#
#   powershell -ExecutionPolicy Bypass -File .\publish.ps1 -Message "fix scan hint"
#   powershell -ExecutionPolicy Bypass -File .\publish.ps1 "fix scan hint"
#
# Run it after any change you want the repository to keep. Signing keys are
# refused on purpose; binaries belong on the GitHub Releases page instead.

param(
    [Parameter(Position = 0)]
    [string]$Message = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here
try {
    $git = (Get-Command git -ErrorAction SilentlyContinue).Source
    if (-not $git) {
        $portable = Join-Path $env:LOCALAPPDATA "tools\git\cmd\git.exe"
        if (Test-Path $portable) { $git = $portable }
    }
    if (-not $git) {
        throw "git not found. Install Git, or keep the portable copy at $env:LOCALAPPDATA\tools\git."
    }

    if (-not (Test-Path ".git")) {
        & $git init -b main | Out-Null
        Write-Host "Initialised a new git repository on branch main." -ForegroundColor Cyan
    }

    # A public repository must never carry an identity-less commit.
    if (-not (& $git config user.name)) {
        & $git config user.name "Alam showkat"
    }
    if (-not (& $git config user.email)) {
        & $git config user.email "70095049+Alamshowkatdar@users.noreply.github.com"
    }

    & $git add -A
    $staged = @(& $git diff --cached --name-only)
    if (-not $staged) {
        Write-Host "Nothing to publish - the working tree is clean." -ForegroundColor Green
        return
    }

    # Belt and braces: .gitignore already excludes these, but a forced add or a
    # renamed key must not slip through.
    $secret = $staged | Where-Object {
        $_ -match '(^|/)keystore/|keystore\.properties$|\.jks$|\.keystore$|\.p12$|\.pfx$'
    }
    if ($secret) {
        throw "Refusing to commit signing material: $($secret -join ', ')"
    }

    if ([string]::IsNullOrWhiteSpace($Message)) {
        $Message = "Update " + (Get-Date -Format "yyyy-MM-dd HH:mm")
    }
    & $git commit -m $Message
    if ($LASTEXITCODE -ne 0) { throw "git commit failed" }

    $remotes = @(& $git remote)
    if (-not ($remotes -contains "origin")) {
        throw "No 'origin' remote yet. Add it once: git remote add origin https://github.com/Alamshowkatdar/lemoGamePad-ultimate.git"
    }

    & $git push -u origin HEAD
    if ($LASTEXITCODE -ne 0) { throw "git push failed" }

    Write-Host ""
    Write-Host "Published: $Message" -ForegroundColor Green
} finally {
    Pop-Location
}
