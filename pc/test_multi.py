#!/usr/bin/env python3
"""
Multi-phone tests for lemoGamePad-server.

    python test_multi.py

Uses real UDP sockets against a server running in this process. No phone or
ViGEmBus driver is required: when the virtual pad is unavailable the server
still records the input state it would have sent.
"""

from __future__ import annotations

import os
import socket
import struct
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import vpad_server as srv  # noqa: E402

MAGIC = b"VPAD"
HEADER = struct.Struct("<4sBBHI")
INPUT = struct.Struct("<4sBBHIIhhhhBBBB")

CROSS = 14
CIRCLE = 13
L1 = 10


def free_udp_port() -> int:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def wait_for(predicate, timeout=3.0, message="condition not met"):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError(message)


class Phone:
    """A fake phone: one UDP socket, one token, one sequence counter."""

    def __init__(self, port: int, token: int = 0):
        self.port = port
        self.token = token
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        # Pad creation can take a couple of seconds on a busy CI runner, so
        # allow the offer more than the old 2 s before giving up.
        self.sock.settimeout(5.0)
        self.addr = ("127.0.0.1", self.port)
        self.local = self.sock.getsockname()
        self.seq = 0

    def discover(self, token: int | None = None):
        probe = self.token if token is None else token
        self.sock.sendto(
            HEADER.pack(MAGIC, srv.VERSION, srv.MSG_DISCOVER, 0, probe), self.addr
        )
        data, _ = self.sock.recvfrom(2048)
        magic, _ver, mtype, _seq, offered = HEADER.unpack_from(data)
        assert magic == MAGIC, magic
        assert mtype == srv.MSG_OFFER, mtype
        name_len = data[HEADER.size]
        name = data[HEADER.size + 1 : HEADER.size + 1 + name_len].decode("utf-8")
        pad_type = data[HEADER.size + 1 + name_len]
        pos = HEADER.size + 2 + name_len
        ips = []
        if len(data) > pos:
            count = data[pos]
            pos += 1
            for _ in range(count):
                if len(data) < pos + 4:
                    break
                ips.append(socket.inet_ntoa(data[pos : pos + 4]))
                pos += 4
        return offered, name, pad_type, ips

    def input(self, buttons=0, lx=0, ly=0, rx=0, ry=0, l2=0, r2=0, battery=90,
              want_slot=0):
        packet = INPUT.pack(
            MAGIC, srv.VERSION, srv.MSG_INPUT, self.seq & 0xFFFF, self.token,
            buttons, lx, ly, rx, ry, l2, r2, battery, 0,
        ) + bytes([want_slot & 0xFF])
        self.sock.sendto(packet, self.addr)
        self.seq += 1

    def ping(self):
        packet = HEADER.pack(
            MAGIC, srv.VERSION, srv.MSG_PING, self.seq & 0xFFFF, self.token
        ) + struct.pack("<I", 1234)
        self.sock.sendto(packet, self.addr)
        self.seq += 1

    def request_slot(self, target: int):
        packet = HEADER.pack(
            MAGIC, srv.VERSION, srv.MSG_SLOT_REQUEST, self.seq & 0xFFFF,
            self.token,
        ) + bytes([target & 0xFF])
        self.sock.sendto(packet, self.addr)
        self.seq += 1

    def vote(self, request_id: int, accept: bool):
        packet = HEADER.pack(
            MAGIC, srv.VERSION, srv.MSG_SLOT_VOTE, self.seq & 0xFFFF,
            self.token,
        ) + struct.pack("<IB", request_id & 0xFFFFFFFF, 1 if accept else 0)
        self.sock.sendto(packet, self.addr)
        self.seq += 1

    def drain(self, timeout=0.35):
        packets = []
        old = self.sock.gettimeout()
        self.sock.settimeout(0.05)
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            packets.append((data, addr))
        self.sock.settimeout(old)
        return packets

    def rumbles(self, timeout=0.35):
        return [
            data
            for data, _ in self.drain(timeout)
            if HEADER.unpack_from(data)[2] == srv.MSG_RUMBLE
        ]

    def swap_packets(self, timeout=0.6):
        """Printed swap asks/results received by this fake phone."""
        out = []
        for data, _ in self.drain(timeout):
            if len(data) < HEADER.size:
                continue
            magic, _ver, mtype, _seq, _tok = HEADER.unpack_from(data)
            if magic != MAGIC:
                continue
            if mtype == srv.MSG_SLOT_ASK and len(data) >= HEADER.size + 5:
                request_id, from_slot = struct.unpack_from(
                    "<IB", data, HEADER.size
                )
                out.append(("ask", request_id, from_slot))
            elif mtype == srv.MSG_SLOT_RESULT and len(data) >= HEADER.size + 6:
                request_id, code, slot = struct.unpack_from(
                    "<IBB", data, HEADER.size
                )
                out.append(("result", request_id, code, slot))
        return out

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def pad_of(server, peer):
    slot = server.slots[peer.slot]
    assert slot.peer is peer
    return slot.pad


