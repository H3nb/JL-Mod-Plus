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
import androidx.compose.runtime.mutableStateOf
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

    private fun scrollTo(text: String) {
        composeRule.onNodeWithText(text).performScrollTo()
    }

    @Test
    fun sharedPermissionChangeDoesNotEditTheReusableProfile() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form, base.screenPresets, base.fontPresets, base.skins, base.soundBanks,
            base.shaders, base.removableScreenPresets, base.profileStatus, base.profileTemplates,
            base.timingControlsEnabled, base.profileNames, base.controllerAvailable,
            base.updatePresetName, ConfigUiState.FileAccess(0, 0, emptyList()),
        )
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme { ConfigScreen(state, events, initialDestination = ConfigDestination.System) }
        }
        composeRule.onNodeWithText(uiString(R.string.midlet_permissions_settings_title)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.midlet_storage_mode_title)).performClick()
        composeRule.onNodeWithText(uiString(R.string.midlet_storage_mode_android)).performClick()
        composeRule.runOnIdle {
            assertEquals(org.microemu.cldc.file.StorageMode.ANDROID, events.storageMode)
            assertEquals(0, events.formChanges)
        }
        scrollTo(uiString(R.string.midlet_permissions_write))
        composeRule.onNodeWithText(uiString(R.string.midlet_permissions_write)).performClick()
        composeRule.onNodeWithText(uiString(R.string.midlet_permissions_deny)).performClick()
        composeRule.runOnIdle {
            assertEquals(true to MidletPermissionStore.DENY, events.filePermission)
            assertEquals(0, events.formChanges)
        }
    }

    @Test
    fun groupedGamepadPreferencesHaveSeparateVerticalBounds() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), initialDestination = ConfigDestination.Controls)
            }
        }
        val analogTitle = uiString(R.string.config_analog_stick)
        val calibrationTitle = uiString(R.string.config_gamepad_calibrate)
        scrollTo(calibrationTitle)
        composeRule.onNodeWithText(analogTitle).assertIsDisplayed()
        composeRule.onNodeWithText(calibrationTitle).assertIsDisplayed()
        val analog = composeRule.onNodeWithText(analogTitle).fetchSemanticsNode().boundsInRoot
        val calibration = composeRule.onNodeWithText(calibrationTitle).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Gamepad preferences must stack vertically", analog.bottom <= calibration.top)
    }

    @Test
    fun cutoutPreferenceIsHiddenWithoutCutoutAndDoesNotModifyStoredValue() {
        val base = sampleState()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(base, RecordingConfigEvents(), cutoutSupportOverride = false)
            }
        }
        composeRule.onNodeWithText(uiString(R.string.config_avoid_camera_cutout_title)).assertDoesNotExist()
        assertTrue(base.form.allowDisplayCutout)
    }

    @Test
    fun cutoutPreferenceCanOptOutWhileKeepingOtherFields() {
        val base = sampleState()
        val draft = mutableStateOf(base.form)
        val events = object : ConfigFormEvents by RecordingConfigEvents() {
            override fun onFormChanged(form: ConfigFormState) { draft.value = form }
        }
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    ConfigUiState(
                        draft.value, base.screenPresets, base.fontPresets, base.skins,
                        base.soundBanks, base.shaders, base.removableScreenPresets,
                        base.profileStatus,
                    ),
                    events,
                    cutoutSupportOverride = true,
                )
            }
        }
        scrollTo(uiString(R.string.config_avoid_camera_cutout_title))
        composeRule.onNodeWithText(uiString(R.string.config_avoid_camera_cutout_title)).performClick()
        composeRule.runOnIdle {
            assertFalse(draft.value.allowDisplayCutout)
            assertEquals(base.form.screenWidth, draft.value.screenWidth)
            assertEquals(base.form.screenHeight, draft.value.screenHeight)
        }
    }

    @Test
    fun configRendersGeneralAndAdaptiveDestinations() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents())
            }
        }

        composeRule.onNodeWithText("Current Configuration").assertDoesNotExist()
        scrollTo(uiString(R.string.config_screen_size))
        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).assertExists()
        scrollTo(uiString(R.string.PREF_ORIENTATION))
        composeRule.onNodeWithText(uiString(R.string.PREF_ORIENTATION)).assertExists()
        scrollTo(uiString(R.string.pref_screen_scale_type))
        composeRule.onNodeWithText(uiString(R.string.pref_screen_scale_type)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.pref_screen_scale_type_fit)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.PREF_SCALE_RATIO)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Start").assertExists()
        composeRule.onNodeWithContentDescription("More").assertDoesNotExist()

        scrollTo(uiString(R.string.config_maintenance))
        composeRule.onNodeWithText(uiString(R.string.config_maintenance)).assertExists()

        composeRule.onNodeWithContentDescription("Display").performClick()
        composeRule.onNodeWithText(uiString(R.string.config_display_appearance)).assertExists()
        scrollTo(uiString(R.string.config_display_text))
        composeRule.onNodeWithText(uiString(R.string.config_display_text)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.PREF_ORIENTATION)).assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.pref_screen_scale_type)).assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.PREF_SCALE_RATIO)).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithText(uiString(R.string.config_controls_key_input)).assertExists()
        composeRule.onNodeWithText("Controls").assertIsSelected()

        composeRule.onNodeWithContentDescription(uiString(R.string.config_destination_media)).performClick()
        composeRule.onNodeWithText(uiString(R.string.pref_audio_title)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.config_camera_title)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("System").performClick()
        scrollTo(uiString(R.string.PREF_SYS_PROPS))
        composeRule.onNodeWithText(uiString(R.string.PREF_SYS_PROPS)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.config_maintenance)).assertDoesNotExist()
        composeRule.onNodeWithText("Advanced settings").assertDoesNotExist()
    }

    @Test
    fun mediaCameraChoicesMapRearFrontAndResolutionToStoredValues() {
        val base = sampleState()
        val currentForm = mutableStateOf(base.form)
        val events = object : ConfigFormEvents by RecordingConfigEvents() {
            override fun onFormChanged(form: ConfigFormState) {
                currentForm.value = form
            }
        }
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    ConfigUiState(
                        currentForm.value, base.screenPresets, base.fontPresets, base.skins,
                        base.soundBanks, base.shaders, base.removableScreenPresets,
                    ),
                    events,
                    initialDestination = ConfigDestination.Media,
                )
            }
        }
        val camera = uiString(R.string.config_camera_default_title)
        val resolution = uiString(R.string.config_camera_resolution_title)
        composeRule.onNodeWithText(uiString(R.string.pref_audio_title)).assertIsDisplayed()
        scrollTo(camera)
        composeRule.onNodeWithText(camera).performClick()
        composeRule.onNodeWithText(uiString(R.string.config_camera_front)).performClick()
        composeRule.runOnIdle { assertEquals(0, currentForm.value.cameraDefaultFacing) }
        composeRule.onNodeWithText(camera).performClick()
        composeRule.onNodeWithText(uiString(R.string.config_camera_rear)).performClick()
        composeRule.runOnIdle { assertEquals(1, currentForm.value.cameraDefaultFacing) }

        scrollTo(resolution)
        composeRule.onNodeWithText(resolution).performClick()
        composeRule.onNodeWithText(uiString(R.string.config_camera_resolution_portrait)).performClick()
        composeRule.runOnIdle { assertEquals(1, currentForm.value.cameraResolution) }
        composeRule.onNodeWithText(resolution).performClick()
        composeRule.onNodeWithText(uiString(R.string.config_camera_resolution_landscape)).performClick()
        composeRule.runOnIdle { assertEquals(2, currentForm.value.cameraResolution) }
        composeRule.onNodeWithText(resolution).performClick()
        composeRule.onNodeWithText(uiString(R.string.config_camera_resolution_auto)).performClick()
        composeRule.runOnIdle { assertEquals(0, currentForm.value.cameraResolution) }
    }

    @Test
    fun manualScalingIsConditionalAndResetsWhenSwitchingModes() {
        val base = sampleState()
        val currentForm = mutableStateOf(
            base.form.toBuilder().screenScaleType(0).screenScaleRatio("400").build(),
        )
        val events = object : ConfigFormEvents by RecordingConfigEvents() {
            override fun onFormChanged(form: ConfigFormState) {
                currentForm.value = form
            }
        }
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    ConfigUiState(
                        currentForm.value, base.screenPresets, base.fontPresets, base.skins,
                        base.soundBanks, base.shaders, base.removableScreenPresets,
                    ),
                    events,
                )
            }
        }

        val mode = uiString(R.string.pref_screen_scale_type)
        val scale = uiString(R.string.PREF_SCALE_RATIO)
        scrollTo(scale)
        composeRule.onNodeWithText(scale).assertExists()
        composeRule.onNodeWithText(mode).performClick()
        composeRule.onNodeWithText(uiString(R.string.pref_screen_scale_type_fit)).performClick()
        composeRule.runOnIdle { assertEquals("100", currentForm.value.screenScaleRatio) }
        composeRule.onNodeWithText(scale).assertDoesNotExist()

        composeRule.onNodeWithText(mode).performClick()
        composeRule.onNodeWithText(uiString(R.string.pref_screen_scale_type_none)).performClick()
        scrollTo(scale)
        composeRule.onNodeWithText(scale).assertExists()
        composeRule.onNodeWithText(scale).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("")
        composeRule.onNodeWithText("OK").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("0")
        composeRule.onNodeWithText("OK").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("1001")
        composeRule.onNodeWithText("OK").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("1000")
        composeRule.onNodeWithText("OK").assertIsEnabled()
        composeRule.onNodeWithText(uiString(R.string.reset)).performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.runOnIdle { assertEquals("100", currentForm.value.screenScaleRatio) }

        composeRule.onNodeWithText(scale).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("450")
        composeRule.onNodeWithText("OK").performClick()
        composeRule.runOnIdle { assertEquals("450", currentForm.value.screenScaleRatio) }
        composeRule.onNodeWithText(scale).performClick()
        composeRule.onNodeWithText(uiString(R.string.reset)).performClick()
        composeRule.onNodeWithText(uiString(android.R.string.cancel)).performClick()
        composeRule.runOnIdle { assertEquals("450", currentForm.value.screenScaleRatio) }
        composeRule.onNodeWithText(mode).performClick()
        composeRule.onNodeWithText(uiString(R.string.pref_screen_scale_type_fill)).performClick()
        composeRule.runOnIdle { assertEquals("100", currentForm.value.screenScaleRatio) }
        composeRule.onNodeWithText(scale).assertDoesNotExist()
    }

    @Test
    fun filterShaderWarningOnlyAppearsForEnabledFilterAndCustomGlesShader() {
        val base = sampleState()
        val identity = ShaderInfo(uiString(R.string.identity_filter), "JL-Mod Plus")
        val custom = ShaderInfo("Test shader", "Test")
        val warning = uiString(R.string.config_warning_filter_shader)

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

        val snapshot = mutableStateOf(state(filterEnabled = true, graphicsMode = 1, shader = custom))
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(snapshot.value, RecordingConfigEvents(), initialDestination = ConfigDestination.Display)
            }
        }
        fun render(state: ConfigUiState) {
            composeRule.runOnIdle { snapshot.value = state }
            scrollTo(uiString(R.string.PREF_FILTER))
        }

        render(state(filterEnabled = true, graphicsMode = 1, shader = custom))
        composeRule.onNodeWithText(warning, substring = true).assertExists()

        render(state(filterEnabled = false, graphicsMode = 1, shader = custom))
        composeRule.onNodeWithText(warning, substring = true).assertDoesNotExist()

        render(state(filterEnabled = true, graphicsMode = 1, shader = identity))
        composeRule.onNodeWithText(warning, substring = true).assertDoesNotExist()

        render(state(filterEnabled = true, graphicsMode = 0, shader = custom))
        composeRule.onNodeWithText(warning, substring = true).assertDoesNotExist()
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

        scrollTo(uiString(R.string.config_analog_stick))
        composeRule.onNodeWithText(uiString(R.string.config_analog_stick)).assertIsEnabled()
        composeRule.onNodeWithText("Configure Gamepad").assertDoesNotExist()
        composeRule.onNodeWithText("Reset Analog Controls").assertDoesNotExist()
        composeRule.onNodeWithText(
            "No compatible gamepad is connected. Gamepad controls stay inactive until one is detected.",
        ).assertDoesNotExist()

        composeRule.onNodeWithText(uiString(R.string.config_analog_stick)).performClick()
        listOf(R.string.config_analog_stick_off, R.string.config_gamepad_directions_four,
            R.string.config_gamepad_directions_eight, R.string.config_gamepad_directions_numeric_v2).forEach {
            composeRule.onNode(hasText(uiString(it)) and hasAnyAncestor(isDialog())).assertExists()
        }
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
        scrollTo(uiString(R.string.pref_map_keys))
        composeRule.onNodeWithText(uiString(R.string.pref_map_keys)).assertExists()
    }

    @Test
    fun configDestinationsSupportHorizontalSwipe() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents())
            }
        }

        composeRule.onNodeWithText(uiString(R.string.config_basic_display)).assertExists()
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(uiString(R.string.config_display_appearance)).assertExists()

        composeRule.onRoot().performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(uiString(R.string.config_basic_display)).assertExists()
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
        // The compact bar must still expose all five destinations, including the last one.
        composeRule.onNodeWithContentDescription(uiString(R.string.config_destination_system))
            .performClick()
        scrollTo(uiString(R.string.config_edit_system_properties))
        composeRule.onNodeWithText(uiString(R.string.config_edit_system_properties)).assertExists()
    }

    @Test
    fun profileEditorKeepsGeneralSettingsWithoutProfileWorkflow() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), isProfile = true)
            }
        }

        composeRule.onNodeWithContentDescription(uiString(R.string.config_destination_general), useUnmergedTree = true).assertExists()
        scrollTo(uiString(R.string.config_screen_size))
        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).assertExists()
        scrollTo(uiString(R.string.PREF_TOUCH_INPUT))
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

        scrollTo(uiString(R.string.PREF_FILTER))
        composeRule.onNodeWithText(uiString(R.string.PREF_FILTER)).performClick()
        composeRule.onNodeWithContentDescription("Controls").performClick()
        composeRule.onNodeWithContentDescription(uiString(R.string.config_destination_general)).performClick()
        scrollTo(uiString(R.string.PREF_TOUCH_INPUT))
        composeRule.onNodeWithText(uiString(R.string.PREF_TOUCH_INPUT)).performClick()

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

        scrollTo(uiString(R.string.PREF_LIMIT_FPS))
        composeRule.onNodeWithText(uiString(R.string.PREF_LIMIT_FPS)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.fps_limit_maximum)).assertIsDisplayed()
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
    fun destructiveActionsLiveInGeneralAndRequireExplicitConfirmation() {
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

        // System no longer contains destructive actions; General does.
        composeRule.onNodeWithContentDescription(uiString(R.string.config_destination_system)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("config_reset_settings_action").assertDoesNotExist()
        composeRule.onNodeWithTag("config_clear_data_action").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(uiString(R.string.config_destination_general)).performClick()
        composeRule.waitForIdle()
        scrollTo(uiString(R.string.config_reset_all_settings))
        composeRule.onNodeWithTag("config_reset_settings_action").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "Reset this MIDlet’s settings and virtual controls to their defaults? MIDlet data will not be deleted.",
        ).assertExists()
        composeRule.onNode(hasText(uiString(R.string.config_reset_all_settings)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, menuActions.resetSettingsCalls)

        scrollTo(uiString(R.string.config_delete_app_data))
        composeRule.onNodeWithTag("config_clear_data_action").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(
            "Permanently delete all saves and data created by this MIDlet? MIDlet settings will not be deleted.",
        ).assertExists()
        composeRule.onNode(hasText(uiString(R.string.config_delete_app_data)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, menuActions.clearDataCalls)

        composeRule.onNodeWithContentDescription(uiString(R.string.config_destination_controls)).performClick()
        composeRule.waitForIdle()
        scrollTo(uiString(R.string.RESET_LAYOUT_CMD))
        composeRule.onNodeWithText(uiString(R.string.RESET_LAYOUT_CMD)).performClick()
        composeRule.onNodeWithText(uiString(R.string.message_reset_layout)).assertExists()
    }

    @Test
    fun profileResetUsesProfileSpecificConfirmation() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    sampleState(),
                    RecordingConfigEvents(),
                    isProfile = true,
                    initialDestination = ConfigDestination.Basic,
                    menuActions = RecordingMenuActions(),
                )
            }
        }

        composeRule.onNodeWithText(uiString(R.string.config_delete_app_data)).assertDoesNotExist()
        scrollTo(uiString(R.string.config_reset_all_settings))
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

        scrollTo(uiString(R.string.config_screen_size))
        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).performClick()
        composeRule.onNodeWithText("360 x 640").performClick()
        assertEquals("360", events.lastForm?.screenWidth)
        assertEquals("640", events.lastForm?.screenHeight)
        composeRule.onNodeWithText("Select").assertDoesNotExist()

        scrollTo(uiString(R.string.config_screen_size))
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

        scrollTo(uiString(R.string.PREF_ORIENTATION))
        composeRule.onNodeWithText(uiString(R.string.PREF_ORIENTATION)).performClick()
        composeRule.onNodeWithText("Landscape").performClick()

        assertEquals(3, events.lastForm?.orientation)
    }

    @Test
    fun controlsColorsStayInlineAndEachSwatchOpensTheExpectedPicker() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Controls)
            }
        }
        scrollTo(uiString(R.string.config_controls_virtual_keyboard_colors))
        composeRule.onNodeWithTag("controls_colors_section").assertExists()
        composeRule.onNodeWithTag("virtual_controls_color_preview").assertExists()
        composeRule.onNodeWithText(uiString(R.string.config_virtual_controls_preview_normal))
            .assertIsSelected()
        composeRule.onNodeWithText(uiString(R.string.config_virtual_controls_preview_pressed))
            .performClick()
        composeRule.onNodeWithText(uiString(R.string.config_virtual_controls_preview_pressed))
            .assertIsSelected()
        composeRule.onNodeWithText(uiString(R.string.config_controls_color_button_normal)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.config_controls_color_label_normal)).assertExists()
        val samples = listOf(
            "color_normal_button" to ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_BACKGROUND,
            "color_pressed_button" to ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_BACKGROUND,
            "color_normal_labels" to ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_FOREGROUND,
            "color_pressed_labels" to ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_FOREGROUND,
            "color_outline" to ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_OUTLINE,
        )
        samples.forEach { (tag, field) ->
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            assertEquals(field, events.colorPickerField)
        }
        // The MIDlet toolbar remains accessible; no secondary destination was opened.
        composeRule.onNodeWithContentDescription(uiString(R.string.START_CMD)).assertExists()
    }

    @Test
    fun controlDescriptionsAndColorsRemainAvailableWhileVirtualControlsAreOff() {
        val base = sampleState()
        val state = ConfigUiState(
            base.form.toBuilder().showKeyboard(false).build(),
            base.screenPresets, base.fontPresets, base.skins, base.soundBanks,
            base.shaders, base.removableScreenPresets,
        )
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(state, RecordingConfigEvents(), initialDestination = ConfigDestination.Controls)
            }
        }
        composeRule.onNodeWithText(uiString(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS)).assertDoesNotExist()
        composeRule.onNodeWithText(uiString(R.string.config_help_key_layout)).assertExists()
        composeRule.onNodeWithText(uiString(R.string.config_controls_disabled_hint)).assertExists()
        scrollTo(uiString(R.string.config_controls_customization))
        composeRule.onNodeWithText(uiString(R.string.config_help_vk_button_shape)).assertExists()
        scrollTo(uiString(R.string.config_controls_virtual_keyboard_colors))
        composeRule.onNodeWithTag("color_outline").assertExists()
    }

    @Test
    fun keypadShapePrecedesSkinSelection() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), initialDestination = ConfigDestination.Controls)
            }
        }
        scrollTo(uiString(R.string.control_skin_title))
        composeRule.onNodeWithText(uiString(R.string.config_controls_skin_help)).assertExists()
        val shape = composeRule.onNodeWithText(uiString(R.string.pref_button_shape_title))
            .fetchSemanticsNode().boundsInRoot
        val skin = composeRule.onNodeWithText(uiString(R.string.control_skin_title))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Keypad shape must precede skin", shape.bottom <= skin.top)
    }

    @Test
    fun sliderValuesCommitFromTheirDialogs() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Controls)
            }
        }

        scrollTo(uiString(R.string.PREF_VK_ALPHA))
        composeRule.onNodeWithText("25%").performClick()
        composeRule.onNode(hasText("Opacity") and hasAnyAncestor(isDialog())).assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("50")
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
                    onBackgroundColorPicked = { value, dynamic ->
                        if (!dynamic) picked = value
                    },
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
                    onConfirm = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("D0D0D0").performTextReplacement("ABC")

        composeRule.onNodeWithText("Enter exactly six hexadecimal digits.").assertExists()
        composeRule.onNodeWithText("OK").assertIsNotEnabled()
    }

    @Test
    fun dynamicBackgroundPickerPreservesManualHex() {
        var selectedColor: String? = null
        var selectedDynamic: Boolean? = null
        composeRule.setContent {
            JLModPlusTheme {
                ConfigColorPickerDialog(
                    initialHex = "AABBCC",
                    allowDynamic = true,
                    onDismissRequest = {},
                    onConfirm = { value, dynamic ->
                        selectedColor = value
                        selectedDynamic = dynamic
                    },
                )
            }
        }

        val dynamicDescription = uiString(R.string.config_background_dynamic_help)
        val manualDescription = uiString(R.string.config_color_picker_hint)
        // A node in the semantics tree is not enough: the explanation must be
        // on screen without requiring the user to discover a hidden scroll.
        composeRule.onNodeWithText(dynamicDescription).assertIsDisplayed()
        composeRule.onNodeWithText(manualDescription).assertIsDisplayed()

        composeRule.onNodeWithText("Dynamic color").performClick()
        composeRule.onNodeWithText("AABBCC").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Saturation and brightness").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Hue").assertIsNotEnabled()
        // Both explanations stay visible; only the manual editing controls become disabled.
        composeRule.onNodeWithText(dynamicDescription).assertIsDisplayed()
        composeRule.onNodeWithText(manualDescription).assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("AABBCC", selectedColor)
        assertEquals(true, selectedDynamic)
    }

    @Test
    fun dynamicBackgroundPickerCanReturnToManualEditingWithoutLosingColor() {
        var picked: Pair<String, Boolean>? = null
        composeRule.setContent {
            JLModPlusTheme {
                ConfigColorPickerDialog(
                    initialHex = "AABBCC",
                    allowDynamic = true,
                    initialDynamic = true,
                    onDismissRequest = {},
                    onConfirm = { value, dynamic -> picked = value to dynamic },
                )
            }
        }

        composeRule.onNodeWithText("AABBCC").assertIsNotEnabled()
        composeRule.onNodeWithText(uiString(R.string.config_background_dynamic_help)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.config_color_picker_hint)).assertIsDisplayed()
        composeRule.onNodeWithText("Dynamic color").performClick()
        composeRule.onNodeWithText("AABBCC").assertIsEnabled()
        composeRule.onNodeWithText(uiString(R.string.config_background_dynamic_help)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.config_color_picker_hint)).assertIsDisplayed()
        composeRule.onNodeWithText("AABBCC").performTextReplacement("112233")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("112233" to false, picked)
    }

    @Test
    fun colorRowsOpenTheDedicatedPicker() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events, initialDestination = ConfigDestination.Display)
            }
        }

        scrollTo(uiString(R.string.config_background_custom_color))
        composeRule.onNodeWithText(uiString(R.string.config_background_custom_color)).performClick()

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

        scrollTo(uiString(R.string.config_background_mode))
        composeRule.onNodeWithText(uiString(R.string.config_background_mode)).performClick()
        composeRule.onNodeWithText("Immersive").performClick()
        assertEquals(BackgroundMode.IMMERSIVE, events.lastForm?.screenBackgroundMode)
        composeRule.onNodeWithText(uiString(R.string.config_background_custom_color)).assertDoesNotExist()

        composeRule.onNodeWithText(uiString(R.string.config_background_mode)).performClick()
        composeRule.onNodeWithText("Custom").performClick()
        assertEquals("D0D0D0", events.lastForm?.screenBackground)
        composeRule.onNodeWithText(uiString(R.string.config_background_custom_color)).assertExists()
    }

    @Test
    fun customScreenPresetCanBeRemovedFromPresetDialog() {
        val events = RecordingConfigEvents()
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), events)
            }
        }

        scrollTo(uiString(R.string.config_screen_size))
        composeRule.onNodeWithText(uiString(R.string.config_screen_size)).performClick()
        composeRule.onNodeWithContentDescription(uiString(R.string.remove_screen_preset)).performClick()

        assertEquals(Size(360, 640), events.removed)
    }

    @Test
    fun resetSystemPropertiesChangesOnlyDraftUntilSave() {
        var saved: String? = null
        composeRule.setContent {
            JLModPlusTheme {
                ConfigSystemPropertiesPage(
                    value = "microedition.platform: Custom\n",
                    defaultValue = "microedition.platform: Built-in\n",
                    onBack = {},
                    onSave = { saved = it },
                )
            }
        }
        composeRule.onNodeWithText(uiString(R.string.config_system_properties_reset)).performClick()
        composeRule.onNodeWithText(uiString(R.string.config_system_properties_reset_title)).assertExists()
        composeRule.onNode(
            hasText(uiString(R.string.config_system_properties_reset)) and hasAnyAncestor(isDialog())
        ).performClick()
        assertEquals(null, saved)
        composeRule.onNodeWithText(uiString(R.string.save)).performClick()
        assertEquals("microedition.platform: Built-in\n", saved)
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
        scrollTo(uiString(R.string.PREF_VK_HIDE_DELAY))
        composeRule.onNodeWithText("250 ms").assertExists()
        composeRule.onNodeWithContentDescription("System").performClick()
        scrollTo(uiString(R.string.config_edit_system_properties))
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
            .screenScaleType(1)
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
            listOf("None"),
            listOf("Android (default)"),
            emptyList(),
            listOf(Size(360, 640)),
        )
    }

    private class RecordingConfigEvents : ConfigFormEvents {
        var storageMode: org.microemu.cldc.file.StorageMode? = null
        override fun onStorageModeChanged(mode: org.microemu.cldc.file.StorageMode) {
            storageMode = mode
        }
        var filePermission: Pair<Boolean, Int>? = null
        override fun onFilePermissionChanged(write: Boolean, decision: Int) {
            filePermission = write to decision
        }
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
