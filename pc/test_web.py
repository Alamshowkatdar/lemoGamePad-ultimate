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
import threading
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

    def recv_raw(self, timeout: float = 3.0) -> tuple[int, bytes]:
        self.sock.settimeout(timeout)
        first = self._read(2)
        opcode = first[0] & 0x0F
        length = first[1] & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._read(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read(8))[0]
        return opcode, self._read(length)

    def recv(self, timeout: float = 3.0) -> dict:
        opcode, payload = self.recv_raw(timeout)
        assert opcode == 0x1, opcode
        return json.loads(payload.decode("utf-8"))

    def recv_binary(self, timeout: float = 3.0) -> bytes:
        """Skip status messages and return the next binary screen frame."""
        while True:
            opcode, payload = self.recv_raw(timeout)
            if opcode == 0x2:
                return payload
            assert opcode == 0x1, opcode

    def close(self) -> None:
        try:
            self.sock.sendall(bytes([0x88, 0x80]) + os.urandom(4))
            self.sock.close()
        except OSError:
            pass


class FakeVideoServer:
    """A tiny TCP JPEG source that speaks the LGVQ handshake."""

    FRAME = b"\xff\xd8fake-jpeg-frame\xff\xd9"

    def __init__(self):
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(4)
        self.port = self.sock.getsockname()[1]
        self.qualities: list[int] = []
        self.frames = 0
        self._stop = threading.Event()
        self.thread = threading.Thread(target=self._run, name="fake-video", daemon=True)
        self.thread.start()

    def _recv(self, conn: socket.socket, size: int) -> bytes:
        data = b""
        while len(data) < size:
            chunk = conn.recv(size - len(data))
            if not chunk:
                raise EOFError
            data += chunk
        return data

    def _run(self) -> None:
        self.sock.settimeout(0.2)
        while not self._stop.is_set():
            try:
                conn, _addr = self.sock.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            try:
                self._serve(conn)
            except (OSError, EOFError):
                pass
            finally:
                try:
                    conn.close()
                except OSError:
                    pass

    def _serve(self, conn: socket.socket) -> None:
        conn.settimeout(2.0)
        handshake = self._recv(conn, 5)
        if handshake[:4] != b"LGVQ":
            return
        quality = handshake[4]
        self.qualities.append(quality)
        conn.sendall(struct.pack(">iB", -1, quality))
        while not self._stop.is_set():
            conn.sendall(struct.pack(">I", len(self.FRAME)) + self.FRAME)
            self.frames += 1
            time.sleep(0.02)

    def stop(self) -> None:
        self._stop.set()
        try:
            self.sock.close()
        except OSError:
            pass
        if self.thread.is_alive():
            self.thread.join(timeout=1.0)


def start_screen_web(video: FakeVideoServer):
    port = free_port()
    web = srv.WebGamepadServer(
        port,
        KEY,
        on_input=lambda *_args: 0,
        on_ping=lambda *_args: 0,
        on_close=lambda *_args: None,
        on_token=lambda: 1,
        log=lambda _message: None,
        server_name="Test PC",
        max_players=4,
        screen_info=lambda: {
            "host": "127.0.0.1",
            "port": video.port,
            "fps": 24,
            "width": 1024,
        },
    )
    web.start()
    wait_for(lambda: web.enabled or web.error, message="screen web server did not start")
    if web.error:
        raise AssertionError(web.error)
    return web, port


def test_page_and_key(server, port):
    status, body = http_get(port, f"/?k={KEY}")
    assert status == 200, status
    assert "browser pad" in body, body[:120]
    assert "lemoGamePad" in body
    assert "http://" not in body and "https://" not in body, "page must not load remote assets"

    status, _body = http_get(port, "/")
    assert status == 401, status

    for marker in (
        "theme-grid",
        "slot-row",
        "screen-pip",
        "data-faces",
        "fullscreen-toggle",
        "dialog-scrim",
    ):
        assert marker in body, f"page is missing {marker}"

    # The browser requests relative assets without the page's query string, so
    # style.css/app.js must load without a key or the UI stays unstyled and the
    # WebSocket client never runs.
    status, css = http_get(port, "/style.css")
    assert status == 200 and "#pad-canvas" in css and ".swatch" in css, status
    status, script = http_get(port, "/app.js")
    assert status == 200 and "WebSocket" in script, status
    assert "createImageBitmap" in script and "lmgb.layouts" in script
    assert "requestFullscreen" in script and "fullscreenchange" in script
    status, icon = http_get(port, "/favicon.svg")
    assert status == 200 and "<svg" in icon, status
    print("  page/key ok: page needs the key, relative assets load")


