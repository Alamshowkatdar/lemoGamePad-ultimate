//! Linux virtual pad backed directly by `/dev/uinput`.
//!
//! The device identity, button map, axis ranges and force-feedback handling
//! mirror `pc/linux_pad.py` so Steam/SDL recognise it the same way.

use super::{RumbleEvent, VirtualPad};
use crate::proto::{axis, ButtonIndex, InputState};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::fs::{self, File};
use std::io;
use std::mem;
use std::os::fd::AsRawFd;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const EV_SYN: u16 = 0;
const EV_KEY: u16 = 1;
const EV_ABS: u16 = 3;
const EV_FF: u16 = 21;
const EV_UINPUT: u16 = 257;
const SYN_REPORT: u16 = 0;
const ABS_X: u16 = 0;
const ABS_Y: u16 = 1;
const ABS_Z: u16 = 2;
const ABS_RX: u16 = 3;
const ABS_RY: u16 = 4;
const ABS_RZ: u16 = 5;
const ABS_HAT0X: u16 = 16;
const ABS_HAT0Y: u16 = 17;
const FF_RUMBLE: u16 = 80;
const FF_GAIN: u16 = 96;
const UINPUT_EVENT_TYPES: [u16; 4] = [EV_KEY, EV_ABS, EV_SYN, EV_FF];
const UI_FF_UPLOAD: u16 = 1;
const UI_FF_ERASE: u16 = 2;
const UINPUT_IOCTL_BASE: u32 = b'U' as u32;

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct InputEvent {
    tv_sec: libc::time_t,
    tv_usec: libc::suseconds_t,
    kind: u16,
    code: u16,
    value: i32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct UinputSetup {
    id: InputId,
    name: [u8; 80],
    ff_effects_max: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct AbsInfo {
    value: i32,
    minimum: i32,
    maximum: i32,
    fuzz: u32,
    flat: u32,
    resolution: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct UinputAbsSetup {
    code: u16,
    absinfo: AbsInfo,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct FfTrigger {
    button: u16,
    interval: u16,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct FfReplay {
    length: u16,
    delay: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FfEffect {
    kind: u16,
    id: i16,
    direction: u16,
    trigger: FfTrigger,
    replay: FfReplay,
    _pad: [u8; 2],
    u: [u8; 32],
}

impl Default for FfEffect {
    fn default() -> Self {
        Self {
            kind: 0,
            id: 0,
            direction: 0,
            trigger: FfTrigger::default(),
            replay: FfReplay::default(),
            _pad: [0; 2],
            u: [0; 32],
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct UinputUpload {
    request_id: u32,
    retval: i32,
    effect: FfEffect,
    old: FfEffect,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct UinputErase {
    request_id: u32,
    retval: i32,
    effect_id: u32,
}

#[derive(Clone)]
struct Effect {
    weak: u16,
    strong: u16,
    playing: bool,
    length: u16,
    ends_at: Option<Instant>,
}

#[derive(Default)]
struct FfState {
    effects: HashMap<i32, Effect>,
    gain: u16,
    last: (u8, u8),
}

struct Buttons {
    map: [Option<u16>; 17],
}

pub struct LinuxVirtualPad {
    fd: File,
    error: String,
    rumble_error: String,
    ok: bool,
    kind: String,
    buttons: Buttons,
    written: HashMap<(u16, u16), i32>,
    running: Arc<AtomicBool>,
    ff: Arc<Mutex<FfState>>,
    ff_thread: Option<JoinHandle<()>>,
}

impl LinuxVirtualPad {
    pub fn new(kind: &str, rumble: Sender<RumbleEvent>, slot: usize) -> Result<Self, String> {
        if !Path::new("/dev/uinput").exists() {
            return Err("uinput module is not loaded (/dev/uinput is missing)".into());
        }
        let fd = fs::OpenOptions::new()
            .write(true)
            .read(true)
            .open("/dev/uinput")
            .map_err(|err| format!("could not open /dev/uinput ({err})"))?;
        unsafe {
            if libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, libc::O_NONBLOCK) < 0 {
                return Err("could not make /dev/uinput non-blocking".into());
            }
        }

        let buttons = button_map(kind);
        let identity = if kind == "ds4" {
            InputId {
                bustype: 3,
                vendor: 0x054C,
                product: 0x09CC,
                version: 0x8111,
            }
        } else {
            InputId {
                bustype: 3,
                vendor: 0x045E,
                product: 0x028E,
                version: 0x0114,
            }
        };
        let name = if kind == "ds4" {
            "lemoGamePad ultimate (DualShock 4)"
        } else {
            "lemoGamePad ultimate (Xbox 360)"
        };

        unsafe {
            // EV_ABS must be enabled as an event type as well as per-axis bits;
            // without this, sticks, triggers and the D-pad are invisible.
            for event_type in UINPUT_EVENT_TYPES {
                set_bit(fd.as_raw_fd(), 100, i32::from(event_type))?; // UI_SET_EVBIT
            }
            set_bit(fd.as_raw_fd(), 103, EV_ABS as i32)?; // UI_SET_ABSBIT
            set_bit(fd.as_raw_fd(), 107, FF_RUMBLE as i32)?; // UI_SET_FFBIT
            set_bit(fd.as_raw_fd(), 107, FF_GAIN as i32)?; // UI_SET_FFBIT
        }

        let mut codes: Vec<u16> = buttons.map.iter().filter_map(|code| *code).collect();
        codes.sort_unstable();
        codes.dedup();
        for code in codes {
            unsafe { set_bit(fd.as_raw_fd(), 101, i32::from(code))? } // UI_SET_KEYBIT
        }
        for code in [
            ABS_X,
            ABS_Y,
            ABS_RX,
            ABS_RY,
            ABS_Z,
            ABS_RZ,
            ABS_HAT0X,
            ABS_HAT0Y,
        ] {
            unsafe { set_bit(fd.as_raw_fd(), 103, i32::from(code))? }
        }

        let mut setup = UinputSetup {
            id: identity,
            name: [0; 80],
            ff_effects_max: 16,
        };
        let name_bytes = name.as_bytes();
        setup.name[..name_bytes.len()].copy_from_slice(name_bytes);
        unsafe {
            ioctl_struct(
                fd.as_raw_fd(),
                libc::_IOW::<UinputSetup>(UINPUT_IOCTL_BASE, 3),
                &mut setup,
            )?;
        }
        for (code, minimum, maximum, fuzz, flat) in [
            (ABS_X, -32768, 32767, 16, 128),
            (ABS_Y, -32768, 32767, 16, 128),
            (ABS_RX, -32768, 32767, 16, 128),
            (ABS_RY, -32768, 32767, 16, 128),
            (ABS_Z, 0, 255, 0, 0),
            (ABS_RZ, 0, 255, 0, 0),
            (ABS_HAT0X, -1, 1, 0, 0),
            (ABS_HAT0Y, -1, 1, 0, 0),
        ] {
            let mut abs = UinputAbsSetup {
                code,
                absinfo: AbsInfo {
                    value: 0,
                    minimum,
                    maximum,
                    fuzz,
                    flat,
                    resolution: 0,
                },
            };
            unsafe {
                ioctl_struct(
                    fd.as_raw_fd(),
                    libc::_IOW::<UinputAbsSetup>(UINPUT_IOCTL_BASE, 4),
                    &mut abs,
                )?;
            }
        }
        let phys = CString::new("lemogamepad/uinput").unwrap();
        unsafe {
            if libc::ioctl(
                fd.as_raw_fd(),
                libc::_IOW::<*const libc::c_char>(UINPUT_IOCTL_BASE, 108),
                phys.as_ptr(),
            ) < 0
            {
                return Err(format!("UI_SET_PHYS failed ({})", last_error()));
            }
            if libc::ioctl(fd.as_raw_fd(), libc::_IO(UINPUT_IOCTL_BASE, 1)) < 0 {
                return Err(format!("UI_DEV_CREATE failed ({})", last_error()));
            }
        }

        let running = Arc::new(AtomicBool::new(true));
        let ff = Arc::new(Mutex::new(FfState {
            gain: 0xFFFF,
            ..FfState::default()
        }));
        let (rumble_error, ff_thread) = start_ff(
            fd.as_raw_fd(),
            running.clone(),
            ff.clone(),
            rumble,
            slot,
        );

        Ok(Self {
            fd,
            error: String::new(),
            rumble_error,
            ok: true,
            kind: kind.to_string(),
            buttons,
            written: HashMap::new(),
            running,
            ff,
            ff_thread: Some(ff_thread),
        })
    }

    fn put(&mut self, kind: u16, code: u16, value: i32) -> io::Result<()> {
        if self.written.get(&(kind, code)) == Some(&value) {
            return Ok(());
        }
        let event = InputEvent {
            tv_sec: 0,
            tv_usec: 0,
            kind,
            code,
            value,
        };
        let written = unsafe {
            libc::write(
                self.fd.as_raw_fd(),
                &event as *const InputEvent as *const libc::c_void,
                mem::size_of::<InputEvent>(),
            )
        };
        if written < 0 {
            return Err(io::Error::last_os_error());
        }
        self.written.insert((kind, code), value);
        Ok(())
    }
}

impl VirtualPad for LinuxVirtualPad {
    fn apply(&mut self, state: &InputState) {
        if !self.ok {
            return;
        }
        let mut changed = false;
        for index in 0..self.buttons.map.len() {
            let Some(code) = self.buttons.map[index] else { continue };
            let pressed = state.buttons & (1 << index) != 0;
            if self.put(EV_KEY, code, i32::from(pressed)).is_err() {
                self.fail("uinput write failed");
                return;
            }
            changed = true;
        }

        let hat_x = i32::from(state.pressed(crate::proto::BUTTON_RIGHT))
            - i32::from(state.pressed(crate::proto::BUTTON_LEFT));
        let hat_y = i32::from(state.pressed(crate::proto::BUTTON_DOWN))
            - i32::from(state.pressed(crate::proto::BUTTON_UP));
        for (code, value) in [
            (ABS_X, i32::from(state.lx)),
            (ABS_Y, i32::from(-i32::from(state.ly))),
            (ABS_RX, i32::from(state.rx)),
            (ABS_RY, i32::from(-i32::from(state.ry))),
            (ABS_Z, (state.left_trigger() * 255.0) as i32),
            (ABS_RZ, (state.right_trigger() * 255.0) as i32),
            (ABS_HAT0X, hat_x),
            (ABS_HAT0Y, hat_y),
        ] {
            if self.put(EV_ABS, code, value.clamp(-32768, 32767)).is_err() {
                self.fail("uinput write failed");
                return;
            }
            changed = true;
        }
        if changed {
            let syn = InputEvent {
                tv_sec: 0,
                tv_usec: 0,
                kind: EV_SYN,
                code: SYN_REPORT,
                value: 0,
            };
            unsafe {
                libc::write(
                    self.fd.as_raw_fd(),
                    &syn as *const InputEvent as *const libc::c_void,
                    mem::size_of::<InputEvent>(),
                );
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
        self.running.store(false, Ordering::Relaxed);
        if self.ok {
            let neutral = InputState::default();
            self.apply(&neutral);
            unsafe {
                libc::ioctl(self.fd.as_raw_fd(), libc::_IO(UINPUT_IOCTL_BASE, 2));
            }
            self.ok = false;
        }
        if let Some(thread) = self.ff_thread.take() {
            let _ = thread.join();
        }
        self.ff.lock().unwrap_or_else(|err| err.into_inner()).last = (0, 0);
        let _ = self.kind.as_str();
    }
}

impl LinuxVirtualPad {
    fn fail(&mut self, message: &str) {
        self.ok = false;
        self.error = format!("{message}: {}", last_error());
    }
}

impl Drop for LinuxVirtualPad {
    fn drop(&mut self) {
        self.close();
    }
}

fn button_map(kind: &str) -> Buttons {
    // Numeric evdev codes are stable even though symbolic aliases differ.
    // These match pc/linux_pad.py's resolved BTN_* values exactly.
    let mut map = [None; 17];
    map[ButtonIndex::SELECT as usize] = Some(0x13A); // BTN_SELECT
    map[ButtonIndex::L3 as usize] = Some(0x13D); // BTN_THUMBL
    map[ButtonIndex::R3 as usize] = Some(0x13E); // BTN_THUMBR
    map[ButtonIndex::START as usize] = Some(0x13B); // BTN_START
    map[ButtonIndex::L2 as usize] = None; // L2/R2 are analog axes on Linux
    map[ButtonIndex::R2 as usize] = None;
    map[ButtonIndex::L1 as usize] = Some(0x136); // BTN_TL
    map[ButtonIndex::R1 as usize] = Some(0x137); // BTN_TR
    map[ButtonIndex::PS as usize] = Some(0x13C); // BTN_MODE
    map[ButtonIndex::CROSS as usize] = Some(0x130); // BTN_SOUTH / BTN_A
    map[ButtonIndex::CIRCLE as usize] = Some(0x131); // BTN_EAST / BTN_B

    let (square, triangle) = if kind == "ds4" {
        (0x134, 0x133) // BTN_WEST, BTN_NORTH
    } else {
        (0x133, 0x134) // BTN_X, BTN_Y (xpad layout)
    };
    map[ButtonIndex::SQUARE as usize] = Some(square);
    map[ButtonIndex::TRIANGLE as usize] = Some(triangle);

    Buttons { map }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uinput_event_types_include_abs() {
        assert!(UINPUT_EVENT_TYPES.contains(&EV_ABS));
        assert!(UINPUT_EVENT_TYPES.contains(&EV_KEY));
    }

    #[test]
    fn button_codes_match_python_linux_pad() {
        let map = button_map("x360").map;
        for (index, expected) in [
            (ButtonIndex::SELECT, Some(0x13A)),
            (ButtonIndex::L3, Some(0x13D)),
            (ButtonIndex::R3, Some(0x13E)),
            (ButtonIndex::START, Some(0x13B)),
            (ButtonIndex::L2, None),
            (ButtonIndex::R2, None),
            (ButtonIndex::L1, Some(0x136)),
            (ButtonIndex::R1, Some(0x137)),
            (ButtonIndex::PS, Some(0x13C)),
            (ButtonIndex::CROSS, Some(0x130)),
            (ButtonIndex::CIRCLE, Some(0x131)),
            (ButtonIndex::SQUARE, Some(0x133)),
            (ButtonIndex::TRIANGLE, Some(0x134)),
        ] {
            assert_eq!(map[index as usize], expected, "{index:?}");
        }
    }
}

unsafe fn set_bit(fd: i32, number: u32, bit: i32) -> Result<(), String> {
    // The UI_SET_*BIT ioctls are declared with `_IOW(..., int)` but the kernel
    // reads the bit from the ioctl argument itself, not from a user pointer.
    // Passing `&bit` makes every call fail with EINVAL.
    let request = libc::_IOW::<libc::c_int>(UINPUT_IOCTL_BASE, number);
    if libc::ioctl(fd, request, bit as libc::c_ulong) < 0 {
        Err(format!(
            "uinput bit setup failed (request {number}, bit {bit}: {})",
            last_error()
        ))
    } else {
        Ok(())
    }
}

unsafe fn ioctl_struct<T>(fd: i32, request: libc::Ioctl, value: &mut T) -> Result<(), String> {
    if libc::ioctl(fd, request, value as *mut T) < 0 {
        Err(format!("uinput ioctl failed ({})", last_error()))
    } else {
        Ok(())
    }
}

fn last_error() -> String {
    io::Error::last_os_error().to_string()
}

fn start_ff(
    uinput_fd: i32,
    running: Arc<AtomicBool>,
    ff: Arc<Mutex<FfState>>,
    rumble: Sender<RumbleEvent>,
    slot: usize,
) -> (String, JoinHandle<()>) {
    let event_fd = open_event_node(uinput_fd);
    let rumble_error = if event_fd.is_none() {
        "cannot read the created /dev/input/event* node (needed for rumble)".to_string()
    } else {
        String::new()
    };
    let thread = thread::spawn(move || {
        let mut pollfds = vec![libc::pollfd {
            fd: uinput_fd,
            events: libc::POLLIN,
            revents: 0,
        }];
        if let Some(fd) = &event_fd {
            pollfds.push(libc::pollfd {
                fd: fd.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            });
        }
        while running.load(Ordering::Relaxed) {
            let ready = unsafe {
                libc::poll(
                    pollfds.as_mut_ptr(),
                    pollfds.len() as libc::nfds_t,
                    100,
                )
            };
            if ready <= 0 {
                expire_effects(&ff, &rumble, slot);
                continue;
            }
            if pollfds[0].revents & libc::POLLIN != 0 {
                read_raw_requests(uinput_fd, &ff, &rumble, slot);
            }
            if pollfds.len() > 1 && pollfds[1].revents & libc::POLLIN != 0 {
                if let Some(fd) = &event_fd {
                    read_device_events(fd.as_raw_fd(), &ff, &rumble, slot);
                }
            }
            expire_effects(&ff, &rumble, slot);
        }
    });
    (rumble_error, thread)
}

fn open_event_node(uinput_fd: i32) -> Option<File> {
    let mut name = [0u8; 64];
    if unsafe {
        libc::ioctl(
            uinput_fd,
            libc::_IOR::<[u8; 64]>(UINPUT_IOCTL_BASE, 44),
            name.as_mut_ptr(),
        )
    } < 0
    {
        return None;
    }
    let sysname = CStr::from_bytes_until_nul(&name).ok()?.to_str().ok()?;
    let dir = format!("/sys/devices/virtual/input/{sysname}");
    // The event node appears just after UI_DEV_CREATE; give the kernel a short
    // grace period instead of reporting rumble as unavailable on a race.
    for _ in 0..20 {
        if let Some(fd) = try_open_event_node(&dir) {
            return Some(fd);
        }
        thread::sleep(Duration::from_millis(25));
    }
    None
}

fn try_open_event_node(dir: &str) -> Option<File> {
    let entries = fs::read_dir(dir).ok()?;
    for entry in entries.flatten() {
        let file = entry.file_name();
        let text = file.to_string_lossy();
        if text.starts_with("event") {
            let path = format!("/dev/input/{text}");
            if let Ok(fd) = fs::OpenOptions::new().read(true).open(&path) {
                unsafe {
                    libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, libc::O_NONBLOCK);
                }
                return Some(fd);
            }
        }
    }
    None
}

fn read_raw_requests(fd: i32, ff: &Arc<Mutex<FfState>>, rumble: &Sender<RumbleEvent>, slot: usize) {
    let size = mem::size_of::<InputEvent>();
    let mut buffer = [0u8; 24 * 32];
    let count = unsafe {
        libc::read(
            fd,
            buffer.as_mut_ptr() as *mut libc::c_void,
            buffer.len(),
        )
    };
    if count <= 0 {
        return;
    }
    for chunk in buffer[..count as usize].chunks_exact(size) {
        let event = unsafe { std::ptr::read_unaligned(chunk.as_ptr() as *const InputEvent) };
        if event.kind != EV_UINPUT {
            continue;
        }
        if event.code == UI_FF_UPLOAD {
            let mut upload = UinputUpload {
                request_id: event.value as u32,
                retval: 0,
                effect: FfEffect::default(),
                old: FfEffect::default(),
            };
            unsafe {
                if libc::ioctl(
                    fd,
                    libc::_IOWR::<UinputUpload>(UINPUT_IOCTL_BASE, 200),
                    &mut upload,
                ) == 0
                {
                    if upload.effect.kind == FF_RUMBLE {
                        let strong = u16::from_le_bytes([upload.effect.u[0], upload.effect.u[1]]);
                        let weak = u16::from_le_bytes([upload.effect.u[2], upload.effect.u[3]]);
                        let length = upload.effect.replay.length;
                        let ends_at = if length == 0 {
                            None
                        } else {
                            Some(Instant::now() + Duration::from_millis(u64::from(length)))
                        };
                        ff.lock()
                            .unwrap_or_else(|err| err.into_inner())
                            .effects
                            .insert(
                                i32::from(upload.effect.id),
                                Effect {
                                    weak,
                                    strong,
                                    playing: false,
                                    length,
                                    ends_at,
                                },
                            );
                    }
                    upload.retval = 0;
                    libc::ioctl(
                        fd,
                        libc::_IOW::<UinputUpload>(UINPUT_IOCTL_BASE, 201),
                        &mut upload,
                    );
                }
            }
        } else if event.code == UI_FF_ERASE {
            let mut erase = UinputErase {
                request_id: event.value as u32,
                retval: 0,
                effect_id: 0,
            };
            unsafe {
                if libc::ioctl(
                    fd,
                    libc::_IOWR::<UinputErase>(UINPUT_IOCTL_BASE, 202),
                    &mut erase,
                ) == 0
                {
                    ff.lock()
                        .unwrap_or_else(|err| err.into_inner())
                        .effects
                        .remove(&(erase.effect_id as i32));
                    erase.retval = 0;
                    libc::ioctl(
                        fd,
                        libc::_IOW::<UinputErase>(UINPUT_IOCTL_BASE, 203),
                        &mut erase,
                    );
                    publish(ff, rumble, slot);
                }
            }
        }
    }
}

fn read_device_events(
    fd: i32,
    ff: &Arc<Mutex<FfState>>,
    rumble: &Sender<RumbleEvent>,
    slot: usize,
) {
    let size = mem::size_of::<InputEvent>();
    let mut buffer = [0u8; 24 * 64];
    let count = unsafe { libc::read(fd, buffer.as_mut_ptr() as *mut libc::c_void, buffer.len()) };
    if count <= 0 {
        return;
    }
    for chunk in buffer[..count as usize].chunks_exact(size) {
        let event = unsafe { std::ptr::read_unaligned(chunk.as_ptr() as *const InputEvent) };
        if event.kind == EV_FF {
            if event.code == FF_GAIN {
                ff.lock().unwrap_or_else(|err| err.into_inner()).gain =
                    (event.value as u16).min(0xFFFF);
                publish(ff, rumble, slot);
                continue;
            }
            let mut state = ff.lock().unwrap_or_else(|err| err.into_inner());
            if let Some(effect) = state.effects.get_mut(&i32::from(event.code)) {
                effect.playing = event.value != 0;
                effect.ends_at = if effect.playing && effect.length > 0 {
                    effect
                        .ends_at
                        .or(Some(Instant::now() + Duration::from_millis(u64::from(effect.length))))
                } else {
                    None
                };
            }
            drop(state);
            publish(ff, rumble, slot);
        } else if event.kind == EV_UINPUT {
            // Some kernels deliver upload requests on the event node too.
            let _ = (event, ff, rumble, slot);
        }
    }
}

fn expire_effects(ff: &Arc<Mutex<FfState>>, rumble: &Sender<RumbleEvent>, slot: usize) {
    let now = Instant::now();
    let changed = ff
        .lock()
        .unwrap_or_else(|err| err.into_inner())
        .effects
        .values_mut()
        .any(|effect| {
            if effect.playing && effect.ends_at.is_some_and(|end| now >= end) {
                effect.playing = false;
                effect.ends_at = None;
                true
            } else {
                false
            }
        });
    if changed {
        publish(ff, rumble, slot);
    }
}

fn publish(ff: &Arc<Mutex<FfState>>, rumble: &Sender<RumbleEvent>, slot: usize) {
    let mut state = ff.lock().unwrap_or_else(|err| err.into_inner());
    let (mut weak, mut strong) = (0u32, 0u32);
    for effect in state.effects.values().filter(|effect| effect.playing) {
        weak = weak.max(u32::from(effect.weak));
        strong = strong.max(u32::from(effect.strong));
    }
    if state.gain < 0xFFFF {
        weak = weak * u32::from(state.gain) / 0xFFFF;
        strong = strong * u32::from(state.gain) / 0xFFFF;
    }
    let out = ((weak * 255 / 0xFFFF) as u8, (strong * 255 / 0xFFFF) as u8);
    if out == state.last {
        return;
    }
    state.last = out;
    drop(state);
    let _ = rumble.send(RumbleEvent {
        slot,
        small: out.0,
        large: out.1,
    });
}

#[allow(dead_code)]
fn axis_to_wire(value: i16) -> u8 {
    ((axis(value) + 1.0) * 127.5).clamp(0.0, 255.0) as u8
}
