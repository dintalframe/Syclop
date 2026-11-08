from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/android-release.yml"


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


if __name__ == "__main__":
    unittest.main()
