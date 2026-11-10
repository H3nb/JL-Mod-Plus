/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.config

import androidx.test.espresso.Espresso
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise navigation through the real Config UI rather than a duplicate page stack. */
@RunWith(AndroidJUnit4::class)
class ConfigPageTransitionTest {
    @get:Rule val composeRule = createComposeRule()

    private val events = object : ConfigFormEvents {
        override fun onFormChanged(state: ConfigFormState) = Unit
        override fun onAddResolutionPreset(size: Size) = Unit
        override fun onRemoveResolutionPreset(size: Size) = Unit
        override fun onColorPicker(field: ConfigFormEvents.ColorField) = Unit
        override fun onColorPicked(field: ConfigFormEvents.ColorField, value: String) = Unit
        override fun onKeyMappings() = Unit
        override fun onEncodingPicker() = Unit
    }

    @Test
    fun shaderToolbarBackReturnsToDisplay() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(state(graphicsMode = 1), events, initialDestination = ConfigDestination.Display)
            }
        }
        composeRule.onNodeWithText(label(R.string.render_shader_effects))
            .performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(label(R.string.action_back)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label(R.string.render_shader_effects)).assertIsDisplayed()
    }

    @Test
    fun systemPropertiesSystemBackReturnsToExistingTab() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(state(), events, initialDestination = ConfigDestination.System)
            }
        }
        composeRule.onNodeWithText(label(R.string.config_edit_system_properties))
            .performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label(R.string.config_help_system_properties_long)).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("UNSAVED_FIELD_TEST")

        // The IME has its own Back policy; close it before testing page navigation.
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label(R.string.config_edit_system_properties)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("UNSAVED_FIELD_TEST", substring = true).assertDoesNotExist()
    }

    private fun state(graphicsMode: Int = 0): ConfigUiState {
        val form = ConfigFormState.builder()
            .screenWidth("240")
            .screenHeight("320")
            .screenScaleRatio("100")
            .fontSizeSmall("18")
            .fontSizeMedium("22")
            .fontSizeLarge("26")
            .graphicsMode(graphicsMode)
            .build()
        return ConfigUiState(
            form,
            listOf(Size(240, 320)),
            listOf(ConfigUiState.FontPreset("240 x 320", 18, 22, 26)),
            listOf("None"),
            listOf("Android (default)"),
            emptyList(),
            emptyList(),
        )
    }

    private fun label(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
