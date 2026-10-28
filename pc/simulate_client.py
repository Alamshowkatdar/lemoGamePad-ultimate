#!/usr/bin/env python3
"""Send fake input to lemoGamePad-server - useful to test without a phone.

    python simulate_client.py --host 127.0.0.1
"""
import argparse
import math
import socket
import struct
import time

MAGIC = b"VPAD"
HEADER = struct.Struct("<4sBBHI")
INPUT = struct.Struct("<4sBBHIIhhhhBBBB")

CROSS, CIRCLE = 14, 13
L1, R1, START = 10, 11, 3


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9876)
    ap.add_argument("--seconds", type=float, default=6.0)
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    token = 0xCAFE1234
    seq = 0
    start = time.time()
    print(f"sending fake controller input to {args.host}:{args.port} for {args.seconds}s")

    while time.time() - start < args.seconds:
        t = time.time() - start
        lx = int(math.sin(t * 2) * 30000)
        ly = int(math.cos(t * 2) * 30000)
        rx = int(math.sin(t * 1.3) * 20000)
        l2 = int((math.sin(t * 3) * 0.5 + 0.5) * 255)
        buttons = 0
        if int(t * 2) % 2 == 0:
            buttons |= 1 << CROSS
        if int(t * 1.5) % 2 == 0:
            buttons |= 1 << R1
        if 1.0 < t < 2.0:
            buttons |= 1 << START
        packet = INPUT.pack(
            MAGIC, 1, 1, seq & 0xFFFF, token, buttons,
            lx, ly, rx, 0, l2, 0, 77, 0,
        )
        sock.sendto(packet, (args.host, args.port))
        seq += 1
        time.sleep(0.01)

    # neutral
    for _ in range(5):
        sock.sendto(INPUT.pack(MAGIC, 1, 1, seq & 0xFFFF, token, 0, 0, 0, 0, 0, 0, 0, 77, 0),
                    (args.host, args.port))
        time.sleep(0.02)
    print("done")


if __name__ == "__main__":
    main()
