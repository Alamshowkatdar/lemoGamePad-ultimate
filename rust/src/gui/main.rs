//! Native desktop panel for lemoGamePad ultimate.
//!
//! It embeds the Rust receiver directly, reads its local status snapshot,
//! renders pairing QR codes, edits receiver settings and shows the receiver
//! log. No second EXE is required: the UDP/WebSocket/video protocol runs in
//! this process.

#[path = "../cli.rs"]
mod cli;
#[path = "../log.rs"]
mod log;
#[path = "../pad/mod.rs"]
mod pad;
#[path = "../proto.rs"]
mod proto;
#[path = "../qr.rs"]
mod qr;
#[path = "../server.rs"]
mod server;
#[path = "../util.rs"]
mod util;
#[path = "../video.rs"]
mod video;
#[path = "../web.rs"]
mod web;

use eframe::egui;
use serde_json::Value;
use std::collections::HashMap;
use std::env;
use std::fs;
#[cfg(target_os = "windows")]
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const APP_VERSION: &str = "2.2.5";
// Setup scripts and the signed ViGEmBus installer are compiled into the binary
// so "Install dependencies" works offline with only the single EXE.
#[cfg(target_os = "linux")]
const EMBEDDED_INSTALLER: &str = include_str!("../../../pc/linux/install-linux.sh");
#[cfg(target_os = "windows")]
const EMBEDDED_FIREWALL: &str = include_str!("../../../pc/allow_firewall.ps1");
#[cfg(target_os = "windows")]
const EMBEDDED_VIGEM: &[u8] =
    include_bytes!("../../../vendor/vigembus/ViGEmBus_1.22.0_x64_x86_arm64.exe");
#[cfg(target_os = "windows")]
const VIGEM_PAGE: &str = "https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0";
const RELEASES_API: &str =
    "https://api.github.com/repos/Alamshowkatdar/lemoGamePad-ultimate/releases/latest";

// Cyberpunk neon palette
const NEON_CYAN: egui::Color32 = egui::Color32::from_rgb(0, 229, 255);
const NEON_MAGENTA: egui::Color32 = egui::Color32::from_rgb(255, 47, 208);
const NEON_GREEN: egui::Color32 = egui::Color32::from_rgb(0, 255, 136);
const NEON_RED: egui::Color32 = egui::Color32::from_rgb(255, 45, 85);
const NEON_AMBER: egui::Color32 = egui::Color32::from_rgb(255, 181, 46);
const CYBER_BG: egui::Color32 = egui::Color32::from_rgb(10, 12, 18);
const CYBER_PANEL: egui::Color32 = egui::Color32::from_rgb(16, 20, 28);
const CYBER_TEXT: egui::Color32 = egui::Color32::from_rgb(190, 225, 235);

/// Configure monospace fonts for a terminal/cyberpunk aesthetic.
fn setup_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    fonts.families.insert(
        egui::FontFamily::Proportional,
        vec!["Hack".to_owned(), "Ubuntu-Light".to_owned()],
    );
    fonts.families.insert(
        egui::FontFamily::Monospace,
        vec!["Hack".to_owned()],
    );
    ctx.set_fonts(fonts);
    ctx.set_visuals(cyber_visuals());
}

fn cyber_visuals() -> egui::Visuals {
    let mut visuals = egui::Visuals::dark();
    visuals.window_fill = CYBER_BG;
    visuals.panel_fill = CYBER_PANEL;
    visuals.hyperlink_color = NEON_CYAN;
    visuals.widgets.noninteractive.bg_fill = CYBER_PANEL;
    visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(24, 30, 42);
    visuals.widgets.inactive.fg_stroke.color = CYBER_TEXT;
    visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(30, 60, 72);
    visuals.widgets.hovered.fg_stroke.color = NEON_CYAN;
    visuals.widgets.active.bg_fill = egui::Color32::from_rgb(20, 80, 96);
    visuals.widgets.active.fg_stroke.color = NEON_CYAN;
    visuals.selection.bg_fill = egui::Color32::from_rgba_unmultiplied(0, 229, 255, 60);
    visuals.selection.stroke.color = NEON_CYAN;
    visuals
}

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
    rate: f64,
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
                        rate: decimal(peer, "rate"),
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

    /// Total input packets per second across every connected controller.
    fn input_rate(&self) -> f64 {
        self.peers.iter().map(|peer| peer.rate).sum()
    }
}

#[derive(Clone, Copy)]
enum ActionIcon {
    Sun,
    Moon,
    Refresh,
    Download,
    Play,
    Stop,
    Replay,
    Link,
    Save,
    Globe,
}

fn stroke_arc(painter: &egui::Painter, center: egui::Pos2, radius: f32, start: f32, end: f32, stroke: egui::Stroke) {
    const STEPS: usize = 14;
    for i in 0..STEPS {
        let a1 = start + (end - start) * i as f32 / STEPS as f32;
        let a2 = start + (end - start) * (i + 1) as f32 / STEPS as f32;
        painter.line_segment(
            [center + egui::vec2(a1.cos(), a1.sin()) * radius,
             center + egui::vec2(a2.cos(), a2.sin()) * radius],
            stroke,
        );
    }
}

