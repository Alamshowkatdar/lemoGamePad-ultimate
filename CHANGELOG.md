# Changelog

Newest first. The reasoning behind each release is in [`docs/REVIEW.md`](docs/REVIEW.md).

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
