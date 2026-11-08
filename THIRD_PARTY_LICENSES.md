# Third-party licenses

lemoGamePad ultimate is released under the [MIT License](LICENSE). It uses the
following third-party components. They are not covered by the project's MIT
license; their own licenses apply.

## PC server (bundled or required at runtime)

| Component | Used for | License | Project |
| --- | --- | --- | --- |
| [vgamepad](https://github.com/yannbouteiller/vgamepad) | Windows virtual-pad bindings | MIT | yannbouteiller/vgamepad |
| [ViGEmBus](https://github.com/nefarius/ViGEmBus) | Windows virtual-pad kernel driver (downloaded separately by the installer, SHA-256 verified) | BSD-3-Clause | nefarius/ViGEmBus |
| [python-evdev](https://github.com/gvalkov/python-evdev) | Linux `/dev/uinput` bindings | BSD-3-Clause | gvalkov/python-evdev |
| [mss](https://github.com/BoboTiG/python-mss) | X11 screen capture | MIT | BoboTiG/python-mss |
| [Pillow](https://github.com/python-pillow/Pillow) | JPEG encoding | HPND | python-pillow/Pillow |
| [qrcode](https://github.com/lincolnloop/python-qrcode) | Pairing QR code | BSD-3-Clause | lincolnloop/python-qrcode |
| [PyInstaller](https://pyinstaller.org) | Building the single-file server (build tool only) | GPL-2.0-or-later with the PyInstaller exception | pyinstaller/pyinstaller |

## Android app

| Component | Used for | License | Project |
| --- | --- | --- | --- |
| [ZXing core](https://github.com/zxing/zxing) (`zxing-core-3.5.3.jar`) | Decoding pairing QR codes | Apache-2.0 | zxing/zxing |

## Platform components

The app uses only the Android framework. The PC server uses only the Python
standard library, apart from the packages above. Windows and Android system
components (including the camera and Bluetooth stacks) are covered by their
platform licenses.

## Full license texts

Full texts ship with each upstream project. If you redistribute a built
`lemoGamePad-server` binary, include this file and the license texts of the
bundled packages; PyInstaller places them next to the extracted application.
