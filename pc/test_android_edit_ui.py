#!/usr/bin/env python3
"""Regression test for the Android EDIT LAYOUT toggles (stdlib only).

Runs against an already-connected device via ``adb``. It launches the app,
opens EDIT LAYOUT, exercises SNAP / GUIDES / SAFE / MULTI in both directions,
checks the UI text plus the rendered button fill, verifies persistence after
DONE, and finally restores the shipped defaults (SNAP off, GUIDES on, SAFE
off, MULTI off) and leaves edit mode.

The fill check uses a small PNG decoder (zlib/struct only) for Android
``screencap -p`` output: colour types 2 and 6, bit depth 8, filters 0-4,
non-interlaced. Colours are compared relatively because the accent and the
muted surface come from the active theme: an ON button must be noticeably
more chromatic than its OFF counterpart, all ON buttons must share the same
fill, and all OFF buttons must share the same muted fill.

Exit status:
    0  all checks passed, or SKIP (no adb device / adb missing / app not installed)
    1  an assertion failed or the test could not be completed

Usage:
    python3 pc/test_android_edit_ui.py
    python3 pc/test_android_edit_ui.py --package com.alamshowkat.lemogamepad
    python3 pc/test_android_edit_ui.py -s <serial> --screenshot-dir /tmp/shots

A macro smoke check adds and deletes one added button via the dialog and
verifies the saved snapshot, when the debug app allows ``run-as`` access.
"""

from __future__ import annotations

import argparse
import collections
import os
import re
import struct
import subprocess
import sys
import time
import zlib
from xml.etree import ElementTree as ET

DEFAULT_PACKAGE = "com.alamshowkat.lemogamepad.debug"
TOGGLES = ("SNAP", "GUIDES", "SAFE", "MULTI")
DEFAULTS = {"SNAP": False, "GUIDES": True, "SAFE": False, "MULTI": False}
# The known navigation fallback was recorded on a 1920x1080 landscape device.
BASE_W, BASE_H = 1920, 1080
DUMP_NAME = "EDIT LAYOUT"

PNG_SIG = b"\x89PNG\r\n\x1a\n"

Node = collections.namedtuple("Node", "text desc bounds")


class Failure(AssertionError):
    """A test assertion or a required adb operation failed."""


class NoAdb(Exception):
    """adb is unavailable, so the test must be skipped."""


def check(condition, message):
    if not condition:
        raise Failure(message)


# --------------------------------------------------------------- adb helpers


class Adb:
    def __init__(self, binary="adb", serial=None, timeout=45):
        self.binary = binary
        self.serial = serial
        self.timeout = timeout

    def _command(self, *args):
        prefix = [self.binary]
        if self.serial:
            prefix += ["-s", self.serial]
        return prefix + [str(a) for a in args]

    def run(self, *args, check_result=True):
        try:
            proc = subprocess.run(
                self._command(*args),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=self.timeout,
            )
        except FileNotFoundError as exc:
            raise NoAdb("adb executable not found on PATH") from exc
        except subprocess.TimeoutExpired as exc:
            raise Failure("adb timed out: " + " ".join(str(a) for a in args)) from exc
        if check_result and proc.returncode != 0:
            detail = (proc.stdout + proc.stderr).decode("utf-8", "replace").strip()
            raise Failure(
                "adb {} failed ({}): {}".format(
                    " ".join(str(a) for a in args), proc.returncode, detail[:400]
                )
            )
        return proc

    def text(self, *args, check_result=True):
        return self.run(*args, check_result=check_result).stdout.decode(
            "utf-8", "replace"
        )


def device_available(adb):
    try:
        proc = adb.run("get-state", check_result=False)
    except NoAdb:
        raise
    return proc.stdout.decode("utf-8", "replace").strip() == "device"


def package_installed(adb, package):
    out = adb.text("shell", "pm", "list", "packages", package, check_result=False)
    return "package:{}".format(package) in out


