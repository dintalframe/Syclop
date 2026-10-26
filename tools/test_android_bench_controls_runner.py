"""Pure runner integration tests; fake APKs and a mocked emulator never leave temp storage.

The one subprocess exception is an isolated Bash option-guard test. It cannot reach a real
SDK, signing directory, Gradle build or device, and verifies rejection before setup starts.
"""
from contextlib import ExitStack, contextmanager
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from test_android_bench_controls import APP_TREE, TEST_TREE, SUCCESS, ONE_PIXEL_PNG, event

SCRIPT = Path(__file__).with_name("android-bench.py")
spec = importlib.util.spec_from_file_location("controls_runner_bench", SCRIPT)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)

APP_SIGNER = "1" * 64
TEST_SIGNER = "2" * 64
FIXED_CLASSES = ",".join((
    "com.nuvio.tv.ui.screens.detail.PlaybackStartActionsTvTest",
    "com.nuvio.tv.ui.screens.player.PlaybackActionButtonsTvTest",
    "com.nuvio.tv.ui.screens.settings.AppRestartSettingsTvTest",
))
FIXED_RUNNER = "com.dintal.syclop.test/androidx.test.runner.AndroidJUnitRunner"
REMOTE = "/sdcard/Android/data/com.dintal.syclop/files/"
CATEGORY_CLASS = "com.nuvio.tv.ui.screens.home.HomeCategoryNavigationTvTest"
CATEGORY_CASES = (
    (CATEGORY_CLASS, "lateAnimeCatalogKeepsFocusedCardAndOneUpReturnsToHeader"),
    (CATEGORY_CLASS, "lateKidsGroupKeepsSafeEntryAndOneUpReturnsToHeader"),
    (CATEGORY_CLASS, "changingCategoriesKeepsHeaderFocusAndUsesNewCategoryRows"),
    (CATEGORY_CLASS, "newRequestRevisionRestoresConfiguredOrderWithoutOldFocus"),
    (CATEGORY_CLASS, "lateHomeBodyCannotStealHeaderAndDownRestoresRememberedCard"),
)
CATEGORY_IMAGES = (
    "category-anime-late-arrival.png", "category-kids-late-group.png",
    "category-change-anime-header.png", "category-request-order-reset.png",
    "category-home-late-header.png", "category-home-remembered-entry.png")
CATEGORY_SUCCESS = "".join(event(i, case, 1) + event(i, case, 0)
                           for i, case in enumerate(CATEGORY_CASES, 1)).replace("numtests=11", "numtests=5") + (
    "INSTRUMENTATION_RESULT: stream=\n\nTime: 43.2\n\nOK (5 tests)\n\nINSTRUMENTATION_CODE: -1\n")
EPISODE_CLASS = "com.nuvio.tv.ui.screens.stream.EpisodeSourceCardsTvTest"
EPISODE_CASES = (
    (EPISODE_CLASS, "completeSeasonPackIsHiddenAndExactEpisodeCardKeepsRemoteFocus"),
    (EPISODE_CLASS, "seasonPackOnlyResultsStayEmptyWithoutSubstitutingAnotherEpisode"),
)
EPISODE_IMAGES = ("episode-source-only-single-episode.png", "episode-source-packs-only-empty.png")
EPISODE_SUCCESS = "".join(event(i, case, 1) + event(i, case, 0)
                          for i, case in enumerate(EPISODE_CASES, 1)).replace("numtests=11", "numtests=2") + (
    "INSTRUMENTATION_RESULT: stream=\n\nTime: 12.3\n\nOK (2 tests)\n\nINSTRUMENTATION_CODE: -1\n")
STARTUP_CLASS = "com.nuvio.tv.ui.components.StartupInitializationScreenTvTest"
STARTUP_CASES = (
    (STARTUP_CLASS, "lateBackgroundFocusCannotEscapeInitializationGate"),
    (STARTUP_CLASS, "retryContinueAndBackKeepTheirActionsAndRestoreHomeFocus"),
    (STARTUP_CLASS, "repeatedGateTransitionsKeepTheSameNativeWindowRoots"),
)
STARTUP_IMAGES = ("startup-gate-background-blocked.png", "startup-gate-actions.png",
                  "startup-gate-resumed-home.png", "startup-gate-same-window.png")
