#!/usr/bin/env python3
"""Check that signed APKs describe their own version using the published notes."""

import argparse
from pathlib import Path
import re
import subprocess
import sys
from zipfile import BadZipFile, ZipFile


def apk_version(badging: str) -> str:
    match = re.search(r"^package:.*\bversionName='([^']+)'", badging, re.M)
    if not match:
        raise ValueError("Could not read APK version from aapt badging")
    return match.group(1)


def verify_bundled_notes(apk: Path, notes: Path, expected_version: str, packaged_version: str) -> None:
    published = notes.read_bytes()
    text = published.decode("utf-8").strip()
    if not text or "release notes pending" in text.lower():
        raise ValueError("Release notes must contain final user-facing changes")
    if not expected_version or packaged_version != expected_version:
        raise ValueError("APK version does not match release metadata version")
    try:
        with ZipFile(apk) as archive:
            bundled_version = archive.read("assets/release-notes.version").decode("utf-8").strip()
            bundled_notes = archive.read("assets/release-notes.md")
    except (KeyError, BadZipFile) as error:
        raise ValueError("Missing or invalid bundled release notes") from error
    if bundled_version != packaged_version:
        raise ValueError("Bundled notes version does not match the APK version")
    if bundled_notes != published:
        raise ValueError("Bundled notes differ from the published release notes")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("apks", type=Path, nargs="+")
    args = parser.parse_args()
    try:
        for apk in args.apks:
            badging = subprocess.check_output([args.aapt, "dump", "badging", str(apk)], text=True)
            verify_bundled_notes(apk, args.notes, args.version, apk_version(badging))
            print(f"Verified bundled notes: {apk.name}")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Release-note verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
