//! Native desktop panel for lemoGamePad ultimate.
//!
//! It supervises the Rust receiver, reads its local status snapshot, renders
//! pairing QR codes, edits receiver settings and shows the receiver log. It is
//! intentionally a thin shell: the UDP/WebSocket/video protocol stays in the
//! receiver binary.

#[path = "../qr.rs"]
mod qr;
#[path = "../util.rs"]
mod util;

use eframe::egui;
use serde_json::Value;
use std::collections::HashMap;
use std::env;
use std::fs;
use std::sync::atomic::{AtomicBool, Ordering};
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{self, Receiver};
use std::thread;
use std::time::{Duration, Instant};

const APP_VERSION: &str = "2.2";
#[cfg(target_os = "windows")]
const VIGEM_PAGE: &str = "https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0";
const RELEASES_API: &str =
    "https://api.github.com/repos/Alamshowkatdar/lemoGamePad-ultimate/releases/latest";

static STOP: AtomicBool = AtomicBool::new(false);

extern "C" fn request_stop() {
    STOP.store(true, Ordering::SeqCst);
}

/// Close the window on Ctrl+C/SIGTERM so the supervised receiver is stopped by
/// `Drop` instead of being left orphaned.
fn install_signal_handlers() {
    #[cfg(unix)]
    unsafe {
        let handler = request_stop as *const () as usize;
        libc::signal(libc::SIGINT, handler);
        libc::signal(libc::SIGTERM, handler);
    }
    #[cfg(windows)]
    {
        use libloading::Library;
        type SetHandler = unsafe extern "system" fn(Option<extern "system" fn(u32) -> i32>) -> i32;
        unsafe {
            if let Ok(library) = Library::new("kernel32.dll") {
                if let Ok(set_handler) = library.get::<SetHandler>(b"SetConsoleCtrlHandler\0") {
                    extern "system" fn handler(_kind: u32) -> i32 {
                        request_stop();
                        1
                    }
                    let _ = set_handler(Some(handler));
                }
            }
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Tab {
    Status,
    Pairing,
    Settings,
    Log,
}

#[derive(Clone)]
struct Settings {
    port: u16,
    video_port: u16,
    web_port: u16,
    max_phones: usize,
    pad: String,
    video_fps: u32,
    video_width: u32,
    video_quality: u8,
    no_rumble: bool,
    no_video: bool,
    no_web: bool,
    web_key: String,
    web_key_file: String,
    no_install_deps: bool,
    check_updates: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            port: 9876,
            video_port: 0,
            web_port: 0,
            max_phones: 8,
            pad: "x360".into(),
            video_fps: 60,
            video_width: 480,
            video_quality: 42,
            no_rumble: false,
            no_video: false,
            no_web: false,
            web_key: String::new(),
            web_key_file: String::new(),
            no_install_deps: false,
            check_updates: true,
        }
    }
}

#[derive(Clone, Default)]
struct Peer {
    slot: u32,
    token: u32,
    name: String,
    ip: String,
    rate: u64,
    lost: u64,
    battery: u8,
    host: bool,
}

#[derive(Clone, Default)]
struct Status {
    running: bool,
    port: u16,
    max_phones: usize,
    pad: String,
    hostname: String,
    video_port: u16,
    web_port: u16,
    web_key: String,
    locked: bool,
    host_slot: u32,
    problems: Vec<String>,
    peers: Vec<Peer>,
}

impl Status {
    fn from_value(value: &Value) -> Self {
        let peers = value
            .get("peers")
            .and_then(Value::as_array)
            .map(|items| {
                items
                    .iter()
                    .map(|peer| Peer {
                        slot: number(peer, "slot") as u32,
                        token: number(peer, "token") as u32,
                        name: text(peer, "name"),
                        ip: text(peer, "ip"),
                        rate: number(peer, "rate"),
                        lost: number(peer, "lost"),
                        battery: number(peer, "battery") as u8,
                        host: boolean(peer, "host"),
                    })
                    .collect()
            })
            .unwrap_or_default();
        let problems = value
            .get("problems")
            .and_then(Value::as_array)
            .map(|items| items.iter().filter_map(|item| item.as_str().map(str::to_owned)).collect())
            .unwrap_or_default();
        Self {
            running: boolean(value, "running"),
            port: number(value, "port") as u16,
            max_phones: number(value, "max_phones") as usize,
            pad: text(value, "pad"),
            hostname: text(value, "hostname"),
            video_port: number(value, "video_port") as u16,
            web_port: number(value, "web_port") as u16,
            web_key: text(value, "web_key"),
            locked: boolean(value, "locked"),
            host_slot: number(value, "host_slot") as u32,
            problems,
            peers,
        }
    }
}

fn tab_button(ui: &mut egui::Ui, current: Tab, tab: Tab, label: &str) -> bool {
    let selected = current == tab;
    let response = ui.add_sized(
        [ui.available_width(), 30.0],
        egui::Button::new(format!("    {label}")).selected(selected),
    );
    draw_tab_icon(ui, response.rect, tab, selected);
    response.clicked()
}

fn draw_tab_icon(ui: &egui::Ui, rect: egui::Rect, tab: Tab, selected: bool) {
    let center = egui::pos2(rect.left() + 15.0, rect.center().y);
    let color = if selected {
        ui.visuals().hyperlink_color
    } else {
        ui.visuals().text_color()
    };
    let stroke = egui::Stroke::new(1.5, color);
    let painter = ui.painter();
    match tab {
        Tab::Status => {
            painter.circle_filled(center, 4.5, color);
            painter.circle_stroke(center, 7.0, stroke);
        }
        Tab::Pairing => {
            let box_rect = egui::Rect::from_center_size(center, egui::vec2(12.0, 12.0));
            painter.rect(
                box_rect,
                2.0,
                egui::Color32::TRANSPARENT,
                stroke,
                egui::StrokeKind::Inside,
            );
            for (dx, dy) in [(-3.0, -3.0), (3.0, -3.0), (-3.0, 3.0), (3.0, 3.0)] {
                painter.rect_filled(
                    egui::Rect::from_center_size(
                        center + egui::vec2(dx, dy),
                        egui::vec2(3.0, 3.0),
                    ),
                    0.0,
                    color,
                );
            }
        }
        Tab::Settings => {
            painter.circle_stroke(center, 4.0, stroke);
            painter.circle_filled(center, 1.6, color);
            for index in 0..8 {
                let angle = index as f32 * std::f32::consts::TAU / 8.0;
                let dir = egui::vec2(angle.cos(), angle.sin());
                painter.line_segment([center + dir * 5.5, center + dir * 7.5], stroke);
            }
        }
        Tab::Log => {
            for (index, length) in [16.0, 12.0, 14.0].into_iter().enumerate() {
                let y = center.y - 5.0 + index as f32 * 5.0;
                painter.line_segment(
                    [
                        egui::pos2(center.x - 8.0, y),
                        egui::pos2(center.x - 8.0 + length, y),
                    ],
                    stroke,
                );
            }
        }
    }
}

fn boolean(value: &Value, key: &str) -> bool {
    value.get(key).and_then(Value::as_bool).unwrap_or(false)
}

fn number(value: &Value, key: &str) -> u64 {
    value.get(key).and_then(Value::as_u64).unwrap_or(0)
}

fn text(value: &Value, key: &str) -> String {
    value
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned()
}

#[derive(Clone)]
struct Release {
    tag: String,
    url: String,
}

enum InstallMsg {
    Line(String),
    Done,
}

struct GuiApp {
    settings: Settings,
    tab: Tab,
    dark: bool,
    child: Option<Child>,
    status: Status,
    status_path: PathBuf,
    log_rx: Receiver<String>,
    log_lines: Vec<String>,
    update_rx: Receiver<Option<Release>>,
    update: Option<Release>,
    update_error: String,
    update_dismissed: bool,
    checking: bool,
    confirm_install: bool,
    installing: bool,
    install_rx: Receiver<InstallMsg>,
    pair_ip: String,
    textures: HashMap<String, egui::TextureHandle>,
    last_status: Instant,
    error: String,
}

impl GuiApp {
    fn new(settings: Settings, manual_start: bool) -> Self {
        let (_tx, log_rx) = mpsc::channel();
        let (_tx, update_rx) = mpsc::channel();
        let (_tx, install_rx) = mpsc::channel();
        let status_path = env::temp_dir().join(format!("lmgb-rust-gui-{}.json", std::process::id()));
        let mut app = Self {
            settings,
            tab: Tab::Status,
            dark: true,
            child: None,
            status: Status::default(),
            status_path,
            log_rx,
            log_lines: Vec::new(),
            update_rx,
            update: None,
            update_error: String::new(),
            update_dismissed: false,
            checking: false,
            confirm_install: false,
            installing: false,
            install_rx,
            pair_ip: String::new(),
            textures: HashMap::new(),
            last_status: Instant::now(),
            error: String::new(),
        };
        if !manual_start {
            app.start_server();
        }
        if app.settings.check_updates {
            app.check_updates();
        }
        app
    }

    fn validate(&self) -> Result<(), String> {
        if !(1..=65535).contains(&self.settings.port) {
            return Err("Control port must be 1-65535".into());
        }
        if !(1..=8).contains(&self.settings.max_phones) {
            return Err("Player count must be 1-8".into());
        }
        if !(1..=60).contains(&self.settings.video_fps) {
            return Err("Video fps must be 1-60".into());
        }
        if !(160..=1280).contains(&self.settings.video_width) {
            return Err("Video width must be 160-1280".into());
        }
        if !(20..=90).contains(&self.settings.video_quality) {
            return Err("Video quality must be 20-90".into());
        }
        Ok(())
    }

    fn server_arguments(&self) -> Vec<String> {
        let s = &self.settings;
        let mut args = vec![
            "--port".into(),
            s.port.to_string(),
            "--pad".into(),
            s.pad.clone(),
            "--max-phones".into(),
            s.max_phones.to_string(),
            "--video-fps".into(),
            s.video_fps.to_string(),
            "--video-width".into(),
            s.video_width.to_string(),
            "--video-quality".into(),
            s.video_quality.to_string(),
            "--no-qr".into(),
        ];
        if s.no_rumble {
            args.push("--no-rumble".into());
        }
        if s.no_video {
            args.push("--no-video".into());
        } else if s.video_port != 0 {
            args.extend(["--video-port".into(), s.video_port.to_string()]);
        }
        if s.no_web {
            args.push("--no-web".into());
        } else if s.web_port != 0 {
            args.extend(["--web-port".into(), s.web_port.to_string()]);
        }
        if !s.web_key.is_empty() {
            args.extend(["--web-key".into(), s.web_key.clone()]);
        }
        if !s.web_key_file.is_empty() {
            args.extend(["--web-key-file".into(), s.web_key_file.clone()]);
        }
        if s.no_install_deps {
            args.push("--no-install-deps".into());
        }
        args
    }

    fn start_server(&mut self) {
        self.stop_server();
        if let Err(err) = self.validate() {
            self.error = err;
            return;
        }
        let binary = match server_binary() {
            Ok(path) => path,
            Err(err) => {
                self.error = err;
                return;
            }
        };
        let _ = fs::remove_file(&self.status_path);
        let mut command = Command::new(binary);
        command
            .args(self.server_arguments())
            .env("LEMO_GAMEPAD_STATUS_FILE", &self.status_path)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(err) => {
                self.error = format!("Could not start receiver: {err}");
                return;
            }
        };
        let (tx, rx) = mpsc::channel();
        if let Some(stdout) = child.stdout.take() {
            let sender = tx.clone();
            thread::spawn(move || {
                for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                    if sender.send(line).is_err() {
                        break;
                    }
                }
            });
        }
        if let Some(stderr) = child.stderr.take() {
            let sender = tx.clone();
            thread::spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    if sender.send(format!("[stderr] {line}")).is_err() {
                        break;
                    }
                }
            });
        }
        self.log_rx = rx;
        self.child = Some(child);
        self.error.clear();
        self.push_log("receiver started");
    }

    fn stop_server(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
            self.push_log("receiver stopped");
        }
        self.status = Status::default();
        let _ = fs::remove_file(&self.status_path);
    }

    fn push_log(&mut self, line: impl Into<String>) {
        self.log_lines.push(line.into());
        if self.log_lines.len() > 600 {
            self.log_lines.remove(0);
        }
    }

    fn poll(&mut self) {
        if let Some(child) = self.child.as_mut() {
            if let Ok(Some(code)) = child.try_wait() {
                self.push_log(format!("receiver exited with status {code}"));
                self.child = None;
            }
        }
        while let Ok(line) = self.log_rx.try_recv() {
            self.push_log(line);
        }
        if self.last_status.elapsed() >= Duration::from_millis(200) {
            self.last_status = Instant::now();
            if let Ok(payload) = fs::read_to_string(&self.status_path) {
                if let Ok(value) = serde_json::from_str::<Value>(&payload) {
                    self.status = Status::from_value(&value);
                }
            } else if self.child.is_none() {
                self.status.running = false;
            }
        }
        while let Ok(result) = self.update_rx.try_recv() {
            self.checking = false;
            if let Some(release) = result {
                self.update = Some(release);
                self.update_error.clear();
                self.update_dismissed = false;
            } else {
                self.update_error = "Update check failed".into();
            }
        }
        while let Ok(message) = self.install_rx.try_recv() {
            match message {
                InstallMsg::Line(line) => self.push_log(line),
                InstallMsg::Done => self.installing = false,
            }
        }
    }

    fn check_updates(&mut self) {
        self.checking = true;
        self.update_error.clear();
        let (tx, rx) = mpsc::channel();
        self.update_rx = rx;
        thread::spawn(move || {
            let _ = tx.send(fetch_release());
        });
    }

    /// Runs the one-time setup only after an explicit confirmation. Linux uses
    /// `pkexec`; Windows uses a UAC-elevated PowerShell for the firewall script
    /// and opens the ViGEmBus download page. Nothing is automatic.
    fn run_install(&mut self) {
        self.confirm_install = false;
        #[cfg(target_os = "linux")]
        {
            let script = match installer_path() {
                Ok(path) => path,
                Err(err) => {
                    self.error = err;
                    return;
                }
            };
            if !program_available("pkexec") {
                self.error = format!(
                    "pkexec not found. Run this in a terminal: sudo sh {}",
                    script.display()
                );
                return;
            }
            let (tx, rx) = mpsc::channel();
            self.install_rx = rx;
            self.installing = true;
            self.error.clear();
            self.push_log(format!("[installer] running {}", script.display()));
            thread::spawn(move || {
                let output = Command::new("pkexec")
                    .arg("sh")
                    .arg(&script)
                    .output();
                report_install_output(output, tx);
            });
        }
        #[cfg(target_os = "windows")]
        {
            let script = match firewall_script() {
                Ok(path) => path,
                Err(err) => {
                    self.error = err;
                    return;
                }
            };
            let powershell = if program_available("powershell.exe") {
                "powershell.exe"
            } else if program_available("powershell") {
                "powershell"
            } else {
                self.error = format!(
                    "PowerShell not found. Run this as Administrator: {}",
                    script.display()
                );
                return;
            };
            let port = self.settings.port;
            let video_port = if self.settings.no_video {
                0
            } else if self.settings.video_port == 0 {
                port.saturating_add(1).min(65535)
            } else {
                self.settings.video_port
            };
            let web_port = if self.settings.no_web {
                0
            } else if self.settings.web_port == 0 {
                port.saturating_add(2).min(65535)
            } else {
                self.settings.web_port
            };
            let (tx, rx) = mpsc::channel();
            self.install_rx = rx;
            self.installing = true;
            self.error.clear();
            open_url(VIGEM_PAGE);
            self.push_log(format!("[installer] ViGEmBus download: {VIGEM_PAGE}"));
            self.push_log(format!(
                "[installer] firewall setup: {} (ports {port}/{video_port}/{web_port})",
                script.display()
            ));
            thread::spawn(move || {
                let escaped = script.to_string_lossy().replace('\'', "''");
                let command = format!(
                    "Start-Process -FilePath '{powershell}' -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','{escaped}','-Port','{port}','-VideoPort','{video_port}','-WebPort','{web_port}' -Verb RunAs -Wait"
                );
                let output = Command::new(powershell)
                    .arg("-NoProfile")
                    .arg("-Command")
                    .arg(command)
                    .output();
                report_install_output(output, tx);
            });
        }
        #[cfg(not(any(target_os = "linux", target_os = "windows")))]
        {
            self.error = "Automatic dependency setup is not available on this platform.".into();
        }
    }

    fn qr(&mut self, ctx: &egui::Context, name: &str, payload: &str) -> Option<egui::TextureHandle> {
        if let Some(texture) = self.textures.get(name) {
            return Some(texture.clone());
        }
        let image = qr::render(payload).ok()?;
        let (width, height) = image.dimensions();
        let color = egui::ColorImage::new(
            [width as usize, height as usize],
            image.as_raw().iter().map(|value| egui::Color32::from_gray(*value)).collect(),
        );
        let texture = ctx.load_texture(name, color, egui::TextureOptions::NEAREST);
        self.textures.insert(name.to_owned(), texture.clone());
        Some(texture)
    }

    fn show_status(&mut self, ui: &mut egui::Ui) {
        ui.heading("Server status");
        ui.add_space(8.0);
        ui.horizontal(|ui| {
            if ui.button("Start").clicked() {
                self.start_server();
            }
            if ui.button("Stop").clicked() {
                self.stop_server();
            }
            if ui.button("Restart").clicked() {
                self.start_server();
            }
        });
        if !self.error.is_empty() {
            ui.colored_label(egui::Color32::RED, &self.error);
        }
        ui.add_space(12.0);
        let pill = if self.child.is_some() && self.status.running {
            ("RUNNING", egui::Color32::from_rgb(80, 200, 120))
        } else {
            ("STOPPED", egui::Color32::from_rgb(200, 110, 110))
        };
        ui.colored_label(pill.1, format!("● {}", pill.0));
        ui.add_space(8.0);
        egui::Grid::new("status_grid")
            .num_columns(2)
            .spacing([18.0, 6.0])
            .show(ui, |ui| {
                ui.label("Control port");
                ui.monospace(self.status.port.to_string());
                ui.end_row();
                ui.label("Screen port");
                ui.monospace(self.status.video_port.to_string());
                ui.end_row();
                ui.label("Browser port");
                ui.monospace(self.status.web_port.to_string());
                ui.end_row();
                ui.label("Pad backend");
                ui.monospace(if self.status.pad.is_empty() { &self.settings.pad } else { &self.status.pad });
                ui.end_row();
                ui.label("Max players");
                ui.monospace(self.status.max_phones.max(self.settings.max_phones).to_string());
                ui.end_row();
                ui.label("Party lock");
                ui.monospace(if self.status.locked { "on" } else { "off" });
                ui.end_row();
                ui.label("Host slot");
                let host_slot = if self.status.host_slot == 0 {
                    "-".to_owned()
                } else {
                    self.status.host_slot.to_string()
                };
                ui.monospace(host_slot);
                ui.end_row();
            });
        if !self.status.problems.is_empty() {
            ui.add_space(10.0);
            ui.colored_label(egui::Color32::YELLOW, "Problems");
            for problem in &self.status.problems {
                ui.label(format!("• {problem}"));
            }
        }
        ui.add_space(14.0);
        ui.heading("Players");
        if self.status.peers.is_empty() {
            ui.label("No controllers connected.");
        } else {
            egui::Grid::new("peers_grid")
                .num_columns(6)
                .spacing([14.0, 6.0])
                .striped(true)
                .show(ui, |ui| {
                    ui.strong("Slot");
                    ui.strong("Name");
                    ui.strong("IP");
                    ui.strong("Rate");
                    ui.strong("Loss");
                    ui.strong("Battery");
                    ui.end_row();
                    for peer in &self.status.peers {
                        ui.monospace(format!("P{}", peer.slot));
                        ui.label(format!("{}{}", peer.name, if peer.host { " ★" } else { "" }));
                        ui.monospace(&peer.ip);
                        ui.monospace(peer.rate.to_string());
                        ui.monospace(peer.lost.to_string());
                        ui.monospace(format!("{}%", peer.battery));
                        ui.end_row();
                    }
                });
        }
    }

    fn show_pairing(&mut self, ctx: &egui::Context, ui: &mut egui::Ui) {
        ui.heading("Pairing");
        ui.add_space(8.0);
        let hostname = if self.status.hostname.is_empty() {
            util::hostname()
        } else {
            self.status.hostname.clone()
        };
        let ips = util::local_ips();
        if self.pair_ip.is_empty() || !ips.contains(&self.pair_ip) {
            self.pair_ip = ips
                .iter()
                .find(|value| !value.starts_with("127."))
                .cloned()
                .unwrap_or_else(|| "127.0.0.1".into());
        }
        let ip = self.pair_ip.clone();
        ui.horizontal(|ui| {
            ui.label("Pair over");
            egui::ComboBox::from_id_salt("pair-ip")
                .selected_text(ip.clone())
                .show_ui(ui, |ui| {
                    for candidate in &ips {
                        ui.selectable_value(&mut self.pair_ip, candidate.clone(), candidate.clone());
                    }
                });
        });
        let port = if self.status.port == 0 { self.settings.port } else { self.status.port };
        let pad_payload = format!(
            "vpad://connect?h={}&p={}&n={}",
            util::uri_escape(&ip),
            port,
            util::uri_escape(&hostname)
        );
        let guest_payload = format!("{pad_payload}&a=1");
        let key = if self.status.web_key.is_empty() {
            self.settings.web_key.clone()
        } else {
            self.status.web_key.clone()
        };
        let web_port = if self.status.web_port == 0 {
            if self.settings.web_port == 0 { port + 2 } else { self.settings.web_port }
        } else {
            self.status.web_port
        };
        let web_url = format!("http://{ip}:{web_port}/?k={}", util::uri_escape(&key));
        ui.label("Phone controller — scan in the app");
        ui.horizontal(|ui| {
            let pad_name = format!("pad-{ip}");
            if let Some(texture) = self.qr(ctx, &pad_name, &pad_payload) {
                ui.add(egui::Image::from_texture(&texture).fit_to_exact_size(egui::vec2(210.0, 210.0)));
            }
            ui.vertical(|ui| {
                ui.label("Guest / second player");
                let guest_name = format!("guest-{ip}");
                if let Some(texture) = self.qr(ctx, &guest_name, &guest_payload) {
                    ui.add(egui::Image::from_texture(&texture).fit_to_exact_size(egui::vec2(150.0, 150.0)));
                }
            });
        });
        ui.horizontal(|ui| {
            if ui.button("Copy app link").clicked() {
                ctx.copy_text(pad_payload.clone());
            }
            if ui.button("Save app QR").clicked() {
                let path = PathBuf::from(format!("lemoGamePad-qr-{}.png", util::safe_ip(&ip)));
                if let Err(err) = qr::save_png(&pad_payload, &path) {
                    self.error = format!("Could not save QR: {err}");
                }
            }
        });
        ui.add_space(12.0);
        ui.label("Browser pad — open on any device on the same Wi-Fi");
        ui.hyperlink_to(web_url.clone(), web_url.clone());
        ui.horizontal(|ui| {
            let web_name = format!("web-{ip}");
            if let Some(texture) = self.qr(ctx, &web_name, &web_url) {
                ui.add(egui::Image::from_texture(&texture).fit_to_exact_size(egui::vec2(210.0, 210.0)));
            }
            let guest_url = format!("{web_url}&a=1");
            ui.vertical(|ui| {
                ui.label("Guest browser");
                let guest_name = format!("web-guest-{ip}");
                if let Some(texture) = self.qr(ctx, &guest_name, &guest_url) {
                    ui.add(egui::Image::from_texture(&texture).fit_to_exact_size(egui::vec2(150.0, 150.0)));
                }
            });
        });
        ui.horizontal(|ui| {
            if ui.button("Copy browser link").clicked() {
                ctx.copy_text(web_url.clone());
            }
            if ui.button("Save browser QR").clicked() {
                let path = PathBuf::from(format!("lemoGamePad-web-qr-{}.png", util::safe_ip(&ip)));
                if let Err(err) = qr::save_png(&web_url, &path) {
                    self.error = format!("Could not save QR: {err}");
                }
            }
            if ui.button("Open browser pad").clicked() {
                open_url(&web_url);
            }
        });
    }

    fn show_settings(&mut self, ui: &mut egui::Ui) {
        ui.heading("Receiver settings");
        ui.label("Changes apply on Start or Restart.");
        ui.add_space(8.0);
        #[cfg(target_os = "linux")]
        {
            let label = if self.installing {
                "Installing dependencies…"
            } else {
                "Install dependencies (asks sudo)"
            };
            if ui.add_enabled(!self.installing, egui::Button::new(label)).clicked() {
                self.confirm_install = true;
            }
            ui.label("One-time setup: uinput, input group, X11 libs, firewall and capture helper.");
            ui.add_space(8.0);
        }
        #[cfg(target_os = "windows")]
        {
            let label = if self.installing {
                "Installing dependencies..."
            } else {
                "Install dependencies (asks UAC)"
            };
            if ui.add_enabled(!self.installing, egui::Button::new(label)).clicked() {
                self.confirm_install = true;
            }
            ui.label("Opens the ViGEmBus download and runs allow_firewall.ps1 as Administrator.");
            if ui.button("Open Windows setup guide").clicked() {
                open_url("https://github.com/Alamshowkatdar/lemoGamePad-ultimate#install-windows");
            }
            ui.add_space(8.0);
        }
        let s = &mut self.settings;
        egui::Grid::new("settings_grid")
            .num_columns(2)
            .spacing([18.0, 8.0])
            .show(ui, |ui| {
                ui.label("Control port");
                ui.add(egui::DragValue::new(&mut s.port).range(1..=65535));
                ui.end_row();
                ui.label("Screen port (0 = default)");
                ui.add(egui::DragValue::new(&mut s.video_port).range(0..=65535));
                ui.end_row();
                ui.label("Browser port (0 = default)");
                ui.add(egui::DragValue::new(&mut s.web_port).range(0..=65535));
                ui.end_row();
                ui.label("Max players");
                ui.add(egui::DragValue::new(&mut s.max_phones).range(1..=8));
                ui.end_row();
                ui.label("Pad backend");
                egui::ComboBox::from_id_salt("pad").selected_text(s.pad.clone()).show_ui(ui, |ui| {
                    ui.selectable_value(&mut s.pad, "x360".into(), "Xbox 360");
                    ui.selectable_value(&mut s.pad, "ds4".into(), "DualShock 4");
                });
                ui.end_row();
                ui.label("Video fps");
                ui.add(egui::DragValue::new(&mut s.video_fps).range(1..=60));
                ui.end_row();
                ui.label("Video width");
                ui.add(egui::DragValue::new(&mut s.video_width).range(160..=1280));
                ui.end_row();
                ui.label("JPEG quality");
                ui.add(egui::DragValue::new(&mut s.video_quality).range(20..=90));
                ui.end_row();
                ui.label("Fixed browser key");
                ui.text_edit_singleline(&mut s.web_key);
                ui.end_row();
                ui.label("Browser key file");
                ui.text_edit_singleline(&mut s.web_key_file);
                ui.end_row();
            });
        ui.add_space(10.0);
        ui.checkbox(&mut s.no_rumble, "Disable rumble");
        ui.checkbox(&mut s.no_video, "Disable screen stream");
        ui.checkbox(&mut s.no_web, "Disable browser pad");
        ui.checkbox(&mut s.no_install_deps, "Skip Windows setup reminders");
        ui.checkbox(&mut s.check_updates, "Check GitHub for updates on launch");
        if ui.button("Apply and restart").clicked() {
            self.start_server();
        }
    }

    fn show_log(&mut self, ui: &mut egui::Ui) {
        ui.heading("Receiver log");
        ui.add_space(8.0);
        for line in &self.log_lines {
            ui.monospace(line);
        }
    }
}

