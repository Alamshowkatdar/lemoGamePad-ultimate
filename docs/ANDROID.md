# Android app guide

> Part of [lemoGamePad ultimate](../README.md).

## Install the Android app

1. Download the APK attached to the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest) and copy it to the phone (USB, cloud drive, email —
   anything).
2. Tap it. Android will warn about installing from an unknown source; allow it
   for the app you used to open the file (Files, Chrome, …).
3. Open **lemoGamePad ultimate** and turn the phone **landscape**.

The app is distributed outside the Play Store. Official releases use the same
signing key going forward. Version 1.3.2 uses a new key, so uninstall 1.3.1
before installing it. Locally built APKs use your own key; see
[Signing the APK yourself](BUILDING.md#signing-the-apk-yourself).

---

## Choose how the phone talks to the PC

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
[Troubleshooting](TROUBLESHOOTING.md).

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

## Pair the app with the PC

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

## Up to eight phones on one PC

Install the APK on the other phones and pair them the same way.

- Each phone gets **its own virtual controller**. With the default **AUTO**
  setting, XInput slot order is the order the phones first connected.
- **Choose your player** — settings → **PLAYER SLOT** lets a phone ask for
  **P1**…**P8**. A free pad is assigned immediately. A pad another player is
  using sends that player a small **Accept / Decline** card instead of taking
  the pad; the requester sees a pending slot and line until the answer arrives.
  The status pill and the slot line show the pad the PC actually assigned.
- **Swaps ask first** — the other player's card sits at the top of the pad with
  no backdrop, and play continues on both phones while they decide. **Accept**
  trades both pads; **Decline**, no answer within 10 s, or a player leaving
  leaves everyone where they were. Free pads and two players asking for each
  other at once apply immediately. A declined pair is not asked again for 2 s.
- The chosen number matches the game's controller number: the server creates
  every pad from P1 up to the requested slot first, so one phone asking for
  **P5** still appears as the fifth controller rather than the first. A move to
  a slot that was never used creates that pad before input continues.
- The default is **8 phones**; lower it with `--max-phones 1` … `--max-phones 8`.
- A phone beyond the limit is ignored and the console logs
  `ignored <ip>: all N controller slots are in use`.
- Windows games that read **XInput** still see at most four pads (a Windows API
  limit). With the default `x360` backend, pads 5–8 automatically use
  DualShock 4/HID, which DirectInput/Raw Input games and emulators can use;
  `--pad ds4` makes all eight DirectInput devices. Linux can use all eight
  Xbox 360 pads.
- Each phone keeps its slot (and its in-game player number) for as long as the
  server runs. A phone that drops off and reconnects returns as the same player.
- Rumble is routed to the correct phone only.

The phone now keeps **one identity for life**: its token is stored in app
preferences, so restarting the app does not look like a new phone and does not
consume another controller slot.

---

## Using the pad

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

### Phone-to-phone (Bluetooth HID)

You do not need a PC for this mode. One phone acts as the **controller**, the
other is the **game host**. The controller phone advertises itself as a standard
Bluetooth HID gamepad, so the host sees a real controller and no helper app or
Accessibility service is involved.

1. **Controller phone** (Android 9+): open the app → status pill →
   **PHONE-TO-PHONE GAMEPAD** → **START GAMEPAD**. Accept the *Nearby devices*
   prompt if Android asks.
2. **Host phone**: **Settings → Bluetooth**, pair with **Lemo GamePad**.
3. The card on the controller phone switches from
   `○ Broadcasting as “Lemo GamePad”` to `● Connected`.
4. Start the game on the host and play.

The app only sends a report when a control actually changes, and it parks the
gamepad when you leave the app (sending one neutral report first), so a stuck
button cannot survive backgrounding. Keep the controller app in the foreground
while you play.

The HID report carries 16 buttons, four signed 8-bit stick axes, and analog L2/R2:

| On screen | HID button | Typical Android mapping |
|---|---|---|
| ✕ | 1 | A |
| ○ | 2 | B |
| □ | 3 | X |
| △ | 4 | Y |
| L1 / R1 | 5 / 6 | LB / RB |
| L2 / R2 | 7 / 8 | LT / RT (also analog axis) |
| SELECT / START | 9 / 10 | Back / Start |
| L3 / R3 | 11 / 12 | Stick click |
| D-pad | 13–16 | Up / Down / Left / Right |

Sticks go to X/Y (left) and Rx/Ry (right); L2 and R2 also map to the Z and Rz
axes (0–255). The **PS** button is intentionally not exposed over HID, because
host phones treat that button as *Home* and would leave your game. This mode is
independent of the PC link: you can have both configured, and starting one does
not stop the other.

### Themes and animation

Tap the status pill → **APPEARANCE**. The picker is a two-column grid of
palettes, and every tile shows the theme itself: its background gradient plus
both accent colours, so you can see the look before you pick it.

| Theme | Look |
|---|---|
| **Neon** | Default, near-black glass with a neon-green glow. |
| **Midnight** | Blue / violet night palette. |
| **Violet** | Deep purple with pink accents. |
| **Crimson** | Red and amber. |
| **Solar** | Warm sunset: copper background with amber and coral controls. |
| **Retro** | Soft graphite and ice blue. |
| **Arctic** | Light theme for daylight. |
| **Mono** | High-contrast graphite: pure greys with a white key light. |

Switching a theme cross-fades every colour over 300 ms with a smootherstep
curve, so there is no visible kick when the fade starts or ends. Buttons spring
under your thumb (a light, slightly under-damped spring that settles in about
120 ms), the status dot ripples only when the connection state actually changes,
the PiP window scales in, and the edit toolbar slides down from the top.
Each palette also has its own one-shot press ripple, so the same tap feels
different in every theme: Neon pulses a ring, Midnight sends a wave, Violet
blooms a halo, Crimson shocks, Solar bursts, Retro steps in quantised chunks,
Arctic ripples three rings and Mono draws a precise underline. The ripple only
advances while a press is settling, so an idle pad still renders nothing.
Animations are frame-rate independent: they look the same on a 60 Hz and a
120 Hz screen. All animations use the current theme's colours. Themes, face
labels, layout and connection settings are remembered independently.

### Settings (tap the status pill)

The sheet slides in from the right and keeps showing live state while it is open.
Every change applies immediately.

- **Status card** — connection dot, transport actually in use, round-trip time,
  PC name/address, and the phone's interface list. Once connected it also draws
  a latency sparkline with average / min / max / jitter and uplink loss.
  Refreshes twice a second.
- **CONNECT / RECONNECT / DISCONNECT** — connect, rebuild the current link, or
  stop and clear the address.
- **Transport chips** — AUTO / WI-FI / BT / USB with readiness dots.
- **PC address / port**, **Scan network**, **Scan QR**, and the diagnostics line.
- **Bluetooth pairing** — adapter state, bonded PCs, discoverable mode, PAN address.
- **Phone-to-phone Bluetooth HID** — pair this phone as a standard gamepad with another Android phone (Android 9+), with a live *broadcasting / connected* line. No helper app or Accessibility permission is needed on the game host.
- **Appearance** — eight palettes in a two-column picker with colour previews, a cross-fade and an **Animated buttons** toggle.
- **Face button labels** — PlayStation symbols or Xbox letters.
- **Layouts** — save several named arrangements, switch between them, delete
  one, or restore the default.
- **Customize layout** — drag, resize and hide controls.
- **Stick deadzone** — 0–40 %, default 12 %.
- **Stick curve** — 0–100 %, linear at 0; higher values keep small corrections
  near the centre.
- **Vibration feedback** — touch clicks plus game rumble, with a 0–100 %
  strength slider.
- **Input rate** — ECO (battery), STANDARD (default, send on change) or TURBO
  (continuous 250 Hz).
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
saved per phone: **LOW** uses less bandwidth (15 fps / 400 px / JPEG 30),
**BALANCED** is the default (20 fps / 800 px / JPEG 50), and **HIGH** is sharper
(25 fps / 1200 px / JPEG 70). These are default values; the PC command-line
options tune the balanced profile. Updated PC servers honor each phone's choice;
older servers keep their fixed settings and the phone will show a notice. The
preview displays its current received FPS and selected quality. Slow phones do
not block other viewers, and their send buffers are bounded to prevent long
stale-video queues. The Android viewer also drops complete frames queued behind a slow decode.

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
- **SAVE AS** names the current arrangement; **DONE** saves and leaves edit mode;
  **RESET** restores the built-in layout. The layout is also saved when you leave
  edit mode, background the app, or press Back.

### Saving several layouts

Open settings → **LAYOUTS**. The card lists **DEFAULT** and every layout you
have named:

- **SAVE AS…** stores the current positions, sizes and hidden controls under a
  name (up to 24 characters). Saving over an existing name replaces it, and the
  dialog warns you first.
- Tap a saved name to switch to it immediately. The active name is shown under
  the list and is highlighted.
- **DELETE** removes a saved name after a confirmation. The pad keeps the
  arrangement it is showing; it just stops being tied to that name.
- **DEFAULT** restores the built-in arrangement and forgets the active name.
- **CUSTOMIZE** opens edit mode. If a saved layout is active, edits update that
  layout in place, so switching away and back keeps your changes.

The live layout is still stored separately, so updating from an older version
keeps the arrangement you already had.

The app keeps the screen on, hides the system bars and locks to landscape.

---

## Reconnecting

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
