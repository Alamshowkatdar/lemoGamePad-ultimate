# Building and repository layout

> Part of [lemoGamePad ultimate](../README.md).

## Repository layout

```
pc/
  vpad_server.py            legacy Python reference (not shipped; protocol source of truth)
  linux_pad.py              legacy Python Linux backend reference
  desktop_gui.py            earlier Python/Tk panel (protocol/UI reference only)
  simulate_client.py        fake controller for testing without a phone
  test_multi.py             multi-phone suite: discovery, slots, rumble, reconnect
  test_latency.py           receive path, coalescing, ordering, PONG stats
  test_linux_pad.py         Linux pad + force-feedback test (no phone/game needed)
  test_video.py             screen stream (incl. multi-viewer) + QR smoke test
  test_capture.py           screen-capture backend selection (fake Wayland tools)
  test_web.py               browser pad: HTTP, WebSocket, pairing, slots, rumble
  test_rumble.py            end-to-end rumble check through Windows XInput
  test_frozen.py            same checks against a running .exe
  web_ui.py                 local HTTP + WebSocket server for the browser pad
  web/
    index.html              browser pad markup
    style.css               responsive pad styling
    app.js                  touch/keyboard input, WebSocket client, rumble
    favicon.svg             page icon
rust/
  src/main.rs               CLI entrypoint and shutdown handling
  src/gui/main.rs           native eframe/egui desktop panel entrypoint
  src/server.rs             UDP receiver, slots, swaps, party lock, status
  src/pad/                  native ViGEmBus and uinput backends
  src/video.rs              PC screen stream
  src/web.rs                browser pad HTTP + WebSocket server
pc/
  build_rust.sh             copies both Cargo binaries into pc/dist
  build_exe.ps1             builds Rust receiver + Rust panel on Windows
  build_linux.sh            builds Rust receiver + Rust panel on Linux
  linux/
    install-linux.sh        one-shot setup: packages, uinput, venv, firewall, launcher
                            (also attached to the release for the standalone binary)
    install-uinput.sh       compatibility alias for install-linux.sh
    run.sh                  start the native receiver from a source checkout
    70-lemogamepad.rules    udev rule for /dev/uinput permissions
  allow_firewall.ps1        manual inbound UDP + TCP rules (all profiles)
  clean_pads.ps1            removes ghost virtual controllers (Administrator)
  requirements.txt          PC Python packages for source runs
android/
  build_apk.ps1             builds the APK and copies it to release/
  app/libs/zxing-core-3.5.3.jar   pure-Java QR decoder
  app/src/main/java/com/alamshowkat/lemogamepad/
    MainActivity.kt         lifecycle, transports, QR, PiP, edit mode
    SettingsPanel.kt        themed settings sheet + shared preference keys
    BluetoothHidSupport.kt  adapter state, permissions, discoverable mode
    BluetoothHidGamepad.kt  standard HID gamepad for explicitly picked PC/Android hosts
    GamepadView.kt          themed canvas controller, edit mode, multitouch
    Theme.kt                ten palettes, cross-fade, branding
    Link.kt                 UDP sender, ping/rumble, discovery, transports
    ScreenView.kt           draggable PC-screen PiP client
    ScanActivity.kt         Camera1 + ZXing QR scanner
    LayoutStore.kt          single-layout persistence and saved presets
    LayoutDialogs.kt        themed rename/delete layout dialogs
    LatencyGraph.kt         settings-sheet RTT sparkline
    MotionInput.kt          tilt steering + gyro aim sensor input
    ThemePreview.kt         animated theme picker tile
    Protocol.kt             wire format constants + shared state
  keystore/                 your local signing key (git-ignored, never pushed)
docs/
  REVIEW.md                 what changed in 1.3.6-2.2 and why (bug list + notes)
  INSTALL-WINDOWS.md        Windows install and manual setup
  INSTALL-LINUX.md          Linux release binary, source setup, capture notes
  ANDROID.md                app install, transports, pairing, usage, settings
  TROUBLESHOOTING.md        symptom -> fix table
  COMMAND-LINE.md           every server flag
  PROTOCOL.md               architecture and wire protocol
  BUILDING.md               source builds, signing, tests
  RELEASING.md              version bump, tag, release checklist
  LIMITATIONS.md            deliberate non-goals
CHANGELOG.md                what changed in each release
LICENSE                     MIT license
SECURITY.md                 threat model and private vulnerability reporting
CONTRIBUTING.md             build/test workflow, code style, PR rules
THIRD_PARTY_LICENSES.md     licenses of bundled dependencies
.github/                    CI, release workflow, issue/PR templates, dependabot
publish.ps1                 commit + push every change to this repository
release/                    local build output (git-ignored; use GitHub Releases)
```

