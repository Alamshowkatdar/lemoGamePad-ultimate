#!/bin/sh
# Build both native Rust binaries: the console receiver and the desktop panel.
#
#   ./pc/build_linux.sh
#
# Results are dist/lemoGamePad-server and dist/lemoGamePad-app. They still need
# the one-time uinput setup from pc/linux/install-linux.sh at runtime.
set -eu
cd "$(dirname "$0")"

if ! command -v cargo >/dev/null 2>&1; then
    echo "Rust is required: https://rustup.rs" >&2
    exit 1
fi

(
    cd ../rust
    cargo build --release
)
mkdir -p dist
for name in lemoGamePad-server lemoGamePad-app; do
    cp "../rust/target/release/$name" "dist/$name"
    echo "Built: $(pwd)/dist/$name"
done
cp linux/install-linux.sh dist/install-linux.sh
echo "Built: $(pwd)/dist/install-linux.sh"
