#!/usr/bin/env python3
"""
Linux virtual gamepad backend for lemoGamePad ultimate.

Windows uses ViGEmBus (through the ``vgamepad`` package). Linux has the same
idea built into the kernel: ``uinput`` lets a normal program create an input
device. This module creates a standard evdev gamepad, so Steam, Proton, SDL2,
RetroArch and native Linux games see a real controller with analog sticks,
analog triggers and force-feedback rumble.

The device uses the USB ids of an Xbox 360 pad (or a DualShock 4 with
``--pad ds4``) so SDL's built-in controller database maps it without any
per-game configuration. It is still a plain virtual evdev device: no kernel
module of our own, no root daemon, and no code running in the game.

Access to ``/dev/uinput`` is required. On most distributions that means
loading the ``uinput`` module and adding your user to the ``input`` group;
see ``pc/linux/install-uinput.sh``.
"""

from __future__ import annotations

import ctypes
import os
import select
import struct
import threading
import time
from typing import Callable, Optional

try:  # The module must stay importable so the server can explain the fix.
    import evdev
    from evdev import AbsInfo, UInput, ecodes as e, ff
except Exception as exc:  # pragma: no cover - depends on the machine
    evdev = None
    AbsInfo = UInput = e = ff = None  # type: ignore[assignment]
    _IMPORT_ERROR: Optional[Exception] = exc
else:
    _IMPORT_ERROR = None


# USB identities. SDL / Steam match controllers by (bus, vendor, product,
# version), so borrowing the ids of a well-known pad gives us a ready-made
# mapping. The device name stays ours.
_X360 = dict(
    name="lemoGamePad ultimate (Xbox 360)",
    bustype=0x03,  # BUS_USB
    vendor=0x045E,  # Microsoft
    product=0x028E,  # Xbox 360 Controller
    version=0x0114,
)
_DS4 = dict(
    name="lemoGamePad ultimate (DualShock 4)",
    bustype=0x03,
    vendor=0x054C,  # Sony
    product=0x09CC,  # DualShock 4 (v2)
    version=0x8111,
)


# struct input_event { struct timeval time; __u16 type; __u16 code; __s32 value; }
# The timeval is two longs, so the layout differs between 32- and 64-bit hosts.
_INPUT_EVENT = struct.Struct("<qqHHi" if struct.calcsize("P") == 8 else "<llHHi")


def _code(*names: str) -> Optional[int]:
    """First ecodes constant that exists on this kernel/evdev version."""
    if e is None:
        return None
    for name in names:
        value = getattr(e, name, None)
        if value is not None:
            return value
    return None


def uinput_status() -> tuple[bool, str]:
    """Return (ready, human-readable reason) for the banner and errors."""
    if evdev is None:
        return False, f"python-evdev is not installed ({_IMPORT_ERROR})"
    node = "/dev/uinput"
    if not os.path.exists(node):
        return False, "uinput module is not loaded (/dev/uinput is missing)"
    if not os.access(node, os.W_OK):
        return False, "no write access to /dev/uinput (add your user to the input group)"
    return True, "OK (virtual gamepad via /dev/uinput)"


def _button_map() -> dict[str, int]:
    """PS3 button name -> Linux BTN_* code, matching the xpad layout."""
    wanted = {
        "cross": ("BTN_SOUTH", "BTN_A"),
        "circle": ("BTN_EAST", "BTN_B"),
        # evdev naming trap: BTN_X == BTN_NORTH (0x133) and BTN_Y == BTN_WEST
        # (0x134). xpad reports the Xbox X button (left, = PS square) as BTN_X
        # and Y (top, = PS triangle) as BTN_Y, so use the X/Y names explicitly.
        "square": ("BTN_X", "BTN_NORTH"),
        "triangle": ("BTN_Y", "BTN_WEST"),
        "l1": ("BTN_TL",),
        "r1": ("BTN_TR",),
        "select": ("BTN_SELECT",),
        "start": ("BTN_START",),
        "ps": ("BTN_MODE",),
        "l3": ("BTN_THUMBL",),
        "r3": ("BTN_THUMBR",),
    }
    out: dict[str, int] = {}
    for name, candidates in wanted.items():
        code = _code(*candidates)
        if code is not None:
            out[name] = code
    return out


