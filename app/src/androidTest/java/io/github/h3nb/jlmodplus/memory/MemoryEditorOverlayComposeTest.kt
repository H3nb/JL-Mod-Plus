/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package io.github.h3nb.jlmodplus.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.view.KeyEvent
import android.view.Window
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MemoryEditorOverlayComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun peekAboveNestedDialogsHidesEveryWindowAndKeepsItsPosition() {
        val overlay = MemoryEditorOverlayState()
        val nested = mutableStateOf(false)
        val windows = mutableMapOf<String, Window>()
        rule.setContent {
            JLModPlusTheme {
                MemoryEditorOverlayScope(overlay) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                    MemoryEditorDialog(onDismissRequest = {}, text = {
                        val view = LocalView.current
                        SideEffect { windows["form"] = (view.parent as DialogWindowProvider).window }
                        TextButton(onClick = { nested.value = true }) { Text("Choices") }
                        if (nested.value) {
                            MemoryEditorDialog(onDismissRequest = { nested.value = false }, text = {
                                val menuView = LocalView.current
                                SideEffect { windows["menu"] = (menuView.parent as DialogWindowProvider).window }
                                Text("Nested choice")
                            })
                        }
                    })
                }
            }
        }
        val originalBounds = rule.onNodeWithTag("memory-editor-peek").getUnclippedBoundsInRoot()
        rule.onNodeWithText("Choices").performClick()
        assertEquals(originalBounds, rule.onNodeWithTag("memory-editor-peek").getUnclippedBoundsInRoot())
        // Nested modal windows must not bring back Android navigation controls when
        // they take focus (e.g. the Memory Editor overflow menu).
        rule.waitUntil(5_000) {
            windows["menu"]?.let { menu ->
                ViewCompat.getRootWindowInsets(menu.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.navigationBars()) == false
            } == true
        }
        rule.onNodeWithTag("memory-editor-peek").performTouchInput { down(center) }
        rule.runOnIdle {
            assertTrue(overlay.peeking)
            assertEquals(2, windows.size)
            windows.values.forEach {
                assertEquals("The input window must remain visible to Android", 1f, it.attributes.alpha, 0f)
                assertEquals(0f, it.attributes.dimAmount, 0f)
            }
        }
        // Check the rendered composite, not just a window attribute: every modal and its dim
        // must disappear so the original underlay is visible through their former centers.
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            assertEquals(android.graphics.Color.MAGENTA, screenshot.getPixel(screenshot.width / 2, screenshot.height / 2))
        } finally {
            screenshot.recycle()
        }
        rule.onNodeWithTag("memory-editor-peek").performTouchInput {
            moveTo(Offset(center.x - 64f, center.y - 64f))
        }
        rule.runOnIdle {
            assertTrue("A small drift must not reveal the windows before release", overlay.peeking)
        }
        rule.onNodeWithTag("memory-editor-peek").performTouchInput { up() }
        rule.runOnIdle {
            assertFalse(overlay.peeking)
            windows.values.forEach { assertEquals(1f, it.attributes.alpha, 0f) }
            assertTrue(nested.value)
        }
        pressBack()
        rule.onNodeWithText("Nested choice").assertDoesNotExist()
        assertEquals(originalBounds, rule.onNodeWithTag("memory-editor-peek").getUnclippedBoundsInRoot())
    }

    @Test fun directSearchAndPeekPreserveInputAndCursorAcrossAChoiceMenu() {
        val overlay = MemoryEditorOverlayState()
        rule.setContent {
            JLModPlusTheme {
                MemoryEditorRuntimeRoot(MemoryEditorUiState(visible = true, connected = true, supported = true), TestMemoryEditorActions(), overlay)
            }
        }
        rule.onNodeWithContentDescription("Search known values").performClick()
        val field = rule.onNode(hasSetTextAction())
        field.performTextReplacement("31415")
        field.performTextInputSelection(TextRange(2))
        rule.onNodeWithText("Auto").performClick()
        rule.onNodeWithTag("memory-editor-peek").performTouchInput { down(center) }
        rule.runOnIdle { assertTrue(overlay.peeking) }
        rule.onNodeWithTag("memory-editor-peek").performTouchInput { up() }
        pressBack()
        field.assertTextContains("31415")
        rule.onNodeWithText("7").performScrollTo().performClick()
        field.assertTextContains("317415")
    }

    @Test fun accessibilityPeekAndBackRestoreTheOpenForm() {
        val overlay = MemoryEditorOverlayState()
        rule.setContent {
            JLModPlusTheme {
                MemoryEditorRuntimeRoot(MemoryEditorUiState(visible = true, connected = true, supported = true), TestMemoryEditorActions(), overlay)
            }
        }
        rule.onNodeWithContentDescription("Search known values").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("42")
        // Semantic activation toggles Peek for users who cannot hold a touch gesture.
        rule.onNodeWithTag("memory-editor-peek").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertTrue(overlay.peeking) }
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        pressBack()
        rule.runOnIdle { assertFalse(overlay.peeking) }
        rule.onNode(hasSetTextAction()).assertTextContains("42")
        pressBack()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun quickActionsStayStableAndUndoRemainsAvailableAfterAnEmptyScan() {
        val state = mutableStateOf(MemoryEditorUiState(
            visible = true, connected = true, supported = true, writeSupported = true,
            resultCount = 1, results = listOf(MemoryResultRow(
                id = 1, valueText = "42", locationText = "score", aliasMask = 1 shl MemoryEngineContract.TYPE_INT,
                primaryType = MemoryEngineContract.TYPE_INT, state = MemoryEngineContract.CANDIDATE_STABLE,
                relocations = 0,
            )),
        ))
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(720.dp, 360.dp))) {
                JLModPlusTheme { MemoryEditorRuntimeRoot(state.value, TestMemoryEditorActions()) }
            }
        }
        val labels = listOf("Search known values", "Search unknown values", "Edit", "Add selected values to Watch", "Refresh")
        val bounds = labels.map { rule.onNodeWithContentDescription(it).getUnclippedBoundsInRoot() }
        rule.onNodeWithContentDescription("Edit").assertIsNotEnabled()
        rule.runOnIdle { state.value = state.value.copy(selected = setOf(1L)) }
        rule.onNodeWithContentDescription("Edit").assertIsEnabled()
        labels.forEachIndexed { index, label ->
            assertEquals(bounds[index], rule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot())
        }
        rule.runOnIdle {
            state.value = state.value.copy(results = emptyList(), resultCount = 0, selected = emptySet(), canUndo = true)
        }
        rule.onNodeWithContentDescription("More").performClick()
        val menuBounds = rule.onNodeWithTag("adaptive-dialog-surface").getUnclippedBoundsInRoot()
        assertTrue(menuBounds.right - menuBounds.left <= 400.dp)
        rule.onNodeWithText("Undo").performScrollTo().assertIsEnabled()
    }

    @Test fun unsupportedRuntimeExplainsUnavailabilityAndDisablesBothSearches() {
        val explanation = "Installed MIDlet conversion is incompatible; reconvert using JL-Mod Plus."
        rule.setContent {
            JLModPlusTheme {
                MemoryEditorRuntimeRoot(
                    MemoryEditorUiState(visible = true, connected = true,
                        supported = false, message = explanation, messageIsError = true),
                    TestMemoryEditorActions(),
                )
            }
        }
        rule.onNodeWithText(explanation).assertIsDisplayed()
        rule.onNodeWithContentDescription("Search known values").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Search unknown values").assertIsNotEnabled()
    }

    @Test fun opacitySurvivesClosingAndReopeningTheWorkspace() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("memory_editor_ui", 0)
        val previous = preferences.getFloat("panel_opacity", MemoryEditorOverlayState.DEFAULT_OPACITY)
        val existed = preferences.contains("panel_opacity")
        val visible = mutableStateOf(true)
        try {
            rule.setContent {
                JLModPlusTheme {
                    MemoryEditorRuntimeRoot(MemoryEditorUiState(visible = visible.value), TestMemoryEditorActions())
                }
            }
            rule.onNodeWithContentDescription("Panel opacity").performClick()
            rule.onNodeWithContentDescription("Panel opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
            rule.onNodeWithText("OK").performClick()
            rule.runOnIdle { visible.value = false }
            rule.runOnIdle { visible.value = true }
            rule.onNodeWithContentDescription("Panel opacity").performClick()
            rule.onNodeWithText("60%").assertExists()
            assertEquals(0.6f, preferences.getFloat("panel_opacity", -1f), 0.001f)
        } finally {
            preferences.edit().apply {
                if (existed) putFloat("panel_opacity", previous) else remove("panel_opacity")
            }.commit()
        }
    }
    private fun pressBack() {
        // Inject system Back into the focused dialog, not Espresso's Activity root.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

}
