from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/android-release.yml"


class LegacyReleaseSafetyTests(unittest.TestCase):
    def run_cli(self, *arguments):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copy2(WORKFLOW.parents[2] / "scripts/release_beta.py", root / "scripts/release_beta.py")
            (root / "app").mkdir()
            (root / "app/build.gradle.kts").write_text('versionName = "0.1.2"\nversionCode = 3\n')
            summary = root / "summary.md"
            summary.write_text("Keep the existing summary.\n")
            (root / "bin").mkdir()
            git = root / "bin/git"
            git.write_text(f"#!{sys.executable}\n" +
                           "import sys\n" +
                           "if sys.argv[1:] == ['describe', '--tags', '--abbrev=0']: print('v0.1.1')\n" +
                           "elif sys.argv[1:] == ['log', '--reverse', '--pretty=format:%s', 'v0.1.1..HEAD']: print('fix: Repair playback')\n" +
                           "else: raise SystemExit('Unexpected git operation')\n")
            git.chmod(0o755)
            gradle = root / "gradlew"
            gradle.write_text(f"#!{sys.executable}\nraise SystemExit('Unexpected build')\n")
            gradle.chmod(0o755)

            def snapshot():
                return {str(path.relative_to(root)): path.read_bytes() if path.is_file() else None
                        for path in root.rglob("*")}

            before = snapshot()
            result = subprocess.run([sys.executable, str(root / "scripts/release_beta.py"), *arguments],
                                    cwd=root, text=True, capture_output=True,
                                    env=dict(os.environ, PATH=str(root / "bin"),
                                             PYTHONDONTWRITEBYTECODE="1", GITHUB_STEP_SUMMARY=str(summary)))
            return result, before, snapshot()

    def test_legacy_build_draft_and_publish_are_rejected_without_mutation(self):
        for mode in ((), ("--draft",), ("--publish",)):
            for release in (("0.1.3",), ("--manual-release", "--release-tag", "v0.1.2", "--release-title", "Preview")):
                with self.subTest(mode=mode, release=release):
                    result, before, after = self.run_cli(*release, *mode)
                    self.assertEqual(before, after, result.stderr)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("android-release.yml", result.stderr)
                    self.assertIn("verify_release_identity.py", result.stderr)

    def test_legacy_preview_is_read_only_including_job_summary(self):
        for release in (("0.1.3",), ("--manual-release", "--release-tag", "v0.1.2", "--release-title", "Preview")):
            with self.subTest(release=release):
                result, before, after = self.run_cli(*release, "--dry-run")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(before, after)
                self.assertIn("Repair playback", result.stdout)


class ReleaseSecurityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = WORKFLOW.read_text()

    def job(self, name: str) -> str:
        match = re.search(rf"^  {name}:\n(.*?)(?=^  [a-zA-Z_-]+:|\Z)", self.workflow, re.M | re.S)
        self.assertIsNotNone(match, f"Missing {name} job")
        return match.group(0)

    def test_preview_has_read_access_and_no_signing_secrets(self) -> None:
        self.assertRegex(self.workflow, r"(?m)^permissions:\n  contents: read$")
        preview = self.job("review")
        self.assertNotIn("secrets.", preview)
        self.assertNotIn("contents: write", preview)

    def test_signing_runs_only_in_the_protected_release_job(self) -> None:
        release = self.job("release")
        self.assertIn("needs: review", release)
        self.assertIn("if: inputs.mode != 'dry-run'", release)
        self.assertIn("environment: android-release", release)
        self.assertIn("contents: write", release)
        steps = re.split(r"(?m)^      - name: ", release)
        secret_steps = [step for step in steps if "secrets." in step]
        self.assertEqual(1, len(secret_steps))
        self.assertTrue(secret_steps[0].startswith("Configure release properties\n"))
        self.assertIn("umask 077", secret_steps[0])
        self.assertIn("Clean release signing files", release)

    def test_every_action_is_pinned_and_checkout_does_not_keep_credentials(self) -> None:
        actions = re.findall(r"(?m)^\s+uses: (\S+)", self.workflow)
        self.assertTrue(actions)
        self.assertTrue(all(re.fullmatch(r"[\w/-]+@[0-9a-f]{40}", action) for action in actions))
        self.assertEqual(self.workflow.count("uses: actions/checkout@"),
                         self.workflow.count("persist-credentials: false"))

    def test_release_assets_and_tag_match_the_in_app_updater(self) -> None:
        metadata = WORKFLOW.parents[2] / "scripts/release-metadata.sh"
        self.assertIn("printf 'tag=v%s\\n'", metadata.read_text())
        release = self.job("release")
        self.assertIn("release/syclop-release.apk", release)
        self.assertIn("release/syclop-arm64-release.apk", release)
        self.assertIn("release/SHA256SUMS", release)
        self.assertNotIn("find app/build/outputs/apk/full/release", release)

    def test_same_reviewed_notes_are_bound_before_build_and_verified_before_publication(self) -> None:
        release = self.job("release")
        self.assertLess(release.index("Download reviewed release notes"), release.index("Bind reviewed notes to release version"))
        self.assertLess(release.index("Bind reviewed notes to release version"), release.index("Test updater and build full release APKs"))
        self.assertLess(release.index("Verify bundled release notes"), release.index("Create GitHub release"))
        self.assertIn("--notes release-notes.md", release)
        self.assertIn("--notes-file release-notes.md", release)
        self.assertIn('> release-notes.version', release)

    def test_signed_identity_is_verified_before_artifact_upload_and_release_creation(self) -> None:
        release = self.job("release")
        identity = release.index("Verify current release identity")
        self.assertLess(release.index("Verify bundled release notes"), identity)
        self.assertLess(identity, release.index("Upload build artifacts"))
        self.assertLess(identity, release.index("Create GitHub release"))
        self.assertIn('--source-commit "${GITHUB_SHA}"', release)
        self.assertIn('--output release/release-identity.json', release)


if __name__ == "__main__":
    unittest.main()
