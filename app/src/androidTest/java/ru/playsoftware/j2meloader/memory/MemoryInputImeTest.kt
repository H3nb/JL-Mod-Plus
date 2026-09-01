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

package ru.playsoftware.j2meloader.memory

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.R

@RunWith(AndroidJUnit4::class)
class MemoryInputImeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun valueInputUsesInternalKeypadWithoutOpeningPlatformIme() {
        composeRule.setContent {
            MaterialTheme {
                MemoryInputArea {
                    var value by mutableStateOf("")
                    MemoryValueInput(
                        value = value,
                        onValueChange = { value = it },
                        spec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_INT),
                        label = "Value",
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Value").performClick()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.memory_editor_keypad_hide),
        ).assertIsDisplayed()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val imeVisible = ViewCompat.getRootWindowInsets(
                composeRule.activity.window.decorView,
            )?.isVisible(WindowInsetsCompat.Type.ime()) == true
            assertFalse("Memory Editor input must not open the platform IME", imeVisible)
        }
    }
}
