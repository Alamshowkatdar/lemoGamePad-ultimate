# Command-line options

> Part of [lemoGamePad ultimate](../README.md).

## Command-line options

```
lemoGamePad-server.exe                     # virtual Xbox 360 pad (best compatibility)
lemoGamePad-server.exe --pad ds4           # virtual DualShock 4 instead
lemoGamePad-server.exe --port 9999         # different control port (app setting must match)
lemoGamePad-server.exe --max-phones 8      # allow 1-8 phones at once (default 8)
lemoGamePad-server.exe --no-rumble         # do not forward rumble to the phone
lemoGamePad-server.exe --no-video          # disable the PC screen stream
lemoGamePad-server.exe --no-qr             # do not print the pairing QR code
lemoGamePad-server.exe --no-install-deps   # no auto-install of ViGEmBus / packages / firewall
lemoGamePad-server.exe --video-port 9990   # override the TCP stream port
lemoGamePad-server.exe --video-fps 8       # stream frame rate (default 20)
lemoGamePad-server.exe --video-width 480   # stream width (default 800)
lemoGamePad-server.exe --video-quality 45  # JPEG quality 20-90 (default 50)
lemoGamePad-server.exe --list-ip           # print this PC's LAN IPs and exit
```

On Linux use `python3 vpad_server.py` with the same options (or the built
`dist/lemoGamePad-server`).

The console shows the PC IPs, the pairing QR, one line per connected phone
(slot, address, packets/second, battery), the idle state and the slot limit.
`Ctrl+C`, `Ctrl+Break` or closing the window quits and unplugs every pad.

**Eight phones on Windows.** XInput, the API most Windows games use for
controllers, has a hard limit of four pads. With the default `x360` backend the
server therefore emulates four Xbox 360 pads and automatically switches pads
5–8 to DualShock 4/HID, which DirectInput and Raw Input games can see. If a
game lists only four controllers, that is the game's XInput limit, not the
server: use `--pad ds4` (all eight as DirectInput) or a game/emulator with
DirectInput support. Linux has no such limit and can use all eight Xbox 360
pads.

**Why Xbox 360 and not a literal DualShock 3?** Windows has no built-in DS3
driver; a true DS3 needs a kernel driver (DsHidMini). Games and emulators
(RPCS3, PCSX2, Steam, RetroArch) all accept a standard pad and map the PS3
layout to it exactly as the table above. Use `--pad ds4` if a game shows
PlayStation button prompts.

---
