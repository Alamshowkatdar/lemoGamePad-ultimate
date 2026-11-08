# Command-line options

> Part of [lemoGamePad ultimate](../README.md).

## Command-line options

```
lemoGamePad-server.exe                     # virtual Xbox 360 pad (best compatibility)
lemoGamePad-server.exe --pad ds4           # virtual DualShock 4 instead
lemoGamePad-server.exe --port 9999         # different control port (app setting must match)
lemoGamePad-server.exe --max-phones 2      # allow 1-4 phones at once (default 4)
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

**Why Xbox 360 and not a literal DualShock 3?** Windows has no built-in DS3
driver; a true DS3 needs a kernel driver (DsHidMini). Games and emulators
(RPCS3, PCSX2, Steam, RetroArch) all accept a standard pad and map the PS3
layout to it exactly as the table above. Use `--pad ds4` if a game shows
PlayStation button prompts.

---
