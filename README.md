# lemoGamePad ultimate™

**Turn an Android phone into a gamepad for Windows or Linux PCs, or Android phone games.**

Wi-Fi-to-PC · Bluetooth HID gamepad (PC + Android) · USB · PC screen preview · **browser pad**

by **Alam showkat**

[![Latest release](https://img.shields.io/github/v/release/Alamshowkatdar/lemoGamePad-ultimate?label=release)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android build](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/android-build.yml/badge.svg)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/android-build.yml)
[![PC tests](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/pc-tests.yml/badge.svg)](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/actions/workflows/pc-tests.yml)

[**Website & downloads →**](https://alamshowkatdar.github.io/lemoGamePad-ultimate/) · [Download the latest release →](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest) · [Report an issue](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/issues)

**App source version:** 2.2. Download the signed APK and the Windows/Linux server from the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest) — use the **2.2** APK and receiver together for matching version labels. The release workflow also publishes a `SHA256SUMS.txt` so you can verify your download.

---

## What it is

`lemoGamePad ultimate` is two small programs:

| Piece | Runs on | What it does |
| --- | --- | --- |
| **lemoGamePad ultimate** (APK) | Android 7.0+ | Draws the pad, sends your inputs, shows the PC screen in a small window, and notifies you when a newer release is published. |
| **lemoGamePad-app.exe** / **`lemoGamePad-app`** | Windows 10 / 11, Linux | Desktop control panel: start/stop the receiver, see live players and host, show/copy/save pairing QR codes, change settings and read the log. |
| **lemoGamePad-server.exe** / **`lemoGamePad-server`** | Windows 10 / 11, Linux | Native Rust console receiver. Receives inputs and creates a **real virtual gamepad** — Xbox 360 (or DualShock 4 with `--pad ds4`) through ViGEmBus on Windows, or evdev/uinput on Linux. |

Windows sees genuine controllers with analog sticks and pressure-sensitive
triggers. No root, no dongle, no proprietary receiver, and **no Python install on
the PC** — both the receiver and the desktop panel are native Rust binaries. On
Linux the receiver needs no root either, only access to `/dev/uinput`.

```
┌─────────────────┐        Wi-Fi / USB          ┌────────────────────────┐   ┌──────────────┐
│  Android phone  │ ──────────────────────────▶ │  lemoGamePad-server    │──▶│ virtual pads │
│  gamepad + PiP  │ ◀────────────────────────── │  (Windows .exe/Linux)  │   │  1-8 pads    │
└─────────────────┘      PC screen JPEG stream   └────────────────────────┘   └──────────────┘
         ▲                                                            ▲
         └──────────────── up to 8 phones ────────────────────────────┘

Bluetooth is a separate, server-less path: pair once, then pick the bonded host
with **SELECT HOST** in the app. The phone advertises a standard HID gamepad and
the host reads it directly; the app never connects automatically on launch.
```

> **lemoGamePad ultimate™** — trademark of **Alam showkat**.
> App package: `com.alamshowkat.lemogamepad`.

## Get started

1. Download the signed APK and the receiver from the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest).
2. Start `lemoGamePad-app` on Windows/Linux for the desktop panel, or use `lemoGamePad-server.exe` / `lemoGamePad-server` for the console. The panel supervises the same native receiver and shows the pairing QR automatically.
3. Open the phone app in landscape, tap **Connect**, and it becomes controller **P1**. Add more phones for up to eight pads.

Prefer no install? In the app's **Pairing / QR** tab choose **Browser**, or start the console server and open the **Web pad** URL it prints on a phone, iPhone or laptop on the same Wi-Fi.

Full guides: [Android phone as a gamepad](https://alamshowkatdar.github.io/lemoGamePad-ultimate/ANDROID.html) · [Windows receiver](https://alamshowkatdar.github.io/lemoGamePad-ultimate/INSTALL-WINDOWS.html) · [Linux receiver](https://alamshowkatdar.github.io/lemoGamePad-ultimate/INSTALL-LINUX.html) · [Browser pad](https://alamshowkatdar.github.io/lemoGamePad-ultimate/WEB.html) · [Troubleshooting](https://alamshowkatdar.github.io/lemoGamePad-ultimate/TROUBLESHOOTING.html) · [Command-line options](https://alamshowkatdar.github.io/lemoGamePad-ultimate/COMMAND-LINE.html)

### Desktop control panel

`lemoGamePad-app` is a thin control panel around the native Rust receiver.
It starts the receiver on launch, has a dark/light theme toggle and left-side navigation, and
has four panels. The panel checks GitHub for a newer release on launch and shows
a dismissible banner with a **View release** link when one is available; nothing
is downloaded automatically. Use **Check update** in the title bar to recheck.

- **Status** — server state, connected players, seat/device, packet rate, loss,
  battery, host, party lock, active ports and setup problems.
- **Pairing / QR** — app and browser pairing codes for every LAN address, plus
  **Copy link**, **Save QR** and **Open browser pad**.
- **Settings** — ports, pad type, player count, rumble, screen stream and
  browser key. **Apply & Restart** is needed for changes. **Install dependencies**
  opens a confirmation, then a system admin prompt (`pkexec` on Linux, UAC on
  Windows); it never runs by itself. On Windows it opens the ViGEmBus download
  and elevates `allow_firewall.ps1`.
- **Log** — the same messages the console receiver prints.

It still uses the existing protocol — no packet layout or pairing format
changed. From a source checkout, run `pc/gui.sh` after
`pc/linux/install-linux.sh`; add `--manual-start` to open the panel without
starting the server. `pc/desktop_gui.py` is the earlier Python/Tk reference and
is not part of the shipped binaries.

### New in 2.2

- **The desktop panel is easier to use.** Reliable drawn tab icons, a medium
  default window and scrolling keep every control reachable after resizing.
- **Linux sticks, triggers and D-pad work again.** A missing `EV_ABS` event type
  is now enabled; rumble setup was fixed at the same time.
- **One-click, confirmed dependency setup.** The panel can ask for `pkexec` on
  Linux or UAC on Windows. Windows opens the ViGEmBus page and elevates the
  firewall helper; nothing downloads or installs automatically.

### New in 2.1.5

- **The browser pad now shows the current version.** Its footer and in-app
  version label match the app/server release instead of stopping at 1.7.0.

### New in 2.1.4

- **P1 is the party host.** The first controller takes P1, P1 can swap with
  anyone, and `LOCK PARTY` follows whichever player ends up on P1.

### New in 2.1.3

- **PS4/DS4 pads move the sticks the right way.** On Windows, pads presented as
  a DualShock 4 (P5-P8, or `--pad ds4`) now invert the stick's vertical axis in
  the DS4 report, so games with automatic PS4 controller mapping read up as up.

### New in 2.1.2

- **No more intermittent stretching in the iPhone Home Screen web app.** iOS
  sometimes applies the final viewport and safe-area size after the pad has
  drawn itself once. The pad now watches its canvas size and re-measures on
  launch, wake and rotation, so the drawing always matches the screen.

### New in 2.1.1

- **Browser pad fits phone screens again.** A safe-area sizing bug made the
  canvas feed its own bitmap size back into the resize loop, zooming the pad up
  on Android and iOS until the controls were off-screen. The canvas now scales
  to the viewport minus the notch/home-indicator insets.

### New in 2.1.0

- **Control Centre and setup polish.** Android uses a compact tabs-only sheet
  with a dense mode; the browser keeps a pinned command dock and a jump rail.
  First-run setup, a live Connection Doctor and an offline Help centre make the
  common paths easier to find.
- **Game-accurate rumble.** The phone sustains one smooth vibration whose level
  follows both game motors, stops the moment the game stops, and survives a lost
  packet. The browser pad behaves the same way. A separate **Vibration from
  games** switch silences game force feedback without losing button clicks.
- **Party lock and guest QR join.** P1 is the host seat and can freeze seat
  changes from the LINK / CONNECTION card. P1 can swap with any other player and
  the host role follows it. Other players keep playing, late guests can still
  take a free pad, and the lock releases if the host leaves. A second guest QR
  (and `&auto=1` browser link) joins the next free pad without touching a saved
  slot preference.
- **Layout QR sharing.** **SHARE QR** / **SCAN QR** in Android's LAYOUTS card
  exchange a named pad arrangement with another phone. The browser offers
  **SHARE CODE** / **IMPORT CODE** using the same text payload, and can also open
  a `#layout=` link. Nothing is uploaded; the code is just the layout data.

### New in 2.0.3

- **Real blue icon on the launcher.** Some launchers tint adaptive icons to a
  monochrome silhouette; the app now uses its density PNG icons directly so the
  cobalt glass logo is what you see.

### New in 2.0.2

- **Fixed launcher icon.** Density PNG mipmaps and round icons ensure the app
  icon shows on launchers that cannot render vector icons.
- **Blue-glass logo.** The app icon and downloads site now use the cobalt glass
  emblem.

### New in 2.0.1

- **Gold gamepad logo.** The app launcher and the downloads site share a new
  emblem that mirrors the in-app landscape gamepad layout.
- **Plain first launch.** Tilt steering and gyro aim start `OFF`; enable only
  the motion input you need.

### New in 2.0.0

- **One Gamepad layout.** The separate **Racing** and **FPS** modes and their
  layouts are removed. The standard pad keeps sticks, d-pad, shoulders,
  triggers, face buttons and Start/Select/Home.
- **Aim touchpad.** When enabled it replaces the right stick and its click. The
  default **BGMI-style swipe** turns the camera with each sweep; the switchable
  **Mouse aim** mode turns finger speed into relative game-mouse aim that stops
  when your finger stops. Neither mode sends desktop mouse events — both drive
  the same right-stick axes, and gyro aim can add at the same time.
- **Per-axis gyro revert.** Revert left/right aim and revert up/down aim can be
  switched independently, so only the direction that feels wrong needs flipping.
- **One motion card, independent switches.** Tilt steering feeds left X and gyro
  rotation feeds right-stick aim; both live in the same **MOTION & AIM** card and
  can run together. You choose which to enable for the game you are playing —
  there is no foreground-game detection. Tilt only overrides left X while it is
  off centre, so the left stick still works when the phone is level.
- **One set of named layouts.** Layout presets are no longer split per mode;
  swapping the swipe pad on or off preserves the previous control placement.
- **Manual game profiles.** Save a full control setup under a name and tap to
  switch. There is still no foreground-game detection — you choose when to swap.
- **Aim calibration.** A ten-target reticle drill exercises touch and gyro aim
  with a persistent free reticle and tap-to-fire, then suggests sensitivity or
  revert changes.
- **Adaptive stream quality.** Optionally lower or raise video sharpness from
  measured FPS and link health. All profiles still target 60 fps; manual taps
  pause the automatic controller.

### What was new in 1.7.0

> **Historical release notes.** Mode switching, per-mode layouts and automatic
> Bluetooth pairing described below were superseded by the 2.0.0 changes above.
> See [`CHANGELOG.md`](CHANGELOG.md) for the current behaviour.

- **Three controller experiences in one app.** Switch between **Gamepad**,
  **Racing** and **FPS** from the CONTROL CENTRE. Each mode has its own layout,
  hints and motion settings.
- **Racing** adds gyro/tilt steering with calibration, sensitivity, dead zone and
  smoothing, plus analog gas/brake pedals, nitro, handbrake, gears, camera and
  reset. Steering drives the left stick, pedals drive analog L2/R2.
- **FPS** adds a customisable right-hand camera touchpad with swipe aim, optional
  gyro aim, and dedicated fire, aim, reload, jump, crouch, sprint and swap
  controls. Camera/gyro aim drives the right stick.
- **Gamepad** gets Xbox ABXY or PlayStation symbols, sticks, d-pad, shoulders,
  triggers and Start/Select/Home, with a one-tap Xbox/PlayStation face switch.
- **Face buttons keep their iconic colours in every theme** — Xbox A green,
  B red, X blue, Y yellow; PlayStation Cross blue, Circle red, Square pink,
  Triangle green.
- **Ten themes with preview-before-apply** — Midnight Black, Cyberpunk, Arctic,
  AMOLED, Racing Red, Xbox, PlayStation, Minimal, Retro Arcade and Stealth.
  Each has its own border weight, glow/shadow, letter spacing and backdrop.
- **Customization Studio** — drag, pinch-resize or hide any control, save named
  layouts per mode, duplicate/reset, and import or export layouts as text.
- **60 fps screen preview, smoothness first** — all quality levels share a
  60 fps cadence; LOW/BALANCED/HIGH only change sharpness (300/480/600 px).
  Capture uses BOX downscaling and fixed-cadence pacing, and the phone reuses
  decoded bitmaps. The FPS badge shows the received rate. On Wayland, where
  capture shells out to `grim`, the realized rate is limited by the screenshot
  tool.
- **All output protocols are unchanged** — UDP on 9876, video TCP on 9877 and
  the 9-byte Bluetooth HID report all keep the same wire format.

### What's new in 1.6.13

> **Superseded by the current explicit-host behaviour:** the app no longer
> registers or reconnects to a Bluetooth host on launch. Pair once, then use
> **SELECT HOST** in **BLUETOOTH GAMEPAD**.

- **Bluetooth is now a real, automatic HID gamepad.** The old Bluetooth PAN
  transport — tethering, `bnep`, "Join a Personal Area Network" — is gone. The
  phone advertises **Lemo GamePad** as a standard HID controller for a PC or
  Android host, registers itself on launch, and reconnects to a bonded host by
  itself. Pairing is the only manual step.
- **New HID report layout** built from scratch: 16 buttons, an 8-way hat switch
  so DirectInput games get a real d-pad, four signed stick axes and analog
  L2/R2. Reports are released (neutral) when the app is backgrounded.
- **Wi-Fi and USB are untouched** — same server, same protocol, same eight
  players. Only the Bluetooth transport chip, PAN discovery and the tethering
  card were removed.

<details>
<summary>1.6.12</summary>

- **The browser pad recovers from a network change** — the printed Web pad URL,
  the console QR and the saved `lemoGamePad-web-qr-<ip>.png` files used to be
  produced once, at startup. When the PC joined another Wi-Fi network, the old
  address kept being advertised and the link failed on every device, PC
  included, while the server itself was still fine. The server now notices the
  new addresses within about ten seconds, prints the current `Web pad` URL and
  rewrites the QR images.
- Wire-compatible with 1.3 and later apps and servers.

</details>

<details>
<summary>1.6.11</summary>

- **AMOLED theme** — a ninth palette with a true `#000000` background and
  near-black surfaces. On an OLED/AMOLED phone those pixels switch fully off,
  so the same pad uses less power. LCDs see no battery saving, but the look is
  still available. The decorative background glow is removed for AMOLED so the
  background is genuinely black rather than near-black.
- **A new `void` press** — AMOLED gets its own one-shot press: a single hairline
  ring that snaps outward and disappears into the black, echoed by the
  `ui-void` animation on the browser's chips, toggles, actions and theme tiles.
- **D-pad animations fixed** — each d-pad direction now owns a real press
  spring in both clients, so an arrow eases in and out instead of freezing at a
  fixed glow or snapping off. Browser keyboard arrows animate too.
- Wire-compatible with 1.3 and later apps and servers.

</details>

### Highlights from 1.6.10

- **Animated buttons, one motion per theme** — every palette now has its own
  one-shot press response on the phone and the browser pad. **Neon** pulses,
  **Midnight** sends a wave, **Violet** blooms a halo, **Crimson** shocks,
  **Solar** bursts, **Retro** steps in quantised chunks, **Arctic** ripples in
  three rings and **Mono** draws a precise underline. The same motif plays on
  the chips, toggles and theme tiles in the settings sheet.
- **You control the motion** — **APPEARANCE → Animated buttons** turns the
  effects on or off in both the Android settings sheet and the browser CONTROL
  CENTRE. It defaults to on unless the device asks for reduced motion. Ripples
  are one-shot and only advance while a press is settling, so an idle pad still
  renders nothing and battery behaviour is unchanged.
- **Everything from 1.6.9** — consent-based player swaps that never pause
  input.
- Wire-compatible with 1.3 and later apps and servers.

### Highlights from 1.6.9

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

### Highlights from 1.6.4

- **Browser pad rebuilt to match the Android app** — the page now has the same
  nine themes, PS/Xbox face labels, settings sheet, player-slot chips, status
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
3. Pick a link — Wi-Fi (recommended) or USB — and play.

**Prefer no PC server?** The app is also a standard Bluetooth HID gamepad. Pair
**Lemo GamePad** once from a PC or Android host and it appears as a real
controller — no driver, tethering or PC program. See
[Bluetooth gamepad](#bluetooth-gamepad-no-pc-server).

**No Android app?** Open the **Web pad** URL printed by the server on any
phone, tablet or laptop that is on the same Wi-Fi. It is a full touch/keyboard
gamepad served by the PC itself — no internet, no account, no extra install.
See [Browser pad](docs/WEB.md).

> **LAN only.** The control protocol is unauthenticated and unencrypted by
> design. The browser pad adds a per-run pairing key, but nothing here is meant
> to cross the internet. Never expose UDP 9876 / TCP 9877 / TCP 9878 to the
> internet or use the server on an untrusted network. See
> [SECURITY.md](SECURITY.md) and [Limitations](docs/LIMITATIONS.md).

## Bluetooth gamepad (no PC server)

The phone can also act as a normal Bluetooth HID gamepad. This path does not use
`lemoGamePad-server`, the network protocol or USB/Wi-Fi tethering.

1. Open the app (Android 9+). It registers **Lemo GamePad** automatically.
2. On the host — Windows, Linux or another Android phone — open Bluetooth
   settings and pair **Lemo GamePad** once.
3. Play. The app reconnects to the bonded host on later launches, and keeps the
   profile registered while it is running.

On Windows the pad appears as a standard DirectInput HID controller, not an
XInput one, so games that only read XInput should use the Wi-Fi/USB server link
instead. This mode has no rumble, screen preview or multi-phone slots.

## Browser pad

The server also hosts a small gamepad page on **TCP 9878** (control port + 2).
Open the URL printed in the console, or scan the browser QR that the server
draws in the console (a `lemoGamePad-web-qr-*.png` copy is also saved next to
the server):

```
http://192.168.1.20:9878/?k=<pairing-key>
```

- Works on phones, tablets and laptops — anything with a modern browser,
  including Safari on iPhone (iOS 13+ recommended). On iPhone, add the page to
  the Home Screen to launch it full-screen without Safari's bars, and use
  `--web-key-file` so the shortcut survives server restarts; see
  [Browser pad](docs/WEB.md#iphone-and-safari).
- Touch sticks, D-pad, face buttons, shoulders and triggers; multi-touch.
- Nine browser themes and ten Android palettes, PS/Xbox face labels, stick
  deadzone/curve, vibration strength and ECO/STANDARD/TURBO input-rate modes,
  all in the settings sheet. Each palette has its own animated press ripple.
  The browser pad keeps a pinned quick dock and category rail with scroll
  memory; the Android sheet uses one six-chip tab row and starts near the top.
  Wide browser windows get two columns, phones one roomy column, and **DENSE**
  hides the longer notes when you want a denser view.
- Player-slot chips (AUTO or P1–P8), a live status pill and latency graph, and
  CONNECT / RECONNECT / DISCONNECT. The player on P1 gets **LOCK PARTY**; if P1
  swaps, the new P1 player becomes host. Every other tab sees its seat frozen
  without its input being paused.
- A **guest QR** printed by the server (with `&auto=1`) opens the page in AUTO,
  so the PC assigns the next free pad without saving over that tab's usual slot.
- Drag/pinch layout editing with named saved arrangements, stored only in that
  browser's `localStorage`.
- A draggable PC screen preview with LOW/BALANCED/HIGH quality and small/large
  sizes, fed by the same JPEG stream as the Android app.
- **Full screen** from the settings sheet, so the browser bars get out of the
  way on a phone, tablet or laptop.
- On a laptop, the keyboard works too (`WASD` left stick, `IJKL` right stick,
  arrows D-pad, `Q`/`E` L1/R1, `1`/`2` L2/R2, numpad face buttons).
- Rumble is forwarded to the browser with the Vibration API where supported,
  with a quiet sound-cue fallback on iPhone Safari.
- The page is generated by the PC and makes **no internet requests** — no CDN,
  fonts, analytics or accounts.
- Each run gets a random pairing key; the page and WebSockets reject requests
  without it. Use `--web-key` to pin one, `--web-key-file` to reuse one across
  restarts, `--web-port` to move the port, or `--no-web` to turn the feature off.

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

**lemoGamePad ultimate™ © Alam showkat** · v2.2
