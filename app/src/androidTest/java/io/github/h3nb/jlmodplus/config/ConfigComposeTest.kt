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

package io.github.h3nb.jlmodplus.config

import io.github.h3nb.jlmodplus.R
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
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
import io.github.h3nb.jlmodplus.config.model.Size
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

private fun uiString(resId: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

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

        composeRule.onNodeWithText("Current Configuration").assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.PREF_ORIENTATION)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.pref_screen_scale_type)).assertExists()
        composeRule.onNodeWithText("Scale (%)").assertExists()
        composeRule.onNodeWithContentDescription("Start").assertExists()
        composeRule.onNodeWithContentDescription("More").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Display").performClick()
        composeRule.onNodeWithText("Screen Appearance").assertExists()
        composeRule.onNodeWithText("Text Rendering").assertExists()
        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.PREF_ORIENTATION)).assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.pref_screen_scale_type)).assertDoesNotExist()
        composeRule.onNodeWithText("Scale (%)").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText(uiString(R.string.config_controls_key_input)).assertExists()
        composeRule.onNodeWithText("Controls").assertIsSelected()

        composeRule.onNodeWithContentDescription("Audio").performClick()

        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText(uiString(R.string.PREF_SYS_PROPS)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.config_maintenance)).assertExists()
        composeRule.onNodeWithText("Advanced settings").assertDoesNotExist()
    }

    @Test
    fun filterShaderWarningOnlyAppearsForEnabledFilterAndCustomGlesShader() {
        val base = sampleState()
        val identity = ShaderInfo(uiString(R.string.identity_filter), "JL-Mod Plus")
        val custom = ShaderInfo("Test shader", "Test")
        val warning =
            "Linear filtering smooths the input before the screen shader processes it and may reduce output sharpness."

        fun state(filterEnabled: Boolean, graphicsMode: Int, shader: ShaderInfo?): ConfigUiState =
            ConfigUiState(
                base.form.toBuilder()
                    .screenFilter(filterEnabled)
                    .graphicsMode(graphicsMode)
                    .shader(shader)
                    .build(),
                base.screenPresets,
                base.fontPresets,
                base.skins,
                base.soundBanks,
                listOf(identity, custom),
                base.removableScreenPresets,
            )

        fun render(state: ConfigUiState) {
            composeRule.setContent {
                JLModPlusTheme {
                    ConfigScreen(state, RecordingConfigEvents(), initialDestination = ConfigDestination.Display)
                }
            }
        }

        render(state(filterEnabled = true, graphicsMode = 1, shader = custom))
        composeRule.onNodeWithText(warning).assertExists()

        render(state(filterEnabled = false, graphicsMode = 1, shader = custom))
        composeRule.onNodeWithText(warning).assertDoesNotExist()

        render(state(filterEnabled = true, graphicsMode = 1, shader = identity))
        composeRule.onNodeWithText(warning).assertDoesNotExist()

        render(state(filterEnabled = true, graphicsMode = 0, shader = custom))
        composeRule.onNodeWithText(warning).assertDoesNotExist()
    }

    @Test
    fun analogSelectionRemainsAvailableWithoutController() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            base.profileStatus,
            base.profileTemplates,
            base.timingControlsEnabled,
            base.profileNames,
            false,
			null,
        )
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(state, RecordingConfigEvents(), initialDestination = ConfigDestination.Controls)
            }
        }

        composeRule.onNodeWithText(uiString(R.string.config_analog_stick)).assertIsEnabled()
        composeRule.onNodeWithText("Configure Gamepad").assertDoesNotExist()
        composeRule.onNodeWithText("Reset Analog Controls").assertDoesNotExist()
        composeRule.onNodeWithText(
            "No compatible gamepad is connected. Gamepad controls stay inactive until one is detected.",
        ).assertDoesNotExist()

        composeRule.onNodeWithText(uiString(R.string.config_analog_stick)).performClick()
        composeRule.onNodeWithText("Off").assertExists()
        composeRule.onNodeWithText("4-way").assertExists()
        composeRule.onNodeWithText("8-way").assertExists()
        composeRule.onNodeWithText("Numeric keypad").assertExists()
    }

    @Test
    fun inputSectionHasNoSeparateSavedLayoutActions() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(),
                    initialDestination = ConfigDestination.Controls)
            }
        }

        composeRule.onNodeWithText("Choose Saved Virtual Controls Layout").assertDoesNotExist()
        composeRule.onNodeWithText("Save Virtual Controls Layout").assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.pref_map_keys)).assertExists()
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
        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.PREF_TOUCH_INPUT)).assertExists()
        composeRule.onNodeWithContentDescription("Start").assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.preset_use)).assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.preset_save_as)).assertDoesNotExist()
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
        composeRule.onNodeWithText(uiString(R.string.PREF_TOUCH_INPUT)).performScrollTo().performClick()

        assertTrue(events.lastForm?.screenFilter == true)
        assertFalse(events.lastForm?.touchInput == true)
        assertEquals(2, events.formChanges)
    }

    @Test
    fun displayFpsZeroUsesMaximumLabel() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), initialDestination = ConfigDestination.Display)
            }
        }

        composeRule.onNodeWithText(uiString(R.string.PREF_LIMIT_FPS)).performScrollTo()
        composeRule.onNodeWithText("Maximum").assertIsDisplayed()
    }

    @Test
    fun displaySettingsDoNotExposeRuntimeEmulationSpeed() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), initialDestination = ConfigDestination.Display)
            }
        }

        composeRule.onNodeWithText("Show Emulation Speed").assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.PREF_EMULATION_SPEED)).assertDoesNotExist()
    }

    @Test
    fun generalProfileActionsUseIntegratedTemplateFlow() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
  JLModPlusTheme {
      ConfigScreen(sampleState(), events)
  }
        }

        composeRule.onNodeWithText(uiString(R.string.preset_use)).performClick()
        composeRule.onNode(hasText(uiString(R.string.preset_picker_title)) and hasAnyAncestor(isDialog())).assertExists()
        composeRule.onNodeWithText(uiString(R.string.preset_manage)).assertExists()
        composeRule.onNodeWithText("JL-Mod Defaults").performClick()
        composeRule.onNodeWithText("JL-Mod Defaults").assertExists()
        composeRule.onNodeWithText("Apply").performClick()
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
        composeRule.onNodeWithText("Follows future profile updates.").assertExists()
        composeRule.onNodeWithText(uiString(R.string.preset_use)).performClick()
        composeRule.onNode(hasText("Nokia Classic") and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test
    fun builtInDefaultProfileIsNotShownAsCustom() {
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
            ConfigUiState.ProfileStatus.builtInDefault(null),
        )
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(state, events)
            }
        }

        composeRule.onNodeWithText("JL-Mod Defaults").assertExists()
        composeRule.onNodeWithText("Built-in MIDlet settings and virtual controls.").assertDoesNotExist()
        composeRule.onNodeWithText("Current Configuration").assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.preset_use)).performClick()
        composeRule.onNodeWithText("Built-in MIDlet settings and virtual controls.").assertExists()
        composeRule.onNode(hasText("JL-Mod Defaults") and hasAnyAncestor(isDialog())).performClick()
        composeRule.onNodeWithText(uiString(R.string.preset_choose_parts)).assertDoesNotExist()
        composeRule.onNodeWithTag("profile_scope_settings").assertDoesNotExist()
        composeRule.onNodeWithText("Apply").performClick()
        assertEquals(1, events.applyBuiltInCalls)
        assertEquals(ConfigFormEvents.PresetApplyScope.WHOLE_PROFILE, events.appliedBuiltInScope)
    }

    @Test
    fun presetPickerShowsSelectedActionsBeforeDispatchingApply() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            base.profileStatus,
            listOf(
                ConfigUiState.ProfileTemplate("Touchscreen", false, false, 360, 640, 1),
                ConfigUiState.ProfileTemplate("RPG 240×320", false, true, 240, 320, 3),
            ),
        )
        val events = RecordingConfigEvents()
        composeRule.setContent { JLModPlusTheme { ConfigScreen(state, events) } }

        composeRule.onNodeWithText(uiString(R.string.preset_use)).performClick()
        composeRule.onNodeWithText("RPG 240×320").performClick()
        composeRule.onNodeWithText(uiString(R.string.preset_choose_parts)).assertExists()
        composeRule.onNodeWithTag("profile_scope_whole_profile").assertExists()
        composeRule.onNodeWithTag("profile_scope_settings").assertExists()
        composeRule.onNodeWithTag("profile_scope_keyboard_layout").assertExists()
        assertEquals(0, events.applyTemplateCalls)

        composeRule.onNodeWithText("Apply").performClick()
        assertEquals("RPG 240×320", events.appliedTemplate)
        assertEquals(ConfigFormEvents.PresetApplyScope.WHOLE_PROFILE, events.appliedScope)
    }

    @Test
    fun pickerCanApplyEitherPartAndLegacyLayoutOnly() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            base.profileStatus,
            listOf(
                ConfigUiState.ProfileTemplate("RPG 240×320", false, true, 240, 320, 3),
                ConfigUiState.ProfileTemplate("Legacy layout", false, true, false, false, false, 0, 0, 0),
            ),
        )
        val events = RecordingConfigEvents()
        composeRule.setContent { JLModPlusTheme { ConfigScreen(state, events) } }

        composeRule.onNodeWithText(uiString(R.string.preset_use)).performClick()
        composeRule.onNodeWithText("RPG 240×320").performClick()
        composeRule.onNodeWithTag("profile_scope_settings").performClick()
        composeRule.onNodeWithText("Apply").performClick()
        assertEquals(ConfigFormEvents.PresetApplyScope.SETTINGS, events.appliedScope)

        composeRule.onNodeWithText(uiString(R.string.preset_use)).performClick()
        composeRule.onNodeWithText("Legacy layout").performClick()
        composeRule.onNodeWithTag("profile_scope_whole_profile").assertDoesNotExist()
        composeRule.onNodeWithTag("profile_scope_settings").assertDoesNotExist()
        composeRule.onNodeWithTag("profile_scope_keyboard_layout").assertExists()
        composeRule.onNodeWithText("Apply").performClick()
        assertEquals(ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT, events.appliedScope)
    }

    @Test
    fun saveAsPresetCapturesWholeDeviceWithoutComponentChoice() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            base.profileStatus,
            emptyList(),
            true,
        )
        val events = RecordingConfigEvents()
        composeRule.setContent { JLModPlusTheme { ConfigScreen(state, events) } }

        composeRule.onNodeWithText(uiString(R.string.preset_save_as)).performClick()
        composeRule.onNodeWithTag("preset_include_keyboard_layout").assertDoesNotExist()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Comfortable")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("Comfortable", events.savedTemplate)
    }

    @Test
    fun saveAsPresetStillRejectsInvalidAndDuplicateNames() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            base.profileStatus,
            emptyList(),
            true,
            listOf("K800i"),
            true,
            null,
        )
        composeRule.setContent { JLModPlusTheme { ConfigScreen(state, RecordingConfigEvents()) } }

        composeRule.onNodeWithText(uiString(R.string.preset_save_as)).performClick()
        val nameField = composeRule.onNode(hasSetTextAction())
        val saveButton = composeRule.onNode(
            hasText("Save") and hasClickAction() and hasAnyAncestor(isDialog()),
        )

        nameField.performTextReplacement("bad/name")
        saveButton.assertIsNotEnabled()

        nameField.performTextReplacement("k800i")
        saveButton.assertIsNotEnabled()

        nameField.performTextReplacement("N95")
        saveButton.assertIsEnabled()
    }

    @Test
    fun linkedPresetShowsFollowingStatusWithoutUpdateWhenClean() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            ConfigUiState.ProfileStatus.active("K800i", null),
            emptyList(),
            true,
            listOf("K800i"),
            false,
            null,
        )
        composeRule.setContent { JLModPlusTheme { ConfigScreen(state, RecordingConfigEvents()) } }

        composeRule.onNodeWithText("K800i").assertExists()
        composeRule.onNodeWithText("Follows future profile updates.").assertExists()
        composeRule.onNodeWithTag("preset_update_action").assertDoesNotExist()
    }

    @Test
    fun updatePresetActionRequiresConfirmation() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form,
            base.screenPresets,
            base.fontPresets,
            base.skins,
            base.soundBanks,
            base.shaders,
            base.removableScreenPresets,
            ConfigUiState.ProfileStatus.active("K800i", null),
            emptyList(),
            true,
            listOf("K800i"),
            false,
            "K800i",
        )
        val events = RecordingConfigEvents()
        composeRule.setContent { JLModPlusTheme { ConfigScreen(state, events) } }

        composeRule.onNodeWithText("K800i").assertExists()
        composeRule.onNodeWithTag("preset_update_action").assertExists().performClick()
        composeRule.onNodeWithText("Update K800i?").assertExists()
        assertEquals(null, events.updatedTemplate)

        composeRule.onNode(
            hasText("Update") and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()

        assertEquals("K800i", events.updatedTemplate)
    }

    @Test
    fun destructiveActionsLiveInSystemAndRequireExplicitConfirmation() {
        val menuActions = RecordingMenuActions()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    sampleState(),
                    RecordingConfigEvents(),
                    menuActions = menuActions,
                )
            }
        }

        repeat(4) {
            composeRule.onRoot().performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText(uiString(R.string.PREF_SYS_PROPS)).assertIsDisplayed()
        composeRule.onNodeWithTag("config_reset_settings_action").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "Reset this MIDlet’s settings and virtual controls to their defaults? MIDlet data will not be deleted.",
        ).assertExists()
        composeRule.onNode(hasText(uiString(R.string.config_reset_all_settings)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, menuActions.resetSettingsCalls)

        composeRule.onNodeWithTag("config_clear_data_action").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(
            "Permanently delete all saves and data created by this MIDlet? MIDlet settings will not be deleted.",
        ).assertExists()
        composeRule.onNode(hasText(uiString(R.string.config_delete_app_data)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, menuActions.clearDataCalls)

        composeRule.onRoot().performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(uiString(R.string.RESET_LAYOUT_CMD)).performScrollTo().performClick()
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

        composeRule.onNodeWithText(uiString(R.string.config_delete_app_data)).assertDoesNotExist()
        composeRule.onNodeWithTag("config_reset_settings_action").performClick()
        composeRule.onNodeWithText(
            "Reset this profile’s settings and virtual controls to their defaults? You can still edit or cancel before saving.",
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

        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).performClick()
        composeRule.onNodeWithText("360 x 640").performClick()
        assertEquals("360", events.lastForm?.screenWidth)
        assertEquals("640", events.lastForm?.screenHeight)
        composeRule.onNodeWithText("Select").assertDoesNotExist()

        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).performClick()
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

        composeRule.onNodeWithText(uiString(R.string.PREF_ORIENTATION)).performClick()
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

        composeRule.onNodeWithText("Custom color").performClick()

        assertEquals(ConfigFormEvents.ColorField.SCREEN_BACKGROUND, events.colorPickerField)
    }

    @Test
    fun backgroundModeKeepsCustomColorDormantWhenPickerIsHidden() {
        val events = RecordingConfigEvents()
        val snapshot = androidx.compose.runtime.mutableStateOf(sampleState())
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

        composeRule.onNodeWithText("Background").performClick()
        composeRule.onNodeWithText("Immersive").performClick()
        assertEquals(BackgroundMode.IMMERSIVE, events.lastForm?.screenBackgroundMode)
        composeRule.onNodeWithText("Custom color").assertDoesNotExist()

        composeRule.onNodeWithText("Background").performClick()
        composeRule.onNodeWithText("Custom").performClick()
        assertEquals("D0D0D0", events.lastForm?.screenBackground)
        composeRule.onNodeWithText("Custom color").assertExists()
    }

    @Test
    fun customScreenPresetCanBeRemovedFromPresetDialog() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events)
            }
        }

        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).performClick()
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
        composeRule.onNodeWithText(uiString(R.string.config_edit_system_properties)).performClick()
        composeRule.onNodeWithText(uiString(R.string.PREF_SYS_PROPS)).assertExists()
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
        var applyBuiltInCalls = 0
        var appliedBuiltInScope: ConfigFormEvents.PresetApplyScope? = null
        var applyTemplateCalls = 0
        var appliedTemplate: String? = null
        var appliedScope: ConfigFormEvents.PresetApplyScope? = null
        var savedTemplate: String? = null
        var updatedTemplate: String? = null

        override fun onApplyBuiltInTemplate(scope: ConfigFormEvents.PresetApplyScope): Boolean {
            applyBuiltInCalls++
            appliedBuiltInScope = scope
            return true
        }

        override fun onApplyTemplate(
            name: String,
            scope: ConfigFormEvents.PresetApplyScope,
        ): Boolean {
            applyTemplateCalls++
            appliedTemplate = name
            appliedScope = scope
            return true
        }

        override fun onSaveTemplate(name: String): Boolean {
            savedTemplate = name
            return true
        }

        override fun onUpdatePreset(name: String): Boolean {
            updatedTemplate = name
            return true
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