impl eframe::App for GuiApp {
    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if STOP.swap(false, Ordering::SeqCst) {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
        self.poll();
        ctx.set_visuals(if self.dark { egui::Visuals::dark() } else { egui::Visuals::light() });
        ctx.request_repaint_after(Duration::from_millis(200));
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        let mut install_open = self.confirm_install;
        let mut confirm = false;
        let mut cancel = false;
        egui::Window::new("Install dependencies")
            .open(&mut install_open)
            .resizable(false)
            .collapsible(false)
            .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0))
            .show(&ctx, |ui| {
                #[cfg(target_os = "linux")]
                {
                    ui.label("Run the one-time Linux setup now?");
                    ui.label("A system prompt will ask for administrator approval.");
                    ui.label("It installs packages, uinput rules and the launcher. Nothing happens until you approve.");
                }
                #[cfg(target_os = "windows")]
                {
                    ui.label("Run the one-time Windows setup now?");
                    ui.label("The ViGEmBus download page will open, then UAC will ask to allow the firewall script.");
                    ui.label("No driver is downloaded or installed automatically.");
                }
                #[cfg(not(any(target_os = "linux", target_os = "windows")))]
                {
                    ui.label("Dependency setup is not available on this platform.");
                }
                ui.add_space(10.0);
                ui.horizontal(|ui| {
                    if ui.button("Run installer").clicked() {
                        confirm = true;
                    }
                    if ui.button("Cancel").clicked() {
                        cancel = true;
                    }
                });
            });
        if cancel {
            install_open = false;
        }
        self.confirm_install = install_open;
        if confirm {
            self.run_install();
        }

        egui::Panel::top("top").show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.heading("lemoGamePad ultimate");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button(if self.dark { "Light mode" } else { "Dark mode" }).clicked() {
                        self.dark = !self.dark;
                    }
                    let label = if self.checking { "Checking…" } else { "Check update" };
                    if ui.button(label).clicked() {
                        self.check_updates();
                    }
                    ui.label(format!("v{APP_VERSION}"));
                });
            });
        });

        egui::Panel::left("nav").resizable(false).default_size(164.0).show(ui, |ui| {
            ui.add_space(10.0);
            if tab_button(ui, self.tab, Tab::Status, "Status") {
                self.tab = Tab::Status;
            }
            if tab_button(ui, self.tab, Tab::Pairing, "Pairing") {
                self.tab = Tab::Pairing;
            }
            if tab_button(ui, self.tab, Tab::Settings, "Settings") {
                self.tab = Tab::Settings;
            }
            if tab_button(ui, self.tab, Tab::Log, "Log") {
                self.tab = Tab::Log;
            }
            ui.add_space(10.0);
            ui.separator();
            ui.label(format!("Pad: {}", self.status.pad));
            ui.label(format!("Players: {}", self.status.peers.len()));
        });

        egui::CentralPanel::default().show(ui, |ui| {
            let scroll = egui::ScrollArea::both()
                .id_salt("main-content")
                .auto_shrink([false, false])
                .stick_to_bottom(matches!(self.tab, Tab::Log));
            scroll.show(ui, |ui| {
                if let Some(release) = self.update.clone() {
                    if !self.update_dismissed {
                        egui::Frame::new()
                            .fill(ui.visuals().faint_bg_color)
                            .corner_radius(8.0)
                            .inner_margin(10.0)
                            .show(ui, |ui| {
                                ui.horizontal(|ui| {
                                    ui.label(format!("New release available: {}", release.tag));
                                    if ui.button("View release").clicked() {
                                        open_url(&release.url);
                                    }
                                    if ui.button("Dismiss").clicked() {
                                        self.update_dismissed = true;
                                    }
                                });
                            });
                        ui.add_space(10.0);
                    }
                }
                if !self.error.is_empty() {
                    ui.colored_label(egui::Color32::RED, self.error.as_str());
                }
                if !self.update_error.is_empty() {
                    ui.colored_label(egui::Color32::YELLOW, self.update_error.as_str());
                }
                let ctx = ui.ctx().clone();
                match self.tab {
                    Tab::Status => self.show_status(ui),
                    Tab::Pairing => self.show_pairing(&ctx, ui),
                    Tab::Settings => self.show_settings(ui),
                    Tab::Log => self.show_log(ui),
                }
            });
        });
    }
}

