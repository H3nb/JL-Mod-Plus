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

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
@OptIn(ExperimentalTestApi::class)
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
        composeRule.onNodeWithText("Screen Size").assertExists()
        composeRule.onNodeWithText("Screen Orientation").assertExists()
        composeRule.onNodeWithText("Scale Type").assertExists()
        composeRule.onNodeWithText("Scale (%)").assertExists()
        composeRule.onNodeWithContentDescription("Start").assertExists()

        composeRule.onNodeWithContentDescription("Display").performClick()
        composeRule.onNodeWithText("Screen Appearance").assertExists()
        composeRule.onNodeWithText("Text Rendering").assertExists()
        composeRule.onNodeWithText("Screen Size").assertDoesNotExist()
        composeRule.onNodeWithText("Screen Orientation").assertDoesNotExist()
        composeRule.onNodeWithText("Scale Type").assertDoesNotExist()
        composeRule.onNodeWithText("Scale (%)").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText("Key Input").assertExists()
        composeRule.onNodeWithText("Controls").assertIsSelected()

        composeRule.onNodeWithContentDescription("Audio").performClick()

        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText("System Properties").assertExists()
        composeRule.onNodeWithText("Reset & Data").assertExists()
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
    fun configUsesRailForMediumPortraitWindow() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(700.dp, 1_000.dp)),
            ) {
                JLModPlusTheme {
                    ConfigScreen(sampleState(), RecordingConfigEvents())
                }
            }
        }

        composeRule.onNodeWithTag(CONFIG_NAVIGATION_RAIL_TAG).assertExists()
        composeRule.onNodeWithTag(CONFIG_NAVIGATION_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun configKeepsBottomBarForCompactLandscapeWindow() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(500.dp, 360.dp)),
            ) {
                JLModPlusTheme {
                    ConfigScreen(sampleState(), RecordingConfigEvents())
                }
            }
        }

        composeRule.onNodeWithTag(CONFIG_NAVIGATION_BAR_TAG).assertExists()
        composeRule.onNodeWithTag(CONFIG_NAVIGATION_RAIL_TAG).assertDoesNotExist()
    }

    @Test
    fun profileEditorKeepsGeneralSettingsWithoutProfileWorkflow() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), isProfile = true)
            }
        }

        composeRule.onNodeWithContentDescription("Basic", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Screen Size").assertExists()
        composeRule.onNodeWithText("Touch Input").assertExists()
        composeRule.onNodeWithContentDescription("Start").assertDoesNotExist()
        composeRule.onNodeWithText("Use profile").assertDoesNotExist()
        composeRule.onNodeWithText("Save As New Template").assertDoesNotExist()
    }

    @Test
    fun configDraftChangesStayInStateAndEmitEvents() {
        val events = RecordingConfigEvents()
        val snapshot = androidx.compose.runtime.mutableStateOf(sampleState())
        // The activity owns the draft; feed emitted snapshots back just as the host does.
        val hostEvents = object : ConfigFormEvents by events {
            override fun onFormChanged(form: ConfigFormState) {
                events.onFormChanged(form)
                val previous = snapshot.value
                snapshot.value = ConfigUiState(
                    form, previous.screenPresets, previous.fontPresets, previous.skins,
                    previous.soundBanks, previous.shaders, previous.removableScreenPresets,
                    previous.profileStatus, previous.profileTemplates, previous.timingControlsEnabled,
                )
            }
        }
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(snapshot.value, hostEvents, initialDestination = ConfigDestination.Display)
            }
        }

        composeRule.onNodeWithText("Filter").performClick()
        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithContentDescription("Basic").performClick()
        composeRule.onNodeWithText("Touch Input").performScrollTo().performClick()

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

        composeRule.onNodeWithText("Choose or manage templates").performClick()
        composeRule.onNodeWithText("Configuration Templates").assertExists()
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
  listOf(ConfigUiState.ProfileTemplate("Nokia Classic", true)),
        )
        composeRule.setContent {
  JLModPlusTheme { ConfigScreen(state, RecordingConfigEvents()) }
        }
        composeRule.onNodeWithText("Nokia Classic").assertExists()
        composeRule.onNodeWithText("Choose or manage templates").performClick()
        composeRule.onNodeWithText("Configuration Templates").assertExists()
        composeRule.onNode(hasText("Default") and hasText("Nokia Classic")).assertExists()
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

        composeRule.onNodeWithText("Built-In Settings").assertExists()
        composeRule.onNodeWithText("JL-Mod Plus factory configuration · Default for new apps").assertExists()
        composeRule.onNodeWithText("Custom").assertDoesNotExist()
        composeRule.onNodeWithText("Save As New Template").assertDoesNotExist()
        composeRule.onNodeWithText("Choose or manage templates").assertExists()
    }

    @Test
    fun destructiveActionsLiveInSystemAndRequireExplicitConfirmation() {
        val menuActions = RecordingMenuActions()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), menuActions = menuActions)
            }
        }

        composeRule.onNodeWithText("Reset All Settings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("System").performClick()

        composeRule.onNodeWithText("Reset All Settings").performClick()
        composeRule.onNodeWithText(
            "Reset all emulator settings for this app to their defaults? App data and the custom button layout will not be deleted.",
        ).assertExists()
        composeRule.onNode(hasText("Reset All Settings") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, menuActions.resetSettingsCalls)

        composeRule.onNodeWithText("Delete App Data").performClick()
        composeRule.onNodeWithText(
            "Permanently delete all saves and data created by this app? Emulator settings will not be deleted.",
        ).assertExists()
        composeRule.onNode(hasText("Delete App Data") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, menuActions.clearDataCalls)

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText("Reset Key Layout").performScrollTo().performClick()
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

        composeRule.onNodeWithText("Delete App Data").assertDoesNotExist()
        composeRule.onNodeWithText("Reset All Settings").performClick()
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

        composeRule.onNodeWithText("Screen Size").performClick()
        composeRule.onNodeWithText("360 x 640").performClick()
        assertEquals("360", events.lastForm?.screenWidth)
        assertEquals("640", events.lastForm?.screenHeight)
        composeRule.onNodeWithText("Select").assertDoesNotExist()

        composeRule.onNodeWithText("Screen Size").performClick()
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

        composeRule.onNodeWithText("Screen Orientation").performClick()
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
        composeRule.onNodeWithText("Labels").assertExists()
        composeRule.onNode(hasText("Labels") and hasText("#000080")).assertExists()
        composeRule.onNodeWithText("Buttons").assertExists()
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
        composeRule.onNode(hasText("Opacity") and hasAnyAncestor(isDialog())).assertExists()
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

        composeRule.onNodeWithText("Screen Size").performClick()
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
        composeRule.onNodeWithText("250 ms").assertExists()
        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText("Edit System Properties").performClick()
        composeRule.onNodeWithText("System Properties").assertExists()
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
        var applyBuiltInCalls = 0

        override fun onUseProfile() {
            useProfileCalls++
        }

        override fun onSaveAsProfile() {
            saveAsProfileCalls++
        }

        override fun onApplyBuiltInTemplate() {
            applyBuiltInCalls++
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
