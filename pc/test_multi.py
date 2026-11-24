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
import types

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

    def set_party_lock(self, enabled: bool):
        packet = HEADER.pack(
            MAGIC, srv.VERSION, srv.MSG_PARTY_LOCK, self.seq & 0xFFFF,
            self.token,
        ) + bytes([1 if enabled else 0])
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

    slot = server.slots[first_peer.slot]
    server._slot_rumble(slot, 40, 90)
    packets = first.rumbles(0.9)
    assert packets, "no rumble packet reached the phone"
    for packet in packets:
        _magic, _ver, mtype, _seq, token = HEADER.unpack_from(packet)
        assert mtype == srv.MSG_RUMBLE
        assert token == first.token, "rumble must carry the target phone's token"
        assert packet[HEADER.size :] == bytes([40, 90]), packet.hex()

    # An unchanged live level is re-sent by the refresh loop so the phone can
    # keep a long effect alive without mistaking packet loss for "stop".
    first.drain(0.1)
    server._refresh_active_rumble()
    refreshed = [
        packet for packet in first.rumbles(0.25)
        if packet[HEADER.size :] == bytes([40, 90])
    ]
    assert refreshed, "a live rumble level was not refreshed"

    # A game-level stop must be forwarded immediately, not treated as stale.
    server._slot_rumble(slot, 0, 0)
    stops = [
        packet for packet in first.rumbles(0.3)
        if packet[HEADER.size :] == bytes([0, 0])
    ]
    assert stops, "the stop transition was not sent"

    # A refresh that runs after the stop must not resurrect the old level. This
    # used to happen when the refresh snapshot held a stale non-zero value.
    first.drain(0.1)
    server._refresh_active_rumble()
    resurrected = [
        packet for packet in first.rumbles(0.25)
        if packet[HEADER.size :] == bytes([40, 90])
    ]
    assert not resurrected, "a stale refresh overrode the game's stop"

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


