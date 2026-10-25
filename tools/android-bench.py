#!/usr/bin/env python3
"""Syclop's local-emulator bench. No LAN connection, real-device targeting, or account actions."""
import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

PROFILES = {
    "tv34-arm64": ("system-images;android-34;android-tv;arm64-v8a", "syclop-tv34-arm64"),
    "tv36-16k-arm64": ("system-images;android-36;google-tv-ps16k;arm64-v8a", "syclop-tv36-16k-arm64"),
}
PACKAGE = "com.dintal.syclop"
TABS = ("Home", "Movies", "TV", "Anime", "Documentary", "Kids")
KEYS = {"UP": "KEYCODE_DPAD_UP", "DOWN": "KEYCODE_DPAD_DOWN", "LEFT": "KEYCODE_DPAD_LEFT", "RIGHT": "KEYCODE_DPAD_RIGHT"}
MAX_CAPTURE = 32 * 1024 * 1024


class BenchError(Exception):
    pass


def serial_port(value):
    match = re.fullmatch(r"emulator-([0-9]{4})", value or "")
    if not match or not 5554 <= int(match[1]) <= 5682 or int(match[1]) % 2:
        raise BenchError("Use an explicit emulator-NNNN serial with an even console port 5554..5682; TCP/LAN/physical serials are rejected.")
    return int(match[1])


def avd_name(value):
    if not re.fullmatch(r"syclop-[A-Za-z0-9_-]{1,64}", value):
        raise BenchError("Bench AVD names must start with syclop- and contain letters, digits, underscores or hyphens.")
    return value


def default_home():
    if os.environ.get("SYCLOP_BENCH_HOME"):
        return Path(os.environ["SYCLOP_BENCH_HOME"]).expanduser()
    if sys.platform == "darwin":
        return Path.home() / "Library/Caches/Syclop/android-bench"
    return Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "syclop/android-bench"


def run(args, timeout=30, env=None, binary=False, stdin=None, check=True):
    try:
        result = subprocess.run([str(x) for x in args], input=stdin, capture_output=True,
                                timeout=timeout, env=env, text=not binary)
    except subprocess.TimeoutExpired:
        raise BenchError("A bench command exceeded its deadline.") from None
    except OSError:
        raise BenchError("A required bench executable could not be started.") from None
    if check and result.returncode:
        # Never echo raw ADB/UI/service output or environment values into a report.
        raise BenchError("A bench command failed (exit %d); raw output was not recorded." % result.returncode)
    if len(result.stdout) > MAX_CAPTURE or len(result.stderr) > MAX_CAPTURE:
        raise BenchError("Command output exceeded the capture limit.")
    return result


def tab_row(xml):
    """Find the six actual header tabs geometrically; a sidebar Home is not the header."""
    if len(xml) > 2 * 1024 * 1024:
        raise BenchError("Accessibility snapshot exceeded its limit.")
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    parents = {child: parent for parent in root.iter() for child in parent}
    choices = {label: [] for label in TABS}
    for node in root.iter("node"):
        label = node.get("text") or node.get("content-desc")
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if label in choices and match:
            x1, y1, x2, y2 = map(int, match.groups())
            if x2 > x1 and y2 > y1:
                choices[label].append((node, (x1+x2)/2, (y1+y2)/2, y2-y1))
    if any(not choices[label] for label in TABS):
        return None
    # Bound combinations even if a malformed UI repeats labels many times.
    for group in itertools.islice(itertools.product(*(choices[label][:8] for label in TABS)), 4096):
        if any(group[i][1] >= group[i+1][1] for i in range(5)):
            continue
        if max(x[2] for x in group) - min(x[2] for x in group) > max(x[3] for x in group):
            continue
        focused = set()
        for label, (node, _, _, _) in zip(TABS, group):
            for _ in range(4):
                if node.get("focused") == "true":
                    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                    if bounds:
                        x1, y1, x2, y2 = map(int, bounds.groups())
                        # A focused Compose root or whole header cannot prove which tab has focus.
                        others = [item for name, item in zip(TABS, group) if name != label]
                        if not any(x1 <= item[1] <= x2 and y1 <= item[2] <= y2 for item in others):
                            focused.add(label)
                    break
                node = parents.get(node)
                if node is None:
                    break
        return {"focused": next(iter(focused)) if len(focused) == 1 else None}
    return None


