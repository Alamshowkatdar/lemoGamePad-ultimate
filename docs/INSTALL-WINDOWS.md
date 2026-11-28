# Windows install

> Part of [lemoGamePad ultimate](../README.md).

## Download

Open the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest)
and download:

| File | Size | What it is |
|---|---|---|
| `lemoGamePad-app.exe` | ~20 MB | Desktop control panel. Starts the receiver and shows status, QR codes, settings and log. |
| `lemoGamePad-server.exe` | ~2 MB | Native Rust console receiver. Prints the same status and QR codes as the panel. |
| `allow_firewall.ps1` | ~1 KB | One-time helper to open the inbound ports (run as Administrator). |
| `SHA256SUMS.txt` | ~1 KB | Checksums for the release files. |

The binaries are attached to the release, not stored in the git history.
`release/` is a local build folder and is ignored by git.
Most `pc\...` scripts mentioned below (firewall repair, ghost-pad cleanup, tests)
ship with the **source repository**, not with the release binaries.

Linux and Android have their own guides:
[Linux install](INSTALL-LINUX.md) · [Android app guide](ANDROID.md).

> **Windows SmartScreen:** the EXE is not code-signed (a certificate costs money),
> so Windows may show *"Windows protected your PC"*. Click **More info → Run
> anyway**. The receiver never downloads or installs anything; it only listens
> on your LAN.

---

## Install on a fresh PC

1. Put the release files in a folder (for example `C:\lemoGamePad\`). Only
   `lemoGamePad-app.exe` is needed: it contains the receiver, the firewall
   helper and the signed **ViGEmBus 1.22.0** installer, so setup works offline.
2. To set up manually, run `allow_firewall.ps1` as Administrator:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\allow_firewall.ps1
   ```

   It opens inbound UDP on the control port and TCP on the video and
   browser-pad ports for all network profiles (Domain, Private, Public), because
   Windows often classifies USB tethering as *Public*.

   The desktop panel can do both steps for you: open **Settings → Install
   dependencies (asks UAC)**, confirm, and approve the UAC prompt. It adds the
   firewall rules and silently installs the bundled signed ViGEmBus driver, with
   no download. Nothing is installed without that confirmation and approval.
3. Double-click `lemoGamePad-app.exe` for the desktop panel, or
   `lemoGamePad-server.exe` for the console banner and QR. The panel supervises
   the native receiver and starts it automatically.

The console shows something like:

```
lemoGamePad ultimate  -  virtual Xbox 360 gamepad
by Alam showkat
----------------------------------------------------------
Control port : 9876 (UDP)
Screen stream: 9877 (TCP)
Browser pad  : 9878 (TCP, Wi-Fi only)
Controllers  : up to 8 phones at once
Pads 5-8     : DS4/DirectInput (XInput only allows four pads)
ViGEmBus     : OK (virtual gamepad driver installed)
PC IP        : 192.168.1.23
Web pad      : http://192.168.1.23:9878/?k=ABC123
```

The console also prints a **QR code** you can scan from the phone. The desktop
app shows the same details in **Status** and the QR in **Pairing / QR**.

The default is 8 controller slots. Windows games that read XInput still see at
most four pads; pads 5–8 automatically use the DualShock 4/HID backend so
DirectInput/Raw Input games and emulators can use all eight. Use
`--max-phones 4` if you want the old limit.

If ViGEmBus is still missing after setup, the banner prints the pinned download
link as a fallback. The receiver still runs, but no virtual pad is created.
The `--no-install-deps` flag suppresses the one-time setup reminder in the log;
it does not change the network protocol.

### Moving the EXE later

The rules the server adds are tied to the port, not to a folder, so moving the
EXE keeps working. (Rules added by `pc\allow_firewall.ps1` are tied to the old
EXE path — rerun that script after moving if you used it.)