def hierarchy_size(adb):
    """Return the rotated window size from the uiautomator root node."""
    match = re.search(
        r'<hierarchy\b.*?<node\b[^>]*\bbounds="\[-?\d+,-?\d+\]\[(\d+),(\d+)\]"',
        ui_dump(adb),
        re.S,
    )
    if match:
        return int(match.group(1)), int(match.group(2))
    return None


def screen_size(adb):
    """Return the size of the visible window, including rotation.

    ``wm size`` reports the unrotated panel (for example 1080x2340 on a phone
    held in landscape), while uiautomator reports the rotated window the app
    actually draws into (2340x1080). Use the hierarchy first and fall back to
    ``wm size`` only when no hierarchy is available.
    """
    size = hierarchy_size(adb)
    if size:
        return size

    physical = None
    for line in adb.text("shell", "wm", "size", check_result=False).splitlines():
        match = re.search(r"(\d+)\s*x\s*(\d+)", line)
        if not match:
            continue
        size = (int(match.group(1)), int(match.group(2)))
        if "Override" in line:
            return size
        physical = size
    return physical


def launch_app(adb, package):
    """Start the launcher activity explicitly, with monkey as a fallback."""
    resolved = adb.text(
        "shell", "cmd", "package", "resolve-activity", "--brief", package,
        check_result=False,
    )
    match = re.search(r"^([^\s/]+)/([^\s]+)", resolved, re.MULTILINE)
    if match:
        adb.run("shell", "am", "start", "-n", "{}/{}".format(match.group(1), match.group(2)))
    else:
        adb.run(
            "shell", "monkey", "-p", package,
            "-c", "android.intent.category.LAUNCHER", "1",
        )
    time.sleep(2.0)


# ------------------------------------------------------------ UIAutomator UI


def parse_bounds(raw):
    match = re.search(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", raw or "")
    if not match:
        return None
    x0, y0, x1, y1 = (int(v) for v in match.groups())
    if x1 < x0 or y1 < y0:
        return None
    return (x0, y0, x1, y1)


def _xml_attr(tag, name):
    match = re.search(name + r'\s*=\s*"([^"]*)"', tag)
    return match.group(1) if match else ""


def parse_nodes(xml):
    """Parse uiautomator nodes; fall back to a regex if the dump is malformed."""
    if not xml:
        return []
    nodes = []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        for match in re.finditer(r"<node\b[^>]*>", xml):
            tag = match.group(0)
            nodes.append(
                Node(
                    _xml_attr(tag, "text"),
                    _xml_attr(tag, "content-desc"),
                    parse_bounds(_xml_attr(tag, "bounds")),
                )
            )
    else:
        for element in root.iter("node"):
            nodes.append(
                Node(
                    element.get("text", ""),
                    element.get("content-desc", ""),
                    parse_bounds(element.get("bounds", "")),
                )
            )
    return [node for node in nodes if node.bounds is not None]


def ui_dump(adb):
    for path in ("/sdcard/window_dump.xml", "/data/local/tmp/window_dump.xml"):
        for _ in range(2):
            adb.run("shell", "uiautomator", "dump", path, check_result=False)
            xml = adb.text("shell", "cat", path, check_result=False)
            if "<hierarchy" in xml:
                return xml
            time.sleep(0.15)
    return ""


def find_node(adb, text, timeout=6.0):
    """Find an exact text (or content-desc) match; return its bounds or None."""
    deadline = time.monotonic() + timeout
    while True:
        for node in parse_nodes(ui_dump(adb)):
            if node.text == text or node.desc == text:
                return node.bounds
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.2)


def require_node(adb, text, timeout=6.0):
    bounds = find_node(adb, text, timeout=timeout)
    check(bounds is not None, "UI text not found: {!r}".format(text))
    return bounds


