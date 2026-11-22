/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryCollectionRow
import io.github.h3nb.jlmodplus.librarydb.LibraryQuickView
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verify rendered Library viewport, search, selection and collection behavior. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LibraryViewportNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun collectionViewportSurvivesHostIdReassignmentAndPrepend() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }

        openCollectionManagement()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(rowMatcher(MEMBER_PREFIX)).fetchSemanticsNodes().isNotEmpty()
        }
        scrollAwayFromTop(MEMBER_PREFIX)
        val before = visibleRow(MEMBER_PREFIX)

        composeRule.runOnIdle { host.reassignMemberHostIds() }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(rowMatcher(MEMBER_PREFIX)).fetchSemanticsNodes().isNotEmpty()
        }
        assertViewport(before, MEMBER_PREFIX)
    }

    @Test
    fun contextualSettingsDoesNotReplaceLibraryWithCollections() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }

        composeRule.onNodeWithContentDescription(uiString(R.string.action_settings)).performClick()
        assertEquals(1, host.settingsRequests)
        composeRule.onAllNodesWithText(COLLECTION_NAME).assertCountEquals(0)
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun lastMidletRemainsReachableWithoutNavigationFooter() {
        val host = ViewportHost()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }

        activeList(APP_PREFIX).performScrollToIndex(80)
        composeRule.waitForIdle()
        val last = composeRule.onNodeWithText(rowTitle(APP_PREFIX, 79), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = activeList(APP_PREFIX).fetchSemanticsNode().boundsInRoot
        assertTrue("The last MIDlet is clipped by the lower chrome", last.bottom <= viewport.bottom)
        val visible = visibleRow(APP_PREFIX)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertViewport(visible, APP_PREFIX)
    }

    @Test
    fun deepListScrollRespondsToReverseDrags() =
        verifyDeepScrollRespondsToReverseDrags(LibraryLayout.List)

    @Test
    fun deepGridScrollRespondsToReverseDrags() =
        verifyDeepScrollRespondsToReverseDrags(LibraryLayout.Grid)

    private fun verifyDeepScrollRespondsToReverseDrags(layout: LibraryLayout) {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(layout), actions = host) }
            }
        }
        activeList(APP_PREFIX).performScrollToIndex(35)
        composeRule.waitForIdle()
        val before = visibleRow(APP_PREFIX)

        activeList(APP_PREFIX).performTouchInput {
            val start = Offset(width * 0.5f, height * 0.38f)
            down(start)
            moveSmoothlyTo(Offset(start.x, start.y + height * 0.16f), durationMillis = 750)
            up()
        }
        composeRule.waitForIdle()
        val after = visibleRow(APP_PREFIX)
        assertTrue("Reverse drag did not move MIDlet rows", before != after)

        activeList(APP_PREFIX).performTouchInput {
            val start = Offset(width * 0.5f, height * 0.65f)
            down(start)
            moveSmoothlyTo(Offset(start.x, start.y - height * 0.16f), durationMillis = 750)
            up()
        }
        composeRule.waitForIdle()
        assertTrue("Forward drag did not move MIDlet rows", after != visibleRow(APP_PREFIX))
    }

    @Test
    fun scopePickerSwitchesQuickViewsInList() =
        verifyScopePickerSwitchesQuickViews(LibraryLayout.List)

    @Test
    fun scopePickerSwitchesQuickViewsInGrid() =
        verifyScopePickerSwitchesQuickViews(LibraryLayout.Grid)

    private fun verifyScopePickerSwitchesQuickViews(layout: LibraryLayout) {
        val initial = libraryState(layout)
        val visibleState = mutableStateOf(initial)
        val host = ViewportHost().apply {
            onQuickViewChanged = { view ->
                visibleState.value = visibleState.value.copy(
                    quickView = view,
                    apps = if (view == LibraryQuickView.Favorites) initial.apps.takeLast(1)
                        else initial.apps,
                )
            }
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = visibleState.value, actions = host) }
            }
        }

        composeRule.onNodeWithTag("library_scope_picker").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(uiString(R.string.library_filter_favorites)).performClick()
        composeRule.waitForIdle()
        assertEquals(LibraryQuickView.Favorites, visibleState.value.quickView)
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 79), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        composeRule.onNodeWithTag("library_scope_picker").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(uiString(R.string.library_destination_apps)).performClick()
        composeRule.waitForIdle()
        assertEquals(LibraryQuickView.All, visibleState.value.quickView)
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun expandedSearchBackDismissesInputBeforeLeaving() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        composeRule.onNodeWithContentDescription(uiString(R.string.library_search_placeholder))
            .performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Library")
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()

        pressBack()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        composeRule.onNode(hasSetTextAction()).assertTextEquals("Library")
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true)
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(uiString(R.string.action_back)).performClick()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun horizontalGestureDoesNotNavigateAwayFromSelectedMidlet() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        val title = rowTitle(APP_PREFIX, 0)
        composeRule.onNodeWithText(title).performTouchInput { longClick() }
        composeRule.onNodeWithText(uiString(R.string.library_action_select)).performClick()
        val checkbox = uiString(R.string.library_selection_checkbox_description, title)
        composeRule.onNodeWithContentDescription(checkbox).assertIsDisplayed().assertIsOn()

        composeRule.onRoot().performTouchInput { swipeLeft(durationMillis = 500) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(checkbox).assertIsDisplayed().assertIsOn()
        composeRule.onAllNodesWithText(COLLECTION_NAME).assertCountEquals(0)
    }

    @Test
    fun searchRestoresWithinItsLibraryAndClosesOnScopeChange() {
        val host = ViewportHost()
        val state = mutableStateOf(libraryState(LibraryLayout.List))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = state.value, actions = host) }
            }
        }

        composeRule.onNodeWithContentDescription(uiString(R.string.library_search_placeholder)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Library")
        composeRule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertTextEquals("Library")

        composeRule.runOnIdle { state.value = state.value.copy(libraryScope = "/test/another-library") }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(uiString(R.string.library_search_placeholder))
            .assertIsDisplayed()
    }

    @Test
    fun viewportRestoresAcrossRecreationAndResetsForDifferentLibrary() {
        val host = ViewportHost()
        val state = mutableStateOf(libraryState(LibraryLayout.List))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = state.value, actions = host) }
            }
        }
        scrollAwayFromTop(APP_PREFIX)
        val apps = visibleRow(APP_PREFIX)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertViewport(apps, APP_PREFIX)

        // Database-local ids are deliberately reused; a different workdir needs a fresh viewport.
        composeRule.runOnIdle { state.value = state.value.copy(libraryScope = "/test/library-b") }
        composeRule.waitForIdle()
        assertEquals(rowTitle(APP_PREFIX, 0), visibleRow(APP_PREFIX).title)
        composeRule.onNodeWithContentDescription(uiString(R.string.app_name)).assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    private fun openCollectionManagement() {
        composeRule.onNodeWithTag("library_scope_picker").performClick()
        composeRule.onNodeWithText(uiString(R.string.library_collection_manage)).performClick()
    }

    private fun scrollAwayFromTop(prefix: String) {
        activeList(prefix).performScrollToNode(hasText(rowTitle(prefix, 25)))
        composeRule.waitForIdle()
        activeList(prefix).performTouchInput {
            down(Offset(width * 0.5f, height * 0.55f))
            moveSmoothlyTo(Offset(width * 0.5f, height * 0.15f), durationMillis = 250)
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        assertTrue(visibleRow(prefix).title != rowTitle(prefix, 0))
    }

    private fun TouchInjectionScope.moveSmoothlyTo(end: Offset, durationMillis: Long) {
        val start = checkNotNull(currentPosition())
        // Multiple timed MOVE events cross touch slop and produce a continuous drag.
        for (step in 1..25) {
            moveTo(start + (end - start) * (step / 25f), delayMillis = durationMillis / 25)
        }
    }

    private fun rowMatcher(prefix: String) = SemanticsMatcher("row title starts with $prefix") { node ->
        SemanticsProperties.Text in node.config &&
            node.config[SemanticsProperties.Text].any { it.text.startsWith(prefix) }
    }

    private fun activeList(prefix: String): SemanticsNodeInteraction =
        // Pager and Lazy content can both expose ScrollBy; the innermost matching node is last.
        composeRule.onAllNodes(
            hasScrollAction() and hasAnyDescendant(rowMatcher(prefix)),
            useUnmergedTree = true,
        ).onLast()

    private data class VisibleRow(val title: String, val top: Float)

    private fun visibleRow(prefix: String): VisibleRow {
        composeRule.waitForIdle()
        val viewport = activeList(prefix).fetchSemanticsNode().boundsInRoot
        val candidates = composeRule.onAllNodes(rowMatcher(prefix), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val nodes = candidates.filter { node ->
            val bounds = node.boundsInRoot
            // Clipped nodes touch a viewport edge; only interior rendered title bounds qualify.
            bounds.width > 0 && bounds.height > 0 &&
                bounds.top > viewport.top && bounds.bottom < viewport.bottom &&
                composeRule.onNode(
                    SemanticsMatcher("placed row ${node.id}") { it.id == node.id },
                    useUnmergedTree = true,
                ).isDisplayed()
        }
        val first = checkNotNull(nodes.minWithOrNull(
            compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }),
        )) {
            "No fully visible $prefix row in active viewport $viewport"
        }
        val title = first.config[SemanticsProperties.Text].first { it.text.startsWith(prefix) }.text
        val visible = VisibleRow(title, first.boundsInRoot.top)
        return visible
    }

    private fun assertViewport(expected: VisibleRow, prefix: String) {
        val actual = visibleRow(prefix)
        assertEquals("First visible row changed after returning to $prefix", expected.title, actual.title)
        assertEquals("Displayed row moved after returning to $prefix", expected.top, actual.top, 1f)
    }

    private class ViewportHost : LibraryCollectionsHost {
        private val members = rows(MEMBER_PREFIX, 1)
        var onQuickViewChanged: (LibraryQuickView) -> Unit = {}
        override fun onQuickView(quickView: LibraryQuickView) = onQuickViewChanged(quickView)
        private val store = LibraryCollectionsUiStore().apply {
            publishCollections(List(80) { index ->
                LibraryCollectionRow(
                    id = COLLECTION_ID + index,
                    name = rowTitle(FOLDER_PREFIX, index),
                    sortOrder = index,
                    createdAt = 1L,
                    appCount = members.size,
                )
            })
        }
        override fun collectionsStore() = store
        override fun onOpenCollection(collectionId: Long) = store.showMembers(collectionId, members)
        fun reassignMemberHostIds() {
            val inserted = LibraryAppUiItem(
                id = 9_999,
                title = "Collection app 0",
                author = "Vendor",
                version = "1.0",
                iconPath = null,
                canReinstall = true,
                databaseId = 9_999L,
            )
            // The new first row shifts every retained item by one index. Correct restoration
            // needs the stable database key, not the old list index or transient host ID.
            store.showMembers(
                COLLECTION_ID,
                listOf(inserted) + members.mapIndexed { index, app ->
                    app.copy(id = 1_001 + index)
                },
            )
        }
        override fun onDismissCollectionMembers() = store.dismissMembers()
        override fun onPrepareCollectionAppPicker() = store.publishAllApps(members)
        override fun onPrepareSearchApps() = store.publishAllApps(rows(APP_PREFIX, 1))
        override fun onCreateCollection(name: String) = Unit
        override fun onRenameCollection(collectionId: Long, name: String) = Unit
        override fun onDeleteCollection(collectionId: Long) = Unit
        override fun onRequestAddToCollection(appId: Int) = Unit
        override fun onDismissAddToCollection() = Unit
        override fun onAddAppToCollection(appId: Int, collectionId: Long) = Unit
        override fun onSetCollectionMembership(
            appId: Int,
            collectionId: Long,
            included: Boolean,
            callback: CollectionMembershipResultCallback,
        ) = callback.onResult(true)
        override fun onSetCollectionMembershipByDatabaseId(
            databaseId: Long,
            collectionId: Long,
            included: Boolean,
            callback: CollectionMembershipResultCallback,
        ) = callback.onResult(true)
        override fun onAddAppsToCollection(appIds: Set<Long>, collectionId: Long) = Unit
        override fun onRemoveAppsFromCollection(appIds: Set<Long>, collectionId: Long) = Unit
        override fun onRemoveAppFromCollection(appId: Int, collectionId: Long) = Unit
        val searches = mutableListOf<String>()
        override fun onSearch(query: String) { searches += query }
        override fun onLayoutChange(layout: LibraryLayout) = Unit
        override fun onSort(sortIndex: Int) = Unit
        override fun onInstall() = Unit
        override fun onOpenApp(appId: Int) = Unit
        override fun onAddShortcut(appId: Int) = Unit
        override fun onRename(appId: Int, title: String) = Unit
        override fun onOpenAppSettings(appId: Int) = Unit
        override fun onReinstall(appId: Int) = Unit
        override fun onDelete(appId: Int) = Unit
        var settingsRequests = 0
        override fun onOpenSettings() { settingsRequests++ }
        override fun onOpenProfiles() = Unit
        override fun onOpenCrashReports() = Unit
        override fun onRetryLibrary() = Unit
    }

    private companion object {
        const val APP_PREFIX = "Library app "
        const val MEMBER_PREFIX = "Collection app "
        const val COLLECTION_NAME = "Viewport collection"
        const val FOLDER_PREFIX = COLLECTION_NAME
        const val COLLECTION_ID = 7L

        fun rowTitle(prefix: String, index: Int): String = when {
            prefix == FOLDER_PREFIX && index == 0 -> COLLECTION_NAME
            prefix == FOLDER_PREFIX -> "$prefix ${index.toString().padStart(2, '0')}"
            else -> prefix + index.toString().padStart(2, '0')
        }
        fun rows(prefix: String, startId: Int) = List(80) { index ->
            LibraryAppUiItem(startId + index, rowTitle(prefix, index), "Vendor", "1.0", null, true)
        }
        fun libraryState(layout: LibraryLayout) = LibraryUiState(
            loading = false,
            databaseControlsReady = true,
            layout = layout,
            apps = rows(APP_PREFIX, 1),
            generation = 1L,
            libraryScope = "/test/library-a",
        )
        fun uiString(id: Int, vararg args: Any) =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    }
}
