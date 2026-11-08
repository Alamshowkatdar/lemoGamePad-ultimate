#!/usr/bin/env python3
"""
Latency-path tests for lemoGamePad-server.

    python test_latency.py

These do not need a phone, a virtual pad driver or a display. They drive the
receive path directly and check the three properties that keep input feeling
the same on a good link and a bad one:

  * a burst of queued datagrams collapses to one pad update,
  * a late (out-of-order) datagram cannot snap the sticks backwards,
  * a new socket for the same phone is accepted even though its sequence
    counter starts over,
  * PONG carries the uplink-loss figure the phone shows in its HUD.
"""

from __future__ import annotations

import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import vpad_server as srv  # noqa: E402

MAGIC = b"VPAD"
HEADER = struct.Struct("<4sBBHI")
INPUT = struct.Struct("<4sBBHIIhhhhBBBB")

CROSS = 14
CIRCLE = 13


def free_udp_port() -> int:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


class RecordingPad:
    """A virtual pad that remembers every update, without needing a driver."""

    def __init__(self):
        self.ok = True
        self.error = ""
        self.rumble_error = ""
        self.applied: list[srv.InputState] = []
        self._last = srv.InputState()

    def apply(self, state: srv.InputState) -> None:
        self.applied.append(state)
        self._last = state

    def close(self) -> None:
        pass


def packet(token: int, seq: int, buttons: int = 0) -> bytes:
    return INPUT.pack(
        MAGIC, srv.VERSION, srv.MSG_INPUT, seq & 0xFFFF, token,
        buttons, 0, 0, 0, 0, 0, 0, 80, 0,
    )


def flush(server, addr, token, seq, buttons=0, when=None):
    server._handle_input(packet(token, seq, buttons), addr, when or time.time())
    server._flush_inputs(time.time())


def main() -> int:
    port = free_udp_port()
    server = srv.Server(
        port, "x360", video_enabled=False, qr_enabled=False, max_phones=2
    )
    server.running = False
    # Server.__init__ already created a real pad for slot 0. Close it before
    # replacing it, or the test leaves a /dev/uinput / ViGEm device behind.
    real_pad = server.slots[0].pad
    if real_pad is not None:
        real_pad.close()
    rec = RecordingPad()
    server.slots[0].pad = rec
    addr = ("127.0.0.1", 42000)
    token = 0xA11CE

    print("lemoGamePad latency-path test")

    # 1. A burst is one pad update, using the newest state.
    for i in range(1, 31):
        server._handle_input(packet(token, i, 1 << CROSS), addr, time.time())
    assert len(server._pending) == 1
    server._flush_inputs(time.time())
    assert len(rec.applied) == 1, f"burst applied {len(rec.applied)} updates, expected 1"
    assert rec.applied[0].seq == 30, rec.applied[0].seq
    assert rec.applied[0].buttons == 1 << CROSS
    print("  burst coalescing ok: 30 packets -> 1 update")

    # 2. A late packet that arrives after a newer one is dropped.
    flush(server, addr, token, 12, 1 << CIRCLE)
    assert len(rec.applied) == 1, "an out-of-order packet was applied"
    assert rec.applied[0].buttons == 1 << CROSS
    print("  out-of-order discard ok: old state ignored")

    # 3. Forward progress still works, including 16 bit wrap-around.
    flush(server, addr, token, 31, 1 << CIRCLE)
    assert rec.applied[-1].buttons == 1 << CIRCLE
    server.peers[token].applied_seq = 0xFFFE
    flush(server, addr, token, 0x0003, 1 << CROSS)
    assert rec.applied[-1].seq == 3, "sequence wrap-around was rejected"
    print("  forward + wrap-around ok")

    # 4. The same phone on a new socket starts a new sequence counter.
    moved_addr = ("127.0.0.1", 42001)
    flush(server, moved_addr, token, 1, 1 << CIRCLE)
    peer = server.peers[token]
    assert peer.addr == moved_addr, peer.addr
    assert rec.applied[-1].buttons == 1 << CIRCLE, "new socket was rejected as stale"
    print("  new socket ok: sequence restarts are accepted")

    # 4b. A restart on the *same* address must not stall for seconds: once the
    # old position is clearly abandoned, the receiver resyncs to the new count.
    flush(server, moved_addr, token, 400, 1 << CIRCLE)
    peer = server.peers[token]
    assert peer.applied_seq == 400, peer.applied_seq
    peer.last_apply -= 1.0  # pretend the restart happened a second ago
    flush(server, moved_addr, token, 1, 1 << CROSS)
    assert rec.applied[-1].seq == 1, "a same-address restart was dropped as late"
    print("  same-address restart ok: stale-looking sequence resyncs")

    # 5. PONG echoes the phone clock and appends the uplink-loss figure.
    client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client.bind(("127.0.0.1", 0))
    client.settimeout(2.0)
    try:
        client_addr = client.getsockname()
        # Re-register on the client's real address, then create a gap in the
        # sequence (1 -> 11 means nine packets were lost).
        server._handle_input(packet(token, 1), client_addr, time.time())
        server._flush_inputs(time.time())
        server._handle_input(packet(token, 11), client_addr, time.time())
        server._flush_inputs(time.time())

        ping = HEADER.pack(MAGIC, srv.VERSION, srv.MSG_PING, 77, token)
        ping += struct.pack("<I", 123456)
        server._handle_ping(ping, client_addr, time.time(), 77, token)
        data, _ = client.recvfrom(2048)

        magic, _ver, mtype, seq, echoed = HEADER.unpack_from(data)
        assert magic == MAGIC and mtype == srv.MSG_PONG and seq == 77
        assert echoed == token
        assert struct.unpack_from("<I", data, HEADER.size)[0] == 123456
        assert len(data) >= HEADER.size + 6, "PONG is missing the loss field"
        loss = struct.unpack_from("<H", data, HEADER.size + 4)[0]
        assert 0 < loss <= 1000, loss
        print(f"  pong stats ok: clock echo + {loss / 10:.1f}% uplink loss")
    finally:
        client.close()

    # 6. A dropped phone leaves no pending work behind.
    server._pending.clear()
    server.shutdown()
    assert not server.peers
    assert not server._pending
    print("  shutdown ok")
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
