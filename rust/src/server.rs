//! UDP control server. This is a direct port of `pc/vpad_server.py`: same
//! packet layouts, peer/slot rules, consent flow and status text.

use crate::cli::Config;
use crate::log;
use crate::pad::{backend_status, create_pad, RumbleEvent, VirtualPad};
use crate::proto::{
    offer, pong, qr_payload, rumble, seq_newer, slot_ask, slot_result, InputState, Header,
    MSG_DISCOVER, MSG_INPUT, MSG_PARTY_LOCK, MSG_PING, MSG_SLOT_REQUEST, MSG_SLOT_VOTE,
    SLOT_ACCEPTED, SLOT_CANCELLED, SLOT_DECLINED, SLOT_TIMEOUT, SLOT_UNAVAILABLE,
};
use crate::qr;
use crate::util::{
    json_string, local_ips, now, pack_addresses, random_u32, safe_ip, status_file_path,
    uri_escape,
};
use crate::video::VideoServer;
use crate::web::{cleanup_relay, WebHost, WebServer, WsConnection};
use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

const PEER_TIMEOUT: f64 = 8.0;
const STATUS_INTERVAL: f64 = 0.5;
const HOUSEKEEPING_INTERVAL: f64 = 0.2;
const SWAP_TIMEOUT: f64 = 10.0;
const SWAP_ASK_INTERVAL: f64 = 1.5;
const SWAP_COOLDOWN: f64 = 2.0;
const STALE_SLOT_GUARD: f64 = 5.0;
const ADDRESS_GRACE: f64 = 1.5;

#[derive(Clone, Copy)]
struct ResultMsg {
    token: u32,
    id: u32,
    code: u8,
    slot: u8,
}

struct Peer {
    token: u32,
    addr: SocketAddr,
    name: String,
    label: String,
    slot: usize,
    last_packet: f64,
    last_seen: f64,
    packet_count: u64,
    rate: f64,
    battery: u8,
    seq: u16,
    lost: u64,
    released: bool,
    rate_mark: u64,
    rate_mark_time: f64,
    web_send: bool,
    applied_seq: Option<u16>,
    last_apply: f64,
    move_guard: f64,
    stale_slot: u8,
    stale_until: f64,
}

impl Peer {
    fn describe(&self) -> String {
        format!("{} @ {}", self.name, self.addr.ip())
    }
}

struct Slot {
    index: usize,
    peer: Option<u32>,
    pad: Option<Box<dyn VirtualPad>>,
    rumble_state: (u8, u8),
}

#[derive(Clone, Copy)]
struct Swap {
    id: u32,
    requester: u32,
    target: u32,
    from_slot: u8,
    to_slot: u8,
    created_at: f64,
    last_ask: f64,
}

struct State {
    peers: HashMap<u32, Peer>,
    slots: Vec<Slot>,
    pending: HashMap<u32, InputState>,
    web_senders: HashMap<u32, Arc<WsConnection>>,
    swaps: HashMap<u32, Swap>,
    swap_seq: u32,
    swap_cooldown: HashMap<(u32, u32), f64>,
    locked: bool,
    host_token: Option<u32>,
    pads_dirty: bool,
    last_housekeeping: f64,
    full_logged_at: f64,
}

pub struct Server {
    port: u16,
    hostname: String,
    max_phones: usize,
    pad_kind: String,
    slot_kinds: Vec<String>,
    release_after: f64,
    qr_enabled: bool,
    web_port: u16,
    web_key: String,
    web_enabled: bool,
    rumble_enabled: AtomicBool,
    running: AtomicBool,
    sock: UdpSocket,
    state: Mutex<State>,
    /// Per-slot send ordering. Python holds the same lock across state change
    /// and packet send so a stop can never be followed by a stale refresh.
    rumble_locks: Vec<Mutex<()>>,
    rumble_tx: Sender<RumbleEvent>,
    rumble_rx: Mutex<Receiver<RumbleEvent>>,
    web: Mutex<Option<WebServer<Server>>>,
    video: Mutex<Option<VideoServer>>,
    ip_cache: Mutex<(Vec<[u8; 4]>, f64)>,
    status_thread: Mutex<Option<JoinHandle<()>>>,
}

impl Server {
    pub fn new(config: &Config) -> io::Result<Self> {
        let sock = UdpSocket::bind(("0.0.0.0", config.port))?;
        let _ = sock.set_broadcast(true);
        #[cfg(unix)]
        unsafe {
            use std::os::fd::AsRawFd;
            let size = 512 * 1024 as libc::c_int;
            for option in [libc::SO_RCVBUF, libc::SO_SNDBUF] {
                libc::setsockopt(
                    sock.as_raw_fd(),
                    libc::SOL_SOCKET,
                    option,
                    &size as *const libc::c_int as *const libc::c_void,
                    std::mem::size_of::<libc::c_int>() as libc::socklen_t,
                );
            }
        }
        sock.set_read_timeout(Some(Duration::from_millis(25)))?;

        let mut slot_kinds = vec![config.pad.clone(); config.max_phones];
        if cfg!(windows) && config.pad == "x360" && config.max_phones > 4 {
            for kind in slot_kinds.iter_mut().skip(4) {
                *kind = "ds4".into();
            }
        }
        let (rumble_tx, rumble_rx) = mpsc::channel();
        let state = State {
            peers: HashMap::new(),
            slots: (0..config.max_phones)
                .map(|index| Slot {
                    index,
                    peer: None,
                    pad: None,
                    rumble_state: (0, 0),
                })
                .collect(),
            pending: HashMap::new(),
            web_senders: HashMap::new(),
            swaps: HashMap::new(),
            swap_seq: 0,
            swap_cooldown: HashMap::new(),
            locked: false,
            host_token: None,
            pads_dirty: false,
            last_housekeeping: 0.0,
            full_logged_at: 0.0,
        };
        let server = Self {
            port: config.port,
            hostname: crate::util::hostname(),
            max_phones: config.max_phones,
            pad_kind: config.pad.clone(),
            slot_kinds,
            release_after: 1.5,
            qr_enabled: config.qr,
            web_port: config.web_port,
            web_key: config.web_key.clone(),
            web_enabled: config.web,
            rumble_enabled: AtomicBool::new(config.rumble),
            running: AtomicBool::new(true),
            sock,
            state: Mutex::new(state),
            rumble_locks: (0..config.max_phones).map(|_| Mutex::new(())).collect(),
            rumble_tx,
            rumble_rx: Mutex::new(rumble_rx),
            web: Mutex::new(None),
            video: Mutex::new(if config.video {
                Some(VideoServer::new(
                    config.video_port,
                    config.video_fps,
                    config.video_width,
                    config.video_quality,
                    config.max_phones,
                ))
            } else {
                None
            }),
            ip_cache: Mutex::new((pack_addresses(&local_ips()), now())),
            status_thread: Mutex::new(None),
        };
        // Probe the first pad before the banner so a missing driver is visible.
        {
            let mut state = server.state.lock().unwrap_or_else(|err| err.into_inner());
            server.ensure_pad(&mut state, 0);
        }
        Ok(server)
    }

    pub fn attach_web(self: &Arc<Self>) {
        if !self.web_enabled {
            return;
        }
        let web = WebServer::new(self.web_port, self.web_key.clone(), self.clone());
        *self.web.lock().unwrap_or_else(|err| err.into_inner()) = Some(web);
    }

