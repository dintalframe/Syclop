"""Fixed, account-free UI fixtures for the local Android TV bench."""
import json
import re
import struct
import zlib

PACKAGE = "com.dintal.syclop"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TESTS = {
    "com.nuvio.tv.ui.screens.detail.PlaybackStartActionsTvTest": (
        "movieResumeAndStartFromBeginningAreSeparateRemoteActions",
        "episodeResumeAndStartFromBeginningAreSeparateRemoteActions",
        "unavailableEpisodeDisablesBothPlaybackActions",
        "unstartedMovieDoesNotOfferARedundantStartFromBeginningAction"),
    "com.nuvio.tv.ui.screens.player.PlaybackActionButtonsTvTest": (
        "unairedEpisodeDoesNotOfferNext",
        "finalEpisodeKeepsStartFromBeginningWithoutANextAction",
        "inFlightNextSearchDoesNotOfferAnotherNextAction",
        "ordinaryRemotePressSelectsEachLabelledActionOnce"),
    "com.nuvio.tv.ui.screens.settings.AppRestartSettingsTvTest": (
        "cancelIsInitiallyFocusedAndReturnsToTheSettingsAction",
        "backDismissesConfirmationWithoutRestarting",
        "rightThenCenterConfirmsExactlyOneRestartRequest")
}
EXPECTED_TESTS = frozenset((group, method) for group, methods in TESTS.items() for method in methods)
SCREENSHOTS = (
    "playback-movie-resume-and-start.png", "playback-episode-resume-and-start.png",
    "playback-labelled-start-and-next.png", "playback-final-episode-actions.png",
    "restart-confirmation-cancel-focused.png", "restart-confirmation-restart-focused.png")
CATEGORY_TESTS = {
    "com.nuvio.tv.ui.screens.home.HomeCategoryNavigationTvTest": (
        "lateAnimeCatalogKeepsFocusedCardAndOneUpReturnsToHeader",
        "lateKidsGroupKeepsSafeEntryAndOneUpReturnsToHeader",
        "changingCategoriesKeepsHeaderFocusAndUsesNewCategoryRows",
        "newRequestRevisionRestoresConfiguredOrderWithoutOldFocus",
        "lateHomeBodyCannotStealHeaderAndDownRestoresRememberedCard")
}
CATEGORY_SCREENSHOTS = (
    "category-anime-late-arrival.png", "category-kids-late-group.png",
    "category-change-anime-header.png", "category-request-order-reset.png",
    "category-home-late-header.png", "category-home-remembered-entry.png")
EPISODE_TESTS = {
    "com.nuvio.tv.ui.screens.stream.EpisodeSourceCardsTvTest": (
        "completeSeasonPackIsHiddenAndExactEpisodeCardKeepsRemoteFocus",
        "seasonPackOnlyResultsStayEmptyWithoutSubstitutingAnotherEpisode")
}
EPISODE_SCREENSHOTS = (
    "episode-source-only-single-episode.png", "episode-source-packs-only-empty.png")
STARTUP_TESTS = {
    "com.nuvio.tv.ui.components.StartupInitializationScreenTvTest": (
        "lateBackgroundFocusCannotEscapeInitializationGate",
        "retryContinueAndBackKeepTheirActionsAndRestoreHomeFocus",
        "repeatedGateTransitionsKeepTheSameNativeWindowRoots")
}
STARTUP_SCREENSHOTS = (
    "startup-gate-background-blocked.png", "startup-gate-actions.png",
    "startup-gate-resumed-home.png", "startup-gate-same-window.png")
PROGRESSIVE_TESTS = {
    "com.nuvio.tv.ui.screens.stream.ProgressiveSourcesTvTest": (
        "laterBetterResultDoesNotStealTheSelectedSourceFocus",
        "emptySearchDisplaysStructuredReasonsWithoutMediaOrAccountData")
}
PROGRESSIVE_SCREENSHOTS = (
    "progressive-sources-stable-focus.png", "progressive-sources-empty-diagnostics.png")
