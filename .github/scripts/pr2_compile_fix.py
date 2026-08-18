from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one anchor, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))

COLLECTIONS = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryCollectionsUi.kt"
BROWSER = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryCollectionBrowser.kt"
BRIDGE = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt"
INSTALLER = "app/src/main/java/ru/woesss/j2me/installer/InstallerDialog.java"
IMPORTER = "app/src/main/java/ru/playsoftware/j2meloader/librarydb/LibraryAppBundleImporter.kt"

replace_once(
    COLLECTIONS,
    "import androidx.compose.runtime.Composable\n",
    "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\n",
)
replace_once(
    COLLECTIONS,
    "    onOpenActions: (LibraryAppUiItem, Long) -> Unit,\n    onNavigationVisibilityChanged: (Boolean) -> Unit,\n) {",
    "    onOpenActions: (LibraryAppUiItem, Long) -> Unit,\n    onNavigationVisibilityChanged: (Boolean) -> Unit = {},\n) {",
)
replace_once(
    BROWSER,
    "    onSort: (Int) -> Unit,\n    onNavigationVisibilityChanged: (Boolean) -> Unit,\n) {",
    "    onSort: (Int) -> Unit,\n    onNavigationVisibilityChanged: (Boolean) -> Unit = {},\n) {",
)
replace_once(
    BRIDGE,
    "    onGridSpacingChange: (LibraryGridSpacing) -> Unit,\n    onImportAppBundle: () -> Unit,\n    onAbout: () -> Unit,\n",
    "    onGridSpacingChange: (LibraryGridSpacing) -> Unit,\n    onImportAppBundle: () -> Unit = {},\n    onAbout: () -> Unit,\n",
)

replace_once(
    INSTALLER,
    " * Copyright 2020-2026 Yury Kharchenko\n *\n * Licensed under the Apache License",
    " * Copyright 2020-2026 Yury Kharchenko\n *\n * Modified by JL-Mod Plus contributors; original upstream attribution is retained.\n *\n * Licensed under the Apache License",
)
replace_once(
    INSTALLER,
    "\t\t\tcase AppInstaller.STATUS_EQUAL -> {\n\t\t\t\tmessage = getString(R.string.reinstall);\n\t\t\t\trunLabel = getString(R.string.START_CMD);\n\t\t\t}\n",
    "\t\t\tcase AppInstaller.STATUS_EQUAL -> {\n"
    "\t\t\t\tmessage = getString(R.string.reinstall);\n"
    "\t\t\t\trunLabel = isBundleRequest() ? null : getString(R.string.START_CMD);\n"
    "\t\t\t}\n",
)
replace_once(
    INSTALLER,
    "\t\tif (cleanUp) {\n\t\t\tinstaller.clearCache();\n\t\t\tinstaller.deleteTemp();\n\t\t}\n\t\tacknowledgeExternalRequest();\n",
    "\t\tif (cleanUp) {\n"
    "\t\t\tinstaller.clearCache();\n"
    "\t\t\tinstaller.deleteTemp();\n"
    "\t\t}\n"
    "\t\tcleanupBundleImport();\n"
    "\t\tacknowledgeExternalRequest();\n",
)

replace_once(
    IMPORTER,
    "        publishReplacements(replacements)\n        val iconRevision = if (prepared.configDir != null) {\n            LibraryIconOverride.reapplyPersistedOverride(emulatorDir, storageKey)\n        } else {\n            null\n        }\n        return RestoreResult(iconRevision)\n",
    "        publishReplacements(replacements)\n"
    "        return try {\n"
    "            val iconRevision = if (prepared.configDir != null) {\n"
    "                LibraryIconOverride.reapplyPersistedOverride(emulatorDir, storageKey)\n"
    "            } else {\n"
    "                null\n"
    "            }\n"
    "            discardReplacements(replacements)\n"
    "            RestoreResult(iconRevision)\n"
    "        } catch (error: Throwable) {\n"
    "            replacements.asReversed().forEach(::rollbackReplacement)\n"
    "            throw error\n"
    "        }\n",
)
replace_once(
    IMPORTER,
    "        replacements.forEach { replacement -> replacement.backup?.deleteRecursively() }\n    }\n\n    private fun rollbackReplacement(replacement: Replacement) {",
    "    }\n\n"
    "    private fun discardReplacements(replacements: List<Replacement>) {\n"
    "        replacements.forEach { replacement ->\n"
    "            replacement.backup?.deleteRecursively()\n"
    "            replacement.staged.deleteRecursively()\n"
    "        }\n"
    "    }\n\n"
    "    private fun rollbackReplacement(replacement: Replacement) {",
)

print("PR2 compile/hardening fix applied")
