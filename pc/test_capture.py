"""Tests for screen-capture backend selection (Wayland tools, forced backend).

    python test_capture.py

Uses fake `grim` / `spectacle` scripts on PATH, so it needs no Wayland session.
"""

from __future__ import annotations

import io
import os
import socket
import stat
import struct
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import vpad_server as srv  # noqa: E402
from PIL import Image  # noqa: E402

FAKE_GRIM = """#!/usr/bin/env python3
import sys
w, h = 64, 48
sys.stdout.buffer.write(b"P6\\n%d %d\\n255\\n" % (w, h) + bytes([200, 30, 30]) * (w * h))
"""

FAKE_BROKEN = """#!/usr/bin/env python3
import sys
sys.stderr.write("compositor does not support this protocol\\n")
sys.exit(1)
"""


def write_tool(folder: str, name: str, body: str) -> None:
    path = os.path.join(folder, name)
    with open(path, "w") as handle:
        handle.write(body)
    os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)


class Env:
    """Temporarily replace environment variables (None removes one)."""

    def __init__(self, **values):
        self.values = values
        self.saved = {}

    def __enter__(self):
        for key, value in self.values.items():
            self.saved[key] = os.environ.get(key)
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def __exit__(self, *exc):
        for key, value in self.saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def test_session_detection() -> None:
    with Env(XDG_SESSION_TYPE="Wayland", WAYLAND_DISPLAY=None, DISPLAY=None):
        assert srv.linux_session_type() == "wayland"
    with Env(XDG_SESSION_TYPE=None, WAYLAND_DISPLAY="wayland-0", DISPLAY=":0"):
        assert srv.linux_session_type() == "wayland"
    with Env(XDG_SESSION_TYPE=None, WAYLAND_DISPLAY=None, DISPLAY=":0"):
        assert srv.linux_session_type() == "x11"
    with Env(XDG_SESSION_TYPE=None, WAYLAND_DISPLAY=None, DISPLAY=None):
        assert srv.linux_session_type() == ""
    print("  session detection ok")


def test_wayland_uses_grim() -> None:
    with tempfile.TemporaryDirectory() as bin_dir:
        write_tool(bin_dir, "grim", FAKE_GRIM)
        with Env(PATH=bin_dir + os.pathsep + os.environ["PATH"], XDG_SESSION_TYPE="wayland", LEMO_CAPTURE=None):
            capture, notes = srv.open_screen_capture()
            try:
                assert capture.name == "grim", capture.name
                img = capture.grab()
                assert img.size == (64, 48) and img.mode == "RGB", (img.size, img.mode)
                assert img.getpixel((0, 0)) == (200, 30, 30)
            finally:
                capture.close()
    print("  wayland picks grim and decodes its PPM output ok")


def test_broken_tool_is_skipped() -> None:
    with tempfile.TemporaryDirectory() as bin_dir:
        write_tool(bin_dir, "grim", FAKE_BROKEN)
        write_tool(bin_dir, "spectacle", "#!/bin/sh\nexec python3 -c \"from PIL import Image; Image.new('RGB',(40,30),(5,6,7)).save('$5')\"\n")
        with Env(PATH=bin_dir + os.pathsep + os.environ["PATH"], XDG_SESSION_TYPE="wayland", LEMO_CAPTURE=None):
            capture, notes = srv.open_screen_capture()
            try:
                assert capture.name == "spectacle", capture.name
                assert any("grim" in note and "protocol" in note for note in notes), notes
                assert capture.grab().size == (40, 30)
            finally:
                capture.close()
    print("  failing tool is skipped, next tool used ok")


def test_nothing_works_gives_hint() -> None:
    with tempfile.TemporaryDirectory() as empty:
        with Env(PATH=empty, XDG_SESSION_TYPE="wayland", LEMO_CAPTURE="grim"):
            try:
                srv.open_screen_capture()
            except RuntimeError as exc:
                assert "grim: not installed" in str(exc), exc
            else:
                raise AssertionError("expected RuntimeError")
    with Env(XDG_SESSION_TYPE="wayland", LEMO_CAPTURE="nonsense"):
        try:
            srv.open_screen_capture()
        except RuntimeError as exc:
            assert "unknown backend" in str(exc), exc
        else:
            raise AssertionError("expected RuntimeError")
    print("  missing / unknown backend reports a clear error ok")


def test_stream_through_video_server() -> None:
    """Full path: fake grim -> VideoServer -> TCP JPEG frames."""
    with tempfile.TemporaryDirectory() as bin_dir:
        write_tool(bin_dir, "grim", FAKE_GRIM)
        with Env(PATH=bin_dir + os.pathsep + os.environ["PATH"], XDG_SESSION_TYPE="wayland", LEMO_CAPTURE=None):
            port = free_port()
            video = srv.VideoServer(port, fps=8, width=320, quality=55)
            video.start()
            try:
                deadline = time.time() + 6
                while not (video.enabled and video.capture_backend) and time.time() < deadline:
                    if video.error:
                        raise SystemExit("video error: " + video.error)
                    time.sleep(0.05)
                assert video.capture_backend == "grim", video.capture_backend
                client = socket.create_connection(("127.0.0.1", port), timeout=6)
                stream = client.makefile("rb")
                for index in range(3):
                    header = stream.read(4)
                    assert len(header) == 4, f"frame {index}: short header"
                    size = struct.unpack(">I", header)[0]
                    body = stream.read(size)
                    assert len(body) == size, f"frame {index}: short body"
                    img = Image.open(io.BytesIO(body))
                    img.load()
                    assert img.format == "JPEG" and img.size == (64, 48), (img.format, img.size)
                client.close()
            finally:
                video.stop()
    print("  end-to-end stream via grim ok")


def main() -> int:
    print("lemoGamePad screen-capture backend test")
    test_session_detection()
    test_wayland_uses_grim()
    test_broken_tool_is_skipped()
    test_nothing_works_gives_hint()
    test_stream_through_video_server()
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