def test_ds4_axis_convention():
    """DS4 Y is inverted in the HID report; SDL auto-mapping must see it."""

    def named(*names):
        return types.SimpleNamespace(**{name: name for name in names})

    vg = types.SimpleNamespace(
        DS4_BUTTONS=named(
            "DS4_BUTTON_CROSS",
            "DS4_BUTTON_CIRCLE",
            "DS4_BUTTON_SQUARE",
            "DS4_BUTTON_TRIANGLE",
            "DS4_BUTTON_SHOULDER_LEFT",
            "DS4_BUTTON_SHOULDER_RIGHT",
            "DS4_BUTTON_TRIGGER_LEFT",
            "DS4_BUTTON_TRIGGER_RIGHT",
            "DS4_BUTTON_THUMB_LEFT",
            "DS4_BUTTON_THUMB_RIGHT",
            "DS4_BUTTON_SHARE",
            "DS4_BUTTON_OPTIONS",
        ),
        DS4_SPECIAL_BUTTONS=named("DS4_SPECIAL_BUTTON_PS"),
        DS4_DPAD_DIRECTIONS=named(
            "DS4_BUTTON_DPAD_NORTHEAST",
            "DS4_BUTTON_DPAD_NORTHWEST",
            "DS4_BUTTON_DPAD_SOUTHEAST",
            "DS4_BUTTON_DPAD_SOUTHWEST",
            "DS4_BUTTON_DPAD_NORTH",
            "DS4_BUTTON_DPAD_SOUTH",
            "DS4_BUTTON_DPAD_WEST",
            "DS4_BUTTON_DPAD_EAST",
            "DS4_BUTTON_DPAD_NONE",
        ),
    )

    class FakePad:
        def __init__(self):
            self.axes = {}

        def press_button(self, value):
            pass

        def release_button(self, value):
            pass

        def press_special_button(self, value):
            pass

        def release_special_button(self, value):
            pass

        def directional_pad(self, value):
            pass

        def left_trigger_float(self, value):
            pass

        def right_trigger_float(self, value):
            pass

        def left_joystick_float(self, x, y):
            self.axes["left"] = (x, y)

        def right_joystick_float(self, x, y):
            self.axes["right"] = (x, y)

    pad = FakePad()
    virtual = srv.WindowsVirtualPad.__new__(srv.WindowsVirtualPad)
    virtual.vg = vg

    # Full up/left on the phone is positive Y in InputState. vgamepad writes
    # 128 + round(value * 127) into bThumbLY, where 0 = up.
    virtual._apply_ds4(
        pad, srv.InputState(lx=32767, ly=32767, rx=-32768, ry=-32768)
    )
    assert pad.axes["left"] == (1.0, -1.0), pad.axes
    assert pad.axes["right"] == (-1.0, 1.0), pad.axes
    assert 128 + round(pad.axes["left"][1] * 127) == 1, pad.axes
    assert 128 + round(pad.axes["right"][1] * 127) == 255, pad.axes

    # Centre must stay centre, and X must keep the X360 sign.
    virtual._apply_ds4(pad, srv.InputState())
    assert pad.axes == {"left": (0.0, 0.0), "right": (0.0, 0.0)}, pad.axes
    print("  DS4 sticks ok: Y flipped, X unchanged, centre preserved")


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
    The first phone owns the P1 host pad; idle slots never keep a device.

    Only occupied slots keep a virtual device, so switching slots never leaves
    an extra gamepad in the OS device list.
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
        assert peer.slot == 0, peer.slot  # P1 is the host seat
        assert server.host_token == 0x5EED
        # The main loop applies pad changes; a direct registration must sync.
        server._sync_pads()
        assert len(created) == 1, len(created)  # the P1 probe pad is reused
        assert server.slots[0].pad is not None
        assert all(slot.pad is None for slot in server.slots[1:]), "idle pads left connected"
        print("  pad order ok: first phone owns P1, no idle extra pads")
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

        # The first phone always owns P1, even when it remembers P3.
        first.input(want_slot=3)
        wait_for(lambda: first.token in server.peers, timeout=10.0,
                 message="first phone did not register")
        assert server.peers[first.token].slot == 0, server.peers[first.token].slot
        assert server.host_token == first.token

        # The peer is inserted before its virtual pad is created, and creating
        # a uinput device can take several seconds on a busy CI runner. Wait
        # for the receive thread to finish that work, otherwise the PONG below
        # may still be sitting in the socket when drain() times out.
        wait_for(lambda: server.slots[0].pad is not None, timeout=20.0,
                 message="the virtual pad was never created")

        # PONG reports the pad the PC actually assigned (trailing byte).
        first.ping()
        reported = None
        for data, _ in first.drain(0.5):
            magic, _ver, mtype, _seq, _tok = HEADER.unpack_from(data)
            if magic == MAGIC and mtype == srv.MSG_PONG and len(data) >= HEADER.size + 7:
                reported = data[HEADER.size + 6]
        assert reported == 1, f"PONG should report pad 1, got {reported}"

        # Later guests still get the pad they ask for when it is free.
        second.input(want_slot=3)
        wait_for(lambda: second.token in server.peers, timeout=10.0,
                 message="second phone did not register")
        assert server.peers[second.token].slot == 2, "P3 was not honoured"

        # Changing the request moves an already-connected guest to a free pad.
        second.input(want_slot=2)
        wait_for(lambda: server.peers[second.token].slot == 1, timeout=3.0,
                 message="connected phone did not move to P2")
        assert server.slots[2].peer is None, "the old pad was not released"

        # A slot that was never used has no device yet. The move must create
        # it, otherwise input sent after the move is silently dropped.
        wait_for(lambda: server.slots[1].pad is not None, timeout=20.0,
                 message="the moved-to pad was never created")
        assert server.slots[2].pad is None, "the old P3 pad stayed connected"
        assert sum(slot.pad is not None for slot in server.slots) == len(
            server.peers
        ), "idle pads are still connected"
        second.input(buttons=1 << CROSS)
        wait_for(
            lambda: pad_buttons(server, server.peers[second.token]) == 1 << CROSS,
            message="the moved-to pad did not receive input",
        )

        # P1 is never taken while the host is on it.
        for _ in range(3):
            second.input(want_slot=1)
            time.sleep(0.05)
        time.sleep(0.2)
        assert server.peers[second.token].slot == 1, "an occupied pad was stolen"
        assert server.host_token == first.token

        # Out-of-range requests are ignored like AUTO.
        second.input(want_slot=9)
        time.sleep(0.2)
        assert server.peers[second.token].slot == 1, "out-of-range slot moved the phone"
        print(
            "  requested slot ok: P1 stays host, later pads honoured, "
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


def test_p1_host_follows_swap():
    """Swapping P1 hands the party host role to the new occupant."""
    server, port, thread, first, second = two_phone_server()
    try:
        assert server.host_token == first.token
        assert server.peers[first.token].slot == 0

        first.request_slot(2)
        wait_for(lambda: bool(server._swaps), message="swap request was not registered")
        request_id = next(iter(server._swaps))
        second.vote(request_id, True)
        wait_for(
            lambda: server.peers[first.token].slot == 1
            and server.peers[second.token].slot == 0,
            message="pads were not swapped",
        )
        wait_for(lambda: server.host_token == second.token,
                 message="host did not follow P1")

        def party_state(phone):
            phone.ping()
            state = None
            for data, _ in phone.drain(0.6):
                if HEADER.unpack_from(data)[2] != srv.MSG_PONG:
                    continue
                if len(data) >= HEADER.size + 9:
                    state = (data[HEADER.size + 7], data[HEADER.size + 8])
            return state

        assert party_state(second) == (0, 1), "new P1 must report HOST"
        assert party_state(first) == (0, 0), "old P1 must lose HOST"
        print("  host follows P1: swap transfers the role and PONG flags")
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
        # The second phone is idle throughout these waits; refresh its lease so
        # the drain below cannot outlive PEER_TIMEOUT on a slow machine.
        second.input()
        wait_for(
            lambda: second.token in server.peers,
            message="second phone was dropped before the check",
        )
        results = first.swap_packets(1.5)
        assert any(
            kind == "result" and code == srv.SLOT_ACCEPTED and slot == 3
            for kind, _rid, code, slot in results
        ), results
        # Leaving P1 hands the host role to the next player.
        assert server.peers[second.token].slot == 0, "P1 was left empty"
        assert server.host_token == second.token

        # The input frame written before the result still names the old pad.
        first.input(buttons=1 << CROSS, want_slot=1)
        time.sleep(0.25)
        assert server.peers[first.token].slot == 2, "stale request moved the player back"
        print("  free slot ok: move, P1 host handoff, stale input ignored")
    finally:
        stop_phones(server, thread, first, second)


def test_party_lock_blocks_guests():
    """The host's lock freezes other players' seats but never their input."""
    server, port, thread, first, second = two_phone_server()
    try:
        first.set_party_lock(True)
        wait_for(lambda: server.locked, message="host lock was not applied")
        assert server.host_token == first.token

        # A locked guest cannot move, even to a free pad.
        second.input(want_slot=3)
        time.sleep(0.3)
        assert server.peers[second.token].slot == 1, "locked guest moved seats"

        # A locked guest cannot swap either; the PC answers UNAVAILABLE so the
        # pending card closes immediately instead of waiting for a timeout.
        second.drain(0.15)
        second.request_slot(1)
        results = second.swap_packets(0.8)
        assert any(
            kind == "result" and code == srv.SLOT_UNAVAILABLE
            for kind, _id, code, _slot in results
        ), results
        assert not server._swaps, "a swap was queued while locked"

        # Locking input, on the other hand, would be a bug: both pads still
        # receive button state throughout.
        first.input(buttons=1 << CROSS, want_slot=1)
        second.input(buttons=1 << CIRCLE, want_slot=2)
        wait_for(
            lambda: pad_buttons(server, server.peers[first.token]) == 1 << CROSS,
            message="host input stopped while locked",
        )
        wait_for(
            lambda: pad_buttons(server, server.peers[second.token]) == 1 << CIRCLE,
            message="guest input stopped while locked",
        )
        print("  party lock blocks guest seat changes but keeps input flowing")
    finally:
        stop_phones(server, thread, first, second)


def test_party_lock_host_and_join():
    """Moving off P1 hands the host role on; locked guests can still join."""
    server, port, thread, first, second = two_phone_server()
    third = Phone(port)
    try:
        first.set_party_lock(True)
        wait_for(lambda: server.locked, message="host lock was not applied")

        first.input(want_slot=3)
        wait_for(
            lambda: server.peers[first.token].slot == 2,
            message="locked host could not move to a free pad",
        )
        wait_for(lambda: server.slots[2].pad is not None, timeout=20.0,
                 message="host's new pad was never created")
        assert server.peers[second.token].slot == 0, "P1 was left empty"
        assert server.host_token == second.token, "host did not follow P1"

        # This is the server half of guest QR join: the code puts the phone in
        # AUTO (want_slot=0) and the PC fills the lowest free pad.
        third.token = third.discover(0)[0]
        with Keepalive([first, second, third]):
            third.input(want_slot=0)
            wait_for(lambda: third.token in server.peers, timeout=10.0,
                     message="guest did not register while locked")
            assert server.peers[third.token].slot == 1, (
                "guest did not take the remaining free pad"
            )
            wait_for(lambda: server.slots[1].pad is not None, timeout=20.0,
                     message="guest's pad was never created")

            # PONG carries lock + role after the slot byte for the phone UI.
            third.ping()
            party = None
            for data, _ in third.drain(0.6):
                magic, _ver, mtype, _seq, _tok = HEADER.unpack_from(data)
                if magic == MAGIC and mtype == srv.MSG_PONG:
                    if len(data) >= HEADER.size + 9:
                        party = (data[HEADER.size + 7], data[HEADER.size + 8])
            assert party == (1, 0), f"guest PONG party state {party}"

        assert "a=1" in srv.qr_payload("127.0.0.1", port, "PC", auto=True)
        assert "a=1" not in srv.qr_payload("127.0.0.1", port, "PC")
        assert "&auto=1" in server.web_url("127.0.0.1", auto=True)
        print("  host follows P1; guest join still takes a free pad")
    finally:
        third.close()
        stop_phones(server, thread, first, second)


def test_host_leave_unlocks():
    """A host that disconnects must not leave the party locked forever."""
    server, port, thread, first, second = two_phone_server()
    first.set_party_lock(True)
    wait_for(lambda: server.locked, message="host lock was not applied")
    server.PEER_TIMEOUT = 1.5
    first.close()
    try:
        with Keepalive([second]):
            wait_for(lambda: not server.locked, timeout=6.0,
                     message="party stayed locked after the host left")
            assert server.host_token == second.token
            assert server.peers[second.token].slot == 0, "P1 was not filled"
        print("  host disconnect releases the lock; P1 is filled")
    finally:
        second.close()
        server.running = False
        server._wake_loop()
        thread.join(timeout=3.0)


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
        test_ds4_axis_convention()
        test_max_phones_cap()
        test_pad_creation_order()
        test_requested_slot()
        test_swap_accept()
        test_p1_host_follows_swap()
        test_swap_decline()
        test_swap_cancel()
        test_swap_timeout()
        test_free_slot_request()
        test_party_lock_blocks_guests()
        test_party_lock_host_and_join()
        test_host_leave_unlocks()
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
