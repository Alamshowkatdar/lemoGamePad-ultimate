# Browser pad

> Part of [lemoGamePad ultimate](../README.md).

The PC server can host its own gamepad page. Open it in any modern browser on
the same Wi-Fi and the phone, tablet or laptop becomes a controller — no
Android app, no account and no internet connection.

The page is served by the PC, uses only local files and talks to the server
over a WebSocket on the local network. It never loads a CDN script, web font,
analytics service or any other remote resource. It mirrors most of the Android
pad: nine browser themes, PS/Xbox face labels, player slots, layout editing and
saved arrangements, plus latency, status and the PC screen preview. The Android
app has ten themes; the two newer Android palettes are not in the browser build.

**CONTROL CENTRE** opens from the pad's settings button. A pinned QUICK dock and
category rail jump straight to CONNECT, SLOT, STICK, THEME or SCREEN, and each
category remembers where you scrolled. On a wide window the cards sit in two
columns; **DENSE** hides the longer notes when you want to see more at once.
The sheet picks up the active theme's gradient and accent glow, active tabs and
theme swatches animate, and each palette has its own press ripple. The MOTION
switch turns all of that off for players who prefer a still pad.

## Open it

1. Start `lemoGamePad-server` (`vpad_server.py` when running from source).
2. Look for the **Web pad** line in the console:
   ```
   Web pad      : http://192.168.1.20:9878/?k=AbCdEf12
   ```
3. Open that URL on the device. Typing the URL works, and the server also draws
   a **scannable browser QR** right in the console: point any phone camera at it
   and the page opens. A `lemoGamePad-web-qr-<ip>.png` copy is saved next to the
   server for printing or sharing. The server also prints a **guest** QR with
   `&auto=1` (`lemoGamePad-web-guest-qr-<ip>.png`) that opens the page in AUTO
   and takes the next free pad without changing the tab's stored slot.

The default port is **TCP 9878** (the control port + 2). The `k` value is a
random pairing key generated for each run. Keep the full URL: without the key
the page and the WebSocket both answer `401`.

> Run the server on the PC that should receive the input, and make sure the
> device is on the same Wi-Fi. The phone does not need to reach the internet at
> all.

## iPhone and Safari

There is no native iOS app: the Android APK cannot run on iOS, and the receiver
still has to run on a Windows or Linux PC. An iPhone joins through this page in
Safari, using the same local WebSocket as every other browser.

- **iOS 13 or newer is recommended.** It has Pointer Events, which the pad uses
  for multi-touch sticks, buttons, layout editing and the draggable preview.
  Older iOS versions get a touch-event fallback for the pad itself, but the
  preview cannot be dragged.
- **Add to Home Screen.** Tap Safari's Share button, then **Add to Home Screen**.
  The page declares `apple-mobile-web-app-capable`, so it opens as a standalone
  pad without Safari's bars. This is the iPhone replacement for **FULL SCREEN**,
  which iOS Safari does not allow (the setting shows **N/A**).
- **Make the pairing key stable before you save the shortcut.** A Home Screen
  icon keeps the original URL, including `?k=`, but the key is random for every
  server run by default. Start the server with
  `--web-key-file lemoGamePad-web.key` (or a fixed `--web-key`) and scan the QR
  once; the saved shortcut then keeps working after a restart. See
  [Command-line options](COMMAND-LINE.md).
- **Safe areas are respected.** The pad canvas avoids the notch and the home
  indicator, and the screen preview is clamped inside the same safe area. Use
  landscape for larger controls.
- **No gyro or tilt aim in the browser.** Motion sensors are Android-app
  features. iOS also requires `DeviceOrientationEvent.requestPermission()` from
  a tap and a secure (HTTPS) context; this page is plain LAN `http://`, so that
  path is deliberately not offered.
- **Rumble becomes a sound cue on iOS.** Safari has no Vibration API, so with
  **SOUND CUE FALLBACK** on (the default) a quiet tone follows the rumble
  strength instead. It only plays where vibration is unavailable, so Android
  browsers keep using the real motor. Turn it off in CONTROL CENTRE →
  CONTROLS if you want silence.
