# Builds the VPad Android APK.
#   powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
#   powershell -ExecutionPolicy Bypass -File .\build_apk.ps1 -Debug
#
# Needs a JDK 17-21 and the Android SDK (platform 34 + build-tools 34).

param(
    [switch]$Debug
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# --- locate a JDK the Android Gradle Plugin can use (17-21) ----------------
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk "bin\javac.exe"))) {
    $candidates = @(
        (Join-Path $env:USERPROFILE "jdk-2*"),
        "C:\Program Files\Eclipse Adoptium\jdk-2*",
        "C:\Program Files\Java\jdk-21*",
        "C:\Program Files\Java\jdk-17*",
        "C:\Program Files\Android\Android Studio\jbr"
    )
    $jdk = $null
    foreach ($pattern in $candidates) {
        $found = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found -and (Test-Path (Join-Path $found.FullName "bin\javac.exe"))) {
            $jdk = $found.FullName
            break
        }
    }
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk "bin\javac.exe"))) {
    throw "No JDK found. Install JDK 17 or 21 and set JAVA_HOME."
}
$env:JAVA_HOME = $jdk
Write-Host "JDK: $jdk"

# --- locate the Android SDK ------------------------------------------------
if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
}
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    throw "Android SDK not found. Set ANDROID_HOME to your SDK folder."
}
Write-Host "SDK: $env:ANDROID_HOME"

# --- build -----------------------------------------------------------------
Push-Location $here
try {
    $task = if ($Debug) { "assembleDebug" } else { "assembleRelease" }

    $gradleCmd = $null
    $onPath = Get-Command gradle -ErrorAction SilentlyContinue
    if ($onPath) {
        $gradleCmd = $onPath.Source
    } elseif (Test-Path (Join-Path $env:USERPROFILE "gradle-8.9\bin\gradle.bat")) {
        $gradleCmd = Join-Path $env:USERPROFILE "gradle-8.9\bin\gradle.bat"
    } elseif (Test-Path ".\gradlew.bat") {
        $gradleCmd = ".\gradlew.bat"
    } else {
        throw "No Gradle found. Install Gradle 8.9+ or keep the gradle wrapper files."
    }
    Write-Host "Gradle: $gradleCmd"
    Write-Host ""

    # Native tools write progress to stderr; do not treat that as a failure.
    $ErrorActionPreference = "Continue"
    & $gradleCmd --no-daemon $task
    $code = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($code -ne 0) { throw "Gradle failed with exit code $code" }

    $variant = if ($Debug) { "debug" } else { "release" }
    $apk = Join-Path $here "app\build\outputs\apk\$variant\app-$variant.apk"
    $unsigned = $false
    if (-not (Test-Path $apk)) {
        # With no keystore Gradle emits app-release-unsigned.apk instead.
        $unsignedApk = Join-Path $here "app\build\outputs\apk\$variant\app-$variant-unsigned.apk"
        if (Test-Path $unsignedApk) {
            $apk = $unsignedApk
            $unsigned = $true
        } else {
            throw "APK not found at $apk"
        }
    }

    $out = Join-Path (Split-Path -Parent $here) "release"
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    # Keep the file name in step with versionName instead of hardcoding it.
    $gradle = Get-Content (Join-Path $here "app\build.gradle.kts") -Raw
    $version = ([regex]'versionName\s*=\s*"([^"]+)"').Match($gradle).Groups[1].Value
    if (-not $version) { $version = "dev" }
    $suffix = if ($Debug) { "-debug" } elseif ($unsigned) { "-unsigned" } else { "" }
    $dest = Join-Path $out "lemoGamePad-ultimate-$version$suffix.apk"
    Copy-Item $apk $dest -Force
    # Remove APKs from earlier versions so release\ only holds current files.
    Get-ChildItem $out -Filter "lemoGamePad-ultimate-*$suffix.apk" |
        Where-Object { $_.FullName -ne $dest } |
        Remove-Item -Force -ErrorAction SilentlyContinue
    if ($unsigned) {
        Write-Host ""
        Write-Host "WARNING: this APK is UNSIGNED and Android will not install it." -ForegroundColor Yellow
        Write-Host "Create android\keystore\vpad.jks + keystore.properties (see README," -ForegroundColor Yellow
        Write-Host "'Signing the APK yourself') and rebuild to get a signed release." -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "APK ready: $dest" -ForegroundColor Green
} finally {
    Pop-Location
}
