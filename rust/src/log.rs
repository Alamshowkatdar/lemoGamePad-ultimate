//! Minimal timestamped logging. All lines go to stdout so standalone runs keep
//! the same console output, and subscribers (the desktop panel) get a copy.

use std::io::{self, Write};
use std::sync::mpsc::Sender;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

static STDOUT_LOCK: Mutex<()> = Mutex::new(());
static SUBSCRIBERS: Mutex<Vec<Sender<String>>> = Mutex::new(Vec::new());

/// Receive a copy of every log line. Used by the embedded receiver in the GUI.
pub fn subscribe(sender: Sender<String>) {
    SUBSCRIBERS
        .lock()
        .unwrap_or_else(|err| err.into_inner())
        .push(sender);
}

fn broadcast(line: &str) {
    let mut subscribers = SUBSCRIBERS.lock().unwrap_or_else(|err| err.into_inner());
    subscribers.retain(|sender| sender.send(line.to_owned()).is_ok());
}

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
    let text = {
        let _guard = STDOUT_LOCK.lock().unwrap_or_else(|err| err.into_inner());
        let mut out = io::stdout().lock();
        let formatted = format!("[{}] {}", stamp(), message.as_ref());
        let _ = writeln!(out, "{formatted}");
        let _ = out.flush();
        formatted
    };
    broadcast(&text);
}

pub fn plain(message: impl AsRef<str>) {
    let text = {
        let _guard = STDOUT_LOCK.lock().unwrap_or_else(|err| err.into_inner());
        let mut out = io::stdout().lock();
        let text = message.as_ref().to_owned();
        let _ = writeln!(out, "{text}");
        let _ = out.flush();
        text
    };
    broadcast(&text);
}