- **Sleeping and waking.** iOS can freeze the socket when the phone locks or the
  tab is backgrounded. The pad releases every button when hidden and reconnects
  automatically on wake (or when Wi-Fi returns); tap **RECONNECT** if the status
  still says CONNECTING. Keeping the tab in the foreground is still fastest.
- **Keep the LAN URL.** A public HTTPS page cannot open the server's local
  `ws://` socket, and this server has no TLS mode. Always use the `http://`
  address printed in the console.

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
  **AUTO** (next free pad) or **P1**–**P8**. A free pad is assigned immediately;
  an occupied pad now sends the other player an **Accept / Decline** card
  instead of stealing it. The requester shows a pending chip and line until the
  answer arrives, then a short result toast. The game keeps running on both
  sides while the card is open, and the sheet shows which pad is actually in
  use. The number matches the controller the game sees, because the server
  creates every lower pad first; a move to an unused slot creates that pad too.
  The first connected controller is the **host** and gets a **LOCK PARTY**
  button here. While the party is locked, guests cannot move or swap seats,
  but they keep sending input and a new guest can still take a free pad. The
  lock is released if the host disconnects.
- **Appearance** — the nine themes (Neon, Midnight, Violet, Crimson, Solar,
  Retro, Arctic, Mono, AMOLED) with a cross-fade when switching, PlayStation or
  Xbox face-button labels, an **Animated buttons** toggle and the **FULL
  SCREEN** toggle. **AMOLED** uses a true-black background so OLED panels can
  switch those pixels off; LCDs see no battery saving.
- **Controls** — stick deadzone, stick curve, vibration on/off, a separate
  **Vibration from games** switch, strength, and the input-rate mode.
- **Layouts** — customize the pad and save named arrangements (see below).
- **PC screen** — an in-page preview of the PC screen (see below).

Every theme also has its own one-shot press animation, drawn on the pad and
echoed by the settings buttons: Neon pulses, Midnight waves, Violet halos,
Crimson shocks, Solar bursts, Retro steps, Arctic ripples, Mono draws a
line and AMOLED snaps a single hairline `void` ring. **APPEARANCE → Animated
buttons** turns them off; it starts off when the
browser reports `prefers-reduced-motion: reduce`. Motion only advances while a
press is settling, so an idle pad still draws nothing.

Settings are stored in the browser's `localStorage` on that device. They are
not shared with other browsers, other devices or the Android app.

## Full screen

**APPEARANCE → FULL SCREEN** hides the browser bars and gives the pad the whole
display. The button follows the browser's real state, so leaving full screen
with `Esc` or a system gesture updates it too. If the browser refuses the
request the label returns to **OFF**; where the Fullscreen API is missing
(notably iPhone Safari) it shows **N/A**. On iPhone, add the page to the Home
Screen to run it without browser bars.

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

The ~60 Hz ceiling is on the browser side: JavaScript timers share the main
thread, pointer events arrive at the display refresh rate, and background tabs
are throttled. It is not a server limit — since 1.6.6 the server applies a
browser frame within a millisecond or two of receiving it, and a phone can
still stream at its full 250 Hz over UDP.

## Layouts

The pad can be rearranged on the browser itself:

1. Open settings → **LAYOUTS** → **CUSTOMIZE** (or **SAVE AS** / the edit bar).
2. Drag a control to move it, pinch with two fingers to resize it, or hold it
   for about half a second to hide it. Hidden controls show as dashed
   placeholders while editing.
3. **DONE** saves the current arrangement. **RESET** restores the built-in
   positions. **SHOW ALL** brings every hidden control back. **SAVE AS** stores
   a named copy; use **DEFAULT** in the layout list to return to the built-in
   arrangement. **SHARE CODE** copies a `LMGB-LAYOUT-1` text code; **IMPORT
   CODE** pastes one back in.

