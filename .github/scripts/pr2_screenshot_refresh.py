from pathlib import Path
import subprocess
import sys

BASE_SHA = "22dc0d1269b09f83c9bfce8c557c89f9802fbe4c"
SCRIPT_PATH = Path(".github/scripts/pr2_screenshot_refresh.py")


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"Expected patch anchor missing in {path}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))


def apply() -> None:
    browser = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryCollectionBrowser.kt"
    replace_once(
        browser,
        "import androidx.compose.foundation.ExperimentalFoundationApi\n",
        "import androidx.compose.foundation.ExperimentalFoundationApi\nimport androidx.compose.foundation.background\n",
    )
    replace_once(
        browser,
        "@Composable\nprivate fun LibraryCollectionAppPicker(\n",
        "@Composable\ninternal fun LibraryCollectionAppPicker(\n",
    )

    screenshots = Path(
        "app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/applist/LibraryProfilesScreenshotTest.kt"
    )
    text = screenshots.read_text()
    marker = "\n@PreviewTest\n@Preview(name = \"Library metadata editor\""
    addition = '''
@PreviewTest
@Preview(name = "Library collection add apps", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LibraryCollectionAppPickerScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryCollectionAppPicker(
            collection = LibraryCollectionUiItem(1L, "RPG Favorites", 2),
            allApps = PreviewApps,
            memberIds = setOf(1, 3),
            sortVariant = 0,
            iconRatio = LibraryIconRatio.Square,
            scaffoldPadding = PaddingValues(),
            onBack = {},
            onSetMembership = { _, _ -> },
        )
    }
}
'''
    if "fun LibraryCollectionAppPickerScreenshot()" not in text:
        index = text.find(marker)
        if index < 0:
            raise SystemExit("Screenshot insertion anchor missing")
        text = text[:index] + "\n" + addition + text[index:]
        screenshots.write_text(text)

    subprocess.run(["git", "diff", "--check"], check=True)


def finalize() -> None:
    subprocess.run(["git", "checkout", BASE_SHA, "--", ".github/workflows/android.yml"], check=True)
    SCRIPT_PATH.unlink()
    subprocess.run(["git", "config", "user.name", "github-actions[bot]"], check=True)
    subprocess.run(
        ["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"],
        check=True,
    )
    subprocess.run(["git", "add", "-A"], check=True)
    subprocess.run(["git", "diff", "--cached", "--check"], check=True)
    subprocess.run(["git", "commit", "-m", "Refresh Library v2 screenshot coverage"], check=True)
    subprocess.run(["git", "push", "origin", "HEAD:agent/library-pr2-features"], check=True)


if len(sys.argv) != 2 or sys.argv[1] not in {"apply", "finalize"}:
    raise SystemExit("usage: pr2_screenshot_refresh.py apply|finalize")

if sys.argv[1] == "apply":
    apply()
else:
    finalize()
