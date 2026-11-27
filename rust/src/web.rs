//! Browser gamepad HTTP and WebSocket server, wire-compatible with
//! `pc/web_ui.py` and the bundled browser page.

use crate::proto::InputState;
use crate::util::{
    clamp, json_bool, json_escape, json_int, json_raw_value, json_value, websocket_accept,
};
use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Condvar, LazyLock, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const CSP: &str = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self' ws: wss:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const UNAUTHORIZED: &str = "Pairing link is not valid.\nOpen the browser pad URL printed by lemoGamePad-server.\n";
const MAX_PAYLOAD: usize = 64 * 1024;
const MAX_SCREEN_FRAME: usize = 4 * 1024 * 1024;

static INDEX: &[u8] = include_bytes!("../../pc/web/index.html");
static APP_JS: &[u8] = include_bytes!("../../pc/web/app.js");
static STYLE_CSS: &[u8] = include_bytes!("../../pc/web/style.css");
static FAVICON: &[u8] = include_bytes!("../../pc/web/favicon.svg");
static ICON: &[u8] = include_bytes!("../../pc/web/icon.png");

pub trait WebHost: Send + Sync + 'static {
    fn new_token(&self) -> u32;
    fn register_connection(&self, token: u32, connection: Arc<WsConnection>, mode: &str);
    fn remove_connection(&self, token: u32);
    fn submit_input(&self, token: u32, ip: String, state: InputState, want_slot: u8) -> u32;
    fn ping(&self, token: u32) -> u32;
    fn swap(&self, token: u32, wanted: u32);
    fn vote(&self, token: u32, request_id: u32, accept: bool);
    fn lock(&self, token: u32, enabled: bool);
    fn party_state(&self) -> (bool, Option<u32>);
    fn server_name(&self) -> String;
    fn max_players(&self) -> usize;
    fn screen_info(&self) -> Option<(u16, u32, u32)>;
    fn log(&self, message: String);
}

pub struct WsConnection {
    /// Writes share one lock; reads use a separate cloned socket so a quiet
    /// browser tab cannot block server-originated slot/rumble/screen messages.
    writer: Mutex<TcpStream>,
    closed: AtomicBool,
    slot: AtomicU8,
    seq: AtomicU16,
}

impl WsConnection {
    pub fn send_json(&self, message: &str) {
        self.send_frame(0x1, message.as_bytes());
    }

    pub fn send_binary(&self, payload: &[u8]) -> bool {
        self.send_frame(0x2, payload)
    }

    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Relaxed)
    }

    pub fn close(&self) {
        self.closed.store(true, Ordering::Relaxed);
        if let Ok(socket) = self.writer.lock() {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }

    fn send_frame(&self, opcode: u8, payload: &[u8]) -> bool {
        if self.is_closed() {
            return false;
        }
        let mut header = vec![0x80 | opcode];
        match payload.len() {
            0..=125 => header.push(payload.len() as u8),
            126..=65535 => {
                header.push(126);
                header.extend_from_slice(&(payload.len() as u16).to_be_bytes());
            }
            _ => {
                header.push(127);
                header.extend_from_slice(&(payload.len() as u64).to_be_bytes());
            }
        }
        let mut socket = self.writer.lock().unwrap_or_else(|err| err.into_inner());
        socket.write_all(&header).is_ok() && socket.write_all(payload).is_ok()
    }
}

struct ActiveConnection<H: WebHost> {
    id: u64,
    token: u32,
    connection: Arc<WsConnection>,
    host: Arc<H>,
}

static NEXT_CONNECTION_ID: AtomicU64 = AtomicU64::new(1);

pub struct WebServer<H: WebHost> {
    port: u16,
    key: String,
    host: Arc<H>,
    stop: Arc<AtomicBool>,
    connections: Arc<Mutex<Vec<ActiveConnection<H>>>>,
    thread: Option<JoinHandle<()>>,
}

impl<H: WebHost> WebServer<H> {
    pub fn new(port: u16, key: String, host: Arc<H>) -> Self {
        Self {
            port,
            key,
            host,
            stop: Arc::new(AtomicBool::new(false)),
            connections: Arc::new(Mutex::new(Vec::new())),
            thread: None,
        }
    }

