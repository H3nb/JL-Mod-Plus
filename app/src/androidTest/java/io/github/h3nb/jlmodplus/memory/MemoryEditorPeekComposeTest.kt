/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package io.github.h3nb.jlmodplus.memory

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.up
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

class MemoryEditorPeekComposeTest {
    @get:Rule val rule = createComposeRule()

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
                    actions = TestMemoryEditorActions(),
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

}
