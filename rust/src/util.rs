//! Small helpers shared by the UDP, HTTP and video servers.

use std::collections::HashSet;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static RANDOM_COUNTER: AtomicU64 = AtomicU64::new(0);

pub fn now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs_f64()
}

pub fn monotonic() -> f64 {
    now()
}

/// Optional machine-readable status file consumed by the desktop panel. The
/// network protocol stays untouched; this is only a local IPC surface.
pub fn status_file_path() -> PathBuf {
    if let Ok(path) = std::env::var("LEMO_GAMEPAD_STATUS_FILE") {
        if !path.trim().is_empty() {
            return PathBuf::from(path);
        }
    }
    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .map(PathBuf::from)
        .unwrap_or_default();
    home.join(".lmgb-server-status.json")
}

pub fn json_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    out.push('"');
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

pub fn random_u32() -> u32 {
    let mut bytes = [0u8; 4];
    if read_random(&mut bytes).is_ok() {
        let value = u32::from_ne_bytes(bytes);
        if value != 0 {
            return value;
        }
    }
    let counter = RANDOM_COUNTER.fetch_add(1, Ordering::Relaxed) as u32;
    let seed = (now() * 1_000_000.0) as u32 ^ std::process::id().wrapping_mul(2654435761);
    seed.wrapping_add(counter.wrapping_mul(2246822519)) | 1
}

fn read_random(bytes: &mut [u8]) -> io::Result<()> {
    #[cfg(unix)]
    {
        let mut file = File::open("/dev/urandom")?;
        file.read_exact(bytes)
    }
    #[cfg(windows)]
    {
        use std::ffi::c_void;
        type RtlGenRandom = unsafe extern "system" fn(*mut c_void, u32) -> u8;
        unsafe {
            let name = std::ffi::CString::new("SystemFunction036").unwrap();
            let module = windows_sys_module("advapi32.dll");
            let address = windows_proc(module, name.as_ptr());
            if address.is_null() {
                return Err(io::Error::other("RtlGenRandom unavailable"));
            }
            let function: RtlGenRandom = std::mem::transmute(address);
            if function(bytes.as_mut_ptr() as *mut c_void, bytes.len() as u32) == 0 {
                return Err(io::Error::other("RtlGenRandom failed"));
            }
            Ok(())
        }
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = bytes;
        Err(io::Error::other("no random source"))
    }
}

#[cfg(windows)]
unsafe fn windows_sys_module(name: &str) -> *mut std::ffi::c_void {
    use std::ffi::CString;
    let wide: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe { windows_sys_load(wide.as_ptr()) }
}

#[cfg(windows)]
unsafe extern "system" {
    #[link_name = "LoadLibraryW"]
    fn windows_sys_load(name: *const u16) -> *mut std::ffi::c_void;
    #[link_name = "GetProcAddress"]
    fn windows_get_proc(module: *mut std::ffi::c_void, name: *const i8) -> *mut std::ffi::c_void;
}

#[cfg(windows)]
unsafe fn windows_proc(module: *mut std::ffi::c_void, name: *const i8) -> *mut std::ffi::c_void {
    unsafe { windows_get_proc(module, name) }
}

pub fn hostname() -> String {
    #[cfg(unix)]
    {
        use std::ffi::CStr;
        let mut buffer = [0i8; 256];
        unsafe {
            if libc::gethostname(buffer.as_mut_ptr(), buffer.len()) == 0 {
                return CStr::from_ptr(buffer.as_ptr())
                    .to_string_lossy()
                    .chars()
                    .take(32)
                    .collect();
            }
        }
    }
    std::env::var("COMPUTERNAME")
        .unwrap_or_else(|_| "pc".to_string())
        .chars()
        .take(32)
        .collect()
}

/// IPv4 addresses suitable for QR codes, preferred address first.
pub fn local_ips() -> Vec<String> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();

    // The default-route address is the one most phones need.
    if let Ok(socket) = UdpSocket::bind("0.0.0.0:0") {
        if socket.connect("8.8.8.8:80").is_ok() {
            if let Ok(SocketAddr::V4(addr)) = socket.local_addr() {
                add_ip(&mut out, &mut seen, *addr.ip());
            }
        }
    }

    // Match the Python server: the route address plus addresses the host name
    // resolves to. Extra VPN/Docker interfaces are deliberately not advertised.
    if let Ok(addresses) = (hostname().as_str(), 0).to_socket_addrs() {
        for address in addresses {
            if let SocketAddr::V4(addr) = address {
                let ip = *addr.ip();
                if !ip.is_loopback() {
                    add_ip(&mut out, &mut seen, ip);
                }
            }
        }
    }

    if out.is_empty() {
        add_ip(&mut out, &mut seen, Ipv4Addr::new(127, 0, 0, 1));
    }
    out
}

