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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
        composeRule.onNodeWithText("Screen orientation").assertExists()
        composeRule.onNodeWithText("Scale type").assertExists()
        composeRule.onNodeWithText("Scale (%)").assertExists()
        composeRule.onNodeWithContentDescription("Start").assertExists()

        composeRule.onNodeWithContentDescription("Display").performClick()
        composeRule.onNodeWithText("Screen Appearance").assertExists()
        composeRule.onNodeWithText("Font").assertExists()
        composeRule.onNodeWithText("Screen size").assertDoesNotExist()
        composeRule.onNodeWithText("Screen orientation").assertDoesNotExist()
        composeRule.onNodeWithText("Scale type").assertDoesNotExist()
        composeRule.onNodeWithText("Scale (%)").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText("Key Input").assertExists()
        composeRule.onNodeWithText("Audio").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Audio").performClick()

        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText("System properties").assertExists()
        composeRule.onNodeWithText("Reset & data").assertExists()
        composeRule.onNodeWithText("Advanced settings").assertDoesNotExist()
    }

    @Test
    fun configDestinationsSupportHorizontalSwipe() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents())
            }
        }

        composeRule.onNodeWithText("Screen & Window Basics").assertExists()
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Screen Appearance").assertExists()

        composeRule.onRoot().performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Screen & Window Basics").assertExists()
    }

    @Test
    fun profileEditorKeepsGeneralSettingsWithoutProfileWorkflow() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), isProfile = true)
            }
        }

        composeRule.onNodeWithContentDescription("Basic").assertExists()
        composeRule.onNodeWithText("Screen size").assertExists()
        composeRule.onNodeWithText("Touch input").assertExists()
        composeRule.onNodeWithContentDescription("Start").assertDoesNotExist()
        composeRule.onNodeWithText("Use profile").assertDoesNotExist()
        composeRule.onNodeWithText("Save as Profile").assertDoesNotExist()
    }

    @Test
    fun configDraftChangesStayInStateAndEmitEvents() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Display)
            }
        }

        composeRule.onNodeWithText("Filter").performClick()
        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithContentDescription("Basic").performClick()
        composeRule.onNodeWithText("Touch input").performClick()

        assertTrue(events.lastForm?.screenFilter == true)
        assertFalse(events.lastForm?.touchInput == true)
        assertEquals(2, events.formChanges)
    }

    @Test
    fun displaySettingsDoNotExposeRuntimeEmulationSpeed() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), initialDestination = ConfigDestination.Display)
            }
        }

        composeRule.onNodeWithText("Show Emulation Speed").assertDoesNotExist()
        composeRule.onNodeWithText("Emulation Speed").assertDoesNotExist()
    }

    @Test
    fun generalProfileActionsUseIntegratedTemplateFlow() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events)
            }
        }

        composeRule.onNodeWithText("Change Profile").performClick()
        composeRule.onNodeWithText("Profiles").assertExists()
        composeRule.onNodeWithText("Built-In Settings").performClick()
        assertEquals(1, events.applyBuiltInCalls)
    }

    @Test
    fun activeProfileShowsIntegratedManagerAndDefaultStatus() {
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
            listOf(ConfigUiState.ProfileTemplate("Nokia Classic", true, true, true)),
        )
        composeRule.setContent {
            JLModPlusTheme { ConfigScreen(state, RecordingConfigEvents()) }
        }
        composeRule.onNodeWithText("Nokia Classic").assertExists()
        composeRule.onNodeWithText("Change Profile").performClick()
        composeRule.onNodeWithText("Profiles").assertExists()
        composeRule.onNodeWithText("Default").assertExists()
    }

    @Test
    fun builtInSettingsCanCoexistWithAppSpecificKeyboard() {
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

        composeRule.onNodeWithText("Built-In Settings").assertExists()
        composeRule.onNodeWithText("App-specific").assertExists()
        composeRule.onNodeWithText("Custom").assertDoesNotExist()
        composeRule.onNodeWithText("Save as Profile").assertExists()
        composeRule.onNodeWithText("Choose or manage templates").assertDoesNotExist()
    }

    @Test
    fun mixedProfileSourcesRenderIndependentlyAndGlobalUpdateRequiresConfirmation() {
        val base = sampleState()
        val events = RecordingConfigEvents()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            ConfigUiState.ProfileStatus.components(
                "Nokia Settings",
                true,
                "Touch Layout",
                false,
                false,
                null,
            ),
            listOf(
                ConfigUiState.ProfileTemplate("Nokia Settings", false, true, false),
                ConfigUiState.ProfileTemplate("Touch Layout", false, false, true),
            ),
        )
        composeRule.setContent {
            JLModPlusTheme { ConfigScreen(state, events) }
        }

        composeRule.onNodeWithText("Nokia Settings").assertExists()
        composeRule.onNodeWithText("Touch Layout").assertExists()
        composeRule.onNodeWithText("Modified").assertExists()
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.onNodeWithText("Update Profile").performClick()
        composeRule.onNodeWithText("Update “Nokia Settings”?").assertExists()
        composeRule.onNodeWithText(
            "This changes the linked profile globally. Every app that uses the updated component will receive it automatically unless that app has its own modified copy.",
        ).assertExists()
        assertEquals(null, events.updatedTemplate)
        composeRule.onNodeWithText("Update Profile").performClick()
        assertEquals("Nokia Settings", events.updatedTemplate)
    }

    @Test
    fun combinedProfileOffersGranularApplyAndQuickSaveUsesModularDialogFlow() {
        val base = sampleState()
        val events = RecordingConfigEvents()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            ConfigUiState.ProfileStatus.custom(null),
            listOf(ConfigUiState.ProfileTemplate("Combined", false, true, true)),
        )
        composeRule.setContent {
            JLModPlusTheme { ConfigScreen(state, events) }
        }

        composeRule.onNodeWithText("Save as Profile").performClick()
        assertEquals(1, events.saveAsProfileCalls)

        composeRule.onNodeWithText("Change Profile").performClick()
        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.onNodeWithText("Apply Settings Only").performClick()
        assertEquals("Combined", events.appliedComponentsName)
        assertTrue(events.appliedSettings)
        assertFalse(events.appliedKeyboard)
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
            "Reset all emulator settings for this app to their defaults? App data and the custom button layout will not be deleted.",
        ).assertExists()
        composeRule.onNodeWithText("Reset all settings", useUnmergedTree = true).performClick()
        assertEquals(1, menuActions.resetSettingsCalls)

        composeRule.onNodeWithText("Delete app data").performClick()
        composeRule.onNodeWithText(
            "Permanently delete all saves and data created by this app? Emulator settings will not be deleted.",
        ).assertExists()
        composeRule.onNodeWithText("Delete app data", useUnmergedTree = true).performClick()
        assertEquals(1, menuActions.clearDataCalls)

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText("Reset key layout").performClick()
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

        composeRule.onNodeWithText("Delete app data").assertDoesNotExist()
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

        composeRule.onNodeWithText("Screen size").performClick()
        composeRule.onNodeWithText("Swap width and height").performClick()
        assertEquals("320", events.lastForm?.screenWidth)
        assertEquals("240", events.lastForm?.screenHeight)
    }

    @Test
    fun configurationDropdownsExposeTheirOptionsAndUpdateTheDraft() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Basic)
            }
        }

        composeRule.onNodeWithText("Screen orientation").performClick()
        composeRule.onNodeWithText("Landscape").performClick()

        assertEquals(3, events.lastForm?.orientation)
    }

    @Test
    fun advancedControlsRenderColorPreferencesWithoutCrashing() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), initialDestination = ConfigDestination.Controls)
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Foreground").assertExists()
        composeRule.onNodeWithText("#000080").assertExists()
        composeRule.onNodeWithText("Background").assertExists()
        composeRule.onNodeWithText("Outline").assertExists()
    }

    @Test
    fun sliderValuesCommitFromTheirDialogs() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Controls)
            }
        }

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
                    initialDestination = ConfigDestination.Display,
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
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Display)
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
    fun systemPropertiesUseFocusedEditorAndHideDelayUsesMilliseconds() {
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
            JLModPlusTheme { ConfigScreen(state, events, initialDestination = ConfigDestination.Controls) }
        }
        composeRule.onNodeWithText("ms").assertExists()
        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText("Edit system properties").performClick()
        composeRule.onNodeWithText("System properties").assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("microedition.platform: updated\n")
        composeRule.onNodeWithText("Save").performClick()
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
        var removed: Size? = null
        var colorPickerField: ConfigFormEvents.ColorField? = null
        var saveAsProfileCalls = 0
        var applyBuiltInCalls = 0
        var updatedTemplate: String? = null
        var appliedComponentsName: String? = null
        var appliedSettings = false
        var appliedKeyboard = false

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


        override fun onSaveAsProfile() {
            saveAsProfileCalls++
        }

        override fun onApplyBuiltInTemplate() {
            applyBuiltInCalls++
        }

        override fun onApplyTemplateComponents(name: String, settings: Boolean, keyboard: Boolean) {
            appliedComponentsName = name
            appliedSettings = settings
            appliedKeyboard = keyboard
        }

        override fun onUpdateTemplate(name: String) {
            updatedTemplate = name
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
