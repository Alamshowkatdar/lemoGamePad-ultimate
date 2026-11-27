//! Windows virtual pad backed by the ViGEmBus client DLL.
//!
//! ViGEm's C ABI is loaded dynamically so the binary still starts when the
//! driver is not installed; the failure is reported in the server banner.

use super::{RumbleEvent, VirtualPad};
use crate::proto::{axis, ButtonIndex, InputState, BUTTON_CROSS, BUTTON_PS};
use libloading::{Library, Symbol};
use std::ffi::c_void;
use std::sync::mpsc::Sender;

const ERROR_SUCCESS: u32 = 0;

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

type AllocateClient = unsafe extern "C" fn() -> *mut c_void;
type Connect = unsafe extern "C" fn(*mut c_void) -> u32;
type AllocateTarget = unsafe extern "C" fn() -> *mut c_void;
type AddTarget = unsafe extern "C" fn(*mut c_void, *mut c_void) -> u32;
type X360Update = unsafe extern "C" fn(*mut c_void, *mut c_void, XusbReport) -> u32;
type Ds4Update = unsafe extern "C" fn(*mut c_void, *mut c_void, Ds4Report) -> u32;
type Unregister = unsafe extern "C" fn(*mut c_void);
type RemoveTarget = unsafe extern "C" fn(*mut c_void, *mut c_void) -> u32;
type FreeTarget = unsafe extern "C" fn(*mut c_void);
type Disconnect = unsafe extern "C" fn(*mut c_void);
type FreeClient = unsafe extern "C" fn(*mut c_void);
type X360Register = unsafe extern "C" fn(
    *mut c_void,
    *mut c_void,
    Option<unsafe extern "C" fn(*mut c_void, *mut c_void, u8, u8, u8, *mut c_void)>,
    *mut c_void,
) -> u32;
type Ds4Register = unsafe extern "C" fn(
    *mut c_void,
    *mut c_void,
    Option<unsafe extern "C" fn(*mut c_void, *mut c_void, u8, u8, u32, *mut c_void)>,
    *mut c_void,
) -> u32;

struct Api {
    _library: Library,
    allocate_client: Symbol<'static, AllocateClient>,
    connect: Symbol<'static, Connect>,
    allocate_x360: Symbol<'static, AllocateTarget>,
    allocate_ds4: Symbol<'static, AllocateTarget>,
    add_target: Symbol<'static, AddTarget>,
    x360_update: Symbol<'static, X360Update>,
    ds4_update: Symbol<'static, Ds4Update>,
    x360_register: Symbol<'static, X360Register>,
    ds4_register: Symbol<'static, Ds4Register>,
    x360_unregister: Symbol<'static, Unregister>,
    ds4_unregister: Symbol<'static, Unregister>,
    remove_target: Symbol<'static, RemoveTarget>,
    free_target: Symbol<'static, FreeTarget>,
    disconnect: Symbol<'static, Disconnect>,
    free_client: Symbol<'static, FreeClient>,
}

impl Api {
    unsafe fn load() -> Result<Self, String> {
        let library = Library::new("ViGEmClient.dll")
            .or_else(|_| Library::new("ViGEmClient64.dll"))
            .map_err(|err| format!("ViGEmClient.dll not available ({err})"))?;
        let library: &'static Library = Box::leak(Box::new(library));
        macro_rules! symbol {
            ($name:literal, $type:ty) => {
                library
                    .get::<$type>(concat!($name, "\0").as_bytes())
                    .map_err(|err| format!("{} missing from ViGEmClient.dll ({err})", $name))?
            };
        }
        Ok(Self {
            _library: Library::new("ViGEmClient.dll").or_else(|_| Library::new("ViGEmClient64.dll"))?,
            allocate_client: symbol!("vigem_alloc", AllocateClient),
            connect: symbol!("vigem_connect", Connect),
            allocate_x360: symbol!("vigem_target_x360_alloc", AllocateTarget),
            allocate_ds4: symbol!("vigem_target_ds4_alloc", AllocateTarget),
            add_target: symbol!("vigem_target_add", AddTarget),
            x360_update: symbol!("vigem_target_x360_update", X360Update),
            ds4_update: symbol!("vigem_target_ds4_update", Ds4Update),
            x360_register: symbol!("vigem_target_x360_register_notification", X360Register),
            ds4_register: symbol!("vigem_target_ds4_register_notification", Ds4Register),
            x360_unregister: symbol!("vigem_target_x360_unregister_notification", Unregister),
            ds4_unregister: symbol!("vigem_target_ds4_unregister_notification", Unregister),
            remove_target: symbol!("vigem_target_remove", RemoveTarget),
            free_target: symbol!("vigem_target_free", FreeTarget),
            disconnect: symbol!("vigem_disconnect", Disconnect),
            free_client: symbol!("vigem_free", FreeClient),
        })
    }
}