    pub fn start(&mut self) -> io::Result<()> {
        let listener = TcpListener::bind(("0.0.0.0", self.port))?;
        listener.set_nonblocking(true)?;
        let key = self.key.clone();
        let host = self.host.clone();
        let stop = self.stop.clone();
        let connections = self.connections.clone();
        let port = self.port;
        self.thread = Some(thread::spawn(move || {
            println!(
                "[web] browser pad ready on http://0.0.0.0:{port} (local network only)"
            );
            while !stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((socket, addr)) => {
                        let key = key.clone();
                        let host = host.clone();
                        let connections = connections.clone();
                        thread::spawn(move || {
                            handle_connection(socket, addr, key, host, connections);
                        });
                    }
                    Err(ref err) if err.kind() == io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(25));
                    }
                    Err(_) => break,
                }
            }
        }));
        Ok(())
    }

    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
        let active: Vec<ActiveConnection<H>> = {
            let mut connections = self
                .connections
                .lock()
                .unwrap_or_else(|err| err.into_inner());
            connections.drain(..).collect()
        };
        for entry in active {
            entry.connection.close();
            entry.host.remove_connection(entry.token);
        }
        cleanup_all_relays();
    }
}

impl<H: WebHost> Drop for WebServer<H> {
    fn drop(&mut self) {
        self.stop();
    }
}

fn handle_connection<H: WebHost>(
    mut socket: TcpStream,
    addr: SocketAddr,
    key: String,
    host: Arc<H>,
    connections: Arc<Mutex<Vec<ActiveConnection<H>>>>,
) {
    let _ = socket.set_read_timeout(Some(Duration::from_secs(15)));
    let request = match read_request(&mut socket) {
        Ok(request) => request,
        Err(_) => return,
    };
    if request.method != "GET" {
        let status = if request.method == "POST" { 405 } else { 501 };
        plain(&mut socket, status, "Unsupported method\n");
        return;
    }
    let (path, query) = match request.target.split_once('?') {
        Some((path, query)) => (path, query),
        None => (request.target.as_str(), ""),
    };
    let given = query_value_decoded(query, "k").unwrap_or_default();
    let has_key = constant_eq(given.as_bytes(), key.as_bytes());

    if path == "/ws" || path == "/screen" {
        if !has_key {
            plain(&mut socket, 401, UNAUTHORIZED);
            return;
        }
        if !request.header("upgrade").is_some_and(|value| value.eq_ignore_ascii_case("websocket")) {
            plain(&mut socket, 400, "Expected a WebSocket upgrade\n");
            return;
        }
        if request.header("sec-websocket-version") != Some("13") {
            plain(&mut socket, 426, "WebSocket version 13 required\n");
            return;
        }
        let Some(ws_key) = request.header("sec-websocket-key") else {
            plain(&mut socket, 400, "Missing Sec-WebSocket-Key\n");
            return;
        };
        if !origin_ok(&request, path) {
            plain(&mut socket, 403, "Cross-site browser control is not allowed\n");
            return;
        }
        let accept = websocket_accept(ws_key);
        let response = format!(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n"
        );
        if socket.write_all(response.as_bytes()).is_err() {
            return;
        }
        // Upgraded browser sockets must not inherit the initial-request timeout.
        let reader = match socket.try_clone() {
            Ok(reader) => reader,
            Err(_) => return,
        };
        let _ = socket.set_read_timeout(None);
        let _ = reader.set_read_timeout(None);
        let mode = if path == "/screen" { "screen" } else { "pad" };
        let quality = clamp(
            query_value_decoded(query, "q")
                .and_then(|value| value.parse::<i64>().ok())
                .unwrap_or(0),
            0,
            2,
        ) as u8;
        let connection = Arc::new(WsConnection {
            writer: Mutex::new(socket),
            closed: AtomicBool::new(false),
            slot: AtomicU8::new(0),
            seq: AtomicU16::new(0),
        });
        run_websocket(connection, reader, addr, mode, quality, host, connections);
        return;
    }

    let asset = match path {
        "/" | "/index.html" => Some(("index.html", INDEX, "text/html; charset=utf-8")),
        "/app.js" => Some(("app.js", APP_JS, "text/javascript; charset=utf-8")),
        "/style.css" => Some(("style.css", STYLE_CSS, "text/css; charset=utf-8")),
        "/favicon.svg" => Some(("favicon.svg", FAVICON, "image/svg+xml")),
        "/icon.png" => Some(("icon.png", ICON, "image/png")),
        _ => None,
    };
    let Some((_name, body, content_type)) = asset else {
        plain(&mut socket, 404, "Not found\n");
        return;
    };
    if path == "/" || path == "/index.html" {
        if !has_key {
            plain(&mut socket, 401, UNAUTHORIZED);
            return;
        }
    }
    let header = format!(
        "HTTP/1.1 200 OK\r\nServer: lemoGamePad\r\nDate: {}\r\nConnection: close\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: {CSP}\r\n\r\n",
        http_date(),
        body.len()
    );
    let _ = socket.write_all(header.as_bytes());
    let _ = socket.write_all(body);
}