fn add_ip(out: &mut Vec<String>, seen: &mut HashSet<Ipv4Addr>, ip: Ipv4Addr) {
    if ip.is_loopback() || ip.is_unspecified() || !seen.insert(ip) {
        return;
    }
    out.push(ip.to_string());
}

pub fn pack_addresses(addresses: &[String]) -> Vec<[u8; 4]> {
    let mut out: Vec<[u8; 4]> = Vec::new();
    for text in addresses {
        if let Ok(IpAddr::V4(ip)) = text.parse() {
            let octets = ip.octets();
            if !out.contains(&octets) {
                out.push(octets);
            }
            if out.len() == 8 {
                break;
            }
        }
    }
    out
}

pub fn valid_web_key(key: &str) -> bool {
    (8..=64).contains(&key.len())
        && key
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
}

pub fn generate_web_key() -> String {
    let mut bytes = [0u8; 8];
    if read_random(&mut bytes).is_err() {
        bytes = random_u32().to_le_bytes().iter().copied().chain(random_u32().to_le_bytes()).collect::<Vec<_>>().try_into().unwrap();
    }
    base64_url(&bytes).trim_end_matches('=').to_string()
}

pub fn resolve_web_key(explicit: &str, path: &str) -> String {
    if !explicit.is_empty() {
        return explicit.to_string();
    }
    if path.is_empty() {
        return generate_web_key();
    }
    let file = Path::new(path);
    if let Ok(text) = fs::read_to_string(file) {
        let key = text.trim();
        if valid_web_key(key) {
            return key.to_string();
        }
        println!("Ignoring invalid browser key in {path}; generating a new one.");
    } else if !file.exists() {
        // first run: create below
    } else {
        // Unreadable key should degrade to a random one, not stop the server.
        return generate_web_key();
    }
    let key = generate_web_key();
    if let Some(parent) = file.parent() {
        if !parent.as_os_str().is_empty() {
            let _ = fs::create_dir_all(parent);
        }
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        if let Ok(mut handle) = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(file)
        {
            let _ = writeln!(handle, "{key}");
            return key;
        }
    }
    #[cfg(not(unix))]
    if let Ok(mut handle) = OpenOptions::new().write(true).create(true).truncate(true).open(file) {
        let _ = writeln!(handle, "{key}");
        return key;
    }
    println!("Could not save browser key file {path}");
    key
}