def pad_buttons(server, peer):
    """The pad's last button mask, or None while its device is still created."""
    pad = pad_of(server, peer)
    if pad is None or pad._last is None:
        return None
    return pad._last.buttons


class Keepalive:
    """
    Send periodic input for fake phones while a test does slow setup.

    The suite uses a deliberately short ``PEER_TIMEOUT`` (4 s here, 8 s in
    production) to test timeouts. Creating a virtual pad can take longer than
    that on a loaded runner, and a real phone keeps sending at ~140 Hz the
    whole time, so the fake phones must too. Without this, an earlier phone
    expires while the next one is being registered.
    """

    def __init__(self, phones, interval: float = 0.2):
        self.phones = phones
        self.interval = interval
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self._run, name="fake-phone-keepalive", daemon=True
        )

    def _run(self) -> None:
        while not self._stop.wait(self.interval):
            for phone in list(self.phones):
                try:
                    phone.input()
                except OSError:
                    pass

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        self._thread.join(timeout=2.0)
        return False


def test_discovery(server, port):
    phone = Phone(port)
    try:
        token, name, pad_type, ips = phone.discover(0)
        assert token != 0, "discovery must hand out a token"
        assert name, "offer should carry the PC name"
        assert pad_type in (0, 1)
        assert ips, "offer should list the PC's IPv4 addresses"
        assert all(ip.count(".") == 3 for ip in ips), ips
        print(
            f"  discovery ok: token 0x{token:08X}, name {name!r}, "
            f"addresses {', '.join(ips)}"
        )

        phone.token = token
        repeat, _, _, _ = phone.discover()
        assert repeat == token, "a known token must be echoed unchanged"
        print("  discovery keeps the phone's token (no duplicate slot)")
    finally:
        phone.close()


def test_two_pads(server, port):
    first = Phone(port)
    second = Phone(port)
    try:
        first.token = first.discover(0)[0]
        second.token = second.discover(0)[0]
        assert first.token != second.token

        first.input(buttons=1 << CROSS, lx=16000)
        second.input(buttons=1 << CIRCLE, ly=-16000)
        wait_for(lambda: len(server.peers) == 2, timeout=10.0,
                 message="both phones should register")

        first_peer = server.peers[first.token]
        second_peer = server.peers[second.token]
        assert first_peer.slot != second_peer.slot, "phones must get separate pads"

        wait_for(
            lambda: pad_buttons(server, first_peer) == 1 << CROSS,
            message="first pad did not receive its input",
        )
        wait_for(
            lambda: pad_buttons(server, second_peer) == 1 << CIRCLE,
            message="second pad did not receive its input",
        )
        assert pad_of(server, first_peer) is not pad_of(server, second_peer)
        print(
            "  independent pads ok: "
            f"slots {first_peer.slot + 1} and {second_peer.slot + 1}"
        )
        return first, second
    except Exception:
        first.close()
        second.close()
        raise


