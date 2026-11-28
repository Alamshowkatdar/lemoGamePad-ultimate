#!/usr/bin/env python3
"""Golden vectors for the wire protocol and Android layout snapshots.

    python test_protocol.py

The INPUT packet is 28 bytes plus one optional trailing want_slot byte. These
vectors pin every offset so a field cannot be added, removed or reordered
without a deliberate test change. The real Python server parser consumes the
same bytes. No phone, virtual pad driver or display is needed.
"""

from __future__ import annotations

import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import vpad_server as srv  # noqa: E402

HEADER = struct.Struct("<4sBBHI")
INPUT = struct.Struct("<4sBBHIIhhhhBBBB")

# VPAD | version 1 | INPUT | seq 0x0102 | token 0xA1B2C3D4
# buttons 0x00010400 | (-32768, 1234, -1, 32767)
# l2 200 | r2 77 | battery 91 | pad_hint 1 | want_slot 3
INPUT_GOLDEN = bytes.fromhex(
    "5650414401010201D4C3B2A1"  #  0..11 header
    "00040100"                # 12..15 buttons
    "0080D204FFFFFF7F"        # 16..23 four sticks
    "C84D5B01"                # 24..27 triggers, battery, pad hint
    "03"                      #     28 want_slot
)

# Snapshots written by 2.2.x carry __meta. Added buttons store their function
# mask as the seventh field; older codes stop after that field.
LAYOUT_GOLDEN = (
    "ls:2650,7500,1000,0,0;l1:1150,2475,1000,0,1,4;"
    "m1:5000,6200,1000,0,0,2,81920,4,-1,1050,620;__meta:2,0,0"
)
LAYOUT_SEVEN_FIELD_GOLDEN = "m1:5000,6200,1000,0,0,2,1024;__meta:2,0,0"
LAYOUT_LEGACY_GOLDEN = "ls:2650,7500,1000,0;l1:1150,2475,1000,0,0,3;m1:4000,6000,1000,0,0,0,65536"


class RecordingPad:
    """Stands in for the virtual pad so no driver is touched."""

    def __init__(self):
        self.ok = True
        self.error = ""
        self.rumble_error = ""
        self.applied: list[srv.InputState] = []

    def apply(self, state: srv.InputState) -> None:
        self.applied.append(state)

    def close(self) -> None:
        pass


def free_udp_port() -> int:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def parse_snapshot(data: str) -> dict[str, dict]:
    """Minimal mirror of GamepadView.applyLayoutData's field indexing."""
    parsed: dict[str, dict] = {}
    for chunk in data.split(";"):
        key, sep, values = chunk.partition(":")
        if not sep:
            continue
        fields = values.split(",")
        if len(fields) < 3:
            continue

        def number(index: int, default: int) -> int:
            raw = fields[index] if index < len(fields) else ""
            return int(raw) if raw.lstrip("-").isdigit() else default

        parsed[key] = {
            "fx": number(0, 0),
            "fy": number(1, 0),
            "scale": number(2, 1000),
            "hidden": fields[3] == "1" if len(fields) > 3 else False,
            "locked": fields[4] == "1" if len(fields) > 4 else False,
            "shape": number(5, 0),
            "bits": number(6, 0),
            "template": number(7, 4),
            "face": number(8, -1),
            "bw": number(9, 1050),
            "bh": number(10, 620),
        }
    return parsed


