from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one anchor, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

COLLECTIONS = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryCollectionsUi.kt"
BRIDGE = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt"
SCREENSHOTS = "app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/applist/LibraryProfilesScreenshotTest.kt"
ANDROID_TEST = "app/src/androidTest/java/ru/playsoftware/j2meloader/applist/LibraryComposeTest.kt"

# Avoid mutating parent state directly during fallback composition.
replace_once(
    COLLECTIONS,
    "    if (!state.ready) {\n        onNavigationVisibilityChanged(true)\n        LibraryCollectionsDestination(scaffoldPadding)\n        return\n    }\n",
    "    if (!state.ready) {\n        LibraryCollectionsDestination(scaffoldPadding)\n        return\n    }\n",
)

# Every destination starts with visible chrome; the active scroll container may hide it again.
replace_once(
    BRIDGE,
    "    LaunchedEffect(destination) {\n        if (destination != LibraryDestination.Apps) {\n            showInstallFab = true\n            showNavigationBar = true\n            appActions = null\n            appActionsCollectionId = null\n        }\n    }\n",
    "    LaunchedEffect(destination) {\n"
    "        showInstallFab = true\n"
    "        showNavigationBar = true\n"
    "        if (destination != LibraryDestination.Apps) {\n"
    "            appActions = null\n"
    "            appActionsCollectionId = null\n"
    "        }\n"
    "    }\n",
)

# Visually separate App / Collection / Transfer / Maintenance actions.
replace_once(
    BRIDGE,
    "                if (onAddToCollection != null) {\n",
    "                if (onAddToCollection != null || onRemoveFromCollection != null) {\n"
    "                    item { DialogActionDivider() }\n"
    "                }\n"
    "                if (onAddToCollection != null) {\n",
)
replace_once(
    BRIDGE,
    "                if (app.canReinstall) {\n                    item { DialogActionDivider() }\n                    item {\n",
    "                item { DialogActionDivider() }\n"
    "                if (app.canReinstall) {\n"
    "                    item {\n",
)

# Screenshot the complete action menu rather than only the legacy subset.
replace_once(
    SCREENSHOTS,
    "            onReinstall = {},\n            onDelete = {},\n        )\n",
    "            onReinstall = {},\n"
    "            onDelete = {},\n"
    "            onEditMetadata = {},\n"
    "            onAddToCollection = {},\n"
    "            onShareApp = {},\n"
    "            onExportAppBundle = {},\n"
    "        )\n",
)

# More/Less must not steal the parent app-open action.
replace_once(
    ANDROID_TEST,
    "        composeRule.onNodeWithContentDescription(\"Expand description\").performClick()\n        composeRule.onNodeWithContentDescription(\"Collapse description\").assertIsDisplayed()\n",
    "        composeRule.onNodeWithContentDescription(\"Expand description\").performClick()\n"
    "        assertEquals(null, actions.openedId)\n"
    "        composeRule.onNodeWithContentDescription(\"Collapse description\").assertIsDisplayed()\n",
)

# Global Import entry is a real Library action, not decorative Options UI.
replace_once(
    ANDROID_TEST,
    "    @Test\n    fun gridOptionsExposeTitleVisibilitySpacingAndIconRatio() {\n",
    "    @Test\n"
    "    fun optionsExposeImportAppBundleCallback() {\n"
    "        val actions = RecordingLibraryActions()\n"
    "        setLibraryContent(actions = actions)\n\n"
    "        composeRule.onNodeWithText(\"Options\").performClick()\n"
    "        composeRule.onNodeWithText(\"Import App Bundle\").performClick()\n\n"
    "        assertEquals(1, actions.importCount)\n"
    "    }\n\n"
    "    @Test\n"
    "    fun gridOptionsExposeTitleVisibilitySpacingAndIconRatio() {\n",
)
replace_once(
    ANDROID_TEST,
    "        var installCount = 0\n        var openedId: Int? = null\n",
    "        var installCount = 0\n        var importCount = 0\n        var openedId: Int? = null\n",
)
replace_once(
    ANDROID_TEST,
    "        override fun onInstall() { installCount++ }\n        override fun onOpenApp(appId: Int) { openedId = appId }\n",
    "        override fun onInstall() { installCount++ }\n"
    "        override fun onImportAppBundle() { importCount++ }\n"
    "        override fun onOpenApp(appId: Int) { openedId = appId }\n",
)

print("Final Library visual polish applied")
