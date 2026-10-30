#!/usr/bin/env python3
"""Check that rumble really reaches the phone, without a game.

Starts a server on a spare port, pretends to be the phone, then asks Windows
XInput for vibration exactly like a game would. If a type-7 packet comes back
with non-zero motors, the whole chain works.

    python test_rumble.py

Close lemoGamePad-server.exe first (a second virtual pad + port 9876 would clash).
Runs for a few seconds; any real controller on the PC may buzz briefly.

This test is Windows-only (it uses XInput). On Linux run test_linux_pad.py,
which exercises the same rumble path through /dev/uinput.
"""
import argparse
import ctypes
import os
import socket
import struct
import sys
import threading
import time

import vpad_server as vs

HEADER = struct.Struct("<4sBBHI")
INPUT = struct.Struct("<4sBBHIIhhhhBBBB")
MAGIC = b"VPAD"
TOKEN = 0x5EED1234


class Vibration(ctypes.Structure):
    _fields_ = [("left", ctypes.c_ushort), ("right", ctypes.c_ushort)]


def set_vibration(left, right):
    dll = ctypes.windll.xinput1_4
    vib = Vibration(left, right)
    return [i for i in range(4) if dll.XInputSetState(i, ctypes.byref(vib)) == 0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9879)
    ap.add_argument("--pad", choices=("x360", "ds4"), default="x360")
    ap.add_argument("--seconds", type=float, default=4.0)
    args = ap.parse_args()

    if os.name != "nt":
        print("This test drives the pad through Windows XInput.")
        print("On Linux use: python3 test_linux_pad.py")
        return 0

    if args.pad != "x360":
        print("This test drives the pad through XInput, so use --pad x360.")
        return 2

    server = vs.Server(args.port, "x360", video_enabled=False, qr_enabled=False)
    pad = server.slots[0].pad
    if not pad.ok:
        print("FAIL: virtual pad unavailable -", pad.error)
        return 1
    if pad.rumble_error:
        print("FAIL: rumble callback not registered -", pad.rumble_error)
        return 1

    threading.Thread(target=server.run, daemon=True).start()
    time.sleep(0.3)

    phone = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    phone.bind(("127.0.0.1", 0))
    phone.settimeout(0.15)

    # Pretend to be the app so the server has a peer to send rumble to.
    seq = 0
    stop = threading.Event()

    def send_input():
        nonlocal seq
        while not stop.is_set():
            phone.sendto(INPUT.pack(MAGIC, 1, 1, seq & 0xFFFF, TOKEN, 0, 0, 0, 0, 0, 0, 0, 80, 0),
                         ("127.0.0.1", args.port))
            seq += 1
            time.sleep(0.01)

    threading.Thread(target=send_input, daemon=True).start()
    deadline = time.time() + 2.0
    while TOKEN not in server.peers and time.time() < deadline:
        time.sleep(0.02)
    if TOKEN not in server.peers:
        stop.set()
        print("FAIL: the fake phone never registered")
        return 1

    print("virtual pad is XInput index %d (after other pads)" % pad.pad.get_index())
    hit = set_vibration(40000, 20000)
    print("XInputSetState accepted on index(es):", hit or "none - no controller?")
    if not hit:
        stop.set()
        server.shutdown()
        return 1

    seen = []
    deadline = time.time() + args.seconds
    while time.time() < deadline:
        try:
            pkt = phone.recvfrom(64)[0]
        except socket.timeout:
            continue
        if len(pkt) >= HEADER.size + 2 and pkt[:4] == MAGIC:
            _, _, mtype, _, token = HEADER.unpack_from(pkt)
            if mtype == 7 and token == TOKEN:
                seen.append((pkt[HEADER.size], pkt[HEADER.size + 1]))

    set_vibration(0, 0)
    stop.set()
    server.shutdown()
    print("rumble packets:", seen if seen else "none")
    ok = any(small or large for small, large in seen)
    print("RESULT:", "PASS - rumble reaches the phone layer" if ok else "FAIL - no non-zero rumble")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