class Bench:
    def __init__(self, options):
        self.options = options
        self.project = Path(options.project).expanduser().resolve()
        sdk = options.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise BenchError("Set --sdk, ANDROID_HOME or ANDROID_SDK_ROOT explicitly.")
        self.sdk = Path(sdk).expanduser().resolve()
        emulator = options.emulator or os.environ.get("SYCLOP_EMULATOR")
        self.emulator = Path(emulator).expanduser().resolve() if emulator else self.sdk / "emulator/emulator"
        self.home = Path(options.home).expanduser().resolve()
        self.avds = self.home / "avd"
        self.validate_home()
        self.env = os.environ.copy()
        for key in ("ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_ADDRESS", "ANDROID_SERIAL"):
            self.env.pop(key, None)
        self.server_port = options.adb_server_port
        self.env.update(ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk), ANDROID_AVD_HOME=str(self.avds),
                        ANDROID_USER_HOME=str(self.home / "android"), ANDROID_EMULATOR_HOME=str(self.home / "emulator"),
                        ANDROID_ADB_SERVER_PORT=str(self.server_port),
                        ADB_SERVER_SOCKET="tcp:127.0.0.1:%d" % self.server_port, ADB_MDNS_AUTO_CONNECT="0")
        self.server_ready = False
        self.adb = self.sdk / "platform-tools/adb"
        self.image, preset = PROFILES[options.profile]
        self.image = options.image or self.image
        self.name = avd_name(options.avd or preset)
        self.serial = options.serial
        self.report = {"schema": 1, "command": options.command, "steps": []}
        self.report_dir = None

    def validate_home(self):
        forbidden = [self.project.resolve(), (Path.home() / "Desktop").resolve(),
                     (Path.home() / "Library/Mobile Documents/com~apple~CloudDocs").resolve(),
                     (Path.home() / "Library/CloudStorage").resolve()]
        if any(self.home == root or root in self.home.parents for root in forbidden):
            raise BenchError("Mutable bench home must be outside the workspace, Desktop and known cloud-sync roots.")
        if self.avds.is_symlink() or self.avds.resolve().parent != self.home:
            raise BenchError("The bench AVD directory must not be a symlink or escape its cache home.")
        for name in ("reports", "android", "emulator", "logs"):
            directory = self.home / name
            if directory.is_symlink() or directory.resolve().parent != self.home:
                raise BenchError("Bench state directories must not be symlinks or escape the cache home.")

    def initialize_home(self):
        self.validate_home()
        # Existing roots may have been prepared manually. Never change their permissions.
        self.home.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.avds.mkdir(exist_ok=True, mode=0o700)
        for name in ("android", "emulator"):
            (self.home / name).mkdir(exist_ok=True, mode=0o700)

    def tool(self, name):
        path = self.sdk / "cmdline-tools/latest/bin" / name
        if not path.is_file():
            raise BenchError("Install Android command-line tools/latest or select the correct SDK.")
        return path

    def ensure_server(self):
        if not self.server_ready:
            startup_env = self.env.copy()
            startup_env.pop("ADB_SERVER_SOCKET", None)
            startup_env.pop("ANDROID_ADB_SERVER_PORT", None)
            # Current adb allows local autostart for localhost; 127.0.0.1 in -L is
            # classified as a remote host. Subsequent clients use explicit 127.0.0.1.
            run([self.adb, "-L", "tcp:localhost:%d" % self.server_port, "start-server"],
                timeout=15, env=startup_env)
            self.server_ready = True

    def device(self, *args, timeout=15, binary=False, check=True):
        serial_port(self.serial)
        self.ensure_server()
        return run([self.adb, "-H", "127.0.0.1", "-P", str(self.server_port), "-s", self.serial, *args],
                   timeout=timeout, env=self.env, binary=binary, check=check)

    def verify_emulator(self):
        serial_port(self.serial)
        if self.device("get-state").stdout.strip() != "device":
            raise BenchError("Selected emulator is not online.")
        qemu = self.device("shell", "getprop", "ro.kernel.qemu").stdout.strip()
        if qemu != "1" and self.device("shell", "getprop", "ro.boot.qemu").stdout.strip() != "1":
            raise BenchError("Target did not identify itself as an emulator; refusing device commands.")
        self.report["serial"] = self.serial
        self.report["adb_server_port"] = self.server_port

    def begin_report(self):
        self.initialize_home()
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        self.report_dir = self.home / "reports" / (stamp + "-" + self.options.command)
        self.report_dir.mkdir(parents=True, mode=0o700)

    def save_report(self, ok, message=None):
        if self.report_dir:
            self.report["passed"] = ok
            if message:
                self.report["message"] = message
            if not ok and self.report.get("serial") == self.serial:
                try:
                    self.report["failure_screenshot"] = self.capture("failure")
                except (BenchError, OSError, subprocess.TimeoutExpired):
                    self.report["failure_screenshot"] = None
            (self.report_dir / "report.json").write_text(json.dumps(self.report, indent=2) + "\n")
            print("Report:", self.report_dir / "report.json")

    def capture(self, label):
        data = self.device("exec-out", "screencap", "-p", binary=True).stdout
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise BenchError("Emulator did not return a PNG screenshot.")
        path = self.report_dir / (label + ".png")
        path.write_bytes(data)
        return path.name

    def setup(self):
        if not re.fullmatch(r"system-images;android-\d+;[a-z0-9-]+;(arm64-v8a|x86_64)", self.image):
            raise BenchError("Select one explicit Android system-image package.")
        machine = platform.machine().lower()
        if machine in ("arm64", "aarch64") and not self.image.endswith(";arm64-v8a"):
            raise BenchError("This ARM host needs a host-compatible arm64 system image.")
        # Setup installs only the explicitly selected image. It never creates or starts an AVD.
        result = subprocess.run([str(self.tool("sdkmanager")), "--sdk_root=" + str(self.sdk),
                                 "platform-tools", "emulator", self.image], env=self.env, timeout=1800)
        if result.returncode:
            raise BenchError("SDK setup did not complete. Review licenses and the selected package in SDK Manager.")
        print("Selected SDK packages installed; no emulator started.")

    def create(self):
        self.initialize_home()
        directory = self.avds / (self.name + ".avd")
        if directory.exists() or (self.avds / (self.name + ".ini")).exists():
            raise BenchError("AVD already exists; it was not changed. Use start, or explicit reset for a bench-owned AVD.")
        if shutil.disk_usage(self.home).free < 3 * 1024**3:
            raise BenchError("Keep at least 3 GiB free before creating this AVD.")
        run([self.tool("avdmanager"), "create", "avd", "--name", self.name, "--package", self.image,
             "--device", self.options.device, "--path", directory], timeout=120, env=self.env, stdin="no\n")
        config = directory / "config.ini"
        values = {"hw.ramSize": "2048", "hw.cpu.ncore": "2", "hw.lcd.width": "1280", "hw.lcd.height": "720",
                  "hw.lcd.density": "213", "disk.dataPartition.size": "1536M", "hw.sdCard": "no",
                  "fastboot.forceColdBoot": "yes", "fastboot.forceFastBoot": "no", "showDeviceFrame": "no"}
        lines = [line for line in config.read_text().splitlines() if line.split("=", 1)[0].strip() not in values]
        config.write_text("\n".join(lines + [key + "=" + value for key, value in values.items()]) + "\n")
        (directory / ".syclop-bench-owned.json").write_text(json.dumps({"schema": 1, "avd": self.name,
            "directory": str(directory.resolve()), "home": str(self.home), "image": self.image}) + "\n")
        print("Created", self.name, "in", self.avds, "(no emulator started).")

    def start(self):
        port = serial_port(self.serial)
        self.initialize_home()
        directory = self.avds / (self.name + ".avd")
        if directory.is_symlink() or directory.resolve().parent != self.avds.resolve():
            raise BenchError("The selected AVD must not escape the isolated bench directory.")
        if not (directory / "config.ini").is_file():
            raise BenchError("No matching AVD in the isolated bench home. Run create first.")
        if self.device("get-state", timeout=5, check=False).stdout.strip() == "device":
            raise BenchError("That emulator serial is already online; use status or choose a free even port.")
        system_dir = self.sdk.joinpath(*self.image.split(";"))
        if not system_dir.is_dir():
            raise BenchError("Selected system image is not installed; run setup first.")
        args = [self.emulator, "-avd", self.name, "-sysdir", system_dir, "-port", str(port), "-no-snapshot", "-no-boot-anim", "-no-metrics", "-crash-report-mode", "disabled", "-gpu", self.options.gpu]
        if self.options.headless:
            args.append("-no-window")
        logs = self.home / "logs"
        logs.mkdir(exist_ok=True, mode=0o700)
        log_path = logs / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ") + "-emulator.log")
        try:
            with log_path.open("xb") as output:
                os.chmod(log_path, 0o600)
                child = subprocess.Popen([str(x) for x in args], env=self.env, stdout=output,
                                         stderr=subprocess.STDOUT, start_new_session=True)
        except OSError:
            raise BenchError("Emulator executable unavailable; run setup separately.") from None
        print("Private emulator log:", log_path, flush=True)
        deadline = time.monotonic() + self.options.boot_timeout
        while time.monotonic() < deadline:
            if child.poll() is not None:
                raise BenchError("Emulator exited before boot; inspect the private startup log.")
            ready = self.device("shell", "getprop", "sys.boot_completed", timeout=5, check=False).stdout.strip()
            if ready == "1":
                self.verify_emulator()
                print("Emulator ready:", self.serial)
                return
            time.sleep(1)
        raise BenchError("Emulator boot deadline expired; use stop explicitly if it is still running.")

    def status(self):
        self.verify_emulator()
        facts = {}
        for label, prop in (("api", "ro.build.version.sdk"), ("abi", "ro.product.cpu.abi")):
            value = self.device("shell", "getprop", prop).stdout.strip()
            facts[label] = value if re.fullmatch(r"[a-zA-Z0-9_-]{1,32}", value) else "unknown"
        page = self.device("shell", "getconf", "PAGESIZE", check=False).stdout.strip()
        facts["page_size"] = int(page) if page.isdigit() else None
        self.report["device"] = facts
        self.report["screenshot"] = self.capture("status")
        print(json.dumps(facts, indent=2))

    def build(self):
        script = self.project / "build-syclop.sh"
        args = ["bash", str(script), "--release"] + (["--test"] if self.options.test else [])
        build_env = os.environ.copy()
        build_env.update(ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk))
        try:
            result = subprocess.run(args, timeout=2400, env=build_env)
        except (OSError, subprocess.TimeoutExpired):
            raise BenchError("Development build did not complete before its deadline.") from None
        if result.returncode:
            raise BenchError("Development build failed; no emulator install was attempted.")
        if not self.apk().is_file():
            raise BenchError("Expected development universal APK was not produced.")
        print("Development universal APK ready:", self.apk())

    def apk(self):
        return Path(self.options.apk).expanduser().resolve() if self.options.apk else self.project.parent / "dist/development/syclop-release.apk"

    def install(self):
        self.verify_emulator()
        apk = self.apk().resolve()
        development = (self.project.parent / "dist/development").resolve()
        if development not in apk.parents or not apk.is_file():
            raise BenchError("Install requires an existing APK under dist/development; no legacy/stable fallback.")
        aapt = self.sdk / "build-tools/36.0.0/aapt2"
        badging = run([aapt, "dump", "badging", apk], env=self.env).stdout
        if not re.search(r"^package: name='com\.dintal\.syclop' ", badging, re.M):
            raise BenchError("APK package is not Syclop.")
        floor = re.search(r"^minSdkVersion:'(\d+)'", badging, re.M)
        api = self.device("shell", "getprop", "ro.build.version.sdk").stdout.strip()
        if not floor or not api.isdigit() or int(floor[1]) > int(api):
            raise BenchError("APK minimum SDK does not match this emulator.")
        result = self.device("install", "-r", str(apk), timeout=180)
        if not result.stdout.splitlines() or result.stdout.splitlines()[-1].strip() != "Success":
            raise BenchError("Emulator did not confirm package installation.")
        self.report["apk"] = {"file": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
        self.report["screenshot"] = self.capture("after-install")

    def hierarchy(self, allow_unfocused=False):
        focus = self.device("shell", "dumpsys", "window").stdout
        if not any("mCurrentFocus=" in line and " " + PACKAGE + "/" in line for line in focus.splitlines()):
            if allow_unfocused:
                return None
            raise BenchError("Syclop is not the focused app; no navigation continued.")
        output = self.device("exec-out", "uiautomator", "dump", "/dev/tty", timeout=15).stdout
        start, end = output.find("<?xml"), output.rfind("</hierarchy>")
        if start < 0 or end < start:
            return None
        return tab_row(output[start:end + len("</hierarchy>")])

    def key(self, direction):
        # No CENTER/ENTER, text input, account approval, playback, or mutating Settings action.
        self.device("shell", "input", "keyevent", KEYS[direction])
        time.sleep(0.25)

    def wait_header(self, label, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            row = self.hierarchy()
            if row and row["focused"] == label:
                return row
            time.sleep(0.25)
        raise BenchError("Keyboard focus did not reach the expected category header.")

    def smoke(self):
        self.verify_emulator()
        resolved = self.device("shell", "cmd", "package", "resolve-activity", "--brief", "-a",
            "android.intent.action.MAIN", "-c", "android.intent.category.LEANBACK_LAUNCHER", "-p", PACKAGE).stdout
        component = next((line.strip() for line in reversed(resolved.splitlines())
            if re.fullmatch(r"com\.dintal\.syclop/[A-Za-z0-9_.$]+", line.strip())), None)
        if not component:
            raise BenchError("Syclop TV launcher activity could not be resolved.")
        self.device("shell", "am", "force-stop", PACKAGE)
        self.device("shell", "am", "start", "-W", "-n", component, timeout=30)
        deadline = time.monotonic() + self.options.ui_timeout
        row = None
        while time.monotonic() < deadline:
            row = self.hierarchy(allow_unfocused=True)
            if row:
                break
            time.sleep(1)
        if not row:
            self.report["screenshot"] = self.capture("blocked-home")
            running = bool(self.device("shell", "pidof", PACKAGE, check=False).stdout.strip())
            self.report["app_process_running"] = running
            if not running:
                raise BenchError("Syclop exited before Home was observed; inspect the failure screenshot and local crash diagnostics.")
            raise BenchError("Home's six English tabs were not observed; complete setup/import manually and rerun. No account action was taken.")
        for direction in ["UP"] * 6 + ["RIGHT"] + ["UP"] * 3:
            if row["focused"]:
                break
            self.key(direction)
            row = self.hierarchy() or {"focused": None}
        if not row["focused"]:
            raise BenchError("Header keyboard focus could not be verified; no blind navigation continued.")
        for _ in range(5):
            if row["focused"] == "Home":
                break
            previous = TABS[TABS.index(row["focused"]) - 1]
            self.key("LEFT")
            row = self.wait_header(previous)
        for index, label in enumerate(TABS):
            if not row or row["focused"] != label:
                raise BenchError("Category focus order did not match the six-tab contract.")
            step = {"tab": label, "preview": self.capture("%02d-%s" % (index+1, label.lower()))}
            # Content can still be loading after tab selection. Retry Down only
            # while the same tab remains focused; never walk blindly through rows.
            entry_deadline = time.monotonic() + min(self.options.ui_timeout, 20)
            below = row
            while below and below["focused"] == label and time.monotonic() < entry_deadline:
                self.key("DOWN")
                below = self.hierarchy()
                if below and below["focused"] == label:
                    time.sleep(0.5)
            step["left_header_on_down"] = bool(below and below["focused"] is None)
            if not step["left_header_on_down"]:
                self.report["steps"].append(step)
                raise BenchError("Down entry could not be verified for the selected category.")
            step["entry"] = self.capture("%02d-%s-entry" % (index+1, label.lower()))
            self.key("UP")
            row = self.wait_header(label)
            if not row or row["focused"] != label:
                raise BenchError("Up did not return to the selected category header.")
            self.report["steps"].append(step)
            if index < 5:
                self.key("RIGHT")
                row = self.wait_header(TABS[index + 1])
        self.key("RIGHT")
        self.wait_header("Kids")
        for label in reversed(TABS[:-1]):
            self.key("LEFT")
            row = self.hierarchy()
            # Home intentionally restores saved content focus on remount. Verify
            # that its Up route reaches Home rather than treating this as a lost key.
            if label == "Home" and row and row["focused"] is None:
                self.report["home_restored_content"] = self.capture("07-home-restored-content")
                self.key("UP")
            self.wait_header(label)
        self.report["final"] = self.capture("07-home-return")

    def stop(self):
        self.verify_emulator()
        self.device("emu", "kill")
        print("Stop requested for", self.serial, "(AVD data preserved).")

    def reset(self):
        if not self.options.confirm_reset:
            raise BenchError("Reset requires --confirm-reset and deletes only this bench-owned AVD.")
        self.initialize_home()
        directory = self.avds / (self.name + ".avd")
        marker = directory / ".syclop-bench-owned.json"
        try:
            owner = json.loads(marker.read_text())
        except (OSError, ValueError):
            raise BenchError("No bench ownership marker; refusing to delete this AVD.") from None
        if directory.is_symlink() or directory.resolve().parent != self.avds.resolve() or owner != {
            "schema": 1, "avd": self.name, "directory": str(directory.resolve()), "home": str(self.home), "image": self.image}:
            raise BenchError("AVD ownership did not match; nothing was deleted.")
        if not self.serial:
            raise BenchError("Supply its explicit emulator serial so running state can be checked before reset.")
        if self.device("get-state", timeout=5, check=False).stdout.strip() == "device":
            raise BenchError("Stop the selected emulator before reset.")
        if list(directory.glob("*.lock")) or list(directory.glob("*.lock/*")):
            raise BenchError("AVD has active or stale lock files; reset was refused.")
        ini = self.avds / (self.name + ".ini")
        path_lines = [line[5:] for line in ini.read_text().splitlines() if line.startswith("path=")]
        if len(path_lines) != 1 or Path(path_lines[0]).resolve() != directory.resolve():
            raise BenchError("AVD index path does not match the owned directory; reset was refused.")
        run([self.tool("avdmanager"), "delete", "avd", "--name", self.name], env=self.env)
        print("Deleted only bench-owned AVD", self.name)


def bounded_integer(low, high):
    def parse(value):
        try:
            number = int(value)
        except ValueError:
            raise argparse.ArgumentTypeError("Use an integer from %d to %d." % (low, high)) from None
        if not low <= number <= high:
            raise argparse.ArgumentTypeError("Use an integer from %d to %d." % (low, high))
        return number
    return parse


def server_port(value):
    number = bounded_integer(1024, 65535)(value)
    if number == 5037:
        raise argparse.ArgumentTypeError("The usual physical-device ADB server 5037 is reserved; use the isolated bench server 5038.")
    return number


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--sdk", help="Explicit Android SDK; otherwise ANDROID_HOME/ANDROID_SDK_ROOT")
    cli.add_argument("--emulator", help="Optional emulator executable; otherwise SYCLOP_EMULATOR or the SDK bundle")
    cli.add_argument("--project", default=str(Path(__file__).resolve().parents[1]), help="Syclop source checkout")
    cli.add_argument("--home", default=str(default_home()), help="Mutable bench cache outside synced workspaces")
    cli.add_argument("--serial", help="Required for device actions: emulator-5580, never LAN/physical transport")
    cli.add_argument("--adb-server-port", type=server_port, default=5038,
                     help="Isolated loopback ADB server; default 5038 avoids the usual physical-device server")
    cli.add_argument("--profile", choices=PROFILES, default="tv34-arm64")
    cli.add_argument("--image", help="Explicit package override, e.g. host-compatible x86_64 image on Linux")
    cli.add_argument("--avd", help="AVD name; default from selected profile")
    cli.add_argument("--device", default="tv_720p", help="Exact avdmanager TV device identifier")
    commands = cli.add_subparsers(dest="command", required=True)
    commands.add_parser("setup", help="Install selected SDK image/emulator; never start a device")
    commands.add_parser("create", help="Create isolated 720p/2GB/2-core AVD; never replace one")
    start = commands.add_parser("start")
    start.add_argument("--headless", action="store_true")
    start.add_argument("--gpu", choices=("software", "auto", "host", "swiftshader", "swangle"), default="software",
                       help="Guest graphics renderer; software is the reliable local UI-test default")
    start.add_argument("--boot-timeout", type=bounded_integer(30, 600), default=240)
    commands.add_parser("status")
    build = commands.add_parser("build", help="Existing build-syclop.sh --release; development artifacts only")
    build.add_argument("--test", action="store_true")
    install = commands.add_parser("install")
    install.add_argument("--apk", help="Optional artifact under dist/development")
    smoke = commands.add_parser("smoke")
    smoke.add_argument("--ui-timeout", type=bounded_integer(15, 180), default=90)
    commands.add_parser("stop")
    reset = commands.add_parser("reset", help="Explicit deletion, ownership marker required")
    reset.add_argument("--confirm-reset", action="store_true")
    return cli


def main(argv=None):
    options = parser().parse_args(argv)
    # Keep optional subcommand fields predictable without ever echoing arguments or environment.
    for key, value in (("apk", None), ("test", False)):
        if not hasattr(options, key):
            setattr(options, key, value)
    bench = None
    try:
        bench = Bench(options)
        if options.command in ("status", "install", "smoke"):
            bench.begin_report()
        getattr(bench, options.command)()
        bench.save_report(True)
        return 0
    except (BenchError, OSError, subprocess.TimeoutExpired):
        # Fixed controlled errors only. Never persist raw command output, UI XML, credentials or logcat.
        message = str(sys.exc_info()[1]) if isinstance(sys.exc_info()[1], BenchError) else "Local bench operation failed."
        if bench:
            bench.save_report(False, message)
        print("Bench:", message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