STARTUP_SUCCESS = "".join(event(i, case, 1) + event(i, case, 0)
                          for i, case in enumerate(STARTUP_CASES, 1)).replace("numtests=11", "numtests=3") + (
    "INSTRUMENTATION_RESULT: stream=\n\nTime: 16.2\n\nOK (3 tests)\n\nINSTRUMENTATION_CODE: -1\n")


def answer(output="", code=0, binary=False):
    return subprocess.CompletedProcess([], code, output, b"" if binary else "")


class PlaybackControlsRunnerTest(unittest.TestCase):
    def setUp(self):
        self.storage = tempfile.TemporaryDirectory(prefix="syclop-controls-runner-")
        self.addCleanup(self.storage.cleanup)
        self.root = Path(self.storage.name).resolve()
        self.project = self.root / "checkout"
        self.project.mkdir()
        self.development = self.root / "dist/development"
        self.development.mkdir(parents=True)
        self.app = self.development / "syclop-debug.apk"
        self.tests = self.development / "syclop-ui-tests.apk"
        self.app.write_bytes(b"fake app APK: never installed on a device")
        self.tests.write_bytes(b"fake test APK: never installed on a device")
        self.app_tree, self.test_tree = APP_TREE, TEST_TREE
        self.app_signature = "Signer #1 certificate SHA-256 digest: " + APP_SIGNER + "\n"
        self.test_signature = "Signer #1 certificate SHA-256 digest: " + TEST_SIGNER + "\n"
        self.badging = "package: name='com.dintal.syclop' versionCode='1'\nnative-code: 'arm64-v8a'\n"
        self.api, self.abi, self.qemu = "34", "arm64-v8a", "1"
        self.protocol, self.instrument_exit = SUCCESS, 0
        self.capture_override = {}
        self.install_override = None

    def arguments(self, *extra, command="playback-controls", serial="emulator-5580"):
        args = ["--sdk", "/fake-sdk", "--home", str(self.root / "cache"),
                "--project", str(self.project)]
        if serial is not None:
            args.extend(("--serial", serial))
        return args + [command, *extra]

    def instance(self, *extra, command="playback-controls", serial="emulator-5580"):
        instance = bench.Bench(bench.parser().parse_args(self.arguments(*extra, command=command, serial=serial)))
        instance.report_dir = self.root / "captures"
        instance.report_dir.mkdir(exist_ok=True)
        return instance

    def host(self, args, **kwargs):
        command = [str(value) for value in args]
        if command[1:3] == ["dump", "xmltree"]:
            return answer(self.app_tree if Path(command[3]).name == self.app.name else self.test_tree)
        if command[1:3] == ["verify", "--print-certs"]:
            return answer(self.app_signature if Path(command[3]).name == self.app.name else self.test_signature)
        if command[1:3] == ["dump", "badging"]:
            return answer(self.badging)
        raise AssertionError("Unexpected external host command")

    def device(self, *args, **kwargs):
        if args == ("get-state",):
            return answer("device\n")
        if args[:2] == ("shell", "getprop"):
            values = {"ro.kernel.qemu": self.qemu, "ro.boot.qemu": self.qemu,
                      "ro.build.version.sdk": self.api, "ro.product.cpu.abi": self.abi}
            return answer(values[args[2]] + "\n")
        if args and args[0] == "install":
            return self.install_override or answer("Performing Streamed Install\nSuccess\n")
        if args[:3] == ("shell", "rm", "-f"):
            return answer()
        if args[:3] == ("shell", "am", "instrument"):
            return answer(self.protocol, self.instrument_exit)
        if args[:2] == ("exec-out", "cat"):
            name = Path(args[2]).name
            return self.capture_override.get(name, answer(ONE_PIXEL_PNG, binary=True))
        raise AssertionError("Unexpected emulator command")

    @contextmanager
    def mocks(self, instance):
        with ExitStack() as stack:
            host = stack.enter_context(patch.object(bench, "run", side_effect=self.host))
            device = stack.enter_context(patch.object(instance, "device", side_effect=self.device))
            stack.enter_context(patch("sys.stdout", new_callable=io.StringIO))
            yield host, device

    def assert_no_installs(self, device):
        self.assertFalse(any(call.args and call.args[0] == "install" for call in device.call_args_list))

    def test_category_navigation_runs_only_its_fixed_class_and_six_fresh_images(self):
        self.protocol = CATEGORY_SUCCESS
        instance = self.instance(command="category-navigation")
        with self.mocks(instance) as (_, device):
            instance.category_navigation()
        invocation = next(call for call in device.call_args_list if call.args[:3] == ("shell", "am", "instrument"))
        self.assertEqual(("shell", "am", "instrument", "-w", "-r", "-e", "class", CATEGORY_CLASS, FIXED_RUNNER), invocation.args)
        removal = next(call for call in device.call_args_list if call.args[:3] == ("shell", "rm", "-f"))
        self.assertEqual(("shell", "rm", "-f", *[REMOTE + name for name in CATEGORY_IMAGES]), removal.args)
        self.assertEqual("account-free-category-navigation", instance.report["fixture"])
        self.assertEqual({"expected": 5, "completed": 5, "passed": True}, instance.report["instrumentation"])
        self.assertEqual(list(CATEGORY_IMAGES), instance.report["screenshots"])
        self.assertEqual("required", instance.report["visual_inspection"])

    def test_episode_sources_runs_only_two_fixed_cases_and_fresh_source_images(self):
        self.protocol = EPISODE_SUCCESS
        instance = self.instance(command="episode-sources")
        with self.mocks(instance) as (_, device):
            instance.episode_sources()
        invocation = next(call for call in device.call_args_list if call.args[:3] == ("shell", "am", "instrument"))
        self.assertEqual(("shell", "am", "instrument", "-w", "-r", "-e", "class", EPISODE_CLASS, FIXED_RUNNER), invocation.args)
        removal = next(call for call in device.call_args_list if call.args[:3] == ("shell", "rm", "-f"))
        self.assertEqual(("shell", "rm", "-f", *[REMOTE + name for name in EPISODE_IMAGES]), removal.args)
        self.assertEqual("account-free-episode-sources", instance.report["fixture"])
        self.assertEqual({"expected": 2, "completed": 2, "passed": True}, instance.report["instrumentation"])
        self.assertEqual(list(EPISODE_IMAGES), instance.report["screenshots"])

    def test_episode_protocol_rejects_other_suite_failure_or_incomplete_protocol(self):
        for output in (SUCCESS, CATEGORY_SUCCESS, EPISODE_SUCCESS.replace("OK (2 tests)", "OK (1 test)"),
                       EPISODE_SUCCESS.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", 1),
                       EPISODE_SUCCESS.replace("INSTRUMENTATION_CODE: -1", "")):
            with self.subTest(output=output[-45:]):
                self.assertFalse(bench.controls.instrumentation_result(output, 0, "episode-sources")["passed"])
        self.assertFalse(bench.controls.instrumentation_result(EPISODE_SUCCESS, 1, "episode-sources")["passed"])
        self.assertFalse(bench.controls.instrumentation_result(EPISODE_SUCCESS, 0, "category-navigation")["passed"])

    def test_episode_capture_failure_cannot_reuse_an_earlier_screenshot(self):
        self.protocol = EPISODE_SUCCESS
        self.capture_override[EPISODE_IMAGES[0]] = answer(b"", 1, binary=True)
        instance = self.instance(command="episode-sources")
        with self.mocks(instance), self.assertRaises(bench.BenchError):
            instance.episode_sources()
        self.assertNotIn(EPISODE_IMAGES[0], instance.report["screenshots"])

    def test_episode_command_rejects_arbitrary_instrumentation_arguments(self):
        options = bench.parser().parse_args(["episode-sources", "--test-timeout", "600"])
        self.assertEqual("episode_sources", options.command)
        self.assertEqual(600, options.test_timeout)
        for flag in ("--class", "--runner", "--arguments"):
            with self.subTest(flag=flag), patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
                bench.parser().parse_args(["episode-sources", flag, "untrusted"])

    def test_startup_runs_only_fixed_cases_and_four_fresh_window_images(self):
        self.protocol = STARTUP_SUCCESS
        instance = self.instance(command="startup-initialization")
        with self.mocks(instance) as (_, device):
            instance.startup_initialization()
        invocation = next(call for call in device.call_args_list if call.args[:3] == ("shell", "am", "instrument"))
        self.assertEqual(("shell", "am", "instrument", "-w", "-r", "-e", "class", STARTUP_CLASS, FIXED_RUNNER), invocation.args)
        removal = next(call for call in device.call_args_list if call.args[:3] == ("shell", "rm", "-f"))
        self.assertEqual(("shell", "rm", "-f", *[REMOTE + name for name in STARTUP_IMAGES]), removal.args)
        self.assertEqual("account-free-startup-initialization", instance.report["fixture"])
        self.assertEqual({"expected": 3, "completed": 3, "passed": True}, instance.report["instrumentation"])
        self.assertEqual(list(STARTUP_IMAGES), instance.report["screenshots"])

    def test_startup_native_window_fixture_requires_api29_before_install(self):
        self.protocol, self.api = STARTUP_SUCCESS, "28"
        instance = self.instance(command="startup-initialization")
        with self.mocks(instance) as (_, device), self.assertRaises(bench.BenchError):
            instance.startup_initialization()
        self.assert_no_installs(device)

    def test_startup_protocol_rejects_other_suite_incomplete_or_failed_cases(self):
        for output in (SUCCESS, CATEGORY_SUCCESS, EPISODE_SUCCESS,
                       STARTUP_SUCCESS.replace("OK (3 tests)", "OK (2 tests)"),
                       STARTUP_SUCCESS.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", 1),
                       STARTUP_SUCCESS.replace("INSTRUMENTATION_CODE: -1", "")):
            with self.subTest(output=output[-45:]):
                self.assertFalse(bench.controls.instrumentation_result(output, 0, "startup-initialization")["passed"])
        self.assertFalse(bench.controls.instrumentation_result(STARTUP_SUCCESS, 1, "startup-initialization")["passed"])

    def test_startup_capture_failure_cannot_reuse_an_earlier_image(self):
        self.protocol = STARTUP_SUCCESS
        self.capture_override[STARTUP_IMAGES[0]] = answer(b"", 1, binary=True)
        instance = self.instance(command="startup-initialization")
        with self.mocks(instance), self.assertRaises(bench.BenchError):
            instance.startup_initialization()
        self.assertNotIn(STARTUP_IMAGES[0], instance.report["screenshots"])

    def test_startup_command_rejects_arbitrary_instrumentation_arguments(self):
        options = bench.parser().parse_args(["startup-initialization", "--test-timeout", "600"])
        self.assertEqual("startup_initialization", options.command)
        for flag in ("--class", "--runner", "--arguments"):
            with self.subTest(flag=flag), patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
                bench.parser().parse_args(["startup-initialization", flag, "untrusted"])

    def test_category_protocol_rejects_playback_or_incomplete_or_failed_suite(self):
        for index, output in enumerate((SUCCESS, CATEGORY_SUCCESS.replace("OK (5 tests)", "OK (4 tests)"),
                       CATEGORY_SUCCESS.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", 1),
                       CATEGORY_SUCCESS.replace("INSTRUMENTATION_CODE: -1", ""),
                       CATEGORY_SUCCESS + "INSTRUMENTATION_CODE: -1\n")):
            with self.subTest(case=index):
                self.assertFalse(bench.controls.instrumentation_result(output, 0, "category-navigation")["passed"])
        self.assertFalse(bench.controls.instrumentation_result(CATEGORY_SUCCESS, 0)["passed"])
        self.assertFalse(bench.controls.instrumentation_result(CATEGORY_SUCCESS, 1, "category-navigation")["passed"])

    def test_category_requires_late_home_focus_case_despite_a_success_summary(self):
        incomplete = "".join(event(i, case, 1) + event(i, case, 0)
                             for i, case in enumerate(CATEGORY_CASES[:-1], 1)).replace("numtests=11", "numtests=5") + (
            "INSTRUMENTATION_RESULT: stream=\n\nOK (5 tests)\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertFalse(bench.controls.instrumentation_result(incomplete, 0, "category-navigation")["passed"])

    def test_category_capture_failure_cannot_reuse_a_playback_screenshot(self):
        self.protocol = CATEGORY_SUCCESS
        self.capture_override[CATEGORY_IMAGES[0]] = answer(b"", 1, binary=True)
        instance = self.instance(command="category-navigation")
        with self.mocks(instance), self.assertRaises(bench.BenchError):
            instance.category_navigation()
        self.assertNotIn(CATEGORY_IMAGES[0], instance.report["screenshots"])

    def test_unknown_suite_fails_before_any_host_or_device_command(self):
        instance = self.instance()
        with self.mocks(instance) as (host, device), self.assertRaises(bench.BenchError):
            instance.fixed_fixtures("external-fixture")
        host.assert_not_called()
        device.assert_not_called()
        with self.assertRaises(bench.controls.ControlsError):
            bench.controls.instrumentation_result(CATEGORY_SUCCESS, 0, "external-fixture")

    def test_category_command_rejects_arbitrary_instrumentation_arguments(self):
        options = bench.parser().parse_args(["category-navigation", "--test-timeout", "600"])
        self.assertEqual("category_navigation", options.command)
        self.assertEqual(600, options.test_timeout)
        for flag in ("--class", "--runner", "--arguments"):
            with self.subTest(flag=flag), patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
                bench.parser().parse_args(["category-navigation", flag, "untrusted"])

    def test_missing_or_nonemulator_serial_fails_before_tools_or_server(self):
        for serial in [None, "192.0.2.1:5555", "SHIELD123", "emulator-5581"]:
            with self.subTest(serial=serial):
                instance = self.instance(serial=serial)
                with self.mocks(instance) as (host, device), self.assertRaises(bench.BenchError):
                    instance.playback_controls()
                host.assert_not_called()
                device.assert_not_called()

    def test_stable_or_foreign_apks_are_rejected_before_inspection(self):
        stable = self.root / "dist/stable/syclop-debug.apk"
        stable.parent.mkdir()
        stable.write_bytes(b"preserve stable")
        for flag in ["--apk", "--test-apk"]:
            with self.subTest(flag=flag):
                instance = self.instance(flag, str(stable))
                with self.mocks(instance) as (host, device), self.assertRaises(bench.BenchError):
                    instance.playback_controls()
                host.assert_not_called()
                device.assert_not_called()

    def test_explicit_symlink_escape_fails_before_inspection(self):
        outside = self.root / "outside.apk"
        outside.write_bytes(b"not a development artifact")
        link = self.development / "escaping.apk"
        link.symlink_to(outside)
        for flag in ["--apk", "--test-apk"]:
            with self.subTest(flag=flag):
                instance = self.instance(flag, str(link))
                with self.mocks(instance) as (host, device), self.assertRaises(bench.BenchError):
                    instance.playback_controls()
                host.assert_not_called()
                device.assert_not_called()

    def test_default_artifact_symlink_escape_also_fails_before_inspection(self):
        outside = self.root / "outside.apk"
        outside.write_bytes(b"not a development artifact")
        self.app.unlink()
        self.app.symlink_to(outside)
        instance = self.instance()
        with self.mocks(instance) as (host, device), self.assertRaises(bench.BenchError):
            instance.playback_controls()
        host.assert_not_called()
        device.assert_not_called()

    def test_development_directory_cannot_redirect_to_stable_through_a_symlink(self):
        stable = self.root / "dist/stable"
        self.development.rename(stable)
        self.development.symlink_to(stable, target_is_directory=True)
        instance = self.instance()
        with self.mocks(instance) as (host, device), self.assertRaises(bench.BenchError):
            instance.playback_controls()
        host.assert_not_called()
        device.assert_not_called()
        self.assertEqual(b"fake app APK: never installed on a device", (stable / self.app.name).read_bytes())

    def test_dist_parent_cannot_redirect_development_to_an_outside_directory(self):
        declared_dist = self.root / "dist"
        outside_dist = self.root / "outside-dist"
        declared_dist.rename(outside_dist)
        declared_dist.symlink_to(outside_dist, target_is_directory=True)
        instance = self.instance()
        with self.mocks(instance) as (host, device), self.assertRaises(bench.BenchError):
            instance.playback_controls()
        host.assert_not_called()
        device.assert_not_called()
        self.assertTrue((outside_dist / "development" / self.tests.name).is_file())

    def test_app_or_test_manifest_failure_prevents_either_install(self):
        for role in ["app_tree", "test_tree"]:
            with self.subTest(role=role):
                original = getattr(self, role)
                setattr(self, role, original.replace("debuggable(0x0101000f)=true", "debuggable(0x0101000f)=false"))
                instance = self.instance()
                with self.mocks(instance) as (_, device), self.assertRaises(bench.BenchError):
                    instance.playback_controls()
                device.assert_not_called()
                setattr(self, role, original)

    def test_missing_or_failed_signature_prevents_either_install(self):
        for role in ["app_signature", "test_signature"]:
            with self.subTest(role=role):
                original = getattr(self, role)
                setattr(self, role, "Verification did not report a signer\n")
                instance = self.instance()
                with self.mocks(instance) as (_, device), self.assertRaises(bench.BenchError):
                    instance.playback_controls()
                device.assert_not_called()
                setattr(self, role, original)
        instance = self.instance()
        with self.mocks(instance) as (_, device), patch.object(bench, "run", side_effect=bench.BenchError("A bench command failed (exit 1); raw output was not recorded.")), self.assertRaises(bench.BenchError):
            instance.playback_controls()
        device.assert_not_called()

    def test_incompatible_app_or_test_sdk_prevents_installs(self):
        for role in ["app_tree", "test_tree"]:
            with self.subTest(role=role):
                original = getattr(self, role)
                setattr(self, role, original.replace("minSdkVersion(0x0101020c)=24", "minSdkVersion(0x0101020c)=35"))
                instance = self.instance()
                with self.mocks(instance) as (_, device), self.assertRaises(bench.BenchError):
                    instance.playback_controls()
                self.assert_no_installs(device)
                setattr(self, role, original)

    def test_incompatible_or_unverifiable_abi_prevents_installs(self):
        for badging in ["", "native-code: 'unknown-abi'\n", "native-code: 'x86_64'\n", "native-code: 'arm64-v8a'\nnative-code: 'x86'\n"]:
            with self.subTest(badging=badging):
                self.badging = badging
                instance = self.instance()
                with self.mocks(instance) as (_, device), self.assertRaises(bench.BenchError):
                    instance.playback_controls()
                self.assert_no_installs(device)

    def test_emulator_identity_must_be_verified_before_installs(self):
        self.qemu = "0"
        instance = self.instance()
        with self.mocks(instance) as (_, device), self.assertRaises(bench.BenchError):
            instance.playback_controls()
        self.assert_no_installs(device)

    def test_independently_valid_different_signers_are_accepted(self):
        instance = self.instance()
        with self.mocks(instance) as (host, device):
            instance.playback_controls()
        self.assertEqual([APP_SIGNER], instance.report["apks"]["app"]["signers"])
        self.assertEqual([TEST_SIGNER], instance.report["apks"]["tests"]["signers"])
        installs = [call.args for call in device.call_args_list if call.args[0] == "install"]
        self.assertEqual([("install", "-r", "-t", str(self.app)), ("install", "-r", "-t", str(self.tests))], installs)
        self.assertEqual(5038, instance.report["adb_server_port"])
        self.assertTrue(instance.report["instrumentation"]["passed"])
        self.assertEqual("required", instance.report["visual_inspection"])
        self.assertEqual(5, host.call_count)

    def test_only_fixed_classes_and_runner_are_sent_with_bounded_timeout(self):
        instance = self.instance("--test-timeout", "600")
        with self.mocks(instance) as (_, device):
            instance.playback_controls()
        calls = [call for call in device.call_args_list if call.args[:3] == ("shell", "am", "instrument")]
        self.assertEqual(1, len(calls))
        self.assertEqual(("shell", "am", "instrument", "-w", "-r", "-e", "class", FIXED_CLASSES, FIXED_RUNNER), calls[0].args)
        self.assertEqual({"timeout": 600, "check": False}, calls[0].kwargs)
        self.assertFalse(any(call.args[:2] in [("shell", "input"), ("shell", "pm")] for call in device.call_args_list))

    def test_exactly_six_fixed_stale_images_are_removed_before_instrumentation(self):
        instance = self.instance()
        with self.mocks(instance) as (_, device):
            instance.playback_controls()
        calls = [call.args for call in device.call_args_list]
        cleanup = [call for call in calls if call[:2] == ("shell", "rm")]
        self.assertEqual([("shell", "rm", "-f", *[REMOTE + name for name in bench.controls.SCREENSHOTS])], cleanup)
        cleanup_at = calls.index(cleanup[0])
        instrument_at = next(i for i, command in enumerate(calls) if command[:3] == ("shell", "am", "instrument"))
        self.assertLess(cleanup_at, instrument_at)
        self.assertTrue(all(i < cleanup_at for i, command in enumerate(calls) if command[0] == "install"))

    def test_all_six_fixed_valid_captures_are_required_and_written(self):
        instance = self.instance()
        with self.mocks(instance) as (_, device):
            instance.playback_controls()
        self.assertEqual(list(bench.controls.SCREENSHOTS), instance.report["screenshots"])
        self.assertEqual(set(bench.controls.SCREENSHOTS), {path.name for path in instance.report_dir.iterdir()})
        for path in instance.report_dir.iterdir():
            self.assertEqual(ONE_PIXEL_PNG, path.read_bytes())
        captures = [call for call in device.call_args_list if call.args[:2] == ("exec-out", "cat")]
        self.assertEqual([("exec-out", "cat", REMOTE + name) for name in bench.controls.SCREENSHOTS], [call.args for call in captures])
        self.assertTrue(all(call.kwargs == {"binary": True, "check": False} for call in captures))

    def test_missing_corrupt_or_truncated_capture_fails_even_after_eleven_passes(self):
        name = bench.controls.SCREENSHOTS[0]
        for response in [answer(b"No such file", code=1, binary=True), answer(b"not png", binary=True), answer(ONE_PIXEL_PNG[:-1], binary=True)]:
            with self.subTest(response=response.stdout):
                self.capture_override[name] = response
                instance = self.instance()
                with self.mocks(instance), self.assertRaises(bench.BenchError):
                    instance.playback_controls()
                self.assertTrue(instance.report["instrumentation"]["passed"])
                self.assertEqual(5, len(instance.report["screenshots"]))
                self.assertNotIn(name, instance.report["screenshots"])

    def test_bad_protocol_fails_and_never_records_raw_instrumentation(self):
        secret = "PRIVATE_CREDENTIAL_SHOULD_NEVER_BE_RETAINED"
        self.protocol = "INSTRUMENTATION_RESULT: shortMsg=Process crashed: " + secret
        instance = self.instance()
        with self.mocks(instance), self.assertRaises(bench.BenchError) as error:
            instance.playback_controls()
        self.assertFalse(instance.report["instrumentation"]["passed"])
        self.assertNotIn(secret, json.dumps(instance.report))
        self.assertNotIn(secret, str(error.exception))
        self.assertFalse(any(path.suffix in [".txt", ".log"] for path in instance.report_dir.iterdir()))

    def test_arbitrary_log_text_is_not_retained_even_on_success(self):
        secret = "PRIVATE_LOG_VALUE_NOT_FOR_JSON"
        self.protocol += "\nSDK example log: " + secret + "\n"
        instance = self.instance()
        with self.mocks(instance):
            instance.playback_controls()
        self.assertTrue(instance.report["instrumentation"]["passed"])
        self.assertNotIn(secret, json.dumps(instance.report))

    def test_main_sanitizes_host_exception_text_in_saved_report(self):
        secret = "PRIVATE_EXECUTABLE_EXCEPTION_TEXT"
        with patch.object(bench, "run", side_effect=OSError(secret)), patch("sys.stdout", new_callable=io.StringIO), patch("sys.stderr", new_callable=io.StringIO) as error:
            self.assertEqual(1, bench.main(self.arguments()))
        reports = list((self.root / "cache/reports").glob("*/report.json"))
        self.assertEqual(1, len(reports))
        self.assertNotIn(secret, reports[0].read_text())
        self.assertNotIn(secret, error.getvalue())
        self.assertFalse(json.loads(reports[0].read_text())["passed"])


class ControlsCommandConfigurationTest(unittest.TestCase):
    def test_timeout_accepts_only_the_documented_bounded_range(self):
        for seconds in [60, 300, 600]:
            options = bench.parser().parse_args(["playback-controls", "--test-timeout", str(seconds)])
            self.assertEqual(seconds, options.test_timeout)
            self.assertEqual("playback_controls", options.command)
        for value in ["59", "601", "0", "abc"]:
            with self.subTest(value=value), patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
                bench.parser().parse_args(["playback-controls", "--test-timeout", value])

    def test_class_runner_shell_and_instrumentation_overrides_are_not_exposed(self):
        for flag in ["--class", "--runner", "--shell", "--instrumentation-args"]:
            with self.subTest(flag=flag), patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
                bench.parser().parse_args(["playback-controls", flag, "untrusted"])

    def test_bench_ui_build_forwards_only_ui_tests_and_optional_regressions(self):
        with tempfile.TemporaryDirectory(prefix="syclop-ui-build-") as temporary:
            root = Path(temporary).resolve()
            project = root / "checkout"
            outputs = root / "dist/development"
            outputs.mkdir(parents=True)
            (outputs / "syclop-debug.apk").write_bytes(b"not an APK")
            (outputs / "syclop-ui-tests.apk").write_bytes(b"not an APK")
            options = bench.parser().parse_args(["--sdk", "/fake-sdk", "--home", str(root / "cache"), "--project", str(project), "build", "--ui-tests", "--test"])
            instance = bench.Bench(options)
            with patch.object(bench.subprocess, "run", return_value=answer()) as runner, patch("sys.stdout", new_callable=io.StringIO):
                instance.build()
            self.assertEqual(["bash", str(project / "build-syclop.sh"), "--ui-tests", "--test"], runner.call_args.args[0])
            self.assertNotIn("--release", runner.call_args.args[0])
            self.assertEqual(2400, runner.call_args.kwargs["timeout"])

    def test_incompatible_build_script_options_reject_before_environment_or_artifacts(self):
        script = SCRIPT.parent.parent / "build-syclop.sh"
        with tempfile.TemporaryDirectory(prefix="syclop-bash-option-guard-") as temporary:
            root = Path(temporary).resolve()
            project = root / "checkout"
            project.mkdir()
            isolated = project / "build-syclop.sh"
            isolated.write_bytes(script.read_bytes())
            toolchain = root / ".toolchain"
            toolchain.mkdir()
            sourced = root / "environment-was-sourced"
            (toolchain / "env.sh").write_text('printf invoked > "' + str(sourced) + '"\nexit 91\n')
            signing, sdk = root / "private-signing", root / "absent-sdk"
            result = subprocess.run(["/bin/bash", str(isolated), "--release", "--ui-tests"],
                cwd=project, env={"PATH": "/usr/bin:/bin", "JAVA_HOME": str(root / "absent-jdk"),
                                  "ANDROID_HOME": str(sdk), "SYCLOP_SIGNING_HOME": str(signing)},
                capture_output=True, text=True, timeout=5)
            self.assertEqual(2, result.returncode)
            self.assertIn("--ui-tests requires", result.stderr)
            self.assertFalse(sourced.exists())
            self.assertFalse(signing.exists())
            self.assertFalse(sdk.exists())
            self.assertFalse((project / "app/build").exists())
            self.assertFalse((root / "dist").exists())


if __name__ == "__main__":
    unittest.main()
