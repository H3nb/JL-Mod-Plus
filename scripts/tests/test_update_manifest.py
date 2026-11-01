import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "generate-update-manifest.py"
spec = importlib.util.spec_from_file_location("update_manifest", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def release(version, prerelease=True, minimum=23):
    tag = "v" + version
    return {
        "tag_name": tag, "prerelease": prerelease, "draft": False,
        "assets": [
            {"name": "release-info.json"},
            {"name": f"JL-Mod-Plus-{tag}-arm64-v8a.apk",
             "browser_download_url": f"https://github.com/H3nb/JL-Mod-Plus/releases/download/{tag}/JL-Mod-Plus-{tag}-arm64-v8a.apk"},
        ],
    }


class UpdateManifestTest(unittest.TestCase):
    def test_includes_stable_preview_and_older_compatible_candidates(self):
        candidates = [
            release("0.3.0-beta"), release("0.2.9", False),
            release("0.2.5-beta"),
        ]
        def info(tag):
            name = tag[1:]
            return {"schema": 1, "applicationId": module.PACKAGE,
                    "versionName": name, "versionCode": {"v0.3.0-beta": 30,
                    "v0.2.9": 29, "v0.2.5-beta": 25}[tag], "minSdk": 23}
        manifest = module.build_manifest([candidates], info)
        self.assertEqual([entry["versionName"] for entry in manifest["releases"]],
                         ["0.3.0-beta", "0.2.9", "0.2.5-beta"])
        self.assertFalse(manifest["releases"][1]["prerelease"])
        self.assertEqual(manifest["releases"][0]["assets"][0]["abi"], "arm64-v8a")

    def test_skips_drafts_and_unverified_legacy_releases(self):
        draft = release("0.3.0-beta")
        draft["draft"] = True
        legacy = release("0.2.5-beta")
        legacy["assets"] = []
        self.assertEqual(len(module.build_manifest([[draft, legacy, release("0.2.4-beta")]],
                          lambda tag: {"schema": 1, "applicationId": module.PACKAGE,
                                       "versionName": tag[1:], "versionCode": 24,
                                       "minSdk": 23})["releases"]), 1)

    def test_published_site_has_entrypoint_and_valid_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            releases_file = root / "releases.json"
            releases_file.write_text(json.dumps([[release("0.3.0-beta")]]))
            output = root / "_site" / "updates.json"
            metadata = {
                "schema": 1, "applicationId": module.PACKAGE,
                "versionName": "0.3.0-beta", "versionCode": 30, "minSdk": 23,
            }
            with patch.object(module, "download_metadata", return_value=metadata), patch(
                "sys.argv", ["generate-update-manifest.py", "--releases", str(releases_file),
                             "--output", str(output)],
            ):
                module.main()
            self.assertEqual(json.loads(output.read_text())["releases"][0]["versionName"],
                             "0.3.0-beta")
            self.assertIn('href="updates.json"', output.with_name("index.html").read_text())

    def test_rejects_missing_or_inconsistent_published_assets(self):
        candidate = release("0.3.0-beta")
        candidate["assets"][1]["browser_download_url"] = "https://example.invalid/fake.apk"
        with self.assertRaises(ValueError):
            module.build_manifest([[candidate]], lambda tag: {
                "schema": 1, "applicationId": module.PACKAGE,
                "versionName": tag[1:], "versionCode": 30, "minSdk": 23,
            })


if __name__ == "__main__":
    unittest.main()
