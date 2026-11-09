# lemoGamePad ultimate™

**Turn an Android phone into a gamepad for Windows or Linux PCs, or Android phone games.**

Wi-Fi-to-PC · Bluetooth HID to Android phones · Bluetooth PAN · USB · PC screen preview · **browser pad**

by **Alam showkat**

[![Latest release](https://img.shields.io/github/v/release/Alamshowkatdar/lemoGamePad-ultimate?label=release)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android build](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/android-build.yml/badge.svg)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/android-build.yml)
[![PC tests](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/pc-tests.yml/badge.svg)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/pc-tests.yml)

[**Download the latest release →**](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest) · [Report an issue](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/issues)

**App source version:** 1.6.9. Download the signed APK and the Windows/Linux server from the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest) — use **1.3.7.2 or newer** on Linux, which fixes the swapped X/Y face buttons. The release workflow also publishes a `SHA256SUMS.txt` so you can verify your download.

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

### What's new in 1.6.9

- **Player swaps now ask first** — selecting a pad another player is using no
  longer steals it. That player gets a small **Accept / Decline** card on their
  phone or browser tab, and the game keeps running while they decide. The swap
  happens only on **Accept**; **Decline**, no answer after 10 seconds, or a
  player leaving leaves both players exactly where they were. A free pad still
  moves immediately, and if two players ask for each other at the same time the
  swap is already mutual and applies at once.
- **The popup never pauses the game** — there is no backdrop and input keeps
  flowing from both pads. The requester sees a pending chip and line, and the
  result arrives as a short toast. Requests are retried over UDP, duplicate
  asks/results are ignored by id, and a briefly stale input frame can no longer
  bounce a player back to the old pad after a move.
- **Browser and Android stay in step** — the same consent flow works in the
  browser pad, including result toasts and the pending chip. Wire-compatible:
  older apps simply ignore the new packets and an unanswered request times out.
- **Everything from 1.6.8** — requested slots match the game's controller
  number, moves to never-used pads create them, and eight controllers work on
  Linux.
- Wire-compatible with 1.3 and later apps and servers.

### Highlights from 1.6.4

- **Browser pad rebuilt to match the Android app** — the page now has the same
  eight themes, PS/Xbox face labels, settings sheet, player-slot chips, status
  and latency graph, layout editing with named saved arrangements, and the PC
  screen preview. The pad is drawn on one canvas with the same geometry,
  spring-press animation and theme cross-fade as `GamepadView`.
- **Live PC screen in the browser** — the preview reuses the phone JPEG stream
  through an authenticated `/screen` relay, with LOW/BALANCED/HIGH quality,
  small/large size, drag-to-move and the same newest-frame coalescing as the
  phone path. It stays off until enabled and disappears in edit mode.
- **Layouts that survive a reload** — `localStorage` keeps the current
  arrangement, named layouts, theme, deadzone/curve, vibration, input-rate,
  slot, preview and its position on that browser.
- **Browser input modes** — ECO / STANDARD / TURBO now map to change-only with
  a heartbeat, change-only with a faster heartbeat, and continuous ~60 Hz.
  Rumble, keyboard play and the peer-slot handshake are unchanged.
- **Everything from 1.6.0–1.6.3** — saved layouts, eight phones by default,
  player-slot selection, PC hardening, the Android lifecycle fixes and the
  1.6.3 asset-loading fix.
- Wire-compatible with 1.3 and later apps and servers.

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

**No Android app?** Open the **Web pad** URL printed by the server on any
phone, tablet or laptop that is on the same Wi-Fi. It is a full touch/keyboard
gamepad served by the PC itself — no internet, no account, no extra install.
See [Browser pad](docs/WEB.md).

> **LAN only.** The control protocol is unauthenticated and unencrypted by
> design. The browser pad adds a per-run pairing key, but nothing here is meant
> to cross the internet. Never expose UDP 9876 / TCP 9877 / TCP 9878 to the
> internet or use the server on an untrusted network. See
> [SECURITY.md](SECURITY.md) and [Limitations](docs/LIMITATIONS.md).

## Browser pad

The server also hosts a small gamepad page on **TCP 9878** (control port + 2).
Open the URL printed in the console, or scan the browser QR that the server
draws in the console (a `lemoGamePad-web-qr-*.png` copy is also saved next to
the server):

```
http://192.168.1.20:9878/?k=<pairing-key>
```

- Works on phones, tablets and laptops — anything with a modern browser.
- Touch sticks, D-pad, face buttons, shoulders and triggers; multi-touch.
- Eight themes, PS/Xbox face labels, stick deadzone/curve, vibration strength
  and ECO/STANDARD/TURBO input-rate modes, all in the settings sheet.
- Player-slot chips (AUTO or P1–P8), a live status pill and latency graph, and
  CONNECT / RECONNECT / DISCONNECT.
- Drag/pinch layout editing with named saved arrangements, stored only in that
  browser's `localStorage`.
- A draggable PC screen preview with LOW/BALANCED/HIGH quality and small/large
  sizes, fed by the same JPEG stream as the Android app.
- **Full screen** from the settings sheet, so the browser bars get out of the
  way on a phone, tablet or laptop.
- On a laptop, the keyboard works too (`WASD` left stick, `IJKL` right stick,
  arrows D-pad, `Q`/`E` L1/R1, `1`/`2` L2/R2, numpad face buttons).
- Rumble is forwarded to the browser with the Vibration API where supported.
- The page is generated by the PC and makes **no internet requests** — no CDN,
  fonts, analytics or accounts.
- Each run gets a random pairing key; the page and WebSockets reject requests
  without it. Use `--web-key` to pin one, `--web-port` to move the port, or
  `--no-web` to turn the feature off.

See [Browser pad](docs/WEB.md) for controls and troubleshooting.

## Documentation

| Guide | Contents |
| --- | --- |
| [Windows install](docs/INSTALL-WINDOWS.md) | One double-click install, UAC, firewall, manual setup, moving the EXE. |
| [Linux install](docs/INSTALL-LINUX.md) | Release binary, from source, uinput, Wayland capture, firewall notes. |
| [Android app guide](docs/ANDROID.md) | Install, transports, pairing, up to eight phones, player-slot selection (P1–P8), on-screen pad, themes, settings, screen preview, custom layouts with saved arrangements, reconnecting. |
| [Browser pad](docs/WEB.md) | Use any phone or laptop browser as a gamepad over Wi-Fi — touch and keyboard, themes, layouts, player slots, latency, PC screen preview, pairing key and troubleshooting. |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Symptom → fix table for the most common problems. |
| [Command-line options](docs/COMMAND-LINE.md) | Every server flag, with defaults. |
| [How it works](docs/PROTOCOL.md) | Architecture and the 29-byte wire protocol. |
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

**lemoGamePad ultimate™ © Alam showkat** · v1.6.9