struct Request {
    method: String,
    target: String,
    headers: HashMap<String, String>,
}

impl Request {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers.get(name).map(String::as_str)
    }
}

fn read_request(socket: &mut TcpStream) -> io::Result<Request> {
    let mut data = Vec::new();
    let mut buffer = [0u8; 4096];
    while !data.windows(4).any(|window| window == b"\r\n\r\n") {
        let count = socket.read(&mut buffer)?;
        if count == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "request closed"));
        }
        data.extend_from_slice(&buffer[..count]);
        if data.len() > MAX_PAYLOAD {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "request too large"));
        }
    }
    let end = data.windows(4).position(|window| window == b"\r\n\r\n").unwrap() + 4;
    let text = String::from_utf8_lossy(&data[..end]);
    let mut lines = text.split("\r\n");
    let first = lines.next().unwrap_or_default();
    let mut parts = first.split_whitespace();
    let method = parts.next().unwrap_or_default();
    let target = parts.next().unwrap_or_default().to_string();
    let mut headers = HashMap::new();
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_string());
        }
    }
    Ok(Request {
        method: method.to_string(),
        target,
        headers,
    })
}

fn plain(socket: &mut TcpStream, status: u16, message: &str) {
    let reason = match status {
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        426 => "Upgrade Required",
        500 => "Internal Server Error",
        501 => "Not Implemented",
        _ => "Error",
    };
    let header = format!(
        "HTTP/1.1 {status} {reason}\r\nServer: lemoGamePad\r\nDate: {}\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: {CSP}\r\n\r\n",
        http_date(),
        message.len()
    );
    let _ = socket.write_all(header.as_bytes());
    let _ = socket.write_all(message.as_bytes());
}

fn origin_ok(request: &Request, _path: &str) -> bool {
    let Some(origin) = request.header("origin") else {
        return true;
    };
    let host = request.header("host").unwrap_or_default();
    let origin = origin.trim_end_matches('/');
    let (scheme, netloc) = match origin.split_once("://") {
        Some((scheme, rest)) => (scheme.to_ascii_lowercase(), rest.split('/').next().unwrap_or("")),
        None => return false,
    };
    (scheme == "http" || scheme == "https") && netloc.eq_ignore_ascii_case(host)
}

fn constant_eq(left: &[u8], right: &[u8]) -> bool {
    if left.is_empty() || left.len() != right.len() {
        return false;
    }
    let mut diff = 0u8;
    for (a, b) in left.iter().zip(right) {
        diff |= a ^ b;
    }
    diff == 0
}

fn query_value<'a>(query: &'a str, key: &str) -> Option<&'a str> {
    for pair in query.split('&') {
        if let Some((name, value)) = pair.split_once('=') {
            if name == key {
                return Some(value);
            }
        }
    }
    None
}

