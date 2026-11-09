# Browser pad

> Part of [lemoGamePad ultimate](../README.md).

The PC server can host its own gamepad page. Open it in any modern browser on
the same Wi-Fi and the phone, tablet or laptop becomes a controller — no
Android app, no account and no internet connection.

The page is served by the PC, uses only local files and talks to the server
over a WebSocket on the local network. It never loads a CDN script, web font,
analytics service or any other remote resource. It mirrors the Android pad:
the same eight themes, PS/Xbox face labels, player slots, layout editing and
saved arrangements, plus latency, status and the PC screen preview.

## Open it

1. Start `lemoGamePad-server` (`vpad_server.py` when running from source).
2. Look for the **Web pad** line in the console:
   ```
   Web pad      : http://192.168.1.20:9878/?k=AbCdEf12
   ```
3. Open that URL on the device. Typing the URL works, and the server also draws
   a **scannable browser QR** right in the console: point any phone camera at it
   and the page opens. A `lemoGamePad-web-qr-<ip>.png` copy is saved next to the
   server for printing or sharing.

The default port is **TCP 9878** (the control port + 2). The `k` value is a
random pairing key generated for each run. Keep the full URL: without the key
the page and the WebSocket both answer `401`.

> Run the server on the PC that should receive the input, and make sure the
> device is on the same Wi-Fi. The phone does not need to reach the internet at
> all.

## The pad

The layout matches the Android app and supports multiple fingers at once. Move
the left stick to L3, the right stick to R3, or roll a finger between face
buttons without lifting it.

