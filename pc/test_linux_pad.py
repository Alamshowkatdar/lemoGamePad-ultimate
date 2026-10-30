#!/usr/bin/env python3
"""Check the Linux virtual gamepad without a phone or a game.

    python3 test_linux_pad.py

Creates a real /dev/uinput pad, reads its events back, then runs a small child
process that uploads and plays a rumble effect. The child is necessary: a real
game is a separate process too, and python-evdev's upload ioctl holds the GIL,
so an in-process upload would block the server's rumble thread.

Exit code 0 means every check passed; 2 means the machine is not set up for
uinput (the server still runs, just without a virtual controller).
"""
import subprocess
import sys
import time

if not sys.platform.startswith("linux"):
    print("Linux only - nothing to do")
    raise SystemExit(0)

import evdev
from evdev import InputDevice, ecodes as e

import vpad_server as srv
from linux_pad import LinuxVirtualPad, uinput_status
from vpad_server import InputState

# Runs in a separate process, exactly like a game using SDL would.
RUMBLE_CLIENT = r"""
import sys, time
import evdev
from evdev import InputDevice, ecodes as e

reader = InputDevice(sys.argv[1])
effect = evdev.ff.Effect(
    type=e.FF_RUMBLE, id=-1, direction=0,
    ff_trigger=evdev.ff.Trigger(0, 0),
    ff_replay=evdev.ff.Replay(600, 0),
    u=evdev.ff.EffectType(
        ff_rumble_effect=evdev.ff.Rumble(
            strong_magnitude=0xFFFF, weak_magnitude=0x8000
        )
    ),
)
eid = reader.upload_effect(effect)
reader.write(e.EV_FF, eid, 1)
reader.write(e.EV_SYN, 0, 0)
time.sleep(0.3)
reader.write(e.EV_FF, eid, 0)
reader.write(e.EV_SYN, 0, 0)
time.sleep(0.1)
reader.erase_effect(eid)
reader.close()
"""


def wait_for(predicate, timeout=2.0, message="timed out"):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError(message)


def read_report(reader, timeout=2.0):
    """Collect events up to and including the next SYN_REPORT."""
    events = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        event = reader.read_one()
        if event is None:
            time.sleep(0.005)
            continue
        events.append((event.type, event.code, event.value))
        if event.type == e.EV_SYN and event.code == e.SYN_REPORT:
            return events
    raise AssertionError(f"no complete report, got {events}")


def main() -> int:
    ready, reason = uinput_status()
    if not ready:
        print(f"SKIP: {reason}")
        return 2

    assert srv.create_virtual_pad("x360").__class__ is LinuxVirtualPad

    rumble = []
    pad = LinuxVirtualPad("x360", on_rumble=lambda small, large: rumble.append((small, large)))
    if not pad.ok:
        print("FAIL:", pad.error)
        return 1

    try:
        device = pad.ui.device
        assert device is not None, "the created event node could not be opened"
        print(f"  device ok: {device.path} - {device.name}")

        caps = device.capabilities()
        assert e.EV_KEY in caps and e.EV_ABS in caps and e.EV_FF in caps
        buttons = caps[e.EV_KEY]
        axes = [code for code, _ in caps[e.EV_ABS]]
        for code in (e.BTN_SOUTH, e.BTN_EAST, e.BTN_TL, e.BTN_START, e.BTN_MODE):
            assert code in buttons, f"missing button {code}"
        for code in (e.ABS_X, e.ABS_Y, e.ABS_RX, e.ABS_RY, e.ABS_Z, e.ABS_RZ):
            assert code in axes, f"missing axis {code}"
        assert e.FF_RUMBLE in caps[e.EV_FF], "rumble is not advertised"
        print("  capabilities ok: 11 buttons, 8 axes, FF_RUMBLE")

        reader = InputDevice(device.path)
        try:
            state = InputState(
                buttons=(1 << srv.BUTTON_BITS["cross"]) | (1 << srv.BUTTON_BITS["r1"]),
                lx=12345,
                ly=-23456,
                rx=-32768,
                ry=32767,
                l2=200,
            )
            pad.apply(state)
            report = read_report(reader)
            assert (e.EV_KEY, e.BTN_SOUTH, 1) in report
            assert (e.EV_KEY, e.BTN_TR, 1) in report
            assert (e.EV_ABS, e.ABS_X, 12345) in report
            # App: positive ly = up. evdev ABS_Y: positive = down, hence the flip.
            assert (e.EV_ABS, e.ABS_Y, 23456) in report
            assert (e.EV_ABS, e.ABS_RX, -32768) in report
            assert (e.EV_ABS, e.ABS_RY, -32767) in report
            assert (e.EV_ABS, e.ABS_Z, 200) in report
            print("  input ok: buttons and analog axes reach the kernel")

            pad.apply(InputState())
            report = read_report(reader)
            assert (e.EV_KEY, e.BTN_SOUTH, 0) in report
            assert (e.EV_ABS, e.ABS_X, 0) in report
            assert (e.EV_ABS, e.ABS_Z, 0) in report
            print("  neutral ok: everything returns to zero")
        finally:
            reader.close()

        proc = subprocess.run(
            [sys.executable, "-c", RUMBLE_CLIENT, device.path],
            capture_output=True,
            text=True,
            timeout=20,
        )
        if proc.returncode != 0:
            print("FAIL: rumble client crashed")
            print(proc.stderr[-800:])
            return 1
        wait_for(lambda: any(small or large for small, large in rumble), message="no rumble callback")
        assert (127, 255) in rumble, f"expected (127, 255), got {rumble}"
        wait_for(lambda: rumble[-1] == (0, 0), message="rumble never stopped")
        print(f"  rumble ok: {rumble}")
    finally:
        pad.close()

    assert not pad.ok
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
