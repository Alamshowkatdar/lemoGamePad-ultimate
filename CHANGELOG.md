# Changelog

Newest first. The reasoning behind each release is in [`docs/REVIEW.md`](docs/REVIEW.md).

## 1.6.2

- **Scannable browser QR** — the console now draws the browser pad URL as a QR
  next to the phone pairing QR, so any camera can open the page without typing
  the IP, port and key. The `lemoGamePad-web-qr-<ip>.png` copy is still saved
  for printing or sharing.

## 1.6.1

- **Browser gamepad** — the PC now hosts a gamepad page on TCP `9878`
  (control port + 2) so any phone, tablet or laptop browser on the same Wi-Fi
  can play. It is a full PS3-style touch pad with multi-touch sticks, D-pad,
  face buttons, shoulders and triggers, plus a keyboard mapping (`WASD`/`IJKL`,
  arrow keys, numpad face buttons). Rumble reaches the browser through the
  Vibration API where supported. The page is served entirely by the PC — no
  internet, CDN, fonts or accounts. Each run gets a random pairing key that the
  page and WebSocket require, WebSocket upgrades are `Origin`-checked, and the
  assets are bundled in the EXE/binary. New flags: `--web-port`, `--web-key`,
  `--no-web`. See [Browser pad](docs/WEB.md). This is a local-network feature:
  never expose the port to the internet.

## 1.6.0

- **Eight phones works out of the box** — `--max-phones` now defaults to `8`
  instead of `4`, so the fifth phone is no longer silently ignored. On Windows
  the first four pads stay Xbox 360/XInput; pads 5–8 automatically use the
  DualShock 4/HID backend, because Windows' XInput API itself exposes only four
  controllers. XInput-only games still see four pads; DirectInput/Raw Input
  games, emulators and Linux can use all eight. The console banner now names
  the mixed backends and reminds you of the XInput limit.
- **Multiple layouts** — the pad can now remember several arrangements. In
  settings → **LAYOUTS**, use **SAVE AS…** (also available as **SAVE AS** in the
  edit toolbar) to name the current control positions, sizes and hidden
  controls; tap a saved name to switch instantly, or **DELETE** to remove one.
  **DEFAULT** restores the built-in arrangement. Editing while a saved layout is
  active updates that layout in place, so the pad and its saved copy stay in
  sync. The existing *Customize layout* flow and the live layout are unchanged
  for anyone who never opens the new card.

## 1.5.0

- **Up to eight phones** — `--max-phones` now accepts `1`…`8` (default stays
  `4`). Windows XInput games still see four pads; slots 5-8 are useful with
  `--pad ds4`, DirectInput/Raw Input titles and on Linux.
- **Choose your player slot** — the Android settings sheet now has
  **PLAYER SLOT**: AUTO (next free pad, unchanged) or P1…P8. The request travels
  with the input packets, so a connected phone moves to the wanted pad without
  reconnecting; a busy pad falls back to the next free one and is retried until
  it frees up. The PC reports the pad it actually assigned, shown in the status
  pill and beside the selector. The wire format stays compatible: the new
  trailing byte is ignored by older servers, and older apps keep working.
- **PC hardening** — a slow viewer can no longer corrupt the video stream with a
  half-written frame; one silent video client no longer stalls other viewers;
  a controller token cannot be moved to another machine's address while its
  real owner is still active; and the log, IP cache and 1 ms timer no longer
  block or linger on the receive path.
- **Safer elevation** — the ViGEm installer is re-hashed immediately before the
  UAC prompt, and the firewall helper runs from System32 through an encoded
  command instead of a writable `.cmd` file under `%LOCALAPPDATA%`.
- **Honest firewall status** — an unreadable `netsh` result is reported as
  unknown instead of "OK".
- **Cleaner ghost pads** — `clean_pads.ps1` now removes only root-enumerated
  ViGEm devices, never a real controller, and asks before acting.
- **Android lifecycle fixes** — the screen preview closes its socket when the
  window is dismissed or the activity pauses; the QR scanner releases the
  camera and decoder thread on destroy and reuses its preview buffer; rumble
  stops when the app is backgrounded; the phone's identity token comes from
  `SecureRandom` and is never adopted from a discovery reply; and a rebind can
  no longer leak sockets.

## 1.4.0

- **Steadier latency** — the PC now collapses a burst of queued packets into one
  update at the newest position instead of replaying every old one, discards
  out-of-order packets, and never lets the console window stall the input path.
  The phone sends the moment the pad changes instead of polling every 7 ms,
  holds a low-latency Wi-Fi lock while connected, and marks its packets
  low-delay.
- **See the link, not just guess at it** — settings show round-trip average,
  min, max, jitter and uplink loss, plus a sparkline of the last ~30 seconds.
  The status pill also shows loss and jitter.
- **Input rate modes** — **ECO**, **STANDARD** (default, wake on change) and
  **TURBO** (continuous 250 Hz) in settings.
- **Stick precision curve** — calm small corrections without reducing full
  deflection.
- **Vibration strength** — scale game rumble from 0 to 100 %.
- Wire-compatible with 1.3 apps and servers.

## 1.3.7.2

- **Linux face buttons fixed** — square/triangle (X/Y) are no longer swapped.
  The Linux pad now uses the right evdev codes for the Xbox 360 identity and,
  with `--pad ds4`, the DualShock 4 identity too. Download this release if you
  installed the original 1.3.7 Linux server.

## 1.3.7

- **Linux receiver** — the PC server now runs on Linux, creating a real
  Xbox 360 (or DualShock 4) evdev gamepad through the kernel's `/dev/uinput`,
  the Linux counterpart of ViGEmBus. Force-feedback rumble works through the
  standard evdev API, and Windows is unchanged.
- **Release binaries** — the release now also ships `lemoGamePad-server` for
  Linux, next to the Windows `.exe` and the signed APK. The Linux binary needs
  the one-time `pc/linux/install-linux.sh` setup (shipped with the release and
  the source repository).

## 1.3.6

- **Two new themes** — **Solar** (warm copper and amber) and **Mono**
  (high-contrast graphite) — in a redesigned two-column picker where every tile
  previews its own colours.
- **Smoother pad** — springy button presses, a smootherstep theme cross-fade,
  and frame-rate-independent animation.
- **Better phone-to-phone gamepad** — standard HID button mapping, an honest
  start (it tells you when Bluetooth is off instead of silently doing nothing),
  a live *broadcasting / connected* line, and reports only sent when something
  changes.
- **Fixes** — the status dot no longer pulses forever, the settings sheet no
  longer rescans every network interface twice a second, and a second thumb can
  no longer steal a held face button.

The full list, with reasoning, is in [`docs/REVIEW.md`](docs/REVIEW.md).

---
