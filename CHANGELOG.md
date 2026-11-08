# Changelog

Newest first. The reasoning behind each release is in [`docs/REVIEW.md`](docs/REVIEW.md).

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
