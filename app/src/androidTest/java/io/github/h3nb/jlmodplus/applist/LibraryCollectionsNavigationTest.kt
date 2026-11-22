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

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryCollectionRow
import io.github.h3nb.jlmodplus.librarydb.LibraryQuickView
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
    fun selectingCollectionFromLibraryScopeOpensItsMembers() {
        val host = RecordingCollectionsHost().apply {
            store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER))
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(state = sampleLibraryState(), actions = host)
                }
            }
        }
        composeRule.onNodeWithTag("library_scope_picker").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        waitForMember()
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()

        pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_scope_picker").assertIsDisplayed()
    }

    @Test
    fun selectingQuickViewInsideCollectionReturnsToAllApps() {
        val host = RecordingCollectionsHost().apply {
            store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER))
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(state = sampleLibraryState(), actions = host)
                }
            }
        }
        composeRule.onNodeWithTag("library_scope_picker").performClick()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        waitForMember()
        composeRule.onNodeWithTag("library_scope_picker").performClick()
        composeRule.onNodeWithText(uiString(R.string.library_filter_favorites)).performClick()
        composeRule.waitForIdle()

        assertEquals(LibraryQuickView.Favorites, host.lastQuickView)
        composeRule.onNodeWithTag("library_scope_picker").assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_destination_apps)).assertExists()
    }

    @Test
    fun compactLandscapeCollectionPickerUsesDialogWithPinnedManageAction() {
        val host = RecordingCollectionsHost().apply {
            store.publishCollections(
                listOf(
                    LibraryCollectionRow(
                        id = COLLECTION_ID, name = "Gameloft",
                        sortOrder = 0, createdAt = 1L, appCount = 8,
                    ),
                    LibraryCollectionRow(
                        id = 2L, name = "RPG",
                        sortOrder = 1, createdAt = 2L, appCount = 0,
                    ),
                ),
            )
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(state = sampleLibraryState(), actions = host)
                }
            }
        }

        composeRule.onNodeWithTag("library_scope_picker").performClick()
        val dialog = composeRule.onNodeWithTag("adaptive-dialog-surface")
        dialog.assertIsDisplayed()
        val footer = composeRule.onNodeWithText(uiString(R.string.library_collection_manage))
        footer.assertIsDisplayed()
        composeRule.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToIndex(3)
        val count = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.library_collection_member_count, 0, 0)
        val countNode = composeRule.onNodeWithText(count)
        countNode.assertIsDisplayed()
        assertTrue(
            "Last collection must not be hidden behind Manage collections",
            countNode.fetchSemanticsNode().boundsInRoot.bottom <=
                footer.fetchSemanticsNode().boundsInRoot.top,
        )
        val dialogTop = dialog.fetchSemanticsNode().boundsInRoot.top
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertEquals(dialogTop, dialog.fetchSemanticsNode().boundsInRoot.top, 0.5f)
    }

    @Test
    fun searchInCollectionOnlyShowsItsMembersAndRestoresBrowsing() {
        val host = RecordingCollectionsHost()
        val outside = LibraryAppUiItem(25, "Outside Demo", "Other Vendor", "1.0", null, true)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = sampleLibraryState().copy(apps = listOf(outside)),
                        actions = host,
                    )
                }
            }
        }

        openCollectionsManagement()
        openCollection()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_search_placeholder))
            .performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Demo")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(MEMBER_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Outside Demo").assertCountEquals(0)
        composeRule.onAllNodesWithText(SAMPLE_MEMBER_2.title).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(uiString(R.string.action_back)).performClick()
        composeRule.onNodeWithText(SAMPLE_MEMBER_2.title).assertIsDisplayed()
    }

    @Test
    fun selectedCollectionRestoresAndBackReturnsToAllApps() {
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

        openCollectionsManagement()
        openCollection()
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
        waitForMember()
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()

        pressBack()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(MEMBER_TITLE).assertCountEquals(0)
        composeRule.onNodeWithTag("library_scope_picker").assertIsDisplayed()
        assertNull(host.store.displayedMembersCollectionId())
    }

    @Test
    fun deletingActiveCollectionReturnsToAllApps() {
        val host = RecordingCollectionsHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(state = sampleLibraryState(), actions = host)
                }
            }
        }
        openCollectionsManagement()
        openCollection()
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()
        composeRule.runOnIdle { host.store.publishCollections(emptyList()) }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("library_scope_picker")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(MEMBER_TITLE).assertCountEquals(0)
        assertNull(host.store.displayedMembersCollectionId())
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

        openCollectionsManagement()
        openCollection()
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()

        composeRule.runOnIdle {
            libraryState.value = libraryState.value.copy(
                libraryScope = "/work/library-b",
                generation = libraryState.value.generation + 1L,
            )
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(MEMBER_TITLE).assertCountEquals(0)
        composeRule.onNodeWithTag("library_scope_picker").assertIsDisplayed()
        assertNull(host.store.displayedMembersCollectionId())
    }

    @Test
    fun appCollectionCheckboxesToggleMembershipWithoutClosingTheDialog() {
        val host = RecordingCollectionsHost()
        composeRule.setContent {
            JLModPlusTheme {
                LibraryCollectionsDialogHost(host)
            }
        }
        composeRule.runOnIdle {
            val request = host.store.showAddTarget(SAMPLE_MEMBER.id, MEMBER_TITLE)
            host.store.publishTargetMemberships(SAMPLE_MEMBER.id, request, emptySet())
        }
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf(Triple(SAMPLE_MEMBER.databaseId, COLLECTION_ID, true)),
            host.membershipRequests,
        )
        composeRule.onNodeWithText(COLLECTION_NAME).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_collection_new)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Arcade")
        composeRule.onNodeWithText(uiString(R.string.create)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SAMPLE_MEMBER.id to "Arcade"), host.createdForApps)
    }

    @Test
    fun closedCollectionTargetIgnoresLateMembershipResults() {
        val store = LibraryCollectionsUiStore()
        val stale = store.showAddTarget(SAMPLE_MEMBER.id, MEMBER_TITLE)
        store.dismissAddTarget()
        val current = store.showAddTarget(SAMPLE_MEMBER.id, MEMBER_TITLE)
        store.publishTargetMemberships(SAMPLE_MEMBER.id, stale, setOf(COLLECTION_ID))
        assertNull(store.state.value.addTarget?.memberCollectionIds)
        store.publishTargetMemberships(SAMPLE_MEMBER.id, current, emptySet())
        assertEquals(emptySet<Long>(), store.state.value.addTarget?.memberCollectionIds)
    }

    @Test
    fun collectionDialogConsumesControllerActionsAndBackBeforeLibrary() {
        val host = RecordingCollectionsHost()
        val events = MutableSharedFlow<LibraryControllerEvent>(extraBufferCapacity = 8)
        val request = host.store.showAddTarget(SAMPLE_MEMBER.id, MEMBER_TITLE)
        host.store.publishTargetMemberships(SAMPLE_MEMBER.id, request, emptySet())
        val app = SAMPLE_MEMBER
        composeRule.setContent {
            JLModPlusTheme {
                LibraryScreen(
                    state = sampleLibraryState().copy(
                        apps = listOf(app),
                        availableAppIds = setOf(app.databaseId),
                        projectedAppIds = listOf(app.databaseId),
                    ),
                    actions = host,
                    controllerEvents = events,
                )
            }
        }
        composeRule.onNodeWithText(uiString(R.string.library_destination_collections))
            .assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            events.subscriptionCount.value >= 2
        }
        composeRule.runOnIdle {
            assertTrue(events.tryEmit(LibraryControllerEvent(1L, LibraryControllerCommand.Activate)))
        }
        composeRule.waitForIdle()
        assertEquals(0, host.openedAppCount)
        assertTrue(host.store.state.value.addTarget != null)

        composeRule.runOnIdle {
            assertTrue(events.tryEmit(LibraryControllerEvent(2L, LibraryControllerCommand.Back)))
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            host.store.state.value.addTarget == null
        }
        assertEquals(0, host.openedAppCount)
    }

    @Test
    fun collectionsManagerExposesCreationAndDoesNotImplyUninstallOnDelete() {
        val host = RecordingCollectionsHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(state = sampleLibraryState(), actions = host)
                }
            }
        }

        openCollectionsManagement()
        composeRule.onNodeWithText(uiString(R.string.library_destination_collections)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_collection_new))
            .performClick()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_delete_selected),
        ).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_select_action, COLLECTION_NAME),
        ).performClick()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_delete_selected),
        ).assertIsEnabled().performClick()
        composeRule.onNodeWithText(
            uiString(R.string.library_collection_delete_confirmation, COLLECTION_NAME),
        ).assertIsDisplayed()
    }

    @Test
    fun collectionActionsAreDirectAndRenameOpensEditor() {
        val host = RecordingCollectionsHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(state = sampleLibraryState(), actions = host)
                }
            }
        }

        openCollectionsManagement()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_rename_selected),
        ).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_select_action, COLLECTION_NAME),
        ).performClick()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_rename_selected),
        ).assertIsEnabled().performClick()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun selectingMultipleCollectionsEnablesBulkDeleteButDisablesRename() {
        val host = RecordingCollectionsHost()
        host.store.publishCollections(
            listOf(
                LibraryCollectionRow(COLLECTION_ID, COLLECTION_NAME, 0, 1L, 2),
                LibraryCollectionRow(2L, "RPG", 1, 2L, 0),
            ),
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = sampleLibraryState(), actions = host) }
            }
        }
        openCollectionsManagement()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_select_action, COLLECTION_NAME),
        ).performClick()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_select_action, "RPG"),
        ).performClick()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_rename_selected),
        ).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_delete_selected),
        ).assertIsEnabled().performClick()
        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(
                R.plurals.library_collection_delete_many_confirmation, 2, 2,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.action_context_delete)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(COLLECTION_ID, 2L), host.bulkCollectionDeletions.single())
        assertTrue(host.store.state.value.collections.isEmpty())
    }

    @Test
    fun longPressDragReordersCollectionsAndPersistsOnce() {
        val host = RecordingCollectionsHost()
        host.store.publishCollections(
            listOf(
                LibraryCollectionRow(COLLECTION_ID, COLLECTION_NAME, 0, 1L, 2),
                LibraryCollectionRow(2L, "RPG", 1, 2L, 0),
            ),
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = sampleLibraryState(), actions = host) }
            }
        }
        openCollectionsManagement()
        composeRule.onNodeWithText(COLLECTION_NAME).performTouchInput {
            down(center)
            advanceEventTime(650)
            moveBy(Offset(0f, 120f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf(2L, COLLECTION_ID), host.reorderRequests.single())
        assertEquals(
            listOf(2L, COLLECTION_ID),
            host.store.state.value.collections.map { it.id },
        )
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

        openCollectionsManagement()
        openCollection()
        composeRule.onNodeWithText(MEMBER_TITLE).performTouchInput { longClick() }
        composeRule.onNodeWithText("Select").performClick()

        composeRule.onNodeWithText(selectionCount(1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_selection_select_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_checkbox_description, SAMPLE_MEMBER.title),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_remove_from_current),
        ).assertIsDisplayed()

        pressBack()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(selectionCount(1)).assertCountEquals(0)
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()
        assertEquals(COLLECTION_ID, host.store.displayedMembersCollectionId())

        pressBack()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(MEMBER_TITLE).assertCountEquals(0)
        composeRule.onNodeWithTag("library_scope_picker").assertIsDisplayed()
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

        openCollectionsManagement()
        openCollection()
        // Enter selection after the Collection detail has already rendered. This verifies that
        // changing UI state reaches the visible detail without becoming navigation/layout state.
        composeRule.onNodeWithText(MEMBER_TITLE).performTouchInput { longClick() }
        composeRule.onNodeWithText("Select").performClick()

        composeRule.onNodeWithText(selectionCount(1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_checkbox_description, SAMPLE_MEMBER.title),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_selection_select_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_action_delete),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_collection_remove_from_current),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_action_reinstall),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_action_export),
        ).assertIsDisplayed()
        composeRule.onAllNodesWithText("Share apps").assertCountEquals(0)
        composeRule.onAllNodesWithText("Add to collection").assertCountEquals(0)

        composeRule.onNodeWithContentDescription(uiString(R.string.library_selection_select_all)).performClick()
        composeRule.onNodeWithText(selectionCount(2)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_selection_unselect_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            uiString(R.string.library_selection_checkbox_description, SAMPLE_MEMBER_2.title),
        ).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(uiString(R.string.library_selection_unselect_all)).performClick()
        composeRule.onNodeWithText(selectionCount(0)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_selection_select_all)).assertIsDisplayed()
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

        openCollectionsManagement()
        openCollection()
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

        openCollectionsManagement()
        openCollection()
        composeRule.onNodeWithText(MEMBER_TITLE).performTouchInput { longClick() }
        composeRule.onNodeWithText("Remove from collection").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(SAMPLE_MEMBER.id to COLLECTION_ID), host.singleRemovals)
        composeRule.onNodeWithText(MEMBER_TITLE).assertIsDisplayed()
    }

    @Test
    fun collectionMembershipEditorRemainsVisibleAcrossLibraryLayoutChange() {
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

        openCollectionsManagement()
        openCollection()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_collection_add_apps)).performClick()
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

        openCollectionsManagement()
        openCollection()
        composeRule.runOnIdle {
            host.store.showMembers(COLLECTION_ID, listOf(SAMPLE_MEMBER))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_collection_add_apps)).performClick()

        val target = composeRule.onNodeWithTag(
            "collection-membership-${SAMPLE_MEMBER_2.databaseId}",
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("collection-membership-${SAMPLE_MEMBER_2.databaseId}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        target.performClick()
        composeRule.waitForIdle()

        assertEquals(
            listOf(Triple(SAMPLE_MEMBER_2.databaseId, COLLECTION_ID, true)),
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
    fun collectionPickerSearchReturnsToFirstResultAfterDeepScroll() {
        val host = RecordingCollectionsHost().apply {
            pickerApps = List(80) { index ->
                SAMPLE_MEMBER.copy(
                    id = 1_000 + index,
                    databaseId = 10_000L + index,
                    title = "Picker app " + index.toString().padStart(2, '0'),
                )
            }
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(state = sampleLibraryState(), actions = host)
                }
            }
        }
        openCollectionsManagement()
        openCollection()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_collection_add_apps)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("collection-membership-10000")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The pager is scrollable too; select the picker LazyColumn by its visible row.
        composeRule.onAllNodes(
            hasScrollAction() and hasAnyDescendant(hasText("Picker app 00")),
            useUnmergedTree = true,
        ).onLast().performScrollToIndex(65)
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextInput("Picker app 00")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("collection-membership-10000")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // InputText contains the query too; assert the unique result row, not matching text.
        composeRule.onNodeWithTag("collection-membership-10000").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        // One Back must dismiss editing without navigating away from Add apps.
        pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onNode(hasSetTextAction())
                .fetchSemanticsNode().config[SemanticsProperties.Focused] == false
        }
        composeRule.onNode(hasSetTextAction()).assertTextEquals("Picker app 00")
        composeRule.onNodeWithTag("collection-membership-10000").assertIsDisplayed()
    }

    private fun openCollectionsManagement() {
        composeRule.onNodeWithTag("library_scope_picker").performClick()
        composeRule.onNodeWithText(uiString(R.string.library_collection_manage)).performClick()
    }

    private fun openCollection() {
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        waitForMember()
    }

    private fun waitForMember() {
        // Projection runs on Default; Compose idleness alone does not mean its rows are ready.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(MEMBER_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
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
        }
        val openedCollectionIds = mutableListOf<Long>()
        var lastQuickView: LibraryQuickView? = null
        val reorderRequests = mutableListOf<List<Long>>()
        val bulkCollectionDeletions = mutableListOf<List<Long>>()
        val bulkRemovals = mutableListOf<Pair<Set<Long>, Long>>()
        val singleRemovals = mutableListOf<Pair<Int, Long>>()
        val membershipRequests = mutableListOf<Triple<Long, Long, Boolean>>()
        val createdForApps = mutableListOf<Pair<Int, String>>()
        var deferMembershipResult = false
        var pickerApps: List<LibraryAppUiItem> = listOf(SAMPLE_MEMBER, SAMPLE_MEMBER_2)
        private var pendingMembershipResult: CollectionMembershipResultCallback? = null
        var loadMembersOnOpen = true
        var openedAppCount = 0

        override fun collectionsStore(): LibraryCollectionsUiStore = store

        override fun onOpenCollection(collectionId: Long) {
            openedCollectionIds += collectionId
            if (loadMembersOnOpen) {
                store.showMembers(collectionId, listOf(SAMPLE_MEMBER, SAMPLE_MEMBER_2))
            }
        }

        override fun onDismissCollectionMembers() = store.dismissMembers()
        override fun onCreateCollection(name: String) = Unit
        override fun onCreateCollectionForApp(appId: Int, name: String) {
            createdForApps += appId to name
        }
        override fun onRenameCollection(collectionId: Long, name: String) = Unit
        override fun onDeleteCollection(collectionId: Long) = Unit
        override fun onDeleteCollections(
            ids: List<Long>,
            callback: CollectionMembershipResultCallback,
        ) {
            bulkCollectionDeletions += ids
            val remaining = store.state.value.collections.filterNot { it.id in ids }
            store.publishCollections(remaining.mapIndexed { index, collection ->
                LibraryCollectionRow(
                    collection.id,
                    collection.name,
                    index,
                    collection.id,
                    collection.appCount,
                )
            })
            callback.onResult(true)
        }
        override fun onReorderCollections(
            orderedIds: List<Long>,
            callback: CollectionMembershipResultCallback,
        ) {
            reorderRequests += orderedIds
            val current = store.state.value.collections.associateBy { it.id }
            store.publishCollections(orderedIds.mapIndexed { index, id ->
                val collection = requireNotNull(current[id])
                LibraryCollectionRow(id, collection.name, index, index.toLong(), collection.appCount)
            })
            callback.onResult(true)
        }
        override fun onPrepareCollectionAppPicker() {
            store.publishAllApps(pickerApps)
        }
        override fun onRequestAddToCollection(appId: Int) = Unit
        override fun onDismissAddToCollection() = store.dismissAddTarget()
        override fun onAddAppToCollection(appId: Int, collectionId: Long) = Unit
        override fun onSetCollectionMembership(
            appId: Int,
            collectionId: Long,
            included: Boolean,
            callback: CollectionMembershipResultCallback,
        ) {
            onSetCollectionMembershipByDatabaseId(
                appId.toLong(), collectionId, included,
                CollectionMembershipResultCallback { success ->
                    if (success) {
                        store.updateTargetMembership(
                            appId, store.currentTargetRequestId(appId), collectionId, included,
                        )
                    }
                    callback.onResult(success)
                },
            )
        }

        override fun onSetCollectionMembershipByDatabaseId(
            databaseId: Long,
            collectionId: Long,
            included: Boolean,
            callback: CollectionMembershipResultCallback,
        ) {
            membershipRequests += Triple(databaseId, collectionId, included)
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
        override fun onQuickView(quickView: LibraryQuickView) {
            lastQuickView = quickView
        }
        override fun onSearch(query: String) = Unit
        override fun onLayoutChange(layout: LibraryLayout) = Unit
        override fun onSort(sortIndex: Int) = Unit
        override fun onInstall() = Unit
        override fun onOpenApp(appId: Int) { openedAppCount++ }
        override fun onAddShortcut(appId: Int) = Unit
        override fun onRename(appId: Int, title: String) = Unit
        override fun onOpenAppSettings(appId: Int) = Unit
        override fun onReinstall(appId: Int) = Unit
        override fun onDelete(appId: Int) = Unit
        override fun onOpenSettings() = Unit
        override fun onOpenProfiles() = Unit
        override fun onOpenCrashReports() = Unit
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
