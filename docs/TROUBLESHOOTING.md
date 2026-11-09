# Troubleshooting

> Part of [lemoGamePad ultimate](../README.md).

## Troubleshooting

| Symptom | Fix |
|---|---|
| SmartScreen blocks the EXE | **More info → Run anyway**. The EXE is unsigned. |
| A UAC prompt asks for permission | Accept it — the server needs it to install ViGEmBus and open the firewall. |
| Scan finds nothing | Look at the diagnostics line: `no interface` means the phone has no link of that transport (enable tethering); `no reply` means probes left but nothing answered. If the link is up, read the server's `Firewall` line. Otherwise disable "AP/client isolation" on the router and avoid guest networks. |
| Server says `Firewall : NOT open` | Accept the UAC prompt, or run `pc\allow_firewall.ps1` as Administrator. |
| Server says `ViGEmBus : MISSING` | Let it install (do not pass `--no-install-deps`), or install from the official release page. |
| `CONNECTING · 192.168.x.x` forever | Wrong IP, firewall blocked, or the server is not running. Check the `PC IP` line. |
| Wi-Fi works for internet but not for the pad | The network isolates clients (guest Wi-Fi, "AP isolation"). Use **USB** or **Bluetooth**, or move both devices to a normal network. |
| `WI-FI found no PC — linked over USB instead` | Wi-Fi answered with nothing while the tether did answer. Use the link that works. |
| Bluetooth card says "access is off" | Tap **ALLOW NEARBY DEVICES** and accept the Android 12+ prompt. |
| Windows sees the phone but PAN won't join | Keep the phone discoverable while pairing and keep Bluetooth tethering on. Windows initiates the PAN join. |
| Bluetooth preview stutters / `LINKING…` | The PAN is ~3 Mbps. Turn **Screen preview** to **LOW** or **SMALL**, or switch preview off. |
| USB shows "no interface yet" | Enable **USB tethering** (use **OPEN TETHERING SETTINGS**) and use a data cable. |
| Windows marks the tether network "Public" | The server's rules cover every profile; if you added them manually, run `pc\allow_firewall.ps1` again. |
| Another phone connects but does nothing | The server is at its limit (default 8); check the console line `ignored <ip>: all N controller slots are in use`. Raise `--max-phones` if it was lowered. |
| Eight phones connect but the game shows only four | Windows' XInput API exposes at most four pads, so XInput-only games cannot show eight. Pads 5–8 are DualShock 4/HID devices for DirectInput/Raw Input games and emulators; `--pad ds4` makes all eight DirectInput devices. On Linux all eight are normal Xbox 360 pads. |
| A phone always becomes the wrong player number | Open settings → **PLAYER SLOT** and pick **P1**…**P8**. A busy pad is not stolen: the phone uses the next free one and moves to the chosen pad as soon as it frees up. |
| Two phones rumble each other | Fixed: rumble is routed per slot and duplicate reports are filtered. |
| "Another copy of lemoGamePad-server may already be running" | Only one instance can own UDP 9876. Close the old one (Task Manager). |
| Buttons stuck down | The server releases everything after 1.5 s without packets, and on exit. |
| Laggy / stuttery | Use 5 GHz Wi-Fi, stay in the same room, disable battery saver for the app, and keep the phone's Wi-Fi awake. Open settings: the sparkline shows whether latency is flat or spiking, and `% uplink loss` means packets are being dropped before the PC. Typical LAN latency is 3–15 ms. Try **Input rate → TURBO** only if the game samples the pad on its own clock; otherwise **STANDARD** is better for battery. |
| Screen preview stays black | Allow the TCP firewall prompt, or run `pc\allow_firewall.ps1`. The TCP port is control port + 1. |
| Browser pad page does not load | Check the full `Web pad` URL from the console (it includes `?k=`), confirm the device is on the same Wi-Fi, and allow TCP control port + 2 (default `9878`) in the firewall. |
| Browser pad says `Pairing link is not valid` | The server restarted (new random key) or the `?k=` part was dropped. Copy the current URL or rescan `lemoGamePad-web-qr-*.png`. |
| Browser pad loads but the dot stays red | The WebSocket is blocked. Guest / AP-isolation Wi-Fi blocks device-to-device traffic; use a normal network, Bluetooth PAN or USB tethering. |
| Browser pad connects but no controller appears | All `--max-phones` slots are busy, or the page has not sent input yet — touch the pad or press a key. Disconnect an idle phone or raise the limit (maximum eight). |
| Browser pad has no rumble | iOS Safari has no Vibration API; rumble is skipped there. On Android, allow the browser to vibrate. |
| Browser pad controls feel delayed | Prefer 5 GHz Wi-Fi and close background downloads. The page sends at up to 60 Hz and the server coalesces bursts. |
| Screen preview is black or says capture is unavailable on Linux | Native Wayland blocks `mss`. Install `grim`, `spectacle` or `gnome-screenshot` (see Linux notes), or log in with an X11 session. |
| Game doesn't see a controller | It probably wants XInput; the virtual pad *is* XInput. For DirectInput-only games, use Steam Input. |
| Device Manager lists greyed "Xbox 360 Controller for Windows" | Ghosts from a server that was killed instead of closed. Run `pc\clean_pads.ps1` as Administrator. |
| Rumble not working | Some games only rumble on the pad they detect at launch — connect before starting the game. Run `python pc\test_rumble.py` (server closed) to prove the PC side works. |
| Windows asks to reboot after ViGEmBus | Reboot, then start the server again. The driver is registered either way. |
| Rebuilt APK won't install over the release APK | Different signing keys. Uninstall the old app first, or sign with the same key. |

---
