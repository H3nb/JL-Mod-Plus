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

import io.github.h3nb.jlmodplus.R
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

private fun uiString(resId: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LibraryComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactHeightAppActionsKeepLastActionReachable() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp)),
            ) {
                JLModPlusTheme {
                    AppActionsDialog(
                        app = LibraryAppUiItem(7, "Demo MIDlet", "Example Vendor", "1.0", null, true),
                        onDismiss = {},
                        onShortcut = {},
                        onRename = {},
                        onSettings = {},
                        onReinstall = {},
                        onDelete = {},
                        onEditMetadata = {},
                        onAddToCollection = {},
                        onShareApp = {},
                        onExportAppBundle = {},
                        onSelect = {},
                    )
                }
            }
        }

        val deleteLabel = uiString(R.string.action_context_delete)
        composeRule.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText(deleteLabel))
        composeRule.onNodeWithText(deleteLabel).assertIsDisplayed()
        composeRule.onAllNodesWithTag("library-controller-focus-indicator").assertCountEquals(0)
    }

    @Test
    fun appActionsShowFocusOnlyAfterControllerNavigation() {
        val controllerEvents = MutableSharedFlow<LibraryControllerEvent>(extraBufferCapacity = 8)
        composeRule.setContent {
            JLModPlusTheme {
                AppActionsDialog(
                    app = LibraryAppUiItem(7, "Demo MIDlet", "Example Vendor", "1.0", null, true),
                    controllerEvents = controllerEvents,
                    onDismiss = {},
                    onShortcut = {},
                    onRename = {},
                    onSettings = {},
                    onReinstall = {},
                    onDelete = {},
                    onEditMetadata = {},
                    onAddToCollection = {},
                    onShareApp = {},
                    onExportAppBundle = {},
                    onSelect = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onAllNodes(isSelected() and hasAnyAncestor(isDialog())).assertCountEquals(0)
        composeRule.waitUntil(timeoutMillis = 5_000) { controllerEvents.subscriptionCount.value > 0 }
        assertTrue(controllerEvents.tryEmit(
            LibraryControllerEvent(1L, LibraryControllerCommand.MoveDown),
        ))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(isSelected() and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes().size == 1
        }

        composeRule.onAllNodes(isSelected() and hasAnyAncestor(isDialog())).assertCountEquals(1)
        composeRule.onNode(hasText(uiString(R.string.library_metadata_edit_title)) and isSelected())
            .assertIsDisplayed()
    }

    @Test
    fun searchDispatchesCurrentTextWithoutArtificialDebounce() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)
        composeRule.waitForIdle()
        actions.searches.clear()

        composeRule.onNode(hasSetTextAction()).performTextInput("Demo")
        composeRule.waitForIdle()

        assertEquals(listOf("Demo"), actions.searches)
    }

    @Test
    fun libraryExposesLoadingState() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(loading = true),
            actions = actions,
        )
        composeRule.onNodeWithContentDescription("Loading apps").assertIsDisplayed()
    }

    @Test
    fun indexingStateShowsProgressAndCurrentStorageKey() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = true,
                loadingCompleted = 12,
                loadingTotal = 100,
                loadingStorageKey = "Bounce_Tales",
            ),
            actions = actions,
        )

        composeRule.onNodeWithText(uiString(R.string.library_indexing_progress, 12, 100)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_indexing_current, "Bounce_Tales")).assertIsDisplayed()
    }

    @Test
    fun libraryErrorOffersRetryCallback() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                errorMessage = "Storage unavailable",
            ),
            actions = actions,
        )

        composeRule.onNodeWithText(uiString(R.string.library_load_error_title)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_load_failed_message)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_retry)).performClick()
        assertEquals(1, actions.retryCount)
    }

    @Test
    fun libraryExposesFilteredEmptyAndInstallStates() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(loading = false, appliedFilter = "missing"),
            actions = actions,
        )
        composeRule.onNodeWithText("No matches for \"missing\"").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Install").performClick()
        assertEquals(1, actions.installCount)
    }

    @Test
    fun appClickAndContextActionsKeepStableAppIdentity() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)

        composeRule.onNodeWithText("Demo MIDlet").performClick()
        assertEquals(7, actions.openedId)

        composeRule.onNodeWithText("Demo MIDlet").performTouchInput { longClick() }
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(" Updated")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(7 to "Demo MIDlet Updated", actions.renamed)
    }

    @Test
    fun controllerEventsMoveStableFocusAndActivateOnce() {
        val actions = RecordingLibraryActions()
        val controllerEvents = MutableSharedFlow<LibraryControllerEvent>(extraBufferCapacity = 8)
        composeRule.setContent {
            JLModPlusTheme {
                LibraryScreen(
                    state = LibraryUiState(
                        loading = false,
                        apps = listOf(
                            LibraryAppUiItem(7, "Demo MIDlet", "Example Vendor", "1.0", null, true),
                            LibraryAppUiItem(8, "Second MIDlet", "Example Vendor", "1.0", null, true),
                        ),
                    ),
                    actions = actions,
                    controllerEvents = controllerEvents,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("library-controller-focus-indicator").assertCountEquals(0)

        controllerEvents.tryEmit(
            LibraryControllerEvent(1L, LibraryControllerCommand.MoveDown),
        )
        composeRule.waitForIdle()
        controllerEvents.tryEmit(
            LibraryControllerEvent(2L, LibraryControllerCommand.Activate),
        )
        composeRule.waitForIdle()

        assertEquals(8, actions.openedId)
    }

    @Test
    fun nonNavigationAppUpdateKeepsTouchScrolledListViewport() {
        val actions = RecordingLibraryActions()
        val apps = (0..24).map { index ->
            LibraryAppUiItem(
                id = index,
                title = "Demo MIDlet $index",
                author = "Example Vendor",
                version = "1.0",
                iconPath = null,
                canReinstall = true,
                databaseId = 100L + index,
            )
        }
        val libraryState = mutableStateOf(
            LibraryUiState(
                loading = false,
                apps = apps,
                databaseControlsReady = true,
                generation = 1L,
            ),
        )
        composeRule.setContent {
            JLModPlusTheme {
                LibraryScreen(
                    state = libraryState.value,
                    actions = actions,
                )
            }
        }
        composeRule.waitForIdle()

        appViewport().performTouchInput {
            // Start above the navigation overlay; the full Lazy viewport includes its inset.
            swipe(Offset(width * 0.5f, height * 0.7f), Offset(width * 0.5f, height * 0.15f), 600)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Demo MIDlet 0", useUnmergedTree = true).assertIsNotDisplayed()
        val before = visibleAppAnchor()

        composeRule.runOnIdle {
            libraryState.value = libraryState.value.copy(
                apps = libraryState.value.apps.map { app ->
                    if (app.id == 12) app.copy(favorite = true) else app
                },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Demo MIDlet 0", useUnmergedTree = true).assertIsNotDisplayed()
        val after = visibleAppAnchor()
        assertEquals("Visible app changed after a non-navigation update", before.first, after.first)
        assertEquals("Visible app moved after a non-navigation update", before.second, after.second, 1f)
    }

    @Test(timeout = 60_000)
    fun controllerNavigationStillScrollsFocusedAppIntoView() {
        val actions = RecordingLibraryActions()
        val controllerEvents = MutableSharedFlow<LibraryControllerEvent>(extraBufferCapacity = 32)
        val apps = (0..24).map { index ->
            LibraryAppUiItem(
                id = index,
                title = "Demo MIDlet $index",
                author = "Example Vendor",
                version = "1.0",
                iconPath = null,
                canReinstall = true,
                databaseId = 100L + index,
            )
        }
        composeRule.setContent {
            JLModPlusTheme {
                LibraryScreen(
                    state = LibraryUiState(
                        loading = false,
                        apps = apps,
                    ),
                    actions = actions,
                    controllerEvents = controllerEvents,
                )
            }
        }
        composeRule.waitForIdle()

        android.util.Log.i("UILibraryController", "initial layout ready")
        appViewport().performScrollToNode(hasText("Demo MIDlet 20"))
        android.util.Log.i("UILibraryController", "away from focus")
        appViewport().performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.7f), Offset(width * 0.5f, height * 0.5f), 300)
        }
        android.util.Log.i("UILibraryController", "touch released")
        controllerEvents.tryEmit(LibraryControllerEvent(1L, LibraryControllerCommand.MoveUp))
        android.util.Log.i("UILibraryController", "MoveUp emitted")
        composeRule.waitForIdle()
        android.util.Log.i("UILibraryController", "MoveUp settled")
        composeRule.onNodeWithText("Demo MIDlet 0").assertIsDisplayed()
        // Start expanded to verify focus is below the opaque header, not merely inside the window.
        appViewport().performScrollToIndex(0)
        android.util.Log.i("UILibraryController", "top requested")
        composeRule.waitForIdle()
        repeat(12) { index ->
            controllerEvents.tryEmit(
                LibraryControllerEvent(
                    sequence = index.toLong() + 2L,
                    command = LibraryControllerCommand.MoveDown,
                ),
            )
            composeRule.waitForIdle()
            android.util.Log.i("UILibraryController", "MoveDown ${index + 1} settled")
        }

        composeRule.onNodeWithText("Demo MIDlet 12").assertIsDisplayed()
        val headerBottom = composeRule.onNodeWithText(uiString(R.string.library_filter_all))
            .fetchSemanticsNode().boundsInRoot.bottom
        val focusedTop = composeRule.onNodeWithText("Demo MIDlet 12")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("Controller focus is covered by the header", focusedTop >= headerBottom)
        composeRule.onAllNodesWithTag("library-controller-focus-indicator").assertCountEquals(1)
    }

    @Test
    fun longPressSelectEntersSelectionModeAndScopesSelectAllToVisibleApps() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                generation = 11L,
                databaseControlsReady = true,
                apps = listOf(
                    LibraryAppUiItem(
                        id = 7,
                        title = "Demo MIDlet",
                        author = "Example Vendor",
                        version = "1.0",
                        iconPath = null,
                        canReinstall = true,
                        databaseId = 70L,
                    ),
                    LibraryAppUiItem(
                        id = 8,
                        title = "Second MIDlet",
                        author = "Example Vendor",
                        version = "1.0",
                        iconPath = null,
                        canReinstall = true,
                        databaseId = 80L,
                    ),
                ),
            ),
            actions = actions,
        )

        composeRule.onNodeWithText("Demo MIDlet").performTouchInput { longClick() }
        composeRule.onNodeWithText("Select").performClick()

        composeRule.onNodeWithText("1 app").assertIsDisplayed()
        composeRule.onAllNodesWithText("Recently played").assertCountEquals(0)
        composeRule.onAllNodesWithText("Favorites").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(uiString(R.string.library_favorite_coming_soon))
            .assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Select all").performClick()
        composeRule.onNodeWithText("2 apps").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Deselect all").performClick()
        composeRule.onNodeWithText("0 apps").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(uiString(R.string.library_back)).performClick()
        composeRule.onNodeWithText(uiString(R.string.library_filter_all)).assertIsDisplayed()
    }

    @Test
    fun selectionSurvivesFilteredProjectionUntilAppLeavesLibrary() {
        val actions = RecordingLibraryActions()
        val first = LibraryAppUiItem(
            id = 7,
            title = "Demo MIDlet",
            author = "Example Vendor",
            version = "1.0",
            iconPath = null,
            canReinstall = true,
            databaseId = 70L,
        )
        val second = LibraryAppUiItem(
            id = 8,
            title = "Second MIDlet",
            author = "Example Vendor",
            version = "1.0",
            iconPath = null,
            canReinstall = true,
            databaseId = 80L,
        )
        val libraryState = mutableStateOf(
            LibraryUiState(
                loading = false,
                generation = 11L,
                databaseControlsReady = true,
                apps = listOf(first, second),
                availableAppIds = setOf(first.databaseId, second.databaseId),
            ),
        )
        composeRule.setContent {
            JLModPlusTheme {
                LibraryScreen(state = libraryState.value, actions = actions)
            }
        }

        composeRule.onNodeWithText(first.title).performTouchInput { longClick() }
        composeRule.onNodeWithText("Select").performClick()
        composeRule.onNodeWithText("1 app").assertIsDisplayed()

        composeRule.runOnIdle {
            libraryState.value = libraryState.value.copy(
                apps = listOf(second),
                appliedFilter = second.title,
            )
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(first.title).assertCountEquals(0)
        composeRule.onNodeWithText("1 app").assertIsDisplayed()

        composeRule.runOnIdle {
            libraryState.value = libraryState.value.copy(
                availableAppIds = setOf(second.databaseId),
            )
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("1 app").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(uiString(R.string.library_back)).assertCountEquals(0)
    }

    @Test
    fun viewAndSortActionsRemainExplicitCallbacks() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)

        composeRule.onNodeWithContentDescription(uiString(R.string.library_sort)).performClick()
        composeRule.onNodeWithText(uiString(R.string.pref_app_sort_vendor)).performClick()
        assertEquals(2, actions.sortIndex)
    }

    @Test
    fun moreTabExposesFlatLibraryActions() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Settings").performClick()

        assertEquals(1, actions.settingsCount)
        composeRule.onAllNodesWithText("Exit Emulator").assertCountEquals(0)
    }

    @Test
    fun moreExposesImportAppBundleCallback() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText(uiString(R.string.library_action_import_bundle)).performClick()

        assertEquals(1, actions.importCount)
    }

    @Test
    fun moreDoesNotExposeFormerInlineLibraryDisplayOptions() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                layout = LibraryLayout.Grid,
            ),
            actions = actions,
        )

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onAllNodesWithText("3:4").assertCountEquals(0)
        composeRule.onAllNodesWithText("Compact (4 dp)").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Hide titles in grid").assertCountEquals(0)
    }

    @Test
    fun destinationsAndDeferredFilterShellAreVisible() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)

        composeRule.onAllNodesWithText(uiString(R.string.library_filter_recently_opened)).assertCountEquals(1)
        composeRule.onAllNodesWithText(uiString(R.string.library_filter_recently_added)).assertCountEquals(1)
        composeRule.onAllNodesWithText(uiString(R.string.library_filter_favorites)).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(uiString(R.string.library_favorite_coming_soon))
            .assertIsDisplayed().assertIsNotEnabled()
        composeRule.onAllNodesWithContentDescription(uiString(R.string.library_remove_from_favorites_action))
            .assertCountEquals(0)

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText(uiString(R.string.library_collections_empty_message))
            .assertIsDisplayed()
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun gridUsesTilesWithoutFavoritePlaceholder() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                layout = LibraryLayout.Grid,
                apps = listOf(
                    LibraryAppUiItem(7, "Demo MIDlet", "Example Vendor", "1.0", null, true),
                    LibraryAppUiItem(8, "Second MIDlet", "Example Vendor", "1.0", null, true),
                ),
            ),
            actions = actions,
        )

        composeRule.onNodeWithText("Demo MIDlet").assertIsDisplayed()
        composeRule.onNodeWithText("Second MIDlet").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(uiString(R.string.library_favorite_coming_soon))
            .assertCountEquals(0)
    }

    @Test
    fun descriptionToggleOnlyAppearsWhenDescriptionOverflows() {
        val actions = RecordingLibraryActions()
        val longDescription = "A MIDlet description that is long enough to require an expandable preview. ".repeat(8)
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                apps = listOf(
                    LibraryAppUiItem(
                        id = 7,
                        title = "Long description",
                        author = "Example Vendor",
                        version = "1.0",
                        iconPath = null,
                        canReinstall = true,
                        description = longDescription,
                    ),
                    LibraryAppUiItem(
                        id = 8,
                        title = "Short description",
                        author = "Example Vendor",
                        version = "1.0",
                        iconPath = null,
                        canReinstall = true,
                        description = "Short description.",
                    ),
                ),
            ),
            actions = actions,
        )

        composeRule.onAllNodesWithTag("library_description_toggle").assertCountEquals(1)
        composeRule.onNodeWithTag("library_description_toggle").assertHasClickAction().performClick()
        assertEquals(null, actions.openedId)
        composeRule.onNodeWithText(uiString(R.string.library_description_less)).assertIsDisplayed()
        composeRule.onNodeWithTag("library_description_toggle").performClick()
        composeRule.onNodeWithText(uiString(R.string.library_description_more)).assertIsDisplayed()
    }

    @Test
    fun installFabFollowsListScrollDirection() {
        val actions = RecordingLibraryActions()
        val apps = (0..24).map { index ->
            LibraryAppUiItem(index, "Demo MIDlet $index", "Example Vendor", "1.0", null, true)
        }
        setLibraryContent(
            state = LibraryUiState(loading = false, apps = apps),
            actions = actions,
        )

        composeRule.onNodeWithContentDescription(uiString(R.string.install)).assertIsDisplayed()
        appViewport().performTouchInput {
            // Start above the navigation overlay; the full Lazy viewport includes its inset.
            swipe(Offset(width * 0.5f, height * 0.7f), Offset(width * 0.5f, height * 0.15f), 600)
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription(uiString(R.string.install)).assertCountEquals(0)
        composeRule.onAllNodesWithText(uiString(R.string.library_destination_apps)).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(uiString(R.string.app_name)).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(uiString(R.string.library_sort)).assertCountEquals(0)

        appViewport().performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.25f), Offset(width * 0.5f, height * 0.75f), 600)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(uiString(R.string.install)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_destination_apps)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_sort)).assertIsDisplayed()
    }

    @Test
    fun shortLibraryKeepsChromeVisibleWhenContentCannotScroll() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                apps = listOf(
                    LibraryAppUiItem(7, "Demo MIDlet", "Example Vendor", "1.0", null, true),
                ),
            ),
            actions = actions,
        )

        appViewport().performTouchInput {
            // Start above the navigation overlay; the full Lazy viewport includes its inset.
            swipe(Offset(width * 0.5f, height * 0.7f), Offset(width * 0.5f, height * 0.15f), 600)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(uiString(R.string.install)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_destination_apps)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_sort)).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_filter_all)).assertIsDisplayed()
    }

    @Test
    fun nearFittingLibraryKeepsFullChromeAfterUpwardGesture() {
        val actions = RecordingLibraryActions()
        val state = LibraryUiState(
            loading = false,
            databaseControlsReady = true,
            apps = (0..4).map { index ->
                LibraryAppUiItem(index, "Demo MIDlet $index", "Example Vendor", "1.0", null, true)
            },
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = state, actions = actions) }
            }
        }

        // Five rows nearly fill this compact viewport. Their small overflow must not consume
        // an entire header's height or leave it resting halfway collapsed after the gesture.
        val initialWordmark = composeRule.onNodeWithContentDescription(uiString(R.string.app_name))
            .fetchSemanticsNode().boundsInRoot
        appViewport().performTouchInput {
            // Start above the navigation overlay; the full Lazy viewport includes its inset.
            swipe(Offset(width * 0.5f, height * 0.7f), Offset(width * 0.5f, height * 0.15f), 600)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(uiString(R.string.app_name)).assertIsDisplayed()
        val settledWordmark = composeRule.onNodeWithContentDescription(uiString(R.string.app_name))
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Short overflow moved the wordmark", initialWordmark.top, settledWordmark.top, 1f)
        assertEquals("Short overflow clipped the wordmark", initialWordmark.bottom, settledWordmark.bottom, 1f)
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.library_sort)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_filter_all)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_filter_favorites)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.install)).assertIsDisplayed()
        navigationItem(R.string.library_destination_apps).assertIsDisplayed()
        navigationItem(R.string.library_destination_collections).assertIsDisplayed()
        navigationItem(R.string.library_destination_more).assertIsDisplayed()
    }

    @Test
    fun sortMenuExplainsCurrentDirection() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                apps = listOf(LibraryAppUiItem(7, "Demo MIDlet", "Example Vendor", "1.0", null, true)),
                sortVariant = Int.MIN_VALUE,
            ),
            actions = actions,
        )

        composeRule.onNodeWithContentDescription(uiString(R.string.library_sort)).performClick()
        composeRule.onNodeWithText(uiString(R.string.pref_app_sort_descending)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.pref_app_sort_name)).performClick()
        assertEquals(0, actions.sortIndex)
    }

    @Test
    fun hiddenGridTitlesKeepAnAccessibleAppLabel() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                layout = LibraryLayout.Grid,
                hideGridTitles = true,
                apps = listOf(
                    LibraryAppUiItem(7, "Demo MIDlet", "Example Vendor", "1.0", null, true),
                ),
            ),
            actions = actions,
        )

        composeRule.onNodeWithContentDescription("Demo MIDlet")
            .assertHasClickAction()
            .performClick()

        assertEquals(7, actions.openedId)
    }

    @Test
    fun aboutUsesCurrentProjectIdentityWithoutLegacyEmail() {
        composeRule.setContent {
            JLModPlusTheme {
                LibraryInformationDialog(
                    dialog = LibraryInfoDialog.About,
                    onDismiss = {},
                    onOpen = {},
                )
            }
        }

        composeRule.onNodeWithText("JL-Mod Plus").assertIsDisplayed()
        composeRule.onAllNodesWithText("j2me.forever@gmail.com").assertCountEquals(0)
        composeRule.onAllNodesWithText("Copyright 2020-2026 Yury Kharchenko").assertCountEquals(0)
    }


    @Test
    fun favoriteButtonAnnouncesStateChangingAction() {
        val actions = RecordingLibraryActions()
        val libraryState = mutableStateOf(
            LibraryUiState(
                loading = false,
                databaseControlsReady = true,
                apps = listOf(
                    LibraryAppUiItem(
                        id = 7,
                        title = "Demo MIDlet",
                        author = "Example Vendor",
                        version = "1.0",
                        iconPath = null,
                        canReinstall = true,
                        favorite = false,
                    ),
                ),
            ),
        )
        composeRule.setContent {
            JLModPlusTheme {
                LibraryScreen(state = libraryState.value, actions = actions)
            }
        }

        composeRule.onNodeWithContentDescription("Add to favorites").assertIsDisplayed()
        composeRule.runOnIdle {
            libraryState.value = libraryState.value.copy(
                apps = libraryState.value.apps.map { it.copy(favorite = true) },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Remove from favorites").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Add to favorites").assertCountEquals(0)
    }

    private fun navigationItem(labelRes: Int) = composeRule.onNode(
        hasClickAction() and (hasText(uiString(labelRes)) or hasContentDescription(uiString(labelRes))),
    )

    private fun appViewport() = composeRule.onAllNodes(
        hasScrollAction() and hasAnyDescendant(hasText("Demo MIDlet", substring = true)),
        useUnmergedTree = true,
    ).onLast()

    private fun visibleAppAnchor(): Pair<String, Float> {
        val viewport = appViewport().fetchSemanticsNode().boundsInRoot
        val first = checkNotNull(composeRule.onAllNodes(
            hasText("Demo MIDlet", substring = true), useUnmergedTree = true,
        ).fetchSemanticsNodes().filter { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0 && bounds.height > 0 &&
                bounds.top > viewport.top && bounds.bottom < viewport.bottom &&
                composeRule.onNode(
                    SemanticsMatcher("placed row ${node.id}") { it.id == node.id },
                    useUnmergedTree = true,
                ).isDisplayed()
        }.minByOrNull { it.boundsInRoot.top })
        return first.config[SemanticsProperties.Text].first { it.text.startsWith("Demo MIDlet") }.text to
            first.boundsInRoot.top
    }

    private fun setLibraryContent(
        state: LibraryUiState = LibraryUiState(
            loading = false,
            apps = listOf(
                LibraryAppUiItem(
                    id = 7,
                    title = "Demo MIDlet",
                    author = "Example Vendor",
                    version = "1.0",
                    iconPath = null,
                    canReinstall = true,
                ),
            ),
        ),
        actions: RecordingLibraryActions,
    ) {
        composeRule.setContent {
            JLModPlusTheme {
                LibraryScreen(state = state, actions = actions)
            }
        }
    }

    private class RecordingLibraryActions : LibraryActions {
        val searches = mutableListOf<String>()
        var layout: LibraryLayout? = null
        var iconRatio: LibraryIconRatio? = null
        var gridSpacing: LibraryGridSpacing? = null
        var hideGridTitles = false
        var sortIndex: Int? = null
        var installCount = 0
        var importCount = 0
        var openedId: Int? = null
        var renamed: Pair<Int, String>? = null
        var settingsCount = 0
        var retryCount = 0

        override fun onSearch(query: String) { searches += query }
        override fun onLayoutChange(layout: LibraryLayout) { this.layout = layout }
        override fun onIconRatioChange(iconRatio: LibraryIconRatio) { this.iconRatio = iconRatio }
        override fun onHideGridTitlesChange(hide: Boolean) { hideGridTitles = hide }
        override fun onGridSpacingChange(spacing: LibraryGridSpacing) { gridSpacing = spacing }
        override fun onSort(sortIndex: Int) { this.sortIndex = sortIndex }
        override fun onInstall() { installCount++ }
        override fun onImportAppBundle() { importCount++ }
        override fun onOpenApp(appId: Int) { openedId = appId }
        override fun onAddShortcut(appId: Int) = Unit
        override fun onRename(appId: Int, title: String) { renamed = appId to title }
        override fun onOpenAppSettings(appId: Int) = Unit
        override fun onReinstall(appId: Int) = Unit
        override fun onDelete(appId: Int) = Unit
        override fun onOpenSettings() { settingsCount++ }
        override fun onOpenProfiles() = Unit
        override fun onOpenCrashReports() = Unit
        override fun onSaveLog() = Unit
        override fun onRetryLibrary() { retryCount++ }
    }
}
