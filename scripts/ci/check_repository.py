#!/usr/bin/env python3
"""Lightweight repository hygiene checks; not an Android build or security audit."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = (
    "README.md",
    "LICENSE",
    "ROADMAP.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "DEPENDENCY_POLICY.md",
    "docs/architecture.md",
    "docs/quality-gates.md",
    "docs/threat-model.md",
)
MAX_TRACKED_BINARY_BYTES = 25 * 1024 * 1024
FORBIDDEN_SUFFIXES = {".gguf", ".safetensors", ".pem", ".key", ".p12", ".pfx"}
ERRORS: list[str] = []


def fail(message: str) -> None:
    ERRORS.append(message)


def tracked_paths() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    )
    return [item.decode("utf-8", "surrogateescape") for item in result.stdout.split(b"\0") if item]


def main() -> int:
    for relative in REQUIRED:
        path = ROOT / relative
        if not path.is_file():
            fail(f"Required file missing: {relative}")
            continue
        text = path.read_text(encoding="utf-8")
        if path.suffix.lower() == ".md" and not text.strip().startswith("# "):
            fail(f"Expected a Markdown H1 at the beginning: {relative}")
        for number, line in enumerate(text.splitlines(), start=1):
            if line.rstrip() != line:
                fail(f"Trailing whitespace: {relative}:{number}")

    for relative in tracked_paths():
        path = ROOT / relative
        suffix = path.suffix.lower()
        if suffix in FORBIDDEN_SUFFIXES:
            fail(f"Potential model/secret artifact tracked: {relative}")
        if path.is_file():
            try:
                size = path.stat().st_size
            except OSError as exc:
                fail(f"Cannot stat tracked file {relative}: {exc}")
                continue
            if size > MAX_TRACKED_BINARY_BYTES and suffix not in {".md", ".txt", ".json", ".xml"}:
                fail(f"Large tracked file ({size} bytes): {relative}")

    if ERRORS:
        print("Repository quality checks failed:", file=sys.stderr)
        for error in ERRORS:
            print(f" - {error}", file=sys.stderr)
        return 1

    print(f"Repository quality checks passed ({len(REQUIRED)} required documents).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
