/*
 * Copyright 2026 H3NB
 *
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

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConfigComposeDialogHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun duplicateChoiceLabelSelectsCorrectIndex() {
        var received = -1
        val state = ConfigDialogState()
        state.showChoice(
            context = context,
            title = "Choose",
            entries = arrayOf("A", "B", "A"),
            selectedIndex = -1,
            cancelLabel = null,
            cancelable = true,
            onSelected = ComposeChoiceAction { received = it },
        )

        composeRule.setContent { state.Render() }

        composeRule.onAllNodesWithText("A")[2].performClick()
        composeRule.runOnIdle { assertEquals(2, received) }
    }

    @Test
    fun duplicateChoiceActionsLabelSelectsCorrectIndex() {
        var received = -1
        val state = ConfigDialogState()
        state.showChoiceActions(
            context = context,
            title = "Choose",
            entries = arrayOf("A", "B", "A"),
            selectedIndex = -1,
            positiveLabel = "OK",
            neutralLabel = null,
            negativeLabel = null,
            cancelable = true,
            neutralRequiresSelection = false,
            positiveAction = ComposeChoiceButtonAction { received = it },
            neutralAction = null,
            negativeAction = null,
        )

        composeRule.setContent { state.Render() }

        composeRule.onAllNodesWithText("A")[2].performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.runOnIdle { assertEquals(2, received) }
    }

    @Test
    fun duplicateProfileNameSelectsCorrectIndex() {
        var received = -1
        val state = ConfigDialogState()
        state.showLoadProfile(
            context = context,
            title = "Choose Profile",
            profileNames = arrayOf("A", "B", "A"),
            hasConfig = booleanArrayOf(true, true, true),
            hasKeyboard = booleanArrayOf(true, true, true),
            defaultIndex = -1,
            configLabel = "Config",
            keyboardLabel = "Keyboard",
            positiveLabel = "Load",
            negativeLabel = "Cancel",
            cancelable = true,
            onConfirmed = ConfigLoadProfileAction { index, _, _ -> received = index },
        )

        composeRule.setContent { state.Render() }

        composeRule.onAllNodesWithText("A")[2].performClick()
        composeRule.onNodeWithText("Load").performClick()
        composeRule.runOnIdle { assertEquals(2, received) }
    }
}