/// Draw a compact neon action icon at a given center point.
fn draw_action_icon(ui: &egui::Ui, center: egui::Pos2, icon: ActionIcon, color: egui::Color32) {
    let time = ui.ctx().input(|i| i.time) as f32;
    let stroke = egui::Stroke::new(1.4, color);
    let painter = ui.painter();
    match icon {
        ActionIcon::Sun => {
            painter.circle_stroke(center, 3.5, stroke);
            for i in 0..8 {
                let a = i as f32 * std::f32::consts::TAU / 8.0 + time * 0.3;
                let d = egui::vec2(a.cos(), a.sin());
                painter.line_segment([center + d * 5.0, center + d * 7.0], stroke);
            }
        }
        ActionIcon::Moon => {
            let c = center + egui::vec2(1.0, 0.0);
            painter.circle_stroke(c, 6.0, stroke);
            painter.circle_filled(center + egui::vec2(-1.5, -1.0), 5.5, CYBER_BG);
        }
        ActionIcon::Refresh => {
            let a = time * 1.2;
            stroke_arc(painter, center, 6.0, a, a + std::f32::consts::TAU * 0.78, stroke);
            let tip = center + egui::vec2((a + std::f32::consts::TAU * 0.78).cos(), (a + std::f32::consts::TAU * 0.78).sin()) * 6.0;
            painter.circle_filled(tip, 1.8, color);
        }
        ActionIcon::Download => {
            painter.line_segment([center + egui::vec2(0.0, -6.0), center + egui::vec2(0.0, 3.0)], stroke);
            painter.line_segment([center + egui::vec2(-3.5, -0.5), center + egui::vec2(0.0, 3.0)], stroke);
            painter.line_segment([center + egui::vec2(3.5, -0.5), center + egui::vec2(0.0, 3.0)], stroke);
            painter.line_segment([center + egui::vec2(-5.0, 6.0), center + egui::vec2(5.0, 6.0)], stroke);
        }
        ActionIcon::Play => {
            let a = center + egui::vec2(-1.0, 0.0);
            painter.line_segment([a + egui::vec2(-3.0, -5.0), a + egui::vec2(5.0, 0.0)], egui::Stroke::new(2.0, color));
            painter.line_segment([a + egui::vec2(5.0, 0.0), a + egui::vec2(-3.0, 5.0)], egui::Stroke::new(2.0, color));
        }
        ActionIcon::Stop => {
            painter.rect_filled(
                egui::Rect::from_center_size(center, egui::vec2(9.0, 9.0)),
                1.0,
                color,
            );
        }
        ActionIcon::Replay => {
            let a = time * 1.5;
            stroke_arc(painter, center, 5.5, a + 0.4, a + std::f32::consts::TAU - 0.4, stroke);
            let tip = center + egui::vec2((a + 0.4).cos(), (a + 0.4).sin()) * 5.5;
            painter.circle_filled(tip, 1.8, color);
        }
        ActionIcon::Link => {
            let l = center + egui::vec2(-2.0, 2.0);
            let r = center + egui::vec2(2.0, -2.0);
            painter.circle_stroke(l, 3.0, stroke);
            painter.circle_stroke(r, 3.0, stroke);
            painter.line_segment([center + egui::vec2(-1.0, 1.0), center + egui::vec2(1.0, -1.0)], stroke);
        }
        ActionIcon::Save => {
            painter.rect_stroke(
                egui::Rect::from_center_size(center + egui::vec2(0.0, 1.0), egui::vec2(11.0, 8.0)),
                1.0,
                stroke,
                egui::StrokeKind::Inside,
            );
            painter.line_segment([center + egui::vec2(0.0, -6.0), center + egui::vec2(0.0, 0.0)], stroke);
            painter.line_segment([center + egui::vec2(-2.5, -2.5), center + egui::vec2(0.0, 0.0)], stroke);
            painter.line_segment([center + egui::vec2(2.5, -2.5), center + egui::vec2(0.0, 0.0)], stroke);
        }
        ActionIcon::Globe => {
            painter.circle_stroke(center, 6.0, stroke);
            painter.add(egui::Shape::ellipse_stroke(center, egui::vec2(2.5, 6.0), stroke));
            painter.line_segment(
                [egui::pos2(center.x - 6.0, center.y), egui::pos2(center.x + 6.0, center.y)],
                stroke,
            );
        }
    }
}

/// True when the window is too narrow for full button labels.
fn compact_mode(ui: &egui::Ui) -> bool {
    ui.ctx().content_rect().width() < 450.0
}

/// Draw the app title at a size that fits the space left by the header buttons.
/// As the window narrows it steps down through smaller, refined variants
/// instead of colliding with the controls. Truncation is the final guard.
fn title_for_budget(budget: f32) -> (&'static str, f32) {
    if budget < 60.0 {
        ("LGP", 12.0)
    } else if budget < 130.0 {
        ("LGP", 15.0)
    } else if budget < 200.0 {
        ("lemoGamePad", 12.0)
    } else if budget < 280.0 {
        ("lemoGamePad", 15.0)
    } else if budget < 340.0 {
        ("lemoGamePad ultimate", 14.0)
    } else {
        ("lemoGamePad ultimate", 18.0)
    }
}

fn draw_app_title(ui: &mut egui::Ui, budget: f32) {
    let (text, size) = title_for_budget(budget);
    ui.add(
        egui::Label::new(
            egui::RichText::new(text)
                .size(size)
                .strong()
                .monospace()
                .color(NEON_CYAN),
        )
        .truncate(),
    );
}

/// A button that shows icon+text normally and collapses to icon-only when the
/// window is narrow. Hover tooltips keep the label discoverable in compact mode.
fn action_button(
    ui: &mut egui::Ui,
    label: &str,
    icon: ActionIcon,
    color: egui::Color32,
    compact: bool,
) -> bool {
    let response = if compact {
        ui.add_sized([30.0, 26.0], egui::Button::new(""))
    } else {
        // Leading spaces reserve an icon-sized gutter so the icon never
        // draws over the label (all text uses the monospace Hack font).
        ui.add(egui::Button::new(format!("  {label}")))
    };
    let icon_center = if compact {
        response.rect.center()
    } else {
        egui::pos2(response.rect.left() + 14.0, response.rect.center().y)
    };
    draw_action_icon(ui, icon_center, icon, color);
    let clicked = response.clicked();
    if compact {
        response.on_hover_text(label);
    }
    clicked
}

fn tab_button(ui: &mut egui::Ui, current: Tab, tab: Tab, label: &str) -> bool {
    let selected = current == tab;
    let compact = compact_mode(ui);
    let response = if compact {
        ui.add_sized([36.0, 32.0], egui::Button::new("").selected(selected))
    } else {
        ui.add_sized(
            [ui.available_width(), 30.0],
            egui::Button::new(format!("    {label}")).selected(selected),
        )
    };
    draw_tab_icon(ui, response.rect, tab, selected);
    let clicked = response.clicked();
    if compact {
        response.on_hover_text(label);
    }
    clicked
}

