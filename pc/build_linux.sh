#!/bin/sh
# Build a self-contained Linux binary with PyInstaller.
#
#   ./pc/build_linux.sh
#
# The result is dist/lemoGamePad-server. It still needs the one-time uinput
# setup (pc/linux/install-uinput.sh) at runtime.
set -eu
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

if ! "$PYTHON" -c "import PyInstaller" >/dev/null 2>&1; then
    echo "PyInstaller is required." >&2
    echo "  $PYTHON -m pip install --user pyinstaller" >&2
    echo "or use a virtualenv:" >&2
    echo "  $PYTHON -m venv .venv && . .venv/bin/activate" >&2
    echo "  pip install -r requirements.txt pyinstaller" >&2
    exit 1
fi

"$PYTHON" -m PyInstaller \
    --noconfirm --clean --onefile \
    --name lemoGamePad-server \
    --hidden-import linux_pad \
    --collect-submodules evdev \
    vpad_server.py

echo
echo "Built: $(pwd)/dist/lemoGamePad-server"
echo "Run it from a terminal so the banner and QR code stay visible."
