import gzip
import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "prepare_release", Path(__file__).resolve().parents[1] / "prepare-release.py"
)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseAssetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apks = self.root / "apks"
        self.apks.mkdir()
        self.mapping = self.root / "mapping.txt"
        self.mapping.write_bytes(b"example.Source -> a:\n" * 100)
        self.metadata = {"applicationId": release.PACKAGE, "elements": []}
        for label in release.LABELS:
            self.write_apk(label)
            self.metadata["elements"].append({
                "versionName": "0.2.3-alpha", "versionCode": 24,
                "filters": [] if label == "universal" else [{"filterType": "ABI", "value": label}],
                "outputFile": label + ".apk",
            })
        self.save_metadata()

    def write_apk(self, label, compression=zipfile.ZIP_DEFLATED, abis=None):
        abis = abis if abis is not None else (release.ABIS if label == "universal" else (label,))
        with zipfile.ZipFile(self.apks / (label + ".apk"), "w", compression=compression) as archive:
            for abi in abis:
                archive.writestr(f"lib/{abi}/libexample.so", b"native-content" * 100)

    def save_metadata(self):
        (self.apks / "output-metadata.json").write_text(json.dumps(self.metadata))

    def prepare(self):
        output = self.root / "dist"
        release.prepare(self.apks, self.mapping, output, "0.2.3-alpha", 24,
                        "a" * 40, 23, "H3nb/JL-Mod-Plus")
        return output

    def test_staged_assets_retain_mapping_identity_and_verifiable_checksums(self):
        output = self.prepare()
        mapping = next(output.glob("*.gz"))
        self.assertEqual(gzip.decompress(mapping.read_bytes()), self.mapping.read_bytes())
        for line in (output / "SHA256SUMS.txt").read_text().splitlines():
            digest, name = line.split("  ")
            self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())
        assets = json.loads((output / "assets.json").read_text())
        self.assertEqual(len(assets), 8)
        self.assertTrue(all(Path(asset["path"]).is_file() for asset in assets))
        info = json.loads((output / "release-info.json").read_text())
        self.assertEqual((info["versionName"], info["versionCode"]), ("0.2.3-alpha", 24))
        self.assertEqual(info["applicationId"], release.PACKAGE)
        notes = (output / "release-notes.md").read_text()
        self.assertIn("/releases/download/v0.2.3-alpha/JL-Mod-Plus-v0.2.3-alpha-universal.apk", notes)

    def test_wrong_identity_and_duplicate_outputs_fail_before_staging(self):
        for field, value in (("versionName", "0.2.2-alpha.1"), ("versionCode", 23)):
            original = self.metadata["elements"][0][field]
            self.metadata["elements"][0][field] = value
            self.save_metadata()
            with self.assertRaisesRegex(ValueError, "identity"):
                self.prepare()
            self.metadata["elements"][0][field] = original
        self.metadata["elements"].append(self.metadata["elements"][0])
        self.save_metadata()
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.prepare()
        self.assertFalse((self.root / "dist").exists())

    def test_missing_variant_and_wrong_native_contents_fail(self):
        self.write_apk("universal", abis=("arm64-v8a",))
        with self.assertRaisesRegex(ValueError, "Native ABI"):
            self.prepare()
        self.write_apk("universal")
        self.metadata["elements"].pop()
        self.save_metadata()
        with self.assertRaisesRegex(ValueError, "four ABI"):
            self.prepare()

    def test_uncompressed_native_is_rejected(self):
        self.write_apk("arm64-v8a", compression=zipfile.ZIP_STORED)
        with self.assertRaisesRegex(ValueError, "must be compressed"):
            self.prepare()


if __name__ == "__main__":
    unittest.main()
