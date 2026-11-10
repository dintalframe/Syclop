from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from verify_bundled_release_notes import apk_version, verify_bundled_notes


class BundledReleaseNotesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.notes = self.root / "release-notes.md"
        self.notes.write_bytes(b"### Improvements\n- Smoother remote menus.\n")
        self.apk = self.root / "fixture.apk"

    def bundle(self, version="0.1.3", notes=None):
        with ZipFile(self.apk, "w") as archive:
            archive.writestr("assets/release-notes.version", version + "\n")
            archive.writestr("assets/release-notes.md", self.notes.read_bytes() if notes is None else notes)

    def test_exact_publication_notes_and_packaged_version_pass(self):
        self.bundle()
        verify_bundled_notes(self.apk, self.notes, "0.1.3", "0.1.3")

    def test_changed_embedded_notes_fail(self):
        self.bundle(notes=b"Old release notes")
        with self.assertRaisesRegex(ValueError, "notes"):
            verify_bundled_notes(self.apk, self.notes, "0.1.3", "0.1.3")

    def test_stale_bundle_or_different_apk_version_fail(self):
        self.bundle(version="0.1.2")
        with self.assertRaisesRegex(ValueError, "version"):
            verify_bundled_notes(self.apk, self.notes, "0.1.3", "0.1.3")
        self.bundle()
        with self.assertRaisesRegex(ValueError, "version"):
            verify_bundled_notes(self.apk, self.notes, "0.1.3", "0.1.4")

    def test_missing_notes_and_publish_placeholder_fail(self):
        for notes in (b" \n", b"Release notes pending. Replace this text before publishing."):
            with self.subTest(notes=notes):
                self.notes.write_bytes(notes)
                self.bundle()
                with self.assertRaisesRegex(ValueError, "notes"):
                    verify_bundled_notes(self.apk, self.notes, "0.1.3", "0.1.3")

    def test_package_version_is_read_from_badging_not_sdk_versions(self):
        self.assertEqual("0.1.3", apk_version("package: name='com.dintal.syclop' versionCode='4' versionName='0.1.3'\nsdkVersion:'24'\n"))
        with self.assertRaisesRegex(ValueError, "version"):
            apk_version("sdkVersion:'24'\n")

    def test_missing_embedded_file_fails(self):
        with ZipFile(self.apk, "w") as archive:
            archive.writestr("assets/release-notes.version", "0.1.3")
        with self.assertRaisesRegex(ValueError, "notes"):
            verify_bundled_notes(self.apk, self.notes, "0.1.3", "0.1.3")


if __name__ == "__main__":
    unittest.main()
