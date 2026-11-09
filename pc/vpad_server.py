#!/usr/bin/env python3
"""
lemoGamePad ultimate (by Alam showkat) - turns your Android phone into a
PS3-style gamepad.

The phone sends compact UDP packets (see protocol below); this program feeds
them into a real virtual gamepad: ViGEmBus on Windows, or the kernel's
uinput/evdev interface on Linux. Games see a normal controller with analog
sticks, pressure-sensitive triggers and rumble.

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
import base64
import hashlib
import io
import os
import queue
import secrets
import select
import shutil
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
from dataclasses import dataclass
from typing import Optional

from web_ui import WebGamepadServer

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
    label: str = "phone"
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
    # Browser pads do not have a UDP address to send rumble to; this callback
    # pushes the motor speeds down their WebSocket instead. None for phones.
    web_send: Optional[object] = None
    # Last input sequence actually pushed into the virtual pad. UDP can deliver
    # packets late or twice; applying an older state after a newer one is what
    # players feel as a one-frame "snap back".
    applied_seq: Optional[int] = None
    last_apply: float = 0.0

    def describe(self) -> str:
        return f"{self.name} @ {self.addr[0]}"


class WindowsVirtualPad:
    """Virtual pad backed by ViGEmBus, the Windows kernel driver."""

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


def _seq_newer(seq: int, previous: Optional[int]) -> bool:
    """
    True when a 16 bit sequence number is newer than the last one applied.

    The compare wraps at 65536, so a phone can run for hours without its
    sequence counter confusing the receiver. Anything more than half the
    range away is treated as a restart instead of a forward step.
    """
    if previous is None:
        return True
    delta = (int(seq) - int(previous)) & 0xFFFF
    return 0 < delta < 0x8000


def _raise_thread_priority() -> None:
    """
    Ask the OS to schedule the UDP loop ahead of background work.

    Screen capture and JPEG encoding are the heaviest things this process does
    and they run on other threads. Without this, a busy PC can delay the input
    thread by several milliseconds, which is exactly the jitter this pass is
    meant to remove. Both calls are best effort: a restricted shell or a
    locked-down Windows session may refuse them.
    """
    if os.name == "nt":
        try:
            import ctypes

            k32 = ctypes.windll.kernel32
            # THREAD_PRIORITY_HIGHEST == 2 (the normal priority is 0).
            k32.SetThreadPriority(k32.GetCurrentThread(), 2)
        except Exception:
            pass
        return
    try:
        os.nice(-5)
    except Exception:
        pass


def _begin_high_res_timer() -> None:
    """
    Windows rounds sleeps and timeouts up to ~15.6 ms by default. The screen
    stream paces itself with ``time.sleep``, so on a machine without a game
    running that granularity turns 20 fps into 12 fps and adds jitter to the
    receive timeout. Requesting 1 ms resolution is what most game servers do.
    """
    global _high_res_timer_active
    if os.name != "nt":
        return
    try:
        import ctypes

        ctypes.windll.winmm.timeBeginPeriod(1)
        _high_res_timer_active = True
    except Exception:
        pass


def _end_high_res_timer() -> None:
    """Release the 1 ms timer request so the PC can idle normally again."""
    global _high_res_timer_active
    if os.name != "nt" or not _high_res_timer_active:
        return
    try:
        import ctypes

        ctypes.windll.winmm.timeEndPeriod(1)
    except Exception:
        pass
    _high_res_timer_active = False


_high_res_timer_active = False


class _MissingPad:
    """Stands in when a platform backend cannot even be imported."""

    def __init__(self, kind: str, on_rumble=None, error: str = ""):
        self.kind = kind
        self.ok = False
        self.pad = None
        self.on_rumble = on_rumble
        self.rumble_error = ""
        self.error = error or "virtual gamepad backend unavailable"
        self._last = InputState()

    def apply(self, state: InputState) -> None:
        self._last = state

    def close(self) -> None:
        pass


def create_virtual_pad(kind: str, on_rumble=None):
    """
    Return this platform's virtual gamepad implementation.

    Windows keeps using ViGEmBus through ``vgamepad``. Linux creates a standard
    evdev device through ``/dev/uinput`` (see ``linux_pad.py``), which needs no
    kernel module of its own. The import is lazy so neither platform pulls in
    the other's dependencies.
    """
    if sys.platform.startswith("linux"):
        try:
            from linux_pad import LinuxVirtualPad
        except Exception as exc:  # pragma: no cover - broken installation
            return _MissingPad(
                kind,
                on_rumble,
                error=(
                    f"could not load the Linux virtual pad ({exc}). "
                    "Run: sudo sh pc/linux/install-linux.sh"
                ),
            )
        return LinuxVirtualPad(kind, on_rumble=on_rumble)
    return WindowsVirtualPad(kind, on_rumble=on_rumble)


class PadSlot:
    """
    One virtual controller slot. Up to eight phones can share a PC; each gets
    its own pad, created lazily on its first input packet and reused when that
    phone leaves so the controller index stays stable in games.
    """

    def __init__(self, index: int, kind: str, on_rumble):
        self.index = index
        self.kind = kind
        self.on_rumble = on_rumble
        self.peer: Optional["Peer"] = None
        self.pad = None  # WindowsVirtualPad or LinuxVirtualPad
        # The pad backend re-reports the current motor speeds periodically, so
        # keep the last value to avoid duplicate packets to the phone.
        self.rumble_state = (0, 0)

    def ensure_pad(self):
        if self.pad is None:
            self.pad = create_virtual_pad(self.kind, on_rumble=self._rumble)
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


# ---------------------------------------------------------------- screen capture
#
# Windows and X11 grab the screen with mss. A native Wayland session does not
# let ordinary programs read the screen (mss only sees XWayland windows, often
# as black), so on Wayland the server asks a screenshot tool for each frame.
# Set LEMO_CAPTURE=mss|grim|spectacle|gnome-screenshot to force one backend.

_WAYLAND_TOOLS = (
    # name, argv ("{file}" is replaced by a temp path), writes image to stdout?
    ("grim", ["grim", "-t", "ppm", "-"], True),  # Sway, Hyprland, wlroots, COSMIC
    ("spectacle", ["spectacle", "-b", "-n", "-f", "-o", "{file}"], False),  # KDE Plasma
    ("gnome-screenshot", ["gnome-screenshot", "-f", "{file}"], False),  # GNOME (best effort)
)

_WAYLAND_HELP = (
    "install grim (Sway/Hyprland/wlroots), spectacle (KDE) or gnome-screenshot (GNOME), "
    "or log in with an X11 session"
)


def linux_session_type() -> str:
    """'wayland', 'x11' or '' (unknown / headless)."""
    kind = os.environ.get("XDG_SESSION_TYPE", "").strip().lower()
    if kind in ("wayland", "x11"):
        return kind
    if os.environ.get("WAYLAND_DISPLAY"):
        return "wayland"
    if os.environ.get("DISPLAY"):
        return "x11"
    return ""


class _MssCapture:
    """Windows and X11: grab the primary monitor with mss."""

    name = "mss"

    def __init__(self) -> None:
        import mss
        from PIL import Image

        self._image = Image
        self._grabber = getattr(mss, "MSS", mss.mss)()
        # monitors[0] is the union of every screen, [1] is the primary.
        if len(self._grabber.monitors) < 2:
            self._grabber.close()
            raise RuntimeError("no monitor found (headless session?)")
        self._monitor = self._grabber.monitors[1]

    def grab(self):
        shot = self._grabber.grab(self._monitor)
        return self._image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")

    def close(self) -> None:
        try:
            self._grabber.close()
        except Exception:
            pass


class _CommandCapture:
    """Wayland: run a screenshot command for every frame."""

    def __init__(self, name: str, argv: list, to_stdout: bool) -> None:
        self.name = name
        self._argv = argv
        self._to_stdout = to_stdout
        self._tmp = ""
        if not to_stdout:
            fd, self._tmp = tempfile.mkstemp(prefix="lemo-shot-", suffix=".png")
            os.close(fd)

    def grab(self):
        from PIL import Image

        argv = [arg.replace("{file}", self._tmp) for arg in self._argv]
        proc = subprocess.run(
            argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=8, check=False
        )
        if proc.returncode != 0:
            tail = proc.stderr.decode("utf-8", "replace").strip().splitlines()
            raise RuntimeError(f"{self.name} exited with {proc.returncode}" + (f": {tail[-1]}" if tail else ""))
        if self._to_stdout:
            data = proc.stdout
        else:
            with open(self._tmp, "rb") as handle:
                data = handle.read()
        if not data:
            raise RuntimeError(f"{self.name} returned no image")
        img = Image.open(io.BytesIO(data))
        img.load()
        return img.convert("RGB")

    def close(self) -> None:
        if self._tmp:
            try:
                os.remove(self._tmp)
            except OSError:
                pass


def open_screen_capture():
    """Return (capture, notes) for the first backend that really produces a frame."""
    forced = os.environ.get("LEMO_CAPTURE", "").strip().lower()
    session = linux_session_type() if sys.platform.startswith("linux") else ""
    tools = {name: (argv, to_stdout) for name, argv, to_stdout in _WAYLAND_TOOLS}

    if forced:
        order = [forced]
    elif session == "wayland":
        order = [name for name, argv, _ in _WAYLAND_TOOLS if shutil.which(argv[0])] + ["mss"]
    else:
        order = ["mss"]

    problems = []
    for name in order:
        capture = None
        try:
            if name == "mss":
                capture = _MssCapture()
            elif name in tools:
                argv, to_stdout = tools[name]
                if not shutil.which(argv[0]):
                    raise RuntimeError("not installed")
                capture = _CommandCapture(name, argv, to_stdout)
            else:
                raise RuntimeError("unknown backend")
            capture.grab()  # probe now, so a broken tool is skipped before any phone connects
            notes = list(problems)
            if name == "mss" and session == "wayland":
                notes.append("Wayland session: mss only sees XWayland windows (" + _WAYLAND_HELP + ")")
            return capture, notes
        except Exception as exc:
            problems.append(f"{name}: {exc}")
            if capture is not None:
                capture.close()

    hint = f" - {_WAYLAND_HELP}" if session == "wayland" else ""
    raise RuntimeError("; ".join(problems) + hint)


class VideoClient:
    """One PiP viewer with a latest-frame queue and a negotiated quality profile."""

    def __init__(self, server: "VideoServer", sock: socket.socket, addr: tuple, quality: str = "balanced"):
        self.server = server
        self.sock = sock
        self.addr = addr
        self.quality = quality
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

    def _send_frame(self, frame: bytes) -> bool:
        """
        Send one length-prefixed frame without ever corrupting the stream.

        ``socket.sendall`` can time out after writing part of the buffer. The
        old code then continued with the next frame, so that frame's 4-byte
        length prefix landed in the middle of the truncated JPEG and every
        later frame was misparsed. A partial write is therefore fatal for this
        viewer; the phone reconnects and starts from a clean frame boundary.
        """
        view = memoryview(frame)
        sent = 0
        while sent < len(view):
            try:
                count = self.sock.send(view[sent:])
            except socket.timeout:
                if sent:
                    raise
                return False
            if count <= 0:
                raise OSError("socket closed while sending")
            sent += count
        return True

    def _run(self) -> None:
        try:
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # Bound kernel buffering so slow phones don't accumulate seconds of
            # stale video behind the one-frame application queue.
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 32 * 1024)
            # Long enough for a weak Wi-Fi / Bluetooth link to drain one frame.
            self.sock.settimeout(5.0)
            while self.alive and self.server.running:
                try:
                    frame = self.queue.get(timeout=1.0)
                except queue.Empty:
                    continue
                try:
                    if not self._send_frame(frame):
                        continue
                except socket.timeout:
                    # Part of a frame is already on the wire; stop now rather
                    # than let the next frame's header corrupt the stream.
                    break
                except OSError:
                    break
        finally:
            self.close()
            self.server.drop_client(self)


class VideoServer:
    """
    Streams length-prefixed JPEG frames over TCP. Phones may request low,
    balanced or high quality independently. Each viewer has a one-frame queue,
    so a slow phone cannot hold up capture or other viewers.
    """

    QUALITY_IDS = {0: "low", 1: "balanced", 2: "high"}

    def __init__(
        self,
        port: int,
        fps: int = 20,
        width: int = 800,
        quality: int = 50,
        max_viewers: int = 4,
    ):
        self.port = port
        self.fps = max(1, min(fps, 30))
        # The "high" profile is capped at 1920 px, so a larger balanced width
        # would make the sharper profile lower-resolution than the default.
        self.width = max(160, min(width, 1920))
        self.quality = max(20, min(quality, 90))
        self.max_viewers = max(1, max_viewers)
        self.enabled = False
        self.error = ""
        self.capture_backend = ""
        self.running = False
        self._thread: Optional[threading.Thread] = None
        self._capture: Optional[threading.Thread] = None
        self._server: Optional[socket.socket] = None
        self._client_lock = threading.Lock()
        self._clients: list[VideoClient] = []
        self._handshakes = 0

    @property
    def viewers(self) -> int:
        with self._client_lock:
            return len(self._clients)

    def _profile_options(self, profile: str) -> tuple[int, int, int]:
        if profile == "low":
            return (
                max(1, round(self.fps * 0.75)),
                max(160, self.width // 2),
                max(20, self.quality - 20),
            )
        if profile == "high":
            return (
                min(30, max(self.fps, round(self.fps * 1.25))),
                min(1920, max(self.width, round(self.width * 1.5))),
                min(90, self.quality + 20),
            )
        return self.fps, self.width, self.quality

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
            from PIL import Image  # noqa: F401
        except Exception as exc:  # pragma: no cover
            self.error = f"screen capture needs pillow ({exc})"
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
            try:
                srv.close()
            except Exception:
                pass
            return

        self.enabled = True
        while self.running:
            try:
                client_sock, addr = srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break

            # The hello read below takes up to 0.25 s. Doing it inline let one
            # client that connects and stays silent stall every other viewer,
            # so each connection gets its own short-lived handshake thread.
            with self._client_lock:
                full = len(self._clients) >= self.max_viewers
                if not full and self._handshakes < self.max_viewers + 2:
                    self._handshakes += 1
                else:
                    full = True
            if full:
                try:
                    client_sock.close()
                except OSError:
                    pass
                continue

            threading.Thread(
                target=self._handshake,
                args=(client_sock, addr),
                name=f"lemo-video-hello-{addr[0]}",
                daemon=True,
            ).start()

    def _handshake(self, client_sock: socket.socket, addr: tuple) -> None:
        try:
            # New phones send LGVQ + a profile byte. Older apps send nothing;
            # after a short timeout they keep receiving the legacy defaults.
            profile = "balanced"
            client_sock.settimeout(0.25)
            hello = bytearray()
            try:
                while len(hello) < 5:
                    chunk = client_sock.recv(5 - len(hello))
                    if not chunk:
                        break
                    hello.extend(chunk)
            except socket.timeout:
                pass
            except OSError:
                client_sock.close()
                return

            if len(hello) == 5 and hello[:4] == b"LGVQ":
                profile_id = hello[4]
                profile = self.QUALITY_IDS.get(profile_id, "balanced")
                resolved_id = next(
                    (key for key, name in self.QUALITY_IDS.items() if name == profile),
                    1,
                )
                try:
                    # Negative frame length is reserved for the one-time
                    # quality acknowledgement; ordinary frames are positive.
                    # Echo the profile actually used, not an unknown request.
                    client_sock.sendall(struct.pack(">iB", -1, resolved_id))
                except OSError:
                    client_sock.close()
                    return

            try:
                client_sock.settimeout(None)
            except OSError:
                client_sock.close()
                return

            client = VideoClient(self, client_sock, addr, profile)
            with self._client_lock:
                if len(self._clients) >= self.max_viewers:
                    client_sock.close()
                    return
                self._clients.append(client)
            print(f"[video] phone viewing the PC screen from {addr[0]} ({profile})")
            client.start()
        finally:
            with self._client_lock:
                self._handshakes = max(0, self._handshakes - 1)

    def _capture_loop(self) -> None:
        try:
            from PIL import Image
        except Exception as exc:  # pragma: no cover
            self.error = f"screen capture needs pillow ({exc})"
            print(f"  !! {self.error}")
            return

        resampling = getattr(Image, "Resampling", Image).BILINEAR
        frame_index = 0

        try:
            capture, notes = open_screen_capture()
        except Exception as exc:  # pragma: no cover - headless/RDP/Wayland
            self.error = f"screen capture unavailable ({exc})"
            print(f"  !! {self.error}")
            return
        self.capture_backend = capture.name
        for note in notes:
            print(f"  !! screen capture: {note}")

        try:
            while self.running:
                with self._client_lock:
                    clients = list(self._clients)
                if not clients:
                    # Nobody is watching: do not burn CPU capturing.
                    time.sleep(0.2)
                    frame_index = 0
                    continue

                groups: dict[str, list[VideoClient]] = {}
                for client in clients:
                    groups.setdefault(client.quality, []).append(client)
                options = {profile: self._profile_options(profile) for profile in groups}
                target_fps = max(item[0] for item in options.values())
                interval = 1.0 / target_fps
                started = time.monotonic()

                try:
                    img = capture.grab()
                    frame_index += 1

                    for profile, viewers in groups.items():
                        profile_fps, width, jpeg_quality = options[profile]
                        # Distribute lower-rate profiles evenly across
                        # capture ticks without rounding 12 fps down to 9.
                        if (frame_index * profile_fps) // target_fps <= ((frame_index - 1) * profile_fps) // target_fps:
                            continue

                        variant = img
                        if img.width > width:
                            height = max(1, round(img.height * width / img.width))
                            variant = img.resize((width, height), resampling)

                        buffer = io.BytesIO()
                        variant.save(
                            buffer,
                            format="JPEG",
                            quality=jpeg_quality,
                            subsampling=2,
                            optimize=False,
                        )
                        payload = buffer.getvalue()
                        packet = struct.pack(">I", len(payload)) + payload
                        for client in viewers:
                            client.offer(packet)
                except Exception as exc:  # pragma: no cover
                    message = f"screen capture failed ({exc})"
                    if message != self.error:
                        self.error = message
                        print(f"  !! {message}")
                    time.sleep(0.5)
                    continue

                elapsed = time.monotonic() - started
                if elapsed < interval:
                    time.sleep(interval - elapsed)
        finally:
            capture.close()

def qr_payload(ip: str, port: int, name: str) -> str:
    query = urllib.parse.urlencode({"h": ip, "p": port, "n": name})
    return f"vpad://connect?{query}"


def _make_qr(payload: str):
    """Build a QR object, raising ImportError when qrcode is missing."""
    import qrcode

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=8,
        border=2,
    )
    qr.add_data(payload)
    qr.make(fit=True)
    return qr


def print_qr_block(payload: str, out_dir: str, filename: str,
                   link_label: str = "Pairing link") -> bool:
    """
    Draw [payload] as a QR in the console, save it as [filename], and print the
    link. Returns False when the optional qrcode/Pillow packages are missing.
    """
    try:
        qr = _make_qr(payload)
    except Exception as exc:  # pragma: no cover - optional dependency
        print(f"  QR code unavailable ({exc}). Install with: pip install qrcode pillow")
        return False

    print()
    try:
        qr.print_ascii(invert=True)
    except Exception:
        pass

    try:
        img = qr.make_image(fill_color="black", back_color="white")
        png = os.path.join(out_dir, filename)
        img.save(png)
        print(f"  QR image saved: {png}")
    except Exception as exc:  # pragma: no cover
        print(f"  could not save QR image: {exc}")

    print(f"  {link_label}: {payload}")
    return True


def print_qr(ip: str, port: int, name: str, out_dir: str) -> bool:
    """Draws a scannable QR in the console and saves a PNG for each address."""
    safe = ip.replace(":", "-").replace(".", "_")
    return print_qr_block(
        qr_payload(ip, port, name),
        out_dir,
        f"lemoGamePad-qr-{safe}.png",
        "Pairing link",
    )


def save_qr_png(payload: str, path: str) -> bool:
    """Write one QR PNG; returns False when qrcode/Pillow is unavailable."""
    try:
        qr = _make_qr(payload)
        qr.make_image(fill_color="black", back_color="white").save(path)
        return True
    except Exception:  # pragma: no cover - optional dependency
        return False


class Server:
    """
    UDP control server for up to eight phones at once.

    Phones are keyed by their 32 bit token, which they keep for the whole
    session, so a phone that drops off and comes back reuses the same peer and
    the same virtual controller instead of creating a duplicate. Each peer owns
    one [PadSlot]; pads are created lazily on the first input packet and are
    reused after that so the controller order in games stays stable.

    Eight is the supported maximum and the default. Windows' XInput API still
    exposes only four pads; with the default ``x360`` backend, slots 5-8
    automatically use the DualShock 4/HID backend so they remain usable by
    DirectInput/Raw Input games and emulators. XInput-only games stay capped at
    four, which is a Windows limit rather than a server limit.
    """

    # The CLI/storage limit. Raising it further needs no protocol change, but
    # XInput's four-controller ceiling makes more than eight pads impractical.
    MAX_PHONES = 8

    PEER_TIMEOUT = 8.0
    STATUS_INTERVAL = 0.5
    HOUSEKEEPING_INTERVAL = 0.2
    # How long select() waits with no traffic before housekeeping runs again.
    SOCKET_TIMEOUT = 0.25
    # Upper bound on datagrams handled in one wakeup. A bounded drain keeps a
    # flood (or a bad Wi-Fi link replaying packets) from starving housekeeping.
    MAX_DRAIN = 512
    # A token may move to a different source address once its old address has
    # been silent for this long. Same-IP port changes are accepted at once.
    # Without this grace any LAN host that learns a token could hijack a live
    # controller slot with one spoofed packet.
    ADDRESS_GRACE = 1.5

    @staticmethod
    def _slot_kinds(pad_kind: str, count: int, windows: Optional[bool] = None) -> list:
        """
        Backend name for each controller slot.

        Windows' XInput API exposes exactly four pads, so on Windows a request
        for more than four phones keeps Xbox 360 pads for P1-P4 and switches
        P5-P8 to the DualShock 4/HID backend. Those extra pads still appear to
        DirectInput/Raw Input games and emulators; XInput-only games remain
        capped at four by Windows itself. Linux has no such ceiling, so every
        slot keeps the requested backend.

        [windows] exists so the choice can be tested on any platform.
        """
        if windows is None:
            windows = os.name == "nt"
        kinds = [pad_kind] * count
        if windows and pad_kind == "x360" and count > 4:
            for index in range(4, count):
                kinds[index] = "ds4"
        return kinds

    def __init__(
        self,
        port: int,
        pad_kind: str,
        release_after: float = 1.5,
        video_port: Optional[int] = None,
        video_fps: int = 20,
        video_width: int = 800,
        video_quality: int = 50,
        video_enabled: bool = True,
        qr_enabled: bool = True,
        max_phones: int = 8,
        web_port: Optional[int] = None,
        web_enabled: bool = True,
        web_key: str = "",
    ):
        self.port = port
        self.pad_kind = pad_kind
        self.release_after = release_after
        self.max_phones = max(1, min(int(max_phones), self.MAX_PHONES))
        self.slot_kinds = self._slot_kinds(pad_kind, self.max_phones)
        self.lock = threading.RLock()
        # Serialises device creation. Games identify controllers by the order
        # the OS enumerates them, so pads must be created from P1 upward even
        # when two web tabs register at the same moment.
        self._pad_lock = threading.Lock()
        self.peers: dict[int, Peer] = {}
        self.slots = [
            PadSlot(index, self.slot_kinds[index], self._slot_rumble)
            for index in range(self.max_phones)
        ]
        # Create the first pad up front so a missing driver is reported in the
        # banner instead of the first time a phone presses a button.
        self._ensure_pads_through(0)

        self.qr_enabled = qr_enabled
        self.web_port = int(web_port) if web_port else port + 2
        self.web_key = web_key or secrets.token_urlsafe(6)
        self.web: Optional[WebGamepadServer] = None
        if web_enabled:
            self.web = WebGamepadServer(
                self.web_port,
                self.web_key,
                on_input=self._web_input,
                on_ping=self._web_ping,
                on_close=self._drop_web_peer,
                on_token=self._new_web_token,
                log=self._log,
                server_name=socket.gethostname()[:32],
                max_players=self.max_phones,
                screen_info=self._screen_info,
            )
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
        # On Linux/BSD, SO_REUSEADDR lets a second server bind the same UDP
        # port and split the phone's packets between two processes, so it is
        # deliberately not set here.
        try:
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        except OSError:
            pass
        # The default receive buffer is 64 KB. A 250 Hz phone stream is tiny,
        # but a Wi-Fi retransmit can deliver a burst at once and a full buffer
        # silently drops the newest input. Ask for room to absorb it.
        for option in (socket.SO_RCVBUF, socket.SO_SNDBUF):
            try:
                self.sock.setsockopt(socket.SOL_SOCKET, option, 512 * 1024)
            except OSError:
                pass
        self.sock.bind(("0.0.0.0", port))
        # Non-blocking + select(): one wakeup can drain everything the kernel
        # has queued, instead of applying one packet per 250 ms timeout.
        self.sock.setblocking(False)
        # Browser input arrives on the HTTP/WebSocket threads, which the UDP
        # select() cannot see. Without a wakeup channel a tab would wait for
        # the 250 ms select timeout before its pad state was applied.
        try:
            self._wake_recv, self._wake_send = socket.socketpair()
            self._wake_recv.setblocking(False)
            self._wake_send.setblocking(False)
        except OSError:  # pragma: no cover - exotic platform without socketpair
            self._wake_recv = None
            self._wake_send = None
        self.running = True
        # token -> (peer, newest InputState) waiting for the pad to be updated.
        # Collapsing a burst to its newest state is the single biggest win for
        # "latency spikes": the game never replays input from 150 ms ago.
        self._pending: dict[int, tuple[Peer, InputState]] = {}
        self._status_thread: Optional[threading.Thread] = None
        self.hostname = socket.gethostname()[:32]
        self.started = time.time()
        self.rumble_enabled = True
        self._tty = sys.stdout.isatty()
        self._status_live = False
        self._last_plain_status = 0.0
        self._last_housekeeping = 0.0
        self._full_logged_at = 0.0
        # Computed once here and refreshed by the status thread: discovery must
        # never do DNS or route lookups on the packet-receive thread.
        self._ip_cache = self._pack_addresses(local_ips())
        self._ip_cache_at = time.time()
        # Input/idle log lines are produced on the receive thread; printing
        # there can block for seconds in a Windows console with QuickEdit
        # selection active. The status thread drains this queue instead.
        self._log_queue: "queue.Queue[str]" = queue.Queue(maxsize=256)

    @staticmethod
    def _pack_addresses(addresses) -> list:
        out = []
        for text in addresses:
            try:
                packed = socket.inet_aton(text)
            except OSError:
                continue
            if len(packed) == 4 and packed not in out:
                out.append(packed)
        return out[:8]

    def _refresh_offer_addresses(self) -> None:
        self._ip_cache = self._pack_addresses(local_ips())
        self._ip_cache_at = time.time()

    # ---------------------------------------------------------------- discovery
    def _handle_discover(self, data: bytes, addr: tuple) -> None:
        if len(data) < HEADER.size:
            return
        _, _, _, seq, token = HEADER.unpack_from(data)

        # Echo the phone's own token when it already has one: a phone that
        # re-pairs (or switches Wi-Fi -> USB) must keep its slot instead of
        # being handed a second controller.
        if token == 0:
            # Never hand out 0 (reserved for "assign me") and never collide
            # with a phone that is already connected.
            with self.lock:
                while token == 0 or token in self.peers:
                    token = secrets.randbits(32)
        # A DISCOVER carrying a known token is only an echo request now.
        # Rebinding a live peer here let any LAN host move a controller to its
        # own address with one spoofed packet. Address changes are handled by
        # _register_peer/_handle_ping after the old address goes quiet.

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
        """Cached packed IPv4 addresses of this machine (max 8).

        The cache is refreshed from the status thread, never here: local_ips()
        can perform route/DNS lookups and must stay off the receive path.
        """
        return self._ip_cache

    # ------------------------------------------------------------------- peers
    def _ensure_pads_through(self, index: int) -> None:
        """
        Create every virtual pad from P1 up to [index], in slot order.

        Games number their controllers by the order the OS enumerates them,
        not by our labels: if a phone asks for P5 while no other pad exists,
        creating only slot 5 would make it the game's second controller. The
        lower pads are therefore created first. They stay alive for the
        lifetime of the server, so the order does not change later.

        [index] is a zero-based slot index and is clamped to the configured
        slots. Creation is serialised because phone packets and browser
        WebSocket threads can both register a peer.
        """
        if not self.slots:
            return
        last = max(0, min(int(index), len(self.slots) - 1))
        with self._pad_lock:
            for slot in self.slots[:last + 1]:
                slot.ensure_pad()

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

    def _can_rebind(self, peer: Peer, addr: tuple, now: float) -> bool:
        """
        Whether a token is allowed to move to [addr].

        A new source port on the same interface is the normal reconnect path
        and is accepted immediately. A different IP (Wi-Fi -> USB, a new DHCP
        lease) waits until the old address has been quiet for ADDRESS_GRACE,
        so a spoofed packet cannot steal a controller that is still active.
        """
        if peer.addr[0] == addr[0]:
            return True
        return now - peer.last_seen >= self.ADDRESS_GRACE

    def _register_peer(self, token: int, addr: tuple, now: float, want_slot: int = 0,
                       label: str = "phone", web_send=None):
        """
        Finds or creates the peer for a token.

        [want_slot] is the 1-based pad number the phone asked for (0 = auto).
        A free requested slot is honoured; when it is taken the phone keeps its
        current pad (or takes the next free one) and tries again on the next
        packet, so a slot frees up without reconnecting. [label] names the
        source in logs ("phone" or "browser"). [web_send] installs a callback
        so rumble reaches a browser tab instead of a UDP address.

        Returns (peer, is_new, reason). peer is None when the PC already serves
        [max_phones] controllers ("full") or when the token tried to move from
        a still-active address it does not own ("move").
        """
        moved_from = -1
        ensure_index = None
        is_new = False
        with self.lock:
            peer = self.peers.get(token)
            if peer is not None:
                if peer.addr != addr:
                    if not self._can_rebind(peer, addr, now):
                        return None, False, "move"
                    # Same phone, new source port or a different interface.
                    # The new socket starts its sequence counter fresh, so the
                    # old position must not mark its first packets as "late".
                    peer.addr = addr
                    peer.applied_seq = None
                    peer.seq = 0
                    self._pending.pop(token, None)
                if web_send is not None:
                    peer.web_send = web_send
                peer.last_seen = now
                # A phone can ask for a different pad from the settings sheet
                # while it is already connected. Move it only when the wanted
                # slot is free; otherwise it stays where it is and retries.
                if 1 <= want_slot <= self.max_phones:
                    target = self.slots[want_slot - 1]
                    if target.peer is None:
                        old = self._slot_of(peer)
                        if old is not None:
                            old.reset()
                            moved_from = old.index
                        target.peer = peer
                        peer.slot = target.index
                        peer.name = f"{peer.label} {target.index + 1}"
                        ensure_index = target.index
            else:
                if len(self.peers) >= self.max_phones:
                    return None, False, "full"

                slot = None
                if 1 <= want_slot <= self.max_phones:
                    wanted = self.slots[want_slot - 1]
                    if wanted.peer is None:
                        slot = wanted
                if slot is None:
                    slot = next((s for s in self.slots if s.peer is None), None)
                if slot is None:
                    return None, False, "full"

                peer = Peer(
                    token=token,
                    addr=addr,
                    name=f"{label} {slot.index + 1}",
                    label=label,
                    slot=slot.index,
                    last_seen=now,
                    rate_mark=0,
                    rate_mark_time=now,
                    web_send=web_send,
                )
                slot.peer = peer
                self.peers[token] = peer
                ensure_index = slot.index
                is_new = True

        # Creating a ViGEm/uinput device can take tens of milliseconds; do it
        # after releasing the lock so discovery/status/ping never wait for it.
        # A move needs this too: a target slot that was never used has no pad,
        # and _flush_inputs deliberately skips a slot without one.
        if ensure_index is not None:
            self._ensure_pads_through(ensure_index)
        if moved_from >= 0:
            self._log(
                f"controller moved: {peer.describe()} "
                f"(pad {moved_from + 1} -> {peer.slot + 1})"
            )
        return peer, is_new, ""

    def _drop_peer(self, peer: Peer) -> None:
        with self.lock:
            self.peers.pop(peer.token, None)
            slot = self._slot_of(peer)
            if slot is not None:
                slot.reset()
            peer.released = True
        self._log(f"controller disconnected: {peer.describe()}")

    # --------------------------------------------------------------- browser pad
    def _new_web_token(self) -> int:
        """A fresh token for one browser connection, never colliding with a peer."""
        with self.lock:
            while True:
                token = secrets.randbits(32)
                if token and token not in self.peers:
                    return token

    def _screen_info(self):
        """Where the browser screen relay can reach the local video server."""
        video = self.video
        if video is None or not video.running or video.error:
            return None
        return {
            "host": "127.0.0.1",
            "port": video.port,
            "fps": video.fps,
            "width": video.width,
        }

    def _web_input(self, token: int, ip: str, state: dict, want_slot: int, send_rumble):
        """Adapt a browser message to the same path UDP phone input uses."""
        frame = InputState(
            buttons=int(state.get("buttons", 0)),
            lx=int(state.get("lx", 0)),
            ly=int(state.get("ly", 0)),
            rx=int(state.get("rx", 0)),
            ry=int(state.get("ry", 0)),
            l2=int(state.get("l2", 0)),
            r2=int(state.get("r2", 0)),
            battery=int(state.get("battery", 100)),
            seq=int(state.get("seq", 0)),
        )
        peer = self._submit_input(
            token,
            (ip, 0),
            time.time(),
            frame,
            want_slot,
            label="browser",
            web_send=send_rumble,
        )
        # The web connection runs on its own thread. Wake the UDP select loop
        # so this state is flushed to the pad now instead of at the next
        # 250 ms timeout.
        self._wake_loop()
        return peer.slot + 1 if peer is not None else 0

    def _web_ping(self, token: int) -> int:
        with self.lock:
            peer = self.peers.get(token)
            if peer is None:
                return 0
            peer.last_seen = time.time()
            return peer.slot + 1

    def _drop_web_peer(self, token: int) -> None:
        with self.lock:
            peer = self.peers.get(token)
            if peer is None or peer.web_send is None:
                return
        self._drop_peer(peer)

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
            web_send = peer.web_send
        if web_send is not None:
            try:
                web_send(small, large)
            except Exception:
                pass
            return
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
        if token == 0:
            # 0 means "assign me": a phone must run discovery before sending
            # input. Accepting it would let several phones share one peer.
            return
        if _pad not in (0, 1):
            _pad = 0
        # 1.5 adds an optional trailing byte: the 1-based pad number the phone
        # would like. Old phones omit it (auto), old servers ignore it.
        want_slot = data[INPUT_SIZE] if len(data) > INPUT_SIZE else 0
        state = InputState(
            buttons=buttons, lx=lx, ly=ly, rx=rx, ry=ry,
            l2=l2, r2=r2, battery=batt, seq=seq,
        )
        self._submit_input(token, addr, now, state, want_slot)

    def _submit_input(self, token: int, addr: tuple, now: float, state: InputState,
                      want_slot: int = 0, label: str = "phone",
                      web_send=None):
        """
        Apply one already-parsed input frame from a phone or a browser tab.

        Both transports share this path so slot assignment, sequence handling,
        idle release and coalescing stay identical.
        """
        if token == 0:
            return None
        peer, fresh, reason = self._register_peer(
            token, addr, now, want_slot, label=label, web_send=web_send
        )
        if peer is None:
            if now - self._full_logged_at > 10.0:
                self._full_logged_at = now
                if reason == "move":
                    self._log(
                        f"ignored {addr[0]}: token is still active at another address"
                    )
                else:
                    self._log(
                        f"ignored {addr[0]}: all {self.max_phones} controller slots are in use"
                    )
            return None

        with self.lock:
            gap = now - peer.last_packet if peer.last_packet else 0.0
            peer.last_packet = now
            peer.last_seen = now
            peer.packet_count += 1
            peer.battery = state.battery
            if web_send is not None:
                peer.web_send = web_send
            prev = peer.seq
            if peer.released:
                peer.released = False
            # A phone that restarts (or reconnects after a pause) begins again
            # at sequence 0. Forget the old position so its first packet is not
            # mistaken for an out-of-order duplicate.
            if gap > 2.0:
                peer.applied_seq = None
                peer.seq = state.seq
            elif prev == 0 or _seq_newer(state.seq, prev):
                # Only advance the metric counter for packets that are actually
                # newer. A late duplicate must not make the next forward gap
                # look like a huge loss on the settings sparkline.
                if prev and 0 < state.seq - prev < 300:
                    peer.lost += state.seq - prev - 1
                peer.seq = state.seq

        if fresh:
            self._log(
                f"controller connected: {peer.describe()} "
                f"(pad {peer.slot + 1}/{self.max_phones})"
            )

        # Keep only the newest state for this peer until the next flush. A
        # burst of queued datagrams then costs one pad update, not one per
        # packet, and the game always sees the freshest input.
        queued = self._pending.get(token)
        if queued is None or _seq_newer(state.seq, queued[1].seq):
            self._pending[token] = (peer, state)
        return peer

    def _wake_loop(self) -> None:
        """Nudge the select() loop after input arrived on a web thread."""
        if self._wake_send is None:
            return
        try:
            self._wake_send.send(b"\0")
        except (BlockingIOError, OSError):
            pass

    def _drain_wake(self) -> None:
        if self._wake_recv is None:
            return
        while True:
            try:
                if not self._wake_recv.recv(4096):
                    return
            except (BlockingIOError, OSError):
                return

    def _flush_inputs(self, now: float) -> None:
        """
        Push one pad update per phone for everything received since the last
        call. This is what turns a stalled link into a single catch-up frame
        instead of a visible replay of the last few hundred milliseconds.
        """
        if not self._pending:
            return
        pending, self._pending = self._pending, {}
        for peer, state in pending.values():
            slot = self._slot_of(peer)
            if slot is None or slot.pad is None or slot.peer is not peer:
                continue
            if not _seq_newer(state.seq, peer.applied_seq):
                # Late duplicate from before the last update; dropping it is
                # better than snapping the sticks backwards. If nothing has
                # been applied for half a second, treat it as a phone whose
                # sequence restarted on the same address and resync instead.
                if now - peer.last_apply < 0.5:
                    continue
                # Follow the new counter so later loss stats stay meaningful.
                peer.seq = state.seq
            try:
                slot.pad.apply(state)
            except Exception as exc:  # pragma: no cover - backend failure
                print(f"\n[!] pad update failed: {exc}")
                continue
            peer.applied_seq = state.seq
            peer.last_apply = now

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
        _begin_high_res_timer()
        _raise_thread_priority()
        if self.video is not None:
            self.video.start()
        if self.web is not None:
            self.web.start()
        self._print_banner()
        self._status_thread = threading.Thread(
            target=self._status_loop, name="lemo-status", daemon=True
        )
        self._status_thread.start()

        watch = [self.sock]
        if self._wake_recv is not None:
            watch.append(self._wake_recv)

        while self.running:
            try:
                ready, _, _ = select.select(watch, [], [], self.SOCKET_TIMEOUT)
            except (OSError, ValueError):
                break

            now = time.time()
            if ready:
                if self._wake_recv is not None and self._wake_recv in ready:
                    self._drain_wake()
                if self.sock in ready:
                    self._drain(now)
            # One pad update per phone per wakeup, using only the newest state
            # that arrived in this batch.
            self._flush_inputs(now)
            self._housekeeping(now)

        self.shutdown()

    def _dispatch(self, data: bytes, addr: tuple, now: float) -> None:
        """Route one datagram. Cheap packets (ping/discover) are answered inline."""
        if len(data) < HEADER.size:
            return
        magic, _ver, mtype, seq, token = HEADER.unpack_from(data)
        if magic != MAGIC:
            return
        if mtype == MSG_DISCOVER:
            self._handle_discover(data, addr)
            return
        if mtype == MSG_PING:
            self._handle_ping(data, addr, now, seq, token)
            return
        if mtype == MSG_INPUT and len(data) >= INPUT_SIZE:
            self._handle_input(data, addr, now)

    def _drain(self, now: float) -> None:
        """
        Read every datagram the kernel has queued, without blocking.

        The old loop handled one packet per iteration and then ran
        housekeeping, so a Wi-Fi retransmit burst queued 10-30 input frames.
        Each was applied in turn, making the game replay stale stick positions
        for a moment. Draining first and applying the newest state once removes
        that backlog entirely.
        """
        for _ in range(self.MAX_DRAIN):
            try:
                data, addr = self.sock.recvfrom(2048)
            except (BlockingIOError, socket.timeout):
                return
            except OSError:
                return
            self._dispatch(data, addr, now)

    def _handle_ping(self, data: bytes, addr: tuple, now: float, seq: int, token: int) -> None:
        loss_permille = -1
        assigned_slot = 0
        peer_present = False
        with self.lock:
            peer = self.peers.get(token)
            if peer is not None:
                if peer.addr != addr:
                    if not self._can_rebind(peer, addr, now):
                        # Do not answer a ping from an address that does not
                        # own this token yet; answering would also leak the
                        # peer's loss statistics to the sender.
                        return
                    # A ping from a new socket usually means the phone rebuilt
                    # its link; its sequence counter starts over too.
                    peer.addr = addr
                    peer.applied_seq = None
                    peer.seq = 0
                    self._pending.pop(token, None)
                peer.last_seen = now
                peer_present = True
                assigned_slot = peer.slot + 1
                received = max(0, peer.packet_count - peer.rate_mark)
                total = received + peer.lost
                if total > 0:
                    loss_permille = min(1000, round(peer.lost * 1000 / total))

        pong = HEADER.pack(MAGIC, VERSION, MSG_PONG, seq, token)
        if len(data) >= HEADER.size + 4:
            pong += data[HEADER.size : HEADER.size + 4]
        if peer_present:
            # Trailing fields added after 1.4. The phone shows the real uplink
            # loss, then the pad the PC actually assigned (the requested one
            # may have been busy). Older apps stop after the clock echo.
            pong += struct.pack("<H", max(0, loss_permille))
            pong += bytes([assigned_slot & 0xFF])
        try:
            self.sock.sendto(pong, addr)
        except OSError:
            pass

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
        self.running = False
        self._wake_loop()
        self._pending.clear()
        with self.lock:
            peers = list(self.peers.values())
            self.peers.clear()
        for peer in peers:
            slot = self._slot_of(peer)
            if slot is not None:
                slot.reset()
        if self.web is not None:
            try:
                self.web.stop()
            except Exception:
                pass
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
        for sock in (self._wake_recv, self._wake_send):
            if sock is not None:
                try:
                    sock.close()
                except Exception:
                    pass
        self._wake_recv = None
        self._wake_send = None
        thread = self._status_thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=1.0)
        self._drain_logs()
        _end_high_res_timer()

    # ------------------------------------------------------------------ display
    def _banner_lines(self):
        ips = local_ips()
        mixed = len(set(self.slot_kinds)) > 1
        if mixed:
            first_ds4 = self.slot_kinds.index("ds4") + 1
            pad_name = (
                f"Xbox 360 (P1-{first_ds4 - 1}) + "
                f"DualShock 4 (P{first_ds4}-P{self.max_phones})"
            )
        else:
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
        if self.web is not None:
            lines.append(f"  Browser pad  : {self.web.port} (TCP, Wi-Fi only)")
        else:
            lines.append("  Browser pad  : disabled")
        lines.append(f"  Controllers  : up to {self.max_phones} phones at once")
        if mixed:
            lines.append(
                "  Pads 5-8     : DS4/DirectInput (XInput only allows four pads)"
            )
        if os.name == "nt":
            driver = "OK (virtual gamepad driver installed)"
            if not _vigem_installed():
                driver = "MISSING - install it once (see messages above)"
            lines.append(f"  ViGEmBus     : {driver}")
        elif sys.platform.startswith("linux"):
            ready, reason = _uinput_status()
            status = "OK (virtual gamepad via /dev/uinput)" if ready else f"MISSING - {reason}"
            lines.append(f"  uinput       : {status}")
        for ip in ips:
            lines.append(f"  PC IP        : {ip}")
        if self.web is not None:
            for ip in ips:
                lines.append(f"  Web pad      : {self.web_url(ip)}")
        checks = [("UDP", self.port)]
        if self.video is not None:
            checks.append(("TCP", self.video.port))
        if self.web is not None:
            checks.append(("TCP", self.web.port))
        lines.extend(_firewall_lines(checks))
        lines.append("")
        lines.append("  Phone: open lemoGamePad ultimate, tap the status pill,")
        lines.append("         then tap 'Scan QR' and point the camera at the code below.")
        if self.web is not None:
            lines.append("  Browser: open the Web pad URL on any device on the same Wi-Fi.")
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

    def web_url(self, ip: str) -> str:
        key = urllib.parse.quote(self.web_key, safe="")
        return f"http://{ip}:{self.web_port}/?k={key}"

    def _print_banner(self) -> None:
        for line in self._banner_lines():
            print(line)

        if self.qr_enabled:
            ips = local_ips()
            if ips:
                print_qr(ips[0], self.port, self.hostname, os.getcwd())
                for extra in ips[1:]:
                    print(f"  Other address: {qr_payload(extra, self.port, self.hostname)}")
                if self.web is not None:
                    # The browser pad gets its own scannable code: point any
                    # phone camera at it and the page opens directly.
                    first = ips[0]
                    safe = first.replace(":", "-").replace(".", "_")
                    print_qr_block(
                        self.web_url(first),
                        os.getcwd(),
                        f"lemoGamePad-web-qr-{safe}.png",
                        "Browser link",
                    )
                    for extra in ips[1:]:
                        safe = extra.replace(":", "-").replace(".", "_")
                        name = f"lemoGamePad-web-qr-{safe}.png"
                        if save_qr_png(self.web_url(extra), os.path.join(os.getcwd(), name)):
                            print(f"  Browser QR   : {name}")

        if self.video is not None and self.video.error:
            print(f"  !! {self.video.error}")
        if self.web is not None and self.web.error:
            print(f"  !! {self.web.error}")
        print()

    def _log(self, message: str) -> None:
        """Queue a log line; never print on the packet-receive thread."""
        line = f"[{time.strftime('%H:%M:%S')}] {message}"
        try:
            self._log_queue.put_nowait(line)
        except queue.Full:
            pass

    def _drain_logs(self) -> None:
        while True:
            try:
                line = self._log_queue.get_nowait()
            except queue.Empty:
                return
            if self._tty and self._status_live:
                sys.stdout.write("\r\x1b[K")
                self._status_live = False
            print(line)

    def _status_text(self) -> str:
        with self.lock:
            peers = sorted(self.peers.values(), key=lambda p: p.slot)
        if not peers:
            return "  waiting for controller..."

        parts = []
        for peer in peers:
            battery = f"{peer.battery}%" if peer.battery != 255 else "?"
            loss = ""
            if peer.lost:
                loss = f" {peer.lost} lost"
            parts.append(
                f"P{peer.slot + 1} {peer.addr[0]} "
                f"{peer.rate:4.0f} pkt/s{loss} bat {battery}"
            )
        head = f"  {len(peers)}/{self.max_phones} "
        return head + " | ".join(parts)

    def _status_loop(self) -> None:
        """
        Write the live status line from its own thread.

        Windows consoles block writes while the user has selected text
        (QuickEdit mode). With the old code that blocked the UDP receive loop
        itself, so the gamepad froze until the console was un-clicked. Status is
        cosmetic; input never waits for it.
        """
        while self.running:
            try:
                self._drain_logs()
                if time.time() - self._ip_cache_at >= 10.0:
                    self._refresh_offer_addresses()
                self._status()
            except Exception:
                # A detached console can make stdout unusable; keep serving input.
                pass
            time.sleep(self.STATUS_INTERVAL)

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
    # Localized Windows (or a different netsh build) uses translated keys. If
    # none of the expected fields parsed, report "unknown" instead of assuming
    # a healthy rule from the defaults below.
    if not any(k in fields for k in ("enabled", "action", "program", "profiles", "dir")):
        return {}
    program = fields.get("program", "").strip()
    if program.lower() in ("any", "*"):
        program = ""
    return {
        "name": name,
        "port": port,
        "proto": proto,
        "present": True,
        "enabled": fields.get("enabled", "yes").lower().startswith("yes"),
        "allow": fields.get("action", "allow").lower().startswith("allow"),
        "program": program,
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


def _system32() -> str:
    """A system-owned folder to use as the working directory for elevated tools.

    Launching an installer or shell with a user-writable cwd (``%LOCALAPPDATA%``
    or ``%TEMP%``) lets a low-privilege process plant a DLL that the elevated
    process then loads. System32 is ACL-protected, so it is the safe default.
    """
    root = os.environ.get("SystemRoot") or r"C:\Windows"
    path = os.path.join(root, "System32")
    return path if os.path.isdir(path) else root


def _setup_folder() -> str:
    base = os.path.join(
        os.environ.get("LOCALAPPDATA") or tempfile.gettempdir(), "lemoGamePad"
    )
    try:
        os.makedirs(base, exist_ok=True)
        return base
    except OSError:
        # A locked-down LOCALAPPDATA must not stop the server from starting.
        fallback = os.path.join(tempfile.gettempdir(), "lemoGamePad")
        os.makedirs(fallback, exist_ok=True)
        return fallback


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

    # The download lives under %LOCALAPPDATA%\lemoGamePad, which the current
    # user (and any process running as that user) can modify. Re-check the
    # hash immediately before handing the file to the elevated installer so a
    # swap between download and launch is at least detected.
    try:
        if (
            os.path.getsize(setup) != VIGEM_SETUP_SIZE
            or _file_sha256(setup) != VIGEM_SETUP_SHA256
        ):
            raise OSError("installer changed on disk after download")
    except OSError as exc:
        print(f"  !! ViGEmBus installer could not be verified: {exc}")
        print(f"     Install it manually from {VIGEM_PAGE}")
        return

    log = os.path.join(_setup_folder(), "ViGEmBus-install.log")
    code = -1
    for attempt in (1, 2):
        if attempt == 2:
            print("  ViGEmBus     : driver did not register, trying once more ...")
        print("  ViGEmBus     : installing - Windows will ask for administrator permission.")
        code = _run_elevated(setup, f'/qn /norestart /l*v "{log}"', _system32())
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


def _firewall_fix_commands(checks):
    """Return (netsh commands, unknown checks) for [checks].

    ``unknown`` lists the ports whose state netsh could not report (missing
    tool, no permission, localized output). Callers must not treat an unknown
    result as "already open".
    """
    commands = []
    unknown = []
    for proto, port in checks:
        rule = _firewall_rule(port, proto)
        if not rule:
            unknown.append((proto, port))
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
    return commands, unknown


def _ensure_firewall(checks, install: bool) -> None:
    """Open the inbound ports so phones can actually reach this PC."""
    if os.name != "nt":
        return
    try:
        commands, unknown = _firewall_fix_commands(checks)
    except Exception as exc:
        print(f"  Firewall     : could not query Windows Firewall ({exc})")
        return

    listing = " / ".join(f"{proto} {port}" for proto, port in checks)
    if not commands:
        if unknown:
            # Never print OK when netsh gave us nothing to go on.
            print(f"  Firewall     : unknown for {listing} (could not query Windows Firewall)")
            print("     If phones cannot connect, run pc\\allow_firewall.ps1 as Administrator.")
        return
    if not install:
        print(f"  Firewall     : NOT open for {listing} (auto-install disabled).")
        return

    print("  Firewall     : adding inbound rules - Windows will ask for administrator permission.")
    # Run the netsh lines through PowerShell -EncodedCommand. The previous
    # version wrote a .cmd file into %LOCALAPPDATA% and launched it elevated;
    # a local process could replace that file before the UAC prompt was
    # accepted. Encoding the script into the command line removes the
    # writable script, and System32 as the working directory removes the
    # DLL-planting hazard for the elevated process.
    script = "$ErrorActionPreference = 'Stop'\r\n" + "\r\n".join(commands)
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    powershell = os.path.join(_system32(), "WindowsPowerShell", "v1.0", "powershell.exe")
    try:
        code = _run_elevated(
            powershell,
            f"-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand {encoded}",
            _system32(),
        )
    except Exception as exc:
        print(f"  !! Could not start the firewall helper: {exc}")
        print("     Run pc\\allow_firewall.ps1 as Administrator.")
        return
    if code == -2:
        print("  !! Firewall rules were cancelled at the Windows prompt.")
        print("     Run pc\\allow_firewall.ps1 as Administrator.")
        return
    try:
        remaining, unknown = _firewall_fix_commands(checks)
    except Exception:
        remaining, unknown = [], []
    if remaining:
        print("  !! Could not add the firewall rules automatically.")
        print("     Run pc\\allow_firewall.ps1 as Administrator.")
        return
    if unknown:
        print(f"  Firewall     : rules submitted for {listing} (state could not be confirmed).")
        return
    print(f"  Firewall     : OK ({listing} allowed)")


def _python_packages_missing() -> list:
    """Packages needed when running from source (the EXE bundles them)."""
    import importlib.util

    wanted = []
    if os.name == "nt":
        wanted.append(("vgamepad", "vgamepad>=0.2.0"))
    else:
        wanted.append(("evdev", "evdev>=2.0.0"))
    wanted += [
        ("mss", "mss>=10.2.0"),
        ("PIL", "pillow>=13.0.0"),
        ("qrcode", "qrcode>=8.2"),
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
    if getattr(sys, "frozen", False):
        return
    missing = _python_packages_missing()
    if not missing:
        return
    names = ", ".join(package.split(">")[0] for package in missing)
    print(f"  Python deps  : missing {names}")
    if os.name != "nt":
        # Never pip-install behind the user's back on Linux: distributions
        # since PEP 668 refuse it in the system interpreter, and the fix is
        # usually a distro package or a virtualenv.
        print("     Install with: sudo sh pc/linux/install-linux.sh")
        print("     Then start it with: pc/linux/run.sh")
        print("     (or use your distribution's python-evdev / python-pillow packages)")
        return
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


def _uinput_status() -> tuple[bool, str]:
    """(ready, reason) for the Linux virtual-pad backend."""
    try:
        from linux_pad import uinput_status
    except Exception as exc:  # pragma: no cover - broken installation
        return False, f"the Linux backend could not be loaded ({exc})"
    return uinput_status()


def _ensure_uinput() -> None:
    """Explain the one-time Linux setup needed to create a virtual pad."""
    ready, reason = _uinput_status()
    if ready:
        return
    print(f"  uinput       : {reason}")
    print("     One-time setup: sudo sh pc/linux/install-linux.sh")
    print("     Then run: pc/linux/run.sh")
    print("     Log out and back in if the new 'input' group is not active yet.")


def _ensure_dependencies(port: int, video_port, web_port, install: bool) -> None:
    """Bring a fresh PC up to the point where a phone can connect."""
    _ensure_python_packages(install)
    if os.name == "nt":
        _ensure_vigem(install)
    elif sys.platform.startswith("linux"):
        _ensure_uinput()
    checks = [("UDP", port)]
    if video_port:
        checks.append(("TCP", video_port))
    if web_port:
        checks.append(("TCP", web_port))
    _ensure_firewall(checks, install)


def local_ips():
    ips = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
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
        default=8,
        help="how many phones can share this PC at once, 1-8 (default 8)",
    )
    ap.add_argument("--no-rumble", action="store_true", help="do not forward rumble to the phone")
    ap.add_argument("--no-video", action="store_true", help="disable the PC screen stream")
    ap.add_argument(
        "--video-port",
        type=int,
        default=None,
        help="screen stream TCP port (default: control port + 1)",
    )
    ap.add_argument(
        "--no-web",
        action="store_true",
        help="disable the browser gamepad (local web page)",
    )
    ap.add_argument(
        "--web-port",
        type=int,
        default=None,
        help="browser gamepad HTTP/WebSocket port (default: control port + 2)",
    )
    ap.add_argument(
        "--web-key",
        default="",
        help="fixed browser pairing key (default: random for each run)",
    )
    ap.add_argument("--video-fps", type=int, default=20, help="screen stream frame rate (default 20)")
    ap.add_argument("--video-width", type=int, default=800, help="stream width in pixels (default 800)")
    ap.add_argument("--video-quality", type=int, default=50, help="JPEG quality 20-90 (default 50)")
    ap.add_argument("--no-qr", action="store_true", help="do not print the pairing QR code")
    ap.add_argument(
        "--no-install-deps",
        action="store_true",
        help="do not auto-install Python packages / ViGEmBus or open the firewall (Windows)",
    )
    ap.add_argument("--list-ip", action="store_true", help="print local IPs and exit")
    args = ap.parse_args(argv)

    if args.list_ip:
        for ip in local_ips():
            print(ip)
        return 0

    if not 1 <= args.port <= 65535:
        ap.error("--port must be between 1 and 65535")
    if args.video_port is not None and not 1 <= args.video_port <= 65535:
        ap.error("--video-port must be between 1 and 65535")
    video_port = None if args.no_video else (args.video_port or args.port + 1)
    if video_port is not None and video_port > 65535:
        ap.error(
            "the default video port would be 65536; "
            "use a lower --port or pass --video-port"
        )
    web_port = None
    if not args.no_web:
        web_port = args.web_port or args.port + 2
        if not 1 <= web_port <= 65535:
            ap.error("--web-port must be between 1 and 65535")
        if web_port == video_port:
            ap.error("--web-port must differ from the screen stream port")
        if web_port == args.port:
            # UDP and TCP can share a number, but using different ports keeps
            # firewall rules and logs unambiguous.
            ap.error("--web-port must differ from the control port")

    if not sys.stdout.isatty():
        try:
            sys.stdout.reconfigure(line_buffering=True)
        except Exception:
            pass

    # One-time setup: Python packages (source only), the platform's virtual
    # pad driver (ViGEmBus on Windows, uinput on Linux) and the firewall.
    _ensure_dependencies(
        args.port,
        video_port,
        web_port,
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
            web_port=web_port,
            web_enabled=not args.no_web,
            web_key=args.web_key,
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
        # Unplug the virtual pads so the OS drops the device nodes instead of
        # leaving "Unknown" ghost controllers behind.
        server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
