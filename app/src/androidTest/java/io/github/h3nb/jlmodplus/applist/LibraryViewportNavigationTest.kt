/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryCollectionRow
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Observe the real pager's displayed row bounds; no private navigation or viewport test hooks. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LibraryViewportNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listViewportsSurvivePagerRoundTrip() = verifyPagerRoundTrip(LibraryLayout.List)

    @Test
    fun gridViewportsSurvivePagerRoundTrip() = verifyPagerRoundTrip(LibraryLayout.Grid)

    private fun verifyPagerRoundTrip(layout: LibraryLayout) {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(layout), actions = host) }
            }
        }

        scrollAwayFromTop(APP_PREFIX)
        val apps = visibleRow(APP_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(rowMatcher(MEMBER_PREFIX)).fetchSemanticsNodes().isNotEmpty()
        }
        scrollAwayFromTop(MEMBER_PREFIX)
        val members = visibleRow(MEMBER_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(uiString(R.string.action_settings)).assertIsDisplayed()

        swipeToPreviousPage()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_destination_apps)).assertIsDisplayed()
        assertViewport(members, MEMBER_PREFIX)
        swipeToPreviousPage()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_destination_apps)).assertIsDisplayed()
        assertViewport(apps, APP_PREFIX)
    }

    @Test
    fun collectionOverviewViewportSurvivesPagerRoundTrip() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        swipeToNextPage()
        scrollAwayFromTop(FOLDER_PREFIX)
        val folders = visibleRow(FOLDER_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(uiString(R.string.action_settings)).assertIsDisplayed()
        swipeToPreviousPage()
        assertViewport(folders, FOLDER_PREFIX)
    }

    @Test
    fun endOfLibraryViewportSurvivesDifferentFooterPagerRoundTrip() {
        val host = ViewportHost()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        activeList(APP_PREFIX).performScrollToNode(hasText(rowTitle(APP_PREFIX, 70)))
        composeRule.waitForIdle()
        activeList(APP_PREFIX).performTouchInput {
            down(Offset(width * 0.5f, height * 0.85f))
            moveSmoothlyTo(Offset(width * 0.5f, height * 0.15f), durationMillis = 600)
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        val appsNavigation = uiString(R.string.library_destination_apps)
        composeRule.onAllNodesWithContentDescription(appsNavigation).assertCountEquals(0)
        // Hiding the footer expands this viewport and legitimately changes its end clamp.
        // Establish the final end position before capturing the pager-return contract.
        activeList(APP_PREFIX).performScrollToNode(hasText(rowTitle(APP_PREFIX, 79)))
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription(appsNavigation).assertCountEquals(0)
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 79), useUnmergedTree = true).assertIsDisplayed()
        swipeToNextPage()
        composeRule.onNodeWithText(COLLECTION_NAME).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(appsNavigation).assertIsDisplayed()
        // Programmatic scrolling keeps this page's footer visible, unlike the Apps gesture.
        activeList(FOLDER_PREFIX).performScrollToNode(hasText(rowTitle(FOLDER_PREFIX, 79)))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(appsNavigation).assertIsDisplayed()
        val folders = visibleRow(FOLDER_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(uiString(R.string.action_settings)).assertIsDisplayed()
        swipeToPreviousPage()
        assertViewport(folders, FOLDER_PREFIX)
        composeRule.onNodeWithContentDescription(appsNavigation).assertIsDisplayed()
        swipeToPreviousPage()

        // Returning deliberately reveals navigation. At the end, its inset can clamp the first
        // row; the final app must remain visible, with no replay of an obsolete hidden-bar anchor.
        composeRule.onNodeWithContentDescription(appsNavigation).assertIsDisplayed()
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 79), useUnmergedTree = true).assertIsDisplayed()
        val apps = visibleRow(APP_PREFIX)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(appsNavigation).assertIsDisplayed()
        assertViewport(apps, APP_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithContentDescription(appsNavigation).assertIsDisplayed()
        assertViewport(folders, FOLDER_PREFIX)
    }

    @Test
    fun searchRevealsCompleteHeaderAndStartsResultsAtTop() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        scrollAwayFromTop(APP_PREFIX)
        activeList(APP_PREFIX).performTouchInput {
            down(Offset(width * 0.5f, height * 0.25f))
            moveSmoothlyTo(Offset(width * 0.5f, height * 0.8f), durationMillis = 500)
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performClick().performTextInput("Library")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(uiString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true).assertIsDisplayed()
        val searchBottom = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.bottom
        val resultTop = composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("Search left a large empty gap above results", resultTop - searchBottom < 100f)
    }

    @Test
    fun reversedPagerDragPreservesSelection() {
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
        composeRule.onNodeWithContentDescription(checkbox).assertIsOn()

        // Hold beyond the midpoint so currentPage changes and Compose observes that transient page.
        // Returning before release leaves settledPage unchanged and must retain selection.
        composeRule.onRoot().performTouchInput {
            down(Offset(width * 0.85f, height * 0.55f))
            moveSmoothlyTo(Offset(width * 0.15f, height * 0.55f), durationMillis = 400)
        }
        composeRule.waitForIdle()
        // Offscreen pages clear their semantics, so this proves the indicated page changed.
        composeRule.onNodeWithText(COLLECTION_NAME).assertExists()
        composeRule.onRoot().performTouchInput {
            moveSmoothlyTo(Offset(width * 0.85f, height * 0.55f), durationMillis = 400)
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(checkbox).assertIsDisplayed().assertIsOn()
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
                bounds.top > viewport.top && bounds.bottom < viewport.bottom
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

    private fun swipeToNextPage() {
        composeRule.onRoot().performTouchInput { swipeLeft(durationMillis = 500) }
        composeRule.waitForIdle()
    }

    private fun swipeToPreviousPage() {
        composeRule.onRoot().performTouchInput { swipeRight(durationMillis = 500) }
        composeRule.waitForIdle()
    }

    private class ViewportHost : LibraryCollectionsHost {
        private val members = rows(MEMBER_PREFIX, 1)
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
        override fun onDismissCollectionMembers() = store.dismissMembers()
        override fun onPrepareCollectionAppPicker() = store.publishAllApps(members)
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
        override fun onAddAppsToCollection(appIds: Set<Long>, collectionId: Long) = Unit
        override fun onRemoveAppsFromCollection(appIds: Set<Long>, collectionId: Long) = Unit
        override fun onRemoveAppFromCollection(appId: Int, collectionId: Long) = Unit
        override fun onSearch(query: String) = Unit
        override fun onLayoutChange(layout: LibraryLayout) = Unit
        override fun onSort(sortIndex: Int) = Unit
        override fun onInstall() = Unit
        override fun onOpenApp(appId: Int) = Unit
        override fun onAddShortcut(appId: Int) = Unit
        override fun onRename(appId: Int, title: String) = Unit
        override fun onOpenAppSettings(appId: Int) = Unit
        override fun onReinstall(appId: Int) = Unit
        override fun onDelete(appId: Int) = Unit
        override fun onOpenSettings() = Unit
        override fun onOpenProfiles() = Unit
        override fun onOpenCrashReports() = Unit
        override fun onSaveLog() = Unit
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
