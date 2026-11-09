"""Checks for the local browser gamepad.

    python test_web.py

Starts a real Server on loopback, fetches the page, and drives a virtual pad
through the WebSocket exactly as the JavaScript client does.
"""

from __future__ import annotations

import base64
import json
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import vpad_server as srv  # noqa: E402

KEY = "test-pairing-key"


def free_port() -> int:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def wait_for(predicate, timeout=4.0, message="condition not met"):
    end = time.time() + timeout
    while time.time() < end:
        if predicate():
            return True
        time.sleep(0.02)
    raise AssertionError(message)


def start_server(max_phones: int = 2):
    udp_port = free_port()
    web_port = free_port()
    server = srv.Server(
        udp_port,
        "x360",
        video_enabled=False,
        qr_enabled=False,
        max_phones=max_phones,
        web_port=web_port,
        web_key=KEY,
    )
    server.web.start()
    wait_for(lambda: server.web.enabled or server.web.error, message="web server did not start")
    if server.web.error:
        raise AssertionError(server.web.error)
    return server, web_port


def http_get(port: int, path: str) -> tuple[int, str]:
    sock = socket.create_connection(("127.0.0.1", port), timeout=3)
    sock.sendall(
        f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
        "Connection: close\r\n\r\n".encode()
    )
    data = b""
    while True:
        chunk = sock.recv(65536)
        if not chunk:
            break
        data += chunk
    sock.close()
    head, _, body = data.partition(b"\r\n\r\n")
    status = int(head.split(b"\r\n")[0].split()[1])
    return status, body.decode("utf-8", "replace")


class WebSocket:
    def __init__(self, port: int, path: str, key: str = KEY, origin: str | None = None):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        nonce = base64.b64encode(os.urandom(16)).decode("ascii")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {nonce}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
        )
        if origin is not None:
            request += f"Origin: {origin}\r\n"
        request += "\r\n"
        self.sock.sendall(request.encode("ascii"))
        self.buffer = b""
        head = self._until(b"\r\n\r\n")
        self.status = int(head.split(b"\r\n")[0].split()[1])
        if self.status != 101:
            self.sock.close()
            raise AssertionError(f"handshake returned {self.status}")

    def _read(self, size: int) -> bytes:
        while len(self.buffer) < size:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("socket closed")
            self.buffer += chunk
        data, self.buffer = self.buffer[:size], self.buffer[size:]
        return data

    def _until(self, marker: bytes) -> bytes:
        while marker not in self.buffer:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("socket closed before headers")
            self.buffer += chunk
        head, _, self.buffer = self.buffer.partition(marker)
        return head

    def send(self, message: dict) -> None:
        payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
        mask = os.urandom(4)
        header = bytearray([0x81])
        if len(payload) < 126:
            header.append(0x80 | len(payload))
        elif len(payload) < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack(">H", len(payload)))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack(">Q", len(payload)))
        header.extend(mask)
        body = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self.sock.sendall(bytes(header) + body)

    def recv(self, timeout: float = 3.0) -> dict:
        self.sock.settimeout(timeout)
        first = self._read(2)
        opcode = first[0] & 0x0F
        length = first[1] & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._read(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read(8))[0]
        payload = self._read(length)
        assert opcode == 0x1, opcode
        return json.loads(payload.decode("utf-8"))

    def close(self) -> None:
        try:
            self.sock.sendall(bytes([0x88, 0x80]) + os.urandom(4))
            self.sock.close()
        except OSError:
            pass


def test_page_and_key(server, port):
    status, body = http_get(port, f"/?k={KEY}")
    assert status == 200, status
    assert "browser pad" in body, body[:120]
    assert "lemoGamePad" in body
    assert "http://" not in body and "https://" not in body, "page must not load remote assets"

    status, _body = http_get(port, "/")
    assert status == 401, status

    # The browser requests relative assets without the page's query string, so
    # style.css/app.js must load without a key or the UI stays unstyled and the
    # WebSocket client never runs.
    status, css = http_get(port, "/style.css")
    assert status == 200 and ".stick" in css, status
    status, script = http_get(port, "/app.js")
    assert status == 200 and "WebSocket" in script, status
    status, icon = http_get(port, "/favicon.svg")
    assert status == 200 and "<svg" in icon, status
    print("  page/key ok: page needs the key, relative assets load")


