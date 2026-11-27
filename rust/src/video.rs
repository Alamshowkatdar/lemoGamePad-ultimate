//! TCP PC-screen stream. Frames are `BE u32 length | JPEG`, identical to the
//! Python server and the Android/web viewers.

use image::codecs::jpeg::JpegEncoder;
use image::{DynamicImage, ImageFormat};
use std::io::{Cursor, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const QUALITY_IDS: [&str; 3] = ["low", "balanced", "high"];

pub struct VideoServer {
    pub port: u16,
    pub fps: u32,
    pub width: u32,
    pub quality: u8,
    max_viewers: usize,
    running: Arc<AtomicBool>,
    clients: Arc<Mutex<Vec<Arc<VideoClient>>>>,
    /// Fatal listener/backend errors are sticky; transient capture failures are
    /// kept separately so a later good frame cannot mask a failed TCP bind.
    problem: Arc<Mutex<String>>,
    capture_problem: Arc<Mutex<String>>,
    serve_thread: Option<JoinHandle<()>>,
    capture_thread: Option<JoinHandle<()>>,
}

struct VideoClient {
    socket: Mutex<TcpStream>,
    queue: Mutex<Option<Vec<u8>>>,
    profile: &'static str,
    alive: AtomicBool,
}

impl VideoServer {
    pub fn new(port: u16, fps: u32, width: u32, quality: u8, max_viewers: usize) -> Self {
        Self {
            port,
            fps: fps.clamp(1, 60),
            width: width.clamp(160, 1280),
            quality: quality.clamp(20, 90),
            max_viewers: max_viewers.max(1),
            running: Arc::new(AtomicBool::new(false)),
            clients: Arc::new(Mutex::new(Vec::new())),
            problem: Arc::new(Mutex::new(String::new())),
            capture_problem: Arc::new(Mutex::new(String::new())),
            serve_thread: None,
            capture_thread: None,
        }
    }

    pub fn start(&mut self) {
        if self.running.load(Ordering::Relaxed) {
            return;
        }
        self.running.store(true, Ordering::Relaxed);
        let running = self.running.clone();
        let clients = self.clients.clone();
        let problem = self.problem.clone();
        let port = self.port;
        let max_viewers = self.max_viewers;
        self.serve_thread = Some(thread::spawn(move || {
            serve(port, max_viewers, running, clients, problem);
        }));
        let running = self.running.clone();
        let clients = self.clients.clone();
        let fps = self.fps;
        let width = self.width;
        let quality = self.quality;
        let capture_problem = self.capture_problem.clone();
        self.capture_thread = Some(thread::spawn(move || {
            capture_loop(running, clients, fps, width, quality, capture_problem);
        }));
    }

    pub fn stop(&mut self) {
        self.running.store(false, Ordering::Relaxed);
        for client in self.clients.lock().unwrap_or_else(|err| err.into_inner()).drain(..) {
            client.alive.store(false, Ordering::Relaxed);
            let _ = client.socket.lock().unwrap_or_else(|err| err.into_inner()).shutdown(Shutdown::Both);
        }
        if let Some(thread) = self.serve_thread.take() {
            let _ = thread.join();
        }
        if let Some(thread) = self.capture_thread.take() {
            let _ = thread.join();
        }
    }

    pub fn info(&self) -> Option<(u16, u32, u32)> {
        if !self.running.load(Ordering::Relaxed) || !self.problem().is_empty() {
            return None;
        }
        Some((self.port, self.fps, self.width))
    }

    pub fn problem(&self) -> String {
        let fatal = self.problem.lock().unwrap_or_else(|err| err.into_inner());
        if !fatal.is_empty() {
            return fatal.clone();
        }
        self.capture_problem
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .clone()
    }

    pub fn profile_options(&self, profile: &str) -> (u32, u32, u8) {
        match profile {
            "low" => (
                self.fps,
                (f64::from(self.width) * 0.625).round_ties_even().max(160.0) as u32,
                self.quality.saturating_sub(12).max(20),
            ),
            "high" => (
                self.fps,
                (f64::from(self.width) * 1.25).round_ties_even().min(1280.0) as u32,
                (self.quality + 10).min(75),
            ),
            _ => (self.fps, self.width, self.quality),
        }
    }
}

impl Drop for VideoServer {
    fn drop(&mut self) {
        self.stop();
    }
}

fn serve(
    port: u16,
    max_viewers: usize,
    running: Arc<AtomicBool>,
    clients: Arc<Mutex<Vec<Arc<VideoClient>>>>,
    problem: Arc<Mutex<String>>,
) {
    let listener = match TcpListener::bind(("0.0.0.0", port)) {
        Ok(listener) => listener,
        Err(err) => {
            *problem.lock().unwrap_or_else(|e| e.into_inner()) =
                format!("browser screen port {port} unavailable ({err})");
            return;
        }
    };
    let _ = listener.set_nonblocking(true);
    let handshakes = Arc::new(AtomicUsize::new(0));
    while running.load(Ordering::Relaxed) {
        match listener.accept() {
            Ok((socket, addr)) => {
                // Bound in-flight hellos too: a phone that connects and stays
                // silent must not lock out every later viewer for 0.25 s.
                let reserved = {
                    let mut list = clients.lock().unwrap_or_else(|err| err.into_inner());
                    list.retain(|client| client.alive.load(Ordering::Relaxed));
                    let in_flight = handshakes.load(Ordering::Relaxed);
                    if list.len() < max_viewers && in_flight < max_viewers + 2 {
                        handshakes.fetch_add(1, Ordering::Relaxed);
                        true
                    } else {
                        false
                    }
                };
                if !reserved {
                    let _ = socket.shutdown(Shutdown::Both);
                    continue;
                }
                let clients = clients.clone();
                let handshakes = handshakes.clone();
                thread::spawn(move || {
                    handshake(socket, addr, clients, max_viewers);
                    handshakes.fetch_sub(1, Ordering::Relaxed);
                });
            }
            Err(ref err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(50));
            }
            Err(_) => break,
        }
    }
}

