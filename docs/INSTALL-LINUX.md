# Linux install

> Part of [lemoGamePad ultimate](../README.md).

## Release binary

Keep `lemoGamePad-server` and `install-linux.sh` in the same folder, then:

```bash
sudo sh install-linux.sh
./lemoGamePad-server
```

The installer notices the prebuilt receiver and skips Python entirely. It sets
up `uinput`, installs a Wayland screen-capture helper when needed, opens the
firewall ports if `ufw`/`firewalld` is active, and adds the application-menu
launcher.

The release also attaches `install-linux.sh`: keep it in the same folder as
`lemoGamePad-server` and run it once. It sets up `uinput`, the Wayland capture
helper and the application-menu launcher. When it finds a source checkout it
also creates `pc/.venv` and installs the Python requirements.

## From source

The receiver runs on Linux too and creates a real evdev gamepad through the
kernel's `/dev/uinput` interface — the Linux equivalent of ViGEmBus. Steam,
Proton, SDL2, RetroArch and native games see an Xbox 360 pad (or a DualShock 4
with `--pad ds4`), including force-feedback rumble.

One command sets up everything (Arch, Debian/Ubuntu, Fedora, openSUSE, Void and
Alpine are detected automatically):

```bash
sudo sh pc/linux/install-linux.sh
# log out and back in only if the script says the input group is not active yet
```

It installs the distribution packages (Python 3 with venv/pip, the evdev build
dependencies and a Wayland capture helper), loads the `uinput` module, installs
the udev rule, adds your user to the `input` group, creates `pc/.venv`,
pip-installs `pc/requirements.txt`, opens UDP 9876 / TCP 9877 when `ufw` or
`firewalld` is active, and adds a **lemoGamePad ultimate** entry to the
application menu. It is safe to rerun, and `pc/linux/install-uinput.sh` still
works as an alias for older instructions.

Then start it:

```bash
pc/linux/run.sh
```

`run.sh` uses `pc/.venv` automatically. You can also activate the venv yourself:

```bash
cd pc
. .venv/bin/activate
python3 vpad_server.py
```

`install-linux.sh --dry-run` shows what it would do. Use `--no-packages`,
`--no-venv`, `--no-uinput`, `--no-firewall` or `--no-shortcut` to skip a part.

The banner shows `uinput : OK (virtual gamepad via /dev/uinput)` when the pad is
ready. The server never runs `sudo` or pip-installs behind your back while it is
running on Linux; if something is missing it prints the exact command.
`./pc/build_linux.sh` builds a single-file `dist/lemoGamePad-server` with
PyInstaller.

Linux notes:

- Screen preview works on X11 (via `mss`) and on Wayland. A native Wayland session
  does not let programs read the screen directly, so the server asks a screenshot
  tool for each frame and uses the first one that works: `grim` (Sway, Hyprland,
  wlroots, COSMIC), `spectacle` (KDE Plasma) or `gnome-screenshot` (GNOME, best
  effort). `install-linux.sh` installs one for the current session (`grim` on
  wlroots/Hyprland/Sway, `spectacle` on KDE, `gnome-screenshot` on GNOME); you
  can also install one yourself with `sudo apt install grim`. `spectacle` and
  `gnome-screenshot` start a process per frame, so expect a lower frame rate than
  `grim` or X11. Set `LEMO_CAPTURE=mss|grim|spectacle|gnome-screenshot` to force a
  backend. On a headless machine the server still works as a gamepad and prints
  why capture is unavailable.
- `install-linux.sh` opens UDP `9876` and TCP `9877` when `ufw` or `firewalld` is
  active. Other firewall setups are left alone; if phones cannot reach the PC,
  allow those two ports yourself.
- Rumble uses the standard evdev force-feedback API, so games that rumble
  through SDL work without per-game configuration.

---
