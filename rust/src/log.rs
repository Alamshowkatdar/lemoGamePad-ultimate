//! Minimal timestamped logging. All lines go to stdout so the desktop panel
//! can read them exactly as it read the Python server's console output.

use std::io::{self, Write};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

static STDOUT_LOCK: Mutex<()> = Mutex::new(());

fn stamp() -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = now.as_secs();
    let hours = (secs / 3600) % 24;
    let mins = (secs / 60) % 60;
    let secs = secs % 60;
    format!("{hours:02}:{mins:02}:{secs:02}")
}

pub fn line(message: impl AsRef<str>) {
    let _guard = STDOUT_LOCK.lock().unwrap_or_else(|err| err.into_inner());
    let mut out = io::stdout().lock();
    let _ = writeln!(out, "[{}] {}", stamp(), message.as_ref());
    let _ = out.flush();
}

pub fn plain(message: impl AsRef<str>) {
    let _guard = STDOUT_LOCK.lock().unwrap_or_else(|err| err.into_inner());
    let mut out = io::stdout().lock();
    let _ = writeln!(out, "{}", message.as_ref());
    let _ = out.flush();
}
