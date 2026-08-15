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

package ru.playsoftware.j2meloader.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
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
    fun mappingPromptShowsCurrentHardwareKeyAndCanBeDismissed() {
        var dismissed = false
        composeRule.setContent {
            JLModPlusTheme {
                KeyMapperScreen(
                    state = KeyMapperUiState(
                        mappingDialog = KeyMapperMappingDialog(
                            canvasKey = KeyMapper.KEY_OPTIONS_MENU,
                            currentKeyName = "KEYCODE_BACK",
                        ),
                    ),
                    actions = recordingActions(onDismiss = { dismissed = true }),
                )
            }
        }

        composeRule.onNodeWithText("Press a key").assertIsDisplayed()
        composeRule.onNodeWithText("Current mapping:\nKEYCODE_BACK").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss mapping").performClick()
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