CONTINUE_WATCHING_TESTS = {
    "com.nuvio.tv.ui.screens.home.ContinueWatchingPlaybackTvTest": (
        "latestLocalEpisodeReplacesOlderPausedEpisode",
        "completedLatestEpisodeAdvancesNextUp")
}
CONTINUE_WATCHING_SCREENSHOTS = (
    "continue-watching-latest-episode.png", "continue-watching-next-up.png")
SUITE_MIN_API = {"startup-initialization": 29}
SUITES = {
    "playback-controls": (TESTS, SCREENSHOTS),
    "category-navigation": (CATEGORY_TESTS, CATEGORY_SCREENSHOTS),
    "episode-sources": (EPISODE_TESTS, EPISODE_SCREENSHOTS),
    "startup-initialization": (STARTUP_TESTS, STARTUP_SCREENSHOTS),
    "progressive-sources": (PROGRESSIVE_TESTS, PROGRESSIVE_SCREENSHOTS),
    "continue-watching": (CONTINUE_WATCHING_TESTS, CONTINUE_WATCHING_SCREENSHOTS),
}


class ControlsError(ValueError):
    """Messages are fixed labels, never raw APK, instrumentation or account output."""


def manifest_facts(tree):
    """Read aapt2's XML tree within element scope, including duplicate rejection."""
    if len(tree) > 2 * 1024 * 1024:
        raise ControlsError("APK manifest exceeded the inspection limit.")
    nodes, stack = [], []
    for line in tree.splitlines():
        element = re.fullmatch(r"(\s*)E: ([\w.-]+)(?: \(line=\d+\))?", line)
        if element:
            depth = len(element[1])
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = {"name": element[2], "parent": stack[-1][1] if stack else None, "attrs": {}}
            nodes.append(node)
            stack.append((depth, len(nodes)-1))
            continue
        attribute = re.fullmatch(r"(\s*)A: (.+?)=(.*)", line)
        if not attribute:
            continue
        if not stack or len(attribute[1]) <= stack[-1][0]:
            raise ControlsError("APK manifest attribute scope is invalid.")
        key = attribute[2].split("(", 1)[0]
        namespace = "http://schemas.android.com/apk/res/android:"
        if key.startswith(namespace):
            key = "android:" + key[len(namespace):]
        elif key.startswith("android:"):
            key = "unexpanded:" + key  # SDK36 aapt2 emits the expanded namespace URI.
        raw = attribute[3]
        try:
            if raw.startswith('"'):
                value, _ = json.JSONDecoder().raw_decode(raw)
            elif raw in ("true", "false"):
                value = raw == "true"
            elif re.fullmatch(r"\d+|0x[0-9a-fA-F]+", raw):
                value = int(raw, 16 if raw.startswith("0x") else 10)
            else:
                value = None  # Resource references are not literal identity/permission facts.
        except (ValueError, TypeError):
            raise ControlsError("APK manifest literal could not be read.") from None
        attrs = nodes[stack[-1][1]]["attrs"]
        if key in attrs:
            raise ControlsError("APK manifest contains duplicate attributes.")
        attrs[key] = value
    roots = [i for i, node in enumerate(nodes) if node["parent"] is None]
    if len(roots) != 1 or nodes[roots[0]]["name"] != "manifest":
        raise ControlsError("APK manifest root is invalid.")
    root = roots[0]
    def children(name):
        return [node["attrs"] for node in nodes if node["parent"] == root and node["name"] == name]
    applications, sdk = children("application"), children("uses-sdk")
    if len(applications) != 1 or len(sdk) != 1:
        raise ControlsError("APK application or SDK declaration is ambiguous.")
    floor = sdk[0].get("android:minSdkVersion")
    if type(floor) is not int or not 1 <= floor <= 10000:
        raise ControlsError("APK minimum SDK is not explicit.")
    return {"package": nodes[root]["attrs"].get("package"), "min_sdk": floor,
            "debuggable": applications[0].get("android:debuggable") is True,
            "instrumentation": children("instrumentation")}


