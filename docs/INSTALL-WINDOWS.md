# Windows install

> Part of [lemoGamePad ultimate](../README.md).

## Download

Open the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest)
and download:

| File | Size | What it is |
|---|---|---|
| `lemoGamePad-server.exe` | ~19 MB | Windows receiver. Just run it — it installs its own driver and firewall rules. |
| `SHA256SUMS.txt` | ~1 KB | Checksums for the release files. |

The binaries are attached to the release, not stored in the git history.
`release/` is a local build folder and is ignored by git.
Most `pc\...` scripts mentioned below (firewall repair, ghost-pad cleanup, tests)
ship with the **source repository**, not with the release binaries.

Linux and Android have their own guides:
[Linux install](INSTALL-LINUX.md) · [Android app guide](ANDROID.md).

> **Windows SmartScreen:** the EXE is not code-signed (a certificate costs money),
> so Windows may show *"Windows protected your PC"*. Click **More info → Run
> anyway**. Apart from downloading ViGEmBus when it is missing, the server does
> not use the internet.

---

## Install on a fresh PC (one double-click)

1. Put `lemoGamePad-server.exe` in a folder (for example `C:\lemoGamePad\`) and
   double-click it.
2. The server checks what the PC is missing and installs it:
   - **ViGEmBus driver** — the signed kernel driver that creates virtual pads.
     If it is missing, the server downloads the official Nefarius build
     (version 1.22.0), verifies its SHA-256 and runs the silent installer.
   - **Windows Firewall rules** — inbound UDP on the control port and TCP on the
     video port, for **all network profiles** (Domain, Private, Public), because
     Windows often classifies USB tethering and Bluetooth PAN as *Public*.
3. **Windows will show one or two UAC prompts** ("Do you want to allow this app
   to make changes?"). Click **Yes**. This is required to install a driver and
   change firewall rules. Nothing else is elevated.
4. When setup finishes, the console shows something like:

   ```
   lemoGamePad ultimate  -  virtual Xbox 360 gamepad
   by Alam showkat
   ----------------------------------------------------------
   Control port : 9876 (UDP)
   Screen stream: 9877 (TCP)
   Controllers  : up to 4 phones at once
   ViGEmBus     : OK (virtual gamepad driver installed)
   PC IP        : 192.168.1.23
   Firewall     : OK (UDP 9876 / TCP 9877 allowed)
   ...
   ```

   and prints a **QR code** you can scan from the phone. Leave this window open
   while you play.

   The default is 4 controller slots. Add `--max-phones 8` for up to eight
   phones; Windows games that read XInput still see at most four pads.

That is the whole PC install. There is **no Python, no pip, no driver hunting,
no firewall script** needed on a normal machine.

### What if I don't want the automatic setup?

Run it once with the escape hatch:

```powershell
lemoGamePad-server.exe --no-install-deps
```

Then install ViGEmBus yourself from
<https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0> and open the firewall
with `pc\allow_firewall.ps1` (as Administrator) if phones cannot find the PC.
The banner always tells you the real state, so you can see what is missing.

### If UAC is cancelled

Nothing breaks — the server still starts and prints the exact commands you can
run later. Install ViGEmBus manually and/or run the `netsh` lines the server
prints in an **Administrator** PowerShell (or `pc\allow_firewall.ps1` from the
source repository):

```powershell
powershell -ExecutionPolicy Bypass -File .\pc\allow_firewall.ps1
```

### Moving the EXE later

The rules the server adds are tied to the port, not to a folder, so moving the
EXE keeps working. (Rules added by `pc\allow_firewall.ps1` are tied to the old
EXE path — rerun that script after moving if you used it.)
