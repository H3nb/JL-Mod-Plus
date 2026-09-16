/*
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

package io.github.h3nb.jlmodplus.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import javax.microedition.lcdui.keyboard.KeyMapper

@RunWith(AndroidJUnit4::class)
class KeyMapperComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersAllVirtualKeyRowsAndEmitsSelection() {
        var selected: Int? = null
        composeRule.setContent {
            JLModPlusTheme {
                KeyMapperScreen(
                    state = KeyMapperUiState(),
                    actions = recordingActions(onVirtualKey = { selected = it }),
                )
            }
        }

        composeRule.onNodeWithText("A").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("M").assertIsDisplayed()
        composeRule.onNodeWithText("0").assertIsDisplayed()
        composeRule.onNodeWithText("#").assertIsDisplayed()
        assertEquals(KeyMapper.SE_KEY_SPECIAL_GAMING_A, selected)
    }

    @Test
    fun mappingPromptShowsAllHardwareKeysAndCanBeDismissed() {
        var dismissed = false
        composeRule.setContent {
            JLModPlusTheme {
                KeyMapperScreen(
                    state = KeyMapperUiState(
                        mappingDialog = KeyMapperMappingDialog(
                            canvasKey = KeyMapper.KEY_OPTIONS_MENU,
                            assignedInputs = listOf(
                                KeyMapperAssignedInput(4, "KEYCODE_BACK"),
                                KeyMapperAssignedInput(96, "KEYCODE_BUTTON_A"),
                            ),
                        ),
                    ),
                    actions = recordingActions(onDismiss = { dismissed = true }),
                )
            }
        }

        composeRule.onNodeWithText("Press A Key").assertIsDisplayed()
        composeRule.onNodeWithText("Assigned inputs").assertIsDisplayed()
        composeRule.onNodeWithText("KEYCODE_BACK").assertIsDisplayed()
        composeRule.onNodeWithText("KEYCODE_BUTTON_A").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss mapping")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(dismissed)
    }

    @Test
    fun missingMenuWarningOffersSaveAndCancelWithoutChangingDispatchState() {
        var saved = false
        var dismissed = false
        composeRule.setContent {
            JLModPlusTheme {
                KeyMapperScreen(
                    state = KeyMapperUiState(warningVisible = true),
                    actions = recordingActions(
                        onDismissWarning = { dismissed = true },
                        onSave = { saved = true },
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Warning").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()
        assertTrue(saved)
        assertTrue(!dismissed)
    }

    private fun recordingActions(
        onVirtualKey: (Int) -> Unit = {},
        onDismiss: () -> Unit = {},
        onDismissWarning: () -> Unit = {},
        onSave: () -> Unit = {},
    ) = object : KeyMapperActions {
        override fun onVirtualKey(canvasKey: Int) = onVirtualKey(canvasKey)
        override fun onDismissMapping() = onDismiss()
        override fun onDismissWarning() = onDismissWarning()
        override fun onSaveAndExit() = onSave()
    }
}