impl Drop for GuiApp {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        let _ = fs::remove_file(&self.status_path);
    }
}

fn parse_args() -> (Settings, bool) {
    let mut settings = Settings::default();
    let mut manual = false;
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--manual-start" => manual = true,
            "--no-update-check" => settings.check_updates = false,
            "--no-rumble" => settings.no_rumble = true,
            "--no-video" => settings.no_video = true,
            "--no-web" => settings.no_web = true,
            "--no-install-deps" => settings.no_install_deps = true,
            "--port" => settings.port = next(&mut args, "--port"),
            "--video-port" => settings.video_port = next(&mut args, "--video-port"),
            "--web-port" => settings.web_port = next(&mut args, "--web-port"),
            "--max-phones" => settings.max_phones = next(&mut args, "--max-phones"),
            "--video-fps" => settings.video_fps = next(&mut args, "--video-fps"),
            "--video-width" => settings.video_width = next(&mut args, "--video-width"),
            "--video-quality" => settings.video_quality = next(&mut args, "--video-quality"),
            "--pad" => settings.pad = args.next().unwrap_or_default(),
            "--web-key" => settings.web_key = args.next().unwrap_or_default(),
            "--web-key-file" => settings.web_key_file = args.next().unwrap_or_default(),
            _ => {}
        }
    }
    (settings, manual)
}