fn draw_tab_icon(ui: &egui::Ui, rect: egui::Rect, tab: Tab, selected: bool) {
    let center = egui::pos2(rect.left() + 15.0, rect.center().y);
    let time = ui.ctx().input(|i| i.time) as f32;
    let color = if selected {
        NEON_CYAN
    } else {
        CYBER_TEXT
    };
    let glow = egui::Color32::from_rgba_unmultiplied(color.r(), color.g(), color.b(), 40);
    let stroke = egui::Stroke::new(1.6, color);
    let painter = ui.painter();

    // Outer glow halo for selected tab
    if selected {
        let pulse = (time * 2.0).sin() * 0.5 + 0.5;
        painter.circle_filled(center, 11.0 + pulse * 2.0, glow);
    }

    match tab {
        Tab::Status => {
            // Animated radar sweep + pulsing core
            let sweep = time * 1.5;
            for index in 0..3 {
                let angle = sweep + index as f32 * std::f32::consts::TAU / 3.0;
                let dir = egui::vec2(angle.cos(), angle.sin());
                painter.line_segment(
                    [center + dir * 3.0, center + dir * 8.0],
                    egui::Stroke::new(1.0, glow),
                );
            }
            let pulse = (time * 3.0).sin() * 0.5 + 0.5;
            painter.circle_filled(center, 3.0 + pulse, color);
            painter.circle_stroke(center, 7.0, stroke);
        }
        Tab::Pairing => {
            // QR corners with animated scan line
            let box_rect = egui::Rect::from_center_size(center, egui::vec2(12.0, 12.0));
            painter.rect(box_rect, 2.0, egui::Color32::TRANSPARENT, stroke, egui::StrokeKind::Inside);
            for (dx, dy) in [(-3.0, -3.0), (3.0, -3.0), (-3.0, 3.0), (3.0, 3.0)] {
                painter.rect_filled(
                    egui::Rect::from_center_size(center + egui::vec2(dx, dy), egui::vec2(3.0, 3.0)),
                    0.0,
                    color,
                );
            }
            let scan_y = center.y - 5.0 + ((time * 8.0) % 10.0);
            painter.line_segment(
                [egui::pos2(center.x - 5.0, scan_y), egui::pos2(center.x + 5.0, scan_y)],
                egui::Stroke::new(1.2, NEON_MAGENTA),
            );
        }
        Tab::Settings => {
            // Slowly rotating gear
            let rotation = time * 0.6;
            painter.circle_stroke(center, 4.0, stroke);
            painter.circle_filled(center, 1.8, color);
            for index in 0..8 {
                let angle = rotation + index as f32 * std::f32::consts::TAU / 8.0;
                let dir = egui::vec2(angle.cos(), angle.sin());
                painter.line_segment([center + dir * 5.5, center + dir * 7.5], stroke);
            }
        }
        Tab::Log => {
            // Terminal lines with blinking cursor
            for (index, length) in [16.0, 12.0, 14.0].into_iter().enumerate() {
                let y = center.y - 5.0 + index as f32 * 5.0;
                painter.line_segment(
                    [egui::pos2(center.x - 8.0, y), egui::pos2(center.x - 8.0 + length, y)],
                    stroke,
                );
            }
            if (time * 2.0) as u64 % 2 == 0 {
                painter.rect_filled(
                    egui::Rect::from_center_size(
                        egui::pos2(center.x - 8.0 + 14.0 + 1.5, center.y + 5.0),
                        egui::vec2(3.0, 3.0),
                    ),
                    0.0,
                    NEON_GREEN,
                );
            }
        }
    }
}

