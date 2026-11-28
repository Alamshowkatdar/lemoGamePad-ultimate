//! Windows virtual pad backed by the ViGEmBus client.
//!
//! The upstream ViGEm Client C++ source is compiled into this binary by
//! `rust/build.rs`, so no separate `ViGEmClient.dll` has to be shipped or
//! discovered at runtime. Only the signed ViGEmBus driver is required.

use super::{vigem_error_text, vigem_is_success, RumbleEvent, VirtualPad};
use crate::proto::{axis, ButtonIndex, InputState, BUTTON_CROSS, BUTTON_PS};
use std::ffi::c_void;
use std::sync::mpsc::Sender;

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct XusbReport {
    buttons: u16,
    left_trigger: u8,
    right_trigger: u8,
    thumb_lx: i16,
    thumb_ly: i16,
    thumb_rx: i16,
    thumb_ry: i16,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Ds4Report {
    thumb_lx: u8,
    thumb_ly: u8,
    thumb_rx: u8,
    thumb_ry: u8,
    buttons: u16,
    special: u8,
    trigger_l: u8,
    trigger_r: u8,
}

/// `DS4_LIGHTBAR_COLOR` from ViGEm's `Common.h`.
#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Ds4LightbarColor {
    red: u8,
    green: u8,
    blue: u8,
}

type X360Notification = unsafe extern "system" fn(
    *mut c_void,
    *mut c_void,
    u8,
    u8,
    u8,
    *mut c_void,
);
type Ds4Notification = unsafe extern "system" fn(
    *mut c_void,
    *mut c_void,
    u8,
    u8,
    Ds4LightbarColor,
    *mut c_void,
);

// ViGEm uses the C ABI for its API functions and `CALLBACK` (__stdcall on
// x86) for notification callbacks. The static library is linked by build.rs.
unsafe extern "C" {
    fn vigem_alloc() -> *mut c_void;
    fn vigem_free(client: *mut c_void);
    fn vigem_connect(client: *mut c_void) -> u32;
    fn vigem_disconnect(client: *mut c_void);
    fn vigem_target_x360_alloc() -> *mut c_void;
    fn vigem_target_ds4_alloc() -> *mut c_void;
    fn vigem_target_add(client: *mut c_void, target: *mut c_void) -> u32;
    fn vigem_target_x360_update(
        client: *mut c_void,
        target: *mut c_void,
        report: XusbReport,
    ) -> u32;
    fn vigem_target_ds4_update(
        client: *mut c_void,
        target: *mut c_void,
        report: Ds4Report,
    ) -> u32;
    fn vigem_target_x360_register_notification(
        client: *mut c_void,
        target: *mut c_void,
        notification: Option<X360Notification>,
        user_data: *mut c_void,
    ) -> u32;
    fn vigem_target_ds4_register_notification(
        client: *mut c_void,
        target: *mut c_void,
        notification: Option<Ds4Notification>,
        user_data: *mut c_void,
    ) -> u32;
    fn vigem_target_x360_unregister_notification(target: *mut c_void);
    fn vigem_target_ds4_unregister_notification(target: *mut c_void);
    fn vigem_target_remove(client: *mut c_void, target: *mut c_void) -> u32;
    fn vigem_target_free(target: *mut c_void);
}

pub struct WindowsVirtualPad {
    client: *mut c_void,
    target: *mut c_void,
    user_data: *mut c_void,
    rumble: Sender<RumbleEvent>,
    slot: usize,
    kind: String,
    error: String,
    rumble_error: String,
    ok: bool,
    failures: usize,
    last: InputState,
}

unsafe impl Send for WindowsVirtualPad {}

impl WindowsVirtualPad {
    pub fn new(kind: &str, rumble: Sender<RumbleEvent>, slot: usize) -> Result<Self, String> {
        let client = unsafe { vigem_alloc() };
        if client.is_null() {
            return Err("vigem_alloc returned null".into());
        }
        let code = unsafe { vigem_connect(client) };
        if !vigem_is_success(code) {
            unsafe { vigem_free(client) };
            return Err(format!(
                "ViGEmBus connection failed: {}",
                vigem_error_text(code)
            ));
        }
        let target = unsafe {
            if kind == "ds4" {
                vigem_target_ds4_alloc()
            } else {
                vigem_target_x360_alloc()
            }
        };
        if target.is_null() {
            unsafe {
                vigem_disconnect(client);
                vigem_free(client);
            }
            return Err("ViGEm target allocation failed".into());
        }
        let code = unsafe { vigem_target_add(client, target) };
        if !vigem_is_success(code) {
            unsafe {
                vigem_target_free(target);
                vigem_disconnect(client);
                vigem_free(client);
            }
            return Err(format!(
                "could not add virtual pad: {}",
                vigem_error_text(code)
            ));
        }

        let state = Box::new((rumble.clone(), slot));
        let user_data = Box::into_raw(state) as *mut c_void;
        let mut pad = Self {
            client,
            target,
            user_data,
            rumble: rumble.clone(),
            slot,
            kind: kind.to_string(),
            error: String::new(),
            rumble_error: String::new(),
            ok: true,
            failures: 0,
            last: InputState::default(),
        };
        pad.register();
        Ok(pad)
    }

