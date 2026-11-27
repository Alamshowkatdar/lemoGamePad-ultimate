#!/bin/sh
# Start the native lemoGamePad ultimate receiver from this checkout.
#
# Arguments are passed straight through, for example:
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

for candidate in \
    "$PC_DIR/lemoGamePad-server" \
    "$PC_DIR/dist/lemoGamePad-server" \
    "$PC_DIR/../rust/target/release/lemoGamePad-server"
do
    if [ -x "$candidate" ]; then
        exec "$candidate" "$@"
    fi
done

echo "Native receiver not found. Run pc/build_rust.sh first." >&2
exit 1
