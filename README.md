# lemoGamePad ultimate™

**Turn an Android phone into a PS3-style gamepad for Windows.**

Wi-Fi · Bluetooth · USB · QR pairing · PC screen preview · up to 4 phones on one PC

by **Alam showkat**

**App source version:** 1.3.4. Download the signed APK and Windows server from the [v1.3.4 release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/tag/v1.3.4).

[**Download the latest release →**](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest)
· [Report an issue](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/issues)

---

## What it is

`lemoGamePad ultimate` is two small programs:

| Piece | Runs on | What it does |
|---|---|---|
| **lemoGamePad ultimate** (APK) | Android 7.0+ | Draws the pad, sends your inputs, shows the PC screen in a small window. |
| **lemoGamePad-server.exe** | Windows 10 / 11 | Receives the inputs and creates a **real virtual gamepad** that games see as an Xbox 360 controller (or a DualShock 4 with `--pad ds4`). |

Windows sees genuine controllers with analog sticks and pressure-sensitive
triggers. No root, no dongle, no proprietary receiver, and **no Python install on
the PC** — the server is a single `.exe`.

```
┌─────────────────┐   Wi-Fi / Bluetooth PAN / USB   ┌────────────────────────┐   ┌──────────────┐
│  Android phone  │ ──────────────────────────────▶ │  lemoGamePad-server    │──▶│  ViGEmBus    │
│  gamepad + PiP  │ ◀────────────────────────────── │  (Windows .exe)        │   │  1-4 pads    │
└─────────────────┘      PC screen JPEG stream      └────────────────────────┘   └──────────────┘
         ▲                                                            ▲
         └──────────────── up to 4 phones ────────────────────────────┘
```

> **lemoGamePad ultimate™** — trademark of **Alam showkat**.
> App package: `com.alamshowkat.lemogamepad`.

---

## 1. Download

Open the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest)
and download both files:

| File | Size | What it is |
|---|---|---|
| `lemoGamePad-server.exe` | ~29 MB | Windows receiver. Just run it — it installs its own driver and firewall rules. |
| APK attached to the latest release | ~0.9 MB | Android app (Android 7.0 / API 24 and newer). |

The binaries are attached to the release, not stored in the git history.
`release/` is a local build folder and is ignored by git.
The `pc\...` scripts mentioned below (firewall repair, ghost-pad cleanup, tests)
ship with the **source repository**, not with the two release files.

> **Windows SmartScreen:** the EXE is not code-signed (a certificate costs money),
> so Windows may show *"Windows protected your PC"*. Click **More info → Run
> anyway**. Apart from downloading ViGEmBus when it is missing, the server does
> not use the internet.

---

## 2. Install on a fresh PC (one double-click)

