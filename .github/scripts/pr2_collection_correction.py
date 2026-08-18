from pathlib import Path
import subprocess

BASE_SHA = "6eddbbce25df8ce287dcd0f073a5688b4c6b2e55"
SCRIPT_PATH = Path(".github/scripts/pr2_collection_correction.py")


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"Expected patch anchor missing in {path}: {old[:80]!r}")
    file.write_text(text.replace(old, new, 1))


def insert_before(path: str, marker: str, addition: str) -> None:
    file = Path(path)
    text = file.read_text()
    if addition.strip() in text:
        return
    index = text.find(marker)
    if index < 0:
        raise SystemExit(f"Insertion marker missing in {path}: {marker!r}")
    file.write_text(text[:index] + addition + text[index:])


# Repository/ViewModel: expose the full in-memory READY app snapshot without a DB/UI-thread read.
insert_before(
    "app/src/main/java/ru/playsoftware/j2meloader/librarydb/LibraryRepository.kt",
    "    fun currentCollection(expected: LibraryGenerationToken, collectionId: Long): LibraryCollectionRow? {",
    "    fun currentApps(expected: LibraryGenerationToken): List<LibraryAppRow> =\n"
    "        requireReadyGeneration(expected).apps\n\n",
)
insert_before(
    "app/src/main/java/ru/playsoftware/j2meloader/librarydb/LibraryViewModel.kt",
    "    fun storageKeys(expectedGeneration: Long, expectedWorkdir: File): Set<String> =",
    "    fun getAllApps(): List<LibraryAppRow> {\n"
    "        val generation = readyGeneration() ?: return emptyList()\n"
    "        return try {\n"
    "            repository.currentApps(generation)\n"
    "        } catch (_: IllegalStateException) {\n"
    "            emptyList()\n"
    "        }\n"
    "    }\n\n",
)

# Fragment: publish all apps to the Collection picker and refresh active membership after either toggle.
replace_once(
    "app/src/main/java/ru/playsoftware/j2meloader/applist/AppsListFragment.java",
    "\t\tcollectionsUiStore.publishCollections(state.getCollections());\n"
    "\t\tList<LibraryAppUiItem> uiItems = new ArrayList<>(state.getApps().size());",
    "\t\tcollectionsUiStore.publishCollections(state.getCollections());\n"
    "\t\tList<LibraryAppRow> allRows = libraryViewModel.getAllApps();\n"
    "\t\tList<LibraryAppUiItem> allUiItems = new ArrayList<>(allRows.size());\n"
    "\t\tfor (LibraryAppRow row : allRows) {\n"
    "\t\t\tallUiItems.add(toLibraryUiItem(row));\n"
    "\t\t}\n"
    "\t\tcollectionsUiStore.publishAllApps(allUiItems);\n"
    "\t\tList<LibraryAppUiItem> uiItems = new ArrayList<>(state.getApps().size());",
)

fragment = Path("app/src/main/java/ru/playsoftware/j2meloader/applist/AppsListFragment.java")
text = fragment.read_text()
start = text.index("\t\t\tpublic void onAddAppToCollection(int appId, long collectionId) {")
end = text.index("\n\t\t\t@Override\n\t\t\tpublic void onRemoveAppFromCollection", start)
section = text[start:end]
section = section.replace(
    "\t\t\t\t\t\t(ignored, error) -> {\n"
    "\t\t\t\t\t\t\tif (error != null) showError(error);\n"
    "\t\t\t\t\t\t});",
    "\t\t\t\t\t\t(ignored, error) -> {\n"
    "\t\t\t\t\t\t\tif (error != null) {\n"
    "\t\t\t\t\t\t\t\tshowError(error);\n"
    "\t\t\t\t\t\t\t\treturn;\n"
    "\t\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\t\tloadCollectionMembers(collectionId);\n"
    "\t\t\t\t\t\t});",
    1,
)
text = text[:start] + section + text[end:]
fragment.write_text(text)

