from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
LIBRARY = ROOT / "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt"
SCREENSHOT_TEST = ROOT / "app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/applist/LibraryProfilesScreenshotTest.kt"
REFERENCE_DIR = ROOT / "app/src/screenshotTestEmulatorDebug/reference/ru/playsoftware/j2meloader/applist/LibraryProfilesScreenshotTestKt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


text = LIBRARY.read_text(encoding="utf-8")

# Options should use grouping and whitespace only; section surfaces already provide enough hierarchy.
options_start = text.index("@OptIn(ExperimentalLayoutApi::class)\n@Composable\ninternal fun LibraryOptionsDestination(")
options_end = text.index("@OptIn(ExperimentalFoundationApi::class)\n@Composable\nprivate fun LibraryGridItem(", options_start)
options = text[options_start:options_end]
dividers = re.findall(r"^\s*HorizontalDivider\([^\n]*\)\n", options, flags=re.MULTILINE)
if len(dividers) != 5:
    raise RuntimeError(f"Options dividers: expected 5, found {len(dividers)}")
options = re.sub(r"^\s*HorizontalDivider\([^\n]*\)\n", "", options, flags=re.MULTILINE)
text = text[:options_start] + options + text[options_end:]

# More/Lainnya duplicated About's description and is no longer exposed from About.
text = replace_once(
    text,
    "internal enum class LibraryInfoDialog {\n    About,\n    More,\n    Help,\n    Licenses,\n}",
    "internal enum class LibraryInfoDialog {\n    About,\n    Help,\n    Licenses,\n}",
    "LibraryInfoDialog enum",
)
text = replace_once(
    text,
    "        LibraryInfoDialog.More -> stringResource(R.string.more)\n",
    "",
    "More title branch",
)
text = replace_once(
    text,
    "        LibraryInfoDialog.About, LibraryInfoDialog.More -> R.drawable.ic_info\n",
    "        LibraryInfoDialog.About -> R.drawable.ic_info\n",
    "More icon branch",
)
text = replace_once(
    text,
    "        LibraryInfoDialog.More -> AnnotatedString(stringResource(R.string.about_message))\n",
    "",
    "More message branch",
)
text = replace_once(
    text,
    "                LibraryInfoDialog.About -> LibraryAboutBody(\n                    onLicenses = { onOpen(LibraryInfoDialog.Licenses) },\n                    onMore = { onOpen(LibraryInfoDialog.More) },\n                )\n",
    "                LibraryInfoDialog.About -> LibraryAboutBody(\n                    onLicenses = { onOpen(LibraryInfoDialog.Licenses) },\n                )\n",
    "About body call",
)
text = replace_once(
    text,
    "                LibraryInfoDialog.Licenses,\n                LibraryInfoDialog.More -> Text(\n",
    "                LibraryInfoDialog.Licenses -> Text(\n",
    "More text body branch",
)
text = replace_once(
    text,
    "private fun LibraryAboutBody(\n    onLicenses: () -> Unit,\n    onMore: () -> Unit,\n) {",
    "private fun LibraryAboutBody(\n    onLicenses: () -> Unit,\n) {",
    "About body signature",
)
more_button = '''            TextButton(onClick = onMore) {\n                Icon(\n                    painter = painterResource(R.drawable.ic_more_vert),\n                    contentDescription = null,\n                    modifier = Modifier.size(18.dp),\n                )\n                Spacer(Modifier.width(6.dp))\n                Text(stringResource(R.string.more))\n            }\n'''
text = replace_once(text, more_button, "", "More About action")
LIBRARY.write_text(text, encoding="utf-8")

# Remove the no-longer-reachable More preview and its stale reference image.
test = SCREENSHOT_TEST.read_text(encoding="utf-8")
more_preview = '''@PreviewTest\n@Preview(name = "More dialog", widthDp = 360, heightDp = 640, showBackground = true)\n@Composable\nfun MoreDialogScreenshot() {\n    JLModPlusTheme(darkTheme = false) {\n        LibraryInformationDialog(\n            dialog = LibraryInfoDialog.More,\n            onDismiss = {},\n            onOpen = {},\n        )\n    }\n}\n\n'''
test = replace_once(test, more_preview, "", "More screenshot preview")
SCREENSHOT_TEST.write_text(test, encoding="utf-8")

for stale in REFERENCE_DIR.glob("MoreDialogScreenshot_*.png"):
    stale.unlink()
