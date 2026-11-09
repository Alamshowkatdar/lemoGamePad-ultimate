# How it works

> Part of [lemoGamePad ultimate](../README.md).

## How it works

- The app samples the touch state and sends a **29-byte UDP datagram** on every
  change, at up to 250 Hz in TURBO (or instantly on change in ECO/STANDARD),
  plus a keepalive and a ping twice a second. The last
  byte asks for a player slot (`0` = next free, `1`…`8` = P1…P8).
- The PC applies the newest packet to a virtual pad: ViGEmBus on Windows,
  `/dev/uinput` on Linux. Out-of-order or lost packets are overwritten by the
  next one, so latency never accumulates.
- Discovery runs in three passes: broadcast on the selected link, broadcast on
  every other link, then a unicast sweep of each `/24`. The server answers with
  its hostname, pad type, a 32-bit session token and its **IPv4 address list**;
  the app picks the address on the subnet it probed. If the probe already carries
  a token the server **echoes it**, so re-pairing (or switching Wi-Fi → USB)
  reuses the same controller slot.
- Each phone is keyed by its token. Up to eight `PadSlot`s exist (eight by
  default; see `--max-phones`); a slot's virtual pad is created on first input and reused
  afterwards, keeping controller order stable for games. A phone may ask for a
  specific pad in settings: a free request is honoured, a busy one falls back to
  the next free pad, and the request is retried on later packets so the phone
  moves as soon as the wanted pad frees up. The server reports the pad it
  actually assigned in every `PONG`.
- Rumble: the virtual pad reports its motor speeds (ViGEmBus about once a second
  and on every change; evdev on each force-feedback event). The server forwards
  them as type-7 packets **to that slot's phone only**, dropping repeats, and the
  phone keeps pulsing its vibration motor while they stay above zero.
- Screen preview: a TCP server on control port + 1 sends
  `u32 big-endian length + JPEG bytes` frames; the phone decodes them into the PiP
  view.
- QR pairing payload: `vpad://connect?h=<ip>&p=<port>&n=<hostname>`. The app also
  accepts plain `1.2.3.4:9876` text.

### Browser pad transport

The browser gamepad is a second transport in front of the same slot logic. The
PC serves a page on TCP control port + 2 and accepts a WebSocket at `/ws?k=<key>`:

- The page and every asset require the per-run pairing key in `?k=`; missing or
  wrong keys return `401`. WebSocket upgrades are additionally rejected with
  `403` when the `Origin` header does not match the `Host`.
- Input frames are JSON text messages. The browser sends
  `{"t":"i","b":<button bits>,"lx":…,"ly":…,"rx":…,"ry":…,"l2":…,"r2":…,"w":<slot>}`;
  the field names mirror the UDP packet (button bits, signed 16-bit axes,
  0–255 triggers, `w` = requested pad). The server clamps every value.
- Ping: `{"t":"p","c":<client clock>}` → `{"t":"q","c":<echo>,"n":<assigned pad>}`,
  which also gives the page an RTT figure.
- Slot changes are pushed as `{"t":"s","n":<assigned pad>}`.
- Pad swap: the page sends `{"t":"swap","w":<pad>}` to request another player's
  pad, receives `{"t":"ask","id":<request>,"f":<from pad>}` when someone asks
  for its pad, answers `{"t":"vote","id":<request>,"ok":1|0}`, and gets
  `{"t":"sr","id":<request>,"c":<code>,"n":<pad>}` with the result. `w:0`
  withdraws a pending request and closes the other player's card. Result codes
  are `1 ACCEPTED`, `2 DECLINED`, `3 TIMEOUT`, `4 UNAVAILABLE`,
  `5 CANCELLED`. A free pad moves immediately without an ask; duplicate asks
  and results are ignored by id.
- Rumble: `{"t":"r","s":<small>,"l":<large>}` replaces the type-7 UDP packet for
  that peer; it is routed to the browser tab instead of an address.
- The server assigns each WebSocket a fresh token and feeds it through the same
  `_submit_input` path as UDP, so sequence handling, coalescing, idle release
  and the eight-slot limit behave identically.

Protocol (little endian):

```
header (12 B): "VPAD" | ver u8 | type u8 | seq u16 | token u32
type 1 INPUT   : header + buttons u32 | lx i16 | ly i16 | rx i16 | ry i16
                 | l2 u8 | r2 u8 | battery u8 | pad u8
                 | want_pad u8 (0 = auto, 1..8 = P1..P8, 1.5+)   (29 B total)
type 2 DISCOVER: header only, broadcast to the control port
type 3 OFFER   : header + name_len u8 + name + pad_type u8
                 + ip_count u8 + ip_count * 4 bytes (IPv4 list, 1.3+)
type 5 PING    : header + client_clock u32
type 6 PONG    : echo of PING + loss_permille u16 (1.4+)
                 + assigned_pad u8 (1.5+)
type 7 RUMBLE  : header + small u8 + large u8
type 8 SLOT_REQUEST : header + target pad u8 (1..8)            (1.6.9+)
type 9 SLOT_ASK     : header + request_id u32 + requester pad u8
type 10 SLOT_VOTE   : header + request_id u32 + accept u8
type 11 SLOT_RESULT : header + request_id u32 + result u8 + pad u8
```

Types 8–11 are additive. `VERSION` stays 1: an older app ignores the new types,
and a request to one simply times out. Result codes are `1 ACCEPTED`,
`2 DECLINED`, `3 TIMEOUT`, `4 UNAVAILABLE`, `5 CANCELLED`.

Button bits: Select 0, L3 1, R3 2, Start 3, Up 4, Right 5, Down 6, Left 7,
L2 8, R2 9, L1 10, R1 11, Triangle 12, Circle 13, Cross 14, Square 15, PS 16.

---
