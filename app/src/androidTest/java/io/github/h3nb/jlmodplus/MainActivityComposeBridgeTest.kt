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

package io.github.h3nb.jlmodplus

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.h3nb.jlmodplus.input.HostCommand
import io.github.h3nb.jlmodplus.ui.ControllerDialogInputScope
import io.github.h3nb.jlmodplus.ui.ControllerHostCommandHandler
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainActivityComposeBridgeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun controllerNavigationActivatesSelectedDirectoryAction() {
        val events = mutableListOf<String>()
        var handler: ControllerHostCommandHandler? = null
        composeRule.setContent {
            JLModPlusTheme {
                ControllerDialogInputScope(
                    onControllerKeyEvent = { false },
                    onControllerHostCommandHandlerChanged = { handler = it },
                ) {
                    MainHostDialogs(
                        state = MainHostUiState(MainHostDialog.DirectoryFailure("Directory unavailable")),
                        actions = RecordingMainHostActions(events),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val current = checkNotNull(handler)
            current(HostCommand.NavigateRight, true)
            current(HostCommand.Activate, true)
            current(HostCommand.Activate, false)
        }

        assertEquals(listOf("choose"), events)
    }

    @Test
    fun controllerBackDoesNotDiscardNonCancelableMainDialogAction() {
        val events = mutableListOf<String>()
        var handler: ControllerHostCommandHandler? = null
        composeRule.setContent {
            JLModPlusTheme {
                ControllerDialogInputScope(
                    onControllerKeyEvent = { false },
                    onControllerHostCommandHandlerChanged = { handler = it },
                ) {
                    MainHostDialogs(
                        state = MainHostUiState(MainHostDialog.PermissionFailure),
                        actions = RecordingMainHostActions(events),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            checkNotNull(handler)(HostCommand.Back, true)
        }

        assertEquals(emptyList<String>(), events)
    }
}

private class RecordingMainHostActions(
    private val events: MutableList<String>,
) : MainHostActions {
    override fun onViewMidletReports() { events += "midlet-reports" }
    override fun onCloseMidletNotice() { events += "midlet-close" }
    override fun onViewProcessReports() { events += "process-reports" }
    override fun onCloseProcessNotice() { events += "process-close" }
    override fun onChooseDirectory() { events += "choose" }
    override fun onCreateDirectory() { events += "create" }
    override fun onRetryPermission() { events += "retry" }
    override fun onExit() { events += "exit" }
}
