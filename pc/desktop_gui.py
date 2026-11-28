"""Desktop control panel for the native lemoGamePad receiver.

The GUI is a thin shell around the Rust receiver. It starts and stops the
process, reads its local status snapshot, and renders the same QR payloads the
console prints. Tkinter is imported only in :func:`main`, so the pure helpers in
this module can be tested on a headless machine.
"""

from __future__ import annotations

import json
import os
import platform
import queue
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Optional


MAX_PHONES = 8
APP_VERSION = "2.2.2"
REPO_OWNER = "Alamshowkatdar"
REPO_NAME = "lemoGamePad-ultimate"
RELEASES_API = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest"
RELEASES_PAGE = f"https://github.com/{REPO_OWNER}/{REPO_NAME}/releases"
UPDATE_TIMEOUT = 6


THEMES: dict[str, dict[str, str]] = {
    "dark": {
        "bg": "#0B1220",
        "sidebar": "#101A2B",
        "surface": "#172338",
        "surface2": "#1F2D45",
        "entry": "#121C2C",
        "border": "#2A3A54",
        "border_soft": "#33445F",
        "shadow": "#050A12",
        "text": "#EDF3FB",
        "muted": "#B8C6D9",
        "accent": "#5B9CFA",
        "accent2": "#7A6DF0",
        "success": "#49C88D",
        "warn": "#E8B85C",
        "danger": "#EE7784",
        "qr_bg": "#FFFFFF",
        "qr_text": "#1E293B",
        "log_bg": "#0B1422",
        "on_success": "#062218",
        "on_warn": "#2A1E08",
        "on_danger": "#2A0C12",
        "on_accent": "#FFFFFF",
    },
    "light": {
        "bg": "#F2F5FA",
        "sidebar": "#FFFFFF",
        "surface": "#FFFFFF",
        "surface2": "#EEF3F9",
        "entry": "#F8FAFD",
        "border": "#D9E2EE",
        "border_soft": "#E6ECF4",
        "shadow": "#8393A8",
        "text": "#1E2A3B",
        "muted": "#7284A0",
        "accent": "#3B82F6",
        "accent2": "#7C6EF0",
        "success": "#16A46A",
        "warn": "#D9992A",
        "danger": "#E45D6A",
        "qr_bg": "#FFFFFF",
        "qr_text": "#1E293B",
        "log_bg": "#F8FAFD",
        "on_success": "#FFFFFF",
        "on_warn": "#FFFFFF",
        "on_danger": "#FFFFFF",
        "on_accent": "#FFFFFF",
    },
}


def theme_palette(name: str) -> dict[str, str]:
    """Return a copy of the named colour palette."""
    return dict(THEMES.get(name, THEMES["dark"]))


def status_tone(state: str) -> str:
    """Map a status label to a theme colour role."""
    if state == "Running":
        return "success"
    if state in ("Starting…", "Stopping…"):
        return "warn"
    if state == "Error":
        return "danger"
    return "muted"


def version_tuple(value: str) -> tuple[int, ...]:
    """Turn a release tag such as ``v2.1.6`` into a comparable tuple."""
    parts = re.findall(r"\d+", str(value))
    return tuple(int(part) for part in parts) if parts else (0,)


def is_newer_version(latest: str, current: str = APP_VERSION) -> bool:
    """True when *latest* is a newer release than *current*."""
    return version_tuple(latest) > version_tuple(current)


def release_url(tag: str) -> str:
    """Build a release page URL from a GitHub tag."""
    return f"{RELEASES_PAGE}/tag/{urllib.parse.quote(tag, safe='.-_')}"


def fetch_latest_release(timeout: float = UPDATE_TIMEOUT) -> Optional[dict[str, str]]:
    """Check GitHub once, returning only genuinely newer releases.

    Network and parsing failures are deliberately swallowed: a missing update
    notification must never stop the desktop panel from starting.
    """
    request = urllib.request.Request(
        RELEASES_API,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "lemoGamePad-desktop",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8", "replace"))
        tag = str(payload.get("tag_name") or "").strip()
        if not tag or not is_newer_version(tag):
            return None
        return {
            "tag": tag,
            "version": tag.lstrip("v"),
            "name": str(payload.get("name") or "").strip(),
            "url": release_url(tag),
        }
    except Exception:
        return None


def ui_font() -> str:
    """A clean UI font that exists on each supported desktop."""
    if sys.platform == "win32":
        return "Segoe UI"
    if sys.platform == "darwin":
        return "Helvetica"
    return "DejaVu Sans"


def mono_font() -> str:
    """A monospace font for the log pane."""
    if sys.platform == "win32":
        return "Consolas"
    if sys.platform == "darwin":
        return "Menlo"
    return "DejaVu Sans Mono"


@dataclass
class GuiConfig:
    """The settings exposed by the desktop panel."""

    port: int = 9876
    pad: str = "x360"
    max_phones: int = 8
    no_rumble: bool = False
    no_video: bool = False
    video_port: Optional[int] = None
    no_web: bool = False
    web_port: Optional[int] = None
    web_key: str = ""
    web_key_file: str = ""
    video_fps: int = 60
    video_width: int = 480
    video_quality: int = 42
    no_qr: bool = True
    no_install_deps: bool = False
    check_updates: bool = True


def resolved_ports(config: GuiConfig) -> tuple[Optional[int], Optional[int]]:
    """Return the effective screen and browser TCP ports."""
    video_port = None if config.no_video else (config.video_port or config.port + 1)
    web_port = None if config.no_web else (config.web_port or config.port + 2)
    return video_port, web_port


def validate_config(config: GuiConfig) -> Optional[str]:
    """Return a human-readable error, or None when the settings are usable."""
    if config.pad not in ("x360", "ds4"):
        return "pad type must be x360 or ds4"
    if not 1 <= config.port <= 65535:
        return "control port must be between 1 and 65535"
    if not 1 <= config.max_phones <= MAX_PHONES:
        return f"players must be between 1 and {MAX_PHONES}"
    if not 1 <= config.video_fps <= 60:
        return "screen FPS must be between 1 and 60"
    if not 160 <= config.video_width <= 1280:
        return "screen width must be between 160 and 1280"
    if not 20 <= config.video_quality <= 90:
        return "screen quality must be between 20 and 90"

    video_port, web_port = resolved_ports(config)
    if video_port is not None and not 1 <= video_port <= 65535:
        return "screen port must be between 1 and 65535"
    if web_port is not None and not 1 <= web_port <= 65535:
        return "browser pad port must be between 1 and 65535"
    if video_port is not None and video_port > 65535:
        return "the default screen port would be 65536; lower the control port"
    if web_port is not None and web_port > 65535:
        return "the default browser port would be 65536; lower the control port"
    if web_port is not None and video_port is not None and web_port == video_port:
        return "browser pad port must differ from the screen stream port"
    if web_port is not None and web_port == config.port:
        return "browser pad port must differ from the control port"
    if config.web_key and config.web_key_file:
        return "use either a fixed browser key or a key file, not both"
    return None


def hostname() -> str:
    """Name embedded in QR payloads, matching the receiver's own label."""
    return platform.node() or "PC"


def local_ips() -> list[str]:
    """Best-effort LAN IPv4 addresses without any third-party dependency."""
    addresses: list[str] = []
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("198.51.100.1", 53))
        primary = probe.getsockname()[0]
        if primary and primary != "0.0.0.0":
            addresses.append(primary)
    except OSError:
        pass
    finally:
        probe.close()
    try:
        discovered = {
            info[4][0]
            for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
            if info[4] and info[4][0] != "0.0.0.0"
        }
    except OSError:
        discovered = set()
    for address in sorted(discovered):
        if address not in addresses:
            addresses.append(address)
    return addresses


def qr_payload(host: str, port: int, name: str, auto: bool = False) -> str:
    """Pairing URI used by both the console receiver and the GUI."""
    query = urllib.parse.urlencode({"h": host, "p": port, "n": name})
    return f"vpad://connect?{query}" + ("&a=1" if auto else "")


def web_url(ip: str, port: int, key: str, auto: bool = False) -> str:
    """Browser pad link; query order is part of the existing protocol."""
    url = f"http://{ip}:{port}/?k={urllib.parse.quote(key, safe='-_')}"
    return f"{url}&auto=1" if auto else url


