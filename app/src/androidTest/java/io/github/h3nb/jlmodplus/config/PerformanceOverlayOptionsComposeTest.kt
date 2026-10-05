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

import io.github.h3nb.jlmodplus.R
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun uiString(resId: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PerformanceOverlayOptionsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shortWindowWithLargeTextSupportsLastMetricBackAndDone() {
        var metrics by mutableStateOf(PerformanceOverlayOptions.ALL)
        var visible by mutableStateOf(true)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(640.dp, 320.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    JLModPlusTheme {
                        Button(onClick = { visible = true }) { Text("Open Parameters") }
                        if (visible) {
                            PerformanceOverlayParametersDialog(
                                selectedMetrics = metrics,
                                onMetricsChanged = { metrics = it },
                                onDismissRequest = { visible = false },
                            )
                        }
                    }
                }
            }
        }
        val displayTag = "perf_metric_${PerformanceOverlayOptions.DISPLAY}"
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasTestTag(displayTag))
        composeRule.onNodeWithTag(displayTag).assertIsOn().performClick().assertIsOff()
        // Exercise native Back dismissal for the dialog.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithText("Done").assertDoesNotExist()

        composeRule.onNodeWithText("Open Parameters").performClick()
        composeRule.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasTestTag(displayTag))
        composeRule.onNodeWithTag(displayTag).assertIsOff()
        composeRule.onNodeWithText("Done").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Done").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(
                PerformanceOverlayOptions.ALL and PerformanceOverlayOptions.DISPLAY.inv(),
                metrics,
            )
        }
    }

    @Test
    fun presetAndLastCheckboxRemainReachableAndCustomSelectionPersists() {
        var form by mutableStateOf(ConfigFormState.builder().showFps(true).build())
        composeRule.setContent {
            JLModPlusTheme {
                Column { PerformanceOverlayPreferences(form, onFormChanged = { form = it }) }
            }
        }
        composeRule.onNodeWithText(uiString(R.string.perf_overlay_preset)).performClick()
        composeRule.onNodeWithText("Debug").performClick()
        composeRule.runOnIdle { assertEquals(PerformanceOverlayOptions.ALL, form.performanceOverlayMetrics) }

        composeRule.onNodeWithText(uiString(R.string.perf_overlay_parameters)).performClick()
        composeRule.onNodeWithTag("perf_metric_${PerformanceOverlayOptions.FPS}").assertIsOn()
        val displayTag = "perf_metric_${PerformanceOverlayOptions.DISPLAY}"
        composeRule.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasTestTag(displayTag))
        composeRule.onNodeWithTag(displayTag).assertIsOn().performClick().assertIsOff()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Custom").assertExists()
        composeRule.runOnIdle {
            assertEquals(
                PerformanceOverlayOptions.ALL and PerformanceOverlayOptions.DISPLAY.inv(),
                form.performanceOverlayMetrics,
            )
        }

        composeRule.onNodeWithText(uiString(R.string.perf_overlay_parameters)).performClick()
        composeRule.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasTestTag(displayTag))
        composeRule.onNodeWithTag(displayTag).assertIsOff()
        composeRule.onNodeWithText("Done").performClick()
    }
}