fn next<T: std::str::FromStr>(args: &mut impl Iterator<Item = String>, flag: &str) -> T {
    args.next()
        .and_then(|value| value.parse().ok())
        .unwrap_or_else(|| panic!("invalid value for {flag}"))
}

fn report_install_output(
    output: std::io::Result<std::process::Output>,
    tx: mpsc::Sender<InstallMsg>,
) {
    match output {
        Ok(output) => {
            for line in String::from_utf8_lossy(&output.stdout).lines() {
                let _ = tx.send(InstallMsg::Line(format!("[installer] {line}")));
            }
            for line in String::from_utf8_lossy(&output.stderr).lines() {
                let _ = tx.send(InstallMsg::Line(format!("[installer] {line}")));
            }
            let status = if output.status.success() {
                "done".to_owned()
            } else {
                format!("exit {}", output.status)
            };
            let _ = tx.send(InstallMsg::Line(format!("[installer] {status}")));
        }
        Err(err) => {
            let _ = tx.send(InstallMsg::Line(format!("[installer] failed: {err}")));
        }
    }
    let _ = tx.send(InstallMsg::Done);
}

fn program_available(name: &str) -> bool {
    if let Some(path) = env::var_os("PATH") {
        for dir in env::split_paths(&path) {
            if dir.join(name).is_file() {
                return true;
            }
        }
    }
    false
}

