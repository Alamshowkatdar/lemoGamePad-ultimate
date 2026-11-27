//! Command-line parsing with the same defaults and validation as the Python server.

#[derive(Clone, Debug)]
pub struct Config {
    pub port: u16,
    pub pad: String,
    pub max_phones: usize,
    pub rumble: bool,
    pub video: bool,
    pub video_port: u16,
    pub web: bool,
    pub web_port: u16,
    pub web_key: String,
    pub web_key_file: String,
    pub video_fps: u32,
    pub video_width: u32,
    pub video_quality: u8,
    pub qr: bool,
    pub install_deps: bool,
    pub list_ip: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: 9876,
            pad: "x360".into(),
            max_phones: 8,
            rumble: true,
            video: true,
            video_port: 9877,
            web: true,
            web_port: 9878,
            web_key: String::new(),
            web_key_file: String::new(),
            video_fps: 60,
            video_width: 480,
            video_quality: 42,
            qr: true,
            install_deps: true,
            list_ip: false,
        }
    }
}

const USAGE: &str = "usage: lemoGamePad-server [options]\n\n  --port PORT            control UDP port (default 9876)\n  --pad x360|ds4         virtual pad type (default x360)\n  --max-phones N         phones sharing this PC, 1-8 (default 8)\n  --no-rumble            do not forward rumble to the phone\n  --no-video             disable the PC screen stream\n  --video-port PORT      screen stream TCP port (default control + 1)\n  --no-web               disable the browser gamepad\n  --web-port PORT        browser pad HTTP/WebSocket port (default control + 2)\n  --web-key KEY          fixed browser pairing key\n  --web-key-file PATH    store/reuse the browser key in PATH\n  --video-fps N          screen stream frame rate, 1-60 (default 60)\n  --video-width N        balanced stream width, 160-1280 (default 480)\n  --video-quality N      JPEG quality, 20-90 (default 42)\n  --no-qr                do not print the pairing QR code\n  --no-install-deps      do not install drivers/open firewall (Windows)\n  --list-ip              print local IPs and exit\n  -h, --help             show this help\n";

fn error(message: &str) -> ! {
    eprintln!("lemoGamePad-server: error: {message}");
    eprintln!("{USAGE}");
    std::process::exit(2);
}

fn integer(value: &str, flag: &str) -> i64 {
    value.parse::<i64>().unwrap_or_else(|_| error(&format!("argument {flag}: invalid integer value: '{value}'")))
}

pub fn parse(args: &[String]) -> Config {
    let mut config = Config::default();
    let mut port = i64::from(config.port);
    let mut max_phones = config.max_phones as i64;
    let mut video_fps = i64::from(config.video_fps);
    let mut video_width = i64::from(config.video_width);
    let mut video_quality = i64::from(config.video_quality);
    let mut explicit_video_port: Option<i64> = None;
    let mut explicit_web_port: Option<i64> = None;
    let mut index = 0usize;
    while index < args.len() {
        let raw = &args[index];
        if raw == "-h" || raw == "--help" {
            print!("{USAGE}");
            std::process::exit(0);
        }
        let (flag, inline) = match raw.split_once('=') {
            Some((flag, value)) => (flag, Some(value.to_string())),
            None => (raw.as_str(), None),
        };
        let mut take = || -> String {
            if let Some(value) = inline.clone() {
                return value;
            }
            index += 1;
            match args.get(index) {
                Some(value) => value.clone(),
                None => error(&format!("argument {flag}: expected one argument")),
            }
        };
        match flag {
            "--port" => port = integer(&take(), flag),
            "--pad" => {
                let value = take();
                if value != "x360" && value != "ds4" {
                    error("argument --pad: invalid choice: choose from 'x360', 'ds4'");
                }
                config.pad = value;
            }
            "--max-phones" => max_phones = integer(&take(), flag),
            "--video-port" => explicit_video_port = Some(integer(&take(), flag)),
            "--web-port" => explicit_web_port = Some(integer(&take(), flag)),
            "--web-key" => config.web_key = take(),
            "--web-key-file" => config.web_key_file = take(),
            "--video-fps" => video_fps = integer(&take(), flag),
            "--video-width" => video_width = integer(&take(), flag),
            "--video-quality" => video_quality = integer(&take(), flag),
            "--no-rumble" => config.rumble = false,
            "--no-video" => config.video = false,
            "--no-web" => config.web = false,
            "--no-qr" => config.qr = false,
            "--no-install-deps" => config.install_deps = false,
            "--list-ip" => config.list_ip = true,
            _ => error(&format!("unrecognized arguments: {raw}")),
        }
        index += 1;
    }

    if !(1..=8).contains(&max_phones) {
        error("--max-phones must be between 1 and 8");
    }
    if !(1..=60).contains(&video_fps) {
        error("--video-fps must be between 1 and 60 (60 is the ceiling)");
    }
    if !(160..=1280).contains(&video_width) {
        error("--video-width must be between 160 and 1280");
    }
    if !(20..=90).contains(&video_quality) {
        error("--video-quality must be between 20 and 90");
    }
    if !(1..=65535).contains(&port) {
        error("--port must be between 1 and 65535");
    }
    if let Some(value) = explicit_video_port {
        if !(1..=65535).contains(&value) {
            error("--video-port must be between 1 and 65535");
        }
    }
    if let Some(value) = explicit_web_port {
        if value != 0 && !(1..=65535).contains(&value) {
            error("--web-port must be between 1 and 65535");
        }
    }

    config.port = port as u16;
    config.max_phones = max_phones as usize;
    config.video_fps = video_fps as u32;
    config.video_width = video_width as u32;
    config.video_quality = video_quality as u8;

    let explicit_video = explicit_video_port;
    let default_video = port + 1;
    if default_video > 65535 && explicit_video.is_none() && config.video {
        error("the default video port would be 65536; use a lower --port or pass --video-port");
    }
    config.video_port = explicit_video.unwrap_or(default_video) as u16;

    if config.web {
        let explicit_web = explicit_web_port.filter(|value| *value != 0);
        let default_web = port + 2;
        if default_web > 65535 && explicit_web.is_none() {
            error("--web-port must be between 1 and 65535");
        }
        config.web_port = explicit_web.unwrap_or(default_web) as u16;
        if config.video && config.web_port == config.video_port {
            error("--web-port must differ from the screen stream port");
        }
        if config.web_port == config.port {
            error("--web-port must differ from the control port");
        }
    }

    if !config.web_key.is_empty() && !config.web_key_file.is_empty() {
        println!("Both --web-key and --web-key-file given; using --web-key.");
    }
    config
}