def test_slot_limit(server, port, existing):
    phones = list(existing)
    extra = None
    try:
        # Keep the phones that already hold a slot alive while the next pads
        # are created, otherwise the short test timeout drops them first.
        with Keepalive(phones):
            while len(server.peers) < server.max_phones:
                phone = Phone(port)
                phone.token = phone.discover(0)[0]
                phone.input(buttons=1 << L1)
                phones.append(phone)
                wait_for(
                    lambda: len(server.peers) == len(phones),
                    timeout=10.0,
                    message="phone did not take a slot",
                )
                time.sleep(0.05)

            extra = Phone(port)
            extra.token = extra.discover(0)[0]
            for _ in range(5):
                extra.input(buttons=1 << CROSS)
                time.sleep(0.03)
            time.sleep(0.3)
            assert extra.token not in server.peers, "over-limit phone must be rejected"
            assert len(server.peers) == server.max_phones
            used = sorted(server.peers[phone.token].slot for phone in phones)
            assert used == list(range(server.max_phones)), used
            print(f"  slot limit ok: {server.max_phones} pads, extra phone ignored")
        return phones
    except Exception:
        for phone in phones:
            phone.close()
        raise
    finally:
        if extra is not None:
            extra.close()


def test_rumble(server, phones):
    for phone in phones:
        phone.input(buttons=0)
    wait_for(
        lambda: len(server.peers) == len(phones),
        timeout=10.0,
        message="phones dropped before the rumble test",
    )
    first, second = phones[0], phones[1]
    first_peer = server.peers[first.token]
    first.drain(0.1)
    second.drain(0.1)

    server._slot_rumble(server.slots[first_peer.slot], 40, 90)
    packets = first.rumbles(0.5)
    assert len(packets) == 1, f"expected one rumble packet, got {len(packets)}"
    _magic, _ver, mtype, _seq, token = HEADER.unpack_from(packets[0])
    assert mtype == srv.MSG_RUMBLE
    assert token == first.token, "rumble must carry the target phone's token"
    assert packets[0][HEADER.size :] == bytes([40, 90])

    other = second.rumbles(0.4)
    if other:
        details = ", ".join(
            str(HEADER.unpack_from(packet, 0)) + " " + packet[HEADER.size :].hex()
            for packet in other
        )
        raise AssertionError(f"the other phone received rumble packets: {details}")
    print("  rumble routing ok: only the target phone is buzzed")


def test_idle_release(server, phones):
    phone = phones[0]
    peer = server.peers[phone.token]
    pad = pad_of(server, peer)
    phone.input(buttons=1 << CROSS)
    wait_for(lambda: pad._last.buttons == 1 << CROSS)
    wait_for(
        lambda: pad._last.is_neutral() and peer.released,
        timeout=server.release_after + 2.0,
        message="idle phone did not release its buttons",
    )
    print("  idle release ok: buttons released without dropping the phone")


def test_reconnect(server, phones):
    pads = {slot.index: slot.pad for slot in server.slots if slot.pad is not None}
    for phone in phones:
        phone.close()
    wait_for(lambda: not server.peers, timeout=server.PEER_TIMEOUT + 3.0,
             message="phones were not dropped after the timeout")
    wait_for(
        lambda: all(slot.peer is None for slot in server.slots),
        message="slots were not released",
    )
    print("  timeout ok: phones dropped and slots released")

    phone = Phone(server.port)
    try:
        token, _, _, _ = phone.discover(0)
        phone.token = token
        phone.input(buttons=1 << CIRCLE)
        wait_for(lambda: token in server.peers, message="phone did not reconnect")
        peer = server.peers[token]
        assert peer.slot == 0, f"reconnect should reuse the first pad, got {peer.slot}"
        assert server.slots[0].pad is pads[0], "the virtual pad must be reused, not recreated"

        # Same phone, new socket (e.g. Wi-Fi -> USB tether): identity survives.
        moved = Phone(server.port, token=token)
        try:
            moved.input(buttons=1 << CROSS)
            wait_for(
                lambda: server.peers[token].addr == moved.local,
                message="peer address did not follow the phone",
            )
            assert len(server.peers) == 1, "a new socket must not create a second pad"
            wait_for(
                lambda: server.slots[0].pad._last.buttons == 1 << CROSS,
                message="input from the new address was ignored",
            )
            print("  reconnect ok: same token, same pad, new address")
        finally:
            moved.close()
    finally:
        phone.close()


