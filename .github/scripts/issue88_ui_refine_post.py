from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected text not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, count), encoding="utf-8")


micro = "app/src/main/java/javax/microedition/shell/MicroActivity.java"
replace(micro, "\t\tToast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();\n", "\t\ttoast(toastMessage);\n")
replace(micro, "\t\tToast.makeText(this, R.string.layout_edit_finished, Toast.LENGTH_SHORT).show();\n", "\t\ttoast(R.string.layout_edit_finished);\n")
replace(
    micro,
    "\t\t\t\tToast.makeText(MicroActivity.this, getString(R.string.screenshot_saved)\n"
    "\t\t\t\t\t\t+ \" \" + s, Toast.LENGTH_LONG).show();\n",
    "\t\t\t\ttoast(getString(R.string.screenshot_saved) + \" \" + s);\n",
)
replace(micro, "\t\t\t\tToast.makeText(MicroActivity.this, R.string.error, Toast.LENGTH_SHORT).show();\n", "\t\t\t\ttoast(R.string.error);\n")
replace(micro, "\t\t\tToast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show();\n", "\t\t\ttoast(R.string.log_saved);\n")
replace(micro, "\t\t\tToast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();\n", "\t\t\ttoast(R.string.error);\n")
replace(
    micro,
    "\tpublic void toast(@StringRes int message) {\n"
    "\t\trunOnUiThread(() -> {\n"
    "\t\t\tif (runtimeNoticeController != null) {\n"
    "\t\t\t\truntimeNoticeController.show(getString(message));\n"
    "\t\t\t}\n"
    "\t\t});\n"
    "\t}\n",
    "\tpublic void toast(@StringRes int message) {\n"
    "\t\ttoast(getString(message));\n"
    "\t}\n\n"
    "\tprivate void toast(String message) {\n"
    "\t\trunOnUiThread(() -> {\n"
    "\t\t\tif (runtimeNoticeController != null) {\n"
    "\t\t\t\truntimeNoticeController.show(message);\n"
    "\t\t\t}\n"
    "\t\t});\n"
    "\t}\n",
)

# The first-pass script intentionally replaces the old body GitHub button, but its broad
# insertion target can land in the batch-selection toolbar. Keep Report on GitHub exclusively
# in the crash-detail app bar where it belongs.
crash = Path("app/src/main/java/ru/playsoftware/j2meloader/crashes/CrashReportsComposeBridge.kt")
text = crash.read_text(encoding="utf-8")
github_block = """                    IconButton(onClick = actions::onReportGitHub) {
                        Icon(
                            painter = painterResource(R.drawable.ic_bug_report),
                            contentDescription = stringResource(R.string.report_on_github),
                        )
                    }
"""
if github_block not in text:
    raise RuntimeError("Expected temporary GitHub action block was not generated")
text = text.replace(github_block, "", 1)
marker = "fun CrashReportDetailsScreen("
head, detail = text.split(marker, 1)
delete_button = "                    IconButton(onClick = { confirmation = CrashReportConfirmation.Delete }) {\n"
if delete_button not in detail:
    raise RuntimeError("Crash detail delete action not found")
detail = detail.replace(delete_button, github_block + delete_button, 1)
crash.write_text(head + marker + detail, encoding="utf-8")

# RowScope supplies Modifier.weight; an explicit import is not needed and may resolve to the
# restricted layout extension on some Compose versions.
installer = Path("app/src/main/java/ru/woesss/j2me/installer/InstallerComposeBridge.kt")
installer_text = installer.read_text(encoding="utf-8").replace(
    "import androidx.compose.foundation.layout.weight\n",
    "",
)
installer.write_text(installer_text, encoding="utf-8")

# The transient notice on the Library is above the app navigation rather than underneath it.
library = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt"
replace(
    library,
    "                    TransientNoticeHost(\n"
    "                        state = noticeState,\n"
    "                        modifier = Modifier.align(Alignment.BottomCenter),\n"
    "                    )\n",
    "                    val noticeBottomPadding = if (\n"
    "                        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE\n"
    "                    ) 8.dp else 88.dp\n"
    "                    TransientNoticeHost(\n"
    "                        state = noticeState,\n"
    "                        modifier = Modifier\n"
    "                            .align(Alignment.BottomCenter)\n"
    "                            .padding(bottom = noticeBottomPadding),\n"
    "                    )\n",
)

# No Toast remains in the runtime or main Library notification paths covered by this pass.
for path in (
    micro,
    "app/src/main/java/ru/playsoftware/j2meloader/applist/AppsListFragment.java",
):
    text = Path(path).read_text(encoding="utf-8")
    if "Toast.makeText" in text or "android.widget.Toast" in text:
        raise RuntimeError(f"Legacy Toast remains in {path}")
