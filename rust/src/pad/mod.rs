//! Virtual gamepad backends. The public surface deliberately matches the old
//! Python backends so the UDP loop does not care which OS it is running on.

use crate::proto::InputState;
use std::sync::mpsc::Sender;

#[cfg(target_os = "windows")]
pub const VIGEM_PAGE: &str =
    "https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0";

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "windows")]
mod windows;

#[cfg(target_os = "linux")]
use linux::LinuxVirtualPad;
#[cfg(target_os = "windows")]
use windows::WindowsVirtualPad;

pub struct RumbleEvent {
    pub slot: usize,
    pub small: u8,
    pub large: u8,
}

pub trait VirtualPad: Send {
    fn apply(&mut self, state: &InputState);
    fn ok(&self) -> bool;
    fn error(&self) -> &str;
    fn rumble_error(&self) -> &str;
    fn close(&mut self);
}

pub struct MissingPad {
    error: String,
    last: InputState,
}

impl MissingPad {
    fn new(error: impl Into<String>) -> Self {
        Self {
            error: error.into(),
            last: InputState::default(),
        }
    }
}

impl VirtualPad for MissingPad {
    fn apply(&mut self, state: &InputState) {
        self.last = *state;
    }
    fn ok(&self) -> bool {
        false
    }
    fn error(&self) -> &str {
        &self.error
    }
    fn rumble_error(&self) -> &str {
        ""
    }
    fn close(&mut self) {}
}

pub fn create_pad(
    kind: &str,
    rumble: Sender<RumbleEvent>,
    slot: usize,
) -> Box<dyn VirtualPad> {
    #[cfg(target_os = "linux")]
    {
        match LinuxVirtualPad::new(kind, rumble, slot) {
            Ok(pad) => Box::new(pad),
            Err(error) => Box::new(MissingPad::new(format!(
                "{error}. Run: sudo sh pc/linux/install-linux.sh"
            ))),
        }
    }
    #[cfg(target_os = "windows")]
    {
        match WindowsVirtualPad::new(kind, rumble, slot) {
            Ok(pad) => Box::new(pad),
            Err(error) => Box::new(MissingPad::new(format!(
                "{error}. Install ViGEmBus once from {VIGEM_PAGE}"
            ))),
        }
    }
    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    {
        let _ = (kind, rumble, slot);
        Box::new(MissingPad::new("virtual gamepad backend unavailable"))
    }
}

pub fn backend_status() -> (bool, String) {
    #[cfg(target_os = "linux")]
    {
        if !std::path::Path::new("/dev/uinput").exists() {
            return (
                false,
                "uinput module is not loaded (/dev/uinput is missing)".to_string(),
            );
        }
        if !std::fs::File::options()
            .write(true)
            .open("/dev/uinput")
            .is_ok()
        {
            return (
                false,
                "no write access to /dev/uinput (add your user to the input group)".to_string(),
            );
        }
        return (true, "OK (virtual gamepad via /dev/uinput)".to_string());
    }
    #[cfg(target_os = "windows")]
    {
        return win_driver_status();
    }
    #[allow(unreachable_code)]
    (false, "virtual gamepad backend unavailable".to_string())
}

#[cfg(target_os = "windows")]
fn win_driver_status() -> (bool, String) {
    use libloading::{Library, Symbol};
    type RegOpenKeyW = unsafe extern "system" fn(isize, *const u16, *mut isize) -> i32;
    type RegCloseKey = unsafe extern "system" fn(isize) -> i32;
    let name: Vec<u16> = "SYSTEM\\CurrentControlSet\\Services\\ViGEmBus"
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    let ok = unsafe {
        Library::new("advapi32.dll")
            .ok()
            .and_then(|library| {
                let open: Symbol<RegOpenKeyW> = library.get(b"RegOpenKeyW\0").ok()?;
                let close: Symbol<RegCloseKey> = library.get(b"RegCloseKey\0").ok()?;
                let mut key: isize = 0;
                let code = open(0x8000_0002, name.as_ptr(), &mut key);
                if code == 0 {
                    close(key);
                }
                Some(code == 0)
            })
            .unwrap_or(false)
    };
    if ok {
        (true, "OK (virtual gamepad driver installed)".to_string())
    } else {
        (
            false,
            format!("install ViGEmBus once from {VIGEM_PAGE}"),
        )
    }
}
