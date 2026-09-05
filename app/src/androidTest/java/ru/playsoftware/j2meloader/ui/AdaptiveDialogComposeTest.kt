/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package ru.playsoftware.j2meloader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AdaptiveDialogComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun actionMenuWrapsContentWithoutAnEmptyFooter() {
        val footer = mutableStateOf(false)
        rule.setContent {
            JLModPlusTheme {
                AdaptiveAlertDialog(onDismissRequest = {}, title = { Text("Menu") },
                    text = { Column { Text("First", Modifier.height(48.dp)); Text("Second", Modifier.height(48.dp)) } },
                    confirmButton = if (footer.value) ({ TextButton(onClick = {}) { Text("Close") } }) else null)
            }
        }
        val without = rule.onNodeWithTag("adaptive-dialog-surface").getUnclippedBoundsInRoot()
        assertTrue("A two-item menu must wrap rather than reserve the window height",
            without.bottom - without.top < 200.dp)
        rule.runOnIdle { footer.value = true }
        val with = rule.onNodeWithTag("adaptive-dialog-surface").getUnclippedBoundsInRoot()
        assertTrue("Footer space should exist only when there is an action",
            with.bottom - with.top >= without.bottom - without.top + 48.dp)
    }

    @Test fun shortLargeTextDialogKeepsActionsVisibleAndBodyScrollable() {
        var accepted = 0
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp))) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    JLModPlusTheme(darkTheme = true, accent = AccentPalette.Teal) {
                        AdaptiveAlertDialog(onDismissRequest = {}, title = { Text("Recovery") },
                            text = { Column { repeat(8) { Text("Recover application data safely.") }; Text("Last instruction") } },
                            confirmButton = { TextButton(onClick = { accepted++ }) { Text("Retry") } },
                            dismissButton = { TextButton(onClick = {}) { Text("Close") } })
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("Swipe to continue").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onNodeWithText("Last instruction").performScrollTo().assertIsDisplayed()
        assertEquals(1, accepted)
    }
    @Test fun largeTextFontFormCanReachItsLastFieldAndConfirm() {
        var confirmed: List<String>? = null
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp))) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    JLModPlusTheme {
                        ru.playsoftware.j2meloader.config.FontSizesDialog(
                            small = "18", medium = "22", large = "26",
                            onDismissRequest = {},
                            onConfirm = { small, medium, large -> confirmed = listOf(small, medium, large) },
                        )
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("Swipe to continue").assertIsDisplayed()
        rule.onNodeWithText("26").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed().performClick()
        assertEquals(listOf("18", "22", "26"), confirmed)
    }

}
