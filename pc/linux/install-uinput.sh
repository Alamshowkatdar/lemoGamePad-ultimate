#!/bin/sh
# One-time Linux setup for lemoGamePad ultimate's virtual gamepad.
#
#   sudo sh pc/linux/install-uinput.sh
#
# Loads the uinput kernel module, installs a udev rule that makes /dev/uinput
# usable by the desktop user, and adds that user to the "input" group.
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "Run this with sudo: sudo sh $0" >&2
    exit 1
fi

TARGET_USER="${SUDO_USER:-${USER:-}}"
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RULE="$HERE/99-lemogamepad.rules"

echo "1/3 loading the uinput kernel module"
modprobe uinput 2>/dev/null || true
echo "uinput" > /etc/modules-load.d/lemogamepad.conf
if [ ! -e /dev/uinput ]; then
    echo "!! /dev/uinput is still missing." >&2
    echo "   Your kernel needs CONFIG_INPUT_UINPUT (most distributions enable it)." >&2
    exit 1
fi

echo "2/3 installing the udev rule"
install -D -m 0644 "$RULE" /etc/udev/rules.d/99-lemogamepad.rules
udevadm control --reload-rules 2>/dev/null || true
udevadm trigger --name-match=uinput 2>/dev/null || true

echo "3/3 granting access"
if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ]; then
    groupadd -f input 2>/dev/null || true
    usermod -aG input "$TARGET_USER"
    echo
    echo "Done. Log out and back in (or reboot) so the new group applies."
    echo "Check with:  ls -l /dev/uinput   and   groups"
else
    echo "Could not detect the desktop user; run: sudo usermod -aG input <your-user>"
fi
