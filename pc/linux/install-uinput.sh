#!/bin/sh
# Compatibility alias for older instructions.
#
# The full Linux setup now lives in install-linux.sh: it installs the distro
# packages, sets up uinput, creates pc/.venv and adds the launcher.
#
#   sudo sh pc/linux/install-linux.sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec sh "$HERE/install-linux.sh" "$@"