pub fn base64(data: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let a = chunk[0];
        let b = *chunk.get(1).unwrap_or(&0);
        let c = *chunk.get(2).unwrap_or(&0);
        out.push(TABLE[(a >> 2) as usize] as char);
        out.push(TABLE[(((a & 3) << 4) | (b >> 4)) as usize] as char);
        if chunk.len() > 1 {
            out.push(TABLE[(((b & 15) << 2) | (c >> 6)) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(TABLE[(c & 63) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

pub fn base64_url(data: &[u8]) -> String {
    base64(data).replace('+', "-").replace('/', "_")
}

pub fn sha1(data: &[u8]) -> [u8; 20] {
    let mut message = data.to_vec();
    let bit_len = (data.len() as u64).wrapping_mul(8);
    message.push(0x80);
    while message.len() % 64 != 56 {
        message.push(0);
    }
    message.extend_from_slice(&bit_len.to_be_bytes());

    let mut h = [
        0x67452301u32,
        0xEFCDAB89,
        0x98BADCFE,
        0x10325476,
        0xC3D2E1F0,
    ];
    for chunk in message.chunks_exact(64) {
        let mut words = [0u32; 80];
        for (index, word) in words[..16].iter_mut().enumerate() {
            let bytes = &chunk[index * 4..index * 4 + 4];
            *word = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        }
        for index in 16..80 {
            words[index] = (words[index - 3] ^ words[index - 8] ^ words[index - 14] ^ words[index - 16])
                .rotate_left(1);
        }
        let (mut a, mut b, mut c, mut d, mut e) = (h[0], h[1], h[2], h[3], h[4]);
        for (index, word) in words.iter().enumerate() {
            let (f, k) = match index {
                0..=19 => ((b & c) | ((!b) & d), 0x5A827999),
                20..=39 => (b ^ c ^ d, 0x6ED9EBA1),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1BBCDC),
                _ => (b ^ c ^ d, 0xCA62C1D6),
            };
            let temp = a
                .rotate_left(5)
                .wrapping_add(f)
                .wrapping_add(e)
                .wrapping_add(k)
                .wrapping_add(*word);
            e = d;
            d = c;
            c = b.rotate_left(30);
            b = a;
            a = temp;
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
    }
    let mut out = [0u8; 20];
    for (chunk, word) in out.chunks_exact_mut(4).zip(h) {
        chunk.copy_from_slice(&word.to_be_bytes());
    }
    out
}

pub fn websocket_accept(key: &str) -> String {
    const GUID: &str = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    base64(&sha1(format!("{key}{GUID}").as_bytes()))
}

pub fn json_escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

fn skip_space(bytes: &[u8], mut index: usize) -> usize {
    while index < bytes.len() && bytes[index].is_ascii_whitespace() {
        index += 1;
    }
    index
}

/// Minimal JSON object lookup good enough for the browser pad's compact messages.
pub fn json_value(text: &str, key: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let needle = format!("\"{key}\"");
    let mut index = 0usize;
    while let Some(found) = find_from(bytes, needle.as_bytes(), index) {
        let mut cursor = skip_space(bytes, found + needle.len());
        if bytes.get(cursor) != Some(&b':') {
            index = found + 1;
            continue;
        }
        cursor = skip_space(bytes, cursor + 1);
        let start = cursor;
        match bytes.get(cursor) {
            Some(b'"') => {
                cursor += 1;
                let mut escaped = false;
                while cursor < bytes.len() {
                    let byte = bytes[cursor];
                    if escaped {
                        escaped = false;
                    } else if byte == b'\\' {
                        escaped = true;
                    } else if byte == b'"' {
                        cursor += 1;
                        break;
                    }
                    cursor += 1;
                }
                return Some(unescape(&text[start..cursor]));
            }
            Some(b't') if text[start..].starts_with("true") => return Some("true".into()),
            Some(b'f') if text[start..].starts_with("false") => return Some("false".into()),
            Some(b'n') if text[start..].starts_with("null") => return Some("null".into()),
            Some(b'-' | b'0'..=b'9') => {
                while cursor < bytes.len()
                    && matches!(bytes[cursor], b'0'..=b'9' | b'-' | b'+' | b'.' | b'e' | b'E')
                {
                    cursor += 1;
                }
                return Some(text[start..cursor].to_string());
            }
            _ => return None,
        }
    }
    None
}

/// Like [`json_value`], but keeps the original JSON literal. Used when a value
/// must be echoed with its exact type (numbers, booleans, `null`, strings or
/// even objects), as the Python browser pad does with ping clocks.
pub fn json_raw_value(text: &str, key: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let needle = format!("\"{key}\"");
    let mut index = 0usize;
    while let Some(found) = find_from(bytes, needle.as_bytes(), index) {
        let mut cursor = skip_space(bytes, found + needle.len());
        if bytes.get(cursor) != Some(&b':') {
            index = found + 1;
            continue;
        }
        cursor = skip_space(bytes, cursor + 1);
        let start = cursor;
        match bytes.get(cursor) {
            Some(b'"') => {
                cursor += 1;
                let mut escaped = false;
                while cursor < bytes.len() {
                    let byte = bytes[cursor];
                    if escaped {
                        escaped = false;
                    } else if byte == b'\\' {
                        escaped = true;
                    } else if byte == b'"' {
                        cursor += 1;
                        return Some(text[start..cursor].to_string());
                    }
                    cursor += 1;
                }
                return None;
            }
            Some(b't') if text[start..].starts_with("true") => return Some("true".into()),
            Some(b'f') if text[start..].starts_with("false") => return Some("false".into()),
            Some(b'n') if text[start..].starts_with("null") => return Some("null".into()),
            Some(b'-' | b'0'..=b'9') => {
                while cursor < bytes.len()
                    && matches!(bytes[cursor], b'0'..=b'9' | b'-' | b'+' | b'.' | b'e' | b'E')
                {
                    cursor += 1;
                }
                return Some(text[start..cursor].to_string());
            }
            Some(open @ (b'{' | b'[')) => {
                let close = if *open == b'{' { b'}' } else { b']' };
                let mut depth = 0usize;
                let mut in_string = false;
                let mut escaped = false;
                while cursor < bytes.len() {
                    let byte = bytes[cursor];
                    if in_string {
                        if escaped {
                            escaped = false;
                        } else if byte == b'\\' {
                            escaped = true;
                        } else if byte == b'"' {
                            in_string = false;
                        }
                    } else if byte == b'"' {
                        in_string = true;
                    } else if byte == *open {
                        depth += 1;
                    } else if byte == close {
                        depth -= 1;
                        if depth == 0 {
                            cursor += 1;
                            return Some(text[start..cursor].to_string());
                        }
                    }
                    cursor += 1;
                }
                return None;
            }
            _ => return None,
        }
    }
    None
}

fn find_from(haystack: &[u8], needle: &[u8], start: usize) -> Option<usize> {
    if needle.is_empty() || start >= haystack.len() {
        return None;
    }
    haystack[start..]
        .windows(needle.len())
        .position(|window| window == needle)
        .map(|offset| start + offset)
}

fn unescape(value: &str) -> String {
    if !value.contains('\\') {
        return value.trim_matches('"').to_string();
    }
    let inner = value.trim_matches('"');
    let mut out = String::with_capacity(inner.len());
    let mut chars = inner.chars();
    while let Some(ch) = chars.next() {
        if ch != '\\' {
            out.push(ch);
            continue;
        }
        match chars.next() {
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('t') => out.push('\t'),
            Some('"') => out.push('"'),
            Some('\\') => out.push('\\'),
            Some(other) => out.push(other),
            None => break,
        }
    }
    out
}

pub fn json_int(value: Option<&str>, fallback: i64) -> i64 {
    let Some(value) = value else { return fallback };
    let Ok(number) = value.parse::<f64>() else { return fallback };
    if !number.is_finite() {
        return fallback;
    }
    number as i64
}

pub fn json_bool(value: Option<&str>, fallback: bool) -> bool {
    match value.map(str::trim) {
        Some("true") => true,
        Some("false") => false,
        Some(value) => json_int(Some(value), i64::from(fallback)) != 0,
        None => fallback,
    }
}

pub fn clamp(value: i64, low: i64, high: i64) -> i64 {
    value.clamp(low, high)
}

pub fn uri_escape(value: &str) -> String {
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

pub fn safe_ip(ip: &str) -> String {
    ip.replace(':', "-").replace('.', "_")
}

pub fn asset_path(name: &str) -> Option<PathBuf> {
    if let Ok(dir) = std::env::var("LEMO_WEB_DIR") {
        let path = Path::new(&dir).join(name);
        if path.is_file() {
            return Some(path);
        }
    }
    if let Ok(current) = std::env::current_exe() {
        if let Some(parent) = current.parent() {
            for candidate in [
                parent.join("web").join(name),
                parent.join("../pc/web").join(name),
                parent.join("../../pc/web").join(name),
            ] {
                if candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
    }
    let relative = Path::new("pc/web").join(name);
    if relative.is_file() {
        return Some(relative);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_raw_value_preserves_json_types() {
        let message = r#"{"t":"p","c":[1,true,null],"ok":false,"s":"hi there"}"#;
        assert_eq!(json_raw_value(message, "c").as_deref(), Some("[1,true,null]"));
        assert_eq!(json_raw_value(message, "ok").as_deref(), Some("false"));
        assert_eq!(json_raw_value(message, "s").as_deref(), Some("\"hi there\""));
        assert_eq!(json_raw_value(message, "missing"), None);
    }

    #[test]
    fn json_value_unescapes_strings() {
        let message = r#"{"n":"a\"b\\c","x":12}"#;
        assert_eq!(json_value(message, "n").as_deref(), Some("a\"b\\c"));
        assert_eq!(json_value(message, "x").as_deref(), Some("12"));
    }

    #[test]
    fn uri_escape_matches_python_quote_plus() {
        assert_eq!(uri_escape("a b/c+d?e"), "a+b%2Fc%2Bd%3Fe");
    }
}