pub struct WindowsVirtualPad {
    api: Api,
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
        let api = unsafe { Api::load()? };
        let client = unsafe { (api.allocate_client)() };
        if client.is_null() {
            return Err("vigem_alloc returned null".into());
        }
        let code = unsafe { (api.connect)(client) };
        if code != ERROR_SUCCESS {
            unsafe { (api.free_client)(client) };
            return Err(format!("ViGEm connection failed (error {code})"));
        }
        let target = unsafe {
            if kind == "ds4" {
                (api.allocate_ds4)()
            } else {
                (api.allocate_x360)()
            }
        };
        if target.is_null() {
            unsafe {
                (api.disconnect)(client);
                (api.free_client)(client);
            }
            return Err("ViGEm target allocation failed".into());
        }
        let code = unsafe { (api.add_target)(client, target) };
        if code != ERROR_SUCCESS {
            unsafe {
                (api.free_target)(target);
                (api.disconnect)(client);
                (api.free_client)(client);
            }
            return Err(format!("could not add virtual pad (error {code})"));
        }

        let state = Box::new((rumble, slot));
        let user_data = Box::into_raw(state) as *mut c_void;
        let mut pad = Self {
            api,
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
        let api = &self.api;
        let result = unsafe {
            if self.kind == "ds4" {
                (api.ds4_register)(self.client, self.target, Some(ds4_notification), self.user_data)
            } else {
                (api.x360_register)(self.client, self.target, Some(x360_notification), self.user_data)
            }
        };
        if result != ERROR_SUCCESS {
            self.rumble_error = format!("error {result}");
        }
    }

    fn write(&mut self, state: &InputState) -> Result<(), u32> {
        let code = unsafe {
            if self.kind == "ds4" {
                (self.api.ds4_update)(self.client, self.target, ds4_report(state))
            } else {
                (self.api.x360_update)(self.client, self.target, x360_report(state))
            }
        };
        if code == ERROR_SUCCESS {
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
                (self.api.ds4_unregister)(self.target);
            } else {
                (self.api.x360_unregister)(self.target);
            }
            (self.api.remove_target)(self.client, self.target);
            (self.api.free_target)(self.target);
            (self.api.disconnect)(self.client);
            (self.api.free_client)(self.client);
            drop(Box::from_raw(self.user_data as *mut (Sender<RumbleEvent>, usize)));
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
                println!("\n[!] pad update failed: ViGEm error {code}");
                if self.failures <= 3 {
                    self.destroy();
                    if let Ok(recreated) =
                        Self::new(&self.kind, self.rumble.clone(), self.slot)
                    {
                        *self = recreated;
                        if let Err(code) = self.write(state) {
                            println!("\n[!] pad retry failed: ViGEm error {code}");
                            self.failures -= 1;
                        } else {
                            self.failures = 0;
                        }
                    }
                } else {
                    self.ok = false;
                    self.error = format!("virtual pad updates keep failing (error {code})");
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

unsafe extern "C" fn x360_notification(
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

unsafe extern "C" fn ds4_notification(
    _client: *mut c_void,
    _target: *mut c_void,
    large: u8,
    small: u8,
    _lightbar: u32,
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
