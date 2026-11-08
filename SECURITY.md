# Security policy

## Supported versions

Security fixes are made on the latest release. Please reproduce the issue on
the [latest release](https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/latest)
before reporting it.

| Version | Supported |
| --- | --- |
| Latest release (1.4.x) | Yes |
| Older releases | No — upgrade first |

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Use GitHub's private vulnerability reporting:

1. Open the **Security** tab of this repository.
2. Click **Report a vulnerability**.
3. Describe the issue, the affected component (APK, Windows server, Linux
   server, install scripts, CI), and the impact.

If private reporting is unavailable, open a minimal public issue that says only
*"I have a security report, please enable private reporting"* — without details.
Do not include exploit steps, crash logs containing personal data, or the
contents of a keystore.

You can expect an initial response as soon as possible. This is a
single-maintainer hobby project, so please allow time for a fix; coordinated
disclosure is appreciated. There is no bug bounty.

## Threat model — what is and is not a vulnerability

lemoGamePad is a **local-network** tool. It is designed for a trusted LAN or a
direct USB/Bluetooth PAN link.

By design:

- The control protocol (UDP 9876) and video stream (TCP 9877) are
  **unauthenticated and unencrypted**. Anyone who can reach those ports on the
  PC can inject controller input or watch the screen preview.
- The pairing token is an identity hint for controller-slot reuse, **not** a
  secret or an authentication mechanism.
- The QR payload contains the PC's LAN address and port only.

Consequences:

- **Do not forward ports 9876/9877 to the internet** and do not run the server
  on an untrusted network (public Wi-Fi, guest networks with client isolation
  disabled, shared hosting).
- This is documented in [Limitations](docs/LIMITATIONS.md). A report that input
  can be spoofed by another device on the same LAN is expected behaviour, not a
  vulnerability.

Reports that **are** in scope include, for example:

- Memory-safety or denial-of-service bugs reachable from a malformed UDP/TCP
  packet (crash, unbounded allocation, resource exhaustion) on the server.
- Remote code execution through the protocol, QR payload, server name, or
  screen-stream metadata.
- Privilege escalation in the install scripts, the ViGEmBus download/verify
  path, or the Linux `uinput`/udev setup.
- Secrets or signing material committed to the repository or leaked by CI.
- Android exported-component or permission issues that let another app control
  the pad or read pairing data.
- A bypass of the SHA-256 verification for downloaded dependencies.

## Safe harbour

Good-faith research on your own devices is welcome. Do not test against other
people's machines or networks, do not publish exploit details before a fix is
available, and do not degrade services you do not own.