def test_slot_kinds():
    """P5-P8 fall back to DS4 on Windows; Linux keeps every requested pad."""
    win8 = srv.Server._slot_kinds("x360", 8, windows=True)
    assert win8 == ["x360"] * 4 + ["ds4"] * 4, win8
    assert srv.Server._slot_kinds("x360", 4, windows=True) == ["x360"] * 4
    assert srv.Server._slot_kinds("ds4", 8, windows=True) == ["ds4"] * 8
    assert srv.Server._slot_kinds("x360", 8, windows=False) == ["x360"] * 8
    print("  slot backends ok: P5-P8 use DS4 only on Windows")


def test_max_phones_cap():
    """The CLI advertises eight; the constructor must clamp to exactly that."""
    port = free_udp_port()
    probe = srv.Server(
        port, "x360", video_enabled=False, qr_enabled=False, max_phones=99
    )
    try:
        assert probe.max_phones == 8, probe.max_phones
        assert len(probe.slots) == 8, len(probe.slots)
        print("  max-phones cap ok: 8 slots")
    finally:
        probe.shutdown()

    port = free_udp_port()
    default = srv.Server(port, "x360", video_enabled=False, qr_enabled=False)
    try:
        assert default.max_phones == 8, default.max_phones
        print("  default slots ok: 8 phones out of the box")
    finally:
        default.shutdown()


def test_pad_creation_order():
    """
    A request for P5 must create P1..P5 in order, never only P5.

    Games number controllers by the order the OS enumerates them, so creating
    just the requested pad would hand P5 the game's first controller.
    """
    created = []
    original = srv.create_virtual_pad

    class FakePad:
        def __init__(self):
            self.ok = True
            self.error = ""
            self._last = srv.InputState()

        def apply(self, state):
            self._last = state

        def close(self):
            pass

    def fake_create(kind, on_rumble=None):
        pad = FakePad()
        created.append(pad)
        return pad

    srv.create_virtual_pad = fake_create
    server = None
    try:
        server = srv.Server(
            free_udp_port(), "x360", video_enabled=False, qr_enabled=False,
            max_phones=8,
        )
        # The constructor creates P1 up front so a missing driver shows in the
        # banner.
        assert len(created) == 1, len(created)

        peer, fresh, reason = server._register_peer(
            0x5EED, ("127.0.0.1", 5000), time.time(), want_slot=5
        )
        assert peer is not None and fresh and reason == "", (peer, fresh, reason)
        assert peer.slot == 4, peer.slot
        assert len(created) == 5, len(created)
        assert all(slot.pad is not None for slot in server.slots[:5])
        assert all(slot.pad is None for slot in server.slots[5:]), "created too many"
        print("  pad order ok: P5 created P1-P5 first, no P6-P8")
    finally:
        srv.create_virtual_pad = original
        if server is not None:
            server.shutdown()


def start_isolated_server(max_phones=4, peer_timeout=6.0):
    """Small server on its own socket, for tests that need a clean slot map."""
    port = free_udp_port()
    server = srv.Server(
        port,
        "x360",
        release_after=0.4,
        video_enabled=False,
        qr_enabled=False,
        max_phones=max_phones,
    )
    server.PEER_TIMEOUT = peer_timeout
    server._print_banner = lambda: None
    server._status = lambda: None
    thread = threading.Thread(target=server.run, name="test-slot-server", daemon=True)
    thread.start()
    time.sleep(0.1)
    return server, port, thread