    fn register(&mut self) {
        let result = unsafe {
            if self.kind == "ds4" {
                vigem_target_ds4_register_notification(
                    self.client,
                    self.target,
                    Some(ds4_notification),
                    self.user_data,
                )
            } else {
                vigem_target_x360_register_notification(
                    self.client,
                    self.target,
                    Some(x360_notification),
                    self.user_data,
                )
            }
        };
        if !vigem_is_success(result) {
            self.rumble_error = vigem_error_text(result);
        }
    }

    fn write(&mut self, state: &InputState) -> Result<(), u32> {
        let code = unsafe {
            if self.kind == "ds4" {
                vigem_target_ds4_update(self.client, self.target, ds4_report(state))
            } else {
                vigem_target_x360_update(self.client, self.target, x360_report(state))
            }
        };
        if vigem_is_success(code) {
            Ok(())
        } else {
            Err(code)
        }
    }

    fn destroy(&mut self) {
        if self.target.is_null() || self.client.is_null() {
            return;
        }
        unsafe {
            if self.kind == "ds4" {
                vigem_target_ds4_unregister_notification(self.target);
            } else {
                vigem_target_x360_unregister_notification(self.target);
            }
            vigem_target_remove(self.client, self.target);
            vigem_target_free(self.target);
            vigem_disconnect(self.client);
            vigem_free(self.client);
            drop(Box::from_raw(
                self.user_data as *mut (Sender<RumbleEvent>, usize),
            ));
        }
        self.target = std::ptr::null_mut();
        self.client = std::ptr::null_mut();
        self.user_data = std::ptr::null_mut();
    }
}

impl VirtualPad for WindowsVirtualPad {
    fn apply(&mut self, state: &InputState) {
        self.last = *state;
        if !self.ok {
            return;
        }
        match self.write(state) {
            Ok(()) => self.failures = 0,
            Err(code) => {
                self.failures += 1;
                println!("\n[!] pad update failed: {}", vigem_error_text(code));
                if self.failures <= 3 {
                    self.destroy();
                    if let Ok(recreated) =
                        Self::new(&self.kind, self.rumble.clone(), self.slot)
                    {
                        *self = recreated;
                        if let Err(code) = self.write(state) {
                            println!("\n[!] pad retry failed: {}", vigem_error_text(code));
                            self.failures -= 1;
                        } else {
                            self.failures = 0;
                        }
                    }
                } else {
                    self.ok = false;
                    self.error = format!(
                        "virtual pad updates keep failing: {}",
                        vigem_error_text(code)
                    );
                }
            }
        }
    }
    fn ok(&self) -> bool {
        self.ok
    }
    fn error(&self) -> &str {
        &self.error
    }
    fn rumble_error(&self) -> &str {
        &self.rumble_error
    }
    fn close(&mut self) {
        if !self.ok {
            return;
        }
        let _ = self.write(&InputState::default());
        self.ok = false;
        self.destroy();
    }
}

impl Drop for WindowsVirtualPad {
    fn drop(&mut self) {
        self.close();
    }
}

unsafe extern "system" fn x360_notification(
    _client: *mut c_void,
    _target: *mut c_void,
    large: u8,
    small: u8,
    _led: u8,
    user_data: *mut c_void,
) {
    if user_data.is_null() {
        return;
    }
    let (sender, slot) = &*(user_data as *const (Sender<RumbleEvent>, usize));
    let _ = sender.send(RumbleEvent {
        slot: *slot,
        small,
        large,
    });
}

unsafe extern "system" fn ds4_notification(
    _client: *mut c_void,
    _target: *mut c_void,
    large: u8,
    small: u8,
    _lightbar: Ds4LightbarColor,
    user_data: *mut c_void,
) {
    if user_data.is_null() {
        return;
    }
    let (sender, slot) = &*(user_data as *const (Sender<RumbleEvent>, usize));
    let _ = sender.send(RumbleEvent {
        slot: *slot,
        small,
        large,
    });
}

