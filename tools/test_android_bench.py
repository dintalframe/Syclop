"""Pure local tests: no SDK, ADB server, emulator, build, network or account access."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).with_name("android-bench.py")
spec = importlib.util.spec_from_file_location("android_bench", SCRIPT)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def header(focus="Movies", sidebar=True, parent_focus=True, root_focus=False):
    root = ET.Element("hierarchy")
    surface = ET.SubElement(root, "node", {"bounds": "[0,0][1280,720]", "focused": str(root_focus).lower()})
    if sidebar:
        ET.SubElement(surface, "node", {"text": "Home", "bounds": "[0,110][80,160]", "focused": "true"})
    for index, label in enumerate(bench.TABS):
        x = 100 + index * 180
        button = ET.SubElement(surface, "node", {"bounds": f"[{x},0][{x+160},60]",
            "focused": str(parent_focus and label == focus).lower()})
        ET.SubElement(button, "node", {"text": label, "bounds": f"[{x+10},10][{x+150},50]",
            "focused": str(not parent_focus and label == focus).lower()})
    return ET.tostring(root, encoding="unicode")


class EmulatorBoundaryTest(unittest.TestCase):
    def options(self, *arguments):
        return bench.parser().parse_args(["--sdk", "/fake-sdk", "--home", "/fake-cache", *arguments])

    def test_tcp_physical_and_invalid_serials_are_rejected(self):
        for value in (None, "", "192.0.2.40:5555", "127.0.0.1:5555", "SHIELD123", "emulator-5581", "emulator-5038", "emulator-9999"):
            with self.subTest(value=value), self.assertRaises(bench.BenchError):
                bench.serial_port(value)
        self.assertEqual(5580, bench.serial_port("emulator-5580"))

    def test_invalid_target_is_rejected_before_server_start(self):
        instance = bench.Bench(self.options("--serial", "192.0.2.40:5555", "status"))
        with patch.object(bench, "run") as runner, self.assertRaises(bench.BenchError):
            instance.device("get-state")
        runner.assert_not_called()

    def test_every_device_command_has_explicit_loopback_server_and_serial(self):
        instance = bench.Bench(self.options("--serial", "emulator-5580", "status"))
        answer = subprocess.CompletedProcess([], 0, "device\n", "")
        with patch.object(bench, "run", return_value=answer) as runner:
            instance.device("get-state")
            instance.device("shell", "getprop", "ro.kernel.qemu")
        calls = [call.args[0] for call in runner.call_args_list]
        self.assertEqual([instance.adb, "-L", "tcp:localhost:5038", "start-server"], calls[0])
        self.assertNotIn("ADB_SERVER_SOCKET", runner.call_args_list[0].kwargs["env"])
        self.assertNotIn("ANDROID_ADB_SERVER_PORT", runner.call_args_list[0].kwargs["env"])
        for command in calls[1:]:
            self.assertEqual([instance.adb, "-H", "127.0.0.1", "-P", "5038", "-s", "emulator-5580"], command[:7])
        self.assertEqual("tcp:127.0.0.1:5038", instance.env["ADB_SERVER_SOCKET"])
        self.assertEqual("0", instance.env["ADB_MDNS_AUTO_CONNECT"])
        self.assertEqual("5038", instance.env["ANDROID_ADB_SERVER_PORT"])

    def test_qemu_identity_required_even_for_emulator_shaped_serial(self):
        instance = bench.Bench(self.options("--serial", "emulator-5580", "status"))
        values = ["device\n", "0\n", "0\n"]
        with patch.object(instance, "device", side_effect=[subprocess.CompletedProcess([], 0, value, "") for value in values]), self.assertRaises(bench.BenchError):
            instance.verify_emulator()
        self.assertNotIn("serial", instance.report)

    def test_unowned_avd_cannot_be_reset(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parents[2], prefix=".bench-test-") as temporary, patch.object(bench.Path, "home", return_value=Path("/fixture-user")):
            instance = bench.Bench(bench.parser().parse_args(["--sdk", "/fake-sdk", "--project", "/source/media-client", "--home", temporary,
                "--serial", "emulator-5580", "reset", "--confirm-reset"]))
            directory = Path(temporary) / "avd/syclop-tv34-arm64.avd"
            directory.mkdir(parents=True)
            (directory / "config.ini").write_text("preserve=this\n")
            with patch.object(bench, "run") as runner, self.assertRaises(bench.BenchError):
                instance.reset()
            runner.assert_not_called()
            self.assertEqual("preserve=this\n", (directory / "config.ini").read_text())

    def test_mutable_home_refuses_checkout_desktop_and_cloud_paths(self):
        for home in ("/source/media-client", "/source/media-client/cache", "/fixture-user/Desktop/bench", "/fixture-user/Library/CloudStorage/bench"):
            with self.subTest(home=home), patch.object(bench.Path, "home", return_value=Path("/fixture-user")), self.assertRaises(bench.BenchError):
                bench.Bench(bench.parser().parse_args(["--sdk", "/fake-sdk", "--project", "/source/media-client", "--home", home, "status"]))

    def test_mutable_home_refuses_avd_symlink_escape(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parents[2], prefix=".bench-test-") as temporary, patch.object(bench.Path, "home", return_value=Path("/fixture-user")):
            cache = Path(temporary) / "cache"
            cache.mkdir()
            (cache / "avd").symlink_to(Path(temporary) / "foreign-avd", target_is_directory=True)
            with self.assertRaises(bench.BenchError):
                bench.Bench(bench.parser().parse_args(["--sdk", "/fake-sdk", "--project", "/source/media-client", "--home", str(cache), "status"]))

    def test_initialize_never_changes_existing_home_permissions(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parents[2], prefix=".bench-test-") as temporary, patch.object(bench.Path, "home", return_value=Path("/fixture-user")):
            root = Path(temporary)
            root.chmod(0o755)
            instance = bench.Bench(bench.parser().parse_args(["--sdk", "/fake-sdk", "--project", "/source/media-client", "--home", temporary, "status"]))
            instance.initialize_home()
            self.assertEqual(0o755, root.stat().st_mode & 0o777)

    def test_reset_rejects_owned_avd_with_lock_before_delete(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parents[2], prefix=".bench-test-") as temporary, patch.object(bench.Path, "home", return_value=Path("/fixture-user")):
            instance = bench.Bench(bench.parser().parse_args(["--sdk", "/fake-sdk", "--project", "/source/media-client", "--home", temporary,
                "--serial", "emulator-5580", "reset", "--confirm-reset"]))
            directory = instance.avds / (instance.name + ".avd")
            directory.mkdir(parents=True)
            (directory / ".syclop-bench-owned.json").write_text(json.dumps({"schema": 1, "avd": instance.name,
                "directory": str(directory.resolve()), "home": str(instance.home), "image": instance.image}))
            (directory / "hardware-qemu.ini.lock").mkdir()
            with patch.object(instance, "device", return_value=subprocess.CompletedProcess([], 1, "", "")), patch.object(bench, "run") as runner, self.assertRaises(bench.BenchError):
                instance.reset()
            runner.assert_not_called()
            self.assertTrue(directory.is_dir())

    def test_install_rejects_legacy_or_stable_artifact_before_install(self):
        for apk in ("/project/dist/syclop-release.apk", "/project/dist/stable/syclop-release.apk"):
            instance = bench.Bench(self.options("--project", "/project/media-client", "--serial", "emulator-5580", "install", "--apk", apk))
            with self.subTest(apk=apk), patch.object(instance, "verify_emulator"), patch.object(bench, "run") as runner, self.assertRaises(bench.BenchError):
                instance.install()
            runner.assert_not_called()


class HeaderRecognitionTest(unittest.TestCase):
    def test_exact_six_tab_row_ignores_focused_sidebar_home(self):
        self.assertEqual({"focused": "Movies"}, bench.tab_row(header()))

    def test_direct_text_node_focus_is_supported(self):
        self.assertEqual({"focused": "Anime"}, bench.tab_row(header("Anime", parent_focus=False)))

    def test_focused_compose_root_does_not_prove_a_tab_is_focused(self):
        self.assertEqual({"focused": None}, bench.tab_row(header(None, sidebar=False, root_focus=True)))

    def test_ambiguous_tab_focus_is_not_reported_as_success(self):
        xml = header("Home", sidebar=False)
        root = ET.fromstring(xml)
        buttons = list(root.find("node"))
        buttons[1].set("focused", "true")
        self.assertEqual({"focused": None}, bench.tab_row(ET.tostring(root, encoding="unicode")))

    def test_partial_header_or_malformed_xml_does_not_pass(self):
        self.assertIsNone(bench.tab_row(header().replace('text="Kids"', 'text="Other"')))
        self.assertIsNone(bench.tab_row("not an accessibility snapshot"))

    def test_ui_dump_is_bounded(self):
        with self.assertRaises(bench.BenchError):
            bench.tab_row(" " * (2 * 1024 * 1024 + 1))

    def test_down_not_leaving_header_fails_instead_of_claiming_pass(self):
        options = bench.parser().parse_args(["--sdk", "/fake-sdk", "--home", "/fake-cache", "--serial", "emulator-5580", "smoke", "--ui-timeout", "15"])
        instance = bench.Bench(options)
        component = subprocess.CompletedProcess([], 0, "com.dintal.syclop/.MainActivity\n", "")
        ticks = iter(range(0, 1000, 10))
        with patch.object(instance, "verify_emulator"), patch.object(instance, "device", return_value=component), patch.object(instance, "hierarchy", side_effect=lambda allow_unfocused=False: {"focused": "Home"}), patch.object(instance, "capture", return_value="preview.png"), patch.object(instance, "key") as key, patch.object(bench.time, "monotonic", side_effect=lambda: next(ticks)), patch.object(bench.time, "sleep"), self.assertRaises(bench.BenchError):
            instance.smoke()
        self.assertEqual(False, instance.report["steps"][0]["left_header_on_down"])
        key.assert_called_once_with("DOWN")

    def test_keys_cannot_approve_login_or_start_playback(self):
        self.assertEqual({"UP", "DOWN", "LEFT", "RIGHT"}, set(bench.KEYS))
        self.assertNotIn("CENTER", bench.KEYS)
        self.assertNotIn("ENTER", bench.KEYS)


class InstallRuntimeRegressionTest(unittest.TestCase):
    def test_streamed_and_plain_success_responses_are_accepted(self):
        for response in ("Success\n", "Performing Streamed Install\nSuccess\n"):
            with self.subTest(response=response), tempfile.TemporaryDirectory(dir=SCRIPT.parents[2], prefix=".bench-test-") as temporary:
                project = Path(temporary) / "media-client"
                apk = project.parent / "dist/development/syclop-release.apk"
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b"fixture APK, never sent to a device")
                options = bench.parser().parse_args(["--sdk", "/fake-sdk", "--home", "/fake-cache",
                    "--project", str(project), "--serial", "emulator-5580", "install"])
                instance = bench.Bench(options)
                badging = subprocess.CompletedProcess([], 0, "package: name='com.dintal.syclop' versionCode='1'\nminSdkVersion:'24'\n", "")
                answers = [subprocess.CompletedProcess([], 0, "34\n", ""), subprocess.CompletedProcess([], 0, response, "")]
                with patch.object(instance, "verify_emulator"), patch.object(bench, "run", return_value=badging), patch.object(instance, "device", side_effect=answers) as device, patch.object(instance, "capture", return_value="after-install.png") as capture:
                    instance.install()
                self.assertEqual(("install", "-r", str(apk)), device.call_args_list[1].args)
                self.assertEqual("syclop-release.apk", instance.report["apk"]["file"])
                self.assertEqual("after-install.png", instance.report["screenshot"])
                capture.assert_called_once_with("after-install")

    def test_failure_or_missing_final_success_does_not_record_installed_apk(self):
        for response in ("", "Performing Streamed Install\nFailure [INSTALL_FAILED_TEST_ONLY]\n", "Success\nFailure [INSTALL_FAILED_TEST_ONLY]\n"):
            with self.subTest(response=response), tempfile.TemporaryDirectory(dir=SCRIPT.parents[2], prefix=".bench-test-") as temporary:
                project = Path(temporary) / "media-client"
                apk = project.parent / "dist/development/syclop-release.apk"
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b"fixture APK, never sent to a device")
                options = bench.parser().parse_args(["--sdk", "/fake-sdk", "--home", "/fake-cache",
                    "--project", str(project), "--serial", "emulator-5580", "install"])
                instance = bench.Bench(options)
                badging = subprocess.CompletedProcess([], 0, "package: name='com.dintal.syclop' versionCode='1'\nminSdkVersion:'24'\n", "")
                answers = [subprocess.CompletedProcess([], 0, "34\n", ""), subprocess.CompletedProcess([], 0, response, "")]
                with patch.object(instance, "verify_emulator"), patch.object(bench, "run", return_value=badging), patch.object(instance, "device", side_effect=answers), patch.object(instance, "capture") as capture, self.assertRaises(bench.BenchError):
                    instance.install()
                self.assertNotIn("apk", instance.report)
                capture.assert_not_called()


class LaunchFocusRuntimeRegressionTest(unittest.TestCase):
    def instance(self):
        return bench.Bench(bench.parser().parse_args(["--sdk", "/fake-sdk", "--home", "/fake-cache",
            "--serial", "emulator-5580", "smoke", "--ui-timeout", "15"]))

    def test_unfocused_launch_wait_does_not_dump_or_navigate_another_app(self):
        instance = self.instance()
        other = subprocess.CompletedProcess([], 0, "mCurrentFocus=Window{test u0 com.google.android.tvlauncher/.MainActivity}\n", "")
        with patch.object(instance, "device", return_value=other) as device:
            self.assertIsNone(instance.hierarchy(allow_unfocused=True))
            with self.assertRaises(bench.BenchError):
                instance.hierarchy()
        self.assertEqual([("shell", "dumpsys", "window"), ("shell", "dumpsys", "window")],
                         [call.args for call in device.call_args_list])

    def test_focused_window_uses_working_summary_then_parses_header(self):
        instance = self.instance()
        window = subprocess.CompletedProcess([], 0, "mCurrentFocus=Window{test u0 com.dintal.syclop/com.nuvio.tv.MainActivity}\n", "")
        dump = subprocess.CompletedProcess([], 0, '<?xml version="1.0" encoding="UTF-8"?>' + header("Anime") + "\nUI hierarchy dumped to: /dev/tty\n", "")
        with patch.object(instance, "device", side_effect=[window, dump]) as device:
            self.assertEqual({"focused": "Anime"}, instance.hierarchy())
        self.assertEqual(("shell", "dumpsys", "window"), device.call_args_list[0].args)
        self.assertEqual(("exec-out", "uiautomator", "dump", "/dev/tty"), device.call_args_list[1].args)

    def run_smoke_fixture(self, transient_start=False, restore_home=False):
        instance = self.instance()
        state = {"tab": 0, "body": False, "reads": 0}
        readiness = []
        directions = []

        def hierarchy(allow_unfocused=False):
            readiness.append(allow_unfocused)
            state["reads"] += 1
            if transient_start and state["reads"] <= 2:
                self.assertTrue(allow_unfocused)
                return None
            return {"focused": None if state["body"] else bench.TABS[state["tab"]]}

        def key(direction):
            directions.append(direction)
            if direction == "DOWN":
                state["body"] = True
            elif direction == "UP":
                state["body"] = False
            elif direction == "RIGHT":
                state["tab"] = min(5, state["tab"] + 1)
            elif direction == "LEFT":
                state["tab"] = max(0, state["tab"] - 1)
                if restore_home and state["tab"] == 0:
                    state["body"] = True

        component = subprocess.CompletedProcess([], 0, "com.dintal.syclop/.MainActivity\n", "")
        with patch.object(instance, "verify_emulator"), patch.object(instance, "device", return_value=component), patch.object(instance, "hierarchy", side_effect=hierarchy), patch.object(instance, "capture", side_effect=lambda label: label + ".png"), patch.object(instance, "key", side_effect=key), patch.object(bench.time, "sleep"):
            instance.smoke()
        return instance, readiness, directions

    def test_smoke_waits_through_launch_focus_transition_then_navigation_is_strict(self):
        instance, readiness, directions = self.run_smoke_fixture(transient_start=True)
        self.assertEqual([True, True, True], readiness[:3])
        self.assertTrue(readiness[3:])
        self.assertFalse(any(readiness[3:]))
        self.assertEqual(list(bench.TABS), [step["tab"] for step in instance.report["steps"]])
        self.assertTrue(all(step["left_header_on_down"] for step in instance.report["steps"]))
        self.assertEqual("07-home-return.png", instance.report["final"])
        self.assertEqual(6, directions.count("DOWN"))
        self.assertEqual(6, directions.count("UP"))

    def test_header_wait_accepts_transient_missing_focus_without_more_keys(self):
        instance = self.instance()
        snapshots = [None, {"focused": None}, {"focused": "Movies"}]
        with patch.object(instance, "hierarchy", side_effect=snapshots) as hierarchy, patch.object(instance, "key") as key, patch.object(bench.time, "sleep"):
            self.assertEqual({"focused": "Movies"}, instance.wait_header("Movies"))
        self.assertEqual(3, hierarchy.call_count)
        self.assertTrue(all(not call.args and not call.kwargs for call in hierarchy.call_args_list))
        key.assert_not_called()

    def test_header_wait_timeout_does_not_send_extra_up_or_direction_keys(self):
        instance = self.instance()
        clock = {"now": 0.0}

        def advance(_):
            clock["now"] += 1.0

        with patch.object(instance, "hierarchy", return_value={"focused": None}) as hierarchy, patch.object(instance, "key") as key, patch.object(bench.time, "monotonic", side_effect=lambda: clock["now"]), patch.object(bench.time, "sleep", side_effect=advance), self.assertRaises(bench.BenchError):
            instance.wait_header("Anime", timeout=2)
        self.assertGreaterEqual(hierarchy.call_count, 1)
        key.assert_not_called()

    def test_header_wait_does_not_hide_foreground_loss(self):
        instance = self.instance()
        with patch.object(instance, "hierarchy", side_effect=bench.BenchError("Syclop is not the focused app")) as hierarchy, patch.object(instance, "key") as key, self.assertRaises(bench.BenchError):
            instance.wait_header("Movies")
        hierarchy.assert_called_once_with()
        key.assert_not_called()

    def test_final_home_content_restore_is_normalized_with_exactly_one_up(self):
        instance, readiness, directions = self.run_smoke_fixture(restore_home=True)
        self.assertEqual("07-home-restored-content.png", instance.report["home_restored_content"])
        self.assertEqual("07-home-return.png", instance.report["final"])
        self.assertEqual(["LEFT", "UP"], directions[-2:])
        self.assertEqual(7, directions.count("UP"))
        self.assertEqual(6, directions.count("DOWN"))
        self.assertEqual(5, directions.count("LEFT"))
        self.assertFalse(any(readiness[1:]))


class ConfigurationTest(unittest.TestCase):
    def test_startup_options_select_explicit_emulator_without_executing_it(self):
        options = bench.parser().parse_args(["--sdk", "/fake-sdk", "--home", "/fake-cache",
            "--emulator", "/separate/emulator", "--serial", "emulator-5580",
            "start", "--headless", "--gpu", "software", "--boot-timeout", "30"])
        with patch.object(bench, "run") as run, patch.object(bench.subprocess, "Popen") as popen:
            instance = bench.Bench(options)
        self.assertEqual(Path("/separate/emulator"), instance.emulator)
        self.assertEqual("tv34-arm64", options.profile)
        self.assertEqual(5038, options.adb_server_port)
        self.assertTrue(options.headless)
        self.assertEqual("software", options.gpu)
        self.assertEqual(30, options.boot_timeout)
        run.assert_not_called()
        popen.assert_not_called()

    def test_standalone_home_clone_allows_default_cache_but_rejects_checkout_storage(self):
        user = Path("/fixture-user")
        project = user / "Syclop"
        with patch.dict(bench.os.environ, {}, clear=True), patch.object(bench.sys, "platform", "darwin"), patch.object(bench.Path, "home", return_value=user):
            options = bench.parser().parse_args(["--sdk", "/fake-sdk", "--project", str(project), "status"])
            instance = bench.Bench(options)
            self.assertEqual(user / "Library/Caches/Syclop/android-bench", instance.home)
            for home in (project, project / "cache"):
                with self.subTest(home=home), self.assertRaises(bench.BenchError):
                    bench.Bench(bench.parser().parse_args(["--sdk", "/fake-sdk", "--project", str(project), "--home", str(home), "status"]))

    def test_mutable_default_is_cache_outside_working_directory(self):
        with patch.dict(bench.os.environ, {}, clear=True), patch.object(bench.sys, "platform", "darwin"), patch.object(bench.Path, "home", return_value=Path("/users/test")):
            self.assertEqual(Path("/users/test/Library/Caches/Syclop/android-bench"), bench.default_home())
        with patch.dict(bench.os.environ, {"XDG_CACHE_HOME": "/cache"}, clear=True), patch.object(bench.sys, "platform", "linux"):
            self.assertEqual(Path("/cache/syclop/android-bench"), bench.default_home())

    def test_default_device_server_5037_is_rejected(self):
        with patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
            bench.parser().parse_args(["--adb-server-port", "5037", "status"])

    def test_bad_server_port_is_bounded_without_huge_help_range(self):
        with patch("sys.stderr", new_callable=io.StringIO) as error, self.assertRaises(SystemExit):
            bench.parser().parse_args(["--adb-server-port", "65536", "status"])
        self.assertLess(len(error.getvalue()), 2000)
        self.assertIn("1024 to 65535", error.getvalue())


if __name__ == "__main__":
    unittest.main()
