"""Pure fixtures for APK inspection, AndroidJUnitRunner results and screenshot transfers.

No SDK, subprocess, ADB, network, local account state or private reports are used.
"""
import base64
from pathlib import Path
import struct
import sys
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_bench_controls as controls


ANDROID = "http://schemas.android.com/apk/res/android:"
# SDK 36 aapt2 XML-tree output, including its expanded namespace attributes and Raw annotations.
APP_TREE = '''N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="com.dintal.syclop" (Raw: "com.dintal.syclop")
      E: uses-sdk (line=5)
        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=24
      E: application (line=24)
        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true
        A: http://schemas.android.com/apk/res/android:label(0x01010001)="Syclop" (Raw: "Syclop")
'''
INSTRUMENTATION = '''      E: instrumentation (line=9)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner" (Raw: "androidx.test.runner.AndroidJUnitRunner")
        A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="com.dintal.syclop" (Raw: "com.dintal.syclop")
'''
TEST_TREE = APP_TREE.replace(
    'A: package="com.dintal.syclop" (Raw: "com.dintal.syclop")',
    'A: package="com.dintal.syclop.test" (Raw: "com.dintal.syclop.test")'
).replace("      E: application (line=24)\n", INSTRUMENTATION + "      E: application (line=24)\n")

# Recorded fixture identities are explicit, so changing a production allowlist cannot silently
# turn this regression transcript into a different test suite.
CASES = (
    ("com.nuvio.tv.ui.screens.detail.PlaybackStartActionsTvTest", "movieResumeAndStartFromBeginningAreSeparateRemoteActions"),
    ("com.nuvio.tv.ui.screens.detail.PlaybackStartActionsTvTest", "episodeResumeAndStartFromBeginningAreSeparateRemoteActions"),
    ("com.nuvio.tv.ui.screens.detail.PlaybackStartActionsTvTest", "unavailableEpisodeDisablesBothPlaybackActions"),
    ("com.nuvio.tv.ui.screens.detail.PlaybackStartActionsTvTest", "unstartedMovieDoesNotOfferARedundantStartFromBeginningAction"),
    ("com.nuvio.tv.ui.screens.player.PlaybackActionButtonsTvTest", "unairedEpisodeDoesNotOfferNext"),
    ("com.nuvio.tv.ui.screens.player.PlaybackActionButtonsTvTest", "finalEpisodeKeepsStartFromBeginningWithoutANextAction"),
    ("com.nuvio.tv.ui.screens.player.PlaybackActionButtonsTvTest", "inFlightNextSearchDoesNotOfferAnotherNextAction"),
    ("com.nuvio.tv.ui.screens.player.PlaybackActionButtonsTvTest", "ordinaryRemotePressSelectsEachLabelledActionOnce"),
    ("com.nuvio.tv.ui.screens.settings.AppRestartSettingsTvTest", "cancelIsInitiallyFocusedAndReturnsToTheSettingsAction"),
    ("com.nuvio.tv.ui.screens.settings.AppRestartSettingsTvTest", "backDismissesConfirmationWithoutRestarting"),
    ("com.nuvio.tv.ui.screens.settings.AppRestartSettingsTvTest", "rightThenCenterConfirmsExactlyOneRestartRequest"),
)


def event(index, case, status):
    group, method = case
    return (
        f"INSTRUMENTATION_STATUS: class={group}\n"
        f"INSTRUMENTATION_STATUS: current={index}\n"
        "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
        "INSTRUMENTATION_STATUS: numtests=11\n"
        "INSTRUMENTATION_STATUS: stream=.\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS_CODE: {status}\n"
    )


SUCCESS = "".join(event(i, case, 1) + event(i, case, 0) for i, case in enumerate(CASES, 1)) + (
    "INSTRUMENTATION_RESULT: stream=\n\nTime: 89.933\n\nOK (11 tests)\n\n"
    "INSTRUMENTATION_CODE: -1\n"
    "s_glBindAttribLocation: bind attrib 0 name position\n"
)

