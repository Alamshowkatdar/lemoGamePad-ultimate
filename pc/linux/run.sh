#!/bin/sh
# Start the lemoGamePad ultimate Linux receiver from this checkout.
#
# Uses pc/.venv when install-linux.sh has created it, otherwise falls back to
# python3 from PATH. Arguments are passed straight through, for example:
#
#   pc/linux/run.sh --pad ds4
set -eu

# Resolve symlinks so ~/.local/bin/lemogamepad can point at this script.
SELF="$0"
while [ -L "$SELF" ]; do
    DIR=$(CDPATH= cd -- "$(dirname -- "$SELF")" && pwd)
    LINK=$(readlink "$SELF")
    case "$LINK" in
        /*) SELF="$LINK" ;;
        *) SELF="$DIR/$LINK" ;;
    esac
done
HERE=$(CDPATH= cd -- "$(dirname -- "$SELF")" && pwd)
PC_DIR=$(dirname "$HERE")
PYTHON="$PC_DIR/.venv/bin/python"

if [ -x "$PYTHON" ]; then
    :
elif command -v python3 >/dev/null 2>&1; then
    PYTHON=python3
else
    echo "python3 not found. Run pc/linux/install-linux.sh first." >&2
    exit 1
fi

exec "$PYTHON" "$PC_DIR/vpad_server.py" "$@"
