#!/usr/bin/env bash
# Local pre-tag check. Mirrors the CI gates without network access or hardware.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

echo "==> Version labels"
python3 tools/check_versions.py

echo "==> Rust check and tests (offline)"
cargo check --locked --offline --manifest-path rust/Cargo.toml
cargo test --locked --offline --manifest-path rust/Cargo.toml

echo "==> PC tests"
for suite in test_protocol.py test_web.py test_desktop_gui.py test_multi.py test_latency.py; do
    echo "  $suite"
    (cd pc && python3 "$suite")
done

echo "==> Android lint and debug APK (offline)"
(
    cd android
    # A stale ANDROID_SDK_ROOT from another checkout wins over ANDROID_HOME in
    # the Android Gradle plugin, so drop it for this build.
    env -u ANDROID_SDK_ROOT ANDROID_HOME=/tmp/opencode/android-sdk \
        ./gradlew --offline --no-daemon lintDebug assembleDebug
)

echo "==> Android EDIT LAYOUT UI (skipped without adb/device)"
if command -v adb >/dev/null 2>&1 && adb get-state 2>/dev/null | grep -q '^device$'; then
    adb install -r android/app/build/outputs/apk/debug/app-debug.apk >/dev/null
fi
(cd pc && python3 test_android_edit_ui.py)

echo "==> git diff --check"
git diff --check

echo "preflight passed"