#[cfg(target_os = "windows")]
fn firewall_script() -> Result<PathBuf, String> {
    if let Ok(path) = env::var("LEMO_GAMEPAD_FIREWALL_SCRIPT") {
        if !path.trim().is_empty() {
            return Ok(PathBuf::from(path));
        }
    }
    let mut candidates = Vec::new();
    if let Ok(exe) = env::current_exe() {
        if let Some(parent) = exe.parent() {
            candidates.push(parent.join("allow_firewall.ps1"));
            candidates.push(parent.join("../allow_firewall.ps1"));
            candidates.push(parent.join("../../allow_firewall.ps1"));
            candidates.push(parent.join("../../../pc/allow_firewall.ps1"));
            candidates.push(parent.join("../../../../pc/allow_firewall.ps1"));
        }
    }
    candidates.push(Path::new(r"pc\allow_firewall.ps1").to_path_buf());
    candidates.push(Path::new("pc/allow_firewall.ps1").to_path_buf());
    candidates
        .into_iter()
        .find(|candidate| candidate.is_file())
        .ok_or_else(|| {
            "Could not find allow_firewall.ps1. Put it next to the app or set LEMO_GAMEPAD_FIREWALL_SCRIPT.".into()
        })
}

fn installer_path() -> Result<PathBuf, String> {
    if let Ok(path) = env::var("LEMO_GAMEPAD_INSTALLER") {
        if !path.trim().is_empty() {
            return Ok(PathBuf::from(path));
        }
    }
    let mut candidates = Vec::new();
    if let Ok(exe) = env::current_exe() {
        if let Some(parent) = exe.parent() {
            candidates.push(parent.join("install-linux.sh"));
            candidates.push(parent.join("../install-linux.sh"));
            candidates.push(parent.join("../../install-linux.sh"));
            candidates.push(parent.join("../../../pc/linux/install-linux.sh"));
            candidates.push(parent.join("../../../../pc/linux/install-linux.sh"));
        }
    }
    candidates.push(Path::new("pc/linux/install-linux.sh").to_path_buf());
    candidates
        .into_iter()
        .find(|candidate| candidate.is_file())
        .ok_or_else(|| {
            "Could not find install-linux.sh. Put it next to the app or set LEMO_GAMEPAD_INSTALLER.".into()
        })
}