# A pre-encoded 1x1 grayscale/alpha PNG. Its IDAT independently inflates to the expected
# scanline below; this is not an image synthesized by the function under test.
ONE_PIXEL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


class ManifestInspectionTest(unittest.TestCase):
    def reject(self, tree, package=controls.TEST_PACKAGE):
        with self.assertRaises(controls.ControlsError):
            controls.validate_manifest(tree, package)

    def test_real_sdk36_app_and_test_tree_literals_are_accepted(self):
        app = controls.validate_manifest(APP_TREE, controls.PACKAGE)
        test = controls.validate_manifest(TEST_TREE, controls.TEST_PACKAGE)
        self.assertEqual(("com.dintal.syclop", 24, True, []),
                         (app["package"], app["min_sdk"], app["debuggable"], app["instrumentation"]))
        self.assertEqual("com.dintal.syclop.test", test["package"])
        self.assertEqual("androidx.test.runner.AndroidJUnitRunner", test["instrumentation"][0]["android:name"])
        self.assertEqual("com.dintal.syclop", test["instrumentation"][0]["android:targetPackage"])

    def test_application_and_test_must_each_be_explicitly_debuggable(self):
        for tree, package in [(APP_TREE, controls.PACKAGE), (TEST_TREE, controls.TEST_PACKAGE)]:
            for value in ["false", '"true"', "1", "0xffffffff", "(type 0x12)0xffffffff", "@0x7f010001"]:
                with self.subTest(package=package, value=value):
                    self.reject(tree.replace("debuggable(0x0101000f)=true", "debuggable(0x0101000f)=" + value), package)
            self.reject(tree.replace("        A: " + ANDROID + "debuggable(0x0101000f)=true\n", ""), package)

    def test_spoofed_or_unexpanded_android_namespaces_cannot_supply_flags(self):
        for namespace in ["https://schemas.android.com/apk/res/android:", "http://example.invalid/android:", "android:", ""]:
            with self.subTest(namespace=namespace):
                self.reject(TEST_TREE.replace(ANDROID + "debuggable", namespace + "debuggable"))

    def test_runner_and_target_are_exact_scoped_attributes(self):
        for old, new in [
            ('="androidx.test.runner.AndroidJUnitRunner"', '="com.example.OtherRunner"'),
            (ANDROID + "name(0x01010003)", "http://example.invalid/:name(0x01010003)"),
            (ANDROID + "targetPackage(0x01010021)", "targetPackage"),
            ('targetPackage(0x01010021)="com.dintal.syclop"', 'targetPackage(0x01010021)="com.dintal.syclop.other"'),
        ]:
            with self.subTest(replacement=new):
                self.reject(TEST_TREE.replace(old, new, 1))
        nested = INSTRUMENTATION.replace("      E:", "        E:").replace("        A:", "          A:")
        self.reject(TEST_TREE.replace(INSTRUMENTATION, "") + nested)

    def test_application_labels_cannot_forge_package_or_instrumentation(self):
        forged = TEST_TREE.replace('package="com.dintal.syclop.test"', 'package="com.example.other"', 1)
        forged = forged.replace('label(0x01010001)="Syclop"', 'label(0x01010001)="com.dintal.syclop.test"')
        self.reject(forged)
        self.reject(TEST_TREE.replace(INSTRUMENTATION, ""))
        self.reject(APP_TREE.replace("      E: application (line=24)\n", INSTRUMENTATION + "      E: application (line=24)\n"), controls.PACKAGE)

    def test_duplicate_application_sdk_and_runner_nodes_are_rejected(self):
        for addition in [
            "      E: application (line=100)\n        A: " + ANDROID + "debuggable(0x0101000f)=true\n",
            "      E: uses-sdk (line=100)\n        A: " + ANDROID + "minSdkVersion(0x0101020c)=24\n",
            INSTRUMENTATION,
        ]:
            with self.subTest(addition=addition.splitlines()[0]):
                self.reject(TEST_TREE + addition)

    def test_duplicate_attribute_is_rejected_even_with_another_resource_id(self):
        needle = "        A: " + ANDROID + "debuggable(0x0101000f)=true\n"
        self.reject(TEST_TREE.replace(needle, needle + "        A: " + ANDROID + "debuggable(0x0101ffff)=false\n"))
        needle = "        A: " + ANDROID + 'targetPackage(0x01010021)="com.dintal.syclop" (Raw: "com.dintal.syclop")\n'
        self.reject(TEST_TREE.replace(needle, needle + needle))

    def test_wrong_scope_cannot_supply_application_debug_or_minimum_sdk(self):
        no_app_flag = TEST_TREE.replace("        A: " + ANDROID + "debuggable(0x0101000f)=true\n", "")
        self.reject(no_app_flag + "        E: activity (line=100)\n          A: " + ANDROID + "debuggable(0x0101000f)=true\n")
        self.reject(TEST_TREE.replace("      E: uses-sdk (line=5)\n", "        E: uses-sdk (line=5)\n"))
        self.reject(TEST_TREE + "    A: package=\"com.dintal.syclop.test\"\n")

    def test_sdk_literal_must_be_positive_bounded_integer(self):
        for value in ['"24"', "true", "0", "10001", "-1", "@0x7f010001", "(type 0x10)0x18"]:
            with self.subTest(value=value):
                self.reject(TEST_TREE.replace("minSdkVersion(0x0101020c)=24", "minSdkVersion(0x0101020c)=" + value))

    def test_incomplete_multiple_root_and_oversized_inputs_fail(self):
        for tree in ["", "not an APK tree", TEST_TREE + "  E: manifest (line=100)\n", "x" * (2 * 1024 * 1024 + 1)]:
            with self.subTest(length=len(tree)):
                self.reject(tree)


