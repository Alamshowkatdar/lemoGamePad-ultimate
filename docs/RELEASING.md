# Releasing

A checklist for publishing a new version. Nothing here is automated end to end;
the release workflow builds and attaches the binaries, but the version bump is
manual.

## 1. Prepare the version

1. Bump `versionName` and `versionCode` in `android/app/build.gradle.kts`.
2. Bump `Brand.VERSION` in
   `android/app/src/main/java/com/alamshowkat/lemogamepad/Theme.kt` so the
   settings footer matches.
3. Update the version in `README.md` (intro and footer), `pc/web/app.js`,
   `pc/web/index.html` (browser pad labels), `version` in `rust/Cargo.toml`,
   and `APP_VERSION` in `rust/src/gui/main.rs` (desktop update checker), then
   add a section to `CHANGELOG.md`. Cargo requires three-part semver, so a short label such as
   `2.2` is written `2.2.0` in `Cargo.toml` while user-facing labels stay short.
4. If behaviour changed, add notes to `docs/REVIEW.md`.
5. Re-run the tests (see [CONTRIBUTING.md](../CONTRIBUTING.md#tests)).

## 2. Smoke-test both halves

At minimum:

- Windows: fresh install (or `--no-install-deps`), QR pairing, all face
  buttons, sticks, rumble, screen preview, and the 1.5 s stuck-button release.
- Linux: `pc/linux/install-linux.sh` on a clean user, `run.sh`, `test_linux_pad.py`,
  and capture on the session type you use (X11 or Wayland).
- Android: install over the previous release (same signing key), rotate,
  background/foreground, toggle Wi-Fi and USB transports, and pair a PC and a
  phone as Bluetooth HID hosts.

## 3. Tag and publish

Use the exact version label as the tag (`2.2.3`). The workflow strips an optional
leading `v`, but plain labels keep the APK name, documentation and update checks
identical. Do not reuse a tag that has already been published.

```bash
git tag -a 2.2.3 -m "lemoGamePad ultimate 2.2.3"
git push origin main --tags
```

Then create a GitHub Release from the tag and publish it. The
`Android release` workflow runs on `published` and produces:

| Asset | Built by |
| --- | --- |
| `lemoGamePad-<version>.apk` | `release` job (signed APK) |
| `lemoGamePad-server.exe`, `lemoGamePad-app.exe`, `allow_firewall.ps1` | `windows-server` job |
| `lemoGamePad-server`, `lemoGamePad-app`, `install-linux.sh` | `linux-server` job |
| `LICENSE`, `THIRD_PARTY_LICENSES.md`, `SHA256SUMS.txt` | `checksums` job |

Do not upload binaries to git; they belong on the release only.

## 4. Verify the release

1. All four jobs in the *Android release* workflow are green.
2. All expected assets exist and the APK is signed with the release key.
3. `SHA256SUMS.txt` lists every asset; verify one download manually:

   ```bash
   sha256sum -c SHA256SUMS.txt
   ```

4. The README badge points at the new release.
5. The release notes mention breaking changes, if any.

## 5. If something is wrong

Fix the source, bump the version (never reuse a version number that was already
published — Android will refuse an update signed or versioned inconsistently),
tag again, and publish a new release. Delete a broken draft release rather than
editing its assets in place. If the broken release was already published, leave
it in place but add a note pointing to the fixed version; do not move or reuse
its tag.
