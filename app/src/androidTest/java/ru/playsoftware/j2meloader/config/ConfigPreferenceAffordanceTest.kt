/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.config

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class ConfigPreferenceAffordanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun popupPreferencesDoNotUseNavigationChevron() {
        composeRule.setContent {
            JLModPlusTheme {
                Column {
                    ConfigValuePreference(
                        title = "Screen size",
                        description = "Logical Java ME screen resolution.",
                        value = "240 × 320",
                        onClick = {},
                    )
                    ConfigColorPreference(
                        title = "Foreground",
                        description = "Virtual-key label color.",
                        value = "000080",
                        onClick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("›").assertDoesNotExist()
        composeRule.onNodeWithText("#000080").assertExists()
    }
}
