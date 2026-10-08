/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.config

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verify destination taps do not traverse intervening settings, on bar or adaptive rail. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ConfigTabNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun compactBarJumpsDirectlyBetweenBasicAndSystem() =
        verifyDirectJump(DpSize(360.dp, 640.dp))

    @Test fun expandedRailJumpsDirectlyBetweenBasicAndSystem() =
        verifyDirectJump(DpSize(820.dp, 640.dp))

    private fun verifyDirectJump(windowSize: DpSize) {
        val events = object : ConfigFormEvents {
            override fun onFormChanged(state: ConfigFormState) = Unit
            override fun onAddResolutionPreset(size: Size) = Unit
            override fun onRemoveResolutionPreset(size: Size) = Unit
            override fun onColorPicker(field: ConfigFormEvents.ColorField) = Unit
            override fun onColorPicked(field: ConfigFormEvents.ColorField, value: String) = Unit
            override fun onKeyMappings() = Unit
            override fun onEncodingPicker() = Unit
            override fun onShaderTuning() = Unit
        }
        val form = ConfigFormState.builder()
            .screenWidth("240")
            .screenHeight("320")
            .screenScaleRatio("100")
            .fontSizeSmall("18")
            .fontSizeMedium("22")
            .fontSizeLarge("26")
            .build()
        val state = ConfigUiState(
            form,
            listOf(Size(240, 320)),
            listOf(ConfigUiState.FontPreset("240 x 320", 18, 22, 26)),
            listOf("None"),
            listOf("Android (default)"),
            emptyList(),
            emptyList(),
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(windowSize)) {
                JLModPlusTheme { ConfigScreen(state, events) }
            }
        }

        val system = label(R.string.config_destination_system)
        val basic = label(R.string.config_destination_general)
        val displayHeading = label(R.string.config_display_appearance)
        val controlsHeading = label(R.string.config_controls_key_input)

        fun assertIntermediatesHidden() {
            assertFalse(composeRule.onNodeWithText(displayHeading).isDisplayed())
            assertFalse(composeRule.onNodeWithText(controlsHeading).isDisplayed())
        }

        // Material 3 navigation items expose icon labels in the unmerged semantics tree.
        // Tap the actual icon bounds and still assert the final page and skipped destinations.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithContentDescription(system, useUnmergedTree = true).performClick()
            repeat(18) {
                composeRule.mainClock.advanceTimeByFrame()
                assertIntermediatesHidden()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label(R.string.PREF_SYS_PROPS)).assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithContentDescription(basic, useUnmergedTree = true).performClick()
            repeat(18) {
                composeRule.mainClock.advanceTimeByFrame()
                assertIntermediatesHidden()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label(R.string.config_screen_size)).assertIsDisplayed()
    }

    private fun label(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
