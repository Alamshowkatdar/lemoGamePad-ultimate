//! Wire protocol. Layouts and constants must stay identical to the Python
//! server and the Android app; this is byte-for-byte compatible.

pub const MAGIC: &[u8; 4] = b"VPAD";
pub const VERSION: u8 = 1;

pub const MSG_INPUT: u8 = 1;
pub const MSG_DISCOVER: u8 = 2;
pub const MSG_OFFER: u8 = 3;
pub const MSG_PING: u8 = 5;
pub const MSG_PONG: u8 = 6;
pub const MSG_RUMBLE: u8 = 7;
pub const MSG_SLOT_REQUEST: u8 = 8;
pub const MSG_SLOT_ASK: u8 = 9;
pub const MSG_SLOT_VOTE: u8 = 10;
pub const MSG_SLOT_RESULT: u8 = 11;
pub const MSG_PARTY_LOCK: u8 = 12;

pub const SLOT_ACCEPTED: u8 = 1;
pub const SLOT_DECLINED: u8 = 2;
pub const SLOT_TIMEOUT: u8 = 3;
pub const SLOT_UNAVAILABLE: u8 = 4;
pub const SLOT_CANCELLED: u8 = 5;

pub const HEADER_SIZE: usize = 12;
pub const INPUT_SIZE: usize = 28;
pub const WIRE_INPUT_SIZE: usize = 29;

pub const BUTTON_SELECT: u32 = 1 << 0;
pub const BUTTON_L3: u32 = 1 << 1;
pub const BUTTON_R3: u32 = 1 << 2;
pub const BUTTON_START: u32 = 1 << 3;
pub const BUTTON_UP: u32 = 1 << 4;
pub const BUTTON_RIGHT: u32 = 1 << 5;
pub const BUTTON_DOWN: u32 = 1 << 6;
pub const BUTTON_LEFT: u32 = 1 << 7;
pub const BUTTON_L2: u32 = 1 << 8;
pub const BUTTON_R2: u32 = 1 << 9;
pub const BUTTON_L1: u32 = 1 << 10;
pub const BUTTON_R1: u32 = 1 << 11;
pub const BUTTON_TRIANGLE: u32 = 1 << 12;
pub const BUTTON_CIRCLE: u32 = 1 << 13;
pub const BUTTON_CROSS: u32 = 1 << 14;
pub const BUTTON_SQUARE: u32 = 1 << 15;
pub const BUTTON_PS: u32 = 1 << 16;

/// Index into the 32-bit button word, used by pad backends.
pub struct ButtonIndex;
impl ButtonIndex {
    pub const SELECT: usize = 0;
    pub const L3: usize = 1;
    pub const R3: usize = 2;
    pub const START: usize = 3;
    pub const UP: usize = 4;
    pub const RIGHT: usize = 5;
    pub const DOWN: usize = 6;
    pub const LEFT: usize = 7;
    pub const L2: usize = 8;
    pub const R2: usize = 9;
    pub const L1: usize = 10;
    pub const R1: usize = 11;
    pub const TRIANGLE: usize = 12;
    pub const CIRCLE: usize = 13;
    pub const CROSS: usize = 14;
    pub const SQUARE: usize = 15;
    pub const PS: usize = 16;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Header {
    pub version: u8,
    pub mtype: u8,
    pub seq: u16,
    pub token: u32,
}

impl Header {
    pub fn parse(data: &[u8]) -> Option<Self> {
        if data.len() < HEADER_SIZE || &data[..4] != MAGIC {
            return None;
        }
        Some(Self {
            version: data[4],
            mtype: data[5],
            seq: u16::from_le_bytes([data[6], data[7]]),
            token: u32::from_le_bytes([data[8], data[9], data[10], data[11]]),
        })
    }

    pub fn pack(mtype: u8, seq: u16, token: u32) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_SIZE);
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        out.push(mtype);
        out.extend_from_slice(&seq.to_le_bytes());
        out.extend_from_slice(&token.to_le_bytes());
        out
    }
}

/// The 28-byte fixed INPUT struct plus the optional 29th `want_slot` byte.
#[derive(Clone, Copy, Debug, Default)]
pub struct InputState {
    pub buttons: u32,
    pub lx: i16,
    pub ly: i16,
    pub rx: i16,
    pub ry: i16,
    pub l2: u8,
    pub r2: u8,
    pub battery: u8,
    pub pad_hint: u8,
    pub want_slot: u8,
    pub seq: u16,
}

