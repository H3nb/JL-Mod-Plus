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

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.HostCommand
import io.github.h3nb.jlmodplus.ui.ControllerDialogInputScope
import io.github.h3nb.jlmodplus.ui.ControllerHostCommandHandler
import io.github.h3nb.jlmodplus.ui.DialogWindowPolicy
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.ProvideDialogWindowPolicy

@OptIn(ExperimentalTestApi::class)
private fun uiString(resId: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

class RuntimeMenuComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sharedFilePermissionShowsMessageAndAllChoices() {
        var result = -1
        composeRule.setContent {
            JLModPlusTheme { FilePermissionDialog(write = true, onChoice = { result = it }) }
        }
        composeRule.onNodeWithText(uiString(R.string.midlet_file_permission_write)).assertIsDisplayed()
        listOf(
            R.string.midlet_file_permission_allow_once,
            R.string.midlet_file_permission_allow_session,
            R.string.midlet_file_permission_allow_always,
            R.string.midlet_file_permission_deny,
        ).forEach { label ->
            composeRule.onNodeWithText(uiString(label)).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText(uiString(R.string.midlet_file_permission_deny)).performClick()
        composeRule.runOnIdle { assertEquals(MidletPermissionManager.DENY, result) }
    }

    @Test
    fun runtimeDialogPreservesInheritedHiddenSystemBars() {
        var dialogWindow: android.view.Window? = null
        composeRule.setContent {
            ProvideDialogWindowPolicy(
                policy = DialogWindowPolicy { window ->
                    dialogWindow = window
                    RuntimeSystemBarController.apply(
                        window,
                        false,
                        false,
                    )
                },
            ) {
                JLModPlusTheme {
                    RuntimeLimitFpsDialog(
                        onDismiss = {},
                        onConfirm = {},
                        onReset = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText(uiString(R.string.PREF_LIMIT_FPS)).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            val window = dialogWindow ?: return@waitUntil false
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
                ?: return@waitUntil false
            !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        }
        composeRule.runOnIdle {
            val window = checkNotNull(dialogWindow) {
                "Runtime dialog did not expose its Compose dialog Window"
            }
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(window.decorView)) {
                "Runtime dialog Window did not expose root insets"
            }
            assertFalse(
                "Runtime dialog unexpectedly showed the status bar",
                insets.isVisible(WindowInsetsCompat.Type.statusBars()),
            )
            assertFalse(
                "Runtime dialog unexpectedly showed the navigation bar",
                insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
            )
        }
    }

    @Test
    fun showControlsGridPreservesOrderAndConvertsVisibleSelectionToHiddenFlags() {
        val events = mutableListOf<String>()
        val selections = mutableListOf<BooleanArray>()
        val names = listOf("First", "Second", "Third", "Fourth", uiString(R.string.runtime_virtual_controls_analog))
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.ShowControls(
                        names = names,
                        hidden = booleanArrayOf(false, true, false, true, false),
                    ),
                    actions = RecordingRuntimeHostDialogActions(events, selections),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText(uiString(R.string.runtime_virtual_controls_show_controls)).assertIsDisplayed()
        composeRule.onNodeWithText("First").assertIsOn()
        composeRule.onNodeWithText("Second").assertIsOff()
        val first = composeRule.onNodeWithText("First").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithText("Second").getUnclippedBoundsInRoot()
        val third = composeRule.onNodeWithText("Third").getUnclippedBoundsInRoot()
        val fourth = composeRule.onNodeWithText("Fourth").getUnclippedBoundsInRoot()
        assertTrue(first.left < second.left && second.left < third.left)
        assertTrue(fourth.top > first.top)

        composeRule.onNodeWithText("Second").performClick()
        composeRule.onNodeWithText("Second").assertIsOn()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(listOf("dismiss", "showControls"), events)
        assertEquals(listOf(false, false, false, true, false), selections.single().toList())
    }

    @Test
    fun showControlsCanonicalMappingPreservesUnderlyingVisibilityIndices() {
        val selections = mutableListOf<BooleanArray>()
        val names = canonicalShowControlNames()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.ShowControls(names, BooleanArray(names.size)),
                    actions = RecordingRuntimeHostDialogActions(mutableListOf(), selections),
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("M").performClick()
        composeRule.onNodeWithText("D-pad").performClick()
        composeRule.onNodeWithText(uiString(R.string.runtime_virtual_controls_analog)).performClick()
        composeRule.onNodeWithText("OK").performClick()

        val hidden = selections.single()
        assertTrue(hidden[27])
        assertTrue(hidden[28])
        assertTrue(hidden[29])
        assertEquals(3, hidden.count { it })
    }

    @Test
    fun showControlsWideWindowUsesTwoPaneSemanticPlacement() {
        val names = canonicalShowControlNames()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(900.dp, 600.dp)),
            ) {
                JLModPlusTheme {
                    RuntimeHostDialogs(
                        state = RuntimeHostDialogState.ShowControls(names, BooleanArray(names.size)),
                        actions = RecordingRuntimeHostDialogActions(mutableListOf(), mutableListOf()),
                        onDismiss = {},
                    )
                }
            }
        }

        val l = composeRule.onNodeWithText("L").getUnclippedBoundsInRoot()
        val one = composeRule.onNodeWithText("1").getUnclippedBoundsInRoot()
        val upLeft = composeRule.onNodeWithText("↖").getUnclippedBoundsInRoot()
        val four = composeRule.onNodeWithText("4").getUnclippedBoundsInRoot()
        val seven = composeRule.onNodeWithText("7").getUnclippedBoundsInRoot()
        val star = composeRule.onNodeWithText("*").getUnclippedBoundsInRoot()

        assertTrue(one.left > l.left)
        assertTrue(upLeft.top > l.top)
        assertTrue(one.top < four.top && four.top < seven.top && seven.top < star.top)
        composeRule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun showControlsCompactLandscapeKeepsSemanticControlsAndActionsReachable() {
        val names = canonicalShowControlNames()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp)),
            ) {
                JLModPlusTheme {
                    RuntimeHostDialogs(
                        state = RuntimeHostDialogState.ShowControls(names, BooleanArray(names.size)),
                        actions = RecordingRuntimeHostDialogActions(mutableListOf(), mutableListOf()),
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText(uiString(R.string.runtime_virtual_controls_analog)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("#").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun compactHeightBackMenuKeepsLastActionReachable() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp)),
            ) {
                JLModPlusTheme {
                    RuntimeMenuHost(
                        state = RuntimeMenuUiState(
                            title = "Demo MIDlet",
                            isCanvas = true,
                            imeAvailable = true,
                            virtualKeyboardAvailable = true,
                        ),
                        menuVisible = true,
                        actions = RecordingRuntimeMenuActions(),
                        onDismissMenu = {},
                    )
                }
            }
        }

        val virtualControlsLabel = uiString(R.string.runtime_virtual_controls_title)
        composeRule.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText(virtualControlsLabel))
        composeRule.onNodeWithText(virtualControlsLabel).assertIsDisplayed()
    }

    @Test
    fun runtimeToolbarDoesNotAddAnOverflowButton() {
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(
                        title = "Canvas MIDlet",
                        isCanvas = true,
                        toolbarVisible = true,
                        imeAvailable = true,
                    ),
                    menuVisible = false,
                    actions = RecordingRuntimeMenuActions(),
                    onDismissMenu = {},
                )
            }
        }
        composeRule.onAllNodesWithContentDescription(uiString(R.string.more)).assertCountEquals(0)
    }

    @Test
    fun canvasToolbarActionsUseCompactTargets() {
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(
                        title = "Canvas MIDlet",
                        isCanvas = true,
                        toolbarVisible = true,
                        imeAvailable = true,
                    ),
                    menuVisible = false,
                    actions = RecordingRuntimeMenuActions(),
                    onDismissMenu = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(uiString(R.string.take_screenshot))
            .assertWidthIsAtLeast(40.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun cameraToolbarShortcutTracksLifecycleDestinationAndBusyStateOnCanvasAndForm() {
        val events = mutableListOf<String>()
        val state = androidx.compose.runtime.mutableStateOf(RuntimeMenuUiState(
            title = "Camera MIDlet",
            isCanvas = true,
            imeAvailable = true,
            emulationSpeedAvailable = true,
        ))
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 48.dp)),
            ) {
                JLModPlusTheme {
                    RuntimeMenuHost(
                        state = state.value,
                        menuVisible = false,
                        actions = RecordingRuntimeMenuActions(events),
                        onDismissMenu = { events += "dismiss" },
                    )
                }
            }
        }
        val front = uiString(R.string.runtime_camera_use_front)
        val rear = uiString(R.string.runtime_camera_use_rear)
        composeRule.onAllNodesWithContentDescription(front).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(rear).assertCountEquals(0)
        composeRule.runOnIdle { state.value = state.value.copy(cameraSwitchAvailable = true) }
        composeRule.onNodeWithContentDescription(front)
            .assertIsDisplayed().assertIsEnabled()
            .assertWidthIsAtLeast(40.dp).assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf("camera"), events)
        composeRule.onNodeWithContentDescription(uiString(R.string.action_keyboard_ime)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.take_screenshot)).assertIsDisplayed()
        composeRule.onNodeWithText(javax.microedition.shell.timing.EmulationSpeed.formatMultiplier(100))
            .assertIsDisplayed()

        composeRule.runOnIdle { state.value = state.value.copy(cameraSwitching = true) }
        // A busy camera remains actionable so the host can explain the restriction.
        // CameraRuntime still rejects the actual switch while recording.
        composeRule.onNodeWithContentDescription(front).assertIsDisplayed().assertIsEnabled()
            .performClick()
        assertEquals(listOf("camera", "camera"), events)
        // The same explanatory action remains reachable on Form-based MIDlets.
        composeRule.runOnIdle { state.value = state.value.copy(isCanvas = false, cameraSwitching = true) }
        composeRule.onNodeWithContentDescription(front).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("camera", "camera", "camera"), events)
        composeRule.runOnIdle {
            state.value = state.value.copy(cameraFrontFacing = true, cameraSwitching = false)
        }
        composeRule.onAllNodesWithContentDescription(front).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(rear).assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onAllNodesWithContentDescription(uiString(R.string.take_screenshot)).assertCountEquals(0)
        assertEquals(listOf("camera", "camera", "camera", "camera"), events)
        composeRule.runOnIdle { state.value = state.value.copy(cameraSwitchAvailable = false) }
        composeRule.onAllNodesWithContentDescription(rear).assertCountEquals(0)
    }

    @Test
    fun cameraMenuActionExistsOnlyWhenShortcutCannotBeShown() {
        val events = mutableListOf<String>()
        val state = androidx.compose.runtime.mutableStateOf(RuntimeMenuUiState(
            title = "Camera MIDlet",
            isCanvas = true,
            imeAvailable = true,
            emulationSpeedAvailable = true,
            cameraSwitchAvailable = true,
            toolbarWidthDp = 360f,
        ))
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = state.value,
                    menuVisible = true,
                    actions = RecordingRuntimeMenuActions(events),
                    onDismissMenu = { events += "dismiss" },
                )
            }
        }
        val label = uiString(R.string.runtime_camera_use_front)
        composeRule.onAllNodesWithText(label).assertCountEquals(0)
        composeRule.runOnIdle { state.value = state.value.copy(toolbarWidthDp = 152f) }
        composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("dismiss", "camera"), events)
        composeRule.runOnIdle { state.value = state.value.copy(toolbarVisible = false, cameraSwitching = true) }
        composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performClick()
        assertEquals(listOf("dismiss", "camera", "dismiss", "camera"), events)
    }

    @Test
    fun compactToolbarKeepsWholeTouchTargetsOutsideCentralCutout() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 48.dp)),
            ) {
                val cutout = with(androidx.compose.ui.platform.LocalDensity.current) {
                    RuntimeToolbarOcclusion(154.dp.roundToPx(), 206.dp.roundToPx())
                }
                JLModPlusTheme {
                    RuntimeMenuHost(
                        state = RuntimeMenuUiState(
                            title = "Demo MIDlet",
                            isCanvas = true,
                            imeAvailable = true,
                            emulationSpeedAvailable = true,
                            toolbarCutouts = listOf(cutout),
                        ),
                        menuVisible = false,
                        actions = RecordingRuntimeMenuActions(),
                        onDismissMenu = {},
                    )
                }
            }
        }
        val title = composeRule.onNodeWithText("Demo MIDlet").getUnclippedBoundsInRoot()
        assertTrue(title.left >= 8.dp && title.right <= 151.dp)
        val speed = composeRule.onNodeWithText(
            javax.microedition.shell.timing.EmulationSpeed.formatMultiplier(100),
        ).getUnclippedBoundsInRoot()
        assertTrue(speed.left >= 209.dp && speed.right <= 344.dp)
        composeRule.onNodeWithContentDescription(uiString(R.string.action_keyboard_ime))
            .assertWidthIsAtLeast(40.dp)
            .assertHeightIsAtLeast(48.dp)
        val ime = composeRule.onNodeWithContentDescription(uiString(R.string.action_keyboard_ime))
            .getUnclippedBoundsInRoot()
        assertTrue(ime.left >= 209.dp && ime.right <= 344.dp)
        assertTrue(ime.top >= 0.dp && ime.bottom <= 48.dp)
        val screenshot = composeRule.onNodeWithContentDescription(uiString(R.string.take_screenshot))
            .assertWidthIsAtLeast(40.dp)
            .assertHeightIsAtLeast(48.dp)
            .getUnclippedBoundsInRoot()
        assertTrue(screenshot.left >= 209.dp && screenshot.right <= 357.dp)
        composeRule.onAllNodesWithContentDescription(uiString(R.string.more)).assertCountEquals(0)
    }

    @Test
    fun landscapeToolbarTargetsFitInsideFortyDpHost() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(640.dp, 120.dp)),
            ) {
                JLModPlusTheme {
                    Column {
                        RuntimeMenuHost(
                            state = RuntimeMenuUiState(
                                title = "Landscape MIDlet",
                                isCanvas = true,
                                imeAvailable = true,
                                emulationSpeedAvailable = true,
                            ),
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            menuVisible = false,
                            actions = RecordingRuntimeMenuActions(events),
                            onDismissMenu = {},
                        )
                        Box(Modifier.fillMaxWidth().height(80.dp)
                            .testTag("below-toolbar").clickable { events += "game" })
                    }
                }
            }
        }
        val screenshot = composeRule.onNodeWithContentDescription(uiString(R.string.take_screenshot))
            .assertWidthIsAtLeast(40.dp)
        val bounds = screenshot.getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= 0.dp && bounds.bottom <= 40.dp)
        assertEquals(40.dp, bounds.bottom - bounds.top)
        screenshot.performClick()
        // The old 48dp target would extend into this first row below the compact toolbar.
        composeRule.onNodeWithTag("below-toolbar").performTouchInput {
            click(Offset(width - 28.dp.toPx(), 1.dp.toPx()))
        }
        assertEquals(listOf("screenshot", "game"), events)
        composeRule.onAllNodesWithContentDescription(uiString(R.string.more)).assertCountEquals(0)
    }

    @Test
    fun canvasMenuRestoresShortcutsWhenFullscreenHidesToolbar() {
        val fullscreen = androidx.compose.runtime.mutableStateOf(false)
        val menuVisible = androidx.compose.runtime.mutableStateOf(false)
        val events = mutableListOf<String>()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    RuntimeMenuHost(
                        state = RuntimeMenuUiState(
                            title = "MIDlet",
                            isCanvas = true,
                            toolbarVisible = !fullscreen.value,
                            imeAvailable = true,
                            emulationSpeedAvailable = true,
                        ),
                        menuVisible = menuVisible.value,
                        actions = RecordingRuntimeMenuActions(events),
                        onDismissMenu = { events += "dismiss" },
                    )
                }
            }
        }
        composeRule.runOnIdle { menuVisible.value = true }
        val shortcutLabels = listOf(
            uiString(R.string.action_keyboard_ime),
            uiString(R.string.take_screenshot),
            uiString(R.string.PREF_EMULATION_SPEED),
        )
        shortcutLabels.forEach { label ->
            composeRule.onAllNodes(hasText(label) and hasAnyAncestor(isDialog()))
                .assertCountEquals(0)
        }
        composeRule.runOnIdle { fullscreen.value = true }
        shortcutLabels.forEach { label ->
            composeRule.onAllNodes(hasText(label) and hasAnyAncestor(isDialog()))
                .assertCountEquals(1)
        }
        composeRule.onNodeWithText(uiString(R.string.PREF_EMULATION_SPEED))
            .performScrollTo().performClick()
        assertEquals(listOf("dismiss", "speed"), events)
    }

    @Test
    fun nonCanvasMenu_excludesCanvasOnlyActions() {
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet Form", toolbarVisible = true),
                    menuVisible = true,
                    actions = RecordingRuntimeMenuActions(),
                    onDismissMenu = {},
                )
            }
        }

        composeRule.onNodeWithText("Exit").assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.save_log)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.action_lock_orientation)).assertIsDisplayed()
        composeRule.onAllNodesWithText("Limit FPS").assertCountEquals(0)
        composeRule.onAllNodesWithText(uiString(R.string.runtime_virtual_controls_title)).assertCountEquals(0)
    }

    @Test
    fun touchOpenedRuntimeMenuDoesNotShowControllerFocusIndicator() {
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet"),
                    menuVisible = true,
                    actions = RecordingRuntimeMenuActions(),
                    onDismissMenu = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("runtime-controller-focus-indicator")
            .assertCountEquals(0)
    }

    @Test
    fun runtimeMenu_keepsGamepadHelpAndDiagnosisOutOfMidletOptions() {
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(
                        title = "Canvas MIDlet",
                        isCanvas = true,
                        imeAvailable = true,
                        virtualKeyboardAvailable = true,
                    ),
                    menuVisible = true,
                    actions = RecordingRuntimeMenuActions(),
                    onDismissMenu = {},
                )
            }
        }

        composeRule.onAllNodesWithText(uiString(R.string.config_gamepad_mapping_help_title)).assertCountEquals(0)
        composeRule.onAllNodesWithText(uiString(R.string.config_gamepad_test)).assertCountEquals(0)
    }

    @Test
    fun fullscreenCanvasMenu_exposesOnlyThreeVirtualControlCustomizations() {
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
                    onDismissMenu = {},
                )
            }
        }

        composeRule.onNodeWithText("Keyboard (IME)").assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.take_screenshot)).assertIsDisplayed()
        composeRule.onNodeWithText("Limit FPS").assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.runtime_virtual_controls_title)).performScrollTo().performClick()
        composeRule.onNodeWithText(uiString(R.string.layout_edit_finish)).assertIsDisplayed()
        composeRule.onNodeWithText("Layout Templates").assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.runtime_virtual_controls_show_controls)).assertIsDisplayed()
        composeRule.onAllNodesWithText("D-pad").assertCountEquals(0)
        composeRule.onAllNodesWithText(uiString(R.string.runtime_virtual_controls_analog)).assertCountEquals(0)
        composeRule.onAllNodesWithText("Key Layout Resize Mode").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Back")
            .assertWidthIsAtLeast(40.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun showControlsActionDismissesBeforeDispatchingCallback() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(
                        title = "Canvas MIDlet",
                        isCanvas = true,
                        virtualKeyboardAvailable = true,
                    ),
                    menuVisible = true,
                    virtualKeyboardPage = true,
                    actions = RecordingRuntimeMenuActions(events),
                    onDismissMenu = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText(uiString(R.string.runtime_virtual_controls_show_controls)).performClick()
        assertEquals(listOf("dismiss", "showControls"), events)
    }

    @Test
    fun fullscreenToggleDismissesBeforeChangingGlobalPreference() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet", isCanvas = true),
                    menuVisible = true,
                    actions = RecordingRuntimeMenuActions(events),
                    onDismissMenu = { events += "dismiss" },
                )
            }
        }
        composeRule.onNodeWithText(uiString(R.string.pref_runtime_fullscreen_title))
            .performScrollTo().performClick()
        assertEquals(listOf("dismiss", "fullscreen"), events)
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
                    onDismissMenu = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText(uiString(R.string.save_log)).performClick()

        assertEquals(listOf("dismiss", "saveLog"), events)
    }

    @Test
    fun canvasMenu_opensMemoryEditorAfterDismissal() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet", isCanvas = true),
                    menuVisible = true,
                    actions = RecordingRuntimeMenuActions(events),
                    onDismissMenu = { events += "dismiss" },
                )
            }
        }

        val memoryEditorLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.memory_editor_bubble)
        composeRule.onNodeWithText(memoryEditorLabel).performScrollTo().performClick()

        assertEquals(listOf("dismiss", "memoryEditor"), events)
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
                    onDismissMenu = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText(uiString(R.string.action_lock_orientation)).performClick()

        assertEquals(listOf("dismiss", "orientation"), events)
    }

    @Test
    fun dismissRequestClosesRuntimeMenuWithoutDispatchingExit() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val events = mutableListOf<String>()
        val dismissMenu = {
            visible.value = false
            events += "dismiss"
        }
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = RuntimeMenuUiState(title = "MIDlet"),
                    menuVisible = visible.value,
                    actions = RecordingRuntimeMenuActions(),
                    onDismissMenu = dismissMenu,
                )
            }
        }

        composeRule.onNodeWithText("Exit").assertIsDisplayed()
        composeRule.runOnIdle { dismissMenu() }
        composeRule.waitUntil(timeoutMillis = 5_000) { !visible.value }
        assertEquals(listOf("dismiss"), events)
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

        composeRule.onNodeWithText("Maximum").assertIsDisplayed()
        composeRule.onNodeWithTag("runtime_fps_input").performTextInput("60")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(60, confirmed)

        composeRule.onNodeWithText("Reset").performClick()
        assertEquals(1, resets)
    }

    @Test
    fun emulationSpeedDialog_appliesManualSpeedInShortWindow() {
        var confirmed = 0
        val visible = androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp)),
            ) {
                JLModPlusTheme {
                    if (visible.value) {
                        RuntimeEmulationSpeedDialog(
                            currentPercent = 125,
                            onDismiss = { visible.value = false },
                            onConfirm = {
                                visible.value = false
                                confirmed = it
                            },
                            onReset = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Auto").assertDoesNotExist()
        composeRule.onNodeWithText("1.25x").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("runtime_emulation_speed_slider")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(12f)
            }
        composeRule.onNodeWithText("OK").assertIsDisplayed().performClick()

        assertEquals(1600, confirmed)
        composeRule.onNodeWithTag("runtime_emulation_speed_slider").assertDoesNotExist()
    }

    @Test
    fun emulationSpeedDialog_resetsInShortWindow() {
        var resets = 0
        val visible = androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(480.dp, 240.dp)),
            ) {
                JLModPlusTheme {
                    if (visible.value) {
                        RuntimeEmulationSpeedDialog(
                            currentPercent = 1600,
                            onDismiss = { visible.value = false },
                            onConfirm = {},
                            onReset = {
                                visible.value = false
                                resets++
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Reset").assertIsDisplayed().performClick()
        assertEquals(1, resets)
        composeRule.onNodeWithTag("runtime_emulation_speed_slider").assertDoesNotExist()
    }

    @Test
    fun controllerNavigatesMidletDialogAndActivatesFocusedEntry() {
        val events = mutableListOf<String>()
        var handler: ControllerHostCommandHandler? = null
        composeRule.setContent {
            JLModPlusTheme {
                ControllerDialogInputScope(
                    onControllerKeyEvent = { false },
                    onControllerHostCommandHandlerChanged = { handler = it },
                ) {
                    RuntimeHostDialogs(
                        state = RuntimeHostDialogState.MidletSelection(listOf("First", "Second")),
                        actions = RecordingRuntimeHostDialogActions(events),
                        onDismiss = { events += "dismiss" },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val current = checkNotNull(handler)
            current(HostCommand.NavigateDown, true)
            current(HostCommand.Activate, true)
            current(HostCommand.Activate, false)
        }

        assertEquals(listOf("dismiss", "midlet:1"), events)
    }

    @Test
    fun controllerBackUsesMidletDialogCancelSemantics() {
        val events = mutableListOf<String>()
        var handler: ControllerHostCommandHandler? = null
        composeRule.setContent {
            JLModPlusTheme {
                ControllerDialogInputScope(
                    onControllerKeyEvent = { false },
                    onControllerHostCommandHandlerChanged = { handler = it },
                ) {
                    RuntimeHostDialogs(
                        state = RuntimeHostDialogState.MidletSelection(listOf("First", "Second")),
                        actions = RecordingRuntimeHostDialogActions(events),
                        onDismiss = { events += "dismiss" },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            checkNotNull(handler)(HostCommand.Back, true)
        }

        assertEquals(listOf("dismiss", "midlet-cancel"), events)
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
    fun layoutEditGuide_confirmsDontShowAgainBeforeEditing() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.LayoutEditGuide,
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText(uiString(R.string.runtime_virtual_controls_edit_guide_title)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.runtime_virtual_controls_edit_guide_dont_show_again)).performClick()
        composeRule.onNodeWithText("OK").performClick()

        assertEquals(listOf("dismiss", "layout-guide:true"), events)
    }

    @Test
    fun finishVirtualKeyboardEditDialog_exposesAllActionsAtNarrowWidth() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(320.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    RuntimeHostDialogs(
                        state = RuntimeHostDialogState.FinishVirtualKeyboardEdit(
                            updateTarget = "Very Long K800i Preset Name That Wraps",
                        ),
                        actions = RecordingRuntimeHostDialogActions(events),
                        onDismiss = { events += "dismiss" },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Discard").assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.layout_edit_continue)).assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithText("Only this MIDlet will change.").assertIsDisplayed()
    }

    @Test
    fun finishVirtualKeyboardEditDialog_defaultsLocalAndPassesSelectedUpdateTarget() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.FinishVirtualKeyboardEdit(
                        updateTarget = "K800i",
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Only this MIDlet will change.").assertIsDisplayed()
        composeRule.onAllNodesWithText(
            "Replaces K800i with this MIDlet's current settings and controls.\nOther followers update when next opened.",
        ).assertCountEquals(0)

        composeRule.onNodeWithText("Update K800i").performClick()
        composeRule.onAllNodesWithText("Only this MIDlet will change.").assertCountEquals(0)
        composeRule.onNodeWithText(
            "Replaces K800i with this MIDlet's current settings and controls.\nOther followers update when next opened.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(listOf("dismiss", "edit-save:K800i"), events)
    }

    @Test
    fun finishVirtualKeyboardEditDialog_controllerBackContinuesEditing() {
        val events = mutableListOf<String>()
        var handler: ControllerHostCommandHandler? = null
        composeRule.setContent {
            JLModPlusTheme {
                ControllerDialogInputScope(
                    onControllerKeyEvent = { false },
                    onControllerHostCommandHandlerChanged = { handler = it },
                ) {
                    RuntimeHostDialogs(
                        state = RuntimeHostDialogState.FinishVirtualKeyboardEdit(
                        ),
                        actions = RecordingRuntimeHostDialogActions(events),
                        onDismiss = { events += "dismiss" },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            checkNotNull(handler)(HostCommand.Back, true)
        }

        assertEquals(listOf("dismiss", "edit-continue"), events)
    }

    @Test
    fun finishVirtualKeyboardEditDialog_hasNoScreenParameterOption() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.FinishVirtualKeyboardEdit(),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onAllNodesWithText("Save Screen Parameters").assertCountEquals(0)
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(listOf("dismiss", "edit-save"), events)
    }

    @Test
    fun saveVirtualKeyboardDialog_withoutTargetKeepsExistingSurface() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.SaveVirtualKeyboard(
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onAllNodesWithText("Save to").assertCountEquals(0)
        composeRule.onAllNodesWithText("This MIDlet only").assertCountEquals(0)
        composeRule.onAllNodesWithText("Only this MIDlet will change.").assertCountEquals(0)
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(listOf("dismiss", "save"), events)
    }

    @Test
    fun saveVirtualKeyboardDialog_targetDefaultsToLocalExplanation() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.SaveVirtualKeyboard(
                        updateTarget = "K800i",
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Save to").assertIsDisplayed()
        composeRule.onNodeWithText("This MIDlet only").assertIsDisplayed()
        composeRule.onNodeWithText("Update K800i")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Only this MIDlet will change.").assertIsDisplayed()
        composeRule.onAllNodesWithText(
            "Replaces K800i with this MIDlet's current settings and controls.\nOther followers update when next opened.",
        ).assertCountEquals(0)
    }

    @Test
    fun saveVirtualKeyboardDialog_updateExplanationIsMutuallyExclusiveAndPassesTarget() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.SaveVirtualKeyboard(
                        updateTarget = "K800i",
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Update K800i").performClick()
        composeRule.onAllNodesWithText("Only this MIDlet will change.").assertCountEquals(0)
        composeRule.onNodeWithText(
            "Replaces K800i with this MIDlet's current settings and controls.\nOther followers update when next opened.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()

        assertEquals(listOf("dismiss", "save:K800i"), events)
    }

    @Test
    fun runtimeLayoutDialog_initialPickerIsSelectionOnlyWithTarget() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.LayoutSelection(
                        entries = listOf("Phone", "Tablet"),
                        selected = 0,
                        updateTarget = "K800i",
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Phone").assertIsDisplayed()
        composeRule.onNodeWithText("Tablet").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onAllNodesWithText("OK").assertCountEquals(0)
        composeRule.onAllNodesWithText("Apply").assertCountEquals(0)
        composeRule.onAllNodesWithText("This MIDlet only").assertCountEquals(0)
        composeRule.onAllNodesWithText("Update K800i").assertCountEquals(0)
        composeRule.onAllNodesWithText("Only this MIDlet will change.").assertCountEquals(0)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun runtimeLayoutDialog_differentTemplateImmediatelyOpensConfirmationWithoutCallback() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.LayoutSelection(
                        entries = listOf("Phone", "Tablet"),
                        selected = 0,
                        updateTarget = "K800i",
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Tablet").performClick()

        assertEquals(emptyList<String>(), events)
        composeRule.onNodeWithText("Apply Tablet?").assertIsDisplayed()
        composeRule.onNodeWithText("This MIDlet only").assertIsDisplayed()
        composeRule.onNodeWithText("Only this MIDlet will change.").assertIsDisplayed()
        composeRule.onAllNodesWithText(
            "Replaces K800i with this MIDlet's current settings and controls.\nOther followers update when next opened.",
        ).assertCountEquals(0)
    }

    @Test
    fun runtimeLayoutDialog_localApplyDispatchesConfirmedSelection() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.LayoutSelection(
                        entries = listOf("Phone", "Tablet"),
                        selected = 0,
                        updateTarget = "K800i",
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Tablet").performClick()
        composeRule.onNodeWithText("Apply").performClick()

        assertEquals(listOf("dismiss", "layout:1"), events)
    }

    @Test
    fun runtimeLayoutDialog_updateApplyDispatchesTarget() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.LayoutSelection(
                        entries = listOf("Phone", "Tablet"),
                        selected = 0,
                        updateTarget = "K800i",
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Tablet").performClick()
        composeRule.onNodeWithText("Update K800i").performClick()
        composeRule.onAllNodesWithText("Only this MIDlet will change.").assertCountEquals(0)
        composeRule.onNodeWithText(
            "Replaces K800i with this MIDlet's current settings and controls.\nOther followers update when next opened.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Apply").performClick()

        assertEquals(listOf("dismiss", "layout:1:K800i"), events)
    }

    @Test
    fun runtimeLayoutDialog_confirmationCancelDoesNotDispatchCallback() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = RuntimeHostDialogState.LayoutSelection(
                        entries = listOf("Phone", "Tablet"),
                        selected = 0,
                        updateTarget = "K800i",
                    ),
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = { events += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Tablet").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun runtimeLayoutDialog_confirmationControllerBackDoesNotDispatchCallback() {
        val events = mutableListOf<String>()
        var handler: ControllerHostCommandHandler? = null
        composeRule.setContent {
            JLModPlusTheme {
                ControllerDialogInputScope(
                    onControllerKeyEvent = { false },
                    onControllerHostCommandHandlerChanged = { handler = it },
                ) {
                    RuntimeHostDialogs(
                        state = RuntimeHostDialogState.LayoutSelection(
                            entries = listOf("Phone", "Tablet"),
                            selected = 0,
                            updateTarget = "K800i",
                        ),
                        actions = RecordingRuntimeHostDialogActions(events),
                        onDismiss = { events += "dismiss" },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Tablet").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            checkNotNull(handler)(HostCommand.Back, true)
        }

        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun runtimeLayoutDialog_currentTemplateIsTrueNoOp() {
        val events = mutableListOf<String>()
        val visible = androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            JLModPlusTheme {
                RuntimeHostDialogs(
                    state = if (visible.value) {
                        RuntimeHostDialogState.LayoutSelection(
                            entries = listOf("Phone", "Tablet"),
                            selected = 0,
                            updateTarget = "K800i",
                        )
                    } else {
                        null
                    },
                    actions = RecordingRuntimeHostDialogActions(events),
                    onDismiss = {
                        events += "dismiss"
                        visible.value = false
                    },
                )
            }
        }

        composeRule.onNodeWithText("Phone").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("dismiss"), events)
        composeRule.onAllNodesWithText("Apply Phone?").assertCountEquals(0)
        composeRule.onAllNodesWithText(uiString(R.string.layout_switch)).assertCountEquals(0)
    }

    @Test
    fun runtimeLayoutDialog_withoutTargetStillUsesSelectionOnlyPickerAndLocalConfirmation() {
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

        composeRule.onAllNodesWithText("OK").assertCountEquals(0)
        composeRule.onNodeWithText("Tablet").performClick()
        assertEquals(emptyList<String>(), events)
        composeRule.onNodeWithText("Apply Tablet?").assertIsDisplayed()
        composeRule.onNodeWithText("Only this MIDlet will change.").assertIsDisplayed()
        composeRule.onAllNodesWithText("This MIDlet only").assertCountEquals(0)
        composeRule.onNodeWithText("Apply").performClick()

        assertEquals(listOf("dismiss", "layout:1"), events)
    }

    @Test
    fun runtimeLayoutDialog_controllerActivateOnDifferentTemplateOpensConfirmation() {
        val events = mutableListOf<String>()
        var handler: ControllerHostCommandHandler? = null
        composeRule.setContent {
            JLModPlusTheme {
                ControllerDialogInputScope(
                    onControllerKeyEvent = { false },
                    onControllerHostCommandHandlerChanged = { handler = it },
                ) {
                    RuntimeHostDialogs(
                        state = RuntimeHostDialogState.LayoutSelection(
                            entries = listOf("Phone", "Tablet"),
                            selected = 0,
                            updateTarget = "K800i",
                        ),
                        actions = RecordingRuntimeHostDialogActions(events),
                        onDismiss = { events += "dismiss" },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val current = checkNotNull(handler)
            current(HostCommand.NavigateDown, true)
            current(HostCommand.Activate, true)
            current(HostCommand.Activate, false)
        }

        assertEquals(emptyList<String>(), events)
        composeRule.onNodeWithText("Apply Tablet?").assertIsDisplayed()
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

    override fun onToggleFullscreen() {
        events += "fullscreen"
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

    override fun onEmulationSpeed() {
        events += "speed"
    }

    override fun onSetEmulationSpeed(value: Int) {
        events += "setSpeed:$value"
    }

    override fun onResetEmulationSpeed() {
        events += "resetSpeed"
    }

    override fun onSwitchCamera() {
        events += "camera"
    }

    override fun onMemoryEditor() {
        events += "memoryEditor"
    }

    override fun onEditVirtualKeyboardLayout() {
        events += "edit"
    }

    override fun onFinishVirtualKeyboardLayout() {
        events += "finish"
    }

    override fun onSwitchVirtualKeyboardLayout() {
        events += "switch"
    }

    override fun onShowControls() {
        events += "showControls"
    }
}

private fun canonicalShowControlNames() = listOf(
    "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "*", "#",
    "L", "R", "D", "C", "↖", "↑", "↗", "←", "→", "↙", "↓", "↘",
    "F", "A", "B", "M", "D-pad", uiString(R.string.runtime_virtual_controls_analog),
)

private class RecordingRuntimeHostDialogActions(
    private val events: MutableList<String>,
    private val hiddenSelections: MutableList<BooleanArray> = mutableListOf(),
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

    override fun onShowControlsConfirmed(hidden: BooleanArray) {
        events += "showControls"
        hiddenSelections += hidden.copyOf()
    }

    override fun onSaveVirtualKeyboard(updateTarget: String?) {
        events += updateTarget?.let { "save:$it" } ?: "save"
    }

    override fun onVirtualKeyboardEditSaved(updateTarget: String?) {
        events += updateTarget?.let { "edit-save:$it" } ?: "edit-save"
    }

    override fun onVirtualKeyboardEditDiscarded() {
        events += "edit-discard"
    }

    override fun onVirtualKeyboardEditContinued() {
        events += "edit-continue"
    }

    override fun onLayoutSelected(index: Int, updateTarget: String?) {
        events += updateTarget?.let { "layout:$index:$it" } ?: "layout:$index"
    }

    override fun onLayoutEditGuideConfirmed(dontShowAgain: Boolean) {
        events += "layout-guide:$dontShowAgain"
    }
}
