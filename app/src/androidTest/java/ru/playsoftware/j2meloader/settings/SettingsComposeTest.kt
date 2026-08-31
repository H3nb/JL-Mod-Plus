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

package ru.playsoftware.j2meloader.settings

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SettingsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactHeightLanguageDialogUsesAdaptiveScrollableBounds() {
        val state = sampleState().copy(
            languages = List(20) { index ->
                SettingsOption("language-$index", "Language option ${index + 1}")
            },
        )
        setSettingsContent(
            state = state,
            actions = RecordingSettingsActions(),
            windowSize = DpSize(480.dp, 240.dp),
        )

        composeRule.onNodeWithText("Language").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("Swipe to continue")
                    .fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithContentDescription("Swipe to continue").assertIsDisplayed()
    }

    @Test
    fun settingsExposePersistedSummariesAndSwitches() {
        setSettingsContent(actions = RecordingSettingsActions())

        composeRule.onNodeWithText("Theme").assertExists()
        composeRule.onNodeWithText("Dark").assertExists()
        composeRule.onNodeWithText("Language").assertExists()
        scrollSettingsToIndex(1)
        composeRule.onNodeWithText("Keep screen on").performScrollTo().assertIsDisplayed()
        scrollSettingsToIndex(2)
        composeRule.onNodeWithText("Working Directory").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("/data/jlmod").assertIsDisplayed()
        scrollSettingsToIndex(3)
        composeRule.onNode(hasText("Profiles") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun settingsRouteOptionSwitchAndNavigationEvents() {
        val actions = RecordingSettingsActions()
        setSettingsContent(actions = actions)

        composeRule.onNodeWithText("Theme").performClick()
        composeRule.onNodeWithText("Light").performClick()
        scrollSettingsToIndex(0)
        composeRule.onNodeWithText("Language").performScrollTo().performClick()
        composeRule.onNodeWithText("English").performClick()
        scrollSettingsToIndex(1)
        composeRule.onNodeWithText("Keep screen on").performScrollTo().performClick()
        scrollSettingsToIndex(3)
        composeRule.onNode(hasText("Profiles") and hasClickAction())
            .performScrollTo()
            .performClick()
        scrollSettingsToIndex(2)
        composeRule.onNodeWithText("Working Directory").performScrollTo().performClick()

        assertEquals(listOf("light", "en", "pref_wakelock_switch"), actions.changes)
        assertEquals(1, actions.profileClicks)
        assertEquals(1, actions.directoryClicks)
    }

    @Test
    fun settingsExposeAccentPaletteAndDispatchSelection() {
        val actions = RecordingSettingsActions()
        setSettingsContent(actions = actions)

        composeRule.onNodeWithText("Accent Color").performClick()
        composeRule.onNodeWithText("Teal").performClick()

        assertEquals(listOf("teal"), actions.accents)
    }

    @Test
    fun libraryAppearanceOptionsStayInGlobalSettings() {
        val actions = RecordingSettingsActions()
        val state = sampleState().copy(
            libraryChoices = listOf(
                SettingsChoice(
                    key = "pref_apps_view",
                    title = "Library View",
                    selected = SettingsOption("list", "List"),
                    options = listOf(
                        SettingsOption("list", "List"),
                        SettingsOption("grid", "Grid"),
                    ),
                ),
            ),
            librarySwitches = listOf(
                SettingsSwitch(
                    key = "pref_apps_enhanced_icons",
                    title = "Enhanced Icons",
                    summary = null,
                    checked = true,
                ),
            ),
        )
        setSettingsContent(state = state, actions = actions)

        scrollSettingsToIndex(1)
        composeRule.onNodeWithText("Library View").performScrollTo().performClick()
        composeRule.onNodeWithText("Grid").performClick()
        scrollSettingsToIndex(1)
        composeRule.onNodeWithText("Enhanced Icons").performScrollTo().performClick()

        assertEquals(listOf("pref_apps_view=grid"), actions.libraryChoices)
        assertEquals(listOf("pref_apps_enhanced_icons=false"), actions.toggles)
    }

    @Test
    fun gridOnlyLibraryOptionsAreNotShownInListMode() {
        val gridState = sampleState().copy(
            libraryChoices = listOf(
                SettingsChoice(
                    key = "pref_apps_view",
                    title = "Library View",
                    selected = SettingsOption("grid", "Grid"),
                    options = listOf(
                        SettingsOption("list", "List"),
                        SettingsOption("grid", "Grid"),
                    ),
                ),
                SettingsChoice(
                    key = "pref_apps_grid_spacing",
                    title = "Grid Spacing",
                    selected = SettingsOption("standard", "Standard (8 dp)"),
                    options = listOf(SettingsOption("standard", "Standard (8 dp)")),
                ),
            ),
            librarySwitches = listOf(
                SettingsSwitch("pref_apps_enhanced_icons", "Enhanced Icons", null, true),
                SettingsSwitch("pref_apps_hide_grid_titles", "Hide MIDlet Titles", null, false),
            ),
        )
        setSettingsContent(state = gridState, actions = RecordingSettingsActions())

        scrollSettingsToIndex(1)
        composeRule.onNodeWithText("Grid Spacing").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Hide MIDlet Titles").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Show MIDlet Descriptions").assertDoesNotExist()
    }

    @Test
    fun listOnlyLibraryOptionsAreNotShownInGridMode() {
        val listState = sampleState().copy(
            libraryChoices = listOf(
                SettingsChoice(
                    key = "pref_apps_view",
                    title = "Library View",
                    selected = SettingsOption("list", "List"),
                    options = listOf(
                        SettingsOption("list", "List"),
                        SettingsOption("grid", "Grid"),
                    ),
                ),
            ),
            librarySwitches = listOf(
                SettingsSwitch("pref_apps_enhanced_icons", "Enhanced Icons", null, true),
                SettingsSwitch(
                    "pref_apps_show_list_description",
                    "Show MIDlet Descriptions",
                    null,
                    true,
                ),
            ),
        )
        setSettingsContent(state = listState, actions = RecordingSettingsActions())

        scrollSettingsToIndex(1)
        composeRule.onNodeWithText("Show MIDlet Descriptions").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Grid Spacing").assertDoesNotExist()
        composeRule.onNodeWithText("Hide MIDlet Titles").assertDoesNotExist()
    }

    private fun sampleState() = SettingsUiState(
        theme = SettingsOption("dark", "Dark"),
        themes = listOf(
            SettingsOption("light", "Light"),
            SettingsOption("dark", "Dark"),
        ),
        language = SettingsOption("", "Follow system settings"),
        languages = listOf(
            SettingsOption("", "Follow system settings"),
            SettingsOption("en", "English"),
        ),
        accent = SettingsOption("blue", "Default Blue"),
        accents = listOf(
            SettingsOption("blue", "Default Blue"),
            SettingsOption("teal", "Teal"),
        ),
        switches = listOf(
            SettingsSwitch(
                key = "pref_wakelock_switch",
                title = "Keep screen on",
                summary = null,
                checked = false,
            ),
        ),
        experimentalSwitches = emptyList(),
        showProfiles = true,
        workingDirectory = "/data/jlmod",
    )

    private fun scrollSettingsToIndex(index: Int) {
        composeRule
            .onNode(hasScrollAction())
            .performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    private fun setSettingsContent(
        state: SettingsUiState = sampleState(),
        actions: SettingsActions,
        windowSize: DpSize = DpSize(480.dp, 240.dp),
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(windowSize),
            ) {
                JLModPlusTheme {
                    SettingsScreen(state = state, actions = actions)
                }
            }
        }
    }

    private class RecordingSettingsActions : SettingsActions {
        val changes = mutableListOf<String>()
        val accents = mutableListOf<String>()
        val libraryChoices = mutableListOf<String>()
        val toggles = mutableListOf<String>()
        var profileClicks = 0
        var directoryClicks = 0

        override fun onBack() = Unit

        override fun onThemeChanged(value: String) {
            changes += value
        }

        override fun onAccentChanged(value: String) {
            accents += value
        }

        override fun onLanguageChanged(value: String) {
            changes += value
        }

        override fun onToggle(key: String, checked: Boolean) {
            changes += key
            toggles += "$key=$checked"
        }

        override fun onLibraryChoiceChanged(key: String, value: String) {
            libraryChoices += "$key=$value"
        }

        override fun onOpenProfiles() {
            profileClicks++
        }

        override fun onChooseDirectory() {
            directoryClicks++
        }

        override fun onDismissDirectoryError() = Unit
    }
}
