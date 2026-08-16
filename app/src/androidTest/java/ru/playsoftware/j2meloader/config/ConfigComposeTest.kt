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

package ru.playsoftware.j2meloader.config

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.config.model.Size
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class ConfigComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun configRendersGeneralAndAdaptiveDestinations() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents())
            }
        }

        composeRule.onNodeWithText("Custom").assertExists()
        composeRule.onNodeWithText("Screen size").assertExists()
        composeRule.onNodeWithText("Screen Orientation").assertExists()
        composeRule.onNodeWithText("Scale Type").assertExists()
        composeRule.onNodeWithText("Scale (%)").assertExists()
        composeRule.onNodeWithContentDescription("Start").assertExists()

        composeRule.onNodeWithContentDescription("Graphics").performClick()
        composeRule.onNodeWithText("Screen Options").assertExists()
        composeRule.onNodeWithText("Font Options").assertExists()
        composeRule.onNodeWithText("Screen size").assertDoesNotExist()
        composeRule.onNodeWithText("Screen Orientation").assertDoesNotExist()
        composeRule.onNodeWithText("Scale Type").assertDoesNotExist()
        composeRule.onNodeWithText("Scale (%)").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText("Input Devices").assertExists()
        composeRule.onNodeWithText("Audio").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Media").performClick()
        composeRule.onNodeWithText("Multimedia settings will be added here in a future update.").assertExists()

        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText("System Properties").assertExists()
        composeRule.onNodeWithText("Reset & data").assertExists()
        composeRule.onNodeWithText("Advanced settings").assertDoesNotExist()
    }

    @Test
    fun profileEditorKeepsGeneralSettingsWithoutProfileWorkflow() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), isProfile = true)
            }
        }

        composeRule.onNodeWithContentDescription("General").assertExists()
        composeRule.onNodeWithText("Screen size").assertExists()
        composeRule.onNodeWithText("Touch Input").assertExists()
        composeRule.onNodeWithContentDescription("Start").assertDoesNotExist()
        composeRule.onNodeWithText("Use profile").assertDoesNotExist()
        composeRule.onNodeWithText("Save as profile").assertDoesNotExist()
    }

    @Test
    fun configDraftChangesStayInStateAndEmitEvents() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Graphics)
            }
        }

        composeRule.onNodeWithText("Filter").performClick()
        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithContentDescription("General").performClick()
        composeRule.onNodeWithText("Touch Input").performClick()

        assertTrue(events.lastForm?.screenFilter == true)
        assertFalse(events.lastForm?.touchInput == true)
        assertEquals(2, events.formChanges)
    }

    @Test
    fun generalProfileActionsUseTheExistingHostFlows() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events)
            }
        }

        composeRule.onNodeWithText("Use profile").performClick()
        composeRule.onNodeWithText("Save as profile").performClick()
        composeRule.onNodeWithText("Manage templates").assertExists()

        assertEquals(1, events.useProfileCalls)
        assertEquals(1, events.saveAsProfileCalls)
    }

    @Test
    fun activeProfileShowsChangeWithoutSaveAs() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            ConfigUiState.ProfileStatus.active("Nokia Classic", "Nokia Classic"),
        )
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(state, events)
            }
        }

        composeRule.onNodeWithText("Change profile").performClick()
        composeRule.onNodeWithText("Save as profile").assertDoesNotExist()
        composeRule.onNodeWithText("Default for new games: Nokia Classic").assertDoesNotExist()

        assertEquals(1, events.useProfileCalls)
    }

    @Test
    fun builtInDefaultProfileIsNotShownAsCustom() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            ConfigUiState.ProfileStatus.builtInDefault(null),
        )
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(state, RecordingConfigEvents())
            }
        }

        composeRule.onNodeWithText("Default").assertExists()
        composeRule.onNodeWithText("Built-in emulator settings").assertExists()
        composeRule.onNodeWithText("Custom").assertDoesNotExist()
        composeRule.onNodeWithText("Save as profile").assertDoesNotExist()
        composeRule.onNodeWithText("Change profile").assertExists()
    }

    @Test
    fun destructiveActionsLiveInSystemAndRequireExplicitConfirmation() {
        val menuActions = RecordingMenuActions()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), menuActions = menuActions)
            }
        }

        composeRule.onNodeWithText("Reset all settings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("System").performClick()

        composeRule.onNodeWithText("Reset all settings").performClick()
        composeRule.onNodeWithText(
            "Reset all emulator settings for this game to their defaults? Game data and the custom button layout will not be deleted.",
        ).assertExists()
        composeRule.onNodeWithText("Reset all settings", useUnmergedTree = true).performClick()
        assertEquals(1, menuActions.resetSettingsCalls)

        composeRule.onNodeWithText("Delete game data").performClick()
        composeRule.onNodeWithText(
            "Permanently delete all saves and data created by this game? Emulator settings will not be deleted.",
        ).assertExists()
        composeRule.onNodeWithText("Delete game data", useUnmergedTree = true).performClick()
        assertEquals(1, menuActions.clearDataCalls)

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText("Reset Keylayout").performClick()
        composeRule.onNodeWithText("Reset the button layout to its default?").assertExists()
    }

    @Test
    fun profileResetUsesProfileSpecificConfirmation() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    sampleState(),
                    RecordingConfigEvents(),
                    isProfile = true,
                    initialDestination = ConfigDestination.System,
                    menuActions = RecordingMenuActions(),
                )
            }
        }

        composeRule.onNodeWithText("Delete game data").assertDoesNotExist()
        composeRule.onNodeWithText("Reset all settings").performClick()
        composeRule.onNodeWithText(
            "Reset all settings in this profile to their defaults? The profile can be edited again before leaving this page.",
        ).assertExists()
    }

    @Test
    fun screenPresetSelectionCommitsImmediatelyAndSwapIsDirect() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events)
            }
        }

        composeRule.onNodeWithText("Screen size").performClick()
        composeRule.onNodeWithText("360 x 640").performClick()
        assertEquals("360", events.lastForm?.screenWidth)
        assertEquals("640", events.lastForm?.screenHeight)
        composeRule.onNodeWithText("Select").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Swap width and height").performClick()
        assertEquals("320", events.lastForm?.screenWidth)
        assertEquals("240", events.lastForm?.screenHeight)
    }

    @Test
    fun configurationDropdownsExposeTheirOptionsAndUpdateTheDraft() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.General)
            }
        }

        composeRule.onNodeWithText("Screen Orientation").performClick()
        composeRule.onNodeWithText("Landscape").performClick()

        assertEquals(3, events.lastForm?.orientation)
    }

    @Test
    fun sliderValuesCommitFromTheirDialogs() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Controls)
            }
        }

        composeRule.onNodeWithText("Advanced settings").performClick()
        composeRule.onNodeWithText("64").performClick()
        composeRule.onNodeWithText("Opacity").assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("128")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(128, events.lastForm?.vkAlpha)
    }

    @Test
    fun colorPickerConfirmsCurrentValueWithoutExternalDependency() {
        var picked: String? = null
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    state = sampleState(),
                    events = RecordingConfigEvents(),
                    initialDestination = ConfigDestination.Graphics,
                    colorPicker = ColorPickerRequest(
                        ConfigFormEvents.ColorField.SCREEN_BACKGROUND,
                        "D0D0D0",
                    ),
                    onColorPicked = { _, value -> picked = value },
                )
            }
        }

        composeRule.onNodeWithText("OK").performClick()

        assertEquals("D0D0D0", picked)
    }

    @Test
    fun colorPickerRejectsIncompleteHexValue() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigColorPickerDialog(
                    initialHex = "D0D0D0",
                    onDismissRequest = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText("D0D0D0").performTextReplacement("ABC")

        composeRule.onNodeWithText("Enter exactly six hexadecimal digits.").assertExists()
        composeRule.onNodeWithText("OK").assertIsNotEnabled()
    }

    @Test
    fun colorRowsOpenTheDedicatedPicker() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Graphics)
            }
        }

        composeRule.onNodeWithText("Background").performClick()

        assertEquals(ConfigFormEvents.ColorField.SCREEN_BACKGROUND, events.colorPickerField)
    }

    @Test
    fun customScreenPresetCanBeRemovedFromPresetDialog() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events)
            }
        }

        composeRule.onNodeWithText("Screen size").performClick()
        composeRule.onNodeWithContentDescription("Remove Screen Preset").performClick()

        assertEquals(Size(360, 640), events.removed)
    }

    @Test
    fun systemPropertiesEditInlineAndHideDelayUsesMilliseconds() {
        val events = RecordingConfigEvents()
        val baseState = sampleState()
        val state = ConfigUiState(
            baseState.form.toBuilder().vkHideDelay("250").build(),
            baseState.screenPresets,
            baseState.fontPresets,
            baseState.skins,
            baseState.soundBanks,
            baseState.shaders,
            baseState.removableScreenPresets,
        )
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(state, events, initialDestination = ConfigDestination.Controls)
            }
        }

        composeRule.onNodeWithText("Advanced settings").performClick()
        composeRule.onNodeWithText("ms").assertExists()

        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("microedition.platform: updated\n")
        assertEquals("microedition.platform: updated\n", events.lastForm?.systemProperties)
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
    }

    private fun sampleState(): ConfigUiState {
        val form = ConfigFormState.builder()
            .screenWidth("240")
            .screenHeight("320")
            .screenBackground("D0D0D0")
            .screenScaleRatio("100")
            .screenPadding("0")
            .fpsLimit("")
            .fontSizeSmall("18")
            .fontSizeMedium("22")
            .fontSizeLarge("26")
            .vkHideDelay("")
            .vkBackground("D0D0D0")
            .vkForeground("000080")
            .vkSelectedBackground("000080")
            .vkSelectedForeground("FFFFFF")
            .vkOutline("FFFFFF")
            .systemProperties("microedition.platform: test\n")
            .showKeyboard(true)
            .touchInput(true)
            .vkAlpha(64)
            .graphicsMode(1)
            .build()
        return ConfigUiState(
            form,
            listOf(Size(240, 320), Size(360, 640)),
            listOf(ConfigUiState.FontPreset("240 x 320", 18, 22, 26)),
            listOf("Not set"),
            listOf("Android (default)"),
            emptyList(),
            listOf(Size(360, 640)),
        )
    }

    private class RecordingConfigEvents : ConfigFormEvents {
        var formChanges = 0
        var lastForm: ConfigFormState? = null

        override fun onFormChanged(state: ConfigFormState) {
            formChanges++
            lastForm = state
        }

        override fun onAddResolutionPreset(size: Size) = Unit
        override fun onRemoveResolutionPreset(size: Size) {
            removed = size
        }
        override fun onColorPicker(field: ConfigFormEvents.ColorField) {
            colorPickerField = field
        }
        override fun onColorPicked(field: ConfigFormEvents.ColorField, value: String) = Unit
        override fun onKeyMappings() = Unit
        override fun onEncodingPicker() = Unit
        override fun onShaderTuning() = Unit

        var removed: Size? = null
        var colorPickerField: ConfigFormEvents.ColorField? = null
        var useProfileCalls = 0
        var saveAsProfileCalls = 0

        override fun onUseProfile() {
            useProfileCalls++
        }

        override fun onSaveAsProfile() {
            saveAsProfileCalls++
        }
    }

    private class RecordingMenuActions : ConfigMenuActions {
        var clearDataCalls = 0
        var resetSettingsCalls = 0
        var resetLayoutCalls = 0

        override fun onBack() = Unit
        override fun onStart() = Unit
        override fun onClearData() {
            clearDataCalls++
        }
        override fun onResetSettings() {
            resetSettingsCalls++
        }
        override fun onResetLayout() {
            resetLayoutCalls++
        }
    }
}