def test_requested_slot():
    """P1..P8 requests: honour a free pad, fall back when busy, move live."""
    server, port, thread = start_isolated_server(max_phones=4)
    first = Phone(port)
    second = Phone(port)
    try:
        first.token = first.discover(0)[0]
        second.token = second.discover(0)[0]

        # A direct request for P3 must land on slot index 2.
        first.input(want_slot=3)
        wait_for(lambda: first.token in server.peers, timeout=10.0,
                 message="first phone did not register")
        assert server.peers[first.token].slot == 2, server.peers[first.token].slot

        # The peer is inserted before its virtual pad is created, and creating
        # a uinput device can take several seconds on a busy CI runner. Wait
        # for the receive thread to finish that work, otherwise the PONG below
        # may still be sitting in the socket when drain() times out.
        wait_for(lambda: server.slots[2].pad is not None, timeout=20.0,
                 message="the virtual pad was never created")

        # PONG reports the pad the PC actually assigned (trailing byte).
        first.ping()
        reported = None
        for data, _ in first.drain(0.5):
            magic, _ver, mtype, _seq, _tok = HEADER.unpack_from(data)
            if magic == MAGIC and mtype == srv.MSG_PONG and len(data) >= HEADER.size + 7:
                reported = data[HEADER.size + 6]
        assert reported == 3, f"PONG should report pad 3, got {reported}"

        # The same request from another phone cannot steal P3; it falls back.
        second.input(want_slot=3)
        wait_for(lambda: second.token in server.peers, timeout=10.0,
                 message="second phone did not register")
        assert server.peers[second.token].slot != 2, "P3 was stolen"

        # Changing the request moves an already-connected phone to a free pad.
        first.input(want_slot=2)
        wait_for(lambda: server.peers[first.token].slot == 1, timeout=3.0,
                 message="connected phone did not move to P2")
        assert server.slots[2].peer is None, "the old pad was not released"

        # A slot that was never used has no device yet. The move must create
        # it, otherwise input sent after the move is silently dropped.
        wait_for(lambda: server.slots[1].pad is not None, timeout=20.0,
                 message="the moved-to pad was never created")
        first.input(buttons=1 << CROSS)
        wait_for(
            lambda: pad_buttons(server, server.peers[first.token]) == 1 << CROSS,
            message="the moved-to pad did not receive input",
        )

        # A pad owned by another phone is never taken; the mover stays put.
        occupied = server.peers[second.token].slot + 1
        for _ in range(3):
            first.input(want_slot=occupied)
            time.sleep(0.05)
        time.sleep(0.2)
        assert server.peers[first.token].slot == 1, "an occupied pad was stolen"

        # Out-of-range requests are ignored like AUTO.
        first.input(want_slot=9)
        time.sleep(0.2)
        assert server.peers[first.token].slot == 1, "out-of-range slot moved the phone"
        print(
            "  requested slot ok: honours P3, falls back when busy, "
            "moves live, never steals"
        )
    finally:
        first.close()
        second.close()
        server.running = False
        thread.join(timeout=3)


