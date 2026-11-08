# Limitations

> Part of [lemoGamePad ultimate](../README.md).

## Limitations

- LAN/local-link only, plain UDP and TCP, no encryption. The QR code is a
  convenience, not an encrypted pairing protocol. Do not forward these ports to
  the internet.
- Up to **8 phones per PC** (one virtual pad each; `--max-phones`, default 4).
  Two phones cannot both be player 1. Windows games that read only XInput still
  see at most four pads; use `--pad ds4`, DirectInput/Raw Input or Linux for
  more.
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

**lemoGamePad ultimate™ © Alam showkat** · v1.5.0
