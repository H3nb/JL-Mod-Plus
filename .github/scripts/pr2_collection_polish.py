from pathlib import Path
import subprocess

BASE_SHA = "d751ebd65b2d5cadb3837fa6a57ee597562f4dc3"
SCRIPT_PATH = Path(".github/scripts/pr2_collection_polish.py")


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"Expected patch anchor missing in {path}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))

# Expose the exact Library icon/description renderers for Collection reuse.
replace_once(
    "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt",
    "@Composable\nprivate fun LibraryDescription(descriptionValue: String, appId: Int) {",
    "@Composable\ninternal fun LibraryDescription(descriptionValue: String, appId: Int) {",
)
replace_once(
    "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt",
    "@Composable\nprivate fun LibraryIconSlot(\n",
    "@Composable\ninternal fun LibraryIconSlot(\n",
)

# Cache the full READY projection by repository list identity. Search/sort/quick-view changes
# reuse the same Repository.State.Ready apps list, so a 5k Library is not remapped per keystroke.
fragment_path = "app/src/main/java/ru/playsoftware/j2meloader/applist/AppsListFragment.java"
replace_once(
    fragment_path,
    "\tprivate final LibraryCollectionsUiStore collectionsUiStore = new LibraryCollectionsUiStore();\n"
    "\tprivate int nextUiId = 1;",
    "\tprivate final LibraryCollectionsUiStore collectionsUiStore = new LibraryCollectionsUiStore();\n"
    "\tprivate List<LibraryAppRow> cachedAllReadyRows;\n"
    "\tprivate int nextUiId = 1;",
)
fragment = Path(fragment_path)
text = fragment.read_text()
start = text.index("\tprivate void publishReady(LibraryViewModel.DisplayState.Ready state) {")
end = text.index("\n\tprivate LibraryAppUiItem toLibraryUiItem", start)
new_method = '''\tprivate void publishReady(LibraryViewModel.DisplayState.Ready state) {
\t\tlong generation = state.getGeneration();
\t\tFile workdir = state.getEmulatorDir();
\t\tboolean generationChanged = activeGeneration != generation || activeWorkdir == null ||
\t\t\t\t!activeWorkdir.equals(workdir);
\t\tif (generationChanged) {
\t\t\tactiveGeneration = generation;
\t\t\tactiveWorkdir = workdir;
\t\t\trowsByUiId.clear();
\t\t\tuiIdsByDatabaseId.clear();
\t\t\tnextUiId = 1;
\t\t\tcachedAllReadyRows = null;
\t\t}

\t\tcollectionsUiStore.publishCollections(state.getCollections());
\t\tList<LibraryAppRow> allRows = libraryViewModel.getAllApps();
\t\tif (allRows != cachedAllReadyRows) {
\t\t\trowsByUiId.clear();
\t\t\tList<LibraryAppUiItem> allUiItems = new ArrayList<>(allRows.size());
\t\t\tfor (LibraryAppRow row : allRows) {
\t\t\t\tallUiItems.add(toLibraryUiItem(row));
\t\t\t}
\t\t\tcollectionsUiStore.publishAllApps(allUiItems);
\t\t\tcachedAllReadyRows = allRows;
\t\t}

\t\tList<LibraryAppUiItem> uiItems = new ArrayList<>(state.getApps().size());
\t\tfor (LibraryAppRow row : state.getApps()) {
\t\t\tuiItems.add(toLibraryUiItem(row));
\t\t}
\t\tLong activeCollectionId = collectionsUiStore.activeCollectionId();
\t\tif (activeCollectionId != null && allRows != cachedAllReadyRows) {
\t\t\tloadCollectionMembers(activeCollectionId);
\t\t} else if (activeCollectionId != null && generationChanged) {
\t\t\tloadCollectionMembers(activeCollectionId);
\t\t}
\t\tLibraryComposeController controller = composeController;
\t\tif (controller != null) {
\t\t\tcontroller.updateSort(state.getSortVariant());
\t\t\tcontroller.updateApps(uiItems, state.getFilter(), state.getQuickView());
\t\t}
\t}
'''
# The condition above needs the pre-update flag, not post-assignment identity. Rewrite cleanly below.
new_method = '''\tprivate void publishReady(LibraryViewModel.DisplayState.Ready state) {
\t\tlong generation = state.getGeneration();
\t\tFile workdir = state.getEmulatorDir();
\t\tboolean generationChanged = activeGeneration != generation || activeWorkdir == null ||
\t\t\t\t!activeWorkdir.equals(workdir);
\t\tif (generationChanged) {
\t\t\tactiveGeneration = generation;
\t\t\tactiveWorkdir = workdir;
\t\t\trowsByUiId.clear();
\t\t\tuiIdsByDatabaseId.clear();
\t\t\tnextUiId = 1;
\t\t\tcachedAllReadyRows = null;
\t\t}

\t\tcollectionsUiStore.publishCollections(state.getCollections());
\t\tList<LibraryAppRow> allRows = libraryViewModel.getAllApps();
\t\tboolean allAppsChanged = allRows != cachedAllReadyRows;
\t\tif (allAppsChanged) {
\t\t\trowsByUiId.clear();
\t\t\tList<LibraryAppUiItem> allUiItems = new ArrayList<>(allRows.size());
\t\t\tfor (LibraryAppRow row : allRows) {
\t\t\t\tallUiItems.add(toLibraryUiItem(row));
\t\t\t}
\t\t\tcollectionsUiStore.publishAllApps(allUiItems);
\t\t\tcachedAllReadyRows = allRows;
\t\t}

\t\tList<LibraryAppUiItem> uiItems = new ArrayList<>(state.getApps().size());
\t\tfor (LibraryAppRow row : state.getApps()) {
\t\t\tuiItems.add(toLibraryUiItem(row));
\t\t}
\t\tLong activeCollectionId = collectionsUiStore.activeCollectionId();
\t\tif (activeCollectionId != null && (generationChanged || allAppsChanged)) {
\t\t\tloadCollectionMembers(activeCollectionId);
\t\t}
\t\tLibraryComposeController controller = composeController;
\t\tif (controller != null) {
\t\t\tcontroller.updateSort(state.getSortVariant());
\t\t\tcontroller.updateApps(uiItems, state.getFilter(), state.getQuickView());
\t\t}
\t}
'''
text = text[:start] + new_method + text[end:]
text = text.replace(
    "\t\tnextUiId = 1;\n\t\tpendingIconUiId = NO_UI_ID;\n\t\tcollectionsUiStore.clear();",
    "\t\tnextUiId = 1;\n\t\tcachedAllReadyRows = null;\n\t\tpendingIconUiId = NO_UI_ID;\n\t\tcollectionsUiStore.clear();",
    1,
)
fragment.write_text(text)