fn handshake(
    mut socket: TcpStream,
    addr: SocketAddr,
    clients: Arc<Mutex<Vec<Arc<VideoClient>>>>,
    max_viewers: usize,
) {
    let _ = socket.set_read_timeout(Some(Duration::from_millis(250)));
    let mut hello = [0u8; 5];
    let mut received = 0usize;
    while received < hello.len() {
        match socket.read(&mut hello[received..]) {
            Ok(0) => break,
            Ok(count) => received += count,
            Err(_) => break,
        }
    }
    let profile = if received == 5 && &hello[..4] == b"LGVQ" {
        let id = hello[4];
        let profile = QUALITY_IDS.get(id as usize).copied().unwrap_or("balanced");
        let resolved = QUALITY_IDS
            .iter()
            .position(|name| *name == profile)
            .unwrap_or(1) as u8;
        let _ = socket.write_all(&[0xFF, 0xFF, 0xFF, 0xFF, resolved]);
        profile
    } else {
        "balanced"
    };
    let _ = socket.set_read_timeout(None);
    let _ = socket.set_write_timeout(Some(Duration::from_secs(5)));
    let _ = socket.set_nodelay(true);
    let client = Arc::new(VideoClient {
        socket: Mutex::new(socket),
        queue: Mutex::new(None),
        profile,
        alive: AtomicBool::new(true),
    });
    {
        let mut list = clients.lock().unwrap_or_else(|err| err.into_inner());
        if list.len() >= max_viewers {
            return;
        }
        list.push(client.clone());
    }
    println!(
        "[video] phone viewing the PC screen from {} ({})",
        addr.ip(),
        profile
    );
    let sender = client.clone();
    thread::spawn(move || send_loop(sender));
}

fn send_loop(client: Arc<VideoClient>) {
    while client.alive.load(Ordering::Relaxed) {
        let frame = {
            let mut queue = client.queue.lock().unwrap_or_else(|err| err.into_inner());
            queue.take()
        };
        let Some(frame) = frame else {
            thread::sleep(Duration::from_millis(20));
            continue;
        };
        let mut socket = client.socket.lock().unwrap_or_else(|err| err.into_inner());
        let mut sent = 0usize;
        let mut failed = false;
        while sent < frame.len() {
            match socket.write(&frame[sent..]) {
                Ok(0) => {
                    failed = true;
                    break;
                }
                Ok(count) => sent += count,
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock && sent == 0 => break,
                Err(_) => {
                    failed = true;
                    break;
                }
            }
        }
        if failed {
            break;
        }
    }
    client.alive.store(false, Ordering::Relaxed);
    let _ = client.socket.lock().unwrap_or_else(|err| err.into_inner()).shutdown(Shutdown::Both);
}

