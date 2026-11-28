# ViGEmBus (bundled Windows driver installer)

`ViGEmBus_1.22.0_x64_x86_arm64.exe` is the official, signed setup binary from
<https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0>. It is embedded into
`lemoGamePad-app.exe` so the Windows **Install dependencies** button works
offline. It is not downloaded or executed unless the user confirms.

- SHA-256: `89220a7865076b342892f98865f3499fb7c4cfd673159e89d352c360fd014c6a`
  (matches the [winget manifest](https://github.com/microsoft/winget-pkgs/blob/master/manifests/v/ViGEm/ViGEmBus/1.22.0/ViGEm.ViGEmBus.installer.yaml)).
- License: BSD-3-Clause, see [`LICENSE`](LICENSE).
- Silent switches: `/exenoui /qn /norestart` (winget manifest; the upstream
  release notes explicitly permit redistribution with `/qn`).
- Upstream marks ViGEmBus as end-of-life; 1.22.0 is the final signed release.

To update: replace the EXE, update the hash above and in
`THIRD_PARTY_LICENSES.md`, and keep the upstream license text in sync.
