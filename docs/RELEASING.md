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
5. Run `./tools/preflight.sh` from the repository root (see below).

## 2. Smoke-test both halves

At minimum:

- Windows: fresh install (or `--no-install-deps`), QR pairing, all face
  buttons, sticks, rumble, screen preview, and the 1.5 s stuck-button release.
- Linux: `pc/linux/install-linux.sh` on a clean user, `run.sh`, `test_linux_pad.py`,
  and capture on the session type you use (X11 or Wayland).
- Android: install over the previous release (same signing key), rotate,
  background/foreground, toggle Wi-Fi and USB transports, and pair a PC and a
  phone as Bluetooth HID hosts.

## 3. Pre-tag checks

`tools/check_versions.py` treats the `version` in `rust/Cargo.toml` as canonical
and checks every user-facing label against it (a Cargo `2.2.0` is the public
label `2.2`). `tools/preflight.sh` runs that check plus offline Rust check/tests,
the PC suites that need no display or `/dev/uinput`, offline Android lint and
`assembleDebug`, the EDIT LAYOUT UI regression test (the debug APK is installed
and exercised when a device is connected, otherwise the test prints `SKIP`),
and `git diff --check`. It uses the SDK at
`/tmp/opencode/android-sdk`; point that at your local SDK if yours lives
elsewhere. Install `pc/requirements.txt` if you also want to run the optional
screen-capture test (`pc/test_capture.py`).

Before tagging, check the exact label you are about to use (`v` is stripped),
then run the full preflight from the repository root:

```bash
python3 tools/check_versions.py --expected "${tag#v}"
./tools/preflight.sh
```

The release workflow repeats the first command as a preflight job for every
tag, using the tag as `--expected`, and CI runs the plain version check on
pushes and pull requests.

## 4. Tag and publish

Use the exact version label as the tag (`2.2.5`). The workflow strips an optional
leading `v`, but plain labels keep the APK name, documentation and update checks
identical. Do not reuse a tag that has already been published.

```bash
tag=2.2.5
git tag -a "$tag" -m "lemoGamePad ultimate $tag"
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

## 5. Verify the release

1. The *Android release* preflight and all four build/publish jobs are green.
2. All expected assets exist and the APK is signed with the release key.
3. `SHA256SUMS.txt` lists every asset; verify one download manually:

   ```bash
   sha256sum -c SHA256SUMS.txt
   ```

4. The README badge points at the new release.
5. The release notes mention breaking changes, if any.

## 6. If something is wrong

Fix the source, bump the version (never reuse a version number that was already
published — Android will refuse an update signed or versioned inconsistently),
tag again, and publish a new release. Delete a broken draft release rather than
editing its assets in place. If the broken release was already published, leave
it in place but add a note pointing to the fixed version; do not move or reuse
its tag.