def server_binary() -> Path:
    """Locate the Rust receiver shipped beside (or near) the GUI."""
    override = os.environ.get("LEMO_GAMEPAD_SERVER", "")
    if override:
        return Path(override)
    name = "lemoGamePad-server.exe" if sys.platform == "win32" else "lemoGamePad-server"
    candidates = [
        Path(sys.executable).resolve().parent / name,
        Path(__file__).resolve().parent / name,
        Path(__file__).resolve().parent.parent / "dist" / name,
        Path(__file__).resolve().parent.parent / "rust" / "target" / "release" / name,
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    found = shutil.which(name)
    if found:
        return Path(found)
    raise FileNotFoundError(
        f"Could not find {name}. Put the Rust receiver next to the desktop app "
        "or set LEMO_GAMEPAD_SERVER."
    )


def server_arguments(config: GuiConfig) -> list[str]:
    """Translate panel settings into native receiver CLI flags."""
    error = validate_config(config)
    if error:
        raise ValueError(error)
    args = [
        "--port", str(config.port),
        "--pad", config.pad,
        "--max-phones", str(config.max_phones),
        "--video-fps", str(config.video_fps),
        "--video-width", str(config.video_width),
        "--video-quality", str(config.video_quality),
        "--no-qr",
    ]
    if config.no_rumble:
        args.append("--no-rumble")
    if config.no_video:
        args.append("--no-video")
    elif config.video_port:
        args += ["--video-port", str(config.video_port)]
    if config.no_web:
        args.append("--no-web")
    elif config.web_port:
        args += ["--web-port", str(config.web_port)]
    if config.web_key:
        args += ["--web-key", config.web_key]
    if config.web_key_file:
        args += ["--web-key-file", config.web_key_file]
    if config.no_install_deps:
        args.append("--no-install-deps")
    return args


def read_server_status(path: Path) -> Optional[dict[str, Any]]:
    """Read the receiver's local status snapshot; failures are non-fatal."""
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return payload if isinstance(payload, dict) else None


def status_snapshot(
    status: Optional[dict[str, Any]],
    config: GuiConfig,
) -> dict[str, Any]:
    """Flatten a receiver status snapshot for the status widgets."""
    if not status or not status.get("running"):
        return {
            "state": "Stopped",
            "players": f"0/{config.max_phones}",
            "pad": f"{config.pad.upper()} x{config.max_phones}",
            "party": "Open",
            "host": "—",
            "ports": "—",
            "problems": "",
            "rows": [],
        }
    peers = status.get("peers") or []
    host_slot = status.get("host_slot")
    rows = []
    for peer in sorted(peers, key=lambda item: int(item.get("slot", 0))):
        battery = f"{peer.get('battery', 255)}%" if peer.get("battery", 255) != 255 else "?"
        loss = str(peer.get("lost", 0)) if peer.get("lost", 0) else "—"
        rows.append(
            (
                f"P{peer.get('slot', 0)}",
                f"{peer.get('name', 'player')} @ {peer.get('ip', '?')}",
                f"{float(peer.get('rate', 0)):.0f}/s",
                loss,
                battery,
                "HOST" if peer.get("slot") == host_slot else "",
            )
        )
    ports = [f"UDP {status.get('port', config.port)}"]
    if status.get("video_port"):
        ports.append(f"screen TCP {status['video_port']}")
    if status.get("web_port"):
        ports.append(f"web TCP {status['web_port']}")
    problems = [str(problem) for problem in status.get("problems") or []]
    host = f"P{host_slot}" if host_slot else "—"
    return {
        "state": "Running",
        "players": f"{len(peers)}/{status.get('max_phones', config.max_phones)}",
        "pad": f"{str(status.get('pad', config.pad)).upper()} x{status.get('max_phones', config.max_phones)}",
        "party": "Locked" if status.get("locked") else "Open",
        "host": host,
        "ports": "  ·  ".join(ports),
        "problems": "  |  ".join(problems),
        "rows": rows,
    }


def pairing_options(
    status: Optional[dict[str, Any]],
    config: GuiConfig,
    ip: str,
) -> list[tuple[str, str]]:
    """Return all QR/link choices for one LAN address."""
    if not status:
        return []
    port = int(status.get("port", config.port))
    name = str(status.get("hostname") or hostname())
    options = [
        ("App", qr_payload(ip, port, name)),
        ("App guest", qr_payload(ip, port, name, auto=True)),
    ]
    if not config.no_web and status.get("web_port") and status.get("web_key"):
        web = web_url(ip, int(status["web_port"]), str(status["web_key"]))
        options.append(("Browser", web))
        options.append(("Browser guest", f"{web}&auto=1"))
    return options


def qr_image(payload: str):
    """Render a payload as a Pillow image for the QR label."""
    import qrcode

    qr = qrcode.QRCode(border=2, box_size=6)
    qr.add_data(payload)
    qr.make(fit=True)
    image = qr.make_image(fill_color="black", back_color="white")
    if hasattr(image, "convert"):
        image = image.convert("RGB")
    return image


class GuiApp:
    """Tk application that owns and supervises one native server process."""

    def __init__(self, root: Any, config: GuiConfig) -> None:
        import tkinter as tk
        from tkinter import filedialog, font as tkfont, messagebox, ttk
        from PIL import Image, ImageDraw, ImageFilter, ImageTk

        self.root = root
        self.config = config
        self.server: Optional[dict[str, Any]] = None
        self.process: Optional[subprocess.Popen[str]] = None
        self.status_file = Path(tempfile.gettempdir()) / f"lmgb-gui-{os.getpid()}.json"
        self.thread: Optional[threading.Thread] = None
        self.messages: "queue.Queue[str]" = queue.Queue()
        self.update_events: "queue.Queue[Optional[dict[str, str]]]" = queue.Queue()
        self.update_thread: Optional[threading.Thread] = None
        self.update_url = ""
        self.pending_restart = False
        self.stop_requested = False
        self.closing = False
        self.qr_key: tuple[Any, ...] = ()
        self.qr_photo = None
        self.theme_name = "dark"
        self.palette = theme_palette(self.theme_name)
        self.font = ui_font()
        self.mono = mono_font()
        self.tkfont = tkfont
        self.Image = Image
        self.ImageDraw = ImageDraw
        self.ImageFilter = ImageFilter
        self.ImageTk = ImageTk
        resampling = getattr(Image, "Resampling", Image)
        self._resample = resampling.LANCZOS
        self._fast_resample = resampling.BILINEAR
        self._mask_resample = resampling.BOX
        self._nearest_resample = resampling.NEAREST
        self._tk_widgets: list[tuple[Any, Optional[str], Optional[str]]] = []
        self._border_widgets: list[Any] = []
        self._rounded_frames: list[Any] = []
        self._pill_buttons: list[Any] = []
        self._pill_badges: list[Any] = []
        self._panel_cache: dict[tuple[Any, ...], Any] = {}
        self._pill_cache: dict[tuple[Any, ...], Any] = {}
        self._gradient_cache: dict[tuple[str, str, int], Any] = {}
        self._icon_cache: dict[tuple[str, str], Any] = {}
        self._logo_cache: dict[str, Any] = {}

        self.tk = tk
        self.ttk = ttk
        self.filedialog = filedialog
        self.messagebox = messagebox
        self.style = ttk.Style()
        try:
            self.style.theme_use("clam")
        except tk.TclError:
            pass

        root.title("lemoGamePad ultimate — PC server")
        root.geometry("1180x760")
        root.minsize(1000, 660)
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.configure(bg=self.palette["bg"])
        self._configure_ttk()

        self._build_variables()
        self._build_layout()
        try:
            self.root.iconphoto(True, self.logo_photo)
        except (self.tk.TclError, AttributeError):
            pass
        self._apply_theme()
        # Render the inactive theme's card backgrounds while the window is idle,
        # so the theme toggle never has to do the expensive work on click.
        self.root.after(300, self._precache_inactive_theme)
        self._poll()

    # ------------------------------------------------------------------ theme
    def _sc(self, name: str, **kwargs: Any) -> None:
        """Configure a ttk style, ignoring options this theme does not support."""
        for key, value in kwargs.items():
            try:
                self.style.configure(name, **{key: value})
            except self.tk.TclError:
                pass

    def _sm(self, name: str, option: str, states: list[tuple[str, str]]) -> None:
        """Configure a ttk state map, ignoring unsupported options."""
        try:
            self.style.map(name, **{option: states})
        except self.tk.TclError:
            pass

    def _rgba(self, color: str, alpha: int = 255) -> tuple[int, int, int, int]:
        value = color.lstrip("#")
        return (
            int(value[0:2], 16),
            int(value[2:4], 16),
            int(value[4:6], 16),
            alpha,
        )

    def _mix_hex(self, first: str, second: str, amount: float) -> str:
        a, b = self._rgba(first), self._rgba(second)
        return "#%02X%02X%02X" % tuple(
            round(a[index] * (1 - amount) + b[index] * amount) for index in range(3)
        )

    def _gradient_strip(self, top: str, bottom: str, height: int) -> Any:
        """Build a 1px-wide vertical gradient once, then stretch it horizontally."""
        height = max(1, height)
        key = (top, bottom, height)
        cached = self._gradient_cache.get(key)
        if cached is not None:
            return cached
        first, last = self._rgba(top), self._rgba(bottom)
        data = bytearray()
        for y in range(height):
            amount = y / max(1, height - 1)
            data.extend(
                bytes(round(first[i] * (1 - amount) + last[i] * amount) for i in range(4))
            )
        strip = self.Image.frombytes("RGBA", (1, height), bytes(data))
        if len(self._gradient_cache) > 64:
            self._gradient_cache.clear()
        self._gradient_cache[key] = strip
        return strip

    def _paint_gradient(self, image: Any, top: str, bottom: str) -> None:
        """Fill an image with a subtle vertical gradient."""
        strip = self._gradient_strip(top, bottom, image.height)
        image.paste(strip.resize((image.width, image.height), self._nearest_resample))

    def _panel_photo(
        self,
        width: int,
        height: int,
        fill_role: str,
        radius: int = 18,
        pad: int = 2,
        shadow: bool = True,
        border: bool = True,
        theme_name: Optional[str] = None,
    ) -> Any:
        """Render a soft, anti-aliased card background with Pillow."""
        width, height = max(1, int(width)), max(1, int(height))
        theme = theme_name or self.theme_name
        key = (
            width,
            height,
            fill_role,
            radius,
            pad,
            shadow,
            border,
            theme,
        )
        cached = self._panel_cache.get(key)
        if cached is not None:
            return cached

        palette = self.palette if theme == self.theme_name else theme_palette(theme)

        # Supersample only the rounded-corner mask, not the whole panel. This
        # keeps the anti-aliasing but avoids multi-megapixel blur work on large
        # cards when the theme changes.
        scale = 2
        inset = max(1, pad)
        box = (inset, inset, width - inset, height - inset)
        image = self.Image.new("RGBA", (width, height), (0, 0, 0, 0))

        if shadow:
            factor = max(2, (max(width, height) + 399) // 400)
            small = (max(1, round(width / factor)), max(1, round(height / factor)))
            layer = self.Image.new("RGBA", small, (0, 0, 0, 0))
            draw = self.ImageDraw.Draw(layer)
            alpha = 105 if theme == "dark" else 48
            sx, sy = width / small[0], height / small[1]
            draw.rounded_rectangle(
                (
                    box[0] / sx,
                    (box[1] + 3) / sy,
                    box[2] / sx,
                    (box[3] + 3) / sy,
                ),
                radius=max(0.5, radius / sx),
                fill=self._rgba(palette["shadow"], alpha),
            )
            blurred = layer.filter(self.ImageFilter.GaussianBlur(2))
            image.alpha_composite(blurred.resize((width, height), self._fast_resample))

        mask_hi = self.Image.new("L", (width * scale, height * scale), 0)
        mask_draw = self.ImageDraw.Draw(mask_hi)
        mask_draw.rounded_rectangle(
            tuple(value * scale for value in box),
            radius=radius * scale,
            fill=255,
        )
        mask = mask_hi.resize((width, height), self._mask_resample)

        fill = palette.get(fill_role, fill_role)
        top = self._mix_hex(fill, "#FFFFFF", 0.05 if theme == "dark" else 0.45)
        bottom = self._mix_hex(fill, palette["bg"], 0.12)
        gradient = self.Image.new("RGBA", (width, height), (0, 0, 0, 0))
        self._paint_gradient(gradient, top, bottom)
        image.paste(gradient, (0, 0), mask)

        if border:
            alpha = 70 if theme == "dark" else 150
            draw = self.ImageDraw.Draw(image)
            draw.rounded_rectangle(
                box,
                radius=radius,
                outline=self._rgba(palette["border_soft"], alpha),
                width=1,
            )
        photo = self.ImageTk.PhotoImage(image)
        if len(self._panel_cache) > 160:
            self._panel_cache.clear()
        self._panel_cache[key] = photo
        return photo

    def _pill_photo(self, width: int, height: int, fill: str) -> Any:
        """Render a smooth pill background without a clipped shadow."""
        width, height = max(1, int(width)), max(1, int(height))
        key = (width, height, fill, self.theme_name)
        cached = self._pill_cache.get(key)
        if cached is not None:
            return cached

        scale = 2
        box = (2, 2, width - 2, height - 2)
        radius = height // 2
        image = self.Image.new("RGBA", (width, height), (0, 0, 0, 0))

        mask_hi = self.Image.new("L", (width * scale, height * scale), 0)
        mask_draw = self.ImageDraw.Draw(mask_hi)
        mask_draw.rounded_rectangle(
            tuple(value * scale for value in box),
            radius=radius * scale,
            fill=255,
        )
        mask = mask_hi.resize((width, height), self._mask_resample)
        top = self._mix_hex(fill, "#FFFFFF", 0.10)
        bottom = self._mix_hex(fill, self.palette["bg"], 0.06)
        gradient = self.Image.new("RGBA", (width, height), (0, 0, 0, 0))
        self._paint_gradient(gradient, top, bottom)
        image.paste(gradient, (0, 0), mask)
        photo = self.ImageTk.PhotoImage(image)
        if len(self._pill_cache) > 200:
            self._pill_cache.clear()
        self._pill_cache[key] = photo
        return photo

    def _icon_photo(self, name: str, color: str) -> Any:
        """Draw a small, crisp navigation icon."""
        key = (name, color)
        cached = self._icon_cache.get(key)
        if cached is not None:
            return cached
        scale = 3
        size = 18 * scale
        image = self.Image.new("RGBA", (size, size), (0, 0, 0, 0))
        draw = self.ImageDraw.Draw(image)
        ink = self._rgba(color)
        if name == "Status":
            for x, y in ((1, 1), (10, 1), (1, 10), (10, 10)):
                draw.rounded_rectangle(
                    (x * scale, y * scale, (x + 7) * scale, (y + 7) * scale),
                    radius=2 * scale,
                    fill=ink,
                )
        elif name == "Pairing":
            for x, y in ((1, 1), (10, 1), (1, 10)):
                draw.rounded_rectangle(
                    (x * scale, y * scale, (x + 7) * scale, (y + 7) * scale),
                    radius=2 * scale,
                    fill=ink,
                )
            for x, y in ((11, 11), (15, 11), (11, 15)):
                draw.rectangle(
                    (x * scale, y * scale, (x + 2) * scale, (y + 2) * scale), fill=ink
                )
        elif name == "Settings":
            draw.ellipse((4 * scale, 4 * scale, 14 * scale, 14 * scale), outline=ink, width=2 * scale)
            draw.ellipse((7 * scale, 7 * scale, 11 * scale, 11 * scale), fill=ink)
            draw.line((9 * scale, 1 * scale, 9 * scale, 4 * scale), fill=ink, width=2 * scale)
            draw.line((9 * scale, 14 * scale, 9 * scale, 17 * scale), fill=ink, width=2 * scale)
            draw.line((1 * scale, 9 * scale, 4 * scale, 9 * scale), fill=ink, width=2 * scale)
            draw.line((14 * scale, 9 * scale, 17 * scale, 9 * scale), fill=ink, width=2 * scale)
        elif name == "Updates":
            draw.arc(
                (3 * scale, 3 * scale, 15 * scale, 15 * scale),
                start=35,
                end=330,
                fill=ink,
                width=2 * scale,
            )
            draw.polygon(
                (
                    (14 * scale, 1 * scale),
                    (17 * scale, 5 * scale),
                    (12 * scale, 5 * scale),
                ),
                fill=ink,
            )
        else:
            for y in (3, 9, 15):
                draw.line((2 * scale, y * scale, 16 * scale, y * scale), fill=ink, width=2 * scale)
                draw.ellipse(
                    (2 * scale, (y - 1) * scale, 4 * scale, (y + 1) * scale), fill=ink
                )
        photo = self.ImageTk.PhotoImage(image.resize((18, 18), self._resample))
        self._icon_cache[key] = photo
        return photo

    def _app_icon_path(self) -> Path:
        """The phone app launcher icon, bundled with the desktop panel."""
        return Path(__file__).resolve().parent / "assets" / "app-icon.png"

    def _make_logo_photo(self) -> Any:
        size = 48
        cached = self._logo_cache.get(self.theme_name)
        if cached is not None:
            return cached
        try:
            source = self.Image.open(self._app_icon_path()).convert("RGBA")
            source.thumbnail((size, size), self._resample)
            image = self.Image.new("RGBA", (size, size), (0, 0, 0, 0))
            image.alpha_composite(
                source, ((size - source.width) // 2, (size - source.height) // 2)
            )
            photo = self.ImageTk.PhotoImage(image)
        except Exception:
            # Flat fallback if the bundled asset is unavailable.
            scale = 3
            image = self.Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
            draw = self.ImageDraw.Draw(image)
            draw.rounded_rectangle(
                (2 * scale, 2 * scale, 46 * scale, 46 * scale),
                radius=14 * scale,
                fill=self._rgba(self.palette["accent"]),
            )
            draw.text(
                (24 * scale, 24 * scale),
                "L",
                fill=self._rgba(self.palette["on_accent"]),
                anchor="mm",
            )
            photo = self.ImageTk.PhotoImage(image.resize((size, size), self._resample))
        self._logo_cache[self.theme_name] = photo
        return photo

    def _rounded_frame(
        self,
        parent: Any,
        fill_role: str = "surface",
        radius: int = 18,
        pad: int = 2,
        shadow: bool = True,
        border: bool = True,
    ) -> Any:
        """A frame with an anti-aliased, softly shadowed background."""
        frame = self.tk.Frame(parent, bg=self.palette["bg"], bd=0, highlightthickness=0)
        canvas = self.tk.Canvas(frame, bg=self.palette["bg"], highlightthickness=0, bd=0)
        canvas.place(x=0, y=0, relwidth=1, relheight=1)
        canvas.tk.call("lower", canvas._w)
        frame._round_canvas = canvas
        frame._round_fill = fill_role
        frame._round_radius = radius
        frame._round_pad = pad
        frame._round_shadow = shadow
        frame._round_border = border
        frame.bind("<Configure>", lambda _event, f=frame: self._redraw_round(f))
        self._rounded_frames.append(frame)
        self._redraw_round(frame)
        return frame

    def _redraw_round(self, frame: Any) -> None:
        width, height = frame.winfo_width(), frame.winfo_height()
        if width < 4 or height < 4:
            return
        photo = self._panel_photo(
            width,
            height,
            frame._round_fill,
            frame._round_radius,
            frame._round_pad,
            frame._round_shadow,
            frame._round_border,
        )
        canvas = frame._round_canvas
        item = getattr(frame, "_round_image_id", None)
        if item is not None:
            # Reuse the canvas item: replacing the image is much cheaper than
            # deleting and recreating every card on each theme change.
            canvas.itemconfigure(item, image=photo)
        else:
            frame._round_image_id = canvas.create_image(0, 0, anchor="nw", image=photo)
        frame._round_photo = photo

    def _pill_canvas(
        self,
        parent: Any,
        text: str = "",
        role: str = "surface2",
        command: Any = None,
        textvariable: Any = None,
        height: int = 34,
        icon: str = "",
        width: int = 0,
        bg_role: str = "bg",
    ) -> Any:
        """A pill button, badge, or navigation item drawn with Pillow."""
        measure = self.tkfont.Font(font=(self.font, 10))
        measured = max(96, measure.measure(text or "Running") + (72 if icon else 44))
        canvas = self.tk.Canvas(
            parent,
            width=width or measured,
            height=height,
            bg=self.palette.get(bg_role, self.palette["bg"]),
            highlightthickness=0,
            bd=0,
            cursor="hand2" if command is not None else "arrow",
        )
        canvas._text = text
        canvas._textvar = textvariable
        canvas._role = role
        canvas._width = width or measured
        canvas._height = height
        canvas._icon_key = icon
        canvas._hover = False
        canvas._bg_role = bg_role
        if command is not None:
            canvas.bind("<Button-1>", lambda _event, cmd=command: cmd())
            canvas.bind("<Enter>", lambda _event, c=canvas: self._hover_pill(c, True))
            canvas.bind("<Leave>", lambda _event, c=canvas: self._hover_pill(c, False))
            self._pill_buttons.append(canvas)
        else:
            self._pill_badges.append(canvas)
            if textvariable is not None:
                textvariable.trace_add("write", lambda *_args, c=canvas: self._redraw_pill(c))
        self._redraw_pill(canvas)
        return canvas

    def _hover_pill(self, canvas: Any, active: bool) -> None:
        canvas._hover = active
        self._redraw_pill(canvas)

    def _set_pill_role(self, canvas: Any, role: str) -> None:
        if canvas._role != role:
            canvas._role = role
            self._redraw_pill(canvas)

    def _redraw_pill(self, canvas: Any) -> None:
        palette = self.palette
        role = canvas._role
        if role == "muted":
            fill, foreground = palette["surface2"], palette["muted"]
        else:
            fill = palette.get(role, palette["surface2"])
            foreground = palette.get(f"on_{role}", palette["text"])
        if canvas._hover:
            fill = self._mix_hex(fill, palette["accent2"], 0.28)
            foreground = palette["on_accent"]
        text = canvas._textvar.get() if canvas._textvar is not None else canvas._text
        photo = self._pill_photo(canvas._width, canvas._height, fill)
        icon = (
            self._icon_photo(canvas._icon_key, foreground) if canvas._icon_key else None
        )
        canvas.configure(bg=palette.get(canvas._bg_role, palette["bg"]))
        item = getattr(canvas, "_pill_image_id", None)
        if item is None:
            canvas.delete("all")
            canvas._pill_image_id = canvas.create_image(0, 0, anchor="nw", image=photo)
            if icon is not None:
                canvas._pill_icon_id = canvas.create_image(
                    16, canvas._height // 2, image=icon, anchor="w"
                )
            canvas._pill_text_id = canvas.create_text(
                42 if icon is not None else canvas._width // 2,
                canvas._height // 2 + 1,
                text=text,
                fill=foreground,
                font=(self.font, 10),
                anchor="w" if icon is not None else "center",
            )
        else:
            canvas.itemconfigure(item, image=photo)
            if icon is not None:
                canvas.itemconfigure(canvas._pill_icon_id, image=icon)
            canvas.itemconfigure(canvas._pill_text_id, text=text, fill=foreground)
        canvas._pill_photo = photo
        canvas._icon_photo = icon

    def _configure_ttk(self) -> None:
        p = self.palette
        f = self.font
        self._sc(".", background=p["bg"], foreground=p["text"], font=(f, 10))
        self._sc("TFrame", background=p["bg"])
        self._sc("Header.TFrame", background=p["bg"])
        self._sc("Sidebar.TFrame", background=p["sidebar"])
        self._sc("Line.TFrame", background=p["border_soft"])
        self._sc("Card.TFrame", background=p["surface"])
        self._sc("Inner.TFrame", background=p["surface2"])

        self._sc("TLabel", background=p["bg"], foreground=p["text"], font=(f, 10))
        self._sc("Brand.TLabel", background=p["sidebar"], foreground=p["text"], font=(f, 13, "bold"))
        self._sc("BrandSub.TLabel", background=p["sidebar"], foreground=p["muted"], font=(f, 8))
        self._sc("Title.TLabel", background=p["bg"], foreground=p["text"], font=(f, 18, "bold"))
        self._sc("Subtitle.TLabel", background=p["bg"], foreground=p["muted"], font=(f, 9))
        self._sc("Banner.TLabel", background=p["surface2"], foreground=p["text"], font=(f, 10))
        self._sc("BannerSub.TLabel", background=p["surface2"], foreground=p["muted"], font=(f, 9))
        self._sc("Section.TLabel", background=p["surface"], foreground=p["accent"], font=(f, 10, "bold"))
        self._sc("Body.TLabel", background=p["surface"], foreground=p["text"], font=(f, 10))
        self._sc("Muted.TLabel", background=p["surface"], foreground=p["muted"], font=(f, 9))
        self._sc("Caption.TLabel", background=p["surface2"], foreground=p["muted"], font=(f, 9))
        self._sc("Metric.TLabel", background=p["surface2"], foreground=p["text"], font=(f, 18, "bold"))
        self._sc("Value.TLabel", background=p["surface2"], foreground=p["text"], font=(f, 11, "bold"))

        self._sc("TButton", background=p["surface2"], foreground=p["text"], padding=(14, 8), font=(f, 10), borderwidth=0)
        self._sm("TButton", "background", [("active", p["surface"]), ("pressed", p["accent"])])
        self._sm("TButton", "foreground", [("disabled", p["muted"])])
        self._sc("Primary.TButton", background=p["accent"], foreground=p["on_accent"])
        self._sm("Primary.TButton", "background", [("active", p["accent2"]), ("pressed", p["accent2"])])
        self._sc("Danger.TButton", background=p["danger"], foreground=p["on_danger"])
        self._sm("Danger.TButton", "background", [("active", p["surface"]), ("pressed", p["danger"])])
        self._sc("Ghost.TButton", background=p["surface"], foreground=p["muted"])
        self._sm("Ghost.TButton", "background", [("active", p["surface2"])])

        self._sc("TNotebook", background=p["bg"], borderwidth=0, tabmargins=(6, 12, 6, 0))
        self._sc("TNotebook.Tab", background=p["bg"], foreground=p["muted"], padding=(18, 10), font=(f, 10), borderwidth=0)
        self._sm("TNotebook.Tab", "background", [("selected", p["surface"])])
        self._sm("TNotebook.Tab", "foreground", [("selected", p["text"])])

        self._sc("TEntry", fieldbackground=p["entry"], foreground=p["text"], insertcolor=p["text"], padding=7, borderwidth=0, relief="flat")
        self._sc("TCombobox", fieldbackground=p["entry"], foreground=p["text"], insertcolor=p["text"], padding=6, borderwidth=0, relief="flat")
        self._sc("TSpinbox", fieldbackground=p["entry"], foreground=p["text"], insertcolor=p["text"], padding=6, borderwidth=0, relief="flat")
        self._sc("TCheckbutton", background=p["surface"], foreground=p["text"], font=(f, 10), selectcolor=p["accent"], indicatorrelief="flat")
        self._sc("Chip.TRadiobutton", background=p["surface2"], foreground=p["muted"], font=(f, 10), padding=(10, 7), selectcolor=p["accent"], indicatorrelief="flat")
        self._sm("Chip.TRadiobutton", "foreground", [("selected", p["accent"])])

        self._sc("Treeview", background=p["surface2"], fieldbackground=p["surface2"], foreground=p["text"], rowheight=30, font=(f, 10), borderwidth=0)
        self._sc("Treeview.Heading", background=p["surface"], foreground=p["muted"], font=(f, 9), padding=(8, 6), borderwidth=0, relief="flat")
        self._sm("Treeview.Heading", "background", [("active", p["surface2"])])
        self._sc("Vertical.TScrollbar", background=p["surface2"], troughcolor=p["bg"], borderwidth=0)
        self._sc("Horizontal.TScrollbar", background=p["surface2"], troughcolor=p["bg"], borderwidth=0)

    def _register_tk(self, widget: Any, bg: Optional[str] = "surface", fg: Optional[str] = "text", border: bool = False) -> Any:
        p = self.palette
        opts: dict[str, str] = {}
        if bg:
            opts["bg"] = p.get(bg, bg)
        if fg:
            opts["fg"] = p.get(fg, fg)
        if opts:
            widget.configure(**opts)
        self._tk_widgets.append((widget, bg, fg))
        if border:
            widget.configure(highlightbackground=p["border"], highlightthickness=1)
            self._border_widgets.append(widget)
        return widget

    def _set_tk_colors(self, widget: Any, bg_role: Optional[str], fg_role: Optional[str]) -> None:
        for index, (item, _bg, _fg) in enumerate(self._tk_widgets):
            if item is widget:
                self._tk_widgets[index] = (widget, bg_role, fg_role)
                break
        p = self.palette
        opts: dict[str, str] = {}
        if bg_role:
            opts["bg"] = p.get(bg_role, bg_role)
        if fg_role:
            opts["fg"] = p.get(fg_role, fg_role)
        if opts:
            widget.configure(**opts)

    def _set_tree_tags(self) -> None:
        if not hasattr(self, "player_tree"):
            return
        p = self.palette
        self.player_tree.tag_configure("even", background=p["surface2"], foreground=p["text"])
        self.player_tree.tag_configure("odd", background=p["surface"], foreground=p["text"])
        self.player_tree.tag_configure("host", background=p["accent"], foreground=p["on_accent"])

    def _set_log_style(self) -> None:
        if not hasattr(self, "log_text"):
            return
        p = self.palette
        self.log_text.configure(
            bg=p["log_bg"],
            fg=p["text"],
            insertbackground=p["text"],
            font=(self.mono, 10),
            relief="flat",
            borderwidth=0,
            padx=12,
            pady=10,
        )
        self.log_text.tag_configure("info", foreground=p["text"])
        self.log_text.tag_configure("ok", foreground=p["success"])
        self.log_text.tag_configure("warn", foreground=p["warn"])
        self.log_text.tag_configure("error", foreground=p["danger"])

    def _draw_logo(self) -> None:
        if not hasattr(self, "logo_canvas"):
            return
        self.logo_photo = self._make_logo_photo()
        self.logo_canvas.delete("all")
        self.logo_canvas.create_image(0, 0, anchor="nw", image=self.logo_photo)

    def _apply_theme(self) -> None:
        self.palette = theme_palette(self.theme_name)
        p = self.palette
        self.root.configure(bg=p["bg"])
        if hasattr(self, "page_host"):
            self.page_host.frame.configure(bg=p["bg"])
        for widget, bg_role, fg_role in self._tk_widgets:
            opts: dict[str, str] = {}
            if bg_role:
                opts["bg"] = p.get(bg_role, bg_role)
            if fg_role:
                opts["fg"] = p.get(fg_role, fg_role)
            if opts:
                try:
                    widget.configure(**opts)
                except self.tk.TclError:
                    pass
        for widget in self._border_widgets:
            try:
                widget.configure(highlightbackground=p["border"])
            except self.tk.TclError:
                pass
        for frame in self._rounded_frames:
            try:
                frame.configure(bg=p["bg"])
                frame._round_canvas.configure(bg=p["bg"])
                self._redraw_round(frame)
            except (self.tk.TclError, AttributeError):
                pass
        for canvas in self._pill_buttons + self._pill_badges:
            self._redraw_pill(canvas)
        self._configure_ttk()
        self._draw_logo()
        self._set_tree_tags()
        self._set_log_style()
        if hasattr(self, "qr_label"):
            self.qr_label.configure(bg=p["qr_bg"], fg=p["qr_text"])
        if hasattr(self, "theme_button"):
            self.theme_button._text = f"Theme: {self.theme_name.title()}"
            self._redraw_pill(self.theme_button)
        if hasattr(self, "log_text"):
            self._refresh_status()
            self._refresh_pairing()

    def _precache_inactive_theme(self, retry: bool = True) -> None:
        """Render the other theme's cards in small idle chunks."""
        theme = "light" if self.theme_name == "dark" else "dark"
        frames = list(self._rounded_frames)

        def step(index: int = 0) -> None:
            if self.closing:
                return
            if index >= len(frames):
                # Some cards get their final size after the first layout pass.
                if retry:
                    self.root.after(1500, lambda: self._precache_inactive_theme(False))
                return
            frame = frames[index]
            width, height = frame.winfo_width(), frame.winfo_height()
            if width >= 4 and height >= 4:
                try:
                    self._panel_photo(
                        width,
                        height,
                        frame._round_fill,
                        frame._round_radius,
                        frame._round_pad,
                        frame._round_shadow,
                        frame._round_border,
                        theme_name=theme,
                    )
                except Exception:
                    pass
            self.root.after(1, lambda: step(index + 1))

        self.root.after_idle(step)

    def toggle_theme(self) -> None:
        self.theme_name = "light" if self.theme_name == "dark" else "dark"
        self._apply_theme()

    # ------------------------------------------------------------------ layout
    def _build_variables(self) -> None:
        tk = self.tk
        cfg = self.config
        self.port_var = tk.StringVar(value=str(cfg.port))
        self.pad_var = tk.StringVar(value=cfg.pad)
        self.max_var = tk.StringVar(value=str(cfg.max_phones))
        self.rumble_var = tk.BooleanVar(value=not cfg.no_rumble)
        self.video_var = tk.BooleanVar(value=not cfg.no_video)
        self.web_var = tk.BooleanVar(value=not cfg.no_web)
        self.video_port_var = tk.StringVar(value=str(cfg.video_port or ""))
        self.web_port_var = tk.StringVar(value=str(cfg.web_port or ""))
        self.web_key_var = tk.StringVar(value=cfg.web_key)
        self.web_key_file_var = tk.StringVar(value=cfg.web_key_file)
        self.video_fps_var = tk.StringVar(value=str(cfg.video_fps))
        self.video_width_var = tk.StringVar(value=str(cfg.video_width))
        self.video_quality_var = tk.StringVar(value=str(cfg.video_quality))
        self.skip_setup_var = tk.BooleanVar(value=cfg.no_install_deps)

        self.state_var = tk.StringVar(value="Stopped")
        self.players_var = tk.StringVar(value=f"0/{cfg.max_phones}")
        self.pad_summary_var = tk.StringVar(value=f"{cfg.pad.upper()} x{cfg.max_phones}")
        self.party_var = tk.StringVar(value="Open")
        self.host_var = tk.StringVar(value="—")
        self.ports_var = tk.StringVar(value="—")
        self.problem_var = tk.StringVar(value="")
        self.ip_var = tk.StringVar(value="")
        self.link_kind_var = tk.StringVar(value="Browser")
        self.link_var = tk.StringVar(value="")
        self.update_var = tk.StringVar(value="")
        self.update_detail_var = tk.StringVar(value="")

    def _build_layout(self) -> None:
        tk, ttk = self.tk, self.ttk
        main = ttk.Frame(self.root, style="Header.TFrame", padding=(12, 12, 12, 12))
        main.pack(fill="both", expand=True)

        sidebar = self._rounded_frame(
            main, fill_role="sidebar", radius=20, pad=0, shadow=True, border=True
        )
        sidebar.pack(side="left", fill="y", padx=(0, 12))

        self.logo_canvas = self._register_tk(
            tk.Canvas(sidebar, width=48, height=48, highlightthickness=0), "sidebar", None
        )
        self.logo_canvas.pack(anchor="w", padx=18, pady=(20, 8))
        self._draw_logo()

        brand = ttk.Frame(sidebar, style="Sidebar.TFrame")
        brand.pack(anchor="w", padx=18)
        ttk.Label(brand, text="lemoGamePad", style="Brand.TLabel").pack(anchor="w")
        ttk.Label(brand, text="ULTIMATE  ·  PC SERVER", style="BrandSub.TLabel").pack(
            anchor="w", pady=(2, 0)
        )

        divider = ttk.Frame(sidebar, style="Line.TFrame", height=1)
        divider.pack(fill="x", padx=18, pady=(18, 10))

        content = ttk.Frame(main, style="Header.TFrame")
        content.pack(side="left", fill="both", expand=True)

        header = self._register_tk(tk.Frame(content), "bg", None)
        header.pack(fill="x", pady=(4, 14))
        titles = ttk.Frame(header, style="Header.TFrame")
        titles.pack(side="left")
        ttk.Label(titles, text="Server overview", style="Title.TLabel").pack(anchor="w")
        ttk.Label(
            titles,
            text="Manage the local receiver, pairing codes and live controller state.",
            style="Subtitle.TLabel",
        ).pack(anchor="w", pady=(3, 0))

        actions = self._register_tk(tk.Frame(header), "bg", None)
        actions.pack(side="right")
        self.state_pill = self._pill_canvas(
            actions, role="muted", textvariable=self.state_var, height=32
        )
        self.state_pill.pack(side="left", padx=(0, 10))
        self.theme_button = self._pill_canvas(
            actions, "Theme: Dark", "surface2", self.toggle_theme
        )
        self.theme_button.pack(side="left", padx=(0, 6))
        self._pill_canvas(actions, "Apply & Restart", "surface2", self.apply_restart).pack(
            side="left", padx=(0, 6)
        )
        self._pill_canvas(actions, "Stop", "danger", self.stop_server).pack(
            side="left", padx=(0, 6)
        )
        self._pill_canvas(actions, "Start", "accent", self.start_server).pack(side="left")

        self.update_banner = self._rounded_frame(
            content, fill_role="surface2", radius=14, pad=1, shadow=False, border=False
        )
        update_body = ttk.Frame(self.update_banner, style="Inner.TFrame")
        update_body.pack(fill="x", padx=14, pady=11)
        update_text = ttk.Frame(update_body, style="Inner.TFrame")
        update_text.pack(side="left", fill="x", expand=True)
        ttk.Label(update_text, textvariable=self.update_var, style="Banner.TLabel").pack(
            anchor="w"
        )
        ttk.Label(
            update_text, textvariable=self.update_detail_var, style="BannerSub.TLabel"
        ).pack(anchor="w", pady=(2, 0))
        update_actions = ttk.Frame(update_body, style="Inner.TFrame")
        update_actions.pack(side="right")
        self._pill_canvas(
            update_actions,
            "Dismiss",
            "surface2",
            self.dismiss_update,
            height=30,
            bg_role="surface2",
        ).pack(side="right", padx=(6, 0))
        self._pill_canvas(
            update_actions,
            "View release",
            "accent",
            self.open_release,
            height=30,
            bg_role="surface2",
        ).pack(side="right")
        self.update_banner.pack_forget()

        app = self

        class PageHost:
            """Minimal page switcher used by the existing tab builders."""

            def __init__(self, master: Any) -> None:
                self.frame = tk.Frame(master, bg=app.palette["bg"], bd=0, highlightthickness=0)
                self.pages: dict[str, Any] = {}
                self.order: list[str] = []

            def add(self, page: Any, text: str) -> None:
                self.pages[text] = page
                self.order.append(text)
                page.pack(fill="both", expand=True)
                page.pack_forget()

            def show(self, name: str) -> None:
                for key, page in self.pages.items():
                    if key == name:
                        page.pack(fill="both", expand=True)
                    else:
                        page.pack_forget()
                app._select_nav(name)

        host = PageHost(content)
        self.page_host = host
        host.frame.pack(fill="both", expand=True)
        self._build_status_tab(host)
        self._build_pairing_tab(host)
        self._build_settings_tab(host)
        self._build_log_tab(host)

        self._nav_buttons: dict[str, Any] = {}
        for name, label, icon in (
            ("Status", "Overview", "Status"),
            ("Pairing / QR", "Pairing / QR", "Pairing"),
            ("Settings", "Settings", "Settings"),
            ("Log", "Log", "Log"),
        ):
            button = self._pill_canvas(
                sidebar,
                label,
                "surface2",
                lambda page=name: host.show(page),
                height=42,
                icon=icon,
                width=184,
                bg_role="sidebar",
            )
            button.pack(padx=14, pady=(0, 6), anchor="w")
            self._nav_buttons[name] = button

        self.update_button = self._pill_canvas(
            sidebar,
            "Check for updates",
            "surface2",
            self.check_for_updates,
            height=36,
            icon="Updates",
            width=184,
            bg_role="sidebar",
        )
        self.update_button.pack(padx=14, pady=(8, 0), anchor="w")

        note = self._rounded_frame(
            sidebar, fill_role="surface2", radius=14, pad=1, shadow=False
        )
        note.pack(side="bottom", fill="x", padx=14, pady=14)
        ttk.Label(
            note,
            text="LAN ONLY  ·  NO CLOUD\nSettings apply after restart",
            style="BrandSub.TLabel",
            justify="left",
        ).pack(anchor="w", padx=14, pady=12)

        host.show("Status")

    def _select_nav(self, name: str) -> None:
        if not hasattr(self, "_nav_buttons"):
            return
        for key, button in self._nav_buttons.items():
            self._set_pill_role(button, "accent" if key == name else "surface2")

    def _card(self, parent: Any, radius: int = 18, fill_role: str = "surface") -> Any:
        return self._rounded_frame(parent, fill_role=fill_role, radius=radius, pad=2)

    def _metric_card(self, parent: Any, row: int, col: int, caption: str, variable: Any, style: str) -> None:
        card = self._rounded_frame(parent, fill_role="surface2", radius=14, pad=1)
        card.grid(row=row, column=col, sticky="nsew", padx=8, pady=8)
        self.ttk.Label(card, text=caption, style="Caption.TLabel").pack(
            anchor="w", padx=16, pady=(13, 0)
        )
        self.ttk.Label(card, textvariable=variable, style=style).pack(
            anchor="w", padx=16, pady=(4, 14)
        )

    def _build_status_tab(self, host: Any) -> None:
        ttk = self.ttk
        page = ttk.Frame(host.frame, style="Header.TFrame", padding=16)
        host.add(page, text="Status")

        metrics = ttk.Frame(page, style="Header.TFrame")
        metrics.pack(fill="x")
        for col in range(3):
            metrics.columnconfigure(col, weight=1)
        specs = [
            (0, 0, "SERVER STATE", self.state_var, "Metric.TLabel"),
            (0, 1, "CONTROLLERS", self.players_var, "Metric.TLabel"),
            (0, 2, "VIRTUAL PADS", self.pad_summary_var, "Metric.TLabel"),
            (1, 0, "PARTY", self.party_var, "Value.TLabel"),
            (1, 1, "HOST SEAT", self.host_var, "Value.TLabel"),
            (1, 2, "ACTIVE PORTS", self.ports_var, "Value.TLabel"),
        ]
        for spec in specs:
            self._metric_card(metrics, *spec)

        self.problem_card = self._rounded_frame(page, fill_role="surface", radius=12, pad=1)
        self.problem_label = self._register_tk(
            self.tk.Label(
                self.problem_card,
                textvariable=self.problem_var,
                wraplength=900,
                justify="left",
                anchor="w",
                padx=16,
                pady=10,
            ),
            "surface",
            "danger",
        )
        self.problem_label.pack(fill="x")

        players = self._card(page, radius=20)
        players.pack(fill="both", expand=True, pady=(14, 0))
        ttk.Label(players, text="CONNECTED CONTROLLERS", style="Section.TLabel").pack(
            anchor="w", padx=14, pady=(12, 6)
        )

        table = self._rounded_frame(players, fill_role="surface2", radius=12, pad=1)
        table.pack(fill="both", expand=True, padx=12, pady=(0, 12))
        columns = ("seat", "device", "rate", "loss", "battery", "role")
        self.player_tree = ttk.Treeview(table, columns=columns, show="headings", height=8)
        headings = {
            "seat": "Seat",
            "device": "Device",
            "rate": "Packets",
            "loss": "Lost",
            "battery": "Battery",
            "role": "Role",
        }
        widths = {
            "seat": 76,
            "device": 360,
            "rate": 110,
            "loss": 90,
            "battery": 100,
            "role": 90,
        }
        for column in columns:
            self.player_tree.heading(column, text=headings[column])
            self.player_tree.column(column, width=widths[column], anchor="w")
        scroll = ttk.Scrollbar(
            table, orient="vertical", command=self.player_tree.yview
        )
        self.player_tree.configure(yscrollcommand=scroll.set)
        self.player_tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self._set_tree_tags()

        self.empty_hint = ttk.Label(
            players,
            text="No controllers yet — open Pairing / QR and scan a code, or open the browser pad.",
            style="Muted.TLabel",
        )
        self.empty_hint.pack(anchor="w", padx=14, pady=(0, 12))

    def _build_pairing_tab(self, host: Any) -> None:
        ttk = self.ttk
        page = ttk.Frame(host.frame, style="Header.TFrame", padding=16)
        host.add(page, text="Pairing / QR")
        page.columnconfigure(0, weight=1)
        page.columnconfigure(1, weight=0, minsize=360)

        qr_card = self._card(page, radius=20)
        qr_card.grid(row=0, column=0, sticky="nsew")
        ttk.Label(qr_card, text="PAIRING CODE", style="Section.TLabel").pack(
            anchor="w", padx=18, pady=(16, 10)
        )
        self.qr_wrap = self._rounded_frame(
            qr_card, fill_role="qr_bg", radius=20, pad=1
        )
        self.qr_wrap.pack(fill="both", expand=True, padx=14, pady=(0, 14))
        self.qr_label = self._register_tk(
            self.tk.Label(
                self.qr_wrap,
                text="Start the server to show a pairing QR.",
                anchor="center",
                justify="center",
            ),
            "qr_bg",
            "qr_text",
        )
        self.qr_label.pack(fill="both", expand=True, padx=18, pady=18)

        side = ttk.Frame(page, style="Header.TFrame")
        side.grid(row=0, column=1, sticky="nsew", padx=(14, 0))
        side.columnconfigure(0, weight=1)

        controls = self._card(side)
        controls.pack(fill="x")
        ttk.Label(controls, text="PC ADDRESS", style="Section.TLabel").pack(
            anchor="w", padx=14, pady=(14, 6)
        )
        self.ip_combo = ttk.Combobox(
            controls, textvariable=self.ip_var, state="readonly", width=24
        )
        self.ip_combo.pack(fill="x", padx=14, pady=(0, 12))

        ttk.Label(controls, text="CODE TYPE", style="Section.TLabel").pack(
            anchor="w", padx=14, pady=(0, 6)
        )
        chips = self._rounded_frame(controls, fill_role="surface2", radius=12, pad=1)
        chips.pack(fill="x", padx=12, pady=(0, 12))
        for index, (text, value) in enumerate(
            [
                ("Browser", "Browser"),
                ("Browser guest", "Browser guest"),
                ("App", "App"),
                ("App guest", "App guest"),
            ]
        ):
            ttk.Radiobutton(
                chips,
                text=text,
                value=value,
                variable=self.link_kind_var,
                style="Chip.TRadiobutton",
            ).grid(row=index // 2, column=index % 2, sticky="w", padx=4, pady=3)

        ttk.Label(controls, text="LINK", style="Section.TLabel").pack(
            anchor="w", padx=14, pady=(0, 6)
        )
        self.link_entry = ttk.Entry(
            controls, textvariable=self.link_var, state="readonly"
        )
        self.link_entry.pack(fill="x", padx=14)
        buttons = self._rounded_frame(controls, fill_role="surface2", radius=12, pad=1)
        buttons.pack(fill="x", padx=12, pady=14)
        self._pill_canvas(
            buttons, "Copy link", "surface2", self.copy_link, height=32
        ).pack(side="left", padx=(0, 6))
        self._pill_canvas(
            buttons, "Save QR", "surface2", self.save_qr, height=32
        ).pack(side="left", padx=(0, 6))
        self._pill_canvas(
            buttons, "Open browser pad", "accent", self.open_browser, height=32
        ).pack(side="right", padx=0)

        note = self._card(side)
        note.pack(fill="x", pady=(12, 0))
        ttk.Label(
            note,
            text="Works on the same Wi-Fi / LAN. Guest codes join the next free pad without changing a saved seat.",
            style="Muted.TLabel",
            wraplength=330,
            justify="left",
        ).pack(anchor="w", padx=14, pady=12)

    def _settings_card(self, parent: Any, title: str, row: int, col: int) -> Any:
        card = self._card(parent)
        card.grid(row=row, column=col, sticky="nsew", padx=7, pady=7)
        self.ttk.Label(card, text=title, style="Section.TLabel").pack(
            anchor="w", padx=14, pady=(12, 6)
        )
        content = self.ttk.Frame(card, style="Card.TFrame")
        content.pack(fill="both", expand=True, padx=14, pady=(0, 12))
        return content

    def _field(self, parent: Any, row: int, label: str, widget: Any, colspan: int = 1) -> None:
        self.ttk.Label(parent, text=label, style="Body.TLabel").grid(
            row=row, column=0, sticky="w", pady=5
        )
        widget.grid(row=row, column=1, columnspan=colspan, sticky="ew", padx=(10, 0), pady=5)

    def _build_settings_tab(self, host: Any) -> None:
        ttk = self.ttk
        page = ttk.Frame(host.frame, style="Header.TFrame", padding=16)
        host.add(page, text="Settings")
        page.columnconfigure(0, weight=1)
        page.columnconfigure(1, weight=1)

        server = self._settings_card(page, "SERVER", 0, 0)
        self._field(server, 0, "Control UDP port", ttk.Entry(server, textvariable=self.port_var, width=12))
        self._field(
            server,
            1,
            "Pad type",
            ttk.Combobox(server, textvariable=self.pad_var, values=["x360", "ds4"], state="readonly", width=12),
        )
        self._field(
            server,
            2,
            "Players",
            ttk.Spinbox(server, from_=1, to=MAX_PHONES, textvariable=self.max_var, width=8),
        )

        features = self._settings_card(page, "FEATURES", 0, 1)
        ttk.Checkbutton(
            features, text="Browser pad", variable=self.web_var
        ).grid(row=0, column=0, columnspan=2, sticky="w", pady=5)
        ttk.Checkbutton(
            features, text="Screen preview", variable=self.video_var
        ).grid(row=1, column=0, columnspan=2, sticky="w", pady=5)
        ttk.Checkbutton(
            features, text="Rumble", variable=self.rumble_var
        ).grid(row=2, column=0, columnspan=2, sticky="w", pady=5)
        ttk.Checkbutton(
            features, text="Skip setup check", variable=self.skip_setup_var
        ).grid(row=3, column=0, columnspan=2, sticky="w", pady=5)
        features.columnconfigure(1, weight=1)

        browser = self._settings_card(page, "BROWSER PAD", 1, 0)
        self._field(browser, 0, "TCP port", ttk.Entry(browser, textvariable=self.web_port_var, width=12))
        self._field(browser, 1, "Fixed key", ttk.Entry(browser, textvariable=self.web_key_var, width=22))
        self._field(browser, 2, "Key file", ttk.Entry(browser, textvariable=self.web_key_file_var, width=22))

        video = self._settings_card(page, "SCREEN PREVIEW", 1, 1)
        self._field(video, 0, "TCP port", ttk.Entry(video, textvariable=self.video_port_var, width=12))
        self._field(video, 1, "FPS", ttk.Entry(video, textvariable=self.video_fps_var, width=8))
        self._field(video, 2, "Width", ttk.Entry(video, textvariable=self.video_width_var, width=10))
        self._field(video, 3, "Quality", ttk.Entry(video, textvariable=self.video_quality_var, width=8))

        page.rowconfigure(0, weight=1)
        page.rowconfigure(1, weight=1)
        ttk.Label(
            page,
            text="Blank ports use control port + 1 (screen) and + 2 (browser). Settings need Apply & Restart.",
            style="Muted.TLabel",
        ).grid(row=2, column=0, columnspan=2, sticky="w", pady=(12, 0))

    def _build_log_tab(self, host: Any) -> None:
        from tkinter import scrolledtext

        ttk = self.ttk
        page = ttk.Frame(host.frame, style="Header.TFrame", padding=16)
        host.add(page, text="Log")
        card = self._card(page)
        card.pack(fill="both", expand=True)
        ttk.Label(card, text="SERVER LOG", style="Section.TLabel").pack(
            anchor="w", padx=14, pady=(12, 6)
        )
        self.log_text = scrolledtext.ScrolledText(card, wrap="word", height=18)
        self.log_text.pack(fill="both", expand=True, padx=14, pady=(0, 10))
        self.log_text.configure(state="disabled")
        self._set_log_style()
        self._pill_canvas(
            card, "Clear log", "surface2", self.clear_log, height=32
        ).pack(anchor="e", padx=14, pady=(0, 12))

    # ------------------------------------------------------------------ actions
    def collect_config(self) -> Optional[GuiConfig]:
        invalid: list[str] = []

        def number(name: str, variable: Any, default: Optional[int] = None) -> Optional[int]:
            text = variable.get().strip()
            if not text:
                return default
            try:
                return int(text)
            except ValueError:
                invalid.append(name)
                return None

        values = {
            "port": number("Control port", self.port_var),
            "max_phones": number("Players", self.max_var),
            "video_port": number("Screen port", self.video_port_var),
            "web_port": number("Browser port", self.web_port_var),
            "video_fps": number("Screen FPS", self.video_fps_var),
            "video_width": number("Screen width", self.video_width_var),
            "video_quality": number("Screen quality", self.video_quality_var),
        }
        if invalid:
            self.messagebox.showerror(
                "Invalid setting",
                ", ".join(invalid) + " must be a whole number.",
            )
            return None
        required = ("port", "max_phones", "video_fps", "video_width", "video_quality")
        if any(values[name] is None for name in required):
            return None
        config = GuiConfig(
            port=int(values["port"]),
            pad=self.pad_var.get(),
            max_phones=int(values["max_phones"]),
            no_rumble=not self.rumble_var.get(),
            no_video=not self.video_var.get(),
            video_port=values["video_port"],
            no_web=not self.web_var.get(),
            web_port=values["web_port"],
            web_key=self.web_key_var.get().strip(),
            web_key_file=self.web_key_file_var.get().strip(),
            video_fps=int(values["video_fps"]),
            video_width=int(values["video_width"]),
            video_quality=int(values["video_quality"]),
            no_install_deps=self.skip_setup_var.get(),
        )
        error = validate_config(config)
        if error:
            self.messagebox.showerror("Invalid settings", error)
            return None
        return config

    def start_server(self) -> None:
        if self.closing or (self.thread is not None and self.thread.is_alive()):
            return
        config = self.collect_config()
        if config is None:
            return
        self.config = config
        self.pending_restart = False
        self.stop_requested = False
        self.state_var.set("Starting…")
        try:
            self.status_file.unlink(missing_ok=True)
        except OSError:
            pass
        self._refresh_status()
        self.thread = threading.Thread(
            target=self._server_worker,
            args=(config,),
            name="lemo-gui-server",
            daemon=True,
        )
        self.thread.start()

    def stop_server(self) -> None:
        self.stop_requested = True
        process = self.process
        if process is not None and process.poll() is None:
            try:
                if sys.platform == "win32":
                    process.send_signal(subprocess.CTRL_BREAK_EVENT)
                else:
                    process.terminate()
            except OSError:
                pass
        if process is not None or (self.thread is not None and self.thread.is_alive()):
            self.state_var.set("Stopping…")
            self._refresh_status()

    def apply_restart(self) -> None:
        config = self.collect_config()
        if config is None:
            return
        self.config = config
        if self.thread is not None and self.thread.is_alive():
            self.pending_restart = True
            self.stop_server()
        else:
            self.start_server()

    def on_close(self) -> None:
        self.closing = True
        self.pending_restart = False
        self.stop_server()
        if self.thread is not None and self.thread.is_alive():
            self.root.after(150, self._finish_close)
            return
        self.root.destroy()

    def _finish_close(self) -> None:
        if self.thread is not None and self.thread.is_alive():
            self.root.after(150, self._finish_close)
            return
        self.root.destroy()

    def copy_link(self) -> None:
        link = self.link_var.get()
        if not link:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(link)
        self.append_log(f"copied: {link}")

    def open_browser(self) -> None:
        if self.config.no_web or not self.server or not self.server.get("web_key"):
            self.messagebox.showinfo(
                "Browser pad off", "Enable the browser pad in Settings, then apply."
            )
            return
        ip = self.ip_var.get() or (local_ips() or ["127.0.0.1"])[0]
        webbrowser.open(
            web_url(ip, int(self.server.get("web_port", 9878)), str(self.server["web_key"]))
        )

    def save_qr(self) -> None:
        payload = self.link_var.get()
        if not payload:
            return
        path = self.filedialog.asksaveasfilename(
            defaultextension=".png",
            initialfile="lemoGamePad-pairing.png",
            filetypes=[("PNG image", "*.png")],
        )
        if not path:
            return
        try:
            qr_image(payload).save(path)
            self.append_log(f"QR saved: {path}")
        except Exception as exc:
            self.messagebox.showerror("Could not save QR", str(exc))

    def clear_log(self) -> None:
        self.log_text.configure(state="normal")
        self.log_text.delete("1.0", "end")
        self.log_text.configure(state="disabled")

    # ------------------------------------------------------------------ worker
    def _server_worker(self, config: GuiConfig) -> None:
        process: Optional[subprocess.Popen[str]] = None
        try:
            binary = server_binary()
            args = [str(binary)] + server_arguments(config)
            environment = os.environ.copy()
            environment["LEMO_GAMEPAD_STATUS_FILE"] = str(self.status_file)
            options: dict[str, Any] = {
                "stdout": subprocess.PIPE,
                "stderr": subprocess.STDOUT,
                "text": True,
                "bufsize": 1,
                "env": environment,
            }
            if sys.platform == "win32":
                options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
            process = subprocess.Popen(args, **options)
            self.process = process
            self.messages.put(f"server started: {binary.name}")
            assert process.stdout is not None
            for line in process.stdout:
                if self.closing and not line.strip():
                    continue
                self.messages.put(line.rstrip())
            code = process.wait()
            if code and not (self.closing or self.stop_requested):
                self.messages.put(f"error: receiver exited with code {code}")
        except BaseException as exc:  # noqa: BLE001 - keep the GUI alive
            self.messages.put(f"error: {exc}")
        finally:
            self.process = None
            self.server = None
            try:
                self.status_file.unlink(missing_ok=True)
            except OSError:
                pass
            self.messages.put("__stopped__")

    # ------------------------------------------------------------------ updates
    def check_for_updates(self, automatic: bool = False) -> None:
        """Start one background release check without blocking the UI."""
        if self.closing or (automatic and not self.config.check_updates):
            return
        if self.update_thread is not None and self.update_thread.is_alive():
            return
        self._set_update_button_text("Checking…")
        self.update_thread = threading.Thread(
            target=self._update_worker,
            name="lemo-gui-update",
            daemon=True,
        )
        self.update_thread.start()

    def _update_worker(self) -> None:
        try:
            self.update_events.put(fetch_latest_release())
        except Exception:
            self.update_events.put(None)

    def _drain_update_events(self) -> None:
        while True:
            try:
                info = self.update_events.get_nowait()
            except queue.Empty:
                return
            if info is None:
                self._set_update_button_text("No update")
                self.root.after(2500, self._reset_update_button)
                continue
            self._reset_update_button()
            self._show_update_banner(info)

    def _set_update_button_text(self, text: str) -> None:
        if not hasattr(self, "update_button"):
            return
        self.update_button._text = text
        self._redraw_pill(self.update_button)

    def _reset_update_button(self) -> None:
        if not self.closing:
            self._set_update_button_text("Check for updates")

    def _show_update_banner(self, info: dict[str, str]) -> None:
        version = (info.get("version") or info.get("tag") or "").lstrip("v")
        if not version:
            return
        self.update_url = info.get("url") or RELEASES_PAGE
        self.update_var.set(f"New update available: v{version}")
        name = info.get("name", "").strip()
        detail = f"{name} — " if name else ""
        detail += "Open the release page to download it. Nothing is downloaded automatically."
        self.update_detail_var.set(detail)
        self.update_banner.pack(
            before=self.page_host.frame, fill="x", pady=(0, 10)
        )

    def dismiss_update(self) -> None:
        self.update_banner.pack_forget()
        self.update_url = ""

    def open_release(self) -> None:
        webbrowser.open(self.update_url or RELEASES_PAGE)

    # ------------------------------------------------------------------ polling
    def _poll(self) -> None:
        self._drain_messages()
        self._drain_update_events()
        self._refresh_status()
        self._refresh_pairing()
        self.root.after(400, self._poll)

    def _drain_messages(self) -> None:
        while True:
            try:
                message = self.messages.get_nowait()
            except queue.Empty:
                return
            if message == "__stopped__":
                self.server = None
                self.state_var.set("Stopped")
                if self.pending_restart:
                    self.root.after(50, self.start_server)
                continue
            self.append_log(message)

    def append_log(self, message: str) -> None:
        lower = message.lower()
        if message.startswith("error:") or any(
            word in lower for word in ("failed", "refused", "denied", "unavailable")
        ):
            tag = "error"
        elif any(word in lower for word in ("ready", "connected", "allowed", " ok", "saved")):
            tag = "ok"
        elif "!" in message or "warn" in lower:
            tag = "warn"
        else:
            tag = "info"
        self.log_text.configure(state="normal")
        self.log_text.insert("end", f"{time.strftime('%H:%M:%S')}  {message}\n", tag)
        self.log_text.see("end")
        lines = int(self.log_text.index("end-1c").split(".")[0])
        if lines > 400:
            self.log_text.delete("1.0", f"{lines - 400}.0")
        self.log_text.configure(state="disabled")

    def _refresh_status(self) -> None:
        process = self.process
        alive = process is not None and process.poll() is None
        status = read_server_status(self.status_file) if alive else None
        self.server = status if status and status.get("running") else None
        snapshot = status_snapshot(self.server, self.config)
        if self.server is not None and alive:
            state = "Running"
        elif self.thread is not None and self.thread.is_alive():
            state = "Stopping…" if not alive else "Starting…"
        else:
            state = "Stopped"
        snapshot["state"] = state

        self.state_var.set(state)
        self.players_var.set(snapshot["players"])
        self.pad_summary_var.set(snapshot["pad"])
        self.party_var.set(snapshot["party"])
        self.host_var.set(snapshot["host"])
        self.ports_var.set(snapshot["ports"])
        self.problem_var.set(snapshot["problems"])

        if self.problem_var.get():
            self.problem_card.pack(fill="x", pady=(12, 0))
        else:
            self.problem_card.pack_forget()
        tone = "danger" if self.problem_var.get() else status_tone(state)
        self._set_pill_role(self.state_pill, tone)
        self._set_rows(snapshot["rows"])
        if snapshot["rows"]:
            self.empty_hint.pack_forget()
        else:
            self.empty_hint.pack(anchor="w", padx=14, pady=(0, 12))

    def _set_rows(self, rows: list[tuple[str, ...]]) -> None:
        current = [self.player_tree.item(item, "values") for item in self.player_tree.get_children()]
        if [tuple(row) for row in current] == rows:
            return
        for item in self.player_tree.get_children():
            self.player_tree.delete(item)
        for index, row in enumerate(rows):
            tags = ("host",) if row[5] == "HOST" else ("even" if index % 2 == 0 else "odd",)
            self.player_tree.insert("", "end", values=row, tags=tags)

    def _refresh_pairing(self) -> None:
        ips = local_ips()
        if ips:
            if self.ip_var.get() not in ips:
                self.ip_var.set(ips[0])
            self.ip_combo["values"] = ips

        ip = self.ip_var.get()
        options = pairing_options(self.server, self.config, ip) if ip else []
        names = [name for name, _ in options]
        if self.link_kind_var.get() not in names:
            self.link_kind_var.set(names[-1] if names else "Browser")
        payload = dict(options).get(self.link_kind_var.get(), "")

        key = (ip, self.link_kind_var.get(), payload)
        if key == self.qr_key:
            return
        self.qr_key = key
        self.link_var.set(payload)
        if not payload:
            self.qr_label.configure(
                image="", text="Start the server to show a pairing QR."
            )
            self.qr_photo = None
            return
        try:
            from PIL import ImageTk

            image = qr_image(payload)
            self.qr_photo = ImageTk.PhotoImage(image)
            self.qr_label.configure(image=self.qr_photo, text="")
        except Exception as exc:
            self.qr_photo = None
            self.qr_label.configure(image="", text=f"QR unavailable: {exc}")


def main(argv: Optional[list[str]] = None) -> int:
    """Launch the desktop panel. CLI flags prefill the settings form."""
    import argparse
    import tkinter as tk

    ap = argparse.ArgumentParser(description="lemoGamePad ultimate desktop server")
    ap.add_argument("--port", type=int, default=9876)
    ap.add_argument("--pad", choices=["x360", "ds4"], default="x360")
    ap.add_argument("--max-phones", type=int, default=8)
    ap.add_argument("--no-rumble", action="store_true")
    ap.add_argument("--no-video", action="store_true")
    ap.add_argument("--no-web", action="store_true")
    ap.add_argument("--web-key", default="")
    ap.add_argument("--web-key-file", default="")
    ap.add_argument("--no-install-deps", action="store_true")
    ap.add_argument(
        "--no-update-check",
        action="store_true",
        help="do not check GitHub for a newer release on launch",
    )
    ap.add_argument(
        "--manual-start",
        action="store_true",
        help="open the panel without starting the server automatically",
    )
    args = ap.parse_args(argv)

    config = replace(
        GuiConfig(),
        port=args.port,
        pad=args.pad,
        max_phones=args.max_phones,
        no_rumble=args.no_rumble,
        no_video=args.no_video,
        no_web=args.no_web,
        web_key=args.web_key,
        web_key_file=args.web_key_file,
        no_install_deps=args.no_install_deps,
        check_updates=not args.no_update_check,
    )
    root = tk.Tk()
    try:
        root.call("tk", "scaling", 1.25)
    except tk.TclError:
        pass
    app = GuiApp(root, config)
    app.check_for_updates(automatic=True)
    if not args.manual_start:
        app.start_server()
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