def test_swap_accept():
    """Two players must agree before the PC trades their pads."""
    server, port, thread, first, second = two_phone_server()
    try:
        first.request_slot(2)
        wait_for(lambda: bool(server._swaps), message="swap request was not registered")
        request_id = next(iter(server._swaps))
        asks = second.swap_packets(0.8)
        assert ("ask", request_id, 1) in asks, asks

        # Input keeps flowing while the other player decides.
        first.input(buttons=1 << CROSS, want_slot=1)
        second.input(buttons=1 << CIRCLE, want_slot=2)
        wait_for(
            lambda: pad_buttons(server, server.peers[first.token]) == 1 << CROSS,
            message="requester input stalled during the swap ask",
        )
        wait_for(
            lambda: pad_buttons(server, server.peers[second.token]) == 1 << CIRCLE,
            message="target input stalled during the swap ask",
        )
        assert server.peers[first.token].slot == 0
        assert server.peers[second.token].slot == 1

        second.vote(request_id, True)
        wait_for(
            lambda: server.peers[first.token].slot == 1
            and server.peers[second.token].slot == 0,
            message="pads were not swapped after ACCEPT",
        )
        results = first.swap_packets(1.0)
        assert any(
            kind == "result" and rid == request_id
            and code == srv.SLOT_ACCEPTED and slot == 2
            for kind, rid, code, slot in results
        ), results

        # Stale input naming the old pad must not bounce the player back: the
        # server briefly ignores slot requests after a swap.
        first.input(buttons=1 << CROSS, want_slot=1)
        time.sleep(0.25)
        assert server.peers[first.token].slot == 1, "stale request moved the player back"

        # Both players keep playing on their new pads.
        first.input(buttons=1 << CROSS, want_slot=2)
        second.input(buttons=1 << CIRCLE, want_slot=1)
        wait_for(
            lambda: pad_buttons(server, server.peers[first.token]) == 1 << CROSS
            and pad_buttons(server, server.peers[second.token]) == 1 << CIRCLE,
            message="swapped pads did not receive input",
        )
        print("  swap accept ok: consent needed, input keeps running, pads trade")
    finally:
        stop_phones(server, thread, first, second)


def test_swap_decline():
    server, port, thread, first, second = two_phone_server()
    try:
        first.request_slot(2)
        wait_for(lambda: bool(server._swaps), message="swap request was not registered")
        request_id = next(iter(server._swaps))
        second.vote(request_id, False)
        results = first.swap_packets(1.0)
        assert any(
            kind == "result" and rid == request_id
            and code == srv.SLOT_DECLINED and slot == 1
            for kind, rid, code, slot in results
        ), results
        assert server.peers[first.token].slot == 0
        assert server.peers[second.token].slot == 1

        # A retry arriving just after DECLINE must not reopen the popup.
        first.request_slot(2)
        wait_for(lambda: not server._swaps, message="a declined swap was reopened")
        again = first.swap_packets(0.6)
        assert any(
            kind == "result" and code == srv.SLOT_DECLINED
            for kind, _rid, code, _slot in again
        ), again
        print("  swap decline ok: stays put and honours the cooldown")
    finally:
        stop_phones(server, thread, first, second)


def test_swap_cancel():
    """Picking another slot withdraws the ask still on the target's screen."""
    server, port, thread, first, second = two_phone_server()
    try:
        first.request_slot(2)
        wait_for(lambda: bool(server._swaps), message="swap request was not registered")
        request_id = next(iter(server._swaps))
        first.swap_packets(0.4)
        second.swap_packets(0.4)

        first.request_slot(0)
        wait_for(lambda: not server._swaps, message="cancelled swap stayed pending")
        cancelled = second.swap_packets(0.8)
        assert any(
            kind == "result" and rid == request_id
            and code == srv.SLOT_CANCELLED
            for kind, rid, code, _slot in cancelled
        ), cancelled
        assert server.peers[first.token].slot == 0
        assert server.peers[second.token].slot == 1

        # A cancellation must not start a cooldown: the same pair can ask again.
        first.request_slot(2)
        wait_for(lambda: bool(server._swaps), message="a cancelled swap could not be retried")
        assert any(
            packet[0] == "ask" for packet in second.swap_packets(0.8)
        )
        print("  swap cancel ok: target card closes and the pair can ask again")
    finally:
        stop_phones(server, thread, first, second)


