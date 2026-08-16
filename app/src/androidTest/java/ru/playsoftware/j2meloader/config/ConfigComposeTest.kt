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

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
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
    fun configRendersQuickAndAdaptiveDestinations() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents())
            }
        }

        composeRule.onNodeWithText("Custom").assertExists()
        composeRule.onNodeWithText("Screen size").assertExists()
        composeRule.onNodeWithContentDescription("Start").assertExists()

        composeRule.onNodeWithContentDescription("Graphics").performClick()
        composeRule.onNodeWithText("Screen options").assertExists()
        composeRule.onNodeWithText("Font options").assertExists()

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText("Input devices").assertExists()
        composeRule.onNodeWithText("Audio").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Media").performClick()
        composeRule.onNodeWithText("Multimedia settings will be added here in a future update.").assertExists()

        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText("System properties").assertExists()
    }

    @Test
    fun profileEditorStartsOnGraphicsWithoutQuickWorkflow() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), isProfile = true)
            }
        }

        composeRule.onNodeWithText("Screen options").assertExists()
        composeRule.onNodeWithContentDescription("Quick").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Start").assertDoesNotExist()
        composeRule.onNodeWithText("Use profile").assertDoesNotExist()
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
        composeRule.onNodeWithText("Touch input").performClick()

        assertTrue(events.lastForm?.screenFilter == true)
        assertFalse(events.lastForm?.touchInput == true)
        assertEquals(2, events.formChanges)
    }

    @Test
    fun quickProfileActionsUseTheExistingHostFlows() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events)
            }
        }

        composeRule.onNodeWithText("Use profile").performClick()
        composeRule.onNodeWithText("Save as profile").performClick()
        composeRule.onNodeWithText("Manage profiles").performClick()

        assertEquals(1, events.useProfileCalls)
        assertEquals(1, events.saveAsProfileCalls)
        assertEquals(1, events.manageProfilesCalls)
    }

    @Test
    fun activeProfileUsesChangeAndManageActions() {
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
        composeRule.onNodeWithText("Manage profiles").performClick()
        composeRule.onNodeWithText("Save as profile").assertDoesNotExist()

        assertEquals(1, events.useProfileCalls)
        assertEquals(1, events.manageProfilesCalls)
    }

    @Test
    fun configurationDropdownsExposeTheirOptionsAndUpdateTheDraft() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Graphics)
            }
        }

        composeRule.onNodeWithText("Screen orientation").performClick()
        composeRule.onNodeWithText("Screen orientation").assertExists()
        composeRule.onNodeWithText("Landscape").performClick()

        assertEquals(3, events.lastForm?.orientation)
    }

    @Test
    fun numericAndSliderValuesCommitFromTheirDialogs() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Graphics)
            }
        }

        composeRule.onNodeWithText("240").performClick()
        composeRule.onNodeWithText("Width").assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("360")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("360", events.lastForm?.screenWidth)

        composeRule.onNodeWithContentDescription("Controls").performClick()
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
    fun customScreenPresetCanBeRemovedFromPresetMenu() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Graphics)
            }
        }

        composeRule.onNodeWithContentDescription("Presets").performClick()
        composeRule.onNodeWithContentDescription("Remove screen preset").performClick()

        assertEquals(Size(360, 640), events.removed)
    }

    @Test
    fun hideDelayUsesMillisecondsAndSystemPropertiesCommitOnlyOnConfirm() {
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
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("microedition.platform: updated\n")
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(null, events.lastForm)

        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("microedition.platform: updated\n")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("microedition.platform: updated\n", events.lastForm?.systemProperties)
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

        override fun onAddResolutionPreset() = Unit
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
        var manageProfilesCalls = 0

        override fun onUseProfile() {
            useProfileCalls++
        }

        override fun onSaveAsProfile() {
            saveAsProfileCalls++
        }

        override fun onManageProfiles() {
            manageProfilesCalls++
        }
    }
}