# Collection browser: share the main Library icon normalization/cache and description More/Less.
browser_path = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryCollectionBrowser.kt"
browser = Path(browser_path)
text = browser.read_text()
for line in [
    "import android.graphics.Bitmap\n",
    "import android.graphics.BitmapFactory\n",
    "import androidx.compose.foundation.Image\n",
    "import androidx.compose.foundation.layout.aspectRatio\n",
    "import androidx.compose.material3.Surface\n",
    "import androidx.compose.ui.graphics.ImageBitmap\n",
    "import androidx.compose.ui.graphics.asImageBitmap\n",
    "import androidx.compose.ui.layout.ContentScale\n",
]:
    text = text.replace(line, "")
# Background is still used by More/Less? After deleting local description no longer needed.
text = text.replace("import androidx.compose.foundation.background\n", "")

text = text.replace(
    "            sortVariant = libraryState.sortVariant,\n"
    "            scaffoldPadding = scaffoldPadding,",
    "            sortVariant = libraryState.sortVariant,\n"
    "            iconRatio = libraryState.iconRatio,\n"
    "            scaffoldPadding = scaffoldPadding,",
    1,
)
text = text.replace(
    "            LibraryCollectionIcon(\n"
    "                app = app,\n"
    "                iconRatio = iconRatio,\n"
    "                modifier = Modifier.width(52.dp),\n"
    "                contentSize = 40.dp,\n"
    "            )",
    "            LibraryIconSlot(\n"
    "                app = app,\n"
    "                modifier = Modifier.width(52.dp),\n"
    "                contentSize = 40.dp,\n"
    "                iconRatio = iconRatio,\n"
    "            )",
    1,
)
text = text.replace("                LibraryCollectionDescription(app.description, app.id)",
                    "                LibraryDescription(app.description, app.id)", 1)
text = text.replace(
    "            LibraryCollectionIcon(\n"
    "                app = app,\n"
    "                iconRatio = iconRatio,\n"
    "                modifier = Modifier.fillMaxWidth(),\n"
    "                contentSize = null,\n"
    "            )",
    "            LibraryIconSlot(\n"
    "                app = app,\n"
    "                modifier = Modifier.fillMaxWidth(),\n"
    "                contentSize = null,\n"
    "                iconRatio = iconRatio,\n"
    "            )",
    1,
)
text = text.replace(
    "    sortVariant: Int,\n"
    "    scaffoldPadding: PaddingValues,",
    "    sortVariant: Int,\n"
    "    iconRatio: LibraryIconRatio,\n"
    "    scaffoldPadding: PaddingValues,",
    1,
)
text = text.replace(
    "                    LibraryCollectionIcon(\n"
    "                        app = app,\n"
    "                        iconRatio = LibraryIconRatio.Square,\n"
    "                        modifier = Modifier.width(48.dp),\n"
    "                        contentSize = 40.dp,\n"
    "                    )",
    "                    LibraryIconSlot(\n"
    "                        app = app,\n"
    "                        modifier = Modifier.width(48.dp),\n"
    "                        contentSize = 40.dp,\n"
    "                        iconRatio = iconRatio,\n"
    "                    )",
    1,
)
# Remove the duplicate local icon decoder and duplicate description composable.
start = text.index("@Composable\nprivate fun LibraryCollectionIcon(")
end = text.index("private fun projectCollectionApps(", start)
text = text[:start] + text[end:]
# Remove imports that became unused after deleting local description.
for line in [
    "import androidx.compose.ui.semantics.Role\n",
    "import androidx.compose.ui.semantics.contentDescription\n",
    "import androidx.compose.ui.semantics.semantics\n",
]:
    text = text.replace(line, "")
browser.write_text(text)

# Restore normal workflow and delete this one-shot script before committing source.
subprocess.run(["git", "checkout", BASE_SHA, "--", ".github/workflows/android.yml"], check=True)
SCRIPT_PATH.unlink()
subprocess.run(["git", "config", "user.name", "github-actions[bot]"], check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], check=True)
subprocess.run(["git", "add", "-A"], check=True)
subprocess.run(["git", "diff", "--cached", "--check"], check=True)
subprocess.run(["git", "commit", "-m", "Polish Collection app browser"], check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/library-pr2-features"], check=True)
