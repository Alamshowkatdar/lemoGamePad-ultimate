# lemoGamePad ultimate™

**Turn an Android phone into a gamepad for Windows or Linux PCs, or Android phone games.**

Wi-Fi-to-PC · Bluetooth HID to Android phones · Bluetooth PAN · USB · PC screen preview

by **Alam showkat**

[![Latest release](https://img.shields.io/github/v/release/Alamshowkatdar/lemoGamePad-ultimate?label=release)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android build](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/android-build.yml/badge.svg)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/android-build.yml)
[![PC tests](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/pc-tests.yml/badge.svg)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/pc-tests.yml)

[**Download the latest release →**](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest) · [Report an issue](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/issues)

**App source version:** 1.5.0. Download the signed APK and the Windows/Linux server from the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest) — use **1.3.7.2 or newer** on Linux, which fixes the swapped X/Y face buttons. The release workflow also publishes a `SHA256SUMS.txt` so you can verify your download.

---

## What it is

`lemoGamePad ultimate` is two small programs:

| Piece | Runs on | What it does |
| --- | --- | --- |
| **lemoGamePad ultimate** (APK) | Android 7.0+ | Draws the pad, sends your inputs, shows the PC screen in a small window. |
| **lemoGamePad-server.exe** | Windows 10 / 11 | Receives the inputs and creates a **real virtual gamepad** that games see as an Xbox 360 controller (or a DualShock 4 with `--pad ds4`). |
| **`lemoGamePad-server`** / `pc/vpad_server.py` | Linux | Same receiver, using the kernel's `uinput` interface instead of ViGEmBus. |

Windows sees genuine controllers with analog sticks and pressure-sensitive
triggers. No root, no dongle, no proprietary receiver, and **no Python install on
the PC** — the server is a single `.exe`. On Linux the receiver needs no root
either, only access to `/dev/uinput`.

```
┌─────────────────┐   Wi-Fi / Bluetooth PAN / USB   ┌────────────────────────┐   ┌──────────────┐
│  Android phone  │ ──────────────────────────────▶ │  lemoGamePad-server    │──▶│ virtual pads │
│  gamepad + PiP  │ ◀────────────────────────────── │  (Windows .exe/Linux)  │   │  1-8 pads    │
└─────────────────┘      PC screen JPEG stream      └────────────────────────┘   └──────────────┘
         ▲                                                            ▲
         └──────────────── up to 8 phones ────────────────────────────┘
```

> **lemoGamePad ultimate™** — trademark of **Alam showkat**.
> App package: `com.alamshowkat.lemogamepad`.

### What's new in 1.5.0

- **Choose your player** — the Android settings sheet has **PLAYER SLOT**: AUTO
  or P1–P8. A connected phone moves to the chosen pad without reconnecting; a
  busy pad falls back to the next free one and is retried until it frees up.
  The status pill shows the pad the PC actually assigned.
- **Up to eight phones** — `--max-phones 1`…`8`. XInput games still see four
  pads; slots 5–8 work with `--pad ds4`, DirectInput/Raw Input titles and Linux.
- **PC hardening** — partial video writes are framed safely, a silent viewer no
  longer stalls others, token moves need the old address to go quiet, and the
  log, IP cache and timer stay off the receive path.
- **Safer helpers** — the ViGEm installer is re-hashed before UAC, the firewall
  helper runs from System32, and ghost-pad cleanup only touches ViGEm devices.
- **Android fixes** — screen preview, QR scanner and rumble shut down cleanly,
  identity tokens use `SecureRandom`, rebinds are synchronized, and ports are
  clamped so the video stream cannot target an invalid port.
- Wire-compatible with 1.4 apps and servers.

See the [changelog](CHANGELOG.md) for older releases and
[`docs/REVIEW.md`](docs/REVIEW.md) for the reasoning behind them.

---

## Quick start

Everything is on the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest):

| File | Platform |
| --- | --- |
| `lemoGamePad-<version>.apk` (older releases: `app-release.apk`) | Android 7.0 / API 24 and newer |
| `lemoGamePad-server.exe` | Windows 10 / 11 |
| `lemoGamePad-server` + `install-linux.sh` | Linux (x86-64) |
| `SHA256SUMS.txt` | Checksums for the files above |

1. Install the PC receiver — [Windows](docs/INSTALL-WINDOWS.md) or [Linux](docs/INSTALL-LINUX.md).
2. Install the [Android app](docs/ANDROID.md#install-the-android-app) and pair it with the QR code shown by the server.
3. Pick a link — Wi-Fi (recommended), Bluetooth PAN, or USB — and play.

> **LAN only.** The control protocol is unauthenticated and unencrypted by
> design. Never expose UDP 9876 / TCP 9877 to the internet or use the server on
> an untrusted network. See [SECURITY.md](SECURITY.md) and
> [Limitations](docs/LIMITATIONS.md).

## Documentation

| Guide | Contents |
| --- | --- |
| [Windows install](docs/INSTALL-WINDOWS.md) | One double-click install, UAC, firewall, manual setup, moving the EXE. |
| [Linux install](docs/INSTALL-LINUX.md) | Release binary, from source, uinput, Wayland capture, firewall notes. |
| [Android app guide](docs/ANDROID.md) | Install, transports, pairing, up to eight phones, player-slot selection (P1–P8), on-screen pad, themes, settings, screen preview, layout editing, reconnecting. |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Symptom → fix table for the most common problems. |
| [Command-line options](docs/COMMAND-LINE.md) | Every server flag, with defaults. |
| [How it works](docs/PROTOCOL.md) | Architecture and the 28-byte wire protocol. |
| [Building](docs/BUILDING.md) | Repository layout, source builds, signing the APK, tests, publishing. |
| [Releasing](docs/RELEASING.md) | Version bump, tag, release checklist, asset verification. |
| [Limitations](docs/LIMITATIONS.md) | What the project deliberately does not do. |
| [Changelog](CHANGELOG.md) | What changed in each release. |

## Contributing

Bug reports and pull requests are welcome. Read
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the build/test workflow and code style,
and [`SECURITY.md`](SECURITY.md) before reporting a security problem.

## License

Released under the [MIT License](LICENSE). Third-party components and their
licenses are listed in [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

---

**lemoGamePad ultimate™ © Alam showkat** · v1.5.0