---

## Build from source

### PC receiver and desktop panel (both Rust)

```powershell
cd pc
powershell -ExecutionPolicy Bypass -File .\build_exe.ps1
```

The script builds both binaries with Cargo and copies `dist/lemoGamePad-server.exe`
and `dist/lemoGamePad-app.exe` into `release\`. Use
`rust\target\release\lemoGamePad-server.exe` directly when you only need the
console receiver. The panel finds the receiver binary next to itself.

On Linux:

```bash
./pc/build_linux.sh
```

It produces `dist/lemoGamePad-server` and `dist/lemoGamePad-app`. The receiver
needs the one-time `pc/linux/install-linux.sh` setup at runtime. The panel needs
X11 client libraries at runtime; `install-linux.sh` installs them and builds both
binaries from a source checkout. Cargo embeds the browser pad assets, so no
`--add-data` step is needed. The old Python files remain in the repository only
as a protocol reference and are not part of the released receiver or panel.

### Android app (needs JDK 17–21 + Android SDK platform 34 / build-tools 34)

```powershell
cd android
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
```

The script finds a suitable JDK, builds `assembleRelease` and copies the APK into
`release\`. Without a keystore it produces an **unsigned** APK and tells you how
to sign it.

Toolchain used for the published build: JDK 17, Gradle 9.8.2, Android Gradle
plugin 9.5.1 with built-in Kotlin, Android SDK platform 34 and Build Tools 36.
Nothing else is required — the app uses the plain Android framework plus one
pure-Java ZXing jar; there is no AndroidX or Play Services dependency.

### Signing the APK yourself

The official GitHub release key is kept out of git and available only to the
release workflow. For local builds, create your own separate key:

```powershell
cd android
keytool -genkeypair -v -keystore keystore\vpad.jks -alias lemogamepad `
        -keyalg RSA -keysize 2048 -validity 10000
```

Create `android\keystore\keystore.properties` (also git-ignored):

```properties
storePassword=your-store-password
keyAlias=lemogamepad
keyPassword=your-key-password
```

`build_apk.ps1` picks the key up automatically. **Keep both files private and
back them up** — Android refuses to install an update signed with a different key.
For a public release, sign with your own key and keep it on a machine you control.

### Run the tests

```powershell
python pc\test_multi.py     # multi-phone: discovery, slots, rumble routing, reconnect
python pc\test_latency.py   # burst coalescing, ordering, PONG loss stats
python pc\test_video.py     # streamed frames (two viewers), QR PNG
python pc\test_web.py       # browser pad HTTP/WebSocket, pairing, slots, rumble
python pc\test_rumble.py    # virtual pad + rumble through Windows XInput
python pc\test_frozen.py    # same slot/viewer checks against a running .exe
```

On Linux:

```bash
cd pc
python3 test_multi.py       # works with or without /dev/uinput
python3 test_latency.py     # receive path, no driver or display needed
python3 test_linux_pad.py   # real uinput pad, buttons/axes, force feedback
python3 test_video.py       # needs a desktop session for screen capture
python3 test_capture.py     # Wayland/X11 capture backend selection, no desktop needed
python3 test_web.py         # browser pad HTTP/WebSocket, no browser needed
```

### Publish a change

```powershell
powershell -ExecutionPolicy Bypass -File .\publish.ps1 -Message "describe the change"
```

The script stages everything, refuses to commit signing material, commits and
pushes to `origin`. Tags and binaries belong on the GitHub Releases page.

---
