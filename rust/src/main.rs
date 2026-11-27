mod cli;
mod log;
mod pad;
mod proto;
mod qr;
mod server;
mod util;
mod video;
mod web;

use server::Server;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

static STOP: AtomicBool = AtomicBool::new(false);

extern "C" fn request_stop() {
    STOP.store(true, Ordering::SeqCst);
}

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

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut config = cli::parse(&args);
    if config.list_ip {
        for ip in util::local_ips() {
            println!("{ip}");
        }
        return;
    }

    config.web_key = util::resolve_web_key(&config.web_key, &config.web_key_file);
    if config.web && config.web_key.is_empty() {
        eprintln!("Browser key must not be empty.");
        std::process::exit(2);
    }
    if config.install_deps {
        // The native binary has no Python packages to install. Windows users
        // still need two one-time system changes, both linked in the banner.
        #[cfg(windows)]
        println!(
            "Firewall: run pc\\allow_firewall.ps1 as Administrator once to allow UDP {} / TCP {} / TCP {}.",
            config.port,
            config.video_port,
            config.web_port
        );
    }

    let server = match Server::new(&config) {
        Ok(server) => Arc::new(server),
        Err(err) => {
            println!("Could not listen on UDP {}: {err}", config.port);
            println!("Another copy of lemoGamePad-server may already be running.");
            std::process::exit(1);
        }
    };
    server.attach_web();
    install_signal_handlers();

    let watcher = server.clone();
    std::thread::spawn(move || {
        while !STOP.load(Ordering::SeqCst) {
            std::thread::sleep(Duration::from_millis(200));
        }
        watcher.stop();
    });

    server.run();
    log::plain("bye");
}
