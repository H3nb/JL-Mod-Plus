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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.mainClock.advanceTimeByFrame()
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
        composeRule.onNodeWithContentDescription("install").performClick()
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
        composeRule.onNodeWithContentDescription("view").performClick()
        assertEquals(LibraryLayout.List, actions.layout)

        composeRule.onNodeWithContentDescription("App sort order").performClick()
        composeRule.onNodeWithText("Vendor").performClick()
        assertEquals(2, actions.sortIndex)
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
        var sortIndex: Int? = null
        var installCount = 0
        var openedId: Int? = null
        var renamed: Pair<Int, String>? = null

        override fun onSearch(query: String) { searches += query }
        override fun onLayoutChange(layout: LibraryLayout) { this.layout = layout }
        override fun onSort(sortIndex: Int) { this.sortIndex = sortIndex }
        override fun onInstall() { installCount++ }
        override fun onOpenApp(appId: Int) { openedId = appId }
        override fun onAddShortcut(appId: Int) = Unit
        override fun onRename(appId: Int, title: String) { renamed = appId to title }
        override fun onOpenAppSettings(appId: Int) = Unit
        override fun onReinstall(appId: Int) = Unit
        override fun onDelete(appId: Int) = Unit
        override fun onOpenSettings() = Unit
        override fun onOpenProfiles() = Unit
        override fun onOpenCrashReports() = Unit
        override fun onSaveLog() = Unit
        override fun onExit() = Unit
    }
}