class InstrumentationProtocolTest(unittest.TestCase):
    def reject(self, output, returncode=0):
        facts = controls.instrumentation_result(output, returncode)
        self.assertFalse(facts["passed"])
        self.assertEqual(11, facts["expected"])
        self.assertIsInstance(facts["completed"], int)

    def test_complete_recorded_shape_passes_with_harmless_renderer_output(self):
        self.assertEqual({"expected": 11, "completed": 11, "passed": True},
                         controls.instrumentation_result(SUCCESS, 0))
        self.assertEqual(frozenset(CASES), controls.EXPECTED_TESTS)
        self.assertEqual(6, len(set(controls.SCREENSHOTS)))

    def test_transport_failure_cannot_be_overridden_by_passing_text(self):
        self.reject(SUCCESS, 1)
        self.reject(SUCCESS, -9)

    def test_summary_and_exit_code_alone_do_not_prove_execution(self):
        self.reject("OK (11 tests)\nINSTRUMENTATION_CODE: -1\n")
        self.reject("INSTRUMENTATION_CODE: -1\n")

    def test_failure_skip_and_assumption_statuses_do_not_pass(self):
        for status in ["-1", "-2", "-3", "-4", "2"]:
            with self.subTest(status=status):
                self.reject(SUCCESS.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: " + status, 1))

    def test_missing_mismatched_and_duplicate_test_events_fail(self):
        self.reject(SUCCESS.replace(event(1, CASES[0], 1), "", 1))
        self.reject(SUCCESS.replace(event(1, CASES[0], 0), "", 1))
        self.reject(SUCCESS.replace(event(1, CASES[0], 0), event(1, CASES[1], 0), 1))
        self.reject(SUCCESS.replace(event(1, CASES[0], 0), event(1, CASES[0], 0) * 2, 1))
        self.reject(SUCCESS.replace("current=2\n", "current=1\n", 1))

    def test_unknown_class_method_runner_or_count_fails(self):
        for old, new in [
            (CASES[0][0], "com.example.OtherTest"),
            (CASES[0][1], "testSomethingElse"),
            ("id=AndroidJUnitRunner", "id=OtherRunner"),
            ("numtests=11", "numtests=12"),
            ("OK (11 tests)", "OK (10 tests)"),
        ]:
            with self.subTest(replacement=new):
                self.reject(SUCCESS.replace(old, new))

    def test_missing_bad_or_duplicate_final_result_fails(self):
        for replacement in ["", "INSTRUMENTATION_CODE: 0\n", "INSTRUMENTATION_CODE: -1\n" * 2]:
            with self.subTest(replacement=replacement):
                self.reject(SUCCESS.replace("INSTRUMENTATION_CODE: -1\n", replacement))
        self.reject(SUCCESS.replace("OK (11 tests)", ""))
        self.reject(SUCCESS + "OK (11 tests)\n")

    def test_a_summary_outside_the_final_result_stream_is_not_success(self):
        no_summary = SUCCESS.replace("OK (11 tests)\n", "")
        self.reject("OK (11 tests)\n" + no_summary)
        self.reject(no_summary + "OK (11 tests)\n")

    def test_crash_failure_or_error_marker_cannot_be_hidden_after_success(self):
        for error in [
            "INSTRUMENTATION_FAILED: runner failed\n",
            "FAILURES!!!\n",
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n",
            "INSTRUMENTATION_RESULT: Error=Unable to find instrumentation info\n",
        ]:
            with self.subTest(error=error.split(":")[0]):
                self.reject(SUCCESS + error)

    def test_pending_fields_after_final_and_duplicate_status_fields_fail(self):
        self.reject(SUCCESS + "INSTRUMENTATION_STATUS: class=" + CASES[0][0] + "\n")
        self.reject(SUCCESS.replace("INSTRUMENTATION_STATUS: numtests=11\n", "INSTRUMENTATION_STATUS: numtests=11\n" * 2, 1))
        self.reject(SUCCESS[:SUCCESS.index("INSTRUMENTATION_STATUS_CODE: 0")])


class PngTransferTest(unittest.TestCase):
    def reject(self, data):
        with self.assertRaises(controls.ControlsError):
            controls.png_dimensions(data)

    def test_independent_png_fixture_has_real_pixels_and_expected_dimensions(self):
        self.assertEqual(b"\x01\x00\xff", zlib.decompress(ONE_PIXEL_PNG[41:52]))
        self.assertEqual((1, 1), controls.png_dimensions(ONE_PIXEL_PNG))

    def test_empty_error_page_wrong_signature_and_oversized_bytes_fail(self):
        for data in [b"", b"remote object does not exist", b"<html>not an image</html>",
                     b"\x00" + ONE_PIXEL_PNG[1:], ONE_PIXEL_PNG + b"\x00" * (32 * 1024 * 1024)]:
            with self.subTest(length=len(data)):
                self.reject(data)

    def test_every_truncation_and_extra_trailing_bytes_fail(self):
        for length in range(len(ONE_PIXEL_PNG)):
            with self.subTest(length=length):
                self.reject(ONE_PIXEL_PNG[:length])
        self.reject(ONE_PIXEL_PNG + b"untrusted trailing bytes")

    def test_payload_or_crc_corruption_is_not_a_valid_capture(self):
        for offset in [16, 29, 44, 52, 64]:
            damaged = bytearray(ONE_PIXEL_PNG)
            damaged[offset] ^= 1
            with self.subTest(offset=offset):
                self.reject(bytes(damaged))

    def test_missing_pixel_data_and_repeated_header_fail(self):
        self.reject(ONE_PIXEL_PNG[:33] + ONE_PIXEL_PNG[-12:])
        self.reject(ONE_PIXEL_PNG[:33] + ONE_PIXEL_PNG[8:33] + ONE_PIXEL_PNG[33:])

    def test_zero_or_extreme_dimensions_fail_even_with_correct_header_crc(self):
        for width, height in [(0, 1), (1, 0), (8193, 1), (1, 8193), (8192, 8192)]:
            header = struct.pack(">II", width, height) + ONE_PIXEL_PNG[24:29]
            encoded = ONE_PIXEL_PNG[:16] + header + struct.pack(">I", zlib.crc32(b"IHDR" + header) & 0xffffffff) + ONE_PIXEL_PNG[33:]
            with self.subTest(dimensions=(width, height)):
                self.reject(encoded)


if __name__ == "__main__":
    unittest.main()
