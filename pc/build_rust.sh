#!/bin/sh
# Build the native Rust receiver and desktop panel into pc/dist.
set -eu
cd "$(dirname "$0")/../rust"

if ! command -v cargo >/dev/null 2>&1; then
    echo "Rust is required: https://rustup.rs" >&2
    exit 1
fi

cargo build --release
mkdir -p ../dist
for name in lemoGamePad-server lemoGamePad-app; do
    source="target/release/$name"
    if [ -f "$source.exe" ]; then
        source="$source.exe"
        name="$name.exe"
    fi
    cp "$source" "../dist/$name"
    echo "Built: $(cd .. && pwd)/dist/$name"
done
cp ../pc/linux/install-linux.sh ../dist/install-linux.sh
echo "Built: $(cd .. && pwd)/dist/install-linux.sh"