fn capture_loop(
    running: Arc<AtomicBool>,
    clients: Arc<Mutex<Vec<Arc<VideoClient>>>>,
    fps: u32,
    width: u32,
    quality: u8,
    capture_problem: Arc<Mutex<String>>,
) {
    let interval = Duration::from_micros(1_000_000 / fps as u64);
    let mut deadline = Instant::now();
    while running.load(Ordering::Relaxed) {
        let groups = {
            let list = clients.lock().unwrap_or_else(|err| err.into_inner());
            let list: Vec<Arc<VideoClient>> = list
                .iter()
                .filter(|client| client.alive.load(Ordering::Relaxed))
                .cloned()
                .collect();
            if list.is_empty() {
                thread::sleep(Duration::from_millis(200));
                deadline = Instant::now();
                continue;
            }
            list
        };
        let now = Instant::now();
        if now < deadline {
            // Python sleeps to 2 ms before the deadline and spins the last
            // slice, avoiding scheduler slack dropping 60 fps to ~55 fps.
            let spin_start = deadline.checked_sub(Duration::from_millis(2)).unwrap_or(now);
            if spin_start > now {
                thread::sleep(spin_start - now);
            }
            while Instant::now() < deadline {
                std::hint::spin_loop();
            }
        } else {
            let missed = ((now.duration_since(deadline).as_nanos() / interval.as_nanos()) + 1) as u32;
            deadline += interval * missed;
        }
        deadline += interval;

        let image = match grab_screen() {
            Ok(image) => image,
            Err(err) => {
                // Never touch the sticky listener error here.
                *capture_problem.lock().unwrap_or_else(|e| e.into_inner()) = err;
                thread::sleep(Duration::from_millis(500));
                continue;
            }
        };
        capture_problem.lock().unwrap_or_else(|e| e.into_inner()).clear();

        let mut profiles: Vec<&str> = groups.iter().map(|client| client.profile).collect();
        profiles.sort_unstable();
        profiles.dedup();
        let mut encoded: Vec<(&str, Vec<u8>)> = Vec::new();
        for profile in profiles {
            let (_, target_width, jpeg_quality) = profile_options(profile, fps, width, quality);
            let variant = if image.width() > target_width {
                let height = ((image.height() as f64) * f64::from(target_width)
                    / f64::from(image.width()))
                .round_ties_even()
                .max(1.0) as u32;
                // Cheap box-like downscale, closest to Pillow's BOX resampling
                // used by the Python server's 60 fps capture loop.
                image.resize_exact(target_width, height, image::imageops::FilterType::Nearest)
            } else {
                image.clone()
            };
            let mut cursor = Cursor::new(Vec::new());
            let mut encoder = JpegEncoder::new_with_quality(&mut cursor, jpeg_quality);
            if encoder.encode_image(&variant).is_ok() {
                let mut packet = (cursor.get_ref().len() as u32).to_be_bytes().to_vec();
                packet.extend_from_slice(cursor.get_ref());
                encoded.push((profile, packet));
            }
        }
        for client in groups {
            if let Some((_, packet)) = encoded.iter().find(|(profile, _)| *profile == client.profile) {
                let mut queue = client.queue.lock().unwrap_or_else(|err| err.into_inner());
                *queue = Some(packet.clone());
            }
        }
    }
}

fn profile_options(profile: &str, fps: u32, width: u32, quality: u8) -> (u32, u32, u8) {
    match profile {
        "low" => (
            fps,
            (f64::from(width) * 0.625).round_ties_even().max(160.0) as u32,
            quality.saturating_sub(12).max(20),
        ),
        "high" => (
            fps,
            (f64::from(width) * 1.25).round_ties_even().min(1280.0) as u32,
            (quality + 10).min(75),
        ),
        _ => (fps, width, quality),
    }
}