# Collection state and destination: collection pages use collection-specific controls, never Favorite/quick filters.
collections = Path("app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryCollectionsUi.kt")
text = collections.read_text()
text = text.replace("import androidx.compose.runtime.produceState\n", "")
text = text.replace("import java.text.Collator\n", "")
text = text.replace("import java.util.Locale\n", "")
text = text.replace("import kotlinx.coroutines.Dispatchers\n", "")
text = text.replace("import kotlinx.coroutines.withContext\n", "")
text = text.replace("import ru.playsoftware.j2meloader.librarydb.LibraryQuickView\n", "")
text = text.replace(
    "data class LibraryCollectionsUiState(\n"
    "    val ready: Boolean = false,\n"
    "    val collections: List<LibraryCollectionUiItem> = emptyList(),\n"
    "    val members: LibraryCollectionMembersUi? = null,\n"
    "    val addTarget: LibraryCollectionAppTargetUi? = null,\n"
    ")",
    "data class LibraryCollectionsUiState(\n"
    "    val ready: Boolean = false,\n"
    "    val collections: List<LibraryCollectionUiItem> = emptyList(),\n"
    "    val allApps: List<LibraryAppUiItem> = emptyList(),\n"
    "    val members: LibraryCollectionMembersUi? = null,\n"
    "    val addTarget: LibraryCollectionAppTargetUi? = null,\n"
    ")",
    1,
)
marker = "    fun clear() {\n"
addition = (
    "    fun publishAllApps(items: List<LibraryAppUiItem>) {\n"
    "        mutableState.value = mutableState.value.copy(allApps = items)\n"
    "    }\n\n"
)
if addition.strip() not in text:
    index = text.index(marker)
    text = text[:index] + addition + text[index:]

start = text.index("    if (members != null && openCollection != null) {")
end = text.index("\n\n    var createDialog", start)
replacement = """    if (members != null && openCollection != null) {
        LibraryCollectionBrowser(
            collection = openCollection,
            members = members.members,
            allApps = state.allApps,
            libraryState = libraryState,
            scaffoldPadding = scaffoldPadding,
            onBack = host::onDismissCollectionMembers,
            onOpenApp = host::onOpenApp,
            onOpenActions = { app -> onOpenActions(app, openCollection.id) },
            onRemove = { appId -> host.onRemoveAppFromCollection(appId, openCollection.id) },
            onSetMembership = { appId, included ->
                if (included) {
                    host.onAddAppToCollection(appId, openCollection.id)
                } else {
                    host.onRemoveAppFromCollection(appId, openCollection.id)
                }
            },
            onSort = host::onSort,
        )
        return
    }"""
text = text[:start] + replacement + text[end:]

old_start = text.find("private fun projectCollectionMembers(")
if old_start >= 0:
    old_end = text.index("@Composable\nprivate fun AddToCollectionDialog", old_start)
    text = text[:old_start] + text[old_end:]
collections.write_text(text)

# Screenshot coverage: render the real collection-specific browser rather than the main app browser.
screens = Path("app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/applist/LibraryProfilesScreenshotTest.kt")
text = screens.read_text()
start = text.index("fun LibraryCollectionBrowserScreenshot() {")
end = text.index("\n}\n\n@PreviewTest\n@Preview(name = \"Library metadata editor\"", start) + 2
replacement = """fun LibraryCollectionBrowserScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryCollectionBrowser(
            collection = LibraryCollectionUiItem(1L, \"RPG Favorites\", 3),
            members = PreviewApps.take(3),
            allApps = PreviewApps,
            libraryState = LibraryUiState(
                loading = false,
                apps = PreviewApps,
                layout = LibraryLayout.List,
                databaseControlsReady = true,
            ),
            scaffoldPadding = PaddingValues(),
            onBack = {},
            onOpenApp = {},
            onOpenActions = {},
            onRemove = {},
            onSetMembership = { _, _ -> },
            onSort = {},
        )
    }
}"""
text = text[:start] + replacement + text[end:]
screens.write_text(text)

# Restore the normal workflow and remove this one-shot executor before publishing source changes.
subprocess.run(["git", "checkout", BASE_SHA, "--", ".github/workflows/android.yml"], check=True)
SCRIPT_PATH.unlink()
subprocess.run(["git", "config", "user.name", "github-actions[bot]"], check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], check=True)
subprocess.run(["git", "add", "-A"], check=True)
subprocess.run(["git", "diff", "--cached", "--check"], check=True)
subprocess.run(["git", "commit", "-m", "Refine Collection membership UX"], check=True)
subprocess.run(["git", "push", "origin", "HEAD:agent/library-pr2-features"], check=True)