def test_packet_layout() -> None:
    assert srv.MAGIC == b"VPAD"
    assert srv.VERSION == 1
    assert HEADER.format == "<4sBBHI" and HEADER.size == 12
    assert INPUT.format == "<4sBBHIIhhhhBBBB" and INPUT.size == 28
    assert len(INPUT_GOLDEN) == 29
    assert INPUT_GOLDEN == INPUT.pack(
        srv.MAGIC, srv.VERSION, srv.MSG_INPUT, 0x0102, 0xA1B2C3D4,
        0x00010400, -32768, 1234, -1, 32767, 200, 77, 91, 1,
    ) + b"\x03"
    assert INPUT_GOLDEN[0:4] == b"VPAD"
    assert INPUT_GOLDEN[4] == 1 and INPUT_GOLDEN[5] == srv.MSG_INPUT
    assert struct.unpack_from("<H", INPUT_GOLDEN, 6)[0] == 0x0102
    assert struct.unpack_from("<I", INPUT_GOLDEN, 8)[0] == 0xA1B2C3D4
    assert struct.unpack_from("<I", INPUT_GOLDEN, 12)[0] == 0x00010400
    assert struct.unpack_from("<hhhh", INPUT_GOLDEN, 16) == (-32768, 1234, -1, 32767)
    assert list(INPUT_GOLDEN[24:28]) == [200, 77, 91, 1]
    assert INPUT_GOLDEN[28] == 3
    print("  INPUT golden bytes and offsets ok")


def test_server_parser() -> None:
    port = free_udp_port()
    server = srv.Server(port, "x360", video_enabled=False, qr_enabled=False, max_phones=4)
    server.running = False
    pads: list[RecordingPad] = []
    try:
        for slot in server.slots:
            if slot.pad is not None:
                slot.pad.close()
            pad = RecordingPad()
            slot.pad = pad
            pads.append(pad)

        wanted: list[int] = []
        submit = server._submit_input

        def record(*args, **kwargs):
            wanted.append(kwargs.get("want_slot", args[4]))
            return submit(*args, **kwargs)

        server._submit_input = record
        addr = ("127.0.0.1", 42000)

        server._dispatch(INPUT_GOLDEN[:11], addr, time.time())
        server._dispatch(b"NOPE" + INPUT_GOLDEN[4:], addr, time.time())
        assert not server.peers, "short and non-VPAD datagrams must be ignored"

        server._handle_input(INPUT_GOLDEN, addr, time.time())
        assert wanted == [3], "29th byte must reach the server as want_slot"
        server._flush_inputs(time.time())
        state = pads[0].applied[-1]
        assert state.seq == 0x0102
        assert state.buttons == 0x00010400
        assert (state.lx, state.ly, state.rx, state.ry) == (-32768, 1234, -1, 32767)
        assert (state.l2, state.r2, state.battery) == (200, 77, 91)

        # A legacy 28-byte frame is still accepted, with no slot request.
        legacy = INPUT_GOLDEN[:28]
        server._handle_input(legacy, addr, time.time())
        assert wanted == [3, 0]
        server._flush_inputs(time.time())
        assert pads[0].applied[-1].seq == 0x0102
        print("  real server parser accepts both 29- and 28-byte frames")
    finally:
        server.sock.close()


def test_layout_snapshots() -> None:
    modern = parse_snapshot(LAYOUT_GOLDEN)
    assert "__meta" in modern
    assert modern["__meta"]["fx"] == 2
    assert modern["l1"]["shape"] == 4  # HEX
    assert modern["m1"]["bits"] == 81920  # CROSS + PS
    assert modern["m1"]["shape"] == 2  # CIRCLE
    assert modern["m1"]["template"] == 4  # K_PILL
    assert (modern["m1"]["bw"], modern["m1"]["bh"]) == (1050, 620)

    seven = parse_snapshot(LAYOUT_SEVEN_FIELD_GOLDEN)
    assert seven["m1"]["bits"] == 1024  # L1, read from field 7
    assert (seven["m1"]["template"], seven["m1"]["face"]) == (4, -1)
    assert (seven["m1"]["bw"], seven["m1"]["bh"]) == (1050, 620)

    legacy = parse_snapshot(LAYOUT_LEGACY_GOLDEN)
    assert "__meta" not in legacy, "an old code must not claim new-layout ownership"
    assert legacy["l1"]["shape"] == 3  # SQUARE
    assert legacy["m1"]["bits"] == 65536  # PS
    assert legacy["m1"]["shape"] == 0  # ROUND
    print("  layout snapshot golden strings and legacy fields ok")


def main() -> int:
    print("lemoGamePad protocol golden test")
    test_packet_layout()
    test_server_parser()
    test_layout_snapshots()
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