/// Animated neon status indicator with a pulsing glow ring.
fn draw_status_pill(ui: &mut egui::Ui, label: &str, color: egui::Color32) {
    let time = ui.ctx().input(|i| i.time) as f32;
    let pulse = (time * 2.5).sin() * 0.5 + 0.5;
    let desired = egui::vec2(140.0, 30.0);
    let (rect, _) = ui.allocate_exact_size(desired, egui::Sense::hover());
    let painter = ui.painter();

    // Glow layers
    painter.rect_filled(
        rect.expand(2.0 + pulse * 2.0),
        12.0,
        egui::Color32::from_rgba_unmultiplied(color.r(), color.g(), color.b(), 25),
    );
    painter.rect_filled(
        rect,
        10.0,
        egui::Color32::from_rgba_unmultiplied(color.r(), color.g(), color.b(), 40),
    );
    painter.rect_stroke(
        rect,
        10.0,
        egui::Stroke::new(1.5, color),
        egui::StrokeKind::Inside,
    );

    // Animated core dot
    let dot_center = egui::pos2(rect.left() + 15.0, rect.center().y);
    painter.circle_filled(dot_center, 7.0 + pulse * 2.0, egui::Color32::from_rgba_unmultiplied(color.r(), color.g(), color.b(), 60));
    painter.circle_filled(dot_center, 4.0, color);

    painter.text(
        egui::pos2(rect.left() + 28.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        label,
        egui::FontId::new(13.0, egui::FontFamily::Monospace),
        color,
    );
}

/// One row of the diagnostics grid. Values truncate inside the last column so
/// a long URL or error can never run into the next column at narrow widths.
fn diagnostic_row(ui: &mut egui::Ui, label: &str, value: String, color: egui::Color32) {
    ui.label(label);
    ui.add(
        egui::Label::new(egui::RichText::new(value).monospace().color(color))
            .truncate(),
    );
    ui.end_row();
}

fn boolean(value: &Value, key: &str) -> bool {
    value.get(key).and_then(Value::as_bool).unwrap_or(false)
}

fn number(value: &Value, key: &str) -> u64 {
    value.get(key).and_then(Value::as_u64).unwrap_or(0)
}

/// Rates are published as fractional JSON numbers; `as_u64` would silently
/// drop them to zero.
fn decimal(value: &Value, key: &str) -> f64 {
    value.get(key).and_then(Value::as_f64).unwrap_or(0.0)
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

enum UpdateResult {
    Available(Release),
    UpToDate,
    Failed,
}

enum InstallMsg {
    Line(String),
    Done(bool),
}

struct GuiApp {
    settings: Settings,
    tab: Tab,
    dark: bool,
    server: Option<Arc<server::Server>>,
    server_thread: Option<JoinHandle<()>>,
    status: Status,
    status_path: PathBuf,
    log_rx: Receiver<String>,
    log_lines: Vec<String>,
    update_rx: Receiver<UpdateResult>,
    update: Option<Release>,
    update_error: String,
    update_info: String,
    update_dismissed: bool,
    checking: bool,
    confirm_install: bool,
    installing: bool,
    install_status: String,
    install_ok: bool,
    install_rx: Receiver<InstallMsg>,
    pair_ip: String,
    textures: HashMap<String, egui::TextureHandle>,
    last_status: Instant,
    status_wait: Instant,
    status_seen: bool,
    error: String,
}

impl GuiApp {
    fn new(settings: Settings, manual_start: bool) -> Self {
        let (log_tx, log_rx) = mpsc::channel();
        log::subscribe(log_tx);
        let (_tx, update_rx) = mpsc::channel();
        let (_tx, install_rx) = mpsc::channel();
        let status_path = env::temp_dir().join(format!("lmgb-rust-gui-{}.json", std::process::id()));
        // The receiver runs in this process and writes the same status JSON.
        env::set_var("LEMO_GAMEPAD_STATUS_FILE", &status_path);
        let mut app = Self {
            settings,
            tab: Tab::Status,
            dark: true,
            server: None,
            server_thread: None,
            status: Status::default(),
            status_path,
            log_rx,
            log_lines: Vec::new(),
            update_rx,
            update: None,
            update_error: String::new(),
            update_info: String::new(),
            update_dismissed: false,
            checking: false,
            confirm_install: false,
            installing: false,
            install_status: String::new(),
            install_ok: false,
            install_rx,
            pair_ip: String::new(),
            textures: HashMap::new(),
            last_status: Instant::now(),
            status_wait: Instant::now(),
            status_seen: false,
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
        if self.settings.video_port > 65535 {
            return Err("Screen port must be 0-65535".into());
        }
        if self.settings.web_port > 65535 {
            return Err("Browser port must be 0-65535".into());
        }
        Ok(())
    }

    /// Build the receiver configuration from the panel settings. The receiver
    /// runs inside this process, so no command line or second EXE is needed.
    fn server_config(&self) -> Result<cli::Config, String> {
        self.validate()?;
        let s = &self.settings;
        let mut config = cli::Config::default();
        config.port = s.port;
        config.pad = s.pad.clone();
        config.max_phones = s.max_phones;
        config.rumble = !s.no_rumble;
        config.video = !s.no_video;
        config.web = !s.no_web;
        config.video_fps = s.video_fps;
        config.video_width = s.video_width;
        config.video_quality = s.video_quality;
        config.web_key = s.web_key.clone();
        config.web_key_file = s.web_key_file.clone();
        config.qr = false; // the panel draws its own QR codes
        config.install_deps = !s.no_install_deps;
        config.video_port = if s.video_port == 0 {
            s.port.saturating_add(1).min(65535)
        } else {
            s.video_port
        };
        config.web_port = if s.web_port == 0 {
            s.port.saturating_add(2).min(65535)
        } else {
            s.web_port
        };
        if config.video && config.video_port == config.port {
            return Err("Screen port must differ from the control port".into());
        }
        if config.web && config.web_port == config.port {
            return Err("Browser port must differ from the control port".into());
        }
        if config.video && config.web && config.video_port == config.web_port {
            return Err("Browser port must differ from the screen port".into());
        }
        config.web_key = util::resolve_web_key(&config.web_key, &config.web_key_file);
        Ok(config)
    }

    fn start_server(&mut self) {
        self.stop_server();
        let config = match self.server_config() {
            Ok(config) => config,
            Err(err) => {
                self.error = err;
                return;
            }
        };
        let _ = fs::remove_file(&self.status_path);
        let server = match server::Server::new(&config) {
            Ok(server) => Arc::new(server),
            Err(err) => {
                self.error = format!("Could not start receiver: {err}");
                return;
            }
        };
        server.attach_web();
        let worker = Arc::clone(&server);
        let thread = thread::spawn(move || worker.run());
        self.server = Some(server);
        self.server_thread = Some(thread);
        self.status_wait = Instant::now();
        self.status_seen = false;
        self.error.clear();
        self.push_log("receiver started");
    }

    fn stop_server(&mut self) {
        if let Some(server) = self.server.take() {
            server.stop();
            if let Some(thread) = self.server_thread.take() {
                let _ = thread.join();
            }
            self.push_log("receiver stopped");
        }
        self.status = Status::default();
        self.status_wait = Instant::now();
        self.status_seen = false;
        let _ = fs::remove_file(&self.status_path);
    }

    fn push_log(&mut self, line: impl Into<String>) {
        self.log_lines.push(line.into());
        if self.log_lines.len() > 600 {
            self.log_lines.remove(0);
        }
    }

    fn status_text(&self, server_alive: bool) -> &'static str {
        if server_alive && self.status.running {
            "RUNNING"
        } else if server_alive
            && !self.status_seen
            && self.status_wait.elapsed() >= Duration::from_secs(2)
        {
            "NO STATUS"
        } else {
            "STOPPED"
        }
    }

    /// Local-only diagnostics: every field comes from the status JSON or the
    /// pairing address already held by the GUI. No probes, no TLS, no DNS.
    fn websocket_address(&self) -> String {
        if self.status.web_port == 0 {
            return "--".into();
        }
        // Prefer the LAN IP chosen on the Pairing tab; fall back to the
        // hostname published by the receiver when the tab has not been opened.
        let host = if !self.pair_ip.trim().is_empty() && !self.pair_ip.starts_with("127.") {
            self.pair_ip.clone()
        } else if !self.status.hostname.trim().is_empty() {
            self.status.hostname.clone()
        } else if !self.pair_ip.trim().is_empty() {
            self.pair_ip.clone()
        } else {
            return "--".into();
        };
        format!("ws://{host}:{}/ws", self.status.web_port)
    }

    fn paired_host_text(&self) -> String {
        if !self.status.running {
            return "--".into();
        }
        if self.status.host_slot == 0 {
            "none".into()
        } else {
            format!("P{}", self.status.host_slot)
        }
    }

    fn connected_text(&self) -> String {
        if !self.status.running {
            return "--".into();
        }
        let total = self.status.max_phones.max(self.settings.max_phones);
        format!("{}/{}", self.status.peers.len(), total)
    }

    fn input_rate_text(&self) -> String {
        if !self.status.running {
            return "--".into();
        }
        format!("{:.1} pkt/s", self.status.input_rate())
    }

    fn last_error_text(&self) -> String {
        if !self.error.trim().is_empty() {
            return self.error.clone();
        }
        if let Some(problem) = self
            .status
            .problems
            .iter()
            .rev()
            .find(|problem| !problem.trim().is_empty())
        {
            return problem.clone();
        }
        "--".into()
    }

    /// Compact connection readout shared by the Status and Pairing tabs.
    fn show_diagnostics(&self, ui: &mut egui::Ui) {
        egui::Frame::new()
            .fill(egui::Color32::from_rgba_unmultiplied(0, 229, 255, 10))
            .stroke(egui::Stroke::new(
                1.0,
                egui::Color32::from_rgba_unmultiplied(0, 229, 255, 55),
            ))
            .corner_radius(6.0)
            .inner_margin(8.0)
            .show(ui, |ui| {
                ui.colored_label(
                    NEON_CYAN,
                    egui::RichText::new("Connection diagnostics")
                        .strong()
                        .monospace(),
                );
                ui.add_space(4.0);
                egui::Grid::new("connection_diagnostics_grid")
                    .num_columns(2)
                    .spacing([14.0, 4.0])
                    .show(ui, |ui| {
                        diagnostic_row(ui, "WebSocket / LAN", self.websocket_address(), NEON_CYAN);
                        diagnostic_row(ui, "Paired host", self.paired_host_text(), NEON_GREEN);
                        diagnostic_row(ui, "Connected", self.connected_text(), NEON_GREEN);
                        diagnostic_row(ui, "Input rate", self.input_rate_text(), NEON_CYAN);
                        diagnostic_row(ui, "Last error", self.last_error_text(), NEON_AMBER);
                    });
            });
    }

    fn poll(&mut self) {
        if self.server_thread.as_ref().is_some_and(|t| t.is_finished()) {
            self.server = None;
            self.server_thread = None;
            self.push_log("receiver exited");
            self.status.running = false;
            self.status_seen = false;
        }
        while let Ok(line) = self.log_rx.try_recv() {
            self.push_log(line);
        }
        if self.last_status.elapsed() >= Duration::from_millis(200) {
            self.last_status = Instant::now();
            let server_alive = self.server.is_some();
            match fs::read_to_string(&self.status_path)
                .ok()
                .and_then(|payload| serde_json::from_str::<Value>(&payload).ok())
            {
                Some(value) => {
                    self.status = Status::from_value(&value);
                    self.status_seen = true;
                }
                None => {
                    if !server_alive {
                        self.status.running = false;
                        self.status_seen = false;
                    }
                }
            }
        }
        while let Ok(result) = self.update_rx.try_recv() {
            self.checking = false;
            match result {
                UpdateResult::Available(release) => {
                    self.update = Some(release);
                    self.update_error.clear();
                    self.update_info.clear();
                    self.update_dismissed = false;
                }
                UpdateResult::UpToDate => {
                    // A finished check with nothing newer is a normal result,
                    // not an error: say so plainly and drop any stale banner.
                    self.update = None;
                    self.update_error.clear();
                    self.update_info = "No releases available — you are up to date".into();
                    self.update_dismissed = false;
                }
                UpdateResult::Failed => {
                    self.update_error = "Update check failed".into();
                    self.update_info.clear();
                }
            }
        }
        while let Ok(message) = self.install_rx.try_recv() {
            match message {
                InstallMsg::Line(line) => self.push_log(line),
                InstallMsg::Done(ok) => {
                    self.installing = false;
                    self.install_ok = ok;
                    self.install_status = if ok {
                        if cfg!(windows) {
                            "Dependency setup done. Install ViGEmBus from the page that opened."
                        } else {
                            "Dependencies installed successfully."
                        }
                    } else {
                        "Dependency setup failed — open the Log tab for details."
                    }
                    .to_owned();
                }
            }
        }
    }

    fn check_updates(&mut self) {
        self.checking = true;
        self.update_error.clear();
        self.update_info.clear();
        let (tx, rx) = mpsc::channel();
        self.update_rx = rx;
        thread::spawn(move || {
            let _ = tx.send(fetch_release());
        });
    }

    /// Runs the one-time setup only after an explicit confirmation. Linux uses
    /// `pkexec`; Windows uses one UAC-elevated PowerShell for both the firewall
    /// script and the bundled ViGEmBus driver. Nothing is automatic.
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
                let _ = std::fs::remove_file(&script);
                self.error = format!(
                    "pkexec not found. Run this in a terminal: sudo sh {}",
                    script.display()
                );
                return;
            }
            let (tx, rx) = mpsc::channel();
            self.install_rx = rx;
            self.installing = true;
            self.install_status.clear();
            self.error.clear();
            self.push_log("[installer] running bundled Linux setup (elevated)".to_owned());
            let cleanup = script.clone();
            thread::spawn(move || {
                let output = Command::new("pkexec")
                    .arg("sh")
                    .arg(&script)
                    .output();
                let _ = std::fs::remove_file(&cleanup);
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
            let vigem = match vigem_installer() {
                Ok(path) => path,
                Err(err) => {
                    let _ = std::fs::remove_file(&script);
                    self.error = err;
                    return;
                }
            };
            let powershell = if program_available("powershell.exe") {
                "powershell.exe"
            } else if program_available("powershell") {
                "powershell"
            } else {
                let _ = std::fs::remove_file(&script);
                let _ = std::fs::remove_file(&vigem);
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
            self.install_status.clear();
            self.error.clear();
            // The bundled script lives next to the app, but pass the program
            // explicitly so the firewall rule is correct even if it had to be
            // written to the temp directory.
            let program = match env::current_exe() {
                Ok(exe) => {
                    let sibling = exe.parent().map(|parent| parent.join("lemoGamePad-server.exe"));
                    match sibling {
                        Some(path) if path.is_file() => path,
                        _ => exe,
                    }
                }
                Err(_) => PathBuf::from("lemoGamePad-app.exe"),
            };
            let quote = |path: &Path| path.to_string_lossy().replace('\'', "''");
            let result = env::temp_dir().join(format!(
                "lemoGamePad-install-result-{}.txt",
                std::process::id()
            ));
            // One UAC prompt runs both steps from an elevated wrapper, so the
            // driver never has to be downloaded from VIGEM_PAGE.
            let wrapper = format!(
                r#"$ErrorActionPreference = 'Stop'
$result = '{result_q}'
try {{
    & '{powershell}' -NoProfile -ExecutionPolicy Bypass -File '{firewall_q}' -Program '{program_q}' -Port {port} -VideoPort {video_port} -WebPort {web_port} | Out-Host
    if ($LASTEXITCODE -ne 0) {{ throw "firewall setup exited with $LASTEXITCODE" }}
    $driver = Start-Process -FilePath '{vigem_q}' -ArgumentList '/exenoui','/qn','/norestart' -Wait -PassThru
    if ($driver.ExitCode -ne 0 -and $driver.ExitCode -ne 3010) {{ throw "ViGEmBus setup exited with $($driver.ExitCode)" }}
    if ($driver.ExitCode -eq 3010) {{ 'ok: restart required' | Set-Content -Path $result -Encoding ASCII }} else {{ 'ok' | Set-Content -Path $result -Encoding ASCII }}
}} catch {{
    "fail: $($_.Exception.Message)" | Set-Content -Path $result -Encoding ASCII
    exit 1
}}
"#,
                result_q = quote(&result),
                powershell = powershell,
                firewall_q = quote(&script),
                program_q = quote(&program),
                port = port,
                video_port = video_port,
                web_port = web_port,
                vigem_q = quote(&vigem),
            );
            let wrapper_path = match write_temp_file(
                &format!("lemoGamePad-install-{}.ps1", std::process::id()),
                wrapper.as_bytes(),
            ) {
                Ok(path) => path,
                Err(err) => {
                    let _ = std::fs::remove_file(&script);
                    let _ = std::fs::remove_file(&vigem);
                    self.error = err;
                    return;
                }
            };
            self.push_log(format!(
                "[installer] bundled firewall + ViGEmBus setup (offline, ports {port}/{video_port}/{web_port}; manual driver: {VIGEM_PAGE})"
            ));
            let outer = format!(
                "Start-Process -FilePath '{powershell}' -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','{wrapper_q}' -Verb RunAs -Wait | Out-Null; $text = if (Test-Path '{result_q}') {{ (Get-Content '{result_q}' -Raw).Trim() }} else {{ 'fail: installer did not report a result' }}; Write-Output $text; if ($text -notlike 'ok*') {{ exit 1 }}",
                powershell = powershell,
                wrapper_q = quote(&wrapper_path),
                result_q = quote(&result),
            );
            thread::spawn(move || {
                let output = Command::new(powershell)
                    .arg("-NoProfile")
                    .arg("-Command")
                    .arg(outer)
                    .output();
                for path in [&script, &vigem, &wrapper_path, &result] {
                    let _ = std::fs::remove_file(path);
                }
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
            let compact = compact_mode(ui);
            if action_button(ui, "Start", ActionIcon::Play, NEON_GREEN, compact) {
                self.start_server();
            }
            if action_button(ui, "Stop", ActionIcon::Stop, NEON_RED, compact) {
                self.stop_server();
            }
            if action_button(ui, "Restart", ActionIcon::Replay, NEON_CYAN, compact) {
                self.start_server();
            }
        });
        if !self.error.is_empty() {
            ui.colored_label(NEON_RED, &self.error);
        }
        ui.add_space(12.0);
        let label = self.status_text(self.server.is_some());
        let color = match label {
            "RUNNING" => NEON_GREEN,
            "NO STATUS" => NEON_AMBER,
            _ => NEON_RED,
        };
        draw_status_pill(ui, label, color);
        ui.add_space(8.0);
        self.show_diagnostics(ui);
        ui.add_space(10.0);
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
            ui.colored_label(NEON_AMBER, "Problems");
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
                        ui.label(format!("{}{}", peer.name, if peer.host { " [HOST]" } else { "" }));
                        ui.monospace(&peer.ip);
                        ui.monospace(format!("{:.0}", peer.rate));
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
        self.show_diagnostics(ui);
        ui.add_space(10.0);
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
            let compact = compact_mode(ui);
            if action_button(ui, "Copy app link", ActionIcon::Link, NEON_CYAN, compact) {
                ctx.copy_text(pad_payload.clone());
            }
            if action_button(ui, "Save app QR", ActionIcon::Save, NEON_MAGENTA, compact) {
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
            let compact = compact_mode(ui);
            if action_button(ui, "Copy browser link", ActionIcon::Link, NEON_CYAN, compact) {
                ctx.copy_text(web_url.clone());
            }
            if action_button(ui, "Save browser QR", ActionIcon::Save, NEON_MAGENTA, compact) {
                let path = PathBuf::from(format!("lemoGamePad-web-qr-{}.png", util::safe_ip(&ip)));
                if let Err(err) = qr::save_png(&web_url, &path) {
                    self.error = format!("Could not save QR: {err}");
                }
            }
            if action_button(ui, "Open browser pad", ActionIcon::Globe, NEON_GREEN, compact) {
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
        if action_button(ui, "Apply and restart", ActionIcon::Replay, NEON_CYAN, compact_mode(ui)) {
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
        ctx.set_visuals(if self.dark { cyber_visuals() } else { egui::Visuals::light() });
        ctx.request_repaint_after(Duration::from_millis(33));
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
            let compact = compact_mode(ui);
            // Reserve room for the right-hand controls, then size the title to
            // whatever is left so it never runs into them.
            let reserved = if compact { 115.0 } else { 560.0 };
            let title_budget = (ui.available_width() - reserved).max(0.0);
            ui.horizontal(|ui| {
                draw_app_title(ui, title_budget);
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let theme_icon = if self.dark { ActionIcon::Sun } else { ActionIcon::Moon };
                    let theme_label = if self.dark { "Light mode" } else { "Dark mode" };
                    if action_button(ui, theme_label, theme_icon, NEON_CYAN, compact) {
                        self.dark = !self.dark;
                    }
                    let update_label = if self.checking { "Checking…" } else { "Check update" };
                    if action_button(ui, update_label, ActionIcon::Refresh, NEON_CYAN, compact) {
                        self.check_updates();
                    }
                    // First-time setup: always visible, collapses to a download icon.
                    if action_button(ui, "Install dependencies", ActionIcon::Download, NEON_MAGENTA, compact) {
                        self.confirm_install = true;
                    }
                    if !compact {
                        ui.label(format!("v{APP_VERSION}"));
                    }
                });
            });
        });

        let nav_compact = compact_mode(ui);
        egui::Panel::left("nav")
            .resizable(false)
            .exact_size(if nav_compact { 52.0 } else { 164.0 })
            .show(ui, |ui| {
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
                if !nav_compact {
                    ui.add_space(10.0);
                    ui.separator();
                    ui.label(format!("Pad: {}", self.status.pad));
                    ui.label(format!("Players: {}", self.status.peers.len()));
                }
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
                    ui.colored_label(NEON_RED, self.error.as_str());
                }
                if !self.install_status.is_empty() {
                    let color = if self.install_ok { NEON_GREEN } else { NEON_RED };
                    ui.colored_label(color, self.install_status.as_str());
                }
                if !self.update_info.is_empty() {
                    ui.colored_label(NEON_CYAN, self.update_info.as_str());
                }
                if !self.update_error.is_empty() {
                    ui.colored_label(NEON_AMBER, self.update_error.as_str());
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
        if let Some(server) = self.server.take() {
            server.stop();
            if let Some(thread) = self.server_thread.take() {
                let _ = thread.join();
            }
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
    let ok = match output {
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
            output.status.success()
        }
        Err(err) => {
            let _ = tx.send(InstallMsg::Line(format!("[installer] failed: {err}")));
            false
        }
    };
    let _ = tx.send(InstallMsg::Done(ok));
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

/// Materialise a bundled setup script next to the running binary when possible
/// (so the script can find the app for its launcher) and in the temp dir
/// otherwise. Callers remove the file when the installer finishes.
fn write_setup_script(file_name: &str, body: &str) -> Result<PathBuf, String> {
    if let Ok(exe) = env::current_exe() {
        if let Some(parent) = exe.parent() {
            let path = parent.join(file_name);
            if std::fs::write(&path, body).is_ok() {
                return Ok(path);
            }
        }
    }
    let path = env::temp_dir().join(file_name);
    std::fs::write(&path, body).map_err(|err| format!("Could not write setup script: {err}"))?;
    Ok(path)
}

#[cfg(target_os = "windows")]
fn firewall_script() -> Result<PathBuf, String> {
    if let Ok(path) = env::var("LEMO_GAMEPAD_FIREWALL_SCRIPT") {
        if !path.trim().is_empty() {
            return Ok(PathBuf::from(path));
        }
    }
    write_setup_script(
        &format!("lemoGamePad-firewall-{}.ps1", std::process::id()),
        EMBEDDED_FIREWALL,
    )
}

/// Files used by the elevated installer do not need to sit beside the app.
#[cfg(target_os = "windows")]
fn write_temp_file(file_name: &str, bytes: &[u8]) -> Result<PathBuf, String> {
    let path = env::temp_dir().join(file_name);
    std::fs::write(&path, bytes).map_err(|err| format!("Could not write {file_name}: {err}"))?;
    Ok(path)
}

#[cfg(target_os = "windows")]
fn vigem_installer() -> Result<PathBuf, String> {
    if let Ok(path) = env::var("LEMO_GAMEPAD_VIGEM_INSTALLER") {
        if !path.trim().is_empty() {
            return Ok(PathBuf::from(path));
        }
    }
    write_temp_file(
        &format!("lemoGamePad-ViGEmBus-{}.exe", std::process::id()),
        EMBEDDED_VIGEM,
    )
}

#[cfg(target_os = "linux")]
fn installer_path() -> Result<PathBuf, String> {
    if let Ok(path) = env::var("LEMO_GAMEPAD_INSTALLER") {
        if !path.trim().is_empty() {
            return Ok(PathBuf::from(path));
        }
    }
    write_setup_script(
        &format!("lemoGamePad-setup-{}.sh", std::process::id()),
        EMBEDDED_INSTALLER,
    )
}

fn fetch_release() -> UpdateResult {
    let program = if cfg!(windows) { "curl.exe" } else { "curl" };
    let output = match Command::new(program)
        .args(["-fsSL", "--max-time", "10", RELEASES_API])
        .output()
    {
        Ok(output) if output.status.success() => output,
        _ => return UpdateResult::Failed,
    };
    let value: Value = match serde_json::from_slice(&output.stdout) {
        Ok(value) => value,
        Err(_) => return UpdateResult::Failed,
    };
    let tag = match value.get("tag_name").and_then(Value::as_str) {
        Some(tag) => tag.trim().to_owned(),
        None => return UpdateResult::Failed,
    };
    if !newer(&tag, APP_VERSION) {
        return UpdateResult::UpToDate;
    }
    let url = value
        .get("html_url")
        .and_then(Value::as_str)
        .unwrap_or("https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases")
        .to_owned();
    UpdateResult::Available(Release { tag, url })
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
                       "rate": 60.5, "lost": 1, "battery": 77, "host": true}]
        }"#;
        let value: Value = serde_json::from_str(payload).unwrap();
        let status = Status::from_value(&value);
        assert!(status.running && status.locked);
        assert_eq!(status.web_key, "abc");
        assert_eq!(status.host_slot, 2);
        assert_eq!(status.problems, ["uinput missing"]);
        assert_eq!(status.peers.len(), 1);
        assert_eq!(status.peers[0].name, "phone");
        assert!((status.peers[0].rate - 60.5).abs() < f64::EPSILON);
        assert!((status.input_rate() - 60.5).abs() < f64::EPSILON);
        assert!(status.peers[0].host);
    }

    #[test]
    fn diagnostics_show_dashes_until_status_arrives() {
        let mut settings = Settings::default();
        settings.check_updates = false;
        let mut app = GuiApp::new(settings, true);
        assert_eq!(app.websocket_address(), "--");
        assert_eq!(app.paired_host_text(), "--");
        assert_eq!(app.connected_text(), "--");
        assert_eq!(app.input_rate_text(), "--");
        assert_eq!(app.last_error_text(), "--");
        app.error = "pairing failed".into();
        assert_eq!(app.last_error_text(), "pairing failed");
    }

    #[test]
    fn diagnostics_read_local_status_snapshot() {
        let mut settings = Settings::default();
        settings.check_updates = false;
        let mut app = GuiApp::new(settings, true);
        app.status = Status::from_value(&serde_json::json!({
            "running": true,
            "web_port": 9878,
            "hostname": "pc",
            "max_phones": 8,
            "host_slot": 2,
            "problems": ["old", "latest problem"],
            "peers": [
                {"slot": 2, "rate": 42.5, "host": true},
                {"slot": 3, "rate": 7.5, "host": false}
            ]
        }));
        assert_eq!(app.websocket_address(), "ws://pc:9878/ws");
        assert_eq!(app.paired_host_text(), "P2");
        assert_eq!(app.connected_text(), "2/8");
        assert_eq!(app.input_rate_text(), "50.0 pkt/s");
        assert_eq!(app.last_error_text(), "latest problem");
        app.pair_ip = "192.168.1.20".into();
        assert_eq!(app.websocket_address(), "ws://192.168.1.20:9878/ws");
    }

    #[test]
    fn title_shrinks_as_header_space_runs_out() {
        assert_eq!(title_for_budget(0.0), ("LGP", 12.0));
        assert_eq!(title_for_budget(100.0), ("LGP", 15.0));
        assert_eq!(title_for_budget(150.0), ("lemoGamePad", 12.0));
        assert_eq!(title_for_budget(250.0), ("lemoGamePad", 15.0));
        assert_eq!(title_for_budget(300.0), ("lemoGamePad ultimate", 14.0));
        assert_eq!(title_for_budget(400.0), ("lemoGamePad ultimate", 18.0));
    }

    #[test]
    fn compares_release_tags() {
        assert!(newer("v2.2.6", APP_VERSION));
        assert!(newer("2.3", APP_VERSION));
        assert!(!newer("v2.1.6", APP_VERSION));
        assert!(!newer("2.2.5", APP_VERSION));
        assert!(!newer("v2", "2.0.1"));
    }

    #[test]
    fn up_to_date_check_shows_no_releases() {
        let mut settings = Settings::default();
        settings.check_updates = false;
        let mut app = GuiApp::new(settings, true);
        app.update_error = "old error".into();
        app.update = Some(Release {
            tag: "v9.9.9".into(),
            url: "https://example.invalid".into(),
        });
        let (tx, rx) = mpsc::channel();
        app.update_rx = rx;
        tx.send(UpdateResult::UpToDate).unwrap();
        app.poll();
        assert!(app.update_error.is_empty());
        assert!(app.update.is_none());
        assert_eq!(app.update_info, "No releases available — you are up to date");
        tx.send(UpdateResult::Failed).unwrap();
        app.poll();
        assert_eq!(app.update_error, "Update check failed");
        assert!(app.update_info.is_empty());
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_setup_script_is_bundled() {
        assert!(EMBEDDED_INSTALLER.starts_with("#!/bin/sh"));
        assert!(EMBEDDED_INSTALLER.contains("uinput"));
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn windows_firewall_script_is_bundled() {
        assert!(EMBEDDED_FIREWALL.contains("param("));
        assert!(EMBEDDED_FIREWALL.contains("New-NetFirewallRule"));
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn windows_vigembus_installer_is_bundled() {
        assert_eq!(&EMBEDDED_VIGEM[..2], b"MZ");
        assert!(EMBEDDED_VIGEM.len() > 5_000_000);
    }

    #[test]
    fn install_result_is_reported() {
        let mut settings = Settings::default();
        settings.check_updates = false;
        let mut app = GuiApp::new(settings, true);
        let (tx, rx) = mpsc::channel();
        app.install_rx = rx;
        app.installing = true;
        tx.send(InstallMsg::Done(true)).unwrap();
        app.poll();
        assert!(!app.installing);
        assert!(app.install_ok);
        assert!(!app.install_status.is_empty());
        tx.send(InstallMsg::Done(false)).unwrap();
        app.poll();
        assert!(!app.install_ok);
        assert!(app.install_status.contains("failed"));
    }

    #[test]
    fn status_text_reflects_server_state() {
        let mut settings = Settings::default();
        settings.check_updates = false;
        let mut app = GuiApp::new(settings, true);
        assert_eq!(app.status_text(false), "STOPPED");
        app.status_wait = Instant::now() - Duration::from_secs(3);
        assert_eq!(app.status_text(true), "NO STATUS");
        app.status_seen = true;
        app.status.running = true;
        assert_eq!(app.status_text(true), "RUNNING");
    }

    #[test]
    fn builds_receiver_config() {
        let mut settings = Settings::default();
        settings.port = 9900;
        settings.no_video = true;
        settings.web_key = "key".into();
        settings.no_install_deps = true;
        settings.check_updates = false;
        let app = GuiApp::new(settings, true);
        let config = app.server_config().unwrap();
        assert_eq!(config.port, 9900);
        assert!(!config.video);
        assert!(!config.qr);
        assert!(!config.install_deps);
        assert_eq!(config.web_key, "key");
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
        Box::new(move |cc| {
            setup_fonts(&cc.egui_ctx);
            Ok(Box::new(GuiApp::new(settings, manual)))
        }),
    )
}