    pub fn run(self: &Arc<Self>) {
        if let Some(video) = self.video.lock().unwrap_or_else(|err| err.into_inner()).as_mut() {
            video.start();
        }
        if let Some(web) = self.web.lock().unwrap_or_else(|err| err.into_inner()).as_mut() {
            if let Err(err) = web.start() {
                log::line(format!("[web] browser pad could not start: {err}"));
            }
        }
        self.print_banner();
        self.publish_status(true);
        let worker = self.clone();
        *self.status_thread.lock().unwrap_or_else(|err| err.into_inner()) =
            Some(thread::spawn(move || worker.status_loop()));

        while self.running.load(Ordering::Relaxed) {
            let mut buffer = [0u8; 2048];
            match self.sock.recv_from(&mut buffer) {
                Ok((count, addr)) => self.dispatch(&buffer[..count], addr, now()),
                Err(ref err)
                    if matches!(
                        err.kind(),
                        io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                    ) => {}
                Err(_) => break,
            }
            let time = now();
            self.sync_pads();
            self.flush_inputs(time);
            self.housekeeping(time);
            self.drain_rumble();
        }
        self.shutdown();
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::Relaxed);
    }

    pub fn shutdown(&self) {
        self.running.store(false, Ordering::Relaxed);
        if let Some(thread) = self
            .status_thread
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .take()
        {
            let _ = thread.join();
        }
        if let Some(web) = self.web.lock().unwrap_or_else(|err| err.into_inner()).as_mut() {
            web.stop();
        }
        if let Some(video) = self.video.lock().unwrap_or_else(|err| err.into_inner()).as_mut() {
            video.stop();
        }
        self.release_pads();
        self.publish_status(false);
    }

    pub fn release_pads(&self) {
        let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        let indices: Vec<usize> = state.slots.iter().map(|slot| slot.index).collect();
        for index in indices {
            self.close_pad(&mut state, index);
        }
    }

    // ------------------------------------------------------------------ pads
    fn ensure_pad(&self, state: &mut State, index: usize) {
        if state.slots[index].pad.is_none() {
            let kind = self.slot_kinds[index].clone();
            let pad = create_pad(&kind, self.rumble_tx.clone(), index);
            state.slots[index].pad = Some(pad);
        }
    }

    fn close_pad(&self, state: &mut State, index: usize) {
        let Some(mut pad) = state.slots[index].pad.take() else {
            return;
        };
        pad.apply(&InputState::default());
        pad.close();
    }

    fn sync_pads(&self) {
        let active: Vec<usize> = {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            if !state.pads_dirty {
                return;
            }
            state.pads_dirty = false;
            let mut active: Vec<usize> = state
                .slots
                .iter()
                .filter(|slot| slot.peer.is_some())
                .map(|slot| slot.index)
                .collect();
            if active.is_empty() {
                active.push(0);
            }
            active
        };
        let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        for index in 0..state.slots.len() {
            if !active.contains(&index) && state.slots[index].pad.is_some() {
                self.close_pad(&mut state, index);
            }
        }
        for index in active {
            self.ensure_pad(&mut state, index);
        }
    }

    fn slot_reset(&self, state: &mut State, index: usize) {
        if let Some(pad) = state.slots[index].pad.as_mut() {
            pad.apply(&InputState::default());
        }
        state.slots[index].peer = None;
        state.slots[index].rumble_state = (0, 0);
        state.pads_dirty = true;
    }

    fn slot_of(state: &State, token: u32) -> Option<usize> {
        state
            .slots
            .iter()
            .find(|slot| slot.peer == Some(token))
            .map(|slot| slot.index)
    }

    // --------------------------------------------------------------- discovery
    fn handle_discover(&self, data: &[u8], addr: SocketAddr) {
        let Some(header) = Header::parse(data) else { return };
        let mut token = header.token;
        if token == 0 {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            while token == 0 || state.peers.contains_key(&token) {
                token = random_u32();
            }
        }
        let addrs = self
            .ip_cache
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .0
            .clone();
        let reply = offer(
            header.seq,
            token,
            &self.hostname,
            self.pad_kind == "ds4",
            &addrs,
        );
        let _ = self.sock.send_to(&reply, addr);
    }

    // ------------------------------------------------------------------- peers
    fn can_rebind(peer: &Peer, addr: SocketAddr, time: f64) -> bool {
        peer.addr.ip() == addr.ip() || time - peer.last_seen >= ADDRESS_GRACE
    }

    fn register_peer(
        &self,
        token: u32,
        addr: SocketAddr,
        time: f64,
        want_slot: u8,
        label: &str,
        web_send: bool,
    ) -> (Option<u32>, bool, &'static str) {
        let mut moved_from: isize = -1;
        let mut is_new = false;
        let (host_changed, notices) = {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let mut rebind = false;
            let mut move_request = None;
            let locked_for_peer = state.locked && state.host_token != Some(token);
            if state.peers.contains_key(&token) {
                {
                    let peer = state.peers.get_mut(&token).expect("peer exists");
                    if peer.addr != addr {
                        if !Self::can_rebind(peer, addr, time) {
                            return (None, false, "move");
                        }
                        peer.addr = addr;
                        peer.applied_seq = None;
                        peer.seq = 0;
                        rebind = true;
                    }
                    if web_send {
                        peer.web_send = true;
                    }
                    peer.last_seen = time;
                    if time >= peer.stale_until {
                        peer.stale_slot = 0;
                    }
                    if !locked_for_peer && (1..=self.max_phones).contains(&usize::from(want_slot)) {
                        move_request = Some((peer.stale_slot, peer.move_guard));
                    }
                }
                if rebind {
                    state.pending.remove(&token);
                }
                if let Some((stale, guard)) = move_request {
                    let wanted = usize::from(want_slot);
                    let current = Self::slot_of(&state, token);
                    if stale != wanted as u8
                        && time >= guard
                        && state.slots[wanted - 1].peer.is_none()
                        && self.move_peer_locked(&mut state, token, wanted - 1)
                    {
                        moved_from = current.map(|slot| slot as isize).unwrap_or(-1);
                    }
                }
            } else {
                if state.peers.len() >= self.max_phones {
                    return (None, false, "full");
                }
                let index = if state.slots[0].peer.is_none() {
                    0
                } else {
                    let wanted = usize::from(want_slot);
                    if (1..=self.max_phones).contains(&wanted)
                        && state.slots[wanted - 1].peer.is_none()
                    {
                        wanted - 1
                    } else {
                        match state.slots.iter().position(|slot| slot.peer.is_none()) {
                            Some(index) => index,
                            None => return (None, false, "full"),
                        }
                    }
                };
                state.peers.insert(
                    token,
                    Peer {
                        token,
                        addr,
                        name: format!("{label} {}", index + 1),
                        label: label.to_string(),
                        slot: index,
                        last_packet: 0.0,
                        last_seen: time,
                        packet_count: 0,
                        rate: 0.0,
                        battery: 255,
                        seq: 0,
                        lost: 0,
                        released: false,
                        rate_mark: 0,
                        rate_mark_time: time,
                        web_send,
                        applied_seq: None,
                        last_apply: 0.0,
                        move_guard: 0.0,
                        stale_slot: 0,
                        stale_until: 0.0,
                    },
                );
                state.slots[index].peer = Some(token);
                state.pads_dirty = true;
                is_new = true;
            }
            let skip_token = if moved_from == 0 { Some(token) } else { None };
            self.sync_host_locked(&mut state, skip_token)
        };
        for notice in notices {
            self.send_result(notice);
        }
        if host_changed {
            log::line("party host is now the player on P1");
            self.broadcast_party_state();
        }
        if moved_from >= 0 {
            let from = moved_from as usize;
            if let Some(peer) = self.state.lock().unwrap_or_else(|err| err.into_inner()).peers.get(&token) {
                log::line(format!(
                    "controller moved: {} (pad {} -> {})",
                    peer.describe(),
                    from + 1,
                    peer.slot + 1
                ));
            }
        }
        (Some(token), is_new, "")
    }

    fn drop_peer(&self, token: u32) {
        let mut host_left = false;
        let mut host_locked = false;
        let (description, host_changed, cancelled) = {
            let mut cancelled = Vec::new();
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let Some(peer) = state.peers.get(&token) else { return };
            let description = peer.describe();
            state.peers.remove(&token);
            if state.host_token == Some(token) {
                host_locked = state.locked;
                state.locked = false;
                host_left = true;
            }
            if let Some(index) = Self::slot_of(&state, token) {
                self.slot_reset(&mut state, index);
            }
            let swaps: Vec<Swap> = state.swaps.values().copied().collect();
            for swap in swaps {
                if swap.requester != token && swap.target != token {
                    continue;
                }
                state.swaps.remove(&swap.id);
                let other = if swap.requester == token { swap.target } else { swap.requester };
                if !state.peers.contains_key(&other) {
                    continue;
                }
                let slot = Self::slot_of(&state, other).unwrap_or(0) as u8 + 1;
                let code = if swap.target == token { SLOT_UNAVAILABLE } else { SLOT_CANCELLED };
                cancelled.push(ResultMsg { token: other, id: swap.id, code, slot });
            }
            let result = self.sync_host_locked(&mut state, None);
            cancelled.extend(result.1);
            (description, result.0, cancelled)
        };
        for notice in cancelled {
            self.send_result(notice);
        }
        log::line(format!("controller disconnected: {description}"));
        if host_left && host_locked {
            log::line("party host left; lock released");
        }
        if host_changed {
            log::line("party host is now the player on P1");
            self.broadcast_party_state();
        }
    }

    fn move_peer_locked(&self, state: &mut State, token: u32, index: usize) -> bool {
        let current = Self::slot_of(state, token);
        if current == Some(index) || state.slots[index].peer.is_some() {
            return false;
        }
        let time = now();
        let (label, stale) = {
            let peer = state.peers.get_mut(&token).expect("peer exists");
            let stale = current.map(|slot| slot as u8 + 1).unwrap_or(0);
            peer.stale_slot = stale;
            peer.stale_until = time + STALE_SLOT_GUARD;
            peer.applied_seq = None;
            peer.move_guard = peer.move_guard.max(time + 1.5);
            (peer.label.clone(), stale)
        };
        let _ = stale;
        if let Some(slot) = current {
            self.slot_reset(state, slot);
        }
        state.slots[index].peer = Some(token);
        if let Some(peer) = state.peers.get_mut(&token) {
            peer.slot = index;
            peer.name = format!("{} {}", label, index + 1);
        }
        state.pads_dirty = true;
        state.pending.remove(&token);
        true
    }

    fn next_swap_id(state: &mut State) -> u32 {
        state.swap_seq = state.swap_seq.wrapping_add(1);
        if state.swap_seq == 0 {
            state.swap_seq = 1;
        }
        state.swap_seq
    }

    fn sync_host_locked(&self, state: &mut State, skip: Option<u32>) -> (bool, Vec<ResultMsg>) {
        let mut results = Vec::new();
        if !state.peers.is_empty() && state.slots[0].peer.is_none() {
            let promoted = state
                .peers
                .keys()
                .filter(|token| Some(**token) != skip)
                .min()
                .copied()
                .or_else(|| state.peers.keys().min().copied());
            if let Some(token) = promoted {
                if self.move_peer_locked(state, token, 0) {
                    results.push(ResultMsg {
                        token,
                        id: Self::next_swap_id(state),
                        code: SLOT_ACCEPTED,
                        slot: 1,
                    });
                }
            }
        }
        let new_token = state.slots.first().and_then(|slot| slot.peer);
        let changed = new_token != state.host_token;
        state.host_token = new_token;
        if new_token.is_none() {
            state.locked = false;
        }
        (changed, results)
    }

    // ------------------------------------------------------------------- input
    fn submit_input(
        &self,
        token: u32,
        addr: SocketAddr,
        time: f64,
        state_data: InputState,
        want_slot: u8,
        label: &str,
        web_send: bool,
    ) -> u32 {
        if token == 0 {
            return 0;
        }
        let (peer_token, fresh, reason) =
            self.register_peer(token, addr, time, want_slot, label, web_send);
        let Some(token) = peer_token else {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            if time - state.full_logged_at > 10.0 {
                state.full_logged_at = time;
                drop(state);
                if reason == "move" {
                    log::line(format!(
                        "ignored {}: token is still active at another address",
                        addr.ip()
                    ));
                } else {
                    log::line(format!(
                        "ignored {}: all {} controller slots are in use",
                        addr.ip(),
                        self.max_phones
                    ));
                }
            }
            return 0;
        };
        let slot = {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let Some(peer) = state.peers.get_mut(&token) else { return 0 };
            let gap = if peer.last_packet != 0.0 { time - peer.last_packet } else { 0.0 };
            peer.last_packet = time;
            peer.last_seen = time;
            peer.packet_count += 1;
            peer.battery = state_data.battery;
            if web_send {
                peer.web_send = true;
            }
            let prev = peer.seq;
            peer.released = false;
            if gap > 2.0 {
                peer.applied_seq = None;
                peer.seq = state_data.seq;
            } else if prev == 0 || seq_newer(state_data.seq, Some(prev)) {
                let distance = state_data.seq.wrapping_sub(prev);
                if prev != 0 && distance > 0 && distance < 300 {
                    peer.lost += u64::from(distance - 1);
                }
                peer.seq = state_data.seq;
            }
            peer.slot as u32 + 1
        };
        if fresh {
            if let Some(peer) = self.state.lock().unwrap_or_else(|err| err.into_inner()).peers.get(&token) {
                log::line(format!(
                    "controller connected: {} (pad {}/{})",
                    peer.describe(),
                    peer.slot + 1,
                    self.max_phones
                ));
            }
        }
        {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let replace = match state.pending.get(&token) {
                None => true,
                Some(old) => seq_newer(state_data.seq, Some(old.seq)),
            };
            if replace {
                state.pending.insert(token, state_data);
            }
        }
        slot
    }

    fn handle_input(&self, data: &[u8], addr: SocketAddr, time: f64) {
        let Some(header) = Header::parse(data) else { return };
        if header.token == 0 {
            return;
        }
        let Some(state) = InputState::parse(data, header) else { return };
        self.submit_input(header.token, addr, time, state, state.want_slot, "phone", false);
    }

    fn flush_inputs(&self, time: f64) {
        let pending: HashMap<u32, InputState> = {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            std::mem::take(&mut state.pending)
        };
        if pending.is_empty() {
            return;
        }
        let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        for (token, input) in pending {
            let Some(index) = Self::slot_of(&state, token) else { continue };
            if state.slots[index].peer != Some(token) {
                continue;
            }
            // Match the Python loop: with no virtual pad attached the frame is
            // skipped completely, including the sequence resync bookkeeping.
            if state.slots[index].pad.is_none() {
                continue;
            }
            let apply = {
                let peer = state.peers.get_mut(&token).expect("peer exists");
                if !seq_newer(input.seq, peer.applied_seq) {
                    if time - peer.last_apply < 0.5 {
                        false
                    } else {
                        peer.seq = input.seq;
                        true
                    }
                } else {
                    true
                }
            };
            if !apply {
                continue;
            }
            if let Some(pad) = state.slots[index].pad.as_mut() {
                pad.apply(&input);
            }
            let peer = state.peers.get_mut(&token).expect("peer exists");
            peer.applied_seq = Some(input.seq);
            peer.last_apply = time;
        }
    }

    // -------------------------------------------------------------------- ping
    fn handle_ping(&self, data: &[u8], addr: SocketAddr, time: f64, header: Header) {
        let (peer_present, assigned_slot, party_locked, is_host, loss_permille) = {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            if !state.peers.contains_key(&header.token) {
                let mut reply = Header::pack(6, header.seq, header.token);
                if data.len() >= 16 {
                    reply.extend_from_slice(&data[12..16]);
                }
                let _ = self.sock.send_to(&reply, addr);
                return;
            }
            let mut rebind = false;
            let (slot, loss) = {
                let peer = state.peers.get_mut(&header.token).expect("peer exists");
                if peer.addr != addr {
                    if !Self::can_rebind(peer, addr, time) {
                        return;
                    }
                    peer.addr = addr;
                    peer.applied_seq = None;
                    peer.seq = 0;
                    rebind = true;
                }
                peer.last_seen = time;
                let received = peer.packet_count.saturating_sub(peer.rate_mark);
                let total = received + peer.lost;
                let loss = if total > 0 {
                    // Python uses round(), which resolves exact .5 ties to even.
                    (peer.lost as f64 * 1000.0 / total as f64)
                        .round_ties_even()
                        .clamp(0.0, 1000.0) as u16
                } else {
                    0
                };
                (peer.slot as u8 + 1, loss)
            };
            if rebind {
                state.pending.remove(&header.token);
            }
            (
                true,
                slot,
                state.locked,
                state.host_token == Some(header.token),
                loss,
            )
        };
        let reply = pong(
            header.seq,
            header.token,
            data.get(12..16).and_then(|bytes| bytes.try_into().ok()),
            peer_present.then_some(loss_permille),
            peer_present.then_some(assigned_slot),
            party_locked,
            is_host,
        );
        let _ = self.sock.send_to(&reply, addr);
    }

    // --------------------------------------------------------------- pad swaps
    fn send_result(&self, message: ResultMsg) {
        let (web, addr) = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let Some(peer) = state.peers.get(&message.token) else { return };
            (peer.web_send, peer.addr)
        };
        if web {
            self.web_event(
                message.token,
                &format!(
                    "{{\"t\":\"sr\",\"id\":{},\"c\":{},\"n\":{}}}",
                    message.id, message.code, message.slot
                ),
            );
            return;
        }
        let packet = slot_result(message.token, message.id, message.code, message.slot);
        for _ in 0..3 {
            if self.sock.send_to(&packet, addr).is_err() {
                break;
            }
        }
    }

    fn send_ask(&self, swap: &Swap) {
        let (web, addr) = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let Some(peer) = state.peers.get(&swap.target) else { return };
            (peer.web_send, peer.addr)
        };
        if web {
            self.web_event(
                swap.target,
                &format!(
                    "{{\"t\":\"ask\",\"id\":{},\"f\":{}}}",
                    swap.id, swap.from_slot
                ),
            );
            return;
        }
        let packet = slot_ask(swap.target, swap.id, swap.from_slot);
        let _ = self.sock.send_to(&packet, addr);
    }

    fn cancel_pending(&self, requester: u32) {
        let mut results = Vec::new();
        {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let existing = state
                .swaps
                .values()
                .find(|swap| swap.requester == requester)
                .copied();
            if let Some(swap) = existing {
                state.swaps.remove(&swap.id);
                let slot = Self::slot_of(&state, swap.target).unwrap_or(0) as u8 + 1;
                results.push(ResultMsg {
                    token: swap.target,
                    id: swap.id,
                    code: SLOT_CANCELLED,
                    slot,
                });
            }
        }
        for notice in results {
            self.send_result(notice);
        }
    }

    fn apply_swap_locked(&self, state: &mut State, swap: &Swap) -> Option<(u8, u8)> {
        if state.peers.get(&swap.requester).map(|peer| peer.token) != Some(swap.requester)
            || state.peers.get(&swap.target).map(|peer| peer.token) != Some(swap.target)
        {
            return None;
        }
        let first = Self::slot_of(state, swap.requester)?;
        let second = Self::slot_of(state, swap.target)?;
        if first == second
            || first != usize::from(swap.from_slot.saturating_sub(1))
            || second != usize::from(swap.to_slot.saturating_sub(1))
        {
            return None;
        }
        let time = now();
        let requester_label = {
            let requester = state.peers.get_mut(&swap.requester)?;
            requester.stale_slot = first as u8 + 1;
            requester.stale_until = time + STALE_SLOT_GUARD;
            requester.applied_seq = None;
            requester.released = false;
            requester.move_guard = requester.move_guard.max(time + 1.5);
            requester.label.clone()
        };
        let target_label = {
            let target = state.peers.get_mut(&swap.target)?;
            target.stale_slot = second as u8 + 1;
            target.stale_until = time + STALE_SLOT_GUARD;
            target.applied_seq = None;
            target.released = false;
            target.move_guard = target.move_guard.max(time + 1.5);
            target.label.clone()
        };
        if let Some(pad) = state.slots[first].pad.as_mut() {
            pad.apply(&InputState::default());
        }
        if let Some(pad) = state.slots[second].pad.as_mut() {
            pad.apply(&InputState::default());
        }
        state.slots[first].rumble_state = (0, 0);
        state.slots[second].rumble_state = (0, 0);
        state.slots[first].peer = Some(swap.target);
        state.slots[second].peer = Some(swap.requester);
        if let Some(peer) = state.peers.get_mut(&swap.target) {
            peer.slot = first;
            peer.name = format!("{} {}", target_label, first + 1);
        }
        if let Some(peer) = state.peers.get_mut(&swap.requester) {
            peer.slot = second;
            peer.name = format!("{} {}", requester_label, second + 1);
        }
        state.pads_dirty = true;
        state.pending.remove(&swap.requester);
        state.pending.remove(&swap.target);
        Some((second as u8 + 1, first as u8 + 1))
    }

    fn begin_swap(&self, requester: u32, target_slot: u8) {
        if target_slot == 0 || target_slot as usize > self.max_phones {
            self.cancel_pending(requester);
            return;
        }
        let time = now();
        let (asks, results, host_changed) = {
            let mut asks = Vec::new();
            let mut results = Vec::new();
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let locked_for_requester = state.locked && state.host_token != Some(requester);
            let current = Self::slot_of(&state, requester);
            if locked_for_requester {
                if let Some(current) = current {
                    results.push(ResultMsg {
                        token: requester,
                        id: Self::next_swap_id(&mut state),
                        code: SLOT_UNAVAILABLE,
                        slot: current as u8 + 1,
                    });
                }
            } else {
                if state.peers.get(&requester).is_none() {
                    return;
                }
                let Some(current) = current else { return };
                let from_slot = current as u8 + 1;
                let existing = state
                    .swaps
                    .values()
                    .find(|swap| swap.requester == requester)
                    .copied();
                if let Some(peer) = state.peers.get_mut(&requester) {
                    peer.stale_slot = 0;
                }
                if state.locked && state.host_token != Some(requester) {
                    if let Some(swap) = existing {
                        state.swaps.remove(&swap.id);
                        let slot = Self::slot_of(&state, swap.target).unwrap_or(0) as u8 + 1;
                        results.push(ResultMsg {
                            token: swap.target,
                            id: swap.id,
                            code: SLOT_CANCELLED,
                            slot,
                        });
                    }
                    results.push(ResultMsg {
                        token: requester,
                        id: Self::next_swap_id(&mut state),
                        code: SLOT_UNAVAILABLE,
                        slot: from_slot,
                    });
                } else if target_slot == from_slot {
                    if let Some(swap) = existing {
                        state.swaps.remove(&swap.id);
                        let slot = Self::slot_of(&state, swap.target).unwrap_or(0) as u8 + 1;
                        results.push(ResultMsg {
                            token: swap.target,
                            id: swap.id,
                            code: SLOT_CANCELLED,
                            slot,
                        });
                    }
                    results.push(ResultMsg {
                        token: requester,
                        id: Self::next_swap_id(&mut state),
                        code: SLOT_ACCEPTED,
                        slot: from_slot,
                    });
                } else {
                    let target = state.slots[usize::from(target_slot) - 1].peer;
                    match target {
                        None => {
                            if let Some(swap) = existing {
                                state.swaps.remove(&swap.id);
                                let slot = Self::slot_of(&state, swap.target).unwrap_or(0) as u8 + 1;
                                results.push(ResultMsg {
                                    token: swap.target,
                                    id: swap.id,
                                    code: SLOT_CANCELLED,
                                    slot,
                                });
                            }
                            self.move_peer_locked(&mut state, requester, usize::from(target_slot) - 1);
                            results.push(ResultMsg {
                                token: requester,
                                id: Self::next_swap_id(&mut state),
                                code: SLOT_ACCEPTED,
                                slot: target_slot,
                            });
                        }
                        Some(target) if target == requester => {
                            if let Some(swap) = existing {
                                state.swaps.remove(&swap.id);
                                let slot = Self::slot_of(&state, swap.target).unwrap_or(0) as u8 + 1;
                                results.push(ResultMsg {
                                    token: swap.target,
                                    id: swap.id,
                                    code: SLOT_CANCELLED,
                                    slot,
                                });
                            }
                            results.push(ResultMsg {
                                token: requester,
                                id: Self::next_swap_id(&mut state),
                                code: SLOT_ACCEPTED,
                                slot: from_slot,
                            });
                        }
                        Some(target) => {
                            let mut handled = false;
                            let mutual = state
                                .swaps
                                .values()
                                .find(|swap| swap.requester == target && swap.target == requester)
                                .copied();
                            if let Some(mutual) = mutual {
                                handled = true;
                                state.swaps.remove(&mutual.id);
                                if let Some(swap) = existing {
                                    if swap.id != mutual.id {
                                        state.swaps.remove(&swap.id);
                                        let slot = Self::slot_of(&state, swap.target).unwrap_or(0) as u8 + 1;
                                        results.push(ResultMsg {
                                            token: swap.target,
                                            id: swap.id,
                                            code: SLOT_CANCELLED,
                                            slot,
                                        });
                                    }
                                }
                                match self.apply_swap_locked(&mut state, &mutual) {
                                    Some((requester_new, target_new)) => {
                                        results.push(ResultMsg {
                                            token: requester,
                                            id: mutual.id,
                                            code: SLOT_ACCEPTED,
                                            slot: requester_new,
                                        });
                                        results.push(ResultMsg {
                                            token: target,
                                            id: mutual.id,
                                            code: SLOT_ACCEPTED,
                                            slot: target_new,
                                        });
                                    }
                                    None => {
                                        results.push(ResultMsg {
                                            token: requester,
                                            id: mutual.id,
                                            code: SLOT_UNAVAILABLE,
                                            slot: from_slot,
                                        });
                                        let slot = Self::slot_of(&state, target).unwrap_or(0) as u8 + 1;
                                        results.push(ResultMsg {
                                            token: target,
                                            id: mutual.id,
                                            code: SLOT_UNAVAILABLE,
                                            slot,
                                        });
                                    }
                                }
                            } else if state
                                .swap_cooldown
                                .get(&(requester, target))
                                .is_some_and(|until| *until > time)
                            {
                                handled = true;
                                results.push(ResultMsg {
                                    token: requester,
                                    id: Self::next_swap_id(&mut state),
                                    code: SLOT_DECLINED,
                                    slot: from_slot,
                                });
                            } else if let Some(swap) = existing {
                                if swap.target == target {
                                    handled = true;
                                    if time - swap.last_ask >= SWAP_ASK_INTERVAL {
                                        let mut updated = swap;
                                        updated.last_ask = time;
                                        state.swaps.insert(updated.id, updated);
                                        asks.push(updated);
                                    }
                                }
                            }
                            // A different target is a retarget, not a refusal:
                            // cancel the old ask and issue a fresh one. Python
                            // only rejects swaps involving *other* requests.
                            if !handled
                                && state.swaps.values().any(|swap| {
                                    !existing.is_some_and(|old| old.id == swap.id)
                                        && (swap.requester == requester
                                            || swap.requester == target
                                            || swap.target == requester
                                            || swap.target == target)
                                })
                            {
                                handled = true;
                                results.push(ResultMsg {
                                    token: requester,
                                    id: Self::next_swap_id(&mut state),
                                    code: SLOT_UNAVAILABLE,
                                    slot: from_slot,
                                });
                            }
                            if !handled {
                                if let Some(swap) = existing {
                                    state.swaps.remove(&swap.id);
                                    let slot = Self::slot_of(&state, swap.target).unwrap_or(0) as u8 + 1;
                                    results.push(ResultMsg {
                                        token: swap.target,
                                        id: swap.id,
                                        code: SLOT_CANCELLED,
                                        slot,
                                    });
                                }
                                let id = Self::next_swap_id(&mut state);
                                let swap = Swap {
                                    id,
                                    requester,
                                    target,
                                    from_slot,
                                    to_slot: target_slot,
                                    created_at: time,
                                    last_ask: time,
                                };
                                state.swaps.insert(id, swap);
                                asks.push(swap);
                            }
                        }
                    }
                }
            }
            let skip = if state.slots[0].peer.is_none()
                && state.peers.get(&requester).is_some_and(|peer| peer.slot != 0)
            {
                Some(requester)
            } else {
                None
            };
            let result = self.sync_host_locked(&mut state, skip);
            results.extend(result.1);
            (asks, results, result.0)
        };
        for swap in asks {
            self.send_ask(&swap);
        }
        for notice in results {
            self.send_result(notice);
        }
        if host_changed {
            log::line("party host is now the player on P1");
            self.broadcast_party_state();
        }
    }

    fn resolve_swap(&self, voter: u32, request_id: u32, accept: bool) {
        let time = now();
        let (results, host_changed) = {
            let mut results = Vec::new();
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            if state.locked && state.host_token != Some(voter) {
                return;
            }
            let Some(swap) = state.swaps.get(&request_id).copied() else { return };
            if swap.target != voter {
                return;
            }
            state.swaps.remove(&request_id);
            if accept {
                match self.apply_swap_locked(&mut state, &swap) {
                    Some((requester_new, target_new)) => {
                        results.push(ResultMsg {
                            token: swap.requester,
                            id: request_id,
                            code: SLOT_ACCEPTED,
                            slot: requester_new,
                        });
                        results.push(ResultMsg {
                            token: voter,
                            id: request_id,
                            code: SLOT_ACCEPTED,
                            slot: target_new,
                        });
                    }
                    None => {
                        let requester_slot = Self::slot_of(&state, swap.requester).unwrap_or(0) as u8 + 1;
                        let voter_slot = Self::slot_of(&state, voter).unwrap_or(0) as u8 + 1;
                        results.push(ResultMsg {
                            token: swap.requester,
                            id: request_id,
                            code: SLOT_UNAVAILABLE,
                            slot: requester_slot,
                        });
                        results.push(ResultMsg {
                            token: voter,
                            id: request_id,
                            code: SLOT_UNAVAILABLE,
                            slot: voter_slot,
                        });
                    }
                }
            } else {
                state.swap_cooldown.insert((swap.requester, voter), time + SWAP_COOLDOWN);
                let slot = Self::slot_of(&state, swap.requester).unwrap_or(0) as u8 + 1;
                results.push(ResultMsg {
                    token: swap.requester,
                    id: request_id,
                    code: SLOT_DECLINED,
                    slot,
                });
            }
            let skip = if state.slots[0].peer.is_none()
                && state.peers.get(&swap.requester).is_some_and(|peer| peer.slot != 0)
            {
                Some(swap.requester)
            } else {
                None
            };
            let result = self.sync_host_locked(&mut state, skip);
            results.extend(result.1);
            (results, result.0)
        };
        for notice in results {
            self.send_result(notice);
        }
        if host_changed {
            log::line("party host is now the player on P1");
            self.broadcast_party_state();
        }
    }

    fn service_swaps(&self) {
        let time = now();
        let mut asks = Vec::new();
        let mut results = Vec::new();
        {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let swaps: Vec<Swap> = state.swaps.values().copied().collect();
            for swap in swaps {
                let requester = state.peers.contains_key(&swap.requester);
                let target = state.peers.contains_key(&swap.target);
                let first = if requester { Self::slot_of(&state, swap.requester) } else { None };
                let second = if target { Self::slot_of(&state, swap.target) } else { None };
                if !requester
                    || !target
                    || first != Some(usize::from(swap.from_slot.saturating_sub(1)))
                    || second != Some(usize::from(swap.to_slot.saturating_sub(1)))
                {
                    state.swaps.remove(&swap.id);
                    if let Some(first) = first {
                        results.push(ResultMsg {
                            token: swap.requester,
                            id: swap.id,
                            code: SLOT_UNAVAILABLE,
                            slot: first as u8 + 1,
                        });
                    }
                    if let Some(second) = second {
                        results.push(ResultMsg {
                            token: swap.target,
                            id: swap.id,
                            code: SLOT_CANCELLED,
                            slot: second as u8 + 1,
                        });
                    }
                    continue;
                }
                if time - swap.created_at >= SWAP_TIMEOUT {
                    state.swaps.remove(&swap.id);
                    state
                        .swap_cooldown
                        .insert((swap.requester, swap.target), time + SWAP_COOLDOWN);
                    results.push(ResultMsg {
                        token: swap.requester,
                        id: swap.id,
                        code: SLOT_TIMEOUT,
                        slot: swap.from_slot,
                    });
                    results.push(ResultMsg {
                        token: swap.target,
                        id: swap.id,
                        code: SLOT_CANCELLED,
                        slot: swap.to_slot,
                    });
                    continue;
                }
                if time - swap.last_ask >= SWAP_ASK_INTERVAL {
                    let mut updated = swap;
                    updated.last_ask = time;
                    state.swaps.insert(updated.id, updated);
                    asks.push(updated);
                }
            }
            state.swap_cooldown.retain(|_, until| *until > time);
        }
        for swap in asks {
            self.send_ask(&swap);
        }
        for notice in results {
            self.send_result(notice);
        }
    }

    fn set_lock(&self, token: u32, enabled: bool) {
        let mut cancelled = Vec::new();
        let mut changed = false;
        {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            if state.host_token != Some(token) {
                return;
            }
            if state.locked != enabled {
                state.locked = enabled;
                changed = true;
            }
            if enabled {
                let swaps: Vec<Swap> = state.swaps.values().copied().collect();
                for swap in swaps {
                    state.swaps.remove(&swap.id);
                    let requester_slot = Self::slot_of(&state, swap.requester).unwrap_or(0) as u8 + 1;
                    let target_slot = Self::slot_of(&state, swap.target).unwrap_or(0) as u8 + 1;
                    cancelled.push(ResultMsg {
                        token: swap.requester,
                        id: swap.id,
                        code: SLOT_UNAVAILABLE,
                        slot: requester_slot,
                    });
                    cancelled.push(ResultMsg {
                        token: swap.target,
                        id: swap.id,
                        code: SLOT_CANCELLED,
                        slot: target_slot,
                    });
                }
            }
        }
        for notice in cancelled {
            self.send_result(notice);
        }
        if changed {
            log::line(if enabled { "party lock on" } else { "party lock off" });
        }
        self.broadcast_party_state();
    }

    // ------------------------------------------------------------------ rumble
    fn send_rumble(&self, token: u32, small: u8, large: u8) {
        let (web, addr) = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let Some(peer) = state.peers.get(&token) else { return };
            (peer.web_send, peer.addr)
        };
        if web {
            self.web_event(
                token,
                &format!("{{\"t\":\"r\",\"s\":{small},\"l\":{large}}}"),
            );
            return;
        }
        let packet = rumble(token, small, large);
        let _ = self.sock.send_to(&packet, addr);
    }

    fn slot_rumble(&self, index: usize, small: u8, large: u8) {
        if !self.rumble_enabled.load(Ordering::Relaxed) {
            return;
        }
        let Some(lock) = self.rumble_locks.get(index) else { return };
        let _guard = lock.lock().unwrap_or_else(|err| err.into_inner());
        let token = {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let slot = &mut state.slots[index];
            if slot.rumble_state == (small, large) {
                return;
            }
            slot.rumble_state = (small, large);
            slot.peer
        };
        if let Some(token) = token {
            self.send_rumble(token, small, large);
        }
    }

    fn drain_rumble(&self) {
        loop {
            let event = {
                let receiver = self.rumble_rx.lock().unwrap_or_else(|err| err.into_inner());
                match receiver.try_recv() {
                    Ok(event) => event,
                    Err(_) => break,
                }
            };
            self.slot_rumble(event.slot, event.small, event.large);
        }
    }

    fn refresh_active_rumble(&self) {
        if !self.rumble_enabled.load(Ordering::Relaxed) {
            return;
        }
        // Hold each slot's send lock while re-reading and sending. A stop that
        // lands in the middle of this loop then wins, matching PadSlot's lock.
        for index in 0..self.rumble_locks.len() {
            let Some(lock) = self.rumble_locks.get(index) else { continue };
            let _guard = lock.lock().unwrap_or_else(|err| err.into_inner());
            let level = {
                let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
                let slot = &state.slots[index];
                let (small, large) = slot.rumble_state;
                if small == 0 && large == 0 {
                    None
                } else {
                    slot.peer.map(|token| (token, small, large))
                }
            };
            if let Some((token, small, large)) = level {
                self.send_rumble(token, small, large);
            }
        }
    }

    // -------------------------------------------------------------- housekeeping
    fn housekeeping(&self, time: f64) {
        let mut idle = Vec::new();
        let mut gone = Vec::new();
        {
            let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            if time - state.last_housekeeping < HOUSEKEEPING_INTERVAL {
                return;
            }
            state.last_housekeeping = time;
            for (&token, peer) in state.peers.iter_mut() {
                if peer.last_packet != 0.0 && !peer.released && time - peer.last_packet > self.release_after {
                    peer.released = true;
                    idle.push(token);
                }
                let span = time - peer.rate_mark_time;
                if span >= 1.0 {
                    peer.rate = (peer.packet_count - peer.rate_mark) as f64 / span;
                    peer.rate_mark = peer.packet_count;
                    peer.rate_mark_time = time;
                    peer.lost = 0;
                }
                if time - peer.last_seen > PEER_TIMEOUT {
                    gone.push(token);
                }
            }
        }
        for token in idle {
            {
                let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
                if let Some(index) = Self::slot_of(&state, token) {
                    if let Some(pad) = state.slots[index].pad.as_mut() {
                        pad.apply(&InputState::default());
                    }
                }
            }
            if let Some(peer) = self.state.lock().unwrap_or_else(|err| err.into_inner()).peers.get(&token) {
                log::line(format!(
                    "controller idle: {} - buttons released",
                    peer.describe()
                ));
            }
        }
        for token in gone {
            self.drop_peer(token);
        }
        self.service_swaps();
    }

    // ----------------------------------------------------------------- dispatch
    fn dispatch(&self, data: &[u8], addr: SocketAddr, time: f64) {
        let Some(header) = Header::parse(data) else { return };
        match header.mtype {
            MSG_DISCOVER => self.handle_discover(data, addr),
            MSG_PING => self.handle_ping(data, addr, time, header),
            MSG_INPUT if data.len() >= 28 => self.handle_input(data, addr, time),
            MSG_SLOT_REQUEST => {
                if data.len() < 13 {
                    return;
                }
                let allowed = self
                    .state
                    .lock()
                    .unwrap_or_else(|err| err.into_inner())
                    .peers
                    .get(&header.token)
                    .is_some_and(|peer| !peer.web_send && peer.addr == addr);
                if allowed {
                    self.begin_swap(header.token, data[12]);
                }
            }
            MSG_SLOT_VOTE => {
                if data.len() < 17 {
                    return;
                }
                let id = crate::proto::u32_at(data, 12).unwrap_or(0);
                let accept = data[16] != 0;
                let allowed = self
                    .state
                    .lock()
                    .unwrap_or_else(|err| err.into_inner())
                    .peers
                    .get(&header.token)
                    .is_some_and(|peer| !peer.web_send && peer.addr == addr);
                if allowed {
                    self.resolve_swap(header.token, id, accept);
                }
            }
            MSG_PARTY_LOCK => {
                if data.len() < 13 {
                    return;
                }
                let allowed = self
                    .state
                    .lock()
                    .unwrap_or_else(|err| err.into_inner())
                    .peers
                    .get(&header.token)
                    .is_some_and(|peer| !peer.web_send && peer.addr == addr);
                if allowed {
                    self.set_lock(header.token, data[12] & 1 != 0);
                }
            }
            _ => {}
        }
    }

    // --------------------------------------------------------------- browser web
    fn web_event(&self, token: u32, message: &str) {
        let connection = self
            .state
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .web_senders
            .get(&token)
            .cloned();
        if let Some(connection) = connection {
            connection.send_json(message);
        }
    }

    fn broadcast_party_state(&self) {
        let (locked, host_token, connections) = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            (
                state.locked,
                state.host_token,
                state.web_senders.iter().map(|(token, connection)| (*token, connection.clone())).collect::<Vec<_>>(),
            )
        };
        let on = if locked { "true" } else { "false" };
        for (token, connection) in connections {
            let is_host = if Some(token) == host_token { "true" } else { "false" };
            connection.send_json(&format!(
                "{{\"t\":\"ps\",\"on\":{on},\"host\":{is_host}}}"
            ));
        }
    }

    fn web_ping(&self, token: u32) -> u32 {
        let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        let Some(peer) = state.peers.get_mut(&token) else { return 0 };
        peer.last_seen = now();
        peer.slot as u32 + 1
    }

    fn drop_web_peer(&self, token: u32) {
        let is_web = self
            .state
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .peers
            .get(&token)
            .is_some_and(|peer| peer.web_send);
        if is_web {
            self.drop_peer(token);
        }
    }

    fn screen_info(&self) -> Option<(u16, u32, u32)> {
        let video = self.video.lock().unwrap_or_else(|err| err.into_inner());
        video.as_ref()?.info()
    }

    // ------------------------------------------------------------------ status
    fn status_text(&self) -> String {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        let mut peers: Vec<&Peer> = state.peers.values().collect();
        peers.sort_by_key(|peer| peer.slot);
        if peers.is_empty() {
            return "  waiting for controller...".into();
        }
        let mut parts = Vec::new();
        for peer in &peers {
            let battery = if peer.battery == 255 { "?".into() } else { format!("{}%", peer.battery) };
            let loss = if peer.lost != 0 { format!(" {} lost", peer.lost) } else { String::new() };
            parts.push(format!(
                "P{} {} {:4.0} pkt/s{} bat {}",
                peer.slot + 1,
                peer.addr.ip(),
                peer.rate,
                loss,
                battery
            ));
        }
        format!("  {}/{} {}", peers.len(), self.max_phones, parts.join(" | "))
    }

    /// Publish a small JSON snapshot for the desktop panel. This is local-only
    /// and never changes the UDP/WebSocket protocol.
    fn publish_status(&self, running: bool) {
        let mut problems = self.pad_errors();
        if let Some(video) = self.video.lock().unwrap_or_else(|err| err.into_inner()).as_ref() {
            if !video.problem().is_empty() {
                problems.push(format!("screen: {}", video.problem()));
            }
        }
        let peers = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            let mut items = Vec::new();
            for (&token, peer) in state.peers.iter() {
                items.push(format!(
                    "{{\"slot\":{},\"token\":{},\"name\":{},\"ip\":{},\"rate\":{},\"lost\":{},\"battery\":{},\"host\":{}}}",
                    peer.slot + 1,
                    token,
                    json_string(&peer.name),
                    json_string(&peer.addr.ip().to_string()),
                    peer.rate,
                    peer.lost,
                    peer.battery,
                    state.host_token == Some(token),
                ));
            }
            items.sort_by(|left, right| {
                let slot = |item: &str| -> u32 {
                    item.split("\"slot\":")
                        .nth(1)
                        .and_then(|rest| rest.split(',').next())
                        .and_then(|value| value.parse().ok())
                        .unwrap_or(0)
                };
                slot(left).cmp(&slot(right))
            });
            (
                items.join(","),
                state.host_token,
                state.locked,
            )
        };
        let (peers_json, host_token, locked) = peers;
        let host_slot = host_token.and_then(|token| {
            self.state
                .lock()
                .unwrap_or_else(|err| err.into_inner())
                .peers
                .get(&token)
                .map(|peer| peer.slot + 1)
        });
        let video_port = self
            .video
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .as_ref()
            .map(|video| video.port);
        let problems_json = problems
            .iter()
            .map(|problem| json_string(problem))
            .collect::<Vec<_>>()
            .join(",");
        let payload = format!(
            "{{\"running\":{},\"port\":{},\"max_phones\":{},\"pad\":{},\"hostname\":{},\"video_port\":{},\"web_port\":{},\"web_key\":{},\"locked\":{},\"host_slot\":{},\"problems\":[{}],\"peers\":[{}]}}",
            running,
            self.port,
            self.max_phones,
            json_string(&self.pad_kind),
            json_string(&self.hostname),
            video_port
                .map(|port| port.to_string())
                .unwrap_or_else(|| "null".into()),
            if self.web_enabled {
                self.web_port.to_string()
            } else {
                "null".into()
            },
            json_string(&self.web_key),
            locked,
            host_slot
                .map(|slot| slot.to_string())
                .unwrap_or_else(|| "null".into()),
            problems_json,
            peers_json,
        );
        let path = status_file_path();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let temporary = path.with_extension("json.tmp");
        if std::fs::write(&temporary, payload).is_err() {
            return;
        }
        if std::fs::rename(&temporary, &path).is_err() {
            let _ = std::fs::remove_file(&path);
            let _ = std::fs::rename(&temporary, &path);
        }
    }

    fn status_loop(self: Arc<Self>) {
        use std::io::IsTerminal;
        let tty = std::io::stdout().is_terminal();
        let mut last_plain = 0.0;
        while self.running.load(Ordering::Relaxed) {
            self.refresh_active_rumble();
            self.publish_status(true);
            let time = now();
            {
                let mut cache = self.ip_cache.lock().unwrap_or_else(|err| err.into_inner());
                if time - cache.1 >= 10.0 {
                    let current = pack_addresses(&local_ips());
                    cache.1 = time;
                    if current != cache.0 {
                        cache.0 = current.clone();
                        self.announce_network_change(&current);
                    }
                }
            }
            let line = self.status_text();
            if tty {
                let width = 110usize;
                let short = if line.len() > width {
                    format!("{}...", &line[..width - 3])
                } else {
                    line
                };
                print!("\r\x1b[K{short}");
                use std::io::Write;
                let _ = std::io::stdout().flush();
            } else if time - last_plain >= 15.0 {
                last_plain = time;
                log::plain(line.trim());
            }
            thread::sleep(Duration::from_secs_f64(STATUS_INTERVAL));
        }
    }

    fn announce_network_change(&self, addresses: &[[u8; 4]]) {
        let ips: Vec<String> = addresses
            .iter()
            .map(|bytes| std::net::Ipv4Addr::from(*bytes).to_string())
            .collect();
        println!();
        println!("  {}", "-".repeat(58));
        println!("  Network changed - phones must use the new address:");
        for ip in &ips {
            println!("  PC IP        : {ip}");
        }
        if self.web_enabled {
            for ip in &ips {
                println!("  Web pad      : {}", self.web_url(ip, false));
            }
        }
        println!("  {}", "-".repeat(58));
        if self.qr_enabled {
            self.print_qr_codes(&ips);
        }
        println!();
    }

    // ------------------------------------------------------------------ display
    fn web_url(&self, ip: &str, auto: bool) -> String {
        let base = format!("http://{ip}:{}/?k={}", self.web_port, uri_escape(&self.web_key));
        if auto { format!("{base}&auto=1") } else { base }
    }

    fn pad_errors(&self) -> Vec<String> {
        let mut errors = Vec::new();
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        for slot in &state.slots {
            let Some(pad) = slot.pad.as_ref() else { continue };
            let message = if !pad.ok() {
                if pad.error().is_empty() { "virtual gamepad unavailable".into() } else { pad.error().to_string() }
            } else if !pad.rumble_error().is_empty() {
                format!("rumble feedback unavailable: {}", pad.rumble_error())
            } else {
                String::new()
            };
            if !message.is_empty() && !errors.contains(&message) {
                errors.push(message);
            }
        }
        errors
    }

    fn print_banner(&self) {
        let ips: Vec<String> = self
            .ip_cache
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .0
            .iter()
            .map(|bytes| std::net::Ipv4Addr::from(*bytes).to_string())
            .collect();
        let mixed = self.slot_kinds.iter().any(|kind| kind != &self.pad_kind);
        let pad_name = if mixed {
            let first = self.slot_kinds.iter().position(|kind| kind == "ds4").unwrap_or(4) + 1;
            format!("Xbox 360 (P1-P{}) + DualShock 4 (P{}-P{})", first - 1, first, self.max_phones)
        } else if self.pad_kind == "ds4" {
            "DualShock 4".into()
        } else {
            "Xbox 360".into()
        };
        log::plain("");
        log::plain(format!("  lemoGamePad ultimate  -  virtual {pad_name} gamepad"));
        log::plain("  by Alam showkat");
        log::plain(&format!("  {}", "-".repeat(58)));
        for error in self.pad_errors() {
            log::plain(format!("  !! {error}"));
        }
        log::plain(format!("  Control port : {} (UDP)", self.port));
        if let Some(video) = self.video.lock().unwrap_or_else(|err| err.into_inner()).as_ref() {
            log::plain(format!("  Screen stream: {} (TCP)", video.port));
        } else {
            log::plain("  Screen stream: disabled");
        }
        if self.web_enabled {
            log::plain(format!("  Browser pad  : {} (TCP, Wi-Fi only)", self.web_port));
        } else {
            log::plain("  Browser pad  : disabled");
        }
        log::plain(format!("  Controllers  : up to {} phones at once", self.max_phones));
        if mixed {
            log::plain("  Pads 5-8     : DS4/DirectInput (XInput only allows four pads)");
        }
        let (ready, detail) = backend_status();
        let status = if ready { detail } else { format!("MISSING - {detail}") };
        log::plain(format!("  {}       : {status}", if cfg!(windows) { "ViGEmBus" } else { "uinput  " }));
        for ip in &ips {
            log::plain(format!("  PC IP        : {ip}"));
        }
        if self.web_enabled {
            for ip in &ips {
                log::plain(format!("  Web pad      : {}", self.web_url(ip, false)));
            }
        }
        log::plain("");
        log::plain("  Phone: open lemoGamePad ultimate, tap the status pill,");
        log::plain("         then tap 'Scan QR' and point the camera at the code below.");
        if self.web_enabled {
            log::plain("  Browser: open the Web pad URL on any device on the same Wi-Fi.");
        }
        log::plain(&format!("  {}", "-".repeat(58)));

        if self.qr_enabled {
            self.print_qr_codes(&ips);
        }
        if let Some(video) = self.video.lock().unwrap_or_else(|err| err.into_inner()).as_ref() {
            if !video.problem().is_empty() {
                log::plain(format!("  !! {}", video.problem()));
            }
        }
        log::plain("");
    }

    fn print_qr_codes(&self, ips: &[String]) {
        let Some(first) = ips.first() else { return };
        let safe = safe_ip(first);
        self.print_qr_block(
            &qr_payload(first, self.port, &self.hostname, false),
            &format!("lemoGamePad-qr-{safe}.png"),
            "Pairing link",
        );
        self.print_qr_block(
            &qr_payload(first, self.port, &self.hostname, true),
            &format!("lemoGamePad-guest-qr-{safe}.png"),
            "Guest join link",
        );
        for extra in ips.iter().skip(1) {
            println!(
                "  Other address: {}",
                qr_payload(extra, self.port, &self.hostname, false)
            );
            let name = format!("lemoGamePad-guest-qr-{}.png", safe_ip(extra));
            let _ = qr::save_png(
                &qr_payload(extra, self.port, &self.hostname, true),
                &std::path::PathBuf::from(&name),
            );
        }
        if !self.web_enabled {
            return;
        }
        self.print_qr_block(
            &self.web_url(first, false),
            &format!("lemoGamePad-web-qr-{safe}.png"),
            "Browser link",
        );
        self.print_qr_block(
            &self.web_url(first, true),
            &format!("lemoGamePad-web-guest-qr-{safe}.png"),
            "Browser guest link",
        );
        for extra in ips.iter().skip(1) {
            let name = format!("lemoGamePad-web-qr-{}.png", safe_ip(extra));
            let payload = self.web_url(extra, false);
            if qr::save_png(&payload, &std::path::PathBuf::from(&name)).is_ok() {
                println!("  Browser QR   : {name}");
            }
            let guest = format!("lemoGamePad-web-guest-qr-{}.png", safe_ip(extra));
            let _ = qr::save_png(&self.web_url(extra, true), &std::path::PathBuf::from(&guest));
        }
    }

    fn print_qr_block(&self, payload: &str, filename: &str, label: &str) {
        println!();
        match qr::terminal(payload) {
            Ok(text) => println!("{text}"),
            Err(err) => println!("  QR code unavailable ({err})"),
        }
        let path = std::env::current_dir()
            .unwrap_or_default()
            .join(filename);
        match qr::save_png(payload, &path) {
            Ok(()) => println!("  QR image saved: {}", path.display()),
            Err(err) => println!("  could not save QR image: {err}"),
        }
        println!("  {label}: {payload}");
    }
}