/// Query values are decoded like `urllib.parse.parse_qs`: `+` becomes a space
/// and `%XX` becomes the byte it encodes.
fn query_value_decoded(query: &str, key: &str) -> Option<String> {
    let raw = query_value(query, key)?;
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0usize;
    while index < bytes.len() {
        match bytes[index] {
            b'+' => {
                out.push(b' ');
                index += 1;
            }
            b'%' if index + 2 < bytes.len() => {
                let high = hex_value(bytes[index + 1]);
                let low = hex_value(bytes[index + 2]);
                if let (Some(high), Some(low)) = (high, low) {
                    out.push((high << 4) | low);
                    index += 3;
                } else {
                    out.push(bytes[index]);
                    index += 1;
                }
            }
            byte => {
                out.push(byte);
                index += 1;
            }
        }
    }
    Some(String::from_utf8_lossy(&out).into_owned())
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

fn http_date() -> String {
    const WEEKDAYS: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64;
    let days = seconds.div_euclid(86_400);
    let clock = seconds.rem_euclid(86_400);
    // Howard Hinnant's civil_from_days conversion.
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_prime = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_prime + 2) / 5 + 1;
    let month = month_prime + if month_prime < 10 { 3 } else { -9 };
    let year = year + i64::from(month <= 2);
    let weekday = WEEKDAYS[(days + 4).rem_euclid(7) as usize];
    format!(
        "{weekday}, {day:02} {} {year:04} {:02}:{:02}:{:02} GMT",
        MONTHS[(month - 1) as usize],
        clock / 3600,
        (clock % 3600) / 60,
        clock % 60
    )
}