fn server_binary() -> Result<PathBuf, String> {
    if let Ok(path) = env::var("LEMO_GAMEPAD_SERVER") {
        if !path.is_empty() {
            return Ok(PathBuf::from(path));
        }
    }
    let name = if cfg!(windows) { "lemoGamePad-server.exe" } else { "lemoGamePad-server" };
    let mut candidates = Vec::new();
    if let Ok(exe) = env::current_exe() {
        if let Some(parent) = exe.parent() {
            candidates.push(parent.join(name));
            candidates.push(parent.join("..").join(name));
            candidates.push(parent.join("../rust/target/release").join(name));
            candidates.push(parent.join("../rust/target/debug").join(name));
        }
    }
    candidates.push(Path::new("rust/target/release").join(name));
    candidates.push(Path::new("rust/target/debug").join(name));
    for candidate in candidates {
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    Err(format!("Could not find {name}. Put the receiver next to the app or set LEMO_GAMEPAD_SERVER."))
}

fn fetch_release() -> Option<Release> {
    let program = if cfg!(windows) { "curl.exe" } else { "curl" };
    let output = Command::new(program)
        .args(["-fsSL", "--max-time", "10", RELEASES_API])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value: Value = serde_json::from_slice(&output.stdout).ok()?;
    let tag = value.get("tag_name")?.as_str()?.trim().to_owned();
    if !newer(&tag, APP_VERSION) {
        return None;
    }
    let url = value
        .get("html_url")
        .and_then(Value::as_str)
        .unwrap_or("https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases")
        .to_owned();
    Some(Release { tag, url })
}

fn newer(tag: &str, current: &str) -> bool {
    let parse = |value: &str| -> Vec<u64> {
        value
            .trim_start_matches('v')
            .split('.')
            .map(|part| part.parse().unwrap_or(0))
            .collect()
    };
    let remote = parse(tag);
    let local = parse(current);
    for index in 0..remote.len().max(local.len()) {
        let left = remote.get(index).copied().unwrap_or(0);
        let right = local.get(index).copied().unwrap_or(0);
        if left != right {
            return left > right;
        }
    }
    false
}

fn open_url(url: &str) {
    #[cfg(target_os = "windows")]
    let result = Command::new("cmd").args(["/C", "start", "", url]).spawn();
    #[cfg(target_os = "macos")]
    let result = Command::new("open").arg(url).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    let result = Command::new("xdg-open").arg(url).spawn();
    let _ = result;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_receiver_status() {
        let payload = r#"{
            "running": true, "port": 9876, "max_phones": 8, "pad": "x360",
            "hostname": "pc", "video_port": 9877, "web_port": 9878,
            "web_key": "abc", "locked": true, "host_slot": 2,
            "problems": ["uinput missing"],
            "peers": [{"slot": 2, "name": "phone", "ip": "192.168.1.4",
                       "rate": 60, "lost": 1, "battery": 77, "host": true}]
        }"#;
        let value: Value = serde_json::from_str(payload).unwrap();
        let status = Status::from_value(&value);
        assert!(status.running && status.locked);
        assert_eq!(status.web_key, "abc");
        assert_eq!(status.host_slot, 2);
        assert_eq!(status.problems, ["uinput missing"]);
        assert_eq!(status.peers.len(), 1);
        assert_eq!(status.peers[0].name, "phone");
        assert!(status.peers[0].host);
    }

    #[test]
    fn compares_release_tags() {
        assert!(newer("v2.2.1", APP_VERSION));
        assert!(newer("2.3", APP_VERSION));
        assert!(!newer("v2.1.6", APP_VERSION));
        assert!(!newer("2.2", APP_VERSION));
        assert!(!newer("v2", "2.0.1"));
    }

    #[test]
    fn builds_receiver_arguments() {
        let mut settings = Settings::default();
        settings.port = 9900;
        settings.no_video = true;
        settings.web_key = "key".into();
        settings.no_install_deps = true;
        settings.check_updates = false;
        let app = GuiApp::new(settings, true);
        let args = app.server_arguments();
        assert!(args.windows(2).any(|pair| pair[0] == "--port" && pair[1] == "9900"));
        assert!(args.contains(&"--no-video".to_owned()));
        assert!(args.contains(&"--no-qr".to_owned()));
        assert!(args.contains(&"--no-install-deps".to_owned()));
        assert!(args.windows(2).any(|pair| pair[0] == "--web-key" && pair[1] == "key"));
        assert!(!args.contains(&"--video-port".to_owned()));
    }
}

fn main() -> eframe::Result<()> {
    install_signal_handlers();
    let (settings, manual) = parse_args();
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([920.0, 620.0])
            .with_min_inner_size([760.0, 520.0]),
        ..Default::default()
    };
    eframe::run_native(
        "lemoGamePad ultimate",
        options,
        Box::new(move |_cc| Ok(Box::new(GuiApp::new(settings, manual)))),
    )
}
