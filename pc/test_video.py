"""Smoke test for the screen stream and QR pairing output.

    python test_video.py

Starts a video server on a free local port, reads three JPEG frames over TCP,
verifies they decode, then writes a QR PNG to a temporary folder.
"""

from __future__ import annotations

import io
import os
import socket
import struct
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import vpad_server as srv  # noqa: E402
from PIL import Image  # noqa: E402


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def test_video() -> None:
    port = free_port()
    video = srv.VideoServer(port, fps=8, width=320, quality=55)
    video.start()
    try:
        deadline = time.time() + 6
        while not video.enabled and time.time() < deadline:
            if video.error:
                raise SystemExit("video error: " + video.error)
            time.sleep(0.05)
        if not video.enabled:
            raise SystemExit("video did not start: " + (video.error or "timeout"))

        client = socket.create_connection(("127.0.0.1", port), timeout=6)
        stream = client.makefile("rb")
        frame = b""
        for index in range(3):
            header = stream.read(4)
            if len(header) < 4:
                raise SystemExit(f"frame {index}: short length header")
            size = struct.unpack(">I", header)[0]
            if size <= 0 or size > 8 * 1024 * 1024:
                raise SystemExit(f"frame {index}: bad size {size}")
            frame = stream.read(size)
            if len(frame) != size:
                raise SystemExit(f"frame {index}: short body")
        client.close()

        img = Image.open(io.BytesIO(frame))
        img.load()
        assert img.format == "JPEG", img.format
        assert img.width <= 320, img.size
        print(f"  video ok: {len(frame)} byte JPEG, {img.width}x{img.height}")
    finally:
        video.stop()


def test_multi_viewer() -> None:
    """Two phones watch at once; a third viewer is refused."""
    port = free_port()
    video = srv.VideoServer(port, fps=8, width=320, quality=55, max_viewers=2)
    video.start()
    clients = []
    extra = None
    try:
        deadline = time.time() + 6
        while not video.enabled and time.time() < deadline:
            if video.error:
                raise SystemExit("video error: " + video.error)
            time.sleep(0.05)
        if not video.enabled:
            raise SystemExit("video did not start: " + (video.error or "timeout"))

        for _ in range(2):
            clients.append(socket.create_connection(("127.0.0.1", port), timeout=6))

        deadline = time.time() + 5
        while video.viewers < 2 and time.time() < deadline:
            time.sleep(0.05)
        assert video.viewers == 2, f"only {video.viewers} viewers registered"

        for index, client in enumerate(clients):
            stream = client.makefile("rb")
            header = stream.read(4)
            if len(header) < 4:
                raise SystemExit(f"viewer {index}: short length header")
            size = struct.unpack(">I", header)[0]
            body = stream.read(size)
            if len(body) != size:
                raise SystemExit(f"viewer {index}: short frame")
            stream.close()
            Image.open(io.BytesIO(body)).load()

        extra = socket.create_connection(("127.0.0.1", port), timeout=6)
        extra.settimeout(3)
        if extra.recv(4) != b"":
            raise SystemExit("the third viewer should have been refused")
        print("  multi-viewer ok: two phones stream at once, third refused")
    finally:
        for client in clients:
            client.close()
        if extra is not None:
            extra.close()
        video.stop()


def test_qr() -> None:
    payload = srv.qr_payload("10.0.0.5", 9877, "my pc")
    assert "h=10.0.0.5" in payload and "p=9877" in payload and "n=my+pc" in payload
    print(f"  payload ok: {payload}")

    with tempfile.TemporaryDirectory() as folder:
        srv.print_qr("192.168.1.20", 9876, "desk", folder)
        pngs = [name for name in os.listdir(folder) if name.endswith(".png")]
        assert pngs, "no QR png written"
        path = os.path.join(folder, pngs[0])
        img = Image.open(path)
        img.load()
        assert img.width > 50 and img.height > 50, img.size
        print(f"  qr ok: {pngs[0]} {img.width}x{img.height}")


def main() -> int:
    print("lemoGamePad video + QR test")
    test_video()
    test_multi_viewer()
    test_qr()
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
