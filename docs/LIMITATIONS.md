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
  the phone's touchscreen. All quality levels target 60 fps, but the realized
  rate depends on the capture backend. On Windows and X11 the server captures
  in-process with `mss` and can reach 60 fps; on Wayland it must launch `grim`
  (or `spectacle`/`gnome-screenshot`) once per frame, which is far slower and
  may deliver only a few fps. Use the LOW quality chip to reduce the encoded
  size, or log into an X11 session, if that matters on a Wayland PC.
- USB-to-PC relies on Android USB tethering (RNDIS). The Bluetooth HID gamepad
  is a separate, server-less mode: it requires Android 9+ on the controller
  phone and a host (PC or Android) that accepts standard HID gamepads. On
  Windows it presents as a DirectInput device, not XInput, and it has no rumble,
  screen preview or multi-phone slots.
- No SIXAXIS motion or pressure-sensitive face buttons (the PS3 hardware features
  that almost no PC game reads anyway).
- ViGEmBus is an archived third-party driver (its final release is 1.22.0). The
  server pins and checksums that exact official build.
- On Linux the pad is a plain evdev device: games that only understand XInput
  (some Windows games under very old Wine/Proton builds) rely on SDL's automatic
  mapping. The Xbox 360 USB ids make that mapping work in practice, but a
  kernel-level XInput shim is not provided.

---

**lemoGamePad ultimate™ © Alam showkat** · v2.0.1
