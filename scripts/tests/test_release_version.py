import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "release_version", Path(__file__).resolve().parents[1] / "release-version.py"
)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleasePolicyTests(unittest.TestCase):
    def test_semver(self):
        cases = (
            ("0.2.0-alpha.1", "0.1.0-alpha.20"),
            ("0.2.0-alpha.10", "0.2.0-alpha.9"),
            ("0.2.0-beta.1", "0.2.0-alpha.99"),
            ("0.2.0-rc.1", "0.2.0-beta.9"),
            ("0.2.0", "0.2.0-rc.9"),
        )
        for higher, lower in cases:
            self.assertGreater(release.compare(higher, lower), 0)
            self.assertLess(release.compare(lower, higher), 0)

    def test_input_validation(self):
        for item in ("v0.2.0", "0.2.0-alpha.0", "0.2.0-alpha.01",
                     "00.2.0", "0.2.0-nightly.1", "0.2.0+build"):
            with self.assertRaises(ValueError):
                release.allocate(item, "alpha", (), lambda *_: 1)

    def test_allocation_and_channels(self):
        prior = (("v0.2.0-alpha.3", True, True), ("v0.1.0", False, True))
        self.assertEqual(release.allocate("0.2.0-beta.1", "alpha", prior, lambda *_: 15), (16, True))
        self.assertEqual(release.allocate("0.1.1", "stable", prior, lambda *_: 15), (16, False))
        for name, branch in (("0.2.0-alpha.2", "alpha"), ("0.2.0", "alpha"),
                             ("0.2.0-alpha.4", "main")):
            with self.assertRaises(ValueError):
                release.allocate(name, branch, prior, lambda *_: 15)


if __name__ == "__main__":
    unittest.main()
