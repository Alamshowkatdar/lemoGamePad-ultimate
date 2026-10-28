#!/usr/bin/env python3
"""
lemoGamePad ultimate (by Alam showkat) - turns your Android phone into a
PS3-style gamepad.

The phone sends compact UDP packets (see protocol below); this program feeds
them into a virtual gamepad created by ViGEmBus, so Windows games see a real
controller with analog sticks and pressure-sensitive triggers.

Protocol (little endian)
------------------------
header (12 bytes):  magic "VPAD" | ver u8 | type u8 | seq u16 | token u32

  type 1 INPUT   : header + buttons u32 + lx i16 + ly i16 + rx i16 + ry i16
                   + l2 u8 + r2 u8 + battery u8 + pad u8
  type 2 DISCOVER: header (broadcast to find servers)
  type 3 OFFER   : header + name_len u8 + name utf8 + pad_type u8
                   + ip_count u8 + ip_count * 4 bytes (IPv4, added in 1.3)
  type 5 PING    : header + client_clock u32 (ms)
  type 6 PONG    : header + same u32 echoed back
  type 7 RUMBLE  : header + small_motor u8 + large_motor u8

Button bits (PS3 naming) are defined in BUTTON_BITS below.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import os
import queue
import secrets
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Optional

MAGIC = b"VPAD"
VERSION = 1

MSG_INPUT = 1
MSG_DISCOVER = 2
MSG_OFFER = 3
MSG_PING = 5
MSG_PONG = 6
MSG_RUMBLE = 7

HEADER = struct.Struct("<4sBBHI")
INPUT = struct.Struct("<4sBBHIIhhhhBBBB")  # 28 bytes
INPUT_SIZE = INPUT.size

# PS3 button layout -> bit index
BUTTON_BITS = {
    "select": 0,
    "l3": 1,
    "r3": 2,
    "start": 3,
    "up": 4,
    "right": 5,
    "down": 6,
    "left": 7,
    "l2": 8,
    "r2": 9,
    "l1": 10,
    "r1": 11,
    "triangle": 12,
    "circle": 13,
    "cross": 14,
    "square": 15,
    "ps": 16,
}


@dataclass
class InputState:
    buttons: int = 0
    lx: int = 0
    ly: int = 0
    rx: int = 0
    ry: int = 0
    l2: int = 0
    r2: int = 0
    battery: int = 255
    seq: int = 0

    def pressed(self, name: str) -> bool:
        return bool(self.buttons >> BUTTON_BITS[name] & 1)

    def is_neutral(self) -> bool:
        return (
            self.buttons == 0
            and self.lx == 0
            and self.ly == 0
            and self.rx == 0
            and self.ry == 0
            and self.l2 == 0
            and self.r2 == 0
        )


@dataclass
class Peer:
    token: int = 0
    addr: tuple = ("", 0)
    name: str = "phone"
    slot: int = -1
    last_packet: float = 0.0
    last_seen: float = 0.0
    packet_count: int = 0
    rate: float = 0.0
    battery: int = 255
    seq: int = 0
    lost: int = 0
    released: bool = False
    rate_mark: int = 0
    rate_mark_time: float = 0.0

    def describe(self) -> str:
        return f"{self.name} @ {self.addr[0]}"


class VirtualPad:
    """Thin wrapper so the program still runs (for debugging) without ViGEm."""

    def __init__(self, kind: str, on_rumble=None):
        self.kind = kind
        self.ok = False
        self.pad = None
        self.on_rumble = on_rumble
        self.rumble_error = ""
        self._lock = threading.Lock()
        self._last = InputState()

        try:
            import vgamepad as vg
        except Exception as exc:  # pragma: no cover
            self.error = f"vgamepad not available ({exc})"
            return

        try:
            if kind == "ds4":
                self.pad = vg.VDS4Gamepad()
                self.vg = vg
                self._register_rumble(vg)
            else:
                self.pad = vg.VX360Gamepad()
                self.vg = vg
                self._register_rumble(vg)
            self.ok = True
            self.error = ""
        except Exception as exc:  # pragma: no cover
            self.error = (
                f"could not create virtual pad ({exc}). "
                "Is ViGEmBus installed and is the driver running?"
            )
            self.pad = None

    def _register_rumble(self, vg):
        # vgamepad's notification callback receives the motor speeds directly:
        #   X360: (client, target, large_motor, small_motor, led_number, user_data)
        #   DS4 : (client, target, large_motor, small_motor, lightbar_color, user_data)
        try:
            if self.kind == "ds4":
                self.pad.register_notification(self._ds4_notification)
            else:
                self.pad.register_notification(self._x360_notification)
        except Exception as exc:
            self.rumble_error = str(exc) or exc.__class__.__name__

    def _x360_notification(self, client, target, large_motor, small_motor, led, user_data):  # pragma: no cover
        try:
            if self.on_rumble:
                self.on_rumble(small_motor, large_motor)
        except Exception:
            pass

    def _ds4_notification(self, client, target, large_motor, small_motor, lightbar, user_data):  # pragma: no cover
        try:
            if self.on_rumble:
                self.on_rumble(small_motor, large_motor)
        except Exception:
            pass

    def close(self) -> None:
        """Unplug the pad so Windows drops the device node instead of keeping
        a ghost "Unknown" controller after the server exits."""
        with self._lock:
            pad, self.pad = self.pad, None
            self.ok = False
        if pad is None:
            return
        try:
            if self.kind == "ds4":
                pad.unregister_notification(self._ds4_notification)
            else:
                pad.unregister_notification(self._x360_notification)
        except Exception:
            pass
        try:
            pad.close()
        except Exception:
            pass

    def apply(self, s: InputState) -> None:
        if not self.ok:
            self._last = s
            return
        with self._lock:
            p = self.pad
            try:
                if self.kind == "ds4":
                    self._apply_ds4(p, s)
                else:
                    self._apply_x360(p, s)
                p.update()
            except Exception as exc:  # pragma: no cover
                print(f"\n[!] pad update failed: {exc}")
                self.ok = False
            self._last = s

    def _apply_x360(self, p, s: InputState) -> None:
        vg = self.vg
        b = vg.XUSB_BUTTON
        mapping = {
            "cross": b.XUSB_GAMEPAD_A,
            "circle": b.XUSB_GAMEPAD_B,
            "square": b.XUSB_GAMEPAD_X,
            "triangle": b.XUSB_GAMEPAD_Y,
            "l1": b.XUSB_GAMEPAD_LEFT_SHOULDER,
            "r1": b.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "l3": b.XUSB_GAMEPAD_LEFT_THUMB,
            "r3": b.XUSB_GAMEPAD_RIGHT_THUMB,
            "select": b.XUSB_GAMEPAD_BACK,
            "start": b.XUSB_GAMEPAD_START,
            "ps": b.XUSB_GAMEPAD_GUIDE,
            "up": b.XUSB_GAMEPAD_DPAD_UP,
            "down": b.XUSB_GAMEPAD_DPAD_DOWN,
            "left": b.XUSB_GAMEPAD_DPAD_LEFT,
            "right": b.XUSB_GAMEPAD_DPAD_RIGHT,
        }
        for name, btn in mapping.items():
            if s.pressed(name):
                p.press_button(btn)
            else:
                p.release_button(btn)

        # L2/R2 are analog on a PS3 pad; on the virtual X360 pad they are triggers.
        # The bit is kept in sync so a quick tap still registers.
        l2 = max(s.l2, 255 if s.pressed("l2") else 0)
        r2 = max(s.r2, 255 if s.pressed("r2") else 0)
        p.left_trigger_float(l2 / 255.0)
        p.right_trigger_float(r2 / 255.0)
        p.left_joystick_float(_axis(s.lx), _axis(s.ly))
        p.right_joystick_float(_axis(s.rx), _axis(s.ry))

    def _apply_ds4(self, p, s: InputState) -> None:
        vg = self.vg
        b = vg.DS4_BUTTONS
        mapping = {
            "cross": b.DS4_BUTTON_CROSS,
            "circle": b.DS4_BUTTON_CIRCLE,
            "square": b.DS4_BUTTON_SQUARE,
            "triangle": b.DS4_BUTTON_TRIANGLE,
            "l1": b.DS4_BUTTON_SHOULDER_LEFT,
            "r1": b.DS4_BUTTON_SHOULDER_RIGHT,
            "l2": b.DS4_BUTTON_TRIGGER_LEFT,
            "r2": b.DS4_BUTTON_TRIGGER_RIGHT,
            "l3": b.DS4_BUTTON_THUMB_LEFT,
            "r3": b.DS4_BUTTON_THUMB_RIGHT,
            "select": b.DS4_BUTTON_SHARE,
            "start": b.DS4_BUTTON_OPTIONS,
        }
        for name, btn in mapping.items():
            if s.pressed(name):
                p.press_button(btn)
            else:
                p.release_button(btn)

        # PS button lives in a separate "special buttons" namespace in vgamepad.
        special = vg.DS4_SPECIAL_BUTTONS
        if s.pressed("ps"):
            p.press_special_button(special.DS4_SPECIAL_BUTTON_PS)
        else:
            p.release_special_button(special.DS4_SPECIAL_BUTTON_PS)

        d = vg.DS4_DPAD_DIRECTIONS
        up, down = s.pressed("up"), s.pressed("down")
        left, right = s.pressed("left"), s.pressed("right")
        if up and right:
            direction = d.DS4_BUTTON_DPAD_NORTHEAST
        elif up and left:
            direction = d.DS4_BUTTON_DPAD_NORTHWEST
        elif down and right:
            direction = d.DS4_BUTTON_DPAD_SOUTHEAST
        elif down and left:
            direction = d.DS4_BUTTON_DPAD_SOUTHWEST
        elif up:
            direction = d.DS4_BUTTON_DPAD_NORTH
        elif down:
            direction = d.DS4_BUTTON_DPAD_SOUTH
        elif left:
            direction = d.DS4_BUTTON_DPAD_WEST
        elif right:
            direction = d.DS4_BUTTON_DPAD_EAST
        else:
            direction = d.DS4_BUTTON_DPAD_NONE
        p.directional_pad(direction)

        p.left_trigger_float(max(s.l2, 255 if s.pressed("l2") else 0) / 255.0)
        p.right_trigger_float(max(s.r2, 255 if s.pressed("r2") else 0) / 255.0)
        p.left_joystick_float(_axis(s.lx), _axis(s.ly))
        p.right_joystick_float(_axis(s.rx), _axis(s.ry))


def _axis(value: int) -> float:
    """Convert a signed 16 bit axis value to the -1..1 range ViGEm expects."""
    if value >= 0:
        return min(value / 32767.0, 1.0)
    return max(value / 32768.0, -1.0)


class PadSlot:
    """
    One virtual controller slot. Up to four phones can share a PC; each gets
    its own pad, created lazily on its first input packet and reused when that
    phone leaves so the controller index stays stable in games.
    """

    def __init__(self, index: int, kind: str, on_rumble):
        self.index = index
        self.kind = kind
        self.on_rumble = on_rumble
        self.peer: Optional["Peer"] = None
        self.pad: Optional[VirtualPad] = None
        # vgamepad re-reports the current motor speeds periodically, so keep
        # the last value to avoid a stream of duplicate packets to the phone.
        self.rumble_state = (0, 0)

    def ensure_pad(self) -> VirtualPad:
        if self.pad is None:
            self.pad = VirtualPad(self.kind, on_rumble=self._rumble)
        return self.pad

    def _rumble(self, small: int, large: int) -> None:
        if self.on_rumble:
            self.on_rumble(self, small, large)

    def release(self) -> None:
        if self.pad is not None:
            try:
                self.pad.apply(InputState())
            except Exception:
                pass

    def reset(self) -> None:
        self.release()
        self.peer = None
        self.rumble_state = (0, 0)


class VideoClient:
    """One PiP viewer. A one-frame queue keeps a slow phone from stalling others."""

    def __init__(self, server: "VideoServer", sock: socket.socket, addr: tuple):
        self.server = server
        self.sock = sock
        self.addr = addr
        self.queue: "queue.Queue[bytes]" = queue.Queue(maxsize=1)
        self.alive = True

    def start(self) -> None:
        threading.Thread(
            target=self._run,
            name=f"lemo-video-{self.addr[0]}",
            daemon=True,
        ).start()

    def offer(self, frame: bytes) -> None:
        if not self.alive:
            return
        try:
            while True:
                self.queue.get_nowait()
        except queue.Empty:
            pass
        try:
            self.queue.put_nowait(frame)
        except queue.Full:
            pass

    def close(self) -> None:
        self.alive = False
        try:
            self.sock.close()
        except OSError:
            pass

    def _run(self) -> None:
        try:
            while self.alive and self.server.running:
                try:
                    frame = self.queue.get(timeout=1.0)
                except queue.Empty:
                    continue
                try:
                    self.sock.sendall(frame)
                except OSError:
                    break
        finally:
            self.close()
            self.server.drop_client(self)


class VideoServer:
    """
    Streams the PC screen to every phone that asks for it as length-prefixed
    JPEG frames over TCP.

    Frame layout:  u32 big-endian length  +  JPEG bytes
    The default port is the control port + 1 (9877), so the phones never need a
    second address. Several phones can watch at once; each gets every frame and
    newer frames replace unread ones, so one slow phone cannot hold the others
    back. Nothing is captured while nobody is watching.
    """

    def __init__(
        self,
        port: int,
        fps: int = 12,
        width: int = 640,
        quality: int = 55,
        max_viewers: int = 4,
    ):
        self.port = port
        self.fps = max(1, min(fps, 30))
        self.width = max(160, width)
        self.quality = max(20, min(quality, 90))
        self.max_viewers = max(1, max_viewers)
        self.enabled = False
        self.error = ""
        self.running = False
        self._thread: Optional[threading.Thread] = None
        self._capture: Optional[threading.Thread] = None
        self._server: Optional[socket.socket] = None
        self._client_lock = threading.Lock()
        self._clients: list[VideoClient] = []

    @property
    def viewers(self) -> int:
        with self._client_lock:
            return len(self._clients)

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self.running = True
        self._thread = threading.Thread(target=self._serve, name="lemo-video", daemon=True)
        self._thread.start()
        self._capture = threading.Thread(target=self._capture_loop, name="lemo-video-capture", daemon=True)
        self._capture.start()

    def stop(self) -> None:
        self.running = False
        with self._client_lock:
            clients, self._clients = self._clients, []
        for client in clients:
            client.close()
        try:
            if self._server is not None:
                self._server.close()
        except OSError:
            pass
        self._server = None

    def drop_client(self, client: VideoClient) -> None:
        with self._client_lock:
            if client in self._clients:
                self._clients.remove(client)
                print(f"[video] viewer disconnected ({client.addr[0]})")

    # ------------------------------------------------------------------ socket
    def _serve(self) -> None:
        try:
            import mss  # noqa: F401
            from PIL import Image  # noqa: F401
        except Exception as exc:  # pragma: no cover
            self.error = f"screen capture needs mss + pillow ({exc})"
            return

        try:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            if os.name == "nt" and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                srv.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            else:
                srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", self.port))
            srv.listen(8)
            srv.settimeout(0.5)
            self._server = srv
        except OSError as exc:
            self.error = f"video port {self.port} unavailable ({exc})"
            return

        self.enabled = True
        while self.running:
            try:
                client_sock, addr = srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break

            with self._client_lock:
                full = len(self._clients) >= self.max_viewers
            if full:
                try:
                    client_sock.close()
                except OSError:
                    pass
                continue

            client = VideoClient(self, client_sock, addr)
            with self._client_lock:
                self._clients.append(client)
            print(f"[video] phone viewing the PC screen from {addr[0]}")
            client.start()

    def _capture_loop(self) -> None:
        import mss
        from PIL import Image

        grabber_factory = getattr(mss, "MSS", mss.mss)
        interval = 1.0 / self.fps

        with grabber_factory() as grabber:
            monitor = grabber.monitors[1]

            while self.running:
                with self._client_lock:
                    clients = list(self._clients)
                if not clients:
                    # Nobody is watching: do not burn CPU capturing.
                    time.sleep(0.2)
                    continue

                started = time.monotonic()
                try:
                    shot = grabber.grab(monitor)
                    img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")

                    if img.width > self.width:
                        height = max(1, round(img.height * self.width / img.width))
                        img = img.resize((self.width, height), Image.BILINEAR)

                    buffer = io.BytesIO()
                    img.save(buffer, format="JPEG", quality=self.quality, optimize=False)
                    payload = buffer.getvalue()
                    frame = struct.pack(">I", len(payload)) + payload
                except Exception as exc:  # pragma: no cover
                    self.error = f"screen capture failed ({exc})"
                    time.sleep(0.5)
                    continue

                for client in clients:
                    client.offer(frame)

                elapsed = time.monotonic() - started
                if elapsed < interval:
                    time.sleep(interval - elapsed)


def qr_payload(ip: str, port: int, name: str) -> str:
    query = urllib.parse.urlencode({"h": ip, "p": port, "n": name})
    return f"vpad://connect?{query}"


def print_qr(ip: str, port: int, name: str, out_dir: str) -> None:
    """Draws a scannable QR in the console and saves a PNG for each address."""
    try:
        import qrcode
    except Exception as exc:  # pragma: no cover
        print(f"  QR code unavailable ({exc}). Install with: pip install qrcode pillow")
        return

    payload = qr_payload(ip, port, name)
    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=8,
        border=2,
    )
    qr.add_data(payload)
    qr.make(fit=True)

    print()
    try:
        qr.print_ascii(invert=True)
    except Exception:
        pass

    try:
        img = qr.make_image(fill_color="black", back_color="white")
        safe = ip.replace(":", "-").replace(".", "_")
        png = os.path.join(out_dir, f"lemoGamePad-qr-{safe}.png")
        img.save(png)
        print(f"  QR image saved: {png}")
    except Exception as exc:  # pragma: no cover
        print(f"  could not save QR image: {exc}")

    print(f"  Pairing link: {payload}")


class Server:
    """
    UDP control server for up to four phones at once.

    Phones are keyed by their 32 bit token, which they keep for the whole
    session, so a phone that drops off and comes back reuses the same peer and
    the same virtual controller instead of creating a duplicate. Each peer owns
    one [PadSlot]; pads are created lazily on the first input packet and are
    reused after that so the controller order in games stays stable.
    """

    PEER_TIMEOUT = 8.0
    STATUS_INTERVAL = 0.5
    HOUSEKEEPING_INTERVAL = 0.2

    def __init__(
        self,
        port: int,
        pad_kind: str,
        release_after: float = 1.5,
        video_port: Optional[int] = None,
        video_fps: int = 12,
        video_width: int = 640,
        video_quality: int = 55,
        video_enabled: bool = True,
        qr_enabled: bool = True,
        max_phones: int = 4,
    ):
        self.port = port
        self.pad_kind = pad_kind
        self.release_after = release_after
        self.max_phones = max(1, min(int(max_phones), 4))
        self.lock = threading.RLock()
        self.peers: dict[int, Peer] = {}
        self.slots = [
            PadSlot(index, pad_kind, self._slot_rumble)
            for index in range(self.max_phones)
        ]
        # Create the first pad up front so a missing driver is reported in the
        # banner instead of the first time a phone presses a button.
        self.slots[0].ensure_pad()

        self.qr_enabled = qr_enabled
        self.video = None
        if video_enabled:
            # The phone assumes "control port + 1" unless the user overrides it.
            self.video = VideoServer(
                video_port if video_port else port + 1,
                fps=video_fps,
                width=video_width,
                quality=video_quality,
                max_viewers=self.max_phones,
            )
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if os.name == "nt" and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            # Refuse a second instance instead of silently sharing the port.
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        else:
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        except OSError:
            pass
        self.sock.bind(("0.0.0.0", port))
        self.sock.settimeout(0.25)
        self.running = True
        self.hostname = socket.gethostname()[:32]
        self.started = time.time()
        self.rumble_enabled = True
        self._tty = sys.stdout.isatty()
        self._status_live = False
        self._last_plain_status = 0.0
        self._last_housekeeping = 0.0
        self._full_logged_at = 0.0
        self._ip_cache = []
        self._ip_cache_at = 0.0

    # ---------------------------------------------------------------- discovery
    def _handle_discover(self, data: bytes, addr: tuple) -> None:
        if len(data) < HEADER.size:
            return
        _, _, _, seq, token = HEADER.unpack_from(data)
        now = time.time()

        # Echo the phone's own token when it already has one: a phone that
        # re-pairs (or switches Wi-Fi -> USB) must keep its slot instead of
        # being handed a second controller.
        if token == 0:
            token = secrets.randbits(32)
        else:
            with self.lock:
                peer = self.peers.get(token)
                if peer is not None:
                    peer.addr = addr
                    peer.last_seen = now

        name = self.hostname.encode("utf-8")[:32]
        payload = HEADER.pack(MAGIC, VERSION, MSG_OFFER, seq, token)
        payload += bytes([len(name)]) + name + bytes([1 if self.pad_kind == "ds4" else 0])
        # Trailing address list: a PC with VPN / virtual adapters answers from
        # one address but the phone must pick the one on its own subnet.
        # Old app versions simply stop parsing before this block.
        addrs = self._offer_addresses()
        payload += bytes([len(addrs)]) + b"".join(addrs)
        try:
            self.sock.sendto(payload, addr)
        except OSError:
            pass

    def _offer_addresses(self):
        """Cached packed IPv4 addresses of this machine (max 8)."""
        now = time.time()
        if now - self._ip_cache_at < 10.0:
            return self._ip_cache
        out = []
        for text in local_ips():
            try:
                packed = socket.inet_aton(text)
            except OSError:
                continue
            if len(packed) == 4 and packed not in out:
                out.append(packed)
        self._ip_cache = out[:8]
        self._ip_cache_at = now
        return self._ip_cache

    # ------------------------------------------------------------------- peers
    def _slot_of(self, peer: Peer) -> Optional[PadSlot]:
        with self.lock:
            if 0 <= peer.slot < len(self.slots):
                slot = self.slots[peer.slot]
                if slot.peer is peer:
                    return slot
            for slot in self.slots:
                if slot.peer is peer:
                    return slot
        return None

    def _register_peer(self, token: int, addr: tuple, now: float):
        """
        Finds or creates the peer for a token. Returns (peer, is_new); peer is
        None when the PC already serves [max_phones] controllers.
        """
        with self.lock:
            peer = self.peers.get(token)
            if peer is not None:
                if peer.addr != addr:
                    # Same phone, new source port or a different interface.
                    peer.addr = addr
                peer.last_seen = now
                return peer, False

            if len(self.peers) >= self.max_phones:
                return None, False

            slot = next((s for s in self.slots if s.peer is None), None)
            if slot is None:
                return None, False

            peer = Peer(
                token=token,
                addr=addr,
                name=f"phone {slot.index + 1}",
                slot=slot.index,
                last_seen=now,
                rate_mark=0,
                rate_mark_time=now,
            )
            slot.peer = peer
            slot.ensure_pad()
            self.peers[token] = peer
            return peer, True

    def _drop_peer(self, peer: Peer) -> None:
        with self.lock:
            self.peers.pop(peer.token, None)
            slot = self._slot_of(peer)
            if slot is not None:
                slot.reset()
            peer.released = True
        self._log(f"controller disconnected: {peer.describe()}")

    # ------------------------------------------------------------------- rumble
    def _slot_rumble(self, slot: PadSlot, small: int, large: int) -> None:
        if not self.rumble_enabled:
            return
        small = min(max(int(small), 0), 255)
        large = min(max(int(large), 0), 255)
        with self.lock:
            if (small, large) == slot.rumble_state:
                return
            slot.rumble_state = (small, large)
            peer = slot.peer
            if peer is None:
                return
            addr = peer.addr
            token = peer.token
        packet = HEADER.pack(MAGIC, VERSION, MSG_RUMBLE, 0, token)
        packet += bytes([small, large])
        try:
            self.sock.sendto(packet, addr)
        except OSError:
            pass

    # ------------------------------------------------------------------- input
    def _handle_input(self, data: bytes, addr: tuple, now: float) -> None:
        (_, _, _, seq, token, buttons, lx, ly, rx, ry, l2, r2, batt, _pad) = (
            INPUT.unpack_from(data)
        )
        if _pad not in (0, 1):
            _pad = 0

        peer, fresh = self._register_peer(token, addr, now)
        if peer is None:
            if now - self._full_logged_at > 10.0:
                self._full_logged_at = now
                self._log(
                    f"ignored {addr[0]}: all {self.max_phones} controller slots are in use"
                )
            return

        with self.lock:
            peer.last_packet = now
            peer.last_seen = now
            peer.packet_count += 1
            peer.battery = batt
            prev = peer.seq
            peer.seq = seq
            if peer.released:
                peer.released = False
            # Only count small forward gaps; a wrap-around or a restarted phone
            # would otherwise report a huge loss.
            if prev and 0 < seq - prev < 300:
                peer.lost += seq - prev - 1

        if fresh:
            self._log(
                f"controller connected: {peer.describe()} "
                f"(pad {peer.slot + 1}/{self.max_phones})"
            )

        slot = self._slot_of(peer)
        if slot is None or slot.pad is None:
            return
        state = InputState(
            buttons=buttons, lx=lx, ly=ly, rx=rx, ry=ry,
            l2=l2, r2=r2, battery=batt, seq=seq,
        )
        slot.pad.apply(state)

    # -------------------------------------------------------------- housekeeping
    def _housekeeping(self, now: float) -> None:
        if now - self._last_housekeeping < self.HOUSEKEEPING_INTERVAL:
            return
        self._last_housekeeping = now

        idle: list[Peer] = []
        gone: list[Peer] = []
        with self.lock:
            for peer in list(self.peers.values()):
                if (
                    peer.last_packet
                    and not peer.released
                    and now - peer.last_packet > self.release_after
                ):
                    peer.released = True
                    idle.append(peer)

                span = now - peer.rate_mark_time
                if span >= 1.0:
                    peer.rate = (peer.packet_count - peer.rate_mark) / span
                    peer.rate_mark = peer.packet_count
                    peer.rate_mark_time = now
                    peer.lost = 0

                if now - peer.last_seen > self.PEER_TIMEOUT:
                    gone.append(peer)

        for peer in idle:
            slot = self._slot_of(peer)
            if slot is not None:
                slot.release()
            self._log(f"controller idle: {peer.describe()} - buttons released")

        for peer in gone:
            self._drop_peer(peer)

    # --------------------------------------------------------------------- main
    def run(self) -> None:
        if self.video is not None:
            self.video.start()
        self._print_banner()
        last_status = 0.0

        while self.running:
            try:
                data, addr = self.sock.recvfrom(2048)
            except socket.timeout:
                data = None
                addr = None
            except OSError:
                break

            now = time.time()

            if data:
                if len(data) < HEADER.size:
                    continue
                magic, ver, mtype, seq, token = HEADER.unpack_from(data)

                if magic != MAGIC:
                    continue

                if mtype == MSG_DISCOVER:
                    self._handle_discover(data, addr)
                    continue

                if mtype == MSG_PING:
                    with self.lock:
                        peer = self.peers.get(token)
                        if peer is not None:
                            peer.last_seen = now
                            peer.addr = addr
                    pong = HEADER.pack(MAGIC, VERSION, MSG_PONG, seq, token)
                    if len(data) >= HEADER.size + 4:
                        pong += data[HEADER.size : HEADER.size + 4]
                    try:
                        self.sock.sendto(pong, addr)
                    except OSError:
                        pass
                    continue

                if mtype == MSG_INPUT and len(data) >= INPUT_SIZE:
                    self._handle_input(data, addr, now)
                    continue

            self._housekeeping(now)

            if now - last_status >= self.STATUS_INTERVAL:
                last_status = now
                self._status()

        self.shutdown()

    def release_pads(self) -> None:
        """
        Unplug every virtual pad so Windows drops the device nodes instead of
        keeping greyed-out "Unknown" controllers after the server stops.

        Safe to call twice, and safe to call from the console-close handler.
        """
        for slot in self.slots:
            pad = slot.pad
            if pad is None:
                continue
            slot.pad = None
            try:
                pad.apply(InputState())
            except Exception:
                pass
            try:
                pad.close()
            except Exception:
                pass

    def shutdown(self) -> None:
        with self.lock:
            peers = list(self.peers.values())
            self.peers.clear()
        for peer in peers:
            slot = self._slot_of(peer)
            if slot is not None:
                slot.reset()
        self.release_pads()
        if self.video is not None:
            try:
                self.video.stop()
            except Exception:
                pass
        try:
            self.sock.close()
        except Exception:
            pass

    # ------------------------------------------------------------------ display
    def _banner_lines(self):
        ips = local_ips()
        pad_name = "DualShock 4" if self.pad_kind == "ds4" else "Xbox 360"
        lines = [
            "",
            f"  lemoGamePad ultimate  -  virtual {pad_name} gamepad",
            "  by Alam showkat",
            "  " + "-" * 58,
        ]
        errors = self._pad_errors()
        for error in errors:
            lines.append(f"  !! {error}")
        lines.append(f"  Control port : {self.port} (UDP)")
        if self.video is not None:
            lines.append(f"  Screen stream: {self.video.port} (TCP)")
        else:
            lines.append("  Screen stream: disabled")
        lines.append(f"  Controllers  : up to {self.max_phones} phones at once")
        if os.name == "nt":
            driver = "OK (virtual gamepad driver installed)"
            if not _vigem_installed():
                driver = "MISSING - install it once (see messages above)"
            lines.append(f"  ViGEmBus     : {driver}")
        for ip in ips:
            lines.append(f"  PC IP        : {ip}")
        checks = [("UDP", self.port)]
        if self.video is not None:
            checks.append(("TCP", self.video.port))
        lines.extend(_firewall_lines(checks))
        lines.append("")
        lines.append("  Phone: open lemoGamePad ultimate, tap the status pill,")
        lines.append("         then tap 'Scan QR' and point the camera at the code below.")
        lines.append("  " + "-" * 58)
        return lines

    def _pad_errors(self):
        errors = []
        for slot in self.slots:
            pad = slot.pad
            if pad is None:
                continue
            message = ""
            if not pad.ok:
                message = pad.error or "virtual gamepad unavailable"
            elif pad.rumble_error:
                message = f"rumble feedback unavailable: {pad.rumble_error}"
            if message and message not in errors:
                errors.append(message)
        return errors

    def _print_banner(self) -> None:
        for line in self._banner_lines():
            print(line)

        if self.qr_enabled:
            ips = local_ips()
            if ips:
                print_qr(ips[0], self.port, self.hostname, os.getcwd())
                for extra in ips[1:]:
                    print(f"  Other address: {qr_payload(extra, self.port, self.hostname)}")

        if self.video is not None and self.video.error:
            print(f"  !! {self.video.error}")
        print()

    def _log(self, message: str) -> None:
        if self._tty and self._status_live:
            sys.stdout.write("\r\x1b[K")
            self._status_live = False
        print(f"[{time.strftime('%H:%M:%S')}] {message}")

    def _status_text(self) -> str:
        with self.lock:
            peers = sorted(self.peers.values(), key=lambda p: p.slot)
        if not peers:
            return "  waiting for controller..."

        parts = []
        for peer in peers:
            battery = f"{peer.battery}%" if peer.battery != 255 else "?"
            parts.append(
                f"P{peer.slot + 1} {peer.addr[0]} "
                f"{peer.rate:4.0f} pkt/s bat {battery}"
            )
        head = f"  {len(peers)}/{self.max_phones} "
        return head + " | ".join(parts)

    def _status(self) -> None:
        line = self._status_text()

        if not self._tty:
            # Log files and pipes: emit a line at most every 15 seconds.
            now = time.time()
            if now - self._last_plain_status < 15.0:
                return
            self._last_plain_status = now
            print(line.strip())
            return

        width = 110
        try:
            width = max(40, os.get_terminal_size().columns - 1)
        except OSError:
            pass
        if len(line) > width:
            line = line[: width - 3] + "..."

        sys.stdout.write("\r\x1b[K" + line)
        sys.stdout.flush()
        self._status_live = True


def _firewall_rule(port: int, proto: str) -> dict:
    """Ask Windows about the inbound rule allow_firewall.ps1 creates.

    Returns {} when the state cannot be determined (not Windows, netsh
    missing, no permission), so a real machine is never told a lie.
    """
    if os.name != "nt":
        return {}
    name = f"lemoGamePad ultimate ({proto} {port})"
    try:
        res = subprocess.run(
            ["netsh", "advfirewall", "firewall", "show", "rule", f"name={name}", "verbose"],
            capture_output=True,
            text=True,
            timeout=5,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception:
        return {}
    text = f"{res.stdout or ''}\n{res.stderr or ''}"
    if res.returncode != 0 or "no rules match" in text.lower():
        return {"name": name, "port": port, "proto": proto, "present": False}
    fields = {}
    for line in text.splitlines():
        key, sep, value = line.partition(":")
        if sep:
            fields[key.strip().lower()] = value.strip()
    return {
        "name": name,
        "port": port,
        "proto": proto,
        "present": True,
        "enabled": fields.get("enabled", "yes").lower().startswith("yes"),
        "allow": fields.get("action", "allow").lower().startswith("allow"),
        "program": fields.get("program", ""),
    }


def _firewall_lines(checks) -> list:
    """Banner lines describing whether phones can actually reach this PC."""
    unknown = True
    problems = []
    for proto, port in checks:
        rule = _firewall_rule(port, proto)
        if not rule:
            continue
        unknown = False
        if not rule["present"]:
            problems.append((rule["name"], proto, port, "no inbound rule"))
        elif not rule["enabled"]:
            problems.append((rule["name"], proto, port, "rule is disabled"))
        elif not rule["allow"]:
            problems.append((rule["name"], proto, port, "rule does not allow traffic"))
        elif rule["program"] and getattr(sys, "frozen", False):
            want = os.path.normcase(os.path.abspath(sys.executable))
            have = os.path.normcase(os.path.abspath(rule["program"]))
            if want != have:
                problems.append((rule["name"], proto, port, f"rule points at {rule['program']}"))

    if unknown:
        return []
    if not problems:
        listing = " / ".join(f"{proto} {port}" for proto, port in checks)
        return [f"  Firewall     : OK ({listing} allowed)"]

    lines = ["  !! Windows Firewall is NOT open for this app."]
    lines.append("     Phones will scan forever with \"no reply\". Run once, as Administrator:")
    for name, proto, port, why in problems:
        # A rule that exists but points at another copy of the EXE has to go
        # first: "add" would only create a second rule with the same name.
        if why.startswith("rule points at"):
            lines.append(f"       netsh advfirewall firewall delete rule name=\"{name}\"")
        lines.append(f"       netsh advfirewall firewall add rule name=\"{name}\" dir=in " +
                     f"action=allow protocol={proto} localport={port} profile=any")
    lines.append(f"     ({problems[0][3]} - the line above recreates the rule)")
    return lines


# --------------------------------------------------------------- dependencies
# A fresh PC needs three things before a phone can drive a game: the Python
# packages (source runs only - the EXE bundles them), the signed ViGEmBus
# kernel driver, and inbound firewall rules.  All three are handled below so
# "download the EXE and double-click it" is genuinely the whole install.
#
# ViGEmBus is pinned to the official Nefarius build and checked against its
# SHA-256 instead of asking GitHub for "latest": a driver that Windows is about
# to trust should never change silently.

VIGEM_VERSION = "1.22.0"
VIGEM_SETUP_URL = (
    "https://github.com/nefarius/ViGEmBus/releases/download/"
    f"v{VIGEM_VERSION}/ViGEmBus_{VIGEM_VERSION}_x64_x86_arm64.exe"
)
VIGEM_SETUP_SHA256 = "89220a7865076b342892f98865f3499fb7c4cfd673159e89d352c360fd014c6a"
VIGEM_SETUP_SIZE = 6_278_576
VIGEM_PAGE = f"https://github.com/nefarius/ViGEmBus/releases/tag/v{VIGEM_VERSION}"


def _vigem_installed() -> bool:
    """True when the signed ViGEmBus kernel driver is registered."""
    if os.name != "nt":
        return False
    try:
        import winreg

        with winreg.OpenKey(
            winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Services\ViGEmBus"
        ):
            return True
    except OSError:
        return False


def _run_elevated(program: str, params: str, workdir: str = "") -> int:
    """Run [program] behind a UAC prompt and wait for it to finish.

    Returns the exit code, -1 if it could not start, or -2 if the user
    declined the UAC prompt.
    """
    if os.name != "nt":
        return -1
    try:
        import ctypes
        from ctypes import wintypes

        SEE_MASK_NOCLOSEPROCESS = 0x00000040
        ERROR_CANCELLED = 1223

        class SHELLEXECUTEINFOW(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.DWORD),
                ("fMask", ctypes.c_ulong),
                ("hwnd", wintypes.HWND),
                ("lpVerb", wintypes.LPCWSTR),
                ("lpFile", wintypes.LPCWSTR),
                ("lpParameters", wintypes.LPCWSTR),
                ("lpDirectory", wintypes.LPCWSTR),
                ("nShow", ctypes.c_int),
                ("hInstApp", wintypes.HINSTANCE),
                ("lpIDList", ctypes.c_void_p),
                ("lpClass", wintypes.LPCWSTR),
                ("hkeyClass", wintypes.HKEY),
                ("dwHotKey", wintypes.DWORD),
                ("hIcon", wintypes.HANDLE),
                ("hProcess", wintypes.HANDLE),
            ]

        info = SHELLEXECUTEINFOW()
        info.cbSize = ctypes.sizeof(info)
        info.fMask = SEE_MASK_NOCLOSEPROCESS
        info.lpVerb = "runas"
        info.lpFile = program
        info.lpParameters = params
        info.lpDirectory = workdir or None
        info.nShow = 0  # SW_HIDE; the installer itself runs with /qn
        shell32 = ctypes.windll.shell32
        shell32.ShellExecuteExW.argtypes = [ctypes.POINTER(SHELLEXECUTEINFOW)]
        shell32.ShellExecuteExW.restype = wintypes.BOOL
        if not shell32.ShellExecuteExW(ctypes.byref(info)):
            return -2 if ctypes.GetLastError() == ERROR_CANCELLED else -1

        kernel32 = ctypes.windll.kernel32
        kernel32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel32.WaitForSingleObject.restype = wintypes.DWORD
        kernel32.GetExitCodeProcess.argtypes = [
            wintypes.HANDLE,
            ctypes.POINTER(wintypes.DWORD),
        ]
        kernel32.GetExitCodeProcess.restype = wintypes.BOOL
        kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel32.CloseHandle.restype = wintypes.BOOL
        try:
            kernel32.WaitForSingleObject(info.hProcess, 0xFFFFFFFF)
            code = wintypes.DWORD()
            kernel32.GetExitCodeProcess(info.hProcess, ctypes.byref(code))
            return int(code.value)
        finally:
            kernel32.CloseHandle(info.hProcess)
    except Exception:
        return -1


def _setup_folder() -> str:
    base = os.path.join(
        os.environ.get("LOCALAPPDATA") or tempfile.gettempdir(), "lemoGamePad"
    )
    os.makedirs(base, exist_ok=True)
    return base


def _file_sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _download_vigem_setup() -> str:
    """Download the pinned ViGEmBus setup, verifying its size and SHA-256."""
    dest = os.path.join(_setup_folder(), f"ViGEmBus_{VIGEM_VERSION}.exe")
    if os.path.isfile(dest) and os.path.getsize(dest) == VIGEM_SETUP_SIZE:
        if _file_sha256(dest) == VIGEM_SETUP_SHA256:
            return dest
        try:
            os.remove(dest)
        except OSError:
            pass

    part = dest + ".part"
    print(f"  ViGEmBus     : downloading {VIGEM_VERSION} from GitHub (6 MB) ...")
    request = urllib.request.Request(
        VIGEM_SETUP_URL, headers={"User-Agent": "lemoGamePad-ultimate"}
    )
    digest = hashlib.sha256()
    size = 0
    try:
        with urllib.request.urlopen(request, timeout=60) as response, open(part, "wb") as out:
            while True:
                chunk = response.read(128 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > VIGEM_SETUP_SIZE + 1024 * 1024:
                    raise OSError("download is larger than expected")
                digest.update(chunk)
                out.write(chunk)
        if size != VIGEM_SETUP_SIZE or digest.hexdigest() != VIGEM_SETUP_SHA256:
            raise OSError(
                f"checksum mismatch (got {size} bytes, sha256 {digest.hexdigest()})"
            )
        os.replace(part, dest)
    except Exception:
        try:
            os.remove(part)
        except OSError:
            pass
        raise
    return dest


def _ensure_vigem(install: bool) -> None:
    """Install the virtual gamepad driver when it is missing."""
    if os.name != "nt" or _vigem_installed():
        return
    print("  ViGEmBus     : MISSING - this signed driver creates the virtual pad.")
    if not install:
        print(f"     Install it once from {VIGEM_PAGE}")
        print("     (or run without --no-install-deps to let this server do it)")
        return
    try:
        setup = _download_vigem_setup()
    except Exception as exc:
        print(f"  !! ViGEmBus download failed: {exc}")
        print(f"     Install it manually from {VIGEM_PAGE}")
        return

    log = os.path.join(_setup_folder(), "ViGEmBus-install.log")
    code = -1
    for attempt in (1, 2):
        if attempt == 2:
            print("  ViGEmBus     : driver did not register, trying once more ...")
        print("  ViGEmBus     : installing - Windows will ask for administrator permission.")
        code = _run_elevated(setup, f'/qn /norestart /l*v "{log}"', _setup_folder())
        if code == -2:
            print("  !! ViGEmBus install was cancelled at the Windows prompt.")
            print(f"     Install it manually from {VIGEM_PAGE}")
            return
        if _vigem_installed():
            print("  ViGEmBus     : installed")
            return
        # 1.22.0 is documented to need two passes on some systems (e.g. when it
        # replaces an older driver), so try once more before giving up.
    print(f"  !! ViGEmBus could not be installed (setup exit code {code}).")
    print(f"     Install it manually from {VIGEM_PAGE}")
    print(f"     Setup log: {log}")


def _firewall_fix_commands(checks) -> list:
    """netsh commands that would make every inbound rule match [checks]."""
    commands = []
    for proto, port in checks:
        rule = _firewall_rule(port, proto)
        if not rule:
            continue
        good = rule["present"] and rule.get("enabled") and rule.get("allow")
        if good and rule.get("program") and getattr(sys, "frozen", False):
            want = os.path.normcase(os.path.abspath(sys.executable))
            have = os.path.normcase(os.path.abspath(rule["program"]))
            good = want == have
        if good:
            continue
        name = rule["name"]
        if rule["present"]:
            # "add" would only create a second rule with the same name.
            commands.append(f'netsh advfirewall firewall delete rule name="{name}"')
        commands.append(
            f'netsh advfirewall firewall add rule name="{name}" dir=in '
            f"action=allow protocol={proto} localport={port} profile=any"
        )
    return commands


def _ensure_firewall(checks, install: bool) -> None:
    """Open the inbound ports so phones can actually reach this PC."""
    if os.name != "nt":
        return
    commands = _firewall_fix_commands(checks)
    if not commands:
        return
    if not install:
        print("  Firewall     : NOT open for this port (auto-install disabled).")
        return

    print("  Firewall     : adding inbound rules - Windows will ask for administrator permission.")
    folder = _setup_folder()
    script = os.path.join(folder, "fix-firewall.cmd")
    log = os.path.join(folder, "fix-firewall.log")
    with open(script, "w", encoding="ascii", newline="\r\n") as fh:
        fh.write("@echo off\r\n")
        for command in commands:
            fh.write(command + "\r\n")
    code = _run_elevated("cmd.exe", f'/c ""{script}" > "{log}" 2>&1"', folder)
    if code == -2:
        print("  !! Firewall rules were cancelled at the Windows prompt.")
        print("     Run pc\\allow_firewall.ps1 as Administrator, or start the server as Administrator.")
        return
    if _firewall_fix_commands(checks):
        print("  !! Could not add the firewall rules automatically.")
        print("     Run this once in an Administrator PowerShell:  pc\\allow_firewall.ps1")
        if os.path.isfile(log):
            print(f"     Details: {log}")
        return
    listing = " / ".join(f"{proto} {port}" for proto, port in checks)
    print(f"  Firewall     : OK ({listing} allowed)")


def _python_packages_missing() -> list:
    """Packages needed when running from source (the EXE bundles them)."""
    import importlib.util

    wanted = [
        ("vgamepad", "vgamepad>=0.2.0"),
        ("mss", "mss>=10.0.0"),
        ("PIL", "pillow>=10.0.0"),
        ("qrcode", "qrcode>=7.4"),
    ]
    missing = []
    for module, package in wanted:
        try:
            found = importlib.util.find_spec(module) is not None
        except Exception:
            found = False
        if not found:
            missing.append(package)
    return missing


def _ensure_python_packages(install: bool) -> None:
    """Source runs install their own requirements; frozen runs skip this."""
    if getattr(sys, "frozen", False) or os.name != "nt":
        return
    missing = _python_packages_missing()
    if not missing:
        return
    names = ", ".join(package.split(">")[0] for package in missing)
    print(f"  Python deps  : missing {names}")
    if not install:
        print("     Install with: python -m pip install -r pc\\requirements.txt")
        return
    print("  Python deps  : installing with pip ...")
    try:
        result = subprocess.run(
            [sys.executable, "-m", "pip", "install", "--disable-pip-version-check", *missing],
            capture_output=True,
            text=True,
            timeout=600,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception as exc:
        print(f"  !! pip failed: {exc}")
        return
    still = _python_packages_missing()
    if still:
        tail = (result.stderr or result.stdout or "").strip().splitlines()[-4:]
        print(f"  !! Could not install: {', '.join(p.split('>')[0] for p in still)}")
        for line in tail:
            print(f"     {line}")
        return
    print("  Python deps  : installed")


def _ensure_dependencies(port: int, video_port, install: bool) -> None:
    """Bring a fresh Windows PC up to the point where a phone can connect."""
    _ensure_python_packages(install)
    _ensure_vigem(install)
    checks = [("UDP", port)]
    if video_port:
        checks.append(("TCP", video_port))
    _ensure_firewall(checks, install)


def local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return ips or ["127.0.0.1"]


def _install_console_close_handler(on_close):
    """
    Run [on_close] when the user closes the console window (or logs off).

    A console window's X button terminates the process without running
    ``finally`` blocks, which used to leave the ViGEm pads plugged in as
    "Unknown" devices. Windows gives the handler a few seconds to tidy up.
    """
    if os.name != "nt":
        return None
    try:
        import ctypes
        from ctypes import wintypes

        routine = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.DWORD)

        def _handler(event: int) -> bool:
            # Ctrl+Break (1): tidy up, then let the default handler kill us.
            if event == 1:
                try:
                    on_close()
                except Exception:
                    pass
                return False
            # 2 = close, 5 = logoff, 6 = shutdown. Ctrl+C stays with Python.
            if event in (2, 5, 6):
                try:
                    on_close()
                except Exception:
                    pass
                return True
            return False

        callback = routine(_handler)
        if not ctypes.windll.kernel32.SetConsoleCtrlHandler(callback, True):
            return None
        # The callback must outlive this function or Windows calls freed memory.
        return callback
    except Exception:
        return None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="lemoGamePad ultimate - virtual gamepad server (by Alam showkat)"
    )
    ap.add_argument("--port", type=int, default=9876, help="control UDP port (default 9876)")
    ap.add_argument(
        "--pad",
        choices=["x360", "ds4"],
        default="x360",
        help="virtual pad type: x360 (best compatibility) or ds4",
    )
    ap.add_argument(
        "--max-phones",
        type=int,
        default=4,
        help="how many phones can share this PC at once, 1-4 (default 4)",
    )
    ap.add_argument("--no-rumble", action="store_true", help="do not forward rumble to the phone")
    ap.add_argument("--no-video", action="store_true", help="disable the PC screen stream")
    ap.add_argument(
        "--video-port",
        type=int,
        default=None,
        help="screen stream TCP port (default: control port + 1)",
    )
    ap.add_argument("--video-fps", type=int, default=12, help="screen stream frame rate (default 12)")
    ap.add_argument("--video-width", type=int, default=640, help="stream width in pixels (default 640)")
    ap.add_argument("--video-quality", type=int, default=55, help="JPEG quality 20-90 (default 55)")
    ap.add_argument("--no-qr", action="store_true", help="do not print the pairing QR code")
    ap.add_argument(
        "--no-install-deps",
        action="store_true",
        help="do not auto-install ViGEmBus / Python packages or open the firewall",
    )
    ap.add_argument("--list-ip", action="store_true", help="print local IPs and exit")
    args = ap.parse_args(argv)

    if args.list_ip:
        for ip in local_ips():
            print(ip)
        return 0

    if not sys.stdout.isatty():
        try:
            sys.stdout.reconfigure(line_buffering=True)
        except Exception:
            pass

    # One-time setup: Python packages (source only), ViGEmBus driver, firewall.
    _ensure_dependencies(
        args.port,
        None if args.no_video else (args.video_port or args.port + 1),
        install=not args.no_install_deps,
    )

    try:
        server = Server(
            args.port,
            args.pad,
            video_port=args.video_port,
            video_fps=args.video_fps,
            video_width=args.video_width,
            video_quality=args.video_quality,
            video_enabled=not args.no_video,
            qr_enabled=not args.no_qr,
            max_phones=args.max_phones,
        )
    except OSError as exc:
        print(f"Could not listen on UDP {args.port}: {exc}")
        print("Another copy of lemoGamePad-server may already be running.")
        return 1

    server.rumble_enabled = not args.no_rumble

    def _stop(_signum, _frame):
        raise KeyboardInterrupt

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, _stop)
        except Exception:
            pass

    # Keep a reference for the lifetime of the process.
    _console_handler = _install_console_close_handler(server.release_pads)

    try:
        server.run()
    except KeyboardInterrupt:
        print("\nbye")
    finally:
        # Release the ViGEm targets so Windows drops the virtual pads instead
        # of leaving "Unknown" ghost controllers behind.
        server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
