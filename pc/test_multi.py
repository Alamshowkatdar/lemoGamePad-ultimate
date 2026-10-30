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

    def input(self, buttons=0, lx=0, ly=0, rx=0, ry=0, l2=0, r2=0, battery=90):
        packet = INPUT.pack(
            MAGIC, srv.VERSION, srv.MSG_INPUT, self.seq & 0xFFFF, self.token,
            buttons, lx, ly, rx, ry, l2, r2, battery, 0,
        )
        self.sock.sendto(packet, self.addr)
        self.seq += 1

    def ping(self):
        packet = HEADER.pack(
            MAGIC, srv.VERSION, srv.MSG_PING, self.seq & 0xFFFF, self.token
        ) + struct.pack("<I", 1234)
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

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def pad_of(server, peer):
    slot = server.slots[peer.slot]
    assert slot.peer is peer
    return slot.pad


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
            lambda: pad_of(server, first_peer)._last.buttons == 1 << CROSS,
            message="first pad did not receive its input",
        )
        wait_for(
            lambda: pad_of(server, second_peer)._last.buttons == 1 << CIRCLE,
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
    second_peer = server.peers[second.token]
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
