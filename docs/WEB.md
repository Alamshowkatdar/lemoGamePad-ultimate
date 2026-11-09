# Browser pad

> Part of [lemoGamePad ultimate](../README.md).

The PC server can host its own gamepad page. Open it in any modern browser on
the same Wi-Fi and the phone, tablet or laptop becomes a controller — no
Android app, no account and no internet connection.

The page is served by the PC, uses only local files and talks to the server
over a WebSocket on the local network. It never loads a CDN script, web font,
analytics service or any other remote resource.

## Open it

1. Start `lemoGamePad-server` (`vpad_server.py` when running from source).
2. Look for the **Web pad** line in the console:
   ```
   Web pad      : http://192.168.1.20:9878/?k=AbCdEf12
   ```
3. Open that URL on the device. Typing the URL works; the server also saves a
   `lemoGamePad-web-qr-<ip>.png` next to itself, which you can scan.

The default port is **TCP 9878** (the control port + 2). The `k` value is a
random pairing key generated for each run. Keep the full URL: without the key
the page and the WebSocket both answer `401`.

> Run the server on the PC that should receive the input, and make sure the
> device is on the same Wi-Fi / Bluetooth PAN / USB network. The phone does not
> need to reach the internet at all.

## Controls

The layout is a PS3-style pad and supports multiple fingers at once.

| Control | Touch | Keyboard |
| --- | --- | --- |
| Left stick (L3) | drag the left circle | `W` `A` `S` `D` |
| Right stick (R3) | drag the right circle | `I` `J` `K` `L` |
| D-pad | tap the arrows | arrow keys |
| Triangle / Circle / Cross / Square | tap the face buttons | numpad `8` / `6` / `2` / `4` |
| L1 / R1 | tap the bumpers | `Q` / `E` |
| L2 / R2 | tap the triggers | `1` / `2` (or numpad `1`) |
| Start | tap `START` | `Enter` |
| Select | tap `SEL` | `Shift` |
| PS | tap `PS` | `` ` `` |

Open **Keys** in the top bar to see the same list in the page.

Every browser connection gets a virtual pad automatically, up to the server's
`--max-phones` limit (eight by default). To ask for a specific pad, open **Keys**
and change **Player slot** from *Auto* to *P1*–*P8*; a busy slot falls back to
the next free pad and is retried.

## Rumble

The server forwards force feedback to the browser through the
[Vibration API](https://developer.mozilla.org/docs/Web/API/Vibration_API) when
the browser exposes it. Android Chrome and most Android browsers support it;
iOS Safari does not, so rumble is simply skipped there. Desktop browsers
usually ignore it unless a gamepad with haptics is present.

## Turning it on or off

| Flag | Effect |
| --- | --- |
| *(default)* | Browser pad enabled on control port + 2. |
| `--web-port 8080` | Use a different TCP port. |
| `--web-key mysecret` | Use a fixed pairing key instead of a random one. |
| `--no-web` | Do not start the browser pad at all. |

The first run on Windows adds an inbound firewall rule for the web port, the
same way it does for the phone ports. If the rule is missing, the page will not
load; the console prints the exact `netsh` command to fix it.

## Security

- The page and the WebSocket require the per-run key in the URL.
- WebSocket upgrades are rejected when the browser's `Origin` does not match the
  page's host, so a malicious website open in another tab cannot silently drive
  the pad.
- The page is served with a restrictive Content-Security-Policy and can only
  load its own files.
- The server binds the normal LAN interfaces, like the phone protocol. **Do not
  port-forward TCP 9878** (or UDP 9876 / TCP 9877) on your router. This is a
  LAN feature, not an internet service.
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
| Controls feel delayed | Prefer 5 GHz Wi-Fi and close background downloads. The page sends at up to 60 Hz and the server coalesces bursts. |
| No vibration | iOS Safari has no Vibration API. On Android, check that the browser is allowed to vibrate. |
| "waiting for controller..." in the console | The page connected but has not sent input yet; touch the pad or press a key. |

The browser does not currently show the PC screen preview. Use the Android app
for the in-pad screen view.
