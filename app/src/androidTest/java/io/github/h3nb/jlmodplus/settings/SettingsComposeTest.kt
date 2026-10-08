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

package io.github.h3nb.jlmodplus.settings

import io.github.h3nb.jlmodplus.R
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

private fun uiString(resId: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

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
        val actions = RecordingSettingsActions()
        setSettingsContent(
            state = state,
            actions = actions,
            windowSize = DpSize(480.dp, 240.dp),
        )

        val languageSetting = hasText("App language") and hasClickAction()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(languageSetting)
        composeRule.onNode(languageSetting).performClick()

        val lastLanguage = hasText("Language option 20")
        composeRule.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(lastLanguage)
        composeRule.onNode(lastLanguage)
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf("language-19"), actions.changes)
    }

    @Test
    fun settingsExposePersistedSummariesAndSwitches() {
        setSettingsContent(actions = RecordingSettingsActions())

        scrollSettingsTo("Theme")
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        scrollSettingsTo("App language")
        composeRule.onNode(hasText("App language") and hasClickAction()).assertIsDisplayed()
        composeRule.onNodeWithText("Follow system settings").assertIsDisplayed()
        scrollSettingsTo("Keep screen on")
        composeRule.onNodeWithText("Keep screen on").assertIsDisplayed()
        scrollSettingsTo(uiString(R.string.pref_emulator_dir))
        composeRule.onNodeWithText(uiString(R.string.pref_emulator_dir)).assertIsDisplayed()
        composeRule.onNodeWithText("/data/jlmod").assertIsDisplayed()
        scrollSettingsTo(uiString(R.string.preset_manage))
        composeRule.onNode(hasText(uiString(R.string.preset_manage)) and hasClickAction())
            .assertIsDisplayed()
    }

    @Test
    fun settingsRouteOptionSwitchAndNavigationEvents() {
        val actions = RecordingSettingsActions()
        setSettingsContent(actions = actions)

        scrollSettingsTo("Theme")
        composeRule.onNodeWithText("Theme").performClick()
        composeRule.onNodeWithText("Light").performClick()
        scrollSettingsTo("App language")
        composeRule.onNode(hasText("App language") and hasClickAction()).performClick()
        composeRule.onNodeWithText("English").performClick()
        scrollSettingsTo("Keep screen on")
        composeRule.onNodeWithText("Keep screen on").performClick()
        scrollSettingsTo(uiString(R.string.preset_manage))
        composeRule.onNode(hasText(uiString(R.string.preset_manage)) and hasClickAction())
            .performClick()
        scrollSettingsTo(uiString(R.string.pref_emulator_dir))
        composeRule.onNodeWithText(uiString(R.string.pref_emulator_dir)).performClick()

        assertEquals(listOf("light", "en", "pref_wakelock_switch"), actions.changes)
        assertEquals(1, actions.profileClicks)
        assertEquals(1, actions.directoryClicks)
    }

    @Test
    fun settingsExposeAccentPaletteAndDispatchSelection() {
        val actions = RecordingSettingsActions()
        setSettingsContent(actions = actions)

        scrollSettingsTo(uiString(R.string.pref_accent_title))
        composeRule.onNodeWithText(uiString(R.string.pref_accent_title)).performClick()
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
                    title = uiString(R.string.pref_apps_view),
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
                    title = uiString(R.string.library_enhanced_icons_title),
                    summary = null,
                    checked = true,
                ),
            ),
        )
        setSettingsContent(state = state, actions = actions)

        scrollSettingsTo(uiString(R.string.pref_apps_view))
        composeRule.onNodeWithText(uiString(R.string.pref_apps_view)).performClick()
        composeRule.onNodeWithText("Grid").performClick()
        scrollSettingsTo(uiString(R.string.library_enhanced_icons_title))
        composeRule.onNodeWithText(uiString(R.string.library_enhanced_icons_title)).performClick()

        assertEquals(listOf("pref_apps_view=grid"), actions.libraryChoices)
        assertEquals(listOf("pref_apps_enhanced_icons=false"), actions.toggles)
    }

    @Test
    fun gridOnlyLibraryOptionsAreNotShownInListMode() {
        val gridState = sampleState().copy(
            libraryChoices = listOf(
                SettingsChoice(
                    key = "pref_apps_view",
                    title = uiString(R.string.pref_apps_view),
                    selected = SettingsOption("grid", "Grid"),
                    options = listOf(
                        SettingsOption("list", "List"),
                        SettingsOption("grid", "Grid"),
                    ),
                ),
                SettingsChoice(
                    key = "pref_apps_grid_spacing",
                    title = uiString(R.string.library_grid_spacing_title),
                    selected = SettingsOption("standard", "Standard (8 dp)"),
                    options = listOf(SettingsOption("standard", "Standard (8 dp)")),
                ),
            ),
            librarySwitches = listOf(
                SettingsSwitch("pref_apps_enhanced_icons", uiString(R.string.library_enhanced_icons_title), null, true),
                SettingsSwitch("pref_apps_hide_grid_titles", uiString(R.string.library_hide_grid_titles), null, false),
            ),
        )
        setSettingsContent(state = gridState, actions = RecordingSettingsActions())

        scrollSettingsTo(uiString(R.string.library_grid_spacing_title))
        composeRule.onNodeWithText(uiString(R.string.library_grid_spacing_title)).assertIsDisplayed()
        scrollSettingsTo(uiString(R.string.library_hide_grid_titles))
        composeRule.onNodeWithText(uiString(R.string.library_hide_grid_titles)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_show_list_description)).assertDoesNotExist()
    }

    @Test
    fun listOnlyLibraryOptionsAreNotShownInGridMode() {
        val listState = sampleState().copy(
            libraryChoices = listOf(
                SettingsChoice(
                    key = "pref_apps_view",
                    title = uiString(R.string.pref_apps_view),
                    selected = SettingsOption("list", "List"),
                    options = listOf(
                        SettingsOption("list", "List"),
                        SettingsOption("grid", "Grid"),
                    ),
                ),
            ),
            librarySwitches = listOf(
                SettingsSwitch("pref_apps_enhanced_icons", uiString(R.string.library_enhanced_icons_title), null, true),
                SettingsSwitch(
                    "pref_apps_show_list_description",
                    uiString(R.string.library_show_list_description),
                    null,
                    true,
                ),
            ),
        )
        setSettingsContent(state = listState, actions = RecordingSettingsActions())

        scrollSettingsTo(uiString(R.string.library_show_list_description))
        composeRule.onNodeWithText(uiString(R.string.library_show_list_description)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.library_grid_spacing_title)).assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.library_hide_grid_titles)).assertDoesNotExist()
    }

    @Test
    fun conditionalLibraryRowsKeepTheVisibleStorageRowAnchored() {
        val viewChoice = SettingsChoice(
            key = "pref_apps_view",
            title = uiString(R.string.pref_apps_view),
            selected = SettingsOption("list", "List"),
            options = listOf(
                SettingsOption("list", "List"),
                SettingsOption("grid", "Grid"),
            ),
        )
        val state = mutableStateOf(
            sampleState().copy(
                libraryChoices = listOf(viewChoice),
                librarySwitches = listOf(
                    SettingsSwitch(
                        "pref_apps_show_list_description",
                        uiString(R.string.library_show_list_description),
                        null,
                        true,
                    ),
                ),
                // Keep enough content below Storage to scroll that row to the leading edge.
                experimentalSwitches = List(20) { index ->
                    SettingsSwitch("experimental-$index", "Experimental option $index", null, false)
                },
            ),
        )
        val actions = RecordingSettingsActions()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp)),
            ) {
                JLModPlusTheme {
                    SettingsScreen(state = state.value, actions = actions)
                }
            }
        }

        val storageTitle = uiString(R.string.pref_emulator_dir)
        scrollSettingsTo(storageTitle)
        val storageRow = composeRule.onNode(hasText(storageTitle) and hasClickAction())
        storageRow.assertIsDisplayed()
        val originalTop = storageRow.fetchSemanticsNode().boundsInRoot.top

        composeRule.runOnIdle {
            state.value = state.value.copy(
                libraryChoices = listOf(
                    viewChoice.copy(selected = SettingsOption("grid", "Grid")),
                    SettingsChoice(
                        key = "pref_apps_grid_spacing",
                        title = uiString(R.string.library_grid_spacing_title),
                        selected = SettingsOption("standard", "Standard (8 dp)"),
                        options = listOf(SettingsOption("standard", "Standard (8 dp)")),
                    ),
                ),
                librarySwitches = listOf(
                    SettingsSwitch(
                        "pref_apps_hide_grid_titles",
                        uiString(R.string.library_hide_grid_titles),
                        null,
                        false,
                    ),
                ),
            )
        }

        storageRow.assertIsDisplayed()
        // Lazy scroll offsets are rounded to whole pixels when preceding rows change.
        assertEquals(originalTop, storageRow.fetchSemanticsNode().boundsInRoot.top, 1f)
        storageRow.performClick()
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
        accent = SettingsOption("blue", uiString(R.string.pref_accent_blue)),
        accents = listOf(
            SettingsOption("blue", uiString(R.string.pref_accent_blue)),
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

    private fun scrollSettingsTo(title: String) {
        val rowMatcher = hasText(title) and hasClickAction()
        val list = composeRule.onNode(hasScrollAction())
        list.performScrollToNode(rowMatcher)
        // Lazy scroll semantics include the area drawn underneath the pinned app bar.
        // Keep touch targets below its Back button instead of clicking an obscured row.
        val backBounds = composeRule.onNode(hasContentDescription(uiString(R.string.action_back)))
            .fetchSemanticsNode().boundsInRoot
        val safeTop = backBounds.bottom + backBounds.height / 2f
        val rowTop = composeRule.onNode(rowMatcher).fetchSemanticsNode().boundsInRoot.top
        if (rowTop < safeTop) {
            list.performSemanticsAction(SemanticsActions.ScrollBy) {
                it(0f, rowTop - safeTop)
            }
        }
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