fn run_websocket<H: WebHost>(
    connection: Arc<WsConnection>,
    reader: TcpStream,
    addr: SocketAddr,
    mode: &str,
    quality: u8,
    host: Arc<H>,
    connections: Arc<Mutex<Vec<ActiveConnection<H>>>>,
) {
    let id = NEXT_CONNECTION_ID.fetch_add(1, Ordering::Relaxed);
    let token = host.new_token();
    connections
        .lock()
        .unwrap_or_else(|err| err.into_inner())
        .push(ActiveConnection {
            id,
            token,
            connection: connection.clone(),
            host: host.clone(),
        });
    host.register_connection(token, connection.clone(), mode);
    if mode == "screen" {
        host.log(format!("browser screen connected: {}", addr.ip()));
        match host.screen_info() {
            None => connection.send_json(r#"{"t":"e","m":"PC screen preview is not available"}"#),
            Some((port, fps, width)) => {
                connection.send_json(&format!(
                    "{{\"t\":\"v\",\"s\":\"connecting\",\"q\":{quality},\"fps\":{fps},\"w\":{width}}}"
                ));
                Relay::start(token, connection.clone(), (port, fps, width), quality);
            }
        }
    } else {
        host.log(format!("browser connected: {}", addr.ip()));
        let (locked, host_token) = host.party_state();
        let name = json_escape(&host.server_name());
        connection.send_json(&format!(
            "{{\"t\":\"h\",\"n\":\"{name}\",\"max\":{},\"screen\":{},\"locked\":{},\"host\":{}}}",
            host.max_players(),
            host.screen_info().is_some(),
            locked,
            Some(token) == host_token
        ));
    }

    let mut fragment: Option<u8> = None;
    let mut scratch = Vec::new();
    let mut fragment_data = Vec::new();
    loop {
        let frame = match read_frame(&reader, &mut scratch) {
            Ok(frame) => frame,
            Err(_) => break,
        };
        let (fin, opcode, payload) = frame;
        match opcode {
            0x8 => {
                connection.send_frame(0x8, &payload[..payload.len().min(125)]);
                break;
            }
            0x9 => {
                connection.send_frame(0xA, &payload);
                continue;
            }
            0xA => continue,
            0x0 => {
                let Some(_saved) = fragment else { continue };
                fragment_data.extend_from_slice(&payload);
                if fin {
                    fragment = None;
                    let message = std::mem::take(&mut fragment_data);
                    dispatch_message(&message, mode, token, &addr, &connection, &host);
                }
                continue;
            }
            0x1 => {
                if !fin {
                    fragment = Some(opcode);
                    fragment_data.clear();
                    fragment_data.extend_from_slice(&payload);
                    continue;
                }
                dispatch_message(&payload, mode, token, &addr, &connection, &host);
            }
            _ => {}
        }
    }
    if !connection.is_closed() {
        if mode == "screen" {
            host.log(format!("browser screen disconnected: {}", addr.ip()));
        } else {
            host.log(format!("browser disconnected: {}", addr.ip()));
        }
    }
    connection.close();
    forget_connection(&connections, id);
    crate::web::cleanup_relay(token);
    host.remove_connection(token);
}

fn forget_connection<H: WebHost>(connections: &Mutex<Vec<ActiveConnection<H>>>, id: u64) {
    let mut list = connections.lock().unwrap_or_else(|err| err.into_inner());
    if let Some(index) = list.iter().position(|entry| entry.id == id) {
        list.remove(index);
    }
}

fn dispatch_message<H: WebHost>(
    payload: &[u8],
    mode: &str,
    token: u32,
    addr: &SocketAddr,
    connection: &Arc<WsConnection>,
    host: &Arc<H>,
) {
    let Ok(text) = std::str::from_utf8(payload) else { return };
    let kind = json_value(text, "t").unwrap_or_default();
    if mode == "screen" {
        if kind == "q" {
            let quality = clamp(json_value(text, "q").and_then(|v| v.parse().ok()).unwrap_or(0), 0, 2) as u8;
            Relay::set_quality(token, quality);
        }
        return;
    }
    if kind == "p" {
        let slot = host.ping(token);
        if connection.slot.swap(slot as u8, Ordering::Relaxed) as u32 != slot {
            connection.send_json(&format!("{{\"t\":\"s\",\"n\":{slot}}}"));
        }
        let (locked, host_token) = host.party_state();
        let clock = json_raw_value(text, "c").unwrap_or_else(|| "null".into());
        connection.send_json(&format!(
            "{{\"t\":\"q\",\"c\":{clock},\"n\":{slot},\"lock\":{locked},\"host\":{}}}",
            Some(token) == host_token
        ));
        return;
    }
    if kind == "swap" {
        let wanted = clamp(json_int(json_value(text, "w").as_deref(), 0), 0, 8) as u32;
        host.swap(token, wanted);
        return;
    }
    if kind == "vote" {
        let id = clamp(json_int(json_value(text, "id").as_deref(), 0), 0, i64::from(u32::MAX)) as u32;
        host.vote(token, id, json_bool(json_value(text, "ok").as_deref(), false));
        return;
    }
    if kind == "lock" {
        host.lock(token, json_bool(json_value(text, "on").as_deref(), false));
        return;
    }
    let seq = connection.seq.fetch_add(1, Ordering::Relaxed).wrapping_add(1);
    let state = InputState {
        buttons: clamp(json_int(json_value(text, "b").as_deref(), 0), 0, 0x1FFFF) as u32,
        lx: clamp(json_int(json_value(text, "lx").as_deref(), 0), -32768, 32767) as i16,
        ly: clamp(json_int(json_value(text, "ly").as_deref(), 0), -32768, 32767) as i16,
        rx: clamp(json_int(json_value(text, "rx").as_deref(), 0), -32768, 32767) as i16,
        ry: clamp(json_int(json_value(text, "ry").as_deref(), 0), -32768, 32767) as i16,
        l2: clamp(json_int(json_value(text, "l2").as_deref(), 0), 0, 255) as u8,
        r2: clamp(json_int(json_value(text, "r2").as_deref(), 0), 0, 255) as u8,
        battery: 100,
        pad_hint: 0,
        want_slot: clamp(json_int(json_value(text, "w").as_deref(), 0), 0, 8) as u8,
        seq,
    };
    let want_slot = state.want_slot;
    let slot = host.submit_input(token, addr.ip().to_string(), state, want_slot);
    if connection.slot.swap(slot as u8, Ordering::Relaxed) as u32 != slot {
        connection.send_json(&format!("{{\"t\":\"s\",\"n\":{slot}}}"));
    }
}

fn read_frame(
    mut socket: &TcpStream,
    scratch: &mut Vec<u8>,
) -> io::Result<(bool, u8, Vec<u8>)> {
    let mut head = [0u8; 2];
    socket.read_exact(&mut head)?;
    let fin = head[0] & 0x80 != 0;
    let opcode = head[0] & 0x0F;
    let masked = head[1] & 0x80 != 0;
    let mut length = usize::from(head[1] & 0x7F);
    if length == 126 {
        let mut bytes = [0u8; 2];
        socket.read_exact(&mut bytes)?;
        length = usize::from(u16::from_be_bytes(bytes));
    } else if length == 127 {
        let mut bytes = [0u8; 8];
        socket.read_exact(&mut bytes)?;
        length = u64::from_be_bytes(bytes) as usize;
    }
    if length > MAX_PAYLOAD || !masked {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad websocket frame"));
    }
    let mut mask = [0u8; 4];
    socket.read_exact(&mut mask)?;
    scratch.resize(length, 0);
    socket.read_exact(scratch)?;
    for (index, byte) in scratch.iter_mut().enumerate() {
        *byte ^= mask[index % 4];
    }
    Ok((fin, opcode, std::mem::take(scratch)))
}

struct Relay {
    stop: Arc<AtomicBool>,
    /// `(quality, generation)`. A quality change invalidates the running reader
    /// so it reconnects with the new profile instead of waiting for a frame.
    state: Arc<Mutex<(u8, usize)>>,
    socket: Arc<Mutex<Option<TcpStream>>>,
    /// One-slot mailbox. Slow tabs keep only the newest complete JPEG.
    outbox: Arc<(Mutex<Option<Vec<u8>>>, Condvar)>,
    reader: Mutex<Option<JoinHandle<()>>>,
    sender: Mutex<Option<JoinHandle<()>>>,
}

impl Relay {
    fn start(
        token: u32,
        connection: Arc<WsConnection>,
        endpoint: (u16, u32, u32),
        quality: u8,
    ) {
        let (port, fps, width) = endpoint;
        let relay = Arc::new(Self {
            stop: Arc::new(AtomicBool::new(false)),
            state: Arc::new(Mutex::new((quality, 0))),
            socket: Arc::new(Mutex::new(None)),
            outbox: Arc::new((Mutex::new(None), Condvar::new())),
            reader: Mutex::new(None),
            sender: Mutex::new(None),
        });
        let reader_relay = relay.clone();
        let reader_connection = connection.clone();
        let reader = thread::spawn(move || reader_relay.run(reader_connection, port, fps, width));
        let sender_relay = relay.clone();
        let sender = thread::spawn(move || sender_relay.send_loop(connection));
        *relay.reader.lock().unwrap_or_else(|err| err.into_inner()) = Some(reader);
        *relay.sender.lock().unwrap_or_else(|err| err.into_inner()) = Some(sender);
        let _ = RELAYS.lock().unwrap_or_else(|err| err.into_inner()).insert(token, relay);
    }

    fn set_quality(token: u32, quality: u8) {
        let relays = RELAYS.lock().unwrap_or_else(|err| err.into_inner());
        let Some(relay) = relays.get(&token) else { return };
        let quality = quality.clamp(0, 2);
        let changed = {
            let mut state = relay.state.lock().unwrap_or_else(|err| err.into_inner());
            if state.0 == quality {
                false
            } else {
                state.0 = quality;
                state.1 += 1;
                true
            }
        };
        if changed {
            relay.close_socket();
            relay.wake();
        }
    }

    fn close_socket(&self) {
        if let Some(socket) = self.socket.lock().unwrap_or_else(|err| err.into_inner()).take() {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }

    fn wake(&self) {
        self.outbox.1.notify_all();
    }

    fn shutdown(&self) {
        self.stop.store(true, Ordering::Relaxed);
        self.close_socket();
        self.wake();
    }

    fn offer(&self, frame: Vec<u8>) {
        let (lock, wake) = &*self.outbox;
        if let Ok(mut pending) = lock.lock() {
            *pending = Some(frame);
            wake.notify_one();
        }
    }

    fn cancelled(&self, generation: usize) -> bool {
        if self.stop.load(Ordering::Relaxed) {
            return true;
        }
        self.state
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .1
            != generation
    }

    fn send_loop(self: Arc<Self>, connection: Arc<WsConnection>) {
        loop {
            let (lock, wake) = &*self.outbox;
            let mut pending = match lock.lock() {
                Ok(guard) => guard,
                Err(err) => err.into_inner(),
            };
            while pending.is_none() && !self.stop.load(Ordering::Relaxed) {
                pending = match wake.wait(pending) {
                    Ok(guard) => guard,
                    Err(err) => err.into_inner(),
                };
            }
            if self.stop.load(Ordering::Relaxed) {
                return;
            }
            let Some(frame) = pending.take() else { continue };
            drop(pending);
            if !connection.send_binary(&frame) {
                self.shutdown();
                return;
            }
        }
    }

    fn run(self: Arc<Self>, connection: Arc<WsConnection>, port: u16, fps: u32, width: u32) {
        let mut backoff = 0.7;
        loop {
            let (quality, generation) = *self.state.lock().unwrap_or_else(|err| err.into_inner());
            if self.cancelled(generation) {
                break;
            }
            let result = self.relay_once(&connection, port, fps, width, quality, generation);
            self.close_socket();
            if self.cancelled(generation) {
                break;
            }
            if let Err(message) = result {
                if !message.is_empty() {
                    let short: String = message.chars().take(90).collect();
                    connection.send_json(&format!(
                        "{{\"t\":\"v\",\"s\":\"retry\",\"q\":{quality},\"m\":{}}}",
                        json_escape(&short)
                    ));
                }
            }
            let (lock, wake) = &*self.outbox;
            let pending = lock.lock().unwrap_or_else(|err| err.into_inner());
            let _ = wake.wait_timeout(pending, Duration::from_secs_f64(backoff));
            backoff = (backoff * 2.0).min(4.0);
        }
    }

    fn relay_once(
        &self,
        connection: &Arc<WsConnection>,
        port: u16,
        fps: u32,
        width: u32,
        quality: u8,
        generation: usize,
    ) -> Result<(), String> {
        let address = SocketAddr::from(([127, 0, 0, 1], port));
        let mut socket = TcpStream::connect_timeout(&address, Duration::from_millis(2500))
            .map_err(|err| err.to_string())?;
        let _ = socket.set_nodelay(true);
        let _ = socket.set_read_timeout(Some(Duration::from_secs(5)));
        socket.write_all(b"LGVQ").map_err(|err| err.to_string())?;
        socket.write_all(&[quality]).map_err(|err| err.to_string())?;

        let mut header = [0u8; 4];
        socket.read_exact(&mut header).map_err(|err| err.to_string())?;
        let first = i32::from_be_bytes(header);
        let mut length = None;
        let accepted = if first == -1 {
            let mut byte = [0u8; 1];
            socket.read_exact(&mut byte).map_err(|err| err.to_string())?;
            if byte[0] > 2 {
                return Err("video server sent an invalid quality".into());
            }
            byte[0]
        } else if first > 0 && first as usize <= MAX_SCREEN_FRAME {
            length = Some(first as usize);
            quality
        } else {
            return Err("video server sent an invalid frame header".into());
        };
        if self.cancelled(generation) {
            return Ok(());
        }
        connection.send_json(&format!(
            "{{\"t\":\"v\",\"s\":\"live\",\"q\":{accepted},\"fps\":{fps},\"w\":{width}}}"
        ));
        if let Ok(clone) = socket.try_clone() {
            *self.socket.lock().unwrap_or_else(|err| err.into_inner()) = Some(clone);
        }
        loop {
            if self.cancelled(generation) {
                return Ok(());
            }
            let size = match length.take() {
                Some(size) => size,
                None => {
                    let mut bytes = [0u8; 4];
                    socket.read_exact(&mut bytes).map_err(|err| err.to_string())?;
                    let signed = i32::from_be_bytes(bytes);
                    if signed <= 0 || signed as usize > MAX_SCREEN_FRAME {
                        return Err("video server sent an invalid frame header".into());
                    }
                    signed as usize
                }
            };
            let mut frame = vec![0u8; size];
            socket.read_exact(&mut frame).map_err(|err| err.to_string())?;
            self.offer(frame);
        }
    }
}

static RELAYS: LazyLock<Mutex<HashMap<u32, Arc<Relay>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

pub fn cleanup_relay(token: u32) {
    if let Some(relay) = RELAYS.lock().unwrap_or_else(|err| err.into_inner()).remove(&token) {
        relay.shutdown();
    }
}

fn cleanup_all_relays() {
    let relays: Vec<Arc<Relay>> = RELAYS
        .lock()
        .unwrap_or_else(|err| err.into_inner())
        .drain()
        .map(|(_, relay)| relay)
        .collect();
    for relay in relays {
        relay.shutdown();
    }
}