class LinuxVirtualPad:
    """
    One virtual gamepad backed by /dev/uinput.

    The public surface matches ``WindowsVirtualPad``: ``ok``, ``error``,
    ``rumble_error``, ``apply(InputState)`` and ``close()``.
    """

    def __init__(self, kind: str, on_rumble: Optional[Callable[[int, int], None]] = None):
        self.kind = kind
        self.ok = False
        self.error = ""
        self.rumble_error = ""
        self.pad = None
        self.on_rumble = on_rumble
        self._last = None
        self._lock = threading.Lock()
        self._running = False
        self._ff_thread: Optional[threading.Thread] = None
        # (event type, code) -> last written value, so each report only carries
        # the controls that actually moved.
        self._written: dict[tuple[int, int], int] = {}
        self._buttons = _button_map()

        # Force feedback bookkeeping. effect id -> (weak, strong, playing, ends_at)
        self._effects: dict[int, tuple[int, int, bool, Optional[float]]] = {}
        self._gain = 0xFFFF
        self._rumble_out = (0, 0)

        if evdev is None:
            self.error = (
                "python-evdev is not installed. "
                "Run: python3 -m pip install -r pc/requirements.txt"
            )
            return

        ready, reason = uinput_status()
        if not ready:
            self.error = (
                f"{reason}. See pc/linux/install-uinput.sh for the one-time setup."
            )
            return

        identity = _DS4 if kind == "ds4" else _X360
        try:
            self.ui = UInput(
                self._capabilities(),
                phys="lemogamepad/uinput",
                max_effects=16,
                **identity,
            )
        except Exception as exc:  # pragma: no cover - depends on the machine
            self.error = f"could not create the uinput gamepad ({exc})"
            return

        self.pad = self.ui
        self.ok = True
        self._running = True

        device = getattr(self.ui, "device", None)
        if device is None:
            # Input still works; only rumble needs to read events back.
            self.rumble_error = (
                "cannot read the created /dev/input/event* node "
                "(needed for rumble)"
            )
        else:
            self._ff_thread = threading.Thread(
                target=self._ff_loop,
                name="vpad-linux-ff",
                daemon=True,
            )
            self._ff_thread.start()

    # ------------------------------------------------------------- capabilities
    @staticmethod
    def _capabilities() -> dict:
        buttons = sorted(set(_button_map().values()))
        axes = [
            (e.ABS_X, AbsInfo(0, -32768, 32767, 16, 128, 0)),
            (e.ABS_Y, AbsInfo(0, -32768, 32767, 16, 128, 0)),
            (e.ABS_RX, AbsInfo(0, -32768, 32767, 16, 128, 0)),
            (e.ABS_RY, AbsInfo(0, -32768, 32767, 16, 128, 0)),
            (e.ABS_Z, AbsInfo(0, 0, 255, 0, 0, 0)),   # L2
            (e.ABS_RZ, AbsInfo(0, 0, 255, 0, 0, 0)),  # R2
            (e.ABS_HAT0X, AbsInfo(0, -1, 1, 0, 0, 0)),
            (e.ABS_HAT0Y, AbsInfo(0, -1, 1, 0, 0, 0)),
        ]
        return {
            e.EV_KEY: buttons,
            e.EV_ABS: axes,
            e.EV_FF: [e.FF_RUMBLE, e.FF_GAIN],
            e.EV_SYN: [],
        }

    # ------------------------------------------------------------------- input
    def apply(self, state) -> None:
        self._last = state
        if not self.ok:
            return
        with self._lock:
            ui = self.pad
            if ui is None:
                return
            try:
                self._write(ui, state)
            except Exception as exc:  # pragma: no cover - device unplugged
                self.error = f"uinput write failed ({exc})"
                self.ok = False

    def _write(self, ui, s) -> None:
        """Write only the controls that changed, then one SYN report."""
        changed = False

        def put(event_type: int, code: int, value: int) -> None:
            nonlocal changed
            key = (event_type, code)
            if self._written.get(key) == value:
                return
            ui.write(event_type, code, value)
            self._written[key] = value
            changed = True

        for name, code in self._buttons.items():
            put(e.EV_KEY, code, 1 if s.pressed(name) else 0)

        # The analog value wins, but a quick tap still reaches full scale even
        # if the app did not have time to ramp l2/r2.
        l2 = max(int(s.l2), 255 if s.pressed("l2") else 0)
        r2 = max(int(s.r2), 255 if s.pressed("r2") else 0)
        put(e.EV_ABS, e.ABS_Z, _clamp(l2, 0, 255))
        put(e.EV_ABS, e.ABS_RZ, _clamp(r2, 0, 255))

        # The app follows the XInput convention (positive = up/right), while
        # evdev's ABS_Y/ABS_RY are positive *down*, so the vertical axes flip.
        put(e.EV_ABS, e.ABS_X, _clamp(s.lx, -32768, 32767))
        put(e.EV_ABS, e.ABS_Y, _clamp(-int(s.ly), -32768, 32767))
        put(e.EV_ABS, e.ABS_RX, _clamp(s.rx, -32768, 32767))
        put(e.EV_ABS, e.ABS_RY, _clamp(-int(s.ry), -32768, 32767))

        hat_x = (1 if s.pressed("right") else 0) - (1 if s.pressed("left") else 0)
        hat_y = (1 if s.pressed("down") else 0) - (1 if s.pressed("up") else 0)
        put(e.EV_ABS, e.ABS_HAT0X, hat_x)
        put(e.EV_ABS, e.ABS_HAT0Y, hat_y)

        if changed:
            ui.syn()

    # ----------------------------------------------------------------- rumble
    def _ff_loop(self) -> None:
        """
        Answer force-feedback requests until the pad is closed.

        Two descriptors matter:

        * the ``/dev/uinput`` fd receives ``EV_UINPUT`` upload/erase requests
          (recent kernels queue them there; older ones queue them on the event
          node, which is why that fd is watched too);
        * the created ``/dev/input/event*`` node carries ``EV_FF`` play/stop
          and ``FF_GAIN`` events written by the game.
        """
        device = getattr(self.ui, "device", None)
        uinput_fd = getattr(self.ui, "fd", None)
        watched = []
        if uinput_fd is not None:
            watched.append(uinput_fd)
        if device is not None:
            watched.append(device.fd)
        if not watched:
            return

        while self._running:
            try:
                ready, _, _ = select.select(watched, [], [], 0.1)
            except (OSError, ValueError):
                break
            if not ready:
                self._expire_effects()
                continue
            try:
                if uinput_fd is not None and uinput_fd in ready:
                    self._read_uinput_requests()
                if device is not None and device.fd in ready:
                    self._read_device_events(device)
            except (OSError, ValueError):
                break
            self._expire_effects()

    def _read_uinput_requests(self) -> None:
        """Parse raw ``input_event`` records from the uinput fd."""
        try:
            data = os.read(self.ui.fd, _INPUT_EVENT.size * 32)
        except BlockingIOError:
            return
        except OSError:
            raise
        for offset in range(0, len(data) - _INPUT_EVENT.size + 1, _INPUT_EVENT.size):
            _, _, event_type, code, value = _INPUT_EVENT.unpack_from(data, offset)
            if event_type == e.EV_UINPUT:
                try:
                    self._handle_uinput(event_type, code, value)
                except Exception:
                    # A malformed request must never kill rumble for the session.
                    pass

    def _read_device_events(self, device) -> None:
        try:
            events = list(device.read())
        except BlockingIOError:
            return
        for event in events:
            try:
                if event.type == e.EV_UINPUT:
                    self._handle_uinput(event.type, event.code, event.value)
                elif event.type == e.EV_FF:
                    self._handle_ff(event)
            except Exception:
                pass

    def _handle_uinput(self, event_type: int, code: int, value: int) -> None:
        if code == e.UI_FF_UPLOAD:
            upload = self._begin_upload(value)
            try:
                effect = upload.effect
                if effect.type == e.FF_RUMBLE:
                    rumble = effect.u.ff_rumble_effect
                    length = effect.ff_replay.length
                    self._effects[int(effect.id)] = (
                        int(rumble.weak_magnitude),
                        int(rumble.strong_magnitude),
                        False,
                        length / 1000.0 if length else None,
                    )
                upload.retval = 0
            finally:
                self.ui.end_upload(upload)
        elif code == e.UI_FF_ERASE:
            erase = self._begin_erase(value)
            try:
                self._effects.pop(int(erase.effect_id), None)
                erase.retval = 0
            finally:
                self.ui.end_erase(erase)
                self._publish_rumble()

    # python-evdev 2.0.0's begin_upload()/begin_erase() assign a stray
    # ``effect_id`` attribute instead of ``request_id``, so every ioctl uses
    # request 0. That only works for the first effect. Do the ioctl ourselves
    # and fall back to the library helpers on versions without the raw dll.
    def _begin_upload(self, request_id: int):
        dll = getattr(self.ui, "dll", None)
        if dll is None or ff is None:
            return self.ui.begin_upload(request_id)
        upload = ff.UInputUpload()
        upload.request_id = int(request_id)
        if dll._uinput_begin_upload(self.ui.fd, ctypes.byref(upload)):
            raise OSError(ctypes.get_errno(), "UI_BEGIN_FF_UPLOAD failed")
        return upload

    def _begin_erase(self, request_id: int):
        dll = getattr(self.ui, "dll", None)
        if dll is None or ff is None:
            return self.ui.begin_erase(request_id)
        erase = ff.UInputErase()
        erase.request_id = int(request_id)
        if dll._uinput_begin_erase(self.ui.fd, ctypes.byref(erase)):
            raise OSError(ctypes.get_errno(), "UI_BEGIN_FF_ERASE failed")
        return erase

    def _handle_ff(self, event) -> None:
        if event.code == e.FF_GAIN:
            self._gain = _clamp(int(event.value), 0, 0xFFFF)
            self._publish_rumble()
            return

        current = self._effects.get(int(event.code))
        if current is None:
            return
        weak, strong, _, length = current
        playing = int(event.value) != 0
        ends_at = time.monotonic() + length if (playing and length) else None
        self._effects[int(event.code)] = (weak, strong, playing, ends_at)
        self._publish_rumble()

    def _expire_effects(self) -> None:
        now = time.monotonic()
        changed = False
        for effect_id, (weak, strong, playing, ends_at) in list(self._effects.items()):
            if playing and ends_at is not None and now >= ends_at:
                self._effects[effect_id] = (weak, strong, False, None)
                changed = True
        if changed:
            self._publish_rumble()

    def _publish_rumble(self) -> None:
        weak = strong = 0
        for w, s, playing, _ in self._effects.values():
            if playing:
                weak = max(weak, w)
                strong = max(strong, s)
        if self._gain < 0xFFFF:
            scale = self._gain / 0xFFFF
            weak = int(weak * scale)
            strong = int(strong * scale)
        # 0..65535 (Linux) -> 0..255 (the phone protocol).
        out = (weak * 255 // 0xFFFF, strong * 255 // 0xFFFF)
        if out == self._rumble_out:
            return
        self._rumble_out = out
        if self.on_rumble:
            try:
                self.on_rumble(out[0], out[1])
            except Exception:
                pass

    # -------------------------------------------------------------------- close
    def close(self) -> None:
        with self._lock:
            self._running = False
            ui, self.pad = self.pad, None
            self.ok = False
        if ui is not None:
            try:
                ui.close()
            except Exception:
                pass
        thread = self._ff_thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=0.5)
        self._ff_thread = None
        self._written.clear()
        self._effects.clear()
        self._rumble_out = (0, 0)


def _clamp(value: int, low: int, high: int) -> int:
    return max(low, min(int(value), high))
