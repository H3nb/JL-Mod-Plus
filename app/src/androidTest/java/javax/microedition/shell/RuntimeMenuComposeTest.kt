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

package javax.microedition.shell

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

class RuntimeMenuComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nonCanvasMenu_excludesCanvasOnlyActions() {
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet Form", toolbarVisible = true),
                    menuVisible = true,
                    actions = RecordingRuntimeMenuActions(),
                    onOpenMenu = {},
                    onDismissMenu = {},
                )
            }
        }

        composeRule.onNodeWithText("Exit").assertIsDisplayed()
        composeRule.onNodeWithText("Save Log").assertIsDisplayed()
        composeRule.onNodeWithText("Lock Screen Rotation").assertIsDisplayed()
        composeRule.onAllNodesWithText("Limit FPS").assertCountEquals(0)
        composeRule.onAllNodesWithText("Virtual Keyboard").assertCountEquals(0)
    }

    @Test
    fun fullscreenCanvasMenu_exposesCanvasAndVirtualKeyboardActions() {
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(
                        title = "Canvas MIDlet",
                        isCanvas = true,
                        toolbarVisible = false,
                        imeAvailable = true,
                        virtualKeyboardAvailable = true,
                        virtualKeyboardEditing = true,
                    ),
                    menuVisible = true,
                    actions = RecordingRuntimeMenuActions(),
                    onOpenMenu = {},
                    onDismissMenu = {},
                )
            }
        }

        composeRule.onNodeWithText("Keyboard (IME)").assertIsDisplayed()
        composeRule.onNodeWithText("Take Screenshot").assertIsDisplayed()
        composeRule.onNodeWithText("Limit FPS").assertIsDisplayed()
        composeRule.onNodeWithText("Virtual Keyboard").performScrollTo().performClick()
        composeRule.onNodeWithText("Finish Edit Mode").assertIsDisplayed()
        composeRule.onNodeWithText("Hide Buttons").assertIsDisplayed()
    }

    @Test
    fun actionClick_dismissesBeforeDispatchingExistingCallback() {
        val events = mutableListOf<String>()
        val actions = RecordingRuntimeMenuActions(events)
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet"),
                    menuVisible = true,
                    actions = actions,
                    onOpenMenu = {},
                    onDismissMenu = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Save Log").performClick()

        assertEquals(listOf("dismiss", "saveLog"), events)
    }

    @Test
    fun lockRotationToggle_dismissesBeforeDispatchingExistingCallback() {
        val events = mutableListOf<String>()
        val actions = RecordingRuntimeMenuActions(events)
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet", orientationLocked = false),
                    menuVisible = true,
                    actions = actions,
                    onOpenMenu = {},
                    onDismissMenu = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Lock Screen Rotation").performClick()

        assertEquals(listOf("dismiss", "orientation"), events)
    }

    @Test
    fun backDismissesRuntimeMenuWithoutDispatchingExit() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet"),
                    menuVisible = visible.value,
                    actions = RecordingRuntimeMenuActions(),
                    onOpenMenu = {},
                    onDismissMenu = { visible.value = false },
                )
            }
        }

        composeRule.onNodeWithText("Exit").assertIsDisplayed()
        pressBack()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Exit").assertCountEquals(0)
    }

    @Test
	fun fpsDialog_keepsNumericConfirmAndResetValues() {
        var confirmed: Int? = null
        var resets = 0
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeLimitFpsDialog(
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                    onReset = { resets++ },
                )
            }
        }

        composeRule.onNodeWithTag("runtime_fps_input").performTextInput("60")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(60, confirmed)

        composeRule.onNodeWithText("Reset").performClick()
		assertEquals(1, resets)
	}

	@Test
	fun runtimeExitDialog_keepsCancelSeparateFromExplicitExit() {
		val events = mutableListOf<String>()
		composeRule.setContent {
			JLModPlusTheme {
				RuntimeHostDialogs(
					state = RuntimeHostDialogState.ExitConfirmation,
					actions = RecordingRuntimeHostDialogActions(events),
					onDismiss = { events += "dismiss" },
				)
			}
		}

		composeRule.onNodeWithText("Cancel").performClick()
		assertEquals(listOf("dismiss"), events)
	}

	@Test
	fun runtimeLayoutDialog_dispatchesOnlyTheConfirmedSelection() {
		val events = mutableListOf<String>()
		composeRule.setContent {
			JLModPlusTheme {
				RuntimeHostDialogs(
					state = RuntimeHostDialogState.LayoutSelection(
						entries = listOf("Phone", "Tablet"),
						selected = 0,
					),
					actions = RecordingRuntimeHostDialogActions(events),
					onDismiss = { events += "dismiss" },
				)
			}
		}

		composeRule.onNodeWithText("Tablet").performClick()
		composeRule.onNodeWithText("OK").performClick()
		assertEquals(listOf("dismiss", "layout:1"), events)
	}
}

private class RecordingRuntimeMenuActions(
    private val events: MutableList<String> = mutableListOf(),
) : RuntimeMenuActions {
    override fun onExit() {
        events += "exit"
    }

    override fun onSaveLog() {
        events += "saveLog"
    }

    override fun onToggleOrientationLock() {
        events += "orientation"
    }

    override fun onOpenImeKeyboard() {
        events += "ime"
    }

    override fun onTakeScreenshot() {
        events += "screenshot"
    }

    override fun onLimitFps() {
        events += "fps"
    }

    override fun onSetFpsLimit(value: Int) {
        events += "setFps:$value"
    }

    override fun onResetFpsLimit() {
        events += "resetFps"
    }

    override fun onEditVirtualKeyboardLayout() {
        events += "edit"
    }

    override fun onResizeVirtualKeyboardLayout() {
        events += "resize"
    }

    override fun onFinishVirtualKeyboardLayout() {
        events += "finish"
    }

    override fun onSwitchVirtualKeyboardLayout() {
        events += "switch"
    }

    override fun onHideVirtualKeyboardButtons() {
        events += "hide"
    }
}

private class RecordingRuntimeHostDialogActions(
	private val events: MutableList<String>,
) : RuntimeHostDialogActions {
	override fun onMidletSelected(index: Int) {
		events += "midlet:$index"
	}

	override fun onMidletCancelled() {
		events += "midlet-cancel"
	}

	override fun onErrorAcknowledged() {
		events += "error"
	}

	override fun onExitConfirmed(openSettings: Boolean) {
		events += if (openSettings) "settings" else "exit"
	}

	override fun onHideButtonsConfirmed(states: BooleanArray) {
		events += "hide"
	}

	override fun onSaveVirtualKeyboard(saveScreenParams: Boolean) {
		events += "save"
	}

	override fun onLayoutSelected(index: Int) {
		events += "layout:$index"
	}
}
