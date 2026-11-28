from __future__ import annotations

import json
import os
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock

import desktop_gui as gui


STATUS = {
    "running": True,
    "port": 9876,
    "max_phones": 8,
    "pad": "x360",
    "hostname": "TEST-PC",
    "video_port": 9877,
    "web_port": 9878,
    "web_key": "KEY",
    "locked": True,
    "host_slot": 1,
    "problems": [],
    "peers": [
        {
            "slot": 1,
            "token": 7,
            "name": "phone 1",
            "ip": "192.168.0.20",
            "rate": 249.7,
            "lost": 3,
            "battery": 88,
            "host": True,
        },
        {
            "slot": 2,
            "token": 8,
            "name": "browser 1",
            "ip": "192.168.0.21",
            "rate": 60.0,
            "lost": 0,
            "battery": 255,
            "host": False,
        },
    ],
}


class DesktopGuiTest(unittest.TestCase):
    def test_theme_palette(self):
        for name in ("dark", "light"):
            palette = gui.theme_palette(name)
            for key in (
                "bg",
                "sidebar",
                "surface",
                "accent",
                "success",
                "danger",
                "border_soft",
                "shadow",
                "qr_bg",
            ):
                self.assertIn(key, palette)
        self.assertNotEqual(
            gui.theme_palette("dark")["bg"], gui.theme_palette("light")["bg"]
        )
        self.assertEqual(gui.status_tone("Running"), "success")
        self.assertEqual(gui.status_tone("Starting…"), "warn")
        self.assertEqual(gui.status_tone("Stopped"), "muted")
        self.assertEqual(gui.status_tone("Error"), "danger")

    def test_ports_and_validation(self):
        config = gui.GuiConfig()
        self.assertEqual(gui.resolved_ports(config), (9877, 9878))
        self.assertIsNone(gui.validate_config(config))

        bad = replace(config, web_port=9877)
        self.assertIn("screen stream", gui.validate_config(bad) or "")
        bad = replace(config, port=0)
        self.assertIn("control port", gui.validate_config(bad) or "")
        bad = replace(config, pad="n64")
        self.assertIn("pad type", gui.validate_config(bad) or "")
        bad = replace(config, web_key="K", web_key_file="key.txt")
        self.assertIn("either", gui.validate_config(bad) or "")

    def test_server_arguments(self):
        args = gui.server_arguments(gui.GuiConfig())
        self.assertIn("--port", args)
        self.assertIn("--no-qr", args)
        self.assertNotIn("--no-rumble", args)
        args = gui.server_arguments(
            gui.GuiConfig(port=19876, no_video=True, no_web=True, no_rumble=True)
        )
        self.assertEqual(args[args.index("--port") + 1], "19876")
        self.assertIn("--no-video", args)
        self.assertIn("--no-web", args)
        self.assertIn("--no-rumble", args)

    def test_server_binary_override(self):
        with mock.patch.dict(
            os.environ, {"LEMO_GAMEPAD_SERVER": "/tmp/fake-lemoGamePad-server"}
        ):
            self.assertEqual(
                gui.server_binary(), Path("/tmp/fake-lemoGamePad-server")
            )

    def test_pairing_options(self):
        options = dict(gui.pairing_options(STATUS, gui.GuiConfig(), "192.168.0.102"))
        self.assertTrue(options["App"].startswith("vpad://connect?"))
        self.assertIn("n=TEST-PC", options["App"])
        self.assertIn("a=1", options["App guest"])
        self.assertEqual(options["Browser"], "http://192.168.0.102:9878/?k=KEY")
        self.assertTrue(options["Browser guest"].endswith("&auto=1"))
        self.assertEqual(gui.pairing_options(None, gui.GuiConfig(), "192.168.0.102"), [])

    def test_status_snapshot(self):
        snapshot = gui.status_snapshot(STATUS, gui.GuiConfig())
        self.assertEqual(snapshot["state"], "Running")
        self.assertEqual(snapshot["players"], "2/8")
        self.assertEqual(snapshot["host"], "P1")
        self.assertEqual(snapshot["party"], "Locked")
        self.assertEqual(snapshot["rows"][0][0], "P1")
        self.assertEqual(snapshot["rows"][0][5], "HOST")
        self.assertEqual(snapshot["rows"][1][4], "?")
        self.assertIn("UDP 9876", snapshot["ports"])
        self.assertIn("web TCP 9878", snapshot["ports"])
        stopped = gui.status_snapshot(None, gui.GuiConfig())
        self.assertEqual(stopped["state"], "Stopped")
        self.assertEqual(stopped["players"], "0/8")

    def test_read_server_status(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "status.json"
            self.assertIsNone(gui.read_server_status(path))
            path.write_text(json.dumps(STATUS), encoding="utf-8")
            self.assertEqual(gui.read_server_status(path)["port"], 9876)
            path.write_text("not json", encoding="utf-8")
            self.assertIsNone(gui.read_server_status(path))

    def test_version_comparison(self):
        self.assertEqual(gui.version_tuple("v2.2.5"), (2, 2, 5))
        self.assertTrue(gui.is_newer_version("v2.2.5", "2.2.1"))
        self.assertFalse(gui.is_newer_version("2.2.1", "2.2.1"))
        self.assertFalse(gui.is_newer_version("2.2", "2.2.1"))
        self.assertEqual(
            gui.release_url("v2.2.5"),
            "https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases/tag/v2.2.5",
        )

    def test_fetch_latest_release(self):
        class FakeResponse:
            def __init__(self, data: bytes) -> None:
                self.data = data

            def read(self) -> bytes:
                return self.data

            def __enter__(self):
                return self

            def __exit__(self, *_args) -> bool:
                return False

        payload = json.dumps(
            {
                "tag_name": "v2.2.6",
                "name": "2.2.6",
                "html_url": "https://example.invalid/not-trusted",
            }
        ).encode()
        with mock.patch.object(
            gui.urllib.request, "urlopen", return_value=FakeResponse(payload)
        ) as urlopen:
            info = gui.fetch_latest_release()
        self.assertEqual(info["version"], "2.2.6")
        self.assertEqual(info["url"], gui.release_url("v2.2.6"))
        self.assertNotIn("example.invalid", info["url"])
        self.assertEqual(urlopen.call_args.args[0].full_url, gui.RELEASES_API)

        with mock.patch.object(
            gui.urllib.request, "urlopen", side_effect=OSError("offline")
        ):
            self.assertIsNone(gui.fetch_latest_release())

    def test_packaging_entrypoints(self):
        pc_dir = Path(__file__).resolve().parent
        self.assertNotIn("vpad_server", (pc_dir / "desktop_gui.py").read_text())
        for name in ("build_exe.ps1", "build_linux.sh"):
            self.assertIn("cargo build --release", (pc_dir / name).read_text())
        self.assertNotIn("vpad_server.py", (pc_dir / "linux/run.sh").read_text())
        self.assertTrue((pc_dir / "gui.sh").is_file())
        self.assertTrue((pc_dir / "assets" / "app-icon.png").is_file())
        for name in ("build_exe.ps1", "build_linux.sh"):
            text = (pc_dir / name).read_text()
            self.assertIn("lemoGamePad-app", text)
            self.assertIn("cargo build --release", text)
        rust = pc_dir.parent / "rust"
        self.assertTrue((rust / "src/gui/main.rs").is_file())
        self.assertIn("lemoGamePad-app", (pc_dir / "gui.sh").read_text())
        cargo = (rust / "Cargo.toml").read_text()
        self.assertIn('name = "lemoGamePad-app"', cargo)
        self.assertIn("eframe", cargo)
        workflow = (pc_dir.parent / ".github/workflows/release-android.yml").read_text()
        self.assertIn("target/release/lemoGamePad-app", workflow)
        self.assertIn("target/release/lemoGamePad-app.exe", workflow)
        self.assertIn("src/gui/main.rs", workflow)


if __name__ == "__main__":
    unittest.main()
