/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.applist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryCollectionRow
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

private fun selectionCount(count: Int): String =
    InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(
        R.plurals.library_selection_count,
        count,
        count,
    )

private fun uiString(resId: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LibraryCollectionsNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedCollectionRestoresAndBackReturnsToOverview() {
        val host = RecordingCollectionsHost()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = sampleLibraryState(),
                        actions = host,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()

        host.loadMembersOnOpen = false
        host.store.dismissMembers()
        composeRule.waitForIdle()
        val opensBeforeRestore = host.openedCollectionIds.size
        host.loadMembersOnOpen = true

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            host.openedCollectionIds.size > opensBeforeRestore
        }
        assertEquals(COLLECTION_ID, host.openedCollectionIds.last())
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()

        pressBack()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(MEMBER_TITLE).assertCountEquals(0)
        composeRule.onNodeWithText(COLLECTION_NAME).assertIsDisplayed()
        assertNull(host.store.displayedMembersCollectionId())
    }

    @Test
    fun inactiveCollectionPagePreservesSelectedDetailForPagerReturn() {
        val host = RecordingCollectionsHost().apply {
            store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER, SAMPLE_MEMBER_2))
        }
        val active = mutableStateOf(true)
        val navigationVisibilityEvents = mutableListOf<Boolean>()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryCollectionsDestination(
                        host = host,
                        libraryState = sampleLibraryState(),
                        scaffoldPadding = PaddingValues(),
                        navigationState = LibraryNavigationState(
                            destination = LibraryDestinationKey.Collections,
                            selectedCollectionId = COLLECTION_ID,
                        ),
                        onOpenActions = { _, _ -> },
                        onNavigationVisibilityChanged = { visible ->
                            navigationVisibilityEvents += visible
                        },
                        active = active.value,
                    )
                }
            }
        }

        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()
        composeRule.runOnIdle {
            navigationVisibilityEvents.clear()
            active.value = false
        }
        composeRule.waitForIdle()

        // HorizontalPager keeps neighbouring pages composed. Losing active ownership must not
        // replace the selected Collection with the overview while the page is off-screen.
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(emptyList<Boolean>(), navigationVisibilityEvents)
            active.value = true
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()
    }

    @Test
    fun collectionRouteDoesNotCrossLibraryWorkdirWithReusedDatabaseId() {
        val host = RecordingCollectionsHost()
        val libraryState = mutableStateOf(
            sampleLibraryState().copy(libraryScope = "/work/library-a"),
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = libraryState.value,
                        actions = host,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()

        composeRule.runOnIdle {
            libraryState.value = libraryState.value.copy(
                libraryScope = "/work/library-b",
                generation = libraryState.value.generation + 1L,
            )
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(MEMBER_TITLE).assertCountEquals(0)
        composeRule.onNodeWithText(COLLECTION_NAME).assertIsDisplayed()
        assertNull(host.store.displayedMembersCollectionId())
    }

    @Test
    fun collectionScopeMismatchDropsOnlyCollectionDetailAnchors() {
        val host = RecordingCollectionsHost()
        val navigationState = mutableStateOf(
            LibraryNavigationState(
                destination = LibraryDestinationKey.Collections,
                selectedCollectionId = COLLECTION_ID,
                selectedCollectionScope = "/work/library-a",
                collectionManageApps = true,
                anchors = mapOf(
                    LibraryNavigationSurface.CollectionAppsList to LibraryScrollAnchor(
                        generation = 0L,
                        stableItemId = 1L,
                        offsetPx = 4,
                        fallbackIndex = 0,
                        scopeId = COLLECTION_ID,
                    ),
                    LibraryNavigationSurface.CollectionAppsGrid to LibraryScrollAnchor(
                        generation = 0L,
                        stableItemId = 1L,
                        offsetPx = 5,
                        fallbackIndex = 0,
                        scopeId = COLLECTION_ID,
                    ),
                    LibraryNavigationSurface.AppsList to LibraryScrollAnchor(
                        generation = 0L,
                        stableItemId = 1L,
                        offsetPx = 6,
                        fallbackIndex = 0,
                    ),
                ),
            ),
        )

        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryCollectionsDestination(
                        host = host,
                        libraryState = sampleLibraryState().copy(libraryScope = "/work/library-b"),
                        scaffoldPadding = PaddingValues(),
                        navigationState = navigationState.value,
                        onNavigationStateChanged = { navigationState.value = it },
                        onOpenActions = { _, _ -> },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val restored = navigationState.value
        assertNull(restored.selectedCollectionId)
        assertNull(restored.selectedCollectionScope)
        assertFalse(restored.collectionManageApps)
        assertNull(restored.anchors[LibraryNavigationSurface.CollectionAppsList])
        assertNull(restored.anchors[LibraryNavigationSurface.CollectionAppsGrid])
        assertNotNull(restored.anchors[LibraryNavigationSurface.AppsList])
    }

    @Test
    fun collectionSelectionBackExitsSelectionBeforeLeavingCollection() {
        val host = RecordingCollectionsHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = sampleLibraryState(),
                        actions = host,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.onNodeWithText(MEMBER_TITLE).performTouchInput { longClick() }
        composeRule.onNodeWithText("Select").performClick()

        composeRule.onNodeWithText(selectionCount(1)).assertIsDisplayed()
        composeRule.onNodeWithText("Select all").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_checkbox_description, SAMPLE_MEMBER.title),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Remove from collection").assertIsDisplayed()

        pressBack()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(selectionCount(1)).assertCountEquals(0)
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()
        assertEquals(COLLECTION_ID, host.store.displayedMembersCollectionId())

        pressBack()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(MEMBER_TITLE).assertCountEquals(0)
        composeRule.onNodeWithText(COLLECTION_NAME).assertIsDisplayed()
        assertNull(host.store.displayedMembersCollectionId())
    }

    @Test
    fun collectionSelectionShowsCheckboxesSelectAllAndContextualBulkActions() {
        val host = RecordingCollectionsHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = sampleLibraryState(),
                        actions = host,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        // Enter selection after the Collection detail has already rendered. This verifies that
        // changing UI state reaches the visible detail without becoming navigation/layout state.
        composeRule.onNodeWithText(MEMBER_TITLE).performTouchInput { longClick() }
        composeRule.onNodeWithText("Select").performClick()

        composeRule.onNodeWithText(selectionCount(1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_checkbox_description, SAMPLE_MEMBER.title),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Select all").assertIsDisplayed()
        composeRule.onNodeWithText("Delete apps").assertIsDisplayed()
        composeRule.onNodeWithText("Remove from collection").assertIsDisplayed()
        composeRule.onNodeWithText("Share apps").assertIsDisplayed()
        composeRule.onNodeWithText("Reinstall").assertIsDisplayed()
        composeRule.onNodeWithText("Export bundle").assertIsDisplayed()
        composeRule.onAllNodesWithText("Add to collection").assertCountEquals(0)

        composeRule.onNodeWithText("Select all").performClick()
        composeRule.onNodeWithText(selectionCount(2)).assertIsDisplayed()
        composeRule.onNodeWithText("Deselect all").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_checkbox_description, SAMPLE_MEMBER_2.title),
        ).assertIsDisplayed()

        composeRule.onNodeWithText("Deselect all").performClick()
        composeRule.onNodeWithText(selectionCount(0)).assertIsDisplayed()
        composeRule.onNodeWithText("Select all").assertIsDisplayed()
    }

    @Test
    fun collectionSelectionBulkRemoveUsesCurrentCollection() {
        val host = RecordingCollectionsHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = sampleLibraryState(),
                        actions = host,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.onNodeWithText(MEMBER_TITLE).performTouchInput { longClick() }
        composeRule.onNodeWithText("Select").performClick()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_remove_from_current),
        ).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(setOf(SAMPLE_MEMBER.databaseId) to COLLECTION_ID), host.bulkRemovals)
        // A mutation request is not a committed membership change. Keep the selection so a
        // failed mutation remains retryable.
        composeRule.onNodeWithText(selectionCount(1)).assertIsDisplayed()
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()

        composeRule.runOnIdle {
            host.store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER_2))
        }
        composeRule.waitForIdle()

        // Once the authoritative member projection confirms removal, reconciliation exits
        // selection because every selected row has actually left the Collection.
        composeRule.onAllNodesWithText(selectionCount(1)).assertCountEquals(0)
        composeRule.onNodeWithText(SAMPLE_MEMBER_2.title).assertIsDisplayed()
    }

    @Test
    fun collectionLongPressRemoveKeepsCurrentCollectionContextAfterDialogDismiss() {
        val host = RecordingCollectionsHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = sampleLibraryState(),
                        actions = host,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.onNodeWithText(MEMBER_TITLE).performTouchInput { longClick() }
        composeRule.onNodeWithText("Remove from collection").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(SAMPLE_MEMBER.id to COLLECTION_ID), host.singleRemovals)
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()
    }

    @Test
    fun collectionManageAppsReassertsNavigationChromeAfterPagerReturn() {
        val host = RecordingCollectionsHost().apply {
            store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER, SAMPLE_MEMBER_2))
        }
        val active = mutableStateOf(true)
        val navigationState = mutableStateOf(
            LibraryNavigationState(
                destination = LibraryDestinationKey.Collections,
                selectedCollectionId = COLLECTION_ID,
            ),
        )
        val navigationVisibilityEvents = mutableListOf<Boolean>()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryCollectionsDestination(
                        host = host,
                        libraryState = sampleLibraryState(),
                        scaffoldPadding = PaddingValues(),
                        navigationState = navigationState.value,
                        onNavigationStateChanged = { navigationState.value = it },
                        onOpenActions = { _, _ -> },
                        onNavigationVisibilityChanged = { visible ->
                            navigationVisibilityEvents += visible
                        },
                        active = active.value,
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { navigationVisibilityEvents.clear() }
        composeRule.onNodeWithText(uiString(R.string.library_collection_add_apps)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(uiString(R.string.library_collection_manage_apps))
            .assertIsDisplayed()
        assertEquals(false, navigationVisibilityEvents.last())

        composeRule.runOnIdle {
            navigationVisibilityEvents.clear()
            active.value = false
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<Boolean>(), navigationVisibilityEvents)

        composeRule.runOnIdle { active.value = true }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(uiString(R.string.library_collection_manage_apps))
            .assertIsDisplayed()
        assertEquals(false, navigationVisibilityEvents.last())
    }

    @Test
    fun offscreenAppsLayoutChangeCannotRevealCollectionNavigationChrome() {
        val host = RecordingCollectionsHost()
        val libraryState = mutableStateOf(sampleLibraryState())
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = libraryState.value,
                        actions = host,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.onNodeWithText(uiString(R.string.library_collection_add_apps)).performClick()
        composeRule.onNodeWithText(uiString(R.string.library_collection_manage_apps))
            .assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription(
            uiString(R.string.library_destination_apps),
        ).assertCountEquals(0)

        composeRule.runOnIdle {
            libraryState.value = libraryState.value.copy(layout = LibraryLayout.Grid)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(uiString(R.string.library_collection_manage_apps))
            .assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(
            uiString(R.string.library_destination_apps),
        ).assertCountEquals(0)
    }

    @Test
    fun collectionManageAppsShowsPendingDesiredStateAndSerializesRowMutation() {
        val host = RecordingCollectionsHost().apply {
            deferMembershipResult = true
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = sampleLibraryState(),
                        actions = host,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.runOnIdle {
            host.store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(uiString(R.string.library_collection_add_apps)).performClick()

        val target = composeRule.onNodeWithTag(
            "collection-membership-${SAMPLE_MEMBER_2.id}",
        )
        target.performClick()
        composeRule.waitForIdle()

        assertEquals(
            listOf(Triple(SAMPLE_MEMBER_2.id, COLLECTION_ID, true)),
            host.membershipRequests,
        )
        target.assertIsOn().assertIsNotEnabled()

        composeRule.runOnIdle {
            host.completeMembership(success = false)
        }
        composeRule.waitForIdle()

        target.assertIsEnabled()
        target.performClick()
        composeRule.waitForIdle()
        assertEquals(2, host.membershipRequests.size)

        composeRule.runOnIdle {
            host.completeMembership(success = true)
            host.store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER, SAMPLE_MEMBER_2))
        }
        composeRule.waitForIdle()

        target.assertIsOn().assertIsEnabled()
    }

    @Test
    fun expandedWindowShowsListAndDetailWithoutDetailBack() {
        val host = RecordingCollectionsHost().apply {
            store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER))
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(900.dp, 1_000.dp)),
            ) {
                JLModPlusTheme {
                    LibraryCollectionsDestination(
                        host = host,
                        libraryState = sampleLibraryState(),
                        scaffoldPadding = PaddingValues(),
                        navigationState = LibraryNavigationState(
                            destination = LibraryDestinationKey.Collections,
                            selectedCollectionId = COLLECTION_ID,
                        ),
                        onOpenActions = { _, _ -> },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Collections").assertIsDisplayed()
        composeRule.onNodeWithText(MEMBER_TITLE).assertExists()
        composeRule.onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }

    private class RecordingCollectionsHost : LibraryCollectionsHost {
        val store = LibraryCollectionsUiStore().apply {
            publishCollections(
                listOf(
                    LibraryCollectionRow(
                        id = COLLECTION_ID,
                        name = COLLECTION_NAME,
                        sortOrder = 0,
                        createdAt = 1L,
                        appCount = 2,
                    ),
                ),
            )
            publishAllApps(listOf(SAMPLE_MEMBER, SAMPLE_MEMBER_2))
        }
        val openedCollectionIds = mutableListOf<Long>()
        val bulkRemovals = mutableListOf<Pair<Set<Long>, Long>>()
        val singleRemovals = mutableListOf<Pair<Int, Long>>()
        val membershipRequests = mutableListOf<Triple<Int, Long, Boolean>>()
        var deferMembershipResult = false
        private var pendingMembershipResult: CollectionMembershipResultCallback? = null
        var loadMembersOnOpen = true

        override fun collectionsStore(): LibraryCollectionsUiStore = store

        override fun onOpenCollection(collectionId: Long) {
            openedCollectionIds += collectionId
            if (loadMembersOnOpen) {
                store.showMembers(collectionId, listOf(SAMPLE_MEMBER, SAMPLE_MEMBER_2))
            }
        }

        override fun onDismissCollectionMembers() = store.dismissMembers()
        override fun onCreateCollection(name: String) = Unit
        override fun onRenameCollection(collectionId: Long, name: String) = Unit
        override fun onDeleteCollection(collectionId: Long) = Unit
        override fun onPrepareCollectionAppPicker() {
            store.publishAllApps(listOf(SAMPLE_MEMBER, SAMPLE_MEMBER_2))
        }
        override fun onRequestAddToCollection(appId: Int) = Unit
        override fun onDismissAddToCollection() = Unit
        override fun onAddAppToCollection(appId: Int, collectionId: Long) = Unit
        override fun onSetCollectionMembership(
            appId: Int,
            collectionId: Long,
            included: Boolean,
            callback: CollectionMembershipResultCallback,
        ) {
            membershipRequests += Triple(appId, collectionId, included)
            if (deferMembershipResult) {
                pendingMembershipResult = callback
            } else {
                callback.onResult(true)
            }
        }
        override fun onAddAppsToCollection(appIds: Set<Long>, collectionId: Long) = Unit
        override fun onRemoveAppsFromCollection(appIds: Set<Long>, collectionId: Long) {
            bulkRemovals += appIds to collectionId
        }
        override fun onRemoveAppFromCollection(appId: Int, collectionId: Long) {
            singleRemovals += appId to collectionId
        }
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

        fun completeMembership(success: Boolean) {
            val callback = checkNotNull(pendingMembershipResult)
            pendingMembershipResult = null
            callback.onResult(success)
        }
    }

    private companion object {
        const val COLLECTION_ID = 1L
        const val COLLECTION_NAME = "RPG Favorites"
        const val MEMBER_TITLE = "Demo MIDlet"

        val SAMPLE_MEMBER = LibraryAppUiItem(
            id = 7,
            title = MEMBER_TITLE,
            author = "Example Vendor",
            version = "1.0",
            iconPath = null,
            canReinstall = true,
        )

        val SAMPLE_MEMBER_2 = LibraryAppUiItem(
            id = 8,
            title = "Second MIDlet",
            author = "Example Vendor",
            version = "1.1",
            iconPath = null,
            canReinstall = true,
        )

        fun sampleLibraryState() = LibraryUiState(
            loading = false,
            apps = emptyList(),
            layout = LibraryLayout.List,
            databaseControlsReady = true,
        )
    }
}
