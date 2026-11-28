#!/usr/bin/env python3
"""Check that every user-facing version label matches rust/Cargo.toml.

    python3 tools/check_versions.py
    python3 tools/check_versions.py --expected 2.2.5

The Cargo manifest is canonical. Cargo needs three-part semver, so 2.2.0 is
shown as 2.2 everywhere a person reads the version. --expected is used by the
release workflow to check the tag before a build starts; it must also agree
with the manifest. Every mismatch is reported before the script exits.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_RE = r"\d+(?:\.\d+){1,2}"
# Third-party versions and OS requirements are not app version labels.
NON_APP_VERSIONS = {"1.22.0", "7.0"}


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def normalize(version: str) -> str:
    """Turn the Cargo spelling 2.2.0 into the public label 2.2."""
    version = version.strip().removeprefix("v")
    parts = version.split(".")
    if len(parts) == 3 and parts[2] == "0":
        return f"{parts[0]}.{parts[1]}"
    return version


def line_of(text: str, position: int) -> int:
    return text.count("\n", 0, position) + 1


def manifest_version(errors: list[str]) -> str:
    text = read("rust/Cargo.toml")
    package = re.search(r"(?m)^\[package\][ \t]*\n(.*?)(?=^\[|\Z)", text, re.S)
    found = re.search(r'(?m)^\s*version\s*=\s*"([0-9.]+)"', package.group(1)) if package else None
    if found is None:
        errors.append("rust/Cargo.toml: no [package] version found")
        return ""
    return normalize(found.group(1))


def check_all(text: str, relative: str, pattern: str, expected: str, errors: list[str],
              description: str) -> None:
    """Every occurrence of pattern must be the expected label."""
    matches = list(re.finditer(pattern, text, re.M))
    if not matches:
        errors.append(f"{relative}: no {description} found")
        return
    for match in matches:
        value = match.group(1)
        if normalize(value) != expected:
            errors.append(
                f"{relative}:{line_of(text, match.start())}: {description} is "
                f"{value!r}, expected {expected!r}"
            )


def check_first(text: str, relative: str, pattern: str, expected: str,
                errors: list[str], description: str) -> None:
    """Only the first occurrence (the newest entry) has to carry the label."""
    match = re.search(pattern, text, re.M)
    if match is None:
        errors.append(f"{relative}: no {description} found")
    elif normalize(match.group(1)) != expected:
        errors.append(
            f"{relative}:{line_of(text, match.start())}: {description} is "
            f"{match.group(1)!r}, expected {expected!r}"
        )


def check_web_spans(text: str, expected: str, errors: list[str]) -> None:
    relative = "pc/web/index.html"
    found: set[str] = set()
    for match in re.finditer(
        r'<span id="(app-version|footer-version)">([^<]*)</span>', text
    ):
        found.add(match.group(1))
        if match.group(2) != f"v{expected}":
            errors.append(
                f"{relative}:{line_of(text, match.start())}: {match.group(1)} is "
                f"{match.group(2)!r}, expected 'v{expected}'"
            )
    for span in {"app-version", "footer-version"} - found:
        errors.append(f"{relative}: missing <span id=\"{span}\">")


def check_readme(text: str, expected: str, errors: list[str]) -> None:
    relative = "README.md"
    check_first(
        text, relative,
        rf"\*\*App source version:\*\*\s*({VERSION_RE})\.",
        expected, errors, "app-source version",
    )
    check_first(
        text, relative, rf"\*\*({VERSION_RE})\*\* APK", expected, errors, "APK label"
    )
    check_first(
        text, relative, rf"© Alam showkat\*\* · v({VERSION_RE})",
        expected, errors, "footer version",
    )
    check_first(
        text, relative, rf"^### New in ({VERSION_RE})\s*$",
        expected, errors, "New in heading",
    )


def check_site(text: str, expected: str, errors: list[str]) -> None:
    relative = "docs/index.html"
    check_all(
        text, relative, rf'"softwareVersion":\s*"({VERSION_RE})"',
        expected, errors, "softwareVersion",
    )
    check_all(
        text, relative, rf"latest release ({VERSION_RE})",
        expected, errors, "downloads release label",
    )
    check_all(
        text, relative, rf"All ({VERSION_RE}) binaries",
        expected, errors, "downloads binaries label",
    )
    if not re.search(rf'\bv{re.escape(expected)}\b', text):
        errors.append(f"{relative}: no 'v{expected}' badge label found")

    # Catch stale copies of the current release label anywhere on the page,
    # including data-badge attributes and hand-written prose. Dependency and
    # OS versions are explicitly exempt from this scan.
    for match in re.finditer(rf"(?<![\d.])(v?)({VERSION_RE})(?![\d.])", text):
        token = match.group(2)
        if token in NON_APP_VERSIONS or normalize(token) == expected:
            continue
        if match.group(1) and text[max(0, match.start() - 4):match.start()] == "tag/":
            continue
        errors.append(
            f"{relative}:{line_of(text, match.start())}: stale version label "
            f"{match.group(0)!r}, expected {expected!r}"
        )


def check_review_title(text: str, expected: str, errors: list[str]) -> None:
    first = text.splitlines()[0] if text.splitlines() else ""
    sections = [part.strip() for part in first.split("\n")[0].split("—")[-1].split("/")]
    newest = sections[-1] if sections else ""
    if normalize(newest) != expected:
        errors.append(
            f"docs/REVIEW.md:1: review title ends in {newest!r}, expected {expected!r}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--expected", metavar="VERSION", help="check against this tag instead of Cargo.toml"
    )
    args = parser.parse_args()

    errors: list[str] = []
    cargo = manifest_version(errors)
    if args.expected:
        expected = normalize(args.expected)
        if cargo and cargo != expected:
            errors.append(
                f"rust/Cargo.toml: package version is {cargo!r}, expected {expected!r}"
            )
    else:
        expected = cargo
    if not re.fullmatch(VERSION_RE, expected):
        errors.append(f"invalid version label {expected!r}")
        print("\n".join(errors), file=sys.stderr)
        return 1

    check_all(read("rust/src/gui/main.rs"), "rust/src/gui/main.rs",
              r'const APP_VERSION:\s*&str\s*=\s*"([0-9.]+)"',
              expected, errors, "APP_VERSION")
    check_all(read("android/app/build.gradle.kts"), "android/app/build.gradle.kts",
              r'versionName\s*=\s*"([0-9.]+)"',
              expected, errors, "versionName")
    check_all(
        read("android/app/src/main/java/com/alamshowkat/lemogamepad/Theme.kt"),
        "android/app/src/main/java/com/alamshowkat/lemogamepad/Theme.kt",
        r'const val VERSION\s*=\s*"([0-9.]+)"',
        expected, errors, "Brand.VERSION",
    )
    check_all(read("pc/desktop_gui.py"), "pc/desktop_gui.py",
              r'^APP_VERSION\s*=\s*"([0-9.]+)"',
              expected, errors, "APP_VERSION")
    check_all(read("pc/web/app.js"), "pc/web/app.js",
              r'const VERSION\s*=\s*"([0-9.]+)"',
              expected, errors, "browser VERSION")
    check_web_spans(read("pc/web/index.html"), expected, errors)
    check_readme(read("README.md"), expected, errors)
    check_site(read("docs/index.html"), expected, errors)
    check_first(read("CHANGELOG.md"), "CHANGELOG.md",
                rf"^##\s+({VERSION_RE})\s*$",
                expected, errors, "top changelog entry")
    check_first(read("docs/LIMITATIONS.md"), "docs/LIMITATIONS.md",
                rf"© Alam showkat\*\* · v({VERSION_RE})\s*$",
                expected, errors, "footer version")
    check_review_title(read("docs/REVIEW.md"), expected, errors)

    # One physical line can be matched by two checks; keep each distinct error.
    unique_errors = list(dict.fromkeys(errors))
    if unique_errors:
        print(f"Version check FAILED for {expected} ({len(unique_errors)} mismatch(es)):")
        for error in unique_errors:
            print(f"  - {error}")
        return 1
    print(f"Version check passed: {expected}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