Editing while a named layout is active updates that saved entry in place. Saved
layouts are per-browser and stored in `localStorage`, never uploaded. They can
now be handed to the Android app as a QR (or pasted from one) and to another
browser via the share code or a `#layout=` link; those transfers are manual.

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
usually ignore it. Both motor levels are combined and sustained for as long as
the game keeps rumbling; a zero level cancels it at once. The strength slider
scales both game rumble and the small haptic tick when touching a control. Use
**Vibration from games** to silence force feedback while keeping button clicks;
it also silences the iOS sound-cue fallback.

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
  port-forward TCP 9878** (or UDP 9876 / TCP 9877) on your router.
  This is a LAN feature, not an internet service.
- The key is printed in the console and stored in the QR PNG. Treat it like a
  temporary password; restart the server to rotate it.

See [SECURITY.md](../SECURITY.md) for the full threat model.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Page does not load | Check the URL's IP and port, confirm the device is on the same Wi-Fi, and allow TCP 9878 through the firewall. If the link stopped working after the PC joined a different network, the IP changed: the console prints `Network changed` with the new `Web pad` URL (and rewrites the QR images) within about ten seconds, so use that one or rescan the code. |
| `Pairing link is not valid` | The server restarted (new random key) or the `?k=` part was dropped. Copy the current URL or rescan the web QR. |
| Page loads but the dot stays red | The WebSocket is blocked. Some guest / AP-isolation Wi-Fi networks block device-to-device traffic; use a normal network or USB tethering. |
| No controller appears | All `--max-phones` slots are busy. Disconnect an idle phone or raise the limit (maximum eight). |
| P1–P8 request is ignored | Another device already has that pad, and that player has not accepted the swap (or declined / did not answer within 10 s). The page stays on its current pad; a free requested slot is honoured in order, so P5 is the game's fifth controller. If the host has locked the party, only the host can change seats until it unlocks or disconnects. |
| No Accept/Decline card appears | The card only appears for the player who owns the requested pad. Make sure their tab is connected and in the foreground; the PC resends the ask for 10 s, then reports a timeout to the requester. |
| Controls feel delayed | Prefer 5 GHz Wi-Fi and close background downloads. Try TURBO, and keep the tab in the foreground. |
| Theme or layout did not persist | Private/incognito windows may block `localStorage`; the session still works. |
| No vibration on iPhone | Safari has no Vibration API; the sound-cue fallback plays a quiet tone instead. On Android, check that the browser is allowed to vibrate. |
| Home Screen shortcut shows `401` after a server restart | The saved URL contains the old per-run key. Start the server with `--web-key-file` (or a fixed `--web-key`), then add the page to Home Screen again from the new QR. |
| Pad stays CONNECTING after the iPhone wakes | It reconnects on wake and when Wi-Fi returns; otherwise tap **RECONNECT**, or disable Auto-Lock while playing. |
| iPhone opens the pad in Safari instead of full screen | iOS Safari has no Fullscreen API. Add the page to the Home Screen; it then opens as a standalone pad. |
| No gyro aim on iPhone | Gyro is Android-app-only. iOS sensor access needs an HTTPS secure context, which this LAN server does not provide. |
| Preview or dragging misbehaves on an old iPhone | iOS 13+ is the supported floor. Older versions get a basic touch pad, but Pointer Events for preview dragging and iOS 13+ layout behaviour are not available. |
| Preview says `SCREEN OFF` | The server's screen stream is disabled (`--no-video`), still starting, or unavailable on this platform. The rest of the pad keeps working. |
| Preview keeps retrying | Only a few viewers can share the stream; close other phones/tabs or restart the screen stream. |
| "waiting for controller..." in the console | The page connected but has not sent input yet; touch the pad or press a key. |

## Not in the browser

The Android-only paths stay out of the page: Bluetooth HID gamepad, USB
tethering, gyro aim and tilt steering require Android APIs or sensor permissions
a browser does not expose here. Use the Android app for those. The browser also
cannot create a virtual controller by itself — the PC server still does that.
