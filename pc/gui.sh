#!/bin/sh
# Start the native Rust desktop control panel from this checkout.
#
# Builds the release binaries on first use if needed. Arguments are passed
# straight through, for example:
#
#   pc/gui.sh --pad ds4
#   pc/gui.sh --manual-start
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
ROOT=$(dirname "$HERE")
BIN="$ROOT/rust/target/release/lemoGamePad-app"

if [ ! -x "$BIN" ]; then
    if ! command -v cargo >/dev/null 2>&1; then
        echo "cargo not found. Install Rust from https://rustup.rs, or run pc/linux/install-linux.sh first." >&2
        exit 1
    fi
    (cd "$ROOT/rust" && cargo build --release)
fi

exec "$BIN" "$@"