impl InputState {
    pub fn parse(data: &[u8], header: Header) -> Option<Self> {
        if data.len() < INPUT_SIZE {
            return None;
        }
        let mut pad_hint = data[27];
        if pad_hint != 0 && pad_hint != 1 {
            pad_hint = 0;
        }
        Some(Self {
            buttons: u32::from_le_bytes([data[12], data[13], data[14], data[15]]),
            lx: i16::from_le_bytes([data[16], data[17]]),
            ly: i16::from_le_bytes([data[18], data[19]]),
            rx: i16::from_le_bytes([data[20], data[21]]),
            ry: i16::from_le_bytes([data[22], data[23]]),
            l2: data[24],
            r2: data[25],
            battery: data[26],
            pad_hint,
            want_slot: if data.len() > INPUT_SIZE { data[INPUT_SIZE] } else { 0 },
            seq: header.seq,
        })
    }

    pub fn pressed(&self, bit: u32) -> bool {
        self.buttons & bit != 0
    }

    pub fn left_trigger(&self) -> f32 {
        self.l2.max(if self.pressed(BUTTON_L2) { 255 } else { 0 }) as f32 / 255.0
    }

    pub fn right_trigger(&self) -> f32 {
        self.r2.max(if self.pressed(BUTTON_R2) { 255 } else { 0 }) as f32 / 255.0
    }
}

/// Signed 16-bit stick value to the -1..1 range used by both pad backends.
pub fn axis(value: i16) -> f32 {
    if value >= 0 {
        (f32::from(value) / 32767.0).min(1.0)
    } else {
        (f32::from(value) / 32768.0).max(-1.0)
    }
}

/// Wrap-aware 16-bit sequence comparison.
pub fn seq_newer(seq: u16, previous: Option<u16>) -> bool {
    match previous {
        None => true,
        Some(previous) => {
            let delta = seq.wrapping_sub(previous);
            delta > 0 && delta < 0x8000
        }
    }
}

pub fn push_u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

pub fn push_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

