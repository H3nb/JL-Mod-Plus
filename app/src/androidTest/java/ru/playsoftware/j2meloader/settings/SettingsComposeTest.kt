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

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class SettingsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsExposePersistedSummariesAndSwitches() {
        composeRule.setContent {
            JLModPlusTheme {
                SettingsScreen(
                    state = sampleState(),
                    actions = RecordingSettingsActions(),
                )
            }
        }

        composeRule.onNodeWithText("Theme").assertExists()
        composeRule.onNodeWithText("Dark").assertExists()
        composeRule.onNodeWithText("Language").assertExists()
        composeRule.onNodeWithText("Working directory").assertExists()
        composeRule.onNodeWithText("/data/jlmod").assertExists()
        composeRule.onNodeWithText("Experimental/temporary options").assertExists()
    }

    @Test
    fun settingsRouteOptionSwitchAndNavigationEvents() {
        val actions = RecordingSettingsActions()
        composeRule.setContent {
            JLModPlusTheme {
                SettingsScreen(state = sampleState(), actions = actions)
            }
        }

        composeRule.onNodeWithText("Theme").performClick()
        composeRule.onNodeWithText("Light").performClick()
        composeRule.onNodeWithText("Language").performClick()
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("Keep screen on").performClick()
        composeRule.onNodeWithText("Profiles").assertDoesNotExist()
        composeRule.onNodeWithText("Working directory").performClick()

        assertEquals(listOf("light", "en", "pref_wakelock_switch"), actions.changes)
        assertEquals(0, actions.profileClicks)
        assertEquals(1, actions.directoryClicks)
    }

    @Test
    fun settingsExposeAccentPaletteAndDispatchSelection() {
        val actions = RecordingSettingsActions()
        composeRule.setContent {
            JLModPlusTheme {
                SettingsScreen(state = sampleState(), actions = actions)
            }
        }

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
        composeRule.setContent {
            JLModPlusTheme {
                SettingsScreen(state = state, actions = actions)
            }
        }

        composeRule.onNodeWithText("Library View").performClick()
        composeRule.onNodeWithText("Grid").performClick()
        composeRule.onNodeWithText("Enhanced Icons").performClick()

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
        composeRule.setContent {
            JLModPlusTheme {
                SettingsScreen(state = gridState, actions = RecordingSettingsActions())
            }
        }

        composeRule.onNodeWithText("Grid Spacing").assertExists()
        composeRule.onNodeWithText("Hide MIDlet Titles").assertExists()
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
        composeRule.setContent {
            JLModPlusTheme {
                SettingsScreen(state = listState, actions = RecordingSettingsActions())
            }
        }

        composeRule.onNodeWithText("Show MIDlet Descriptions").assertExists()
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