def read_toggle(adb, name, timeout=6.0):
    """Return (is_on, bounds) for a SNAP/GUIDES/SAFE/MULTI edit-bar button."""
    on_text, off_text = name + " ON", name + " OFF"
    deadline = time.monotonic() + timeout
    while True:
        for node in parse_nodes(ui_dump(adb)):
            if node.text == on_text or node.desc == on_text:
                return True, node.bounds
            if node.text == off_text or node.desc == off_text:
                return False, node.bounds
        if time.monotonic() >= deadline:
            raise Failure("toggle not found in UI: {}".format(name))
        time.sleep(0.2)


def tap_xy(adb, x, y, pause=0.3):
    adb.run("shell", "input", "tap", str(int(x)), str(int(y)))
    time.sleep(pause)


def tap_bounds(adb, bounds, pause=0.3):
    x0, y0, x1, y1 = bounds
    tap_xy(adb, (x0 + x1) // 2, (y0 + y1) // 2, pause=pause)


def tap_text(adb, text, timeout=6.0):
    bounds = require_node(adb, text, timeout=timeout)
    tap_bounds(adb, bounds)
    return bounds


def swipe_xy(adb, x0, y0, x1, y1, duration=300, pause=0.5):
    adb.run(
        "shell", "input", "swipe", str(int(x0)), str(int(y0)),
        str(int(x1)), str(int(y1)), str(int(duration)),
    )
    time.sleep(pause)


def set_toggle(adb, name, desired):
    """Force a toggle to a state and assert the UI text followed the tap."""
    state, bounds = read_toggle(adb, name)
    if state != desired:
        tap_bounds(adb, bounds)
        time.sleep(0.2)  # don't read a pre-tap dump
        state, bounds = read_toggle(adb, name)
    check(
        state == desired,
        "{} text did not become {!r} (got {!r})".format(
            name, name + (" ON" if desired else " OFF"),
            name + (" ON" if state else " OFF"),
        ),
    )
    return bounds


def flip_toggle(adb, name):
    """Tap a toggle and assert its text flipped. Returns (new_state, bounds)."""
    before, bounds = read_toggle(adb, name)
    tap_bounds(adb, bounds)
    time.sleep(0.2)
    after, bounds = read_toggle(adb, name)
    check(
        after is not before,
        "{} text did not change after tap (stayed {!r})".format(
            name, name + (" ON" if after else " OFF")
        ),
    )
    return after, bounds


# ------------------------------------------------------------- navigation


def enter_edit(adb, scale_x=1.0, scale_y=1.0):
    """Open EDIT LAYOUT from the pad through the settings sheet."""
    nodes = parse_nodes(ui_dump(adb))
    if any(node.text in ("SNAP ON", "SNAP OFF") for node in nodes):
        return  # already editing

    bounds = find_node(adb, DUMP_NAME, timeout=1.0)
    if bounds is None:
        size = hierarchy_size(adb) or (int(1920 * scale_x), int(1080 * scale_y))
        # The status pill is drawn in the custom pad view, so it has no
        # uiautomator text. It is centred at the top of the window.
        tap_xy(adb, size[0] // 2, int(size[1] * 0.065))
        require_node(adb, "CONTROL CENTRE", timeout=8.0)

        if find_node(adb, "STYLE", timeout=2.0):
            tap_text(adb, "STYLE")
        if find_node(adb, DUMP_NAME, timeout=0.4) is None:
            x = int(size[0] * 0.78)
            top = int(size[1] * 0.78)
            bottom = int(size[1] * 0.52)
            for _ in range(10):
                bounds = find_node(adb, DUMP_NAME, timeout=0.4)
                if bounds:
                    break
                swipe_xy(adb, x, top, x, bottom, duration=250, pause=0.35)
            if bounds is None:
                # Scroll the other way in case the sheet remembered a deeper
                # position from a previous run.
                for _ in range(10):
                    bounds = find_node(adb, DUMP_NAME, timeout=0.4)
                    if bounds:
                        break
                    swipe_xy(adb, x, bottom, x, top, duration=250, pause=0.35)
        if bounds is None:
            bounds = require_node(adb, DUMP_NAME, timeout=5.0)
    tap_bounds(adb, bounds)

    state, _ = read_toggle(adb, "SNAP", timeout=8.0)
    print("[edit-ui] edit bar open (SNAP {})".format("ON" if state else "OFF"), flush=True)


def leave_edit(adb):
    tap_text(adb, "DONE")
    check(
        find_node(adb, "DONE", timeout=2.0) is None,
        "edit bar is still visible after DONE",
    )


# ------------------------------------------------------------ PNG decoding


class PngImage:
    """A clipped, unfiltered 8-bit RGB(A) PNG scanline set."""

    def __init__(self, width, height, bpp, x0, rows):
        self.width = width
        self.height = height
        self.bpp = bpp
        self.x0 = x0
        self.rows = rows  # {absolute y: bytearray of col x0..x1}

    def row(self, y):
        return self.rows.get(y)

    def pixel(self, row, x):
        offset = (x - self.x0) * self.bpp
        if offset < 0 or offset + 3 > len(row):
            return None
        return (row[offset], row[offset + 1], row[offset + 2])


def decode_png(data, clip=None):
    """Decode a non-interlaced 8-bit PNG (colour type 2 or 6).

    ``clip`` is (x0, y0, x1, y1) in image coordinates. Only the columns up to
    x1 of each row are unfiltered, which keeps this pure-Python decoder fast
    enough for a 1080p screencap when sampling a small edit-bar button.
    """
    start = data.find(PNG_SIG)
    check(start >= 0, "screencap did not return a PNG")
    data = data[start:]
    pos = len(PNG_SIG)
    width = height = bit_depth = colour_type = interlace = None
    idat = []
    while pos + 8 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        if pos + 12 + length > len(data):
            break
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            (width, height, bit_depth, colour_type, _compression,
             _filter_method, interlace) = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"IDAT":
            idat.append(chunk)
        elif kind == b"IEND":
            break

    check(bool(width and height), "PNG is missing IHDR")
    check(bit_depth == 8, "unsupported PNG bit depth: {}".format(bit_depth))
    check(colour_type in (2, 6), "unsupported PNG colour type: {}".format(colour_type))
    check(interlace == 0, "interlaced PNG is not supported")
    check(bool(idat), "PNG has no IDAT data")

    bpp = 3 if colour_type == 2 else 4
    stride = width * bpp
    if clip is None:
        x0, y0, x1, y1 = 0, 0, width - 1, height - 1
    else:
        cx0, cy0, cx1, cy1 = clip
        x0 = max(0, min(width - 1, int(cx0)))
        y0 = max(0, min(height - 1, int(cy0)))
        x1 = max(x0, min(width - 1, int(cx1)))
        y1 = max(y0, min(height - 1, int(cy1)))

    raw = zlib.decompressobj().decompress(b"".join(idat))
    check(
        len(raw) >= height * (stride + 1),
        "PNG image data is truncated ({} bytes)".format(len(raw)),
    )

    limit = (x1 + 1) * bpp
    rows = {}
    previous = bytearray(limit)
    pointer = 0
    for y in range(height):
        filter_type = raw[pointer]
        pointer += 1
        line = bytearray(raw[pointer:pointer + limit])
        pointer += stride

        if filter_type == 0:  # None
            pass
        elif filter_type == 1:  # Sub
            for i in range(bpp, limit):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filter_type == 2:  # Up
            for i in range(limit):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:  # Average
            for i in range(limit):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for i in range(limit):
                left = line[i - bpp] if i >= bpp else 0
                up = previous[i]
                up_left = previous[i - bpp] if i >= bpp else 0
                prediction = left + up - up_left
                pa = abs(prediction - left)
                pb = abs(prediction - up)
                pc = abs(prediction - up_left)
                if pa <= pb and pa <= pc:
                    predictor = left
                elif pb <= pc:
                    predictor = up
                else:
                    predictor = up_left
                line[i] = (line[i] + predictor) & 0xFF
        else:
            raise Failure("unsupported PNG filter type: {}".format(filter_type))

        if y0 <= y <= y1:
            rows[y] = line
        previous = line

    # Rows are unfiltered from column 0 up to x1, even when clipped; keep x0 at
    # zero so pixel(row, x) can use absolute image coordinates.
    return PngImage(width, height, bpp, 0, rows)


def screencap(adb):
    data = adb.run("exec-out", "screencap", "-p", check_result=False).stdout
    if PNG_SIG not in data[:64]:
        # Some adb versions corrupt binary output when using `shell`.
        data = adb.run("shell", "screencap", "-p", check_result=False).stdout
        data = data.replace(b"\r\n", b"\n")
    check(PNG_SIG in data, "could not obtain a PNG screencap")
    return data


def bounds_to_clip(bounds, scale_x, scale_y, padding=12):
    x0, y0, x1, y1 = bounds
    return (
        int(x0 * scale_x) - padding,
        int(y0 * scale_y) - padding,
        int(x1 * scale_x) + padding,
        int(y1 * scale_y) + padding,
    )


def capture_button(adb, bounds, scale_x, scale_y, screenshot_dir=None, label=""):
    data = screencap(adb)
    image = decode_png(data, clip=bounds_to_clip(bounds, scale_x, scale_y))
    if screenshot_dir:
        safe = re.sub(r"[^A-Za-z0-9_.-]", "_", label) or "toggle"
        try:
            os.makedirs(screenshot_dir, exist_ok=True)
            with open(os.path.join(screenshot_dir, safe + ".png"), "wb") as handle:
                handle.write(data)
        except OSError as exc:
            print("[edit-ui] WARN: could not save screenshot: {}".format(exc), flush=True)
    return image


def dominant_fill(image, bounds, scale_x, scale_y):
    """Return the most common quantised RGB value inside a button's bounds."""
    ax0 = max(0, int(round(bounds[0] * scale_x)))
    ay0 = max(0, int(round(bounds[1] * scale_y)))
    ax1 = min(image.width - 1, int(round(bounds[2] * scale_x)))
    ay1 = min(image.height - 1, int(round(bounds[3] * scale_y)))
    check(ax1 > ax0 and ay1 > ay0, "toggle bounds are outside the screencap")
    inset = max(2, int(min(ax1 - ax0, ay1 - ay0) * 0.15))
    ax0, ax1 = ax0 + inset, ax1 - inset
    ay0, ay1 = ay0 + inset, ay1 - inset
    check(ax1 > ax0 and ay1 > ay0, "toggle bounds are too small to sample")

    buckets = {}
    for y in range(ay0, ay1 + 1):
        row = image.row(y)
        if row is None:
            continue
        for x in range(ax0, ax1 + 1):
            colour = image.pixel(row, x)
            if colour is None:
                continue
            key = (colour[0] >> 3, colour[1] >> 3, colour[2] >> 3)
            bucket = buckets.setdefault(key, [0, 0, 0, 0])
            bucket[0] += 1
            bucket[1] += colour[0]
            bucket[2] += colour[1]
            bucket[3] += colour[2]
    check(bool(buckets), "no pixels sampled from toggle bounds")
    best = max(buckets.values(), key=lambda item: item[0])
    return (best[1] // best[0], best[2] // best[0], best[3] // best[0])


def chroma(colour):
    return max(colour) - min(colour)


def colour_distance(left, right):
    return sum((a - b) ** 2 for a, b in zip(left, right)) ** 0.5


# ------------------------------------------------------------------ checks


def verify_toggles(adb, scale_x, scale_y, screenshot_dir):
    """Flip every toggle both ways and check text plus rendered fill."""
    observed = {}
    for name in TOGGLES:
        set_toggle(adb, name, False)  # normalise before exercising both edges

        state, bounds = flip_toggle(adb, name)
        check(state is True, "{} did not turn ON".format(name))
        on_image = capture_button(adb, bounds, scale_x, scale_y, screenshot_dir,
                                  name + "_ON")
        on_fill = dominant_fill(on_image, bounds, scale_x, scale_y)

        state, bounds = flip_toggle(adb, name)
        check(state is False, "{} did not turn OFF".format(name))
        off_image = capture_button(adb, bounds, scale_x, scale_y, screenshot_dir,
                                   name + "_OFF")
        off_fill = dominant_fill(off_image, bounds, scale_x, scale_y)

        check(
            chroma(on_fill) >= 25,
            "{} ON fill is not accent-coloured: {}".format(name, on_fill),
        )
        check(
            chroma(on_fill) >= chroma(off_fill) + 10,
            "{} ON fill is not more saturated than OFF: on={} off={}".format(
                name, on_fill, off_fill
            ),
        )
        check(
            colour_distance(on_fill, off_fill) >= 40,
            "{} ON/OFF fills are visually identical: on={} off={}".format(
                name, on_fill, off_fill
            ),
        )
        observed[name] = (on_fill, off_fill)
        print(
            "[edit-ui] {:<6} ON={} (chroma {}) OFF={} (chroma {})".format(
                name, on_fill, chroma(on_fill), off_fill, chroma(off_fill)
            ),
            flush=True,
        )

    on_fills = [values[0] for values in observed.values()]
    off_fills = [values[1] for values in observed.values()]
    check(
        max(colour_distance(a, b) for i, a in enumerate(on_fills) for b in on_fills[i + 1:])
        <= 45,
        "ON fills do not share one accent colour: {}".format(on_fills),
    )
    check(
        max(colour_distance(a, b) for i, a in enumerate(off_fills) for b in off_fills[i + 1:])
        <= 45,
        "OFF fills do not share one muted colour: {}".format(off_fills),
    )


def verify_persistence(adb, scale_x, scale_y):
    """Set a distinctive state, leave edit mode, re-enter and compare text."""
    distinctive = {"SNAP": True, "GUIDES": False, "SAFE": True, "MULTI": True}
    for name in TOGGLES:
        set_toggle(adb, name, distinctive[name])

    leave_edit(adb)
    enter_edit(adb, scale_x, scale_y)

    for name in TOGGLES:
        state, _ = read_toggle(adb, name)
        check(
            state is distinctive[name],
            "{} did not persist across DONE: expected {}, got {}".format(
                name,
                name + (" ON" if distinctive[name] else " OFF"),
                name + (" ON" if state else " OFF"),
            ),
        )
    print("[edit-ui] all four toggles persisted across DONE", flush=True)


def restore_defaults(adb, scale_x=1.0, scale_y=1.0):
    """Best-effort cleanup: leave the app on the pad with shipped defaults."""
    try:
        read_toggle(adb, "SNAP", timeout=1.5)
    except Failure:
        try:
            enter_edit(adb, scale_x, scale_y)
        except Failure as exc:
            print(
                "WARN: could not reopen EDIT LAYOUT for cleanup: {}".format(exc),
                flush=True,
            )
            return
    for name in TOGGLES:
        set_toggle(adb, name, DEFAULTS[name])
    leave_edit(adb)
    print(
        "[edit-ui] defaults restored and edit mode closed: {}".format(
            ", ".join(
                "{}={}".format(name, "ON" if DEFAULTS[name] else "OFF")
                for name in TOGGLES
            )
        ),
        flush=True,
    )


def saved_layout(adb, package):
    """Read the persisted layout string, or None if run-as is unavailable."""
    xml = adb.text(
        "shell", "run-as", package, "cat", "shared_prefs/vpad.xml",
        check_result=False,
    )
    match = re.search(r'name="layout">([^<]*)', xml)
    return match.group(1) if match else None


def macro_add_delete_smoke(adb, package):
    """ADD -> OK -> DEL, verified through the persisted layout snapshot."""
    if saved_layout(adb, package) is None:
        print(
            "SKIP macro add/delete smoke: cannot read {} prefs "
            "(release builds are not debuggable)".format(package),
            flush=True,
        )
        return

    tap_text(adb, "RESET")
    time.sleep(0.4)
    check("m1:" not in (saved_layout(adb, package) or ""),
          "RESET did not clear the added-button slot m1")

    tap_text(adb, "ADD")
    require_node(adb, "BUTTON FUNCTION", timeout=5.0)
    tap_text(adb, "OK")
    time.sleep(0.4)
    added = saved_layout(adb, package) or ""
    check("m1:" in added, "ADD did not persist m1 in the saved layout")
    check("__meta:" in added, "added button lost the __meta layout marker")

    tap_text(adb, "DEL")
    time.sleep(0.4)
    deleted = saved_layout(adb, package) or ""
    check("m1:" not in deleted, "DEL did not remove m1 from the saved layout")
    print("[edit-ui] macro ADD/DEL smoke passed", flush=True)


# --------------------------------------------------------------------- main


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Regression-test the Android EDIT LAYOUT toggles over adb."
    )
    parser.add_argument(
        "package", nargs="?", default=DEFAULT_PACKAGE,
        help="app package (default: %(default)s)",
    )
    parser.add_argument(
        "-p", "--package", dest="package_option", default=None,
        help="app package; overrides the positional argument",
    )
    parser.add_argument(
        "-s", "--serial", default=None,
        help="adb device serial, for when more than one device is connected",
    )
    parser.add_argument(
        "--adb", default="adb", help="path to the adb executable (default: %(default)s)"
    )
    parser.add_argument(
        "--screenshot-dir", default=None,
        help="optional directory in which to save per-toggle PNG screenshots",
    )
    args = parser.parse_args(argv)
    args.package = args.package_option or args.package
    return args


def main(argv=None):
    args = parse_args(argv)
    adb = Adb(binary=args.adb, serial=args.serial)

    try:
        if not device_available(adb):
            print(
                "SKIP: no adb device connected (adb get-state is not 'device')",
                flush=True,
            )
            return 0
        if not package_installed(adb, args.package):
            print(
                "SKIP: package {} is not installed on the connected device".format(
                    args.package
                ),
                flush=True,
            )
            return 0
    except NoAdb as exc:
        print("SKIP: {}".format(exc), flush=True)
        return 0

    scale_x = scale_y = 1.0
    try:
        launch_app(adb, args.package)
        # Measure after launch: the app is landscape-locked, while ``wm size``
        # reports the phone's unrotated panel. uiautomator bounds and the
        # screencap already use the same window pixels, so no scaling is needed.
        size = screen_size(adb)
        scale_x = scale_y = 1.0
        print(
            "[edit-ui] package={} window size={}".format(args.package, size),
            flush=True,
        )

        enter_edit(adb, scale_x, scale_y)
        verify_toggles(adb, scale_x, scale_y, args.screenshot_dir)
        verify_persistence(adb, scale_x, scale_y)
        macro_add_delete_smoke(adb, args.package)
    except Failure as exc:
        print("FAIL: {}".format(exc), flush=True)
        return 1
    except Exception as exc:  # keep CI output actionable for unexpected errors
        print("FAIL: unexpected {}: {}".format(type(exc).__name__, exc), flush=True)
        return 1
    finally:
        try:
            restore_defaults(adb, scale_x, scale_y)
        except Exception as exc:
            print("WARN: cleanup failed: {}".format(exc), flush=True)

    print("PASS: EDIT LAYOUT toggle regression test", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
