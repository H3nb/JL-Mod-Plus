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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class LibraryComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchIsLowercasedAndDebouncedForThreeHundredMilliseconds() {
        val actions = RecordingLibraryActions()
        composeRule.mainClock.autoAdvance = false
        setLibraryContent(actions = actions)
        composeRule.mainClock.advanceTimeBy(301)
        composeRule.waitForIdle()
        actions.searches.clear()

        composeRule.onNode(hasSetTextAction()).performTextInput("Demo")
        composeRule.mainClock.advanceTimeBy(299)
        composeRule.waitForIdle()
        assertTrue(actions.searches.isEmpty())

        composeRule.mainClock.advanceTimeBy(2)
        composeRule.waitForIdle()
        assertEquals(listOf("demo"), actions.searches)
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
    fun viewAndSortActionsRemainExplicitCallbacks() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)
        composeRule.onNodeWithText("Options").performClick()
        composeRule.onNodeWithText("Grid").performClick()
        assertEquals(LibraryLayout.Grid, actions.layout)

        composeRule.onNodeWithText("Apps").performClick()
        composeRule.onNodeWithContentDescription("App Sort Order").performClick()
        composeRule.onNodeWithText("Vendor").performClick()
        assertEquals(2, actions.sortIndex)
    }

    @Test
    fun optionsTabExposesTheFormerOverflowActions() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)

        composeRule.onNodeWithText("Options").performClick()
        composeRule.onNodeWithText("Settings").performClick()

        assertEquals(1, actions.settingsCount)
    }

    @Test
    fun gridOptionsExposeTitleVisibilitySpacingAndIconRatio() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                layout = LibraryLayout.Grid,
            ),
            actions = actions,
        )

        composeRule.onNodeWithText("Options").performClick()
        composeRule.onNodeWithText("3:4").performClick()
        composeRule.onNodeWithText("Compact (4 dp)").performClick()
        composeRule.onNodeWithContentDescription("Hide titles in grid").performClick()

        assertEquals(LibraryIconRatio.Portrait, actions.iconRatio)
        assertEquals(LibraryGridSpacing.Compact, actions.gridSpacing)
        assertTrue(actions.hideGridTitles)
    }

    @Test
    fun destinationsAndDeferredFilterShellAreVisible() {
        val actions = RecordingLibraryActions()
        setLibraryContent(actions = actions)

        composeRule.onAllNodesWithText("Recently opened").assertCountEquals(1)
        composeRule.onAllNodesWithText("Recently added").assertCountEquals(1)
        composeRule.onAllNodesWithText("Favorites").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Favorite (coming soon)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Favorite (coming soon)").performClick()
        composeRule.onNodeWithContentDescription("Remove favorite (coming soon)").assertIsDisplayed()

        composeRule.onNodeWithText("Collections").performClick()
        composeRule.onNodeWithText("Collections and folders will be available in a future update.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Options").performClick()
        composeRule.onNodeWithText("List").assertIsDisplayed()
        composeRule.onNodeWithText("Grid").assertIsDisplayed()
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
        composeRule.onAllNodesWithContentDescription("Favorite (coming soon)").assertCountEquals(0)
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

        composeRule.onAllNodesWithContentDescription("Expand description").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Expand description").performClick()
        composeRule.onNodeWithContentDescription("Collapse description").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Collapse description").performClick()
        composeRule.onNodeWithContentDescription("Expand description").assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription("Install").assertIsDisplayed()
        composeRule.onNodeWithText("Demo MIDlet 0").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("Install").assertCountEquals(0)
        composeRule.onAllNodesWithText("Apps").assertCountEquals(0)
        composeRule.onAllNodesWithText("JL-Mod Plus Debug").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("App Sort Order").assertCountEquals(0)

        composeRule.onNodeWithText("Demo MIDlet 1").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Install").assertIsDisplayed()
        composeRule.onNodeWithText("Apps").assertIsDisplayed()
        composeRule.onNodeWithText("JL-Mod Plus Debug").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("App Sort Order").assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription("App Sort Order").performClick()
        composeRule.onNodeWithText("Descending").assertIsDisplayed()
        composeRule.onNodeWithText("Name").performClick()
        assertEquals(0, actions.sortIndex)
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
        var openedId: Int? = null
        var renamed: Pair<Int, String>? = null
        var settingsCount = 0

        override fun onSearch(query: String) { searches += query }
        override fun onLayoutChange(layout: LibraryLayout) { this.layout = layout }
        override fun onIconRatioChange(iconRatio: LibraryIconRatio) { this.iconRatio = iconRatio }
        override fun onHideGridTitlesChange(hide: Boolean) { hideGridTitles = hide }
        override fun onGridSpacingChange(spacing: LibraryGridSpacing) { gridSpacing = spacing }
        override fun onSort(sortIndex: Int) { this.sortIndex = sortIndex }
        override fun onInstall() { installCount++ }
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
        override fun onExit() = Unit
    }
}