def test_swap_timeout():
    server, port, thread, first, second = two_phone_server()
    try:
        server.SWAP_TIMEOUT = 0.6
        server.SWAP_ASK_INTERVAL = 0.2
        first.request_slot(2)
        wait_for(lambda: bool(server._swaps), message="swap request was not registered")
        request_id = next(iter(server._swaps))
        results = first.swap_packets(2.5)
        assert any(
            kind == "result" and rid == request_id
            and code == srv.SLOT_TIMEOUT
            for kind, rid, code, _slot in results
        ), results
        assert not server._swaps
        assert server.peers[first.token].slot == 0
        assert server.peers[second.token].slot == 1
        print("  swap timeout ok: unanswered requests expire without moving")
    finally:
        stop_phones(server, thread, first, second)


def test_free_slot_request():
    """A free pad needs no consent: the request is an ordinary move."""
    server, port, thread, first, second = two_phone_server()
    try:
        first.request_slot(3)
        wait_for(
            lambda: server.peers[first.token].slot == 2,
            message="free slot request did not move the player",
        )
        wait_for(
            lambda: server.slots[2].pad is not None,
            timeout=20.0,
            message="the never-used pad was not created",
        )
        results = first.swap_packets(2.0)
        assert any(
            kind == "result" and code == srv.SLOT_ACCEPTED and slot == 3
            for kind, _rid, code, slot in results
        ), results
        assert server.peers[second.token].slot == 1

        # The input frame written before the result still names P1.
        first.input(buttons=1 << CROSS, want_slot=1)
        time.sleep(0.25)
        assert server.peers[first.token].slot == 2, "stale request moved the player back"
        print("  free slot ok: immediate move, no ask, stale input ignored")
    finally:
        stop_phones(server, thread, first, second)


def two_phone_server(max_phones=4):
    server, port, thread = start_isolated_server(max_phones=max_phones)
    first = Phone(port)
    second = Phone(port)
    first.token = first.discover(0)[0]
    second.token = second.discover(0)[0]
    first.input()
    wait_for(
        lambda: first.token in server.peers,
        timeout=10.0,
        message="first phone did not register",
    )
    second.input()
    wait_for(
        lambda: second.token in server.peers,
        timeout=10.0,
        message="second phone did not register",
    )
    wait_for(
        lambda: all(server.slots[index].pad is not None for index in (0, 1)),
        timeout=20.0,
        message="the first two pads were never created",
    )
    return server, port, thread, first, second


def stop_phones(server, thread, *phones):
    for phone in phones:
        phone.close()
    server.running = False
    server._wake_loop()
    thread.join(timeout=3.0)


def main() -> int:
    port = free_udp_port()
    server = srv.Server(
        port,
        "x360",
        release_after=0.4,
        video_enabled=False,
        qr_enabled=False,
        max_phones=3,
    )
    # Short enough to exercise dropping, long enough for a slow CI runner to
    # finish creating a virtual pad before the phones that are already
    # connected expire. The production value is 8 s.
    server.PEER_TIMEOUT = 4.0
    server._print_banner = lambda: None
    # Server.run prints no status line when stdout is not a tty, but make sure
    # the periodic line stays out of the test log.
    server._status = lambda: None

    thread = threading.Thread(target=server.run, name="test-server", daemon=True)
    thread.start()
    time.sleep(0.1)
    phones = []
    try:
        print("lemoGamePad multi-phone test")
        test_slot_kinds()
        test_max_phones_cap()
        test_pad_creation_order()
        test_requested_slot()
        test_swap_accept()
        test_swap_decline()
        test_swap_cancel()
        test_swap_timeout()
        test_free_slot_request()
        test_discovery(server, port)
        phones = list(test_two_pads(server, port))
        phones = test_slot_limit(server, port, phones)
        test_rumble(server, phones)
        test_idle_release(server, phones[:1])
        test_reconnect(server, phones)
        print("all checks passed")
        return 0
    finally:
        for phone in phones:
            phone.close()
        server.running = False
        thread.join(timeout=3)
        if server.video is not None:
            server.video.stop()


if __name__ == "__main__":
    raise SystemExit(main())