def validate_manifest(tree, package):
    facts = manifest_facts(tree)
    if facts["package"] != package or not facts["debuggable"]:
        raise ControlsError("Fixed fixtures require the exact debuggable app and test packages.")
    declarations = facts["instrumentation"]
    if package == TEST_PACKAGE:
        if len(declarations) != 1 or declarations[0].get("android:name") != RUNNER or declarations[0].get("android:targetPackage") != PACKAGE:
            raise ControlsError("Test APK does not target Syclop with the required runner.")
    elif package != PACKAGE or declarations:
        raise ControlsError("App APK has an unexpected instrumentation declaration.")
    return facts


def instrumentation_result(output, returncode, suite="playback-controls"):
    """Require the exact event protocol for the selected fixed fixture suite."""
    if suite not in SUITES:
        raise ControlsError("Unknown fixed fixture suite.")
    expected = frozenset((group, method) for group, methods in SUITES[suite][0].items() for method in methods)
    completed, started, pending = set(), set(), {}
    active, final_codes, valid = None, [], returncode == 0
    in_summary, summaries, summary_streams = False, 0, 0
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                if key in pending or final_codes or in_summary:
                    valid = False
                pending[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.partition(": ")[2]
            pair = pending.get("class"), pending.get("test")
            good = (pair in expected and pending.get("numtests") == str(len(expected))
                    and pending.get("current") == str(len(completed)+1)
                    and pending.get("id") == "AndroidJUnitRunner" and not final_codes)
            if code == "1" and good and active is None and pair not in started:
                started.add(pair)
                active = pair
            elif code == "0" and good and active == pair and pair not in completed:
                completed.add(pair)
                active = None
            else:
                valid = False
            pending = {}
        elif line == "INSTRUMENTATION_RESULT: stream=":
            summary_streams += 1
            in_summary = completed == expected and active is None and not pending and not final_codes
            if not in_summary:
                valid = False
        elif re.fullmatch(r"OK \(" + str(len(expected)) + r" tests\)\s*", line):
            if in_summary and not final_codes:
                summaries += 1
            else:
                valid = False
        elif line.startswith("INSTRUMENTATION_CODE: "):
            final_codes.append(line.partition(": ")[2])
            in_summary = False
    if re.search(r"INSTRUMENTATION_FAILED|FAILURES!!!|shortMsg=|Process crashed|INSTRUMENTATION_RESULT: (?:Error|error)=", output):
        valid = False
    valid = (valid and not pending and active is None and completed == expected
             and started == expected and final_codes == ["-1"]
             and summaries == 1 and summary_streams == 1)
    return {"expected": len(expected), "completed": len(completed), "passed": bool(valid)}


def png_dimensions(data):
    """Reject stale-file errors, truncated transfers and corrupt PNGs before reporting a capture."""
    if not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) > 32 * 1024 * 1024:
        raise ControlsError("A playback-control screenshot is not a bounded PNG.")
    offset, dimensions, saw_data = 8, None, False
    while offset + 12 <= len(data):
        size = struct.unpack(">I", data[offset:offset+4])[0]
        end = offset + 12 + size
        if end > len(data):
            break
        kind, payload = data[offset+4:offset+8], data[offset+8:offset+8+size]
        if zlib.crc32(kind+payload) & 0xffffffff != struct.unpack(">I", data[end-4:end])[0]:
            break
        if dimensions is None:
            if kind != b"IHDR" or size != 13:
                break
            dimensions = struct.unpack(">II", payload[:8])
            if not all(0 < number <= 8192 for number in dimensions) or dimensions[0]*dimensions[1] > 16777216:
                break
        elif kind == b"IHDR":
            break
        if kind == b"IDAT":
            saw_data = True
        if kind == b"IEND":
            if size == 0 and saw_data and end == len(data):
                return dimensions
            break
        offset = end
    raise ControlsError("A playback-control screenshot is incomplete or corrupt.")
