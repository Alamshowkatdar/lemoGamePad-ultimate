# Third-party licenses

lemoGamePad ultimate is released under the [MIT License](LICENSE). It uses the
following third-party components. They are not covered by the project's MIT
license; their own licenses apply.

## Native binaries (Rust, bundled)

| Component | Used for | License | Project |
| --- | --- | --- | --- |
| [eframe](https://github.com/emilk/egui) | Native window and render loop for the desktop panel | MIT OR Apache-2.0 | emilk/egui |
| [egui](https://github.com/emilk/egui) | Immediate-mode widgets for the desktop panel | MIT OR Apache-2.0 | emilk/egui |
| [image](https://github.com/image-rs/image) | JPEG encoding for the screen stream | MIT OR Apache-2.0 | image-rs/image |
| [libloading](https://github.com/nagisa/rust_libloading) | Loading ViGEmClient.dll / Windows system APIs | MIT OR Apache-2.0 | nagisa/rust_libloading |
| [libc](https://github.com/rust-lang/libc) | Linux uinput ioctls and Unix signals | MIT OR Apache-2.0 | rust-lang/libc |
| [qrcode](https://github.com/kennytm/qrcode-rust) | Pairing QR code | MIT OR Apache-2.0 | kennytm/qrcode-rust |
| [ViGEmBus 1.22.0 setup](https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0) (`vendor/vigembus/ViGEmBus_1.22.0_x64_x86_arm64.exe`, embedded in the Windows app) | Offline driver install from **Install dependencies** | BSD-3-Clause | nefarius/ViGEmBus |

The Python packages listed in `pc/requirements.txt` are used only by the
source-tree reference and test scripts; they are not bundled into the shipped
binaries.

## Android app

| Component | Used for | License | Project |
| --- | --- | --- | --- |
| [ZXing core](https://github.com/zxing/zxing) (`zxing-core-3.5.3.jar`) | Decoding pairing QR codes | Apache-2.0 | zxing/zxing |

## Platform components

The Android app uses only the Android framework. The native receiver uses the
Rust crates above and the operating system's socket, uinput/ViGEm and capture
interfaces. The Windows app embeds the signed [ViGEmBus](https://github.com/nefarius/ViGEmBus)
1.22.0 setup binary and its BSD-3-Clause license in `vendor/vigembus/`, and only
runs it after the user confirms and Windows UAC approves. Windows and Android
system components (including the camera and Bluetooth stacks) are covered by
their platform licenses.

## Full license texts

Full texts ship with each upstream project. If you redistribute a built
`lemoGamePad-server` or `lemoGamePad-app` binary, include this file and the
license texts of the bundled Rust crates.
