# ViGEm Client SDK source

This directory contains the MIT-licensed ViGEm Client C++ source from
<https://github.com/nefarius/ViGEmClient> (upstream commit
`691e13c164dc5028ae0fd04a35b3c467fe899634`, version 1.21.245.0).

`rust/build.rs` compiles `src/ViGEmClient.cpp` into the Windows binaries as a
static library. This removes the runtime dependency on a separately installed
`ViGEmClient.dll`; only the signed ViGEmBus driver is needed on the PC.

Upstream license: `LICENSE` in this directory.
