/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package io.github.h3nb.jlmodplus.memory

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

class MemoryEditorPeekComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun peekWindowStaysFullyTransparentUntilRelease() {
        var observedAlpha = Float.NaN

        rule.setContent {
            var peeking by remember { mutableStateOf(false) }
            JLModPlusTheme {
                Dialog(onDismissRequest = {}) {
                    RuntimeInputDialogWindowEffect(peeking)
                    val view = LocalView.current
                    SideEffect {
                        observedAlpha =
                            (view.parent as? DialogWindowProvider)?.window?.attributes?.alpha
                                ?: Float.NaN
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("peek")
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        peeking = true
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            peeking = false
                                        }
                                    },
                                )
                            },
                    )
                }
            }
        }

        rule.waitForIdle()
        rule.onNodeWithTag("peek").performTouchInput {
            down(center)
            advanceEventTime(400)
        }
        rule.runOnIdle { assertEquals(0f, observedAlpha, 0f) }

        rule.onNodeWithTag("peek").performTouchInput { up() }
        rule.runOnIdle { assertEquals(1f, observedAlpha, 0f) }
    }

    @Test fun pagerUsesLocalizedActionDescriptionsInsteadOfArrowGlyphs() {
        rule.setContent {
            JLModPlusTheme {
                MemoryEditorRuntimeRoot(
                    state = MemoryEditorUiState(
                        visible = true,
                        connected = true,
                        supported = true,
                        resultCount = MemoryEditorComposeController.PAGE_SIZE.toLong() + 1L,
                    ),
                    actions = NoOpMemoryEditorActions,
                )
            }
        }

        rule.onNodeWithContentDescription("Previous results page").assertIsDisplayed()
        rule.onNodeWithContentDescription("Next results page").assertIsDisplayed()
    }

    @Test fun bubbleAccessibilityCopyDescribesTheAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Open Memory Editor", context.getString(R.string.memory_editor_bubble_content_description))
    }

    private object NoOpMemoryEditorActions : MemoryEditorActions {
        override fun close() = Unit
        override fun refreshCapabilities() = Unit
        override fun startSearch(value: String, secondValue: String, type: Int, predicate: Int, unknown: Boolean) = Unit
        override fun nextScan(value: String, secondValue: String, predicate: Int, compare: Int, type: Int) = Unit
        override fun groupSearch(type: Int, values: Array<String>) = Unit
        override fun undo() = Unit
        override fun refresh() = Unit
        override fun setWatchTab(watch: Boolean) = Unit
        override fun toggleSelection(id: Long) = Unit
        override fun selectVisible() = Unit
        override fun invertVisible() = Unit
        override fun clearSelection() = Unit
        override fun editSelected(value: String, type: Int) = Unit
        override fun removeSelected(keep: Boolean) = Unit
        override fun watchSelected(add: Boolean) = Unit
        override fun labelWatch(id: Long, label: String) = Unit
        override fun freezeSelected(mode: Int, first: String, second: String) = Unit
        override fun clearFreezeSelected() = Unit
        override fun copySelected(locations: Boolean) = Unit
        override fun previousPage() = Unit
        override fun nextPage() = Unit
        override fun cancel() = Unit
    }

}
