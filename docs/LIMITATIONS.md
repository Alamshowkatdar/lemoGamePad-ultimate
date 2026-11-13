# Limitations

> Part of [lemoGamePad ultimate](../README.md).

## Limitations

- LAN/local-link only, plain UDP and TCP, no encryption. The QR code is a
  convenience, not an encrypted pairing protocol. Do not forward these ports to
  the internet.
- The browser pad is served over plain HTTP with a per-run key, and its rumble
  reaches the page only where the browser supports the Vibration API (Android
  yes, iOS Safari no). Its themes, layouts and screen-preview position are
  per-browser `localStorage` state, not shared with the Android app or other
  devices. The PC screen preview still depends on the server's screen stream
  (`--no-video` disables it), and only a few viewers can share that stream.
- Up to **8 phones per PC** (one virtual pad each; `--max-phones`, default 8).
  Two phones cannot both be player 1. A requested slot matches the controller
  number the game sees, because every lower pad is created first; an occupied
  slot needs the other player's consent, so a swap can also be declined. Windows
  games that read only XInput still
  see at most four pads (a Windows API limit); with the default `x360` backend
  pads 5–8 are DualShock 4/HID devices for DirectInput/Raw Input games and
  emulators. `--pad ds4` makes all eight DirectInput devices; Linux can use all
  eight Xbox 360 pads.
- Screen preview is one-way video only; it does not let you control the PC with
  the phone's touchscreen.
- Bluetooth PAN and USB-to-PC rely on Android tethering (PAN / RNDIS). Phone-to-phone
  Bluetooth HID is a separate mode; it requires Android 9+ on the controller phone
  and a game that supports external controllers on the host phone.
- No SIXAXIS motion or pressure-sensitive face buttons (the PS3 hardware features
  that almost no PC game reads anyway).
- ViGEmBus is an archived third-party driver (its final release is 1.22.0). The
  server pins and checksums that exact official build.
- On Linux the pad is a plain evdev device: games that only understand XInput
  (some Windows games under very old Wine/Proton builds) rely on SDL's automatic
  mapping. The Xbox 360 USB ids make that mapping work in practice, but a
  kernel-level XInput shim is not provided.

---

**lemoGamePad ultimate™ © Alam showkat** · v1.6.12