def test_input_ping_and_rumble(server, port):
    client = WebSocket(port, f"/ws?k={KEY}")
    try:
        hello = client.recv()
        assert hello["t"] == "h", hello
        assert hello["max"] == 2, hello
        assert isinstance(hello["n"], str) and hello["n"], hello
        assert hello["screen"] is False, hello

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


def test_screen_unavailable(server, port):
    client = WebSocket(port, f"/screen?k={KEY}")
    try:
        message = client.recv()
        assert message["t"] == "e", message
        assert "not available" in message["m"], message
        print("  screen off ok: the relay reports a missing video stream")
    finally:
        client.close()


def test_screen_auth(server, port):
    try:
        WebSocket(port, "/screen?k=wrong")
        raise AssertionError("wrong key was accepted for the screen relay")
    except AssertionError as exc:
        assert "401" in str(exc), exc

    try:
        WebSocket(port, f"/screen?k={KEY}", origin="http://evil.example")
        raise AssertionError("cross-site origin was accepted for the screen relay")
    except AssertionError as exc:
        assert "403" in str(exc), exc
    print("  screen security ok: key and origin are checked")


def test_screen_stream():
    video = FakeVideoServer()
    web, port = start_screen_web(video)
    client = None
    try:
        client = WebSocket(port, f"/screen?k={KEY}&q=1")
        connecting = client.recv()
        assert connecting["t"] == "v" and connecting["s"] == "connecting", connecting
        assert connecting["q"] == 1, connecting
        assert connecting["fps"] == 24 and connecting["w"] == 1024, connecting

        live = client.recv()
        assert live["t"] == "v" and live["s"] == "live", live
        assert live["q"] == 1, live

        frame = client.recv_binary()
        assert frame == FakeVideoServer.FRAME, frame
        assert 1 in video.qualities, video.qualities

        client.send({"t": "q", "q": 0})
        wait_for(lambda: 0 in video.qualities, message="quality change was not forwarded")

        deadline = time.time() + 4.0
        switched = False
        while time.time() < deadline:
            opcode, payload = client.recv_raw(timeout=1.0)
            if opcode != 0x1:
                continue
            message = json.loads(payload.decode("utf-8"))
            if message.get("t") == "v" and message.get("s") == "live" and message.get("q") == 0:
                switched = True
                break
        assert switched, "relay did not report the new quality"
        print("  screen ok: relay negotiates quality and forwards JPEG frames")
    finally:
        if client is not None:
            client.close()
        web.stop()
        video.stop()


def test_browser_input_wakes_loop():
    """Browser input must not wait for the UDP select() timeout."""
    udp_port = free_port()
    web_port = free_port()
    server = srv.Server(
        udp_port,
        "x360",
        video_enabled=False,
        qr_enabled=False,
        max_phones=1,
        web_port=web_port,
        web_key=KEY,
    )
    server._print_banner = lambda: None
    server._status = lambda: None

    flushes = []
    original = server._flush_inputs

    def wrapped(now):
        had_input = bool(server._pending)
        original(now)
        flushes.append((time.perf_counter(), had_input))

    server._flush_inputs = wrapped
    thread = threading.Thread(target=server.run, name="web-wake-test", daemon=True)
    thread.start()
    client = None
    try:
        wait_for(lambda: server.web.enabled or server.web.error,
                 message="web did not start")
        if server.web.error:
            raise AssertionError(server.web.error)
        client = WebSocket(web_port, f"/ws?k={KEY}")
        client.recv()  # hello

        # Wait for a flush so the loop is parked in select(), then send. The
        # old loop only flushed on its 250 ms timeout.
        wait_for(lambda: flushes, timeout=2.0, message="server loop did not start")
        flushes.clear()
        sent = time.perf_counter()
        client.send({"t": "i", "b": 1 << 14, "lx": 0, "ly": 0,
                     "rx": 0, "ry": 0, "l2": 0, "r2": 0})
        wait_for(
            lambda: any(had and stamp >= sent for stamp, had in flushes),
            timeout=1.0,
            message="browser input was not flushed",
        )
        flushed = min(stamp for stamp, had in flushes if had and stamp >= sent)
        elapsed = (flushed - sent) * 1000.0
        assert elapsed < 180.0, f"browser input waited {elapsed:.0f} ms"
        print(f"  wake ok: browser input flushed in {elapsed:.0f} ms")
    finally:
        if client is not None:
            client.close()
        server.running = False
        server._wake_loop()
        thread.join(timeout=3.0)


def main() -> int:
    server, port = start_server()
    try:
        test_page_and_key(server, port)
        test_input_ping_and_rumble(server, port)
        test_clamp(server, port)
        test_wrong_key_and_origin(server, port)
        test_want_slot(server, port)
        test_screen_unavailable(server, port)
        test_screen_auth(server, port)
    finally:
        server.shutdown()
    test_screen_stream()
    test_browser_input_wakes_loop()
    print("web pad checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
