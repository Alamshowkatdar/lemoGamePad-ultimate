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

The PC link has two transports. Both use the same unencrypted local protocol and
are supported by the same APK:

| Mode | Best for | Phone setting | PC setting | Typical latency |
|---|---|---|---|---|
| **Wi-Fi** | Normal home network, best range and speed | Wi-Fi on, same network as the PC | Nothing | 3–15 ms |
| **USB** | Lowest latency, charging while playing, no radio interference | **USB tethering** | Nothing | ~3 ms |

> **They are network links, not custom drivers.** Android's USB tethering creates
> a private network interface (`rndis*`, `ncm*`, `usb*`). The app sends the same
> UDP packets over that interface, so the PC needs no extra software.
>
> **Bluetooth is not a PC transport any more.** The phone advertises a standard
> HID gamepad that a PC or Android host reads directly — no server, no
> tethering, no "Join a Personal Area Network". See
> [Bluetooth HID gamepad](#bluetooth-hid-gamepad).

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

### 4b. USB (fastest, and charges the phone)

1. Connect the phone to the PC with a **data** USB cable (not a charge-only one).
2. On the phone enable **USB tethering**:
   **Settings → Network → Hotspot & tethering → USB tethering**.
   The app's **USB TETHERING SETTINGS** button jumps there.
3. In the app: status pill → **USB** chip → **Scan network** → **Connect**.
4. Windows sees a new network adapter (`rndis*` / `ncm*`). No driver install is
   needed on Windows 10/11.

USB is usually the lowest-latency option and keeps the battery topped up. If
Windows marks that adapter as a **Public** network, the firewall rules created by
the server already cover it.

### Which chip should I use?

- **AUTO** (default) probes every link and connects over whichever answers.
- **WI-FI / USB** start with that link, and *widen* to the other if it gets no
  reply. If that happens the app tells you, for example
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
folder you launched it from, in case you want to show it on another screen. It
also prints a **guest** QR (`lemoGamePad-guest-qr-<ip>.png`) with an `a=1` flag:
scanning that one switches the phone to AUTO and the PC assigns the next free
pad, so extra players can join without picking a slot.

The QR payload is `vpad://connect?h=<ip>&p=<port>&n=<hostname>` (guest codes add
`&a=1`); plain `1.2.3.4:9876` text is accepted too.

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
- **Party lock** — **P1 is the host seat**. The first phone takes P1; if P1
  swaps with someone else, that player becomes host and gets **LOCK PARTY** /
  **UNLOCK PARTY** in the LINK card. If P1 leaves, the next player moves up and
  inherits the role. While the party is locked, other phones cannot move or
  swap seats; their buttons, sticks and triggers keep working, and a new guest
  can still claim a free pad. Non-hosts see their slot chips dimmed and a short
  "Party locked" line.
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

### Bluetooth HID gamepad

The phone can also be a standard Bluetooth HID gamepad for a **PC or Android
phone**, with no server and no tethering. Android 9+ is required on the
controller phone because that is where Android added the HID device profile.

1. Open the app and **BLUETOOTH GAMEPAD** → **MAKE DISCOVERABLE** (or accept the
   system prompt when it asks). Opening the app alone does **not** advertise the
   pad or connect to anything.
2. On the host: open Bluetooth settings, pair **Lemo GamePad**.
3. Back in the app, open **BLUETOOTH GAMEPAD**, wait for the bonded host to
   appear, then tap **SELECT HOST** on that row. The card changes to
   `● Connected — <host> is receiving input`.

After a restart, or after a Bluetooth drop, pick the host again with
**SELECT HOST**. The app deliberately never reconnects by itself, and calls or
audio keep their normal Bluetooth profiles.

The Bluetooth card also has **ALLOW NEARBY DEVICES** (Android 12+),
**BLUETOOTH SETTINGS** and **DISCONNECT**. Pairing is a one-time step; connecting
is an explicit choice each time.

Keep the app in the foreground while you play: Android releases the HID profile
when the app process stops. Leaving the app sends one neutral report first, so a
stuck button cannot survive backgrounding.

The HID report carries 16 buttons, an 8-way hat switch, four signed 8-bit stick
axes, and analog L2/R2:

| On screen | HID control | Typical host mapping |
|---|---|---|
| ✕ | Button 1 | A |
| ○ | Button 2 | B |
| □ | Button 3 | X |
| △ | Button 4 | Y |
| L1 / R1 | Buttons 5 / 6 | LB / RB |
| L2 / R2 | Buttons 7 / 8, axes Z / Rz | LT / RT (analog) |
| SELECT / START | Buttons 9 / 10 | Back / Start |
| L3 / R3 | Buttons 11 / 12 | Stick click |
| D-pad | Hat switch | D-pad |

Sticks go to X/Y (left) and Rx/Ry (right); L2 and R2 also map to the Z and Rz
axes (0–255). The **PS** button is intentionally not exposed over HID, because
hosts treat that button as *Home* and would leave your game.

On Windows the pad is a DirectInput HID controller, not XInput. Games that only
read XInput still need the Wi-Fi/USB server link; this mode also has no rumble,
screen preview or player slots. It is independent of the PC link, so both can be
configured at once.

### Themes and animation

Tap the status pill → **APPEARANCE**. The picker is a two-column grid of
palettes, and every tile shows the theme itself: its background gradient plus
both accent colours, so you can see the look before you pick it.

| Theme | Look |
|---|---|
| **Midnight Black** | Deep blue glass with a quiet violet key light. |
| **Cyberpunk** | Neon pink and cyan over a midnight grid. |
| **Arctic** | Frosted light glass, icy blue and soft daylight. |
| **AMOLED** | True black with one warm accent; OLED panels can switch black pixels fully off. |
| **Racing Red** | Carbon fibre, telemetry red and a fast horizon. |
| **Xbox** | Xbox green, dark carbon and a soft central light. |
| **PlayStation** | PlayStation blue, white type and a deep navy field. |
| **Minimal** | Paper white, one blue accent and no distractions. |
| **Retro Arcade** | Cabinet purple, coin-op yellow and CRT scanlines. |
| **Stealth** | Graphite, low light and no wasted colour. |

Switching a theme cross-fades every colour over 300 ms with a smootherstep
curve, so there is no visible kick when the fade starts or ends. Buttons spring
under your thumb (a light, slightly under-damped spring that settles in about
120 ms), the status dot ripples only when the connection state actually changes,
the PiP window scales in, and the edit toolbar slides down from the top.
Each palette also has its own one-shot press ripple: Midnight Black and
PlayStation draw `wave` arcs, Cyberpunk `shock`s outward with four spokes,
Arctic sends three `ripple` rings, AMOLED snaps a hairline `void` ring, Racing
Red `burst`s eight rays, Xbox blooms a soft `halo`, Minimal expands a precise
underline `line`, Retro Arcade steps a box in quantised chunks, and Stealth
uses the default `pulse` ring. The ripple only advances while a press is
settling, so an idle pad still renders nothing.
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
- **Transport chips** — AUTO / WI-FI / USB with readiness dots.
- **PC address / port**, **Scan network**, **Scan QR**, and the diagnostics line.
- **Bluetooth gamepad** — the app never connects on launch. **SELECT HOST** lists bonded devices and connects only the one you pick; **DISCONNECT** removes the HID node. The card also opens Bluetooth settings and can make the phone discoverable. Android 12+ asks for **Nearby devices** when needed.
- **Motion & aim** — **Aim touchpad** replaces the right stick with a relative pad. Its **Mouse aim** switch chooses between BGMI-style swipes and relative game-mouse aim (finger speed turns the camera; stopping stops the aim). **Tilt steering** feeds left X for racing games; **Gyro aim** feeds right X/Y for shooters and still adds to either touchpad mode. **Revert left/right aim** and **Revert up/down aim** flip each gyro axis independently. Tilt sensitivity, dead zone, curve and **CALIBRATE CENTRE** tune steering; separate gyro and touchpad sensitivity sliders tune aim. **AIM CALIBRATION** opens a ten-target reticle drill; see below. **Tilt steering** and **Gyro aim** start `OFF` on a fresh install; turn on only the one you need.
- **Appearance** — ten palettes in a two-column picker with preview-before-apply and an **Animated buttons** toggle.
- **Face button labels** — PlayStation symbols or Xbox letters.
- **Layouts** — save several named arrangements of the single Gamepad layout,
  switch between them, delete one, or restore the default. Enabling or disabling
  the swipe pad keeps the previous right-side placement. **SHARE QR** renders the
  active layout as a scannable code; **SCAN QR** imports a layout from another
  phone or from the browser pad's **SHARE CODE**. Importing an existing name
  replaces that saved layout.
- **Game profiles** — save a named snapshot of aim/motion settings, haptics,
  input rate, face labels and the current layout, then switch by tapping a name.
  No foreground-game detection is involved.
- **Customize layout** — drag, resize and hide controls.
- **Stick deadzone** — 0–40 %, default 12 %.
- **Stick curve** — 0–100 %, linear at 0; higher values keep small corrections
  near the centre.
- **Vibration feedback** — touch clicks plus game rumble, with a 0–100 %
  strength slider. Game rumble is now a continuous vibration that follows both
  motor levels; it stops as soon as the game stops, and a 500 ms refresh keeps
  long effects alive if a packet is lost.
- **Vibration from games** — separate on/off switch in the same card. Turn it
  off to silence force feedback while keeping touch clicks. Game profiles
  remember this choice.
- **Input rate** — ECO (battery), STANDARD (default, send on change) or TURBO
  (continuous 250 Hz).
- **PC screen** — on/off toggle, **SMALL / LARGE** size, per-phone **LOW / BALANCED / HIGH** stream quality, and an optional **Adaptive quality** switch.

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
sends JPEG frames over TCP on **control port + 1** (9877 by default). The stream
is tuned smoothness-first: all three quality choices target **60 fps** on a
fixed cadence, and only sharpness changes. Defaults are **LOW** 300 px / JPEG 30,
**BALANCED** 480 px / JPEG 42 (the default), and **HIGH** 600 px / JPEG 52.
The PC command-line options tune the balanced profile and `--video-fps` is
capped at 60. Updated PC servers honor each phone's choice;
older servers keep their fixed settings and the phone will show a notice. The
preview displays its current received FPS and selected quality. Slow phones do
not block other viewers, and their send buffers are bounded to prevent long
stale-video queues. The Android viewer drops complete frames queued behind a slow
decode and reuses decoded bitmaps instead of allocating a new one per frame.

**Adaptive quality** is off by default. When enabled, it samples the received
FPS plus RTT/jitter/uplink-loss every 2 s. Two bad windows lower sharpness one
step; six good windows raise it one step. It waits 20 s after any automatic
change and pauses for 45 s after a manual LOW/BALANCED/HIGH tap, so it cannot
fight you or oscillate. The preview badge always shows the active quality and
FPS; a throttled toast explains the first change in a while. All three profiles
still target 60 fps, so automatic changes never trade frame rate for detail.

```powershell
lemoGamePad-server.exe --video-width 480 --video-fps 60 --video-quality 45
lemoGamePad-server.exe --no-video
lemoGamePad-server.exe --video-port 9990
```

Those options set the **BALANCED** profile; **LOW** and **HIGH** keep the same
cadence with less or more sharpness.

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

### Game profiles

Open settings → **GAME PROFILES** to keep complete control setups side by side:

- **SAVE CURRENT…** names a snapshot of the current aim, touchpad, tilt, gyro,
  haptics, input rate, face labels and layout. Names are limited to 24 characters
  and saving over an existing one asks first.
- Tap a saved name to apply it after confirmation. Aim/motion values, the layout
  and the active profile highlight update together.
- **DELETE** removes a saved profile and leaves the current pad untouched.
- **CLEAR** forgets which profile is active without deleting any saved entries.

Profiles are manual only. The app never detects which game is in the foreground
and never switches on its own. Host, port, transport, player slot, theme and
screen-preview settings are intentionally not part of a profile.

### Aim calibration

Open settings → **MOTION & AIM** → **AIM CALIBRATION** for a quick reticle drill.
Drag to move the reticle with your touchpad sensitivity, and it stays where you
leave it — there is no spring-back. Tap to fire; a target only scores if the
reticle is inside it when you shoot. Gyro aim, when enabled, nudges the same
persistent reticle, and the **Revert** switches flip each gyro axis. Ten targets
complete the run.

While the overlay is open, every UDP and HID packet is held neutral, so the
drill cannot move a game in the background. **DONE** or Back closes it, restores
the screen preview if it was visible, and shows the score. The summary reports
shot accuracy and on-target steadiness, and suggests lower sensitivity if you
keep passing targets or the relevant **Revert** switch if one axis consistently
drifts.

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
