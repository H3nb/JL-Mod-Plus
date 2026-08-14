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
        composeRule.onAllNodesWithText("Light").get(1).performClick()
        composeRule.onNodeWithText("Language").performClick()
        composeRule.onAllNodesWithText("English").get(1).performClick()
        composeRule.onNodeWithText("Keep screen on").performClick()
        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("Working directory").performClick()

        assertEquals(listOf("light", "en", "pref_wakelock_switch"), actions.changes)
        assertEquals(1, actions.profileClicks)
        assertEquals(1, actions.directoryClicks)
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
        switches = listOf(
            SettingsSwitch(
                key = "pref_wakelock_switch",
                title = "Keep screen on",
                summary = null,
                checked = false,
            ),
        ),
        showProfiles = true,
        workingDirectory = "/data/jlmod",
    )

    private class RecordingSettingsActions : SettingsActions {
        val changes = mutableListOf<String>()
        var profileClicks = 0
        var directoryClicks = 0

        override fun onBack() = Unit

        override fun onThemeChanged(value: String) {
            changes += value
        }

        override fun onLanguageChanged(value: String) {
            changes += value
        }

        override fun onToggle(key: String, checked: Boolean) {
            changes += key
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
