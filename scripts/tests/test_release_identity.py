import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import warnings
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / "verify_release_identity.py"
COMMIT = "21d3021db57275f3a31387df6ba00a3724298ba2"
SIGNER = "42dc538b170b87c5bbc1f89bdc32bdef3408f1eddd6d5141388cdaa797602f2e"
ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
VCS = ('repositories { system: GIT local_root_path: "$PROJECT_DIR" '
       f'revision: "{COMMIT}" }}\n')


class ReleaseIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = self.root / "native.json"
        self.manifest.write_text(json.dumps({
            "versionName": "0.1.1", "versionCode": 2,
            "minimumAndroidApi": 24, "abis": ABIS,
            "originalUpdateSignerCertificateSha256": SIGNER,
            "sourceCompanion": {
                "assetName": "syclop-native-sources-0.1.1.tar.gz",
                "sha256": "a" * 64, "bytes": 75444584,
                "releaseAssetPath": "https://github.com/dintalframe/Syclop/releases/download/v0.1.1/syclop-native-sources-0.1.1.tar.gz",
            },
        }))
        self.output = self.root / "release-identity.json"
        self.apks = [self.root / "syclop-release.apk", self.root / "syclop-arm64-release.apk"]
        for apk, abis in zip(self.apks, [ABIS, ["arm64-v8a"]]):
            self.bundle(apk)
            self.response(apk, abis=abis)
        self.aapt = self.tool("aapt", ["dump", "badging"], "badging")
        self.apksigner = self.tool("apksigner", ["verify", "--print-certs"], "signer")

    def tool(self, name, arguments, field):
        path = self.root / name
        path.write_text(f"#!{sys.executable}\n" +
                        "import json, pathlib, sys\n" +
                        f"assert sys.argv[1:-1] == {arguments!r}\n" +
                        "data = json.loads(pathlib.Path(sys.argv[-1] + '.json').read_text())\n" +
                        f"print(data[{field!r}])\n" +
                        f"raise SystemExit(data.get('{field}Exit', 0))\n")
        path.chmod(0o755)
        return path

    def bundle(self, apk, vcs=VCS, duplicate=False):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"controlled APK fixture")
                if vcs is not None:
                    archive.writestr("META-INF/version-control-info.textproto", vcs)
                    if duplicate:
                        archive.writestr("META-INF/version-control-info.textproto", vcs)

    def response(self, apk, **changes):
        fields = {
            "package": "com.dintal.syclop", "versionCode": "3", "versionName": "0.1.2",
            "minimumApi": "24", "abis": ABIS if apk == self.apks[0] else ["arm64-v8a"],
            "debuggable": False, "signer": f"Signer #1 certificate SHA-256 digest: {SIGNER}",
        }
        fields.update(changes)
        fields["badging"] = (
            f"package: name='{fields['package']}' versionCode='{fields['versionCode']}' versionName='{fields['versionName']}'\n"
            f"sdkVersion:'{fields['minimumApi']}'\n" +
            "native-code: " + " ".join(f"'{abi}'" for abi in fields["abis"]) + "\n" +
            ("application-debuggable\n" if fields["debuggable"] else "")
        )
        fields.update({key: value for key, value in changes.items() if key == "badging"})
        Path(str(apk) + ".json").write_text(json.dumps(fields))

    def run_cli(self, extra=(), apks=None, repository=True):
        return subprocess.run([
            sys.executable, str(SCRIPT), "--aapt", str(self.aapt),
            "--apksigner", str(self.apksigner), "--version", "0.1.2",
            "--source-commit", COMMIT, "--native-manifest", str(self.manifest),
            "--output", str(self.output),
            *(["--repository", "dintalframe/Syclop"] if repository else []),
            *extra, *map(str, self.apks if apks is None else apks),
        ], text=True, capture_output=True)

    def assert_rejected(self, **kwargs):
        # A failed rerun must not leave yesterday's successful identity available for publication.
        self.output.write_text('{"stale": true}')
        result = self.run_cli(**kwargs)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertFalse(self.output.exists(), result.stderr)

    def test_verified_artifacts_and_historical_native_source_are_separate(self):
        result = self.run_cli()
        self.assertEqual(0, result.returncode, result.stderr)
        identity = json.loads(self.output.read_text())
        self.assertEqual("syclop-release.apk", identity["file"])
        self.assertEqual("com.dintal.syclop", identity["package"])
        self.assertEqual("0.1.2", identity["versionName"])
        self.assertEqual(3, identity["versionCode"])
        self.assertEqual(24, identity["minimumApi"])
        self.assertEqual(ABIS, identity["abis"])
        self.assertIs(False, identity["debuggable"])
        self.assertEqual(SIGNER, identity["signerSha256"])
        self.assertEqual(COMMIT, identity["sourceCommit"])
        self.assertEqual(f"https://github.com/dintalframe/Syclop/tree/{COMMIT}", identity["sourceUrl"])
        self.assertEqual({
            "file": "syclop-native-sources-0.1.1.tar.gz", "sha256": "a" * 64,
            "bytes": 75444584,
            "releaseAssetPath": "https://github.com/dintalframe/Syclop/releases/download/v0.1.1/syclop-native-sources-0.1.1.tar.gz",
        }, identity["nativeSourceCompanion"])
        self.assertEqual(2, len(identity["artifacts"]))
        for apk, facts, abis in zip(self.apks, identity["artifacts"], [ABIS, ["arm64-v8a"]]):
            self.assertEqual(apk.name, facts["file"])
            self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), facts["sha256"])
            self.assertEqual(apk.stat().st_size, facts["bytes"])
            self.assertEqual(abis, facts["abis"])
            self.assertEqual(COMMIT, facts["sourceCommit"])
            self.assertEqual(SIGNER, facts["signerSha256"])
        self.assertEqual(identity["artifacts"][0]["sha256"], identity["sha256"])
        self.assertEqual(identity["artifacts"][0]["bytes"], identity["bytes"])
        serialized = self.output.read_text()
        for unsupported in ("alignment16KiB", "unchangedNativeLibraries", "qualification", "$PROJECT_DIR", str(self.root)):
            self.assertNotIn(unsupported, serialized)

    def test_repository_is_optional_and_input_order_does_not_select_primary(self):
        self.bundle(self.apks[1], vcs=VCS + 'repositories { system: GIT local_root_path: "nested" revision: "' + "b" * 40 + '" }')
        result = self.run_cli(repository=False, apks=list(reversed(self.apks)))
        self.assertEqual(0, result.returncode, result.stderr)
        identity = json.loads(self.output.read_text())
        self.assertEqual("syclop-release.apk", identity["file"])
        self.assertNotIn("sourceUrl", identity)

    def test_output_path_cannot_destroy_an_input_artifact_or_manifest(self):
        for path in (*self.apks, self.manifest):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                result = self.run_cli(extra=["--output", str(path)])
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(original, path.read_bytes())

    def test_rejects_incompatible_identity_in_either_artifact(self):
        cases = [{"package": "other.app"}, {"versionCode": "0"}, {"versionCode": "4"},
                 {"versionCode": "oops"}, {"versionName": "0.1.1"}, {"minimumApi": "23"},
                 {"debuggable": True}, {"abis": []}, {"abis": ["x86"]},
                 {"signer": f"Signer #1 certificate SHA-256 digest: {'b' * 64}"}]
        for apk in self.apks:
            for change in cases:
                with self.subTest(apk=apk.name, change=change):
                    self.response(apk, **change)
                    self.assert_rejected()
                    self.response(apk)

    def test_signer_digest_requires_successful_verification_and_is_unambiguous(self):
        for change in ({"signerExit": 1}, {"signer": "Signer #1 certificate SHA-1 digest: aabb"},
                       {"signer": f"Signer #1 certificate SHA-256 digest: {SIGNER}\nSigner #2 certificate SHA-256 digest: {SIGNER}"},
                       {"signer": f"Signer #1 certificate SHA-256 digest: {SIGNER}\nSigner #1 certificate SHA-256 digest: {SIGNER}"},
                       {"signer": f"Signer #1 certificate SHA-256 digest: {SIGNER}\nSigner #2 certificate DN: CN=unreported-digest"},
                       {"signer": "Signer #1 certificate SHA-256 digest: malformed"}):
            with self.subTest(change=change):
                self.response(self.apks[1], **change)
                self.assert_rejected()

    def test_missing_mismatched_ambiguous_or_unbounded_vcs_is_rejected(self):
        for vcs in (None, VCS.replace(COMMIT, "f" * 40), VCS + VCS,
                    VCS.replace("GIT", "SVN"), VCS.replace("$PROJECT_DIR", "nested"),
                    VCS.replace(COMMIT, COMMIT[:-1]), VCS.replace("revision:", "other:"),
                    VCS + "garbage", " " * 65537 + VCS):
            with self.subTest(vcs=str(vcs)[:90]):
                self.bundle(self.apks[1], vcs=vcs)
                self.assert_rejected()
        self.bundle(self.apks[1], duplicate=True)
        self.assert_rejected()

    def test_corrupt_zip_failed_aapt_or_ambiguous_badging_is_rejected(self):
        self.apks[0].write_bytes(b"not a zip")
        self.assert_rejected()
        self.bundle(self.apks[0])
        for change in ({"badgingExit": 1}, {"badging": ""},
                       {"badging": "package: name='com.dintal.syclop' versionCode='3' versionName='0.1.2'\n" * 2}):
            self.response(self.apks[0], **change)
            self.assert_rejected()

    def test_exact_artifact_pair_is_required(self):
        for apks in ([], [self.apks[0]], [self.apks[1]], self.apks + [self.apks[0]],
                     [self.apks[0], self.root / "other.apk"]):
            with self.subTest(apks=apks):
                self.assert_rejected(apks=apks)

    def test_invalid_public_inputs_are_rejected(self):
        for option, value in (("--version", "0.1.2\nSECRET"), ("--source-commit", "short"),
                              ("--repository", "owner/repo/extra"), ("--repository", "owner/repo\nSECRET"),
                              ("--repository", "owner/.."), ("--repository", "owner/.")):
            with self.subTest(option=option, value=value):
                self.assert_rejected(extra=[option, value])
        native = json.loads(self.manifest.read_text())
        for field, value in (("originalUpdateSignerCertificateSha256", "bad"), ("minimumAndroidApi", 23)):
            changed = dict(native, **{field: value})
            self.manifest.write_text(json.dumps(changed))
            self.assert_rejected()
        self.manifest.write_text(json.dumps(native))
        for field, value in (("sha256", "bad"), ("assetName", "../private"),
                              ("bytes", -1), ("releaseAssetPath", "https://user:secret@example.com/file")):
            changed = dict(native, sourceCompanion=dict(native["sourceCompanion"], **{field: value}))
            self.manifest.write_text(json.dumps(changed))
            self.assert_rejected()


if __name__ == "__main__":
    unittest.main()