1. Put `lemoGamePad-server.exe` in a folder (for example `C:\lemoGamePad\`) and
   double-click it.
2. The server checks what the PC is missing and installs it:
   - **ViGEmBus driver** — the signed kernel driver that creates virtual pads.
     If it is missing, the server downloads the official Nefarius build
     (version 1.22.0), verifies its SHA-256 and runs the silent installer.
   - **Windows Firewall rules** — inbound UDP on the control port and TCP on the
     video port, for **all network profiles** (Domain, Private, Public), because
     Windows often classifies USB tethering and Bluetooth PAN as *Public*.
3. **Windows will show one or two UAC prompts** ("Do you want to allow this app
   to make changes?"). Click **Yes**. This is required to install a driver and
   change firewall rules. Nothing else is elevated.
4. When setup finishes, the console shows something like:

   ```
   lemoGamePad ultimate  -  virtual Xbox 360 gamepad
   by Alam showkat
   ----------------------------------------------------------
   Control port : 9876 (UDP)
   Screen stream: 9877 (TCP)
   Controllers  : up to 4 phones at once
   ViGEmBus     : OK (virtual gamepad driver installed)
   PC IP        : 192.168.1.23
   Firewall     : OK (UDP 9876 / TCP 9877 allowed)
   ...
   ```

   and prints a **QR code** you can scan from the phone. Leave this window open
   while you play.

That is the whole PC install. There is **no Python, no pip, no driver hunting,
no firewall script** needed on a normal machine.

### What if I don't want the automatic setup?

Run it once with the escape hatch:

```powershell
lemoGamePad-server.exe --no-install-deps
```

Then install ViGEmBus yourself from
<https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0> and open the firewall
with `pc\allow_firewall.ps1` (as Administrator) if phones cannot find the PC.
The banner always tells you the real state, so you can see what is missing.

### If UAC is cancelled

Nothing breaks — the server still starts and prints the exact commands you can
run later. Install ViGEmBus manually and/or run the `netsh` lines the server
prints in an **Administrator** PowerShell (or `pc\allow_firewall.ps1` from the
source repository):

```powershell
powershell -ExecutionPolicy Bypass -File .\pc\allow_firewall.ps1
```

### Moving the EXE later

The rules the server adds are tied to the port, not to a folder, so moving the
EXE keeps working. (Rules added by `pc\allow_firewall.ps1` are tied to the old
EXE path — rerun that script after moving if you used it.)

---

## 3. Install the Android app

1. Download the APK attached to the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest) and copy it to the phone (USB, cloud drive, email —
   anything).
2. Tap it. Android will warn about installing from an unknown source; allow it
   for the app you used to open the file (Files, Chrome, …).
3. Open **lemoGamePad ultimate** and turn the phone **landscape**.

The app is distributed outside the Play Store. Official releases use the same
signing key going forward. Version 1.3.2 uses a new key, so uninstall 1.3.1
before installing it. Locally built APKs use your own key; see
[Signing the APK yourself](#signing-the-apk-yourself).

---

## 4. Choose how the phone talks to the PC

All three modes use the same unencrypted local protocol and all three are
supported by the same APK. Pick the one that fits your situation:

| Mode | Best for | Phone setting | PC setting | Typical latency |
|---|---|---|---|---|
| **Wi-Fi** | Normal home network, best range and speed | Wi-Fi on, same network as the PC | Nothing | 3–15 ms |
| **Bluetooth** | No router / locked-down Wi-Fi / cable-free | Bluetooth **tethering** (PAN) | Join the phone's Personal Area Network | ~30 ms |
| **USB** | Lowest latency, charging while playing, no radio interference | **USB tethering** | Nothing | ~3 ms |

> **They are network links, not custom drivers.** Android's Bluetooth PAN and USB
> tethering create a private network interface (`bnep*`, `rndis*`, `ncm*`,
> `usb*`). The app sends the same UDP packets over that interface, so the PC needs
> no extra software.

### 4a. Wi-Fi (recommended)

1. Put the phone and the PC on the **same Wi-Fi network**.
   Avoid guest networks: many of them isolate clients from each other.
2. Start the server on the PC and note the `PC IP` line.
3. On the phone: tap the status pill at the top centre → the settings sheet
   slides in → tap the **WI-FI** chip (or leave **AUTO**).
4. Tap **Scan network**. It reports what it did, for example
   `wi-fi · 1 link · 6 probes · 1 reply`.
5. Tap **Connect**. The pill turns green:
   `YOUR-PC · WI-FI · 6 ms · 87%`.

If the PC is not found even though both devices are on the same network, read
the diagnostics line and the **Firewall** line in the server window, then see
[Troubleshooting](#8-troubleshooting).

### 4b. Bluetooth (PAN)

Bluetooth works, but Windows initiates the PAN join — Android cannot dial the PC
by itself. The app's Bluetooth card guides you through it.

**On the phone**

1. Turn Bluetooth on.
2. Open the app → status pill → **BLUETOOTH** chip.
3. If the card says *access is off*, tap **ALLOW NEARBY DEVICES** and accept the
   Android 12+ **Nearby devices** prompt.
4. Tap **PAIR WITH PC** to open the system Bluetooth page and pair with the PC.
   Tap **DISCOVERABLE** if the PC cannot see the phone.
5. Enable **Bluetooth tethering**:
   **Settings → Network → Hotspot & tethering → Bluetooth tethering**
   (wording varies by manufacturer). The app's **OPEN TETHERING SETTINGS**
   button jumps straight there.

**On the PC**

6. Open **Settings → Bluetooth & devices → Devices**.
7. Find the phone → **More options** → **Join a Personal Area Network**.
8. The app's Bluetooth card now shows a live `bnep*` address, and the chip's
   readiness dot turns green.

**In the app**

9. Tap **Scan network**, then **Connect**.

**Bluetooth is a ~3 Mbps link.** Input is tiny and measures **~30 ms**, but the
PC screen preview is a JPEG stream and will saturate the link:

| Preview | Result on a Galaxy S24 Ultra |
|---|---|
| Off | 31 ms steady |
| On | 927 ms → 6.5 s → `LINKING…` |
| Back off | Recovers by itself |

Keep **Screen preview** off, or at **SMALL**, while using Bluetooth.

### 4c. USB (fastest, and charges the phone)

1. Connect the phone to the PC with a **data** USB cable (not a charge-only one).
2. On the phone enable **USB tethering**:
   **Settings → Network → Hotspot & tethering → USB tethering**.
   The app's **OPEN TETHERING SETTINGS** button jumps there.
3. In the app: status pill → **USB** chip → **Scan network** → **Connect**.
4. Windows sees a new network adapter (`rndis*` / `ncm*`). No driver install is
   needed on Windows 10/11.

USB is usually the lowest-latency option and keeps the battery topped up. If
Windows marks that adapter as a **Public** network, the firewall rules created by
the server already cover it.

### Which chip should I use?

- **AUTO** (default) probes every link and connects over whichever answers.
- **WI-FI / BT / USB** start with that link, and *widen* to the others if it gets
  no reply. If that happens the app tells you, for example
  `WI-FI found no PC — linked over USB instead`, and the diagnostics line says
  `widened`. It never silently lies about the transport in use.

Each chip carries a small dot: **green** = that interface is up, **grey** = it
is missing. **AUTO** probes everything.

---

## 5. Pair the app with the PC

The fastest way is the QR code in the server window:

1. Tap the status pill.
2. Tap **Scan QR** and point the camera at the code in the console.
3. Tap **Connect**.

Other ways:

- **Scan network** — probes the selected link, then every other link, then sweeps
  each `/24` host-by-host if the router drops broadcast. The line under the
  button shows the result (`all links · 2 links · 18 probes · 1 reply`).
- **Manual entry** — type the `PC IP` shown in the server window and tap
  **Connect**.

The server saves a PNG copy of the QR code (`lemoGamePad-qr-<ip>.png`) in the
folder you launched it from, in case you want to show it on another screen.

The QR payload is `vpad://connect?h=<ip>&p=<port>&n=<hostname>`; plain
`1.2.3.4:9876` text is accepted too.

---

## 6. Up to four phones on one PC

Install the APK on the other phones and pair them the same way.

- Each phone gets **its own virtual controller**. XInput slot order is the order
  the phones first connected.
- Default limit is **4 phones**; change it with `--max-phones 1` … `--max-phones 4`.
- A fifth phone is ignored and the console logs
  `ignored <ip>: all N controller slots are in use`.
- Each phone keeps its slot (and its in-game player number) for as long as the
  server runs. A phone that drops off and reconnects returns as the same player.
- Rumble is routed to the correct phone only.

The phone now keeps **one identity for life**: its token is stored in app
preferences, so restarting the app does not look like a new phone and does not
consume another controller slot.

---

## 7. Using the pad

| On screen | PS3 button | Virtual Xbox 360 pad |
|---|---|---|
| ✕ | Cross | A |
| ○ | Circle | B |
| □ | Square | X |
| △ | Triangle | Y |
| L1 / R1 | L1 / R1 | LB / RB |
| L2 / R2 | L2 / R2 | LT / RT (analog 0–255) |
| D-pad | D-pad | D-pad |
| Left / right stick | Analog sticks | Sticks |
| L3 / R3 pills | Stick click | LS / RS click |
| SELECT / START | Select / Start | Back / Start |
| PS | PS button | Guide |

Sticks are analog with a circular dead zone (adjustable), buttons give a light
springy press animation and haptic click, and game rumble is sent back to the
phone that owns that controller.

**A/B/X/Y (△○✕□) are four independent controls.** Each can be moved, resized or
hidden on its own, but keep your thumb down and **slide across the cluster** —
the press follows your thumb from button to button, like rolling across a real
face-button cluster. L1/L2/R1/R2 and the pills behave the same way: sliding off
releases.

Xbox letter order is preserved: △ = Y, ○ = B, ✕ = A, □ = X.

### Themes and animation

Tap the status pill → **THEME**:

- **Neon** — default, near-black glass with a neon-green glow.
- **Midnight** — blue / violet night palette.
- **Violet** — deep purple with pink accents.
- **Crimson** — red and amber.
- **Retro** — soft graphite and ice blue.
- **Arctic** — light theme for daylight.

Switching a theme cross-fades every colour over 300 ms instead of snapping.
Buttons spring when pressed, the status dot pulses a ripple when the link
changes, the PiP window scales in, and the edit toolbar slides down from the top.
All animations use the current theme's colours. Themes, face labels, layout and
connection settings are remembered independently.

### Settings (tap the status pill)

The sheet slides in from the right and keeps showing live state while it is open.
Every change applies immediately.

- **Status card** — connection dot, transport actually in use, round-trip time,
  PC name/address, and the phone's interface list. Refreshes twice a second.
- **CONNECT / RECONNECT / DISCONNECT** — connect, rebuild the current link, or
  stop and clear the address.
- **Transport chips** — AUTO / WI-FI / BT / USB with readiness dots.
- **PC address / port**, **Scan network**, **Scan QR**, and the diagnostics line.
- **Bluetooth pairing** — adapter state, bonded PCs, discoverable mode, PAN address.
- **Theme** — six themes with a cross-fade.
- **Face button labels** — PlayStation symbols or Xbox letters.
- **Customize layout / Reset layout**.
- **Stick deadzone** — 0–40 %, default 12 %.
- **Vibration feedback** — touch clicks plus game rumble.
- **PC screen** — on/off toggle, **SMALL / LARGE** size, and per-phone **LOW / BALANCED / HIGH** stream quality.

The sheet opens without popping the keyboard, keeps every button on one line, and
closes on the scrim, the ✕ button, or Back. The footer shows the app name,
version and **© Alam showkat**.

### PC screen preview

Enable **PC screen** in settings and a small live window appears over the pad.

- **Drag** it anywhere.
- **Tap** it to toggle small / large.
- **✕** closes it (re-enable in settings).
- It shows `CONNECTING`, `LIVE` or `RECONNECTING`.

The server captures the primary monitor only while a phone is watching, then
sends JPEG frames over TCP on **control port + 1** (9877 by default). Quality is
saved per phone: **LOW** uses less bandwidth (6 fps / 320 px / JPEG 35),
**BALANCED** is the default (12 fps / 640 px / JPEG 55), and **HIGH** is sharper
(18 fps / 960 px / JPEG 75). These are the default values; the PC command-line
options tune the balanced profile. Updated PC servers honor each phone's choice;
older servers keep their fixed settings and the phone will show a notice. The
preview displays its current received FPS and selected quality. Slow phones do
not block other viewers, and their send buffers are bounded to prevent long
stale-video queues.

```powershell
lemoGamePad-server.exe --video-width 480 --video-fps 8 --video-quality 45
lemoGamePad-server.exe --no-video
lemoGamePad-server.exe --video-port 9990
```

The PC command-line options set the **BALANCED** profile. The phone's **LOW**
choice halves the frame rate and width and lowers JPEG quality; **HIGH** raises
them for a sharper image. Select LOW for Bluetooth PAN or a crowded Wi-Fi link.

### Customizing the pad

Tap the status pill → **Customize layout**. A toolbar slides in at the top:

- **Drag** any control to move it. Positions are stored as screen fractions, so
  the layout survives rotation and different resolutions, and controls stay fully
  on screen.
- **Pinch** with a second finger while holding a control to resize it (55–190 %).
- **Hold** a control for about half a second to hide it. Hidden controls show as
  faint dashed placeholders; tap one to restore it, or use **SHOW ALL**.
- **SAVE** stores the layout; **DONE** saves and leaves edit mode; **RESET**
  returns everything to default. The layout is also saved when you leave edit
  mode, background the app, or press Back.

The app keeps the screen on, hides the system bars and locks to landscape.

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| SmartScreen blocks the EXE | **More info → Run anyway**. The EXE is unsigned. |
| A UAC prompt asks for permission | Accept it — the server needs it to install ViGEmBus and open the firewall. |
| Scan finds nothing | Look at the diagnostics line: `no interface` means the phone has no link of that transport (enable tethering); `no reply` means probes left but nothing answered. If the link is up, read the server's `Firewall` line. Otherwise disable "AP/client isolation" on the router and avoid guest networks. |
| Server says `Firewall : NOT open` | Accept the UAC prompt, or run `pc\allow_firewall.ps1` as Administrator. |
| Server says `ViGEmBus : MISSING` | Let it install (do not pass `--no-install-deps`), or install from the official release page. |
| `CONNECTING · 192.168.x.x` forever | Wrong IP, firewall blocked, or the server is not running. Check the `PC IP` line. |
| Wi-Fi works for internet but not for the pad | The network isolates clients (guest Wi-Fi, "AP isolation"). Use **USB** or **Bluetooth**, or move both devices to a normal network. |
| `WI-FI found no PC — linked over USB instead` | Wi-Fi answered with nothing while the tether did answer. Use the link that works. |
| Bluetooth card says "access is off" | Tap **ALLOW NEARBY DEVICES** and accept the Android 12+ prompt. |
| Windows sees the phone but PAN won't join | Keep the phone discoverable while pairing and keep Bluetooth tethering on. Windows initiates the PAN join. |
| Bluetooth preview stutters / `LINKING…` | The PAN is ~3 Mbps. Turn **Screen preview** to **LOW** or **SMALL**, or switch preview off. |
| USB shows "no interface yet" | Enable **USB tethering** (use **OPEN TETHERING SETTINGS**) and use a data cable. |
| Windows marks the tether network "Public" | The server's rules cover every profile; if you added them manually, run `pc\allow_firewall.ps1` again. |
| Fifth phone connects but does nothing | The server is at its slot limit; raise it with `--max-phones 4`. |
| Two phones rumble each other | Fixed: rumble is routed per slot and duplicate reports are filtered. |
| "Another copy of lemoGamePad-server may already be running" | Only one instance can own UDP 9876. Close the old one (Task Manager). |
| Buttons stuck down | The server releases everything after 1.5 s without packets, and on exit. |
| Laggy / stuttery | Use 5 GHz Wi-Fi, stay in the same room, disable battery saver for the app. Typical LAN latency is 3–15 ms. |
| Screen preview stays black | Allow the TCP firewall prompt, or run `pc\allow_firewall.ps1`. The TCP port is control port + 1. |
| Game doesn't see a controller | It probably wants XInput; the virtual pad *is* XInput. For DirectInput-only games, use Steam Input. |
| Device Manager lists greyed "Xbox 360 Controller for Windows" | Ghosts from a server that was killed instead of closed. Run `pc\clean_pads.ps1` as Administrator. |
| Rumble not working | Some games only rumble on the pad they detect at launch — connect before starting the game. Run `python pc\test_rumble.py` (server closed) to prove the PC side works. |
| Windows asks to reboot after ViGEmBus | Reboot, then start the server again. The driver is registered either way. |
| Rebuilt APK won't install over the release APK | Different signing keys. Uninstall the old app first, or sign with the same key. |

---

## 9. PC command-line options

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
lemoGamePad-server.exe --video-fps 8       # stream frame rate (default 12)
lemoGamePad-server.exe --video-width 480   # stream width (default 640)
lemoGamePad-server.exe --video-quality 45  # JPEG quality 20-90 (default 55)
lemoGamePad-server.exe --list-ip           # print this PC's LAN IPs and exit
```

The console shows the PC IPs, the pairing QR, one line per connected phone
(slot, address, packets/second, battery), the idle state and the slot limit.
`Ctrl+C`, `Ctrl+Break` or closing the window quits and unplugs every pad.

**Why Xbox 360 and not a literal DualShock 3?** Windows has no built-in DS3
driver; a true DS3 needs a kernel driver (DsHidMini). Games and emulators
(RPCS3, PCSX2, Steam, RetroArch) all accept a standard pad and map the PS3
layout to it exactly as the table above. Use `--pad ds4` if a game shows
PlayStation button prompts.

---

## 10. Reconnecting

- Tap the status pill → **RECONNECT** to rebuild the socket on the current
  transport without restarting the app (useful after toggling tethering).
- If no reply from the PC arrives for 5 seconds while the link was healthy, the
  app rebinds itself automatically and shows `LINKING…`; when the PC answers the
  pill goes green again.
- **DISCONNECT** stops sending and clears the saved address. The PC releases every
  button immediately instead of waiting for the timeout.
- On the PC, a phone that goes quiet for 8 seconds is dropped and its pad is
  released. Reconnecting reuses the same virtual pad, so the game keeps the same
  player number.

---

## 11. How it works

- The app samples the touch state and sends a **28-byte UDP datagram** on every
  change, at up to ~140 Hz, plus a keepalive and a ping twice a second.
- The PC applies the newest packet to a ViGEmBus virtual pad. Out-of-order or lost
  packets are overwritten by the next one, so latency never accumulates.
- Discovery runs in three passes: broadcast on the selected link, broadcast on
  every other link, then a unicast sweep of each `/24`. The server answers with
  its hostname, pad type, a 32-bit session token and its **IPv4 address list**;
  the app picks the address on the subnet it probed. If the probe already carries
  a token the server **echoes it**, so re-pairing (or switching Wi-Fi → USB)
  reuses the same controller slot.
- Each phone is keyed by its token. Up to four `PadSlot`s exist; a slot's virtual
  pad is created on first input and reused afterwards, keeping controller order
  stable for games.
- Rumble: ViGEmBus reports the pad's motor speeds about once a second (and on
  every change). The server forwards them as type-7 packets **to that slot's
  phone only**, dropping repeats, and the phone keeps pulsing its vibration motor
  while they stay above zero.
- Screen preview: a TCP server on control port + 1 sends
  `u32 big-endian length + JPEG bytes` frames; the phone decodes them into the PiP
  view.
- QR pairing payload: `vpad://connect?h=<ip>&p=<port>&n=<hostname>`. The app also
  accepts plain `1.2.3.4:9876` text.

Protocol (little endian):

```
header (12 B): "VPAD" | ver u8 | type u8 | seq u16 | token u32
type 1 INPUT   : header + buttons u32 | lx i16 | ly i16 | rx i16 | ry i16
                 | l2 u8 | r2 u8 | battery u8 | pad u8        (28 B total)
type 2 DISCOVER: header only, broadcast to the control port
type 3 OFFER   : header + name_len u8 + name + pad_type u8
                 + ip_count u8 + ip_count * 4 bytes (IPv4 list, 1.3+)
type 5 PING    : header + client_clock u32
type 6 PONG    : echo of PING
type 7 RUMBLE  : header + small u8 + large u8
```

Button bits: Select 0, L3 1, R3 2, Start 3, Up 4, Right 5, Down 6, Left 7,
L2 8, R2 9, L1 10, R1 11, Triangle 12, Circle 13, Cross 14, Square 15, PS 16.

---

## 12. Repository layout

```
pc/
  vpad_server.py            server + ViGEm glue + dependency setup + stream + QR
  simulate_client.py        fake controller for testing without a phone
  test_multi.py             multi-phone suite: discovery, slots, rumble, reconnect
  test_video.py             screen stream (incl. multi-viewer) + QR smoke test
  test_rumble.py            end-to-end rumble check, no phone needed
  test_frozen.py            same checks against a running .exe
  build_exe.ps1             rebuilds release/lemoGamePad-server.exe
  allow_firewall.ps1        manual inbound UDP + TCP rules (all profiles)
  clean_pads.ps1            removes ghost virtual controllers (Administrator)
  requirements.txt          PC Python packages for source runs
android/
  build_apk.ps1             builds the APK and copies it to release/
  app/libs/zxing-core-3.5.3.jar   pure-Java QR decoder
  app/src/main/java/com/alamshowkat/lemogamepad/
    MainActivity.kt         lifecycle, transports, QR, PiP, edit mode
    SettingsPanel.kt        themed settings sheet + shared preference keys
    BluetoothPairing.kt     adapter state, discoverable mode, PAN address
    GamepadView.kt          themed canvas controller, edit mode, multitouch
    Theme.kt                six palettes, cross-fade, branding
    Link.kt                 UDP sender, ping/rumble, discovery, transports
    ScreenView.kt           draggable PC-screen PiP client
    ScanActivity.kt         Camera1 + ZXing QR scanner
    Protocol.kt             wire format constants + shared state
  keystore/                 your local signing key (git-ignored, never pushed)
publish.ps1                 commit + push every change to this repository
release/                    local build output (git-ignored; use GitHub Releases)
```

---

## 13. Build from source

### PC server (needs Python 3.9+)

```powershell
cd pc
python -m pip install -r requirements.txt
powershell -ExecutionPolicy Bypass -File .\build_exe.ps1
```

The script installs PyInstaller and builds a single-file EXE into
`release\lemoGamePad-server.exe`. Running `python pc\vpad_server.py` also works
and will pip-install missing packages, install ViGEmBus and open the firewall on
first use (unless you pass `--no-install-deps`).

### Android app (needs JDK 17–21 + Android SDK platform 34 / build-tools 34)

```powershell
cd android
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
```

The script finds a suitable JDK, builds `assembleRelease` and copies the APK into
`release\`. Without a keystore it produces an **unsigned** APK and tells you how
to sign it.

Toolchain used for the published build: JDK 21, Gradle 8.9, Android SDK platform
34. Nothing else is required — the app uses the plain Android framework plus one
pure-Java ZXing jar; there is no AndroidX or Play Services dependency.

### Signing the APK yourself

The official GitHub release key is kept out of git and available only to the
release workflow. For local builds, create your own separate key:

```powershell
cd android
keytool -genkeypair -v -keystore keystore\vpad.jks -alias lemogamepad `
        -keyalg RSA -keysize 2048 -validity 10000
```

Create `android\keystore\keystore.properties` (also git-ignored):

```properties
storePassword=your-store-password
keyAlias=lemogamepad
keyPassword=your-key-password
```

`build_apk.ps1` picks the key up automatically. **Keep both files private and
back them up** — Android refuses to install an update signed with a different key.
For a public release, sign with your own key and keep it on a machine you control.

### Run the tests

```powershell
python pc\test_multi.py     # multi-phone: discovery, slots, rumble routing, reconnect
python pc\test_video.py     # streamed frames (two viewers), QR PNG
python pc\test_rumble.py    # virtual pad + rumble (close the server first)
python pc\test_frozen.py    # same slot/viewer checks against a running .exe
```

### Publish a change

```powershell
powershell -ExecutionPolicy Bypass -File .\publish.ps1 -Message "describe the change"
```

The script stages everything, refuses to commit signing material, commits and
pushes to `origin`. Tags and binaries belong on the GitHub Releases page.

---

## 14. Limitations

- LAN/local-link only, plain UDP and TCP, no encryption. The QR code is a
  convenience, not an encrypted pairing protocol. Do not forward these ports to
  the internet.
- Up to **4 phones per PC** (one virtual pad each). Two phones cannot both be
  player 1, and games that only read one XInput pad (some old ports) will only see
  the first controller.
- Screen preview is one-way video only; it does not let you control the PC with
  the phone's touchscreen.
- Bluetooth and USB rely on Android tethering (PAN / RNDIS), not on a custom
  Bluetooth HID or USB gadget driver, so the phone must have tethering enabled.
- No SIXAXIS motion or pressure-sensitive face buttons (the PS3 hardware features
  that almost no PC game reads anyway).
- ViGEmBus is an archived third-party driver (its final release is 1.22.0). The
  server pins and checksums that exact official build.

---

**lemoGamePad ultimate™ © Alam showkat** · v1.3.3
