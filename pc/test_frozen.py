#!/usr/bin/env python3
"""Smoke test that talks to a *running* lemoGamePad-server.exe.

    python test_frozen.py --port 9891

Checks the multi-phone slot limit and the multi-viewer screen stream through
the real frozen binary, not the Python module.
"""

from __future__ import annotations

import argparse
import io
import socket
import struct
import sys
import time

from PIL import Image

MAGIC = b"VPAD"
HEADER = struct.Struct("<4sBBHI")
INPUT = struct.Struct("<4sBBHIIhhhhBBBB")
VERSION = 1
MSG_INPUT = 1
MSG_DISCOVER = 2
MSG_OFFER = 3


def wait_for(predicate, timeout=4.0, message="condition not met"):
    end = time.time() + timeout
    while time.time() < end:
        if predicate():
            return True
        time.sleep(0.05)
    raise AssertionError(message)


def phone(port, token=0):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(1.5)
    sock.sendto(HEADER.pack(MAGIC, VERSION, MSG_DISCOVER, 0, token), ("127.0.0.1", port))
    data, _ = sock.recvfrom(2048)
    magic, _v, mtype, _s, offered = HEADER.unpack_from(data)
    assert magic == MAGIC and mtype == MSG_OFFER, (magic, mtype)
    return sock, offered


def send(sock, token, port, seq, buttons=0):
    sock.sendto(
        INPUT.pack(MAGIC, VERSION, MSG_INPUT, seq & 0xFFFF, token,
                   buttons, 0, 0, 0, 0, 0, 0, 88, 0),
        ("127.0.0.1", port),
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9891)
    ap.add_argument("--slots", type=int, default=2,
                    help="max-phones value the server was started with")
    args = ap.parse_args()
    video_port = args.port + 1

    phones = []
    for _ in range(args.slots + 1):
        sock, token = phone(args.port)
        phones.append((sock, token))
    print("  tokens: " + " ".join(f"0x{token:08X}" for _, token in phones))

    for seq in range(6):
        for index, (sock, token) in enumerate(phones):
            send(sock, token, args.port, seq, 1 << (14 - index % 2))
        time.sleep(0.05)
    print(f"  sent input from {len(phones)} phones to a {args.slots}-slot server")

    viewers = []
    try:
        for _ in range(args.slots):
            viewers.append(socket.create_connection(("127.0.0.1", video_port), timeout=8))
        frames = 0
        for viewer in viewers:
            stream = viewer.makefile("rb")
            header = stream.read(4)
            size = struct.unpack(">I", header)[0]
            body = stream.read(size)
            assert len(body) == size
            Image.open(io.BytesIO(body)).load()
            frames += 1
            stream.close()
        print(f"  multi-viewer ok: {frames} frames over one frozen server")

        extra = socket.create_connection(("127.0.0.1", video_port), timeout=8)
        extra.settimeout(4)
        try:
            refused = extra.recv(4) == b""
        except socket.timeout:
            refused = False
        finally:
            extra.close()
        if not refused:
            raise AssertionError(f"viewer {args.slots + 1} was not refused")
        print(f"  viewer limit ok: viewer {args.slots + 1} refused")
    finally:
        for viewer in viewers:
            try:
                viewer.close()
            except OSError:
                pass
        for sock, _token in phones:
            sock.close()
    print("frozen exe checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
