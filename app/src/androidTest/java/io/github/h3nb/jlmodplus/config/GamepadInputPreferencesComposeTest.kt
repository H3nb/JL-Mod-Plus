/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.h3nb.jlmodplus.config

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import javax.microedition.lcdui.keyboard.VirtualControlsKeyboard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamepadInputPreferencesComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun analogChoiceDoesNotRequireControllerButCalibrationDoes() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigSection(title = "Key Input") {
                    GamepadInputPreferences(
                        form = ConfigFormState.builder().build(),
                        controllerAvailable = false,
                        onFormChanged = {},
                        events = NoOpEvents,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Analog Stick").assertIsEnabled()
        composeRule.onNodeWithText("Calibrate Controller").assertIsNotEnabled()
    }

    @Test
    fun calibrationDispatchesWhenControllerIsAvailable() {
        var calibrationRequests = 0
        val events = object : ConfigFormEvents by NoOpEvents {
            override fun onGamepadCalibration() {
                calibrationRequests++
            }
        }
        composeRule.setContent {
            JLModPlusTheme {
                ConfigSection(title = "Key Input") {
                    GamepadInputPreferences(
                        form = ConfigFormState.builder().build(),
                        controllerAvailable = true,
                        onFormChanged = {},
                        events = events,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Calibrate Controller").assertIsEnabled().performClick()
        assertEquals(1, calibrationRequests)
    }

    @Test
    fun standardMovementTemplatesAreAppendedWithoutReorderingLegacyTemplates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = context.resources.getStringArray(R.array.PREF_VK_TYPE_ENTRIES).toList()

        assertEquals(
            listOf(
                "Custom",
                "Phone",
                "Phone (Arrows)",
                "Numbers & Arrows",
                "Arrows & Numbers",
                "Numbers",
                "Arrows",
            ),
            entries.take(7),
        )
        assertEquals("D-pad Standard", entries[VirtualControlsKeyboard.TYPE_DPAD_STANDARD])
        assertEquals("Analog Standard", entries[VirtualControlsKeyboard.TYPE_ANALOG_STANDARD])
        assertEquals(9, entries.size)
    }

    private object NoOpEvents : ConfigFormEvents {
        override fun onFormChanged(state: ConfigFormState) = Unit
        override fun onAddResolutionPreset(size: Size) = Unit
        override fun onRemoveResolutionPreset(size: Size) = Unit
        override fun onColorPicker(field: ConfigFormEvents.ColorField) = Unit
        override fun onColorPicked(field: ConfigFormEvents.ColorField, value: String) = Unit
        override fun onKeyMappings() = Unit
        override fun onEncodingPicker() = Unit
        override fun onShaderTuning() = Unit
    }
}
