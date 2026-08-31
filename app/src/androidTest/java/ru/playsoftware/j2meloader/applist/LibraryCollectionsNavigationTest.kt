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

package ru.playsoftware.j2meloader.applist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.librarydb.LibraryCollectionRow
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

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
        assertNull(host.store.activeCollectionId())
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
                        appCount = 1,
                    ),
                ),
            )
            publishAllApps(listOf(SAMPLE_MEMBER))
        }
        val openedCollectionIds = mutableListOf<Long>()
        var loadMembersOnOpen = true

        override fun collectionsStore(): LibraryCollectionsUiStore = store

        override fun onOpenCollection(collectionId: Long) {
            openedCollectionIds += collectionId
            if (loadMembersOnOpen) {
                store.showMembers(collectionId, listOf(SAMPLE_MEMBER))
            }
        }

        override fun onDismissCollectionMembers() = store.dismissMembers()
        override fun onCreateCollection(name: String) = Unit
        override fun onRenameCollection(collectionId: Long, name: String) = Unit
        override fun onDeleteCollection(collectionId: Long) = Unit
        override fun onPrepareCollectionAppPicker() = Unit
        override fun onRequestAddToCollection(appId: Int) = Unit
        override fun onDismissAddToCollection() = Unit
        override fun onAddAppToCollection(appId: Int, collectionId: Long) = Unit
        override fun onAddAppsToCollection(appIds: Set<Long>, collectionId: Long) = Unit
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

        fun sampleLibraryState() = LibraryUiState(
            loading = false,
            apps = emptyList(),
            layout = LibraryLayout.List,
            databaseControlsReady = true,
        )
    }
}
