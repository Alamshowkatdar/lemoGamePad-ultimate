# Contributing

Thanks for taking the time to improve lemoGamePad ultimate. This is a small
hobby project, so clear bug reports are as valuable as pull requests.

## Before you start

- Read [Limitations](docs/LIMITATIONS.md) and the [security policy](SECURITY.md).
  The local protocol is deliberately unauthenticated; that is a design choice,
  not a bug.
- Search existing [issues](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/issues)
  before opening a new one.
- For a security problem, **do not open a public issue** — use private
  vulnerability reporting as described in [SECURITY.md](SECURITY.md).

## Reporting a bug

Use the bug report template and include:

- App version (shown in the settings footer) and server version (banner).
- Windows or Linux, the exact OS version, and the transport in use (Wi-Fi,
  Bluetooth PAN, USB).
- What you expected and what happened.
- The server console output, including the `ViGEmBus`, `Firewall`, and `uinput`
  lines, and the app's diagnostics line.
- Whether screen preview was on and which quality was selected.

Please do not paste keystore contents, private keys, or the contents of
`android/keystore/keystore.properties`.

## Development setup

The project has no runtime dependency beyond the Python standard library on the
PC and the Android framework on the phone.

### PC server (Python 3.9+)

```bash
cd pc
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python vpad_server.py
```

On Windows, or for the single-file build:

```powershell
cd pc
python -m pip install -r requirements.txt
powershell -ExecutionPolicy Bypass -File .\build_exe.ps1
```

### Android app (JDK 17–21, Android SDK platform 34)

```powershell
cd android
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
```

Or plain Gradle:

```bash
cd android
./gradlew assembleDebug
```

The app intentionally uses **only the Android framework** plus the pure-Java
ZXing jar. Do not add AndroidX, Play Services, Compose, or new third-party
dependencies without discussing it in an issue first — keeping the APK ~1 MB
and dependency-free is a project goal.

## Tests

Run the relevant suites before opening a pull request:

```bash
cd pc
python3 test_multi.py      # discovery, slots, rumble routing, reconnect
python3 test_latency.py    # receive path, coalescing, ordering, PONG stats
python3 test_capture.py    # screen-capture backend selection
python3 test_video.py      # needs a desktop session for real capture
python3 test_linux_pad.py  # Linux only, needs /dev/uinput
```

CI runs the PC suites on Linux and builds the debug APK. There is no Android
unit-test suite yet; if you add one, wire it into
`.github/workflows/android-build.yml`.

## Code style

- **Kotlin:** official Kotlin style, 4-space indents, no wildcard imports.
  Keep custom views allocation-light in `onDraw` — no per-frame object churn.
- **Python:** PEP 8, 4-space indents, type hints where they help. Keep the
  receive path non-blocking: no `print()`, DNS, driver creation, or disk I/O in
  the packet loop.
- **PowerShell / shell:** prefer fixed argument arrays over string-built
  commands; quote paths; keep elevation to the minimum required.
- Match the surrounding style: this codebase favours explicit comments that
  explain *why* a non-obvious decision was made.

## Wire protocol and compatibility

The 29-byte protocol is documented in [docs/PROTOCOL.md](docs/PROTOCOL.md).
Changes must stay compatible with 1.3 clients and servers unless a version bump
is explicitly agreed. If you change the protocol:

- update `android/.../Protocol.kt` and `pc/vpad_server.py` together;
- update [docs/PROTOCOL.md](docs/PROTOCOL.md);
- add or extend a test in `pc/test_multi.py` / `pc/test_latency.py`.

## Pull requests

1. Fork the repository and create a branch from `main`
   (`fix/…`, `feat/…`, `docs/…`).
2. Keep the change focused; avoid unrelated formatting churn.
3. Describe the problem, the fix, and how you tested it.
4. Update the README, docs, and `CHANGELOG.md` when user-visible behaviour
   changes.
5. Make sure CI is green.

## Signing and secrets

Never commit a keystore, `keystore.properties`, `.jks`, `.p12`, `.pfx`, or any
private key. The repository's `.gitignore` and `publish.ps1` refuse them, but do
not rely on that. Build locally with your own key; the release workflow uses
repository secrets.

## License

By contributing, you agree that your contribution is licensed under the
project's [MIT License](LICENSE).
