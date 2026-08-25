/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.config

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.config.model.Size
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class ProfileManagerComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mixedModifiedSourcesExposeIndependentKeepActions() {
        val events = RecordingEvents()
        val status = ConfigUiState.ProfileStatus.components(
            "Nokia S40",
            true,
            "Touch Landscape",
            true,
            false,
            null,
        )

        composeRule.setContent {
            JLModPlusTheme {
                ConfigProfilePanel(
                    status = status,
                    templates = listOf(
                        ConfigUiState.ProfileTemplate("Nokia S40", false, true, false),
                        ConfigUiState.ProfileTemplate("Touch Landscape", false, false, true),
                    ),
                    events = events,
                )
            }
        }

        composeRule.onNodeWithText("Nokia S40").assertExists()
        composeRule.onNodeWithText("Touch Landscape").assertExists()

        composeRule.onNodeWithText("Keep Settings for This App").performClick()
        composeRule.onNodeWithText("Keep Keyboard for This App").performClick()

        assertEquals(1, events.keepSettingsCalls)
        assertEquals(1, events.keepKeyboardCalls)
    }

    @Test
    fun updatingModifiedProfileRequiresGlobalConfirmation() {
        val events = RecordingEvents()
        val status = ConfigUiState.ProfileStatus.components(
            "Nokia S40",
            true,
            null,
            false,
            false,
            null,
        )

        composeRule.setContent {
            JLModPlusTheme {
                ConfigProfilePanel(
                    status = status,
                    templates = listOf(
                        ConfigUiState.ProfileTemplate("Nokia S40", false, true, false),
                    ),
                    events = events,
                )
            }
        }

        composeRule.onNodeWithText("Update Template").performClick()
        composeRule.onNodeWithText("Update Nokia S40?").assertExists()
        assertEquals(0, events.updateCalls)

        composeRule.onNodeWithText("Update Template", useUnmergedTree = true).performClick()
        assertEquals(1, events.updateCalls)
    }

    private class RecordingEvents : ConfigFormEvents {
        var keepSettingsCalls = 0
        var keepKeyboardCalls = 0
        var updateCalls = 0

        override fun onFormChanged(state: ConfigFormState) = Unit
        override fun onAddResolutionPreset(size: Size) = Unit
        override fun onRemoveResolutionPreset(size: Size) = Unit
        override fun onColorPicker(field: ConfigFormEvents.ColorField) = Unit
        override fun onColorPicked(field: ConfigFormEvents.ColorField, value: String) = Unit
        override fun onKeyMappings() = Unit
        override fun onEncodingPicker() = Unit
        override fun onShaderTuning() = Unit

        override fun onKeepSettingsForApp() {
            keepSettingsCalls++
        }

        override fun onKeepKeyboardForApp() {
            keepKeyboardCalls++
        }

        override fun onUpdateTemplate(name: String) {
            updateCalls++
        }
    }
}