fn grab_screen() -> Result<DynamicImage, String> {
    let forced = std::env::var("LEMO_CAPTURE").unwrap_or_default().to_lowercase();
    let session = if std::env::var_os("WAYLAND_DISPLAY").is_some() {
        "wayland"
    } else if std::env::var_os("DISPLAY").is_some() {
        "x11"
    } else {
        ""
    };
    let mut attempts: Vec<(String, Vec<String>, bool)> = Vec::new();
    if !forced.is_empty() {
        match forced.as_str() {
            "grim" => attempts.push(("grim".into(), vec!["grim".into(), "-t".into(), "ppm".into(), "-".into()], true)),
            "spectacle" => attempts.push(("spectacle".into(), vec!["spectacle".into(), "-b".into(), "-n".into(), "-f".into(), "-o".into(), "{file}".into()], false)),
            "gnome-screenshot" => attempts.push(("gnome-screenshot".into(), vec!["gnome-screenshot".into(), "-f".into(), "{file}".into()], false)),
            // Python's mss backend is Windows-only in practice. Use the
            // native PowerShell screen grab rather than a Python helper.
            "mss" => attempts.push(("powershell".into(), vec![], true)),
            _ => {}
        }
    } else if session == "wayland" {
        if which("grim") {
            attempts.push(("grim".into(), vec!["grim".into(), "-t".into(), "ppm".into(), "-".into()], true));
        }
        if which("spectacle") {
            attempts.push(("spectacle".into(), vec!["spectacle".into(), "-b".into(), "-n".into(), "-f".into(), "-o".into(), "{file}".into()], false));
        }
        if which("gnome-screenshot") {
            attempts.push(("gnome-screenshot".into(), vec!["gnome-screenshot".into(), "-f".into(), "{file}".into()], false));
        }
        attempts.push(("import".into(), vec!["import".into(), "-window".into(), "root".into(), "ppm:-".into()], true));
    } else if cfg!(windows) {
        attempts.push(("powershell".into(), vec![], true));
    } else {
        attempts.push(("import".into(), vec!["import".into(), "-window".into(), "root".into(), "ppm:-".into()], true));
        attempts.push(("scrot".into(), vec!["scrot".into(), "-o".into(), "{file}".into()], false));
    }
    if attempts.is_empty() {
        return Err("no screen capture backend is available".into());
    }

    let mut problems = Vec::new();
    for (name, argv, to_stdout) in attempts {
        match once(&name, &argv, to_stdout) {
            Ok(image) => return Ok(image),
            Err(err) => problems.push(format!("{name}: {err}")),
        }
    }
    Err(format!(
        "screen capture failed ({})",
        problems.join("; ")
    ))
}

fn once(name: &str, argv: &[String], to_stdout: bool) -> Result<DynamicImage, String> {
    if name == "powershell" {
        return powershell_capture();
    }
    if argv.is_empty() {
        return Err("unsupported backend".into());
    }
    if !which(&argv[0]) {
        return Err(format!("{} not found", argv[0]));
    }
    let temp = std::env::temp_dir().join(format!("lemo-shot-{}.png", std::process::id()));
    let mut command = Command::new(&argv[0]);
    for argument in &argv[1..] {
        command.arg(argument.replace("{file}", &temp.to_string_lossy()));
    }
    command.stdout(Stdio::piped()).stderr(Stdio::piped());
    let output = command.output().map_err(|err| err.to_string())?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(if stderr.is_empty() {
            format!("exit status {}", output.status)
        } else {
            stderr
        });
    }
    let bytes = if to_stdout {
        output.stdout
    } else {
        std::fs::read(&temp).map_err(|err| err.to_string())?
    };
    let _ = std::fs::remove_file(&temp);
    image::load_from_memory_with_format(&bytes, ImageFormat::Pnm)
        .or_else(|_| image::load_from_memory_with_format(&bytes, ImageFormat::Png))
        .map_err(|err| err.to_string())
}

fn powershell_capture() -> Result<DynamicImage, String> {
    let program = ["powershell.exe", "powershell"]
        .into_iter()
        .find(|name| which(name))
        .unwrap_or("powershell");
    let script = r#"
Add-Type -AssemblyName System.Windows.Forms,System.Drawing
$b = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap($b.Width, $b.Height)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($b.X, $b.Y, 0, 0, $bmp.Size)
$ms = New-Object System.IO.MemoryStream
$bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
[Console]::OpenStandardOutput().Write($ms.ToArray(), 0, [int]$ms.Length)
"#;
    let output = Command::new(program)
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output()
        .map_err(|err| err.to_string())?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    image::load_from_memory_with_format(&output.stdout, ImageFormat::Png)
        .map_err(|err| err.to_string())
}

fn which(name: &str) -> bool {
    if let Ok(path) = std::env::var("PATH") {
        for directory in std::env::split_paths(&path) {
            if directory.join(name).is_file() {
                return true;
            }
        }
    }
    false
}