pub fn u32_at(data: &[u8], offset: usize) -> Option<u32> {
    let bytes = data.get(offset..offset + 4)?;
    Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

/// Server -> phone discovery reply.
pub fn offer(seq: u16, token: u32, name: &str, ds4: bool, addresses: &[[u8; 4]]) -> Vec<u8> {
    let name = name.as_bytes();
    let name_len = name.len().min(32);
    let mut out = Header::pack(MSG_OFFER, seq, token);
    out.push(name_len as u8);
    out.extend_from_slice(&name[..name_len]);
    out.push(u8::from(ds4));
    out.push(addresses.len().min(8) as u8);
    for address in addresses.iter().take(8) {
        out.extend_from_slice(address);
    }
    out
}

/// Server -> phone latency/status reply. `clock` is echoed verbatim when the
/// phone supplied it. Unknown tokens get the 12-byte header plus the clock.
pub fn pong(
    seq: u16,
    token: u32,
    clock: Option<[u8; 4]>,
    loss_permille: Option<u16>,
    assigned_slot: Option<u8>,
    party_locked: bool,
    is_host: bool,
) -> Vec<u8> {
    let mut out = Header::pack(MSG_PONG, seq, token);
    if let Some(clock) = clock {
        out.extend_from_slice(&clock);
    }
    if let Some(loss) = loss_permille {
        push_u16(&mut out, loss);
        out.push(assigned_slot.unwrap_or(0));
        out.push(u8::from(party_locked));
        out.push(u8::from(is_host));
    }
    out
}

pub fn rumble(token: u32, small: u8, large: u8) -> Vec<u8> {
    let mut out = Header::pack(MSG_RUMBLE, 0, token);
    out.extend_from_slice(&[small, large]);
    out
}

pub fn slot_ask(token: u32, request_id: u32, from_slot: u8) -> Vec<u8> {
    let mut out = Header::pack(MSG_SLOT_ASK, 0, token);
    push_u32(&mut out, request_id);
    out.push(from_slot);
    out
}

pub fn slot_result(token: u32, request_id: u32, code: u8, slot: u8) -> Vec<u8> {
    let mut out = Header::pack(MSG_SLOT_RESULT, 0, token);
    push_u32(&mut out, request_id);
    out.extend_from_slice(&[code, slot]);
    out
}

/// `vpad://connect?h=…&p=…&n=…` pairing URI. `auto=true` adds `a=1`.
pub fn qr_payload(host: &str, port: u16, name: &str, auto: bool) -> String {
    let mut query = format!("h={}&p={}&n={}", uri_escape(host), port, uri_escape(name));
    if auto {
        query.push_str("&a=1");
    }
    format!("vpad://connect?{query}")
}

fn uri_escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_is_12_bytes_little_endian() {
        let packet = Header::pack(MSG_INPUT, 0x1234, 0x89ABCDEF);
        assert_eq!(packet.len(), 12);
        assert_eq!(&packet[..4], MAGIC);
        assert_eq!(packet[4], VERSION);
        assert_eq!(u16::from_le_bytes([packet[6], packet[7]]), 0x1234);
        assert_eq!(u32::from_le_bytes(packet[8..12].try_into().unwrap()), 0x89ABCDEF);
    }

    #[test]
    fn input_layout_and_want_slot() {
        let mut packet = Header::pack(MSG_INPUT, 7, 42);
        packet.resize(INPUT_SIZE, 0);
        packet[12..16].copy_from_slice(&0x0000_0100u32.to_le_bytes());
        packet[16..18].copy_from_slice(&(-32768i16).to_le_bytes());
        packet[24] = 200;
        packet[26] = 88;
        packet[27] = 9;
        packet.push(3);
        let state = InputState::parse(&packet, Header::parse(&packet).unwrap()).unwrap();
        assert_eq!(state.seq, 7);
        assert!(state.pressed(BUTTON_L2));
        assert_eq!(state.l2, 200);
        assert_eq!(state.battery, 88);
        assert_eq!(state.pad_hint, 0);
        assert_eq!(state.want_slot, 3);
        assert_eq!(axis(state.lx), -1.0);
    }

    #[test]
    fn wire_input_golden_bytes_and_offsets() {
        // Same 29-byte vector as pc/test_protocol.py: header, 28-byte INPUT,
        // then the optional want_slot byte. Never reorder these fields.
        const GOLDEN: [u8; WIRE_INPUT_SIZE] = [
            0x56, 0x50, 0x41, 0x44, VERSION, MSG_INPUT, 0x02, 0x01, //  0..11 header
            0xD4, 0xC3, 0xB2, 0xA1,
            0x00, 0x04, 0x01, 0x00, // 12..15 buttons
            0x00, 0x80, 0xD2, 0x04, // 16..19 left stick
            0xFF, 0xFF, 0xFF, 0x7F, // 20..23 right stick
            0xC8, 0x4D, 0x5B, 0x01, // 24..27 triggers, battery, pad hint
            0x03, //                      28 want_slot
        ];
        assert_eq!(HEADER_SIZE, 12);
        assert_eq!(INPUT_SIZE, 28);
        assert_eq!(WIRE_INPUT_SIZE, 29);
        assert_eq!(GOLDEN[..4], MAGIC[..]);
        assert_eq!(u16::from_le_bytes([GOLDEN[6], GOLDEN[7]]), 0x0102);
        assert_eq!(u32::from_le_bytes(GOLDEN[8..12].try_into().unwrap()), 0xA1B2C3D4);
        assert_eq!(u32::from_le_bytes(GOLDEN[12..16].try_into().unwrap()), 0x0001_0400);
        assert_eq!(i16::from_le_bytes([GOLDEN[16], GOLDEN[17]]), -32768);
        assert_eq!(i16::from_le_bytes([GOLDEN[18], GOLDEN[19]]), 1234);
        assert_eq!(i16::from_le_bytes([GOLDEN[20], GOLDEN[21]]), -1);
        assert_eq!(i16::from_le_bytes([GOLDEN[22], GOLDEN[23]]), 32767);
        assert_eq!(&GOLDEN[24..28], &[200, 77, 91, 1]);
        assert_eq!(GOLDEN[28], 3);

        let header = Header::parse(&GOLDEN).unwrap();
        let state = InputState::parse(&GOLDEN, header).unwrap();
        assert_eq!(header.seq, 0x0102);
        assert_eq!(header.token, 0xA1B2C3D4);
        assert_eq!(state.buttons, 0x0001_0400);
        assert_eq!((state.lx, state.ly, state.rx, state.ry), (-32768, 1234, -1, 32767));
        assert_eq!((state.l2, state.r2, state.battery), (200, 77, 91));
        assert_eq!(state.pad_hint, 1);
        assert_eq!(state.want_slot, 3);

        // A 1.3-era frame still parses with no trailing slot request.
        let legacy = InputState::parse(&GOLDEN[..INPUT_SIZE], header).unwrap();
        assert_eq!(legacy.want_slot, 0);
        assert_eq!(legacy.buttons, state.buttons);
    }

    #[test]
    fn sequence_wrap() {
        assert!(seq_newer(0, Some(65535)));
        assert!(!seq_newer(65535, Some(0)));
        assert!(!seq_newer(5, Some(5)));
    }

    #[test]
    fn qr_payload_matches_python_urlencode() {
        assert_eq!(
            qr_payload("192.168.0.102", 9876, "my pc", false),
            "vpad://connect?h=192.168.0.102&p=9876&n=my+pc"
        );
        assert_eq!(
            qr_payload("192.168.0.102", 9876, "PC", true),
            "vpad://connect?h=192.168.0.102&p=9876&n=PC&a=1"
        );
    }
}