impl WebHost for Server {
    fn new_token(&self) -> u32 {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        loop {
            let token = random_u32();
            if token != 0 && !state.peers.contains_key(&token) {
                return token;
            }
        }
    }

    fn register_connection(&self, token: u32, connection: Arc<WsConnection>, mode: &str) {
        if mode == "pad" {
            self.state
                .lock()
                .unwrap_or_else(|err| err.into_inner())
                .web_senders
                .insert(token, connection);
        }
    }

    fn remove_connection(&self, token: u32) {
        self.state
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .web_senders
            .remove(&token);
        cleanup_relay(token);
        self.drop_web_peer(token);
    }

    fn submit_input(&self, token: u32, ip: String, input: InputState, want_slot: u8) -> u32 {
        let addr = SocketAddr::from((ip.parse().unwrap_or(std::net::Ipv4Addr::LOCALHOST), 0));
        self.submit_input(token, addr, now(), input, want_slot, "browser", true)
    }

    fn ping(&self, token: u32) -> u32 {
        self.web_ping(token)
    }

    fn swap(&self, token: u32, wanted: u32) {
        let missing = self.state.lock().unwrap_or_else(|err| err.into_inner()).peers.get(&token).is_none();
        if missing {
            // A browser can send `swap` before its first input registers the
            // token. Python still answers so the pending card clears.
            let id = {
                let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
                Self::next_swap_id(&mut state)
            };
            self.web_event(
                token,
                &format!("{{\"t\":\"sr\",\"id\":{id},\"c\":{SLOT_UNAVAILABLE},\"n\":0}}"),
            );
            return;
        }
        let wanted = wanted.min(self.max_phones as u32) as u8;
        self.begin_swap(token, wanted);
    }

    fn vote(&self, token: u32, request_id: u32, accept: bool) {
        self.resolve_swap(token, request_id, accept);
    }

    fn lock(&self, token: u32, enabled: bool) {
        self.set_lock(token, enabled);
    }

    fn party_state(&self) -> (bool, Option<u32>) {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        (state.locked, state.host_token)
    }

    fn server_name(&self) -> String {
        self.hostname.clone()
    }

    fn max_players(&self) -> usize {
        self.max_phones
    }

    fn screen_info(&self) -> Option<(u16, u32, u32)> {
        self.screen_info()
    }

    fn log(&self, message: String) {
        log::line(message);
    }
}