fn x360_report(state: &InputState) -> XusbReport {
    const UP: u16 = 1 << 0;
    const DOWN: u16 = 1 << 1;
    const LEFT: u16 = 1 << 2;
    const RIGHT: u16 = 1 << 3;
    const START: u16 = 1 << 4;
    const BACK: u16 = 1 << 5;
    const LTHUMB: u16 = 1 << 6;
    const RTHUMB: u16 = 1 << 7;
    const LSHOULDER: u16 = 1 << 8;
    const RSHOULDER: u16 = 1 << 9;
    const GUIDE: u16 = 1 << 10;
    const A: u16 = 1 << 12;
    const B: u16 = 1 << 13;
    const X: u16 = 1 << 14;
    const Y: u16 = 1 << 15;
    let mut buttons = 0u16;
    let bit = |buttons: &mut u16, index: usize, flag: u16| {
        if state.buttons & (1 << index) != 0 {
            *buttons |= flag;
        }
    };
    bit(&mut buttons, ButtonIndex::UP, UP);
    bit(&mut buttons, ButtonIndex::DOWN, DOWN);
    bit(&mut buttons, ButtonIndex::LEFT, LEFT);
    bit(&mut buttons, ButtonIndex::RIGHT, RIGHT);
    bit(&mut buttons, ButtonIndex::START, START);
    bit(&mut buttons, ButtonIndex::SELECT, BACK);
    bit(&mut buttons, ButtonIndex::L3, LTHUMB);
    bit(&mut buttons, ButtonIndex::R3, RTHUMB);
    bit(&mut buttons, ButtonIndex::L1, LSHOULDER);
    bit(&mut buttons, ButtonIndex::R1, RSHOULDER);
    bit(&mut buttons, ButtonIndex::PS, GUIDE);
    bit(&mut buttons, ButtonIndex::CROSS, A);
    bit(&mut buttons, ButtonIndex::CIRCLE, B);
    bit(&mut buttons, ButtonIndex::SQUARE, X);
    bit(&mut buttons, ButtonIndex::TRIANGLE, Y);
    XusbReport {
        buttons,
        left_trigger: (state.left_trigger() * 255.0) as u8,
        right_trigger: (state.right_trigger() * 255.0) as u8,
        thumb_lx: scale_axis(state.lx),
        thumb_ly: scale_axis(state.ly),
        thumb_rx: scale_axis(state.rx),
        thumb_ry: scale_axis(state.ry),
    }
}

fn ds4_report(state: &InputState) -> Ds4Report {
    const SHARE: u16 = 1 << 0;
    const LTHUMB: u16 = 1 << 1;
    const RTHUMB: u16 = 1 << 2;
    const OPTIONS: u16 = 1 << 3;
    const LSHOULDER: u16 = 1 << 8;
    const RSHOULDER: u16 = 1 << 9;
    const SQUARE: u16 = 1 << 12;
    const CROSS: u16 = 1 << 13;
    const CIRCLE: u16 = 1 << 14;
    const TRIANGLE: u16 = 1 << 15;
    let mut buttons = 0u16;
    let bit = |buttons: &mut u16, index: usize, flag: u16| {
        if state.buttons & (1 << index) != 0 {
            *buttons |= flag;
        }
    };
    bit(&mut buttons, ButtonIndex::SELECT, SHARE);
    bit(&mut buttons, ButtonIndex::L3, LTHUMB);
    bit(&mut buttons, ButtonIndex::R3, RTHUMB);
    bit(&mut buttons, ButtonIndex::START, OPTIONS);
    bit(&mut buttons, ButtonIndex::L1, LSHOULDER);
    bit(&mut buttons, ButtonIndex::R1, RSHOULDER);
    bit(&mut buttons, ButtonIndex::L2, 1 << 10);
    bit(&mut buttons, ButtonIndex::R2, 1 << 11);
    bit(&mut buttons, ButtonIndex::SQUARE, SQUARE);
    bit(&mut buttons, ButtonIndex::CROSS, CROSS);
    bit(&mut buttons, ButtonIndex::CIRCLE, CIRCLE);
    bit(&mut buttons, ButtonIndex::TRIANGLE, TRIANGLE);
    let dpad = if state.pressed(crate::proto::BUTTON_UP) && state.pressed(crate::proto::BUTTON_RIGHT) {
        2
    } else if state.pressed(crate::proto::BUTTON_UP) && state.pressed(crate::proto::BUTTON_LEFT) {
        8
    } else if state.pressed(crate::proto::BUTTON_DOWN) && state.pressed(crate::proto::BUTTON_RIGHT) {
        4
    } else if state.pressed(crate::proto::BUTTON_DOWN) && state.pressed(crate::proto::BUTTON_LEFT) {
        6
    } else if state.pressed(crate::proto::BUTTON_UP) {
        1
    } else if state.pressed(crate::proto::BUTTON_DOWN) {
        5
    } else if state.pressed(crate::proto::BUTTON_LEFT) {
        7
    } else if state.pressed(crate::proto::BUTTON_RIGHT) {
        3
    } else {
        0
    };
    buttons |= dpad << 4;
    Ds4Report {
        thumb_lx: axis_byte(axis(state.lx)),
        thumb_ly: axis_byte(-axis(state.ly)),
        thumb_rx: axis_byte(axis(state.rx)),
        thumb_ry: axis_byte(-axis(state.ry)),
        buttons,
        special: u8::from(state.buttons & BUTTON_PS != 0),
        trigger_l: (state.left_trigger() * 255.0) as u8,
        trigger_r: (state.right_trigger() * 255.0) as u8,
    }
}

fn scale_axis(value: i16) -> i16 {
    (axis(value) * 32767.0) as i16
}

fn axis_byte(value: f32) -> u8 {
    ((value + 1.0) * 127.5).clamp(0.0, 255.0) as u8
}

#[allow(dead_code)]
fn _cross_bit() -> u32 {
    BUTTON_CROSS
}
