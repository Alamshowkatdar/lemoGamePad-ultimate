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
lemoGamePad-server.exe --no-web            # disable the browser gamepad page
lemoGamePad-server.exe --web-port 8080     # browser pad TCP port (default: control port + 2)
lemoGamePad-server.exe --web-key mysecret  # fixed browser pairing key (default: random per run)
lemoGamePad-server.exe --web-key-file lemoGamePad-web.key  # reuse a saved key (stable Home Screen URL)
lemoGamePad-server.exe --video-port 9990   # override the TCP stream port
lemoGamePad-server.exe --video-fps 60      # stream frame rate, capped at 60 (default 60)
lemoGamePad-server.exe --video-width 480   # balanced-profile width (default 480)
lemoGamePad-server.exe --video-quality 42  # JPEG quality 20-90 (default 42)
lemoGamePad-server.exe --list-ip           # print this PC's LAN IPs and exit
```

On Linux use `python3 vpad_server.py` with the same options (or the built
`dist/lemoGamePad-server`).

**Browser pad.** Unless `--no-web` is passed, the server also hosts a gamepad
page on TCP **control port + 2** (default `9878`). The console prints the full
URL, including a random pairing key:

```
Web pad      : http://192.168.1.20:9878/?k=AbCdEf12
```

Every run gets a new key unless you pass `--web-key`. The port must differ from
the control port and the screen-stream port. See [Browser pad](WEB.md). Like the
phone protocol this is LAN-only — never forward the port on a router.

**Stable key for bookmarks and Home Screen.** An iPhone added to the Home Screen
keeps the original `?k=` URL, so a per-run key breaks that shortcut on the next
launch. Use `--web-key-file PATH` to create a key on first run and reuse it
afterwards (created with owner-only permissions on POSIX); an explicit
`--web-key` takes precedence. Keep the file private — the key is the only thing
guarding the local page. If the file is unreadable or corrupt, the server warns
and falls back to a random key instead of refusing to start.

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
layout to it exactly as the [face-button table](ANDROID.md#using-the-pad). Use `--pad ds4` if a game shows
PlayStation button prompts.

---
