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

package io.github.h3nb.jlmodplus.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameRateSectionComposeTest {
    @get:Rule val composeRule = createComposeRule()

    private fun string(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun fpsPositionOnlyAppearsWhenEnabledAndSelectionPersists() {
        var form by mutableStateOf(ConfigFormState.builder().build())
        composeRule.setContent {
            JLModPlusTheme {
                FrameRateSection(form, onFormChanged = { form = it })
            }
        }
        composeRule.onNodeWithText(string(R.string.config_fps_position_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.config_show_fps_title)).performClick()
        composeRule.onNodeWithText(string(R.string.config_fps_position_title))
            .assertIsDisplayed().performClick()
        composeRule.onNodeWithText(string(R.string.perf_overlay_bottom_right)).performClick()
        composeRule.runOnIdle {
            assertEquals(PerformanceOverlayOptions.BOTTOM_RIGHT, form.performanceOverlayPosition)
        }
        composeRule.onNodeWithText(string(R.string.config_show_fps_title)).performClick()
        composeRule.onNodeWithText(string(R.string.config_fps_position_title)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(PerformanceOverlayOptions.BOTTOM_RIGHT, form.performanceOverlayPosition)
        }
    }
}