def test_input_ping_and_rumble(server, port):
    client = WebSocket(port, f"/ws?k={KEY}")
    try:
        client.send({"t": "i", "b": 1 << 14, "lx": 12345, "ly": -8000,
                     "rx": 0, "ry": 0, "l2": 0, "r2": 0})
        wait_for(lambda: len(server.peers) == 1, message="browser peer was not registered")
        peer = next(iter(server.peers.values()))
        assert peer.label == "browser", peer.label
        assert peer.slot == 0, peer.slot
        assert peer.web_send is not None

        wait_for(lambda: peer.token in server._pending, message="input was not queued")
        queued = server._pending[peer.token][1]
        assert queued.pressed("cross"), queued.buttons
        assert queued.lx == 12345 and queued.ly == -8000, (queued.lx, queued.ly)

        message = client.recv()
        assert message == {"t": "s", "n": 1}, message

        client.send({"t": "p", "c": 123456})
        pong = client.recv()
        assert pong["t"] == "q" and pong["c"] == 123456 and pong["n"] == 1, pong

        server._slot_rumble(server.slots[0], 10, 200)
        rumble = client.recv()
        assert rumble == {"t": "r", "s": 10, "l": 200}, rumble
        print("  websocket ok: input, slot, ping and rumble")
    finally:
        client.close()

    wait_for(lambda: not server.peers, message="browser peer was not released")


def test_clamp(server, port):
    client = WebSocket(port, f"/ws?k={KEY}")
    try:
        client.send({"t": "i", "b": 999999, "lx": 999999, "ly": -999999,
                     "rx": -999999, "ry": 999999, "l2": 999, "r2": -5})
        wait_for(lambda: len(server.peers) == 1, message="browser peer was not registered")
        peer = next(iter(server.peers.values()))
        wait_for(lambda: peer.token in server._pending, message="input was not queued")
        queued = server._pending[peer.token][1]
        assert queued.buttons == 0x1FFFF, queued.buttons
        assert queued.lx == 32767 and queued.ly == -32768, (queued.lx, queued.ly)
        assert queued.rx == -32768 and queued.ry == 32767, (queued.rx, queued.ry)
        assert queued.l2 == 255 and queued.r2 == 0, (queued.l2, queued.r2)
        print("  clamp ok: out-of-range values are bounded")
    finally:
        client.close()


def test_wrong_key_and_origin(server, port):
    try:
        WebSocket(port, "/ws?k=wrong")
        raise AssertionError("wrong key was accepted")
    except AssertionError as exc:
        assert "401" in str(exc), exc

    try:
        WebSocket(port, f"/ws?k={KEY}", origin="http://evil.example")
        raise AssertionError("cross-site origin was accepted")
    except AssertionError as exc:
        assert "403" in str(exc), exc
    print("  security ok: wrong key and cross-site origin are rejected")


def test_want_slot(server, port):
    client = WebSocket(port, f"/ws?k={KEY}")
    try:
        client.send({"t": "i", "b": 0, "lx": 0, "ly": 0, "rx": 0, "ry": 0,
                     "l2": 0, "r2": 0, "w": 2})
        wait_for(lambda: len(server.peers) == 1, message="browser peer was not registered")
        peer = next(iter(server.peers.values()))
        assert peer.slot == 1, peer.slot
        assert peer.name == "browser 2", peer.name
        print("  slot ok: a browser can request P2")
    finally:
        client.close()


def main() -> int:
    server, port = start_server()
    try:
        test_page_and_key(server, port)
        test_input_ping_and_rumble(server, port)
        test_clamp(server, port)
        test_wrong_key_and_origin(server, port)
        test_want_slot(server, port)
    finally:
        server.shutdown()
    print("web pad checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
