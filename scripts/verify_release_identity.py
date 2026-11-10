#!/usr/bin/env python3
"""Verify signed release APK identity before emitting public metadata."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from zipfile import BadZipFile, ZipFile


APK_NAMES = ("syclop-release.apk", "syclop-arm64-release.apk")
KNOWN_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
HEX256 = r"[0-9a-fA-F]{64}"
FULL_SHA = r"[0-9a-fA-F]{40}"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def native_inputs(path):
    native = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(native, dict), "Invalid native manifest")
    signer = native.get("originalUpdateSignerCertificateSha256")
    require(isinstance(signer, str) and re.fullmatch(HEX256, signer), "Invalid trusted signer digest")
    require(type(native.get("minimumAndroidApi")) is int and native["minimumAndroidApi"] == 24,
            "Native minimum API must be 24")
    abis = native.get("abis")
    require(isinstance(abis, list) and abis and all(isinstance(abi, str) and abi in KNOWN_ABIS for abi in abis)
            and len(abis) == len(set(abis)), "Invalid native ABI set")
    companion = native.get("sourceCompanion")
    require(isinstance(companion, dict), "Missing native source companion")
    name, digest = companion.get("assetName"), companion.get("sha256")
    require(isinstance(name, str) and re.fullmatch(r"syclop-native-sources-[0-9A-Za-z.-]+\.tar\.gz", name),
            "Invalid native companion filename")
    require(isinstance(digest, str) and re.fullmatch(HEX256, digest), "Invalid native companion digest")
    require(type(companion.get("bytes")) is int and companion["bytes"] > 0, "Invalid native companion size")
    url = companion.get("releaseAssetPath")
    require(isinstance(url, str) and re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[A-Za-z0-9_.-]+/" + re.escape(name), url),
            "Invalid native companion release URL")
    return signer.lower(), sorted(abis), {
        "file": name, "sha256": digest.lower(), "bytes": companion["bytes"], "releaseAssetPath": url,
    }


def tool_output(executable, arguments, label):
    result = subprocess.run([executable, *arguments], text=True, capture_output=True)
    require(result.returncode == 0, f"{label} verification failed")
    return result.stdout


def source_revision(apk, expected):
    with ZipFile(apk) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == "META-INF/version-control-info.textproto"]
        require(len(entries) == 1, "Missing or ambiguous APK source metadata")
        require(entries[0].file_size <= 65536, "APK source metadata exceeds size limit")
        with archive.open(entries[0]) as entry:
            data = entry.read(65537)
        require(len(data) <= 65536, "APK source metadata exceeds size limit")
    text = data.decode("utf-8")
    # Accept the AGP repository textproto fields, rejecting unparsed or duplicate data.
    blocks = re.compile(r"repositories\s*:?\s*\{([^{}]*)\}")
    require(not blocks.sub("", text).strip(), "Invalid APK source metadata")
    field = re.compile(r'([a-z_]+)\s*:\s*("(?:[^"\\]|\\.)*"|[A-Z]+)')
    roots = []
    for block in blocks.finditer(text):
        body = block.group(1)
        require(not field.sub("", body).strip(), "Invalid APK source metadata fields")
        fields = {}
        for match in field.finditer(body):
            key, value = match.groups()
            require(key in {"system", "local_root_path", "revision"} and key not in fields,
                    "Unknown or duplicate APK source metadata field")
            fields[key] = json.loads(value) if value.startswith('"') else value
        require(set(fields) == {"system", "local_root_path", "revision"}, "Incomplete APK source metadata")
        if fields["local_root_path"] == "$PROJECT_DIR":
            roots.append(fields)
    require(len(roots) == 1 and roots[0]["system"] == "GIT", "APK needs one root GIT repository")
    revision = roots[0]["revision"]
    require(isinstance(revision, str) and re.fullmatch(FULL_SHA, revision) and revision.lower() == expected,
            "APK source revision does not match requested commit")
    return revision.lower()


def artifact_identity(apk, args, trusted_signer, expected_abis):
    badging = tool_output(args.aapt, ["dump", "badging", str(apk)], "aapt")
    packages = re.findall(r"^package: (.*)$", badging, re.M)
    require(len(packages) == 1, "Missing or ambiguous APK package")
    package = {}
    for name in ("name", "versionCode", "versionName"):
        values = re.findall(r"(?:^|\s)" + name + r"='([^']*)'", packages[0])
        require(len(values) == 1, "Missing or ambiguous APK package field")
        package[name] = values[0]
    require(package["name"] == "com.dintal.syclop", "Unexpected APK package")
    require(package["versionName"] == args.version, "APK versionName mismatch")
    require(re.fullmatch(r"[0-9]+", package["versionCode"]) and int(package["versionCode"]) > 0,
            "APK versionCode must be positive")
    sdk = re.findall(r"^sdkVersion:'([0-9]+)'$", badging, re.M)
    require(sdk == ["24"], "APK minimum API mismatch")
    abi_lines = re.findall(r"^native-code:\s*(.*)$", badging, re.M)
    require(len(abi_lines) == 1, "Missing or ambiguous APK ABI metadata")
    abi_text = abi_lines[0]
    abis = re.findall(r"'([^']+)'", abi_text)
    require(not re.sub(r"'[^']+'", "", abi_text).strip() and sorted(abis) == expected_abis,
            "APK ABI set mismatch")
    require(not re.search(r"^application-debuggable(?:\s|$)", badging, re.M), "APK must not be debuggable")
    certificates = tool_output(args.apksigner, ["verify", "--print-certs", str(apk)], "apksigner")
    digest_lines = re.findall(r"^Signer #\d+ certificate SHA-256 digest:.*$", certificates, re.M)
    require(len(digest_lines) == 1 and set(re.findall(r"^Signer #(\d+)\b", certificates, re.M)) == {"1"},
            "Missing or ambiguous APK signer digest")
    signer = re.fullmatch(r"Signer #1 certificate SHA-256 digest: (" + HEX256 + ")", digest_lines[0])
    require(signer and signer.group(1).lower() == trusted_signer, "APK signer does not match trusted certificate")
    revision = source_revision(apk, args.source_commit)
    digest = hashlib.sha256()
    size = 0
    with apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
            size += len(chunk)
    return {
        "file": apk.name, "sha256": digest.hexdigest(), "bytes": size,
        "package": package["name"], "versionName": package["versionName"],
        "versionCode": int(package["versionCode"]), "minimumApi": 24,
        "abis": sorted(abis), "debuggable": False,
        "signerSha256": signer.group(1).lower(), "sourceCommit": revision,
    }


def write_identity(output, identity):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent, delete=False) as stream:
            temporary = stream.name
            json.dump(identity, stream, indent=2)
            stream.write("\n")
        os.replace(temporary, output)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--native-manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--repository")
    parser.add_argument("apks", nargs="*", type=Path)
    args = parser.parse_args()
    try:
        inputs = [*args.apks, args.native_manifest, Path(args.aapt), Path(args.apksigner)]
        require(all(args.output.resolve() != path.resolve() for path in inputs),
                "Output must not overwrite a verifier input")
        args.output.unlink(missing_ok=True)
        require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", args.version), "Invalid release version")
        require(re.fullmatch(FULL_SHA, args.source_commit), "Invalid full source commit")
        args.source_commit = args.source_commit.lower()
        require(args.repository is None or (re.fullmatch(r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+", args.repository)
                and args.repository.split("/")[1] not in {".", ".."}),
                "Invalid public repository")
        require(len(args.apks) == 2 and sorted(apk.name for apk in args.apks) == sorted(APK_NAMES),
                "Exactly the universal and ARM64 release APKs are required")
        signer, abis, companion = native_inputs(args.native_manifest)
        paths = {apk.name: apk for apk in args.apks}
        artifacts = [artifact_identity(paths[name], args, signer, abis if index == 0 else ["arm64-v8a"])
                     for index, name in enumerate(APK_NAMES)]
        require(artifacts[0]["versionCode"] == artifacts[1]["versionCode"], "APK versionCodes differ")
        identity = dict(artifacts[0], nativeSourceCompanion=companion, artifacts=artifacts)
        if args.repository:
            identity["sourceUrl"] = f"https://github.com/{args.repository}/tree/{identity['sourceCommit']}"
        write_identity(args.output, identity)
    except (OSError, ValueError, TypeError, KeyError, BadZipFile, RuntimeError, NotImplementedError):
        print("Release identity verification failed; no identity was generated", file=sys.stderr)
        return 1
    print("Verified universal and ARM64 release identity")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