| Control | Touch | Keyboard |
| --- | --- | --- |
| Left stick (L3) | drag the left circle | `W` `A` `S` `D` |
| Right stick (R3) | drag the right circle | `I` `J` `K` `L` |
| D-pad | tap or slide on the arrows | arrow keys |
| Triangle / Circle / Cross / Square | tap the face buttons | numpad `8` / `6` / `2` / `4` |
| L1 / R1 | tap the bumpers | `Q` / `E` |
| L2 / R2 | tap the triggers | `1` / `2` (or numpad `1`) |
| Start | tap `START` | `Enter` |
| Select | tap `SELECT` | `Shift` |
| PS | tap `PS` | `` ` `` |
| L3 / R3 | tap the small `L3` / `R3` pills | `Z` / `C` |

The status pill at the top shows the PC name, the assigned pad and the current
round-trip time. Tap it to open the settings sheet. **KEYBOARD MAP** in the
sheet shows the same list on the device.

## Settings

Tap the status pill to open **CONTROL CENTRE**:

- **Status** — connected/offline state, assigned pad, latency graph with
  average/min/max/jitter, and CONNECT / RECONNECT / DISCONNECT.
- **Connection** — the server name, `--max-phones` limit and **PLAYER SLOT**:
  **AUTO** (next free pad) or **P1**–**P8**. A busy pad falls back to the next
  free one and is retried until it frees up; the sheet shows which pad is
  actually in use.
- **Appearance** — the eight themes (Neon, Midnight, Violet, Crimson, Solar,
  Retro, Arctic, Mono) with a cross-fade when switching, and PlayStation or
  Xbox face-button labels.
- **Controls** — stick deadzone, stick curve, vibration on/off and strength,
  and the input-rate mode.
- **Layouts** — customize the pad and save named arrangements (see below).
- **PC screen** — an in-page preview of the PC screen (see below).

Settings are stored in the browser's `localStorage` on that device. They are
not shared with other browsers, other devices or the Android app.

## Input rate

The browser has no background socket and cannot match the phone's 250 Hz
TURBO cadence, so the modes mean:

| Mode | Browser behaviour |
| --- | --- |
| **ECO** | Send only when something changes, plus a 1 s heartbeat. |
| **STANDARD** | Send only when something changes, plus a 250 ms heartbeat. |
| **TURBO** | Send continuously at roughly 60 Hz (one update per animation frame). |

TURBO is the lowest-latency choice on a fast local network; ECO is the lightest
on battery. The server keeps the latest state either way, so a change is never
waiting for the next heartbeat.

## Layouts

The pad can be rearranged on the browser itself:

1. Open settings → **LAYOUTS** → **CUSTOMIZE** (or **SAVE AS** / the edit bar).
2. Drag a control to move it, pinch with two fingers to resize it, or hold it
   for about half a second to hide it. Hidden controls show as dashed
   placeholders while editing.
3. **DONE** saves the current arrangement. **RESET** restores the built-in
   positions. **SHOW ALL** brings every hidden control back. **SAVE AS** stores
   a named copy; use **DEFAULT** in the layout list to return to the built-in
   arrangement.

Editing while a named layout is active updates that saved entry in place. Saved
layouts are per-browser. They are stored in `localStorage`, never uploaded, and
are separate from the Android app's layouts.

## PC screen preview

Settings → **PC SCREEN** turns on a small live preview of the PC screen. Choose
**SMALL** or **LARGE**, and **LOW**, **BALANCED** or **HIGH** stream quality.
Drag the preview to move it, tap it to switch size, or press **×** to close it
(re-enable it in settings). The preview is draggable and stays out of the way of
the pad.

The preview reuses the same JPEG stream as the Android app and requires the
server's screen stream (enabled by default; `--no-video` disables both). The
relay keeps only the newest frame, so a slow tab drops stale images instead of
building a delay. On a LAN, **BALANCED** is a good default; **LOW** uses less
bandwidth and **HIGH** is sharper. The live FPS and stream width appear as a
tooltip on the preview.

## Rumble

The server forwards force feedback to the browser through the
[Vibration API](https://developer.mozilla.org/docs/Web/API/Vibration_API) when
the browser exposes it. Android Chrome and most Android browsers support it;
iOS Safari does not, so rumble is simply skipped there. Desktop browsers
usually ignore it. The strength slider scales both game rumble and the small
haptic tick when touching a control.

## Turning it on or off

| Flag | Effect |
| --- | --- |
| *(default)* | Browser pad enabled on control port + 2. |
| `--web-port 8080` | Use a different TCP port. |
| `--web-key mysecret` | Use a fixed pairing key instead of a random one. |
| `--no-web` | Do not start the browser pad at all. |
| `--no-video` | Keep the pad but disable the PC screen stream (and preview). |

The first run on Windows adds an inbound firewall rule for the web port, the
same way it does for the phone ports. If the rule is missing, the page will not
load; the console prints the exact `netsh` command to fix it.

## Security

- The page and both WebSockets (`/ws` for control, `/screen` for the preview)
  require the per-run key in the URL.
- WebSocket upgrades are rejected when the browser's `Origin` does not match the
  page's host, so a malicious website open in another tab cannot silently drive
  the pad or read the screen preview.
- The page is served with a restrictive Content-Security-Policy and can only
  load its own files. Static assets contain no secrets and load without the key
  so that relative `<link>` / `<script>` requests work; control still requires
  the key.
- The screen relay connects only to the local video server on loopback. It
  cannot be pointed at another host.
- The server binds the normal LAN interfaces, like the phone protocol. **Do not
  port-forward TCP 9878** (or UDP 9876 / TCP 9877 / TCP 9878) on your router.
  This is a LAN feature, not an internet service.
- The key is printed in the console and stored in the QR PNG. Treat it like a
  temporary password; restart the server to rotate it.

See [SECURITY.md](../SECURITY.md) for the full threat model.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Page does not load | Check the URL's IP and port, confirm the device is on the same Wi-Fi, and allow TCP 9878 through the firewall. |
| `Pairing link is not valid` | The server restarted (new random key) or the `?k=` part was dropped. Copy the current URL or rescan the web QR. |
| Page loads but the dot stays red | The WebSocket is blocked. Some guest / AP-isolation Wi-Fi networks block device-to-device traffic; use a normal network or USB tethering. |
| No controller appears | All `--max-phones` slots are busy. Disconnect an idle phone or raise the limit (maximum eight). |
| P1–P8 request is ignored | Another device already has that pad; the page falls back to the next free pad until it frees up. |
| Controls feel delayed | Prefer 5 GHz Wi-Fi and close background downloads. Try TURBO, and keep the tab in the foreground. |
| Theme or layout did not persist | Private/incognito windows may block `localStorage`; the session still works. |
| No vibration | iOS Safari has no Vibration API. On Android, check that the browser is allowed to vibrate. |
| Preview says `SCREEN OFF` | The server's screen stream is disabled (`--no-video`), still starting, or unavailable on this platform. The rest of the pad keeps working. |
| Preview keeps retrying | Only a few viewers can share the stream; close other phones/tabs or restart the screen stream. |
| "waiting for controller..." in the console | The page connected but has not sent input yet; touch the pad or press a key. |

## Not in the browser

The Android-only transports stay out of the page: Bluetooth HID,
phone-to-phone HID, Bluetooth PAN and USB tethering require Android APIs a
browser does not expose. Use the Android app for those links. The browser also
cannot create a virtual controller by itself — the PC server still does that.
