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

package javax.microedition.shell

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import kotlin.math.min

/** Compose-backed toolbar and overflow menu for the emulator Activity. */
class MicroActivityToolbarView(
    context: Context,
    private val actionCallback: ActionCallback,
) : FrameLayout(context) {
    fun interface ActionCallback {
        fun onAction(actionId: Int)
    }

    private var toolbarState by mutableStateOf(MicroToolbarState())
    private var menuExpanded by mutableStateOf(false)
    private var virtualKeyboardMenuExpanded by mutableStateOf(false)
    private var orientationLocked by mutableStateOf(false)

    private val composeView = androidx.compose.ui.platform.ComposeView(context)

    init {
        composeView.id = generateViewId()
        composeView.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
        )
        composeView.setContent {
            AppComposeTheme {
                MicroActivityToolbarContent(
                    state = toolbarState,
                    menuExpanded = menuExpanded,
                    virtualKeyboardMenuExpanded = virtualKeyboardMenuExpanded,
                    orientationLocked = orientationLocked,
                    onMenuExpandedChanged = { menuExpanded = it },
                    onVirtualKeyboardMenuExpandedChanged = {
                        virtualKeyboardMenuExpanded = it
                    },
                    onAction = ::dispatchAction,
                )
            }
        }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun setToolbarState(
        title: String,
        visible: Boolean,
        canvasActionsVisible: Boolean,
        imeAvailable: Boolean,
        virtualKeyboardAvailable: Boolean,
        timingAvailable: Boolean,
        memoryEditorAvailable: Boolean,
        speedLabel: String,
        layoutEditFinishVisible: Boolean,
    ) {
        toolbarState = MicroToolbarState(
            title = title,
            visible = visible,
            canvasActionsVisible = canvasActionsVisible,
            imeAvailable = imeAvailable,
            virtualKeyboardAvailable = virtualKeyboardAvailable,
            timingAvailable = timingAvailable,
            memoryEditorAvailable = memoryEditorAvailable,
            speedLabel = speedLabel,
            layoutEditFinishVisible = layoutEditFinishVisible,
        )
        if (!canvasActionsVisible) {
            virtualKeyboardMenuExpanded = false
        }
    }

    fun showMenu() {
        virtualKeyboardMenuExpanded = false
        menuExpanded = true
    }

    fun dismissMenu() {
        menuExpanded = false
        virtualKeyboardMenuExpanded = false
    }

    fun isOrientationLocked(): Boolean = orientationLocked

    private fun dispatchAction(actionId: Int) {
        if (actionId == R.id.action_lock_orientation) {
            orientationLocked = !orientationLocked
        }
        dismissMenu()
        actionCallback.onAction(actionId)
    }
}

private data class MicroToolbarState(
    val title: String = "",
    val visible: Boolean = false,
    val canvasActionsVisible: Boolean = false,
    val imeAvailable: Boolean = false,
    val virtualKeyboardAvailable: Boolean = false,
    val timingAvailable: Boolean = false,
    val memoryEditorAvailable: Boolean = false,
    val speedLabel: String = "",
    val layoutEditFinishVisible: Boolean = false,
)

@Composable
private fun MicroActivityToolbarContent(
    state: MicroToolbarState,
    menuExpanded: Boolean,
    virtualKeyboardMenuExpanded: Boolean,
    orientationLocked: Boolean,
    onMenuExpandedChanged: (Boolean) -> Unit,
    onVirtualKeyboardMenuExpandedChanged: (Boolean) -> Unit,
    onAction: (Int) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.visible) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.title,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSecondary,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.canvasActionsVisible) {
                        if (state.imeAvailable) {
                            IconButton(onClick = { onAction(R.id.action_ime_keyboard) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_keyboard),
                                    contentDescription = stringResource(R.string.action_keyboard_ime),
                                    tint = MaterialTheme.colorScheme.onSecondary,
                                )
                            }
                        }
                        IconButton(onClick = { onAction(R.id.action_take_screenshot) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_screenshot),
                                contentDescription = stringResource(R.string.take_screenshot),
                                tint = MaterialTheme.colorScheme.onSecondary,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { onMenuExpandedChanged(true) }) {
                            MicroMoreGlyph(MaterialTheme.colorScheme.onSecondary)
                        }
                        MicroActivityOverflowMenu(
                            state = state,
                            expanded = menuExpanded,
                            virtualKeyboardExpanded = virtualKeyboardMenuExpanded,
                            orientationLocked = orientationLocked,
                            onDismiss = {
                                onMenuExpandedChanged(false)
                                onVirtualKeyboardMenuExpandedChanged(false)
                            },
                            onVirtualKeyboardOpen = {
                                onMenuExpandedChanged(false)
                                onVirtualKeyboardMenuExpandedChanged(true)
                            },
                            onVirtualKeyboardDismiss = {
                                onVirtualKeyboardMenuExpandedChanged(false)
                            },
                            onAction = onAction,
                        )
                    }
                }
            }
        } else {
            // Keep a tiny attached anchor so the hardware/menu key can still
            // open the Compose popup while the gameplay toolbar is hidden.
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .align(Alignment.TopEnd),
            ) {
                MicroActivityOverflowMenu(
                    state = state,
                    expanded = menuExpanded,
                    virtualKeyboardExpanded = virtualKeyboardMenuExpanded,
                    orientationLocked = orientationLocked,
                    onDismiss = {
                        onMenuExpandedChanged(false)
                        onVirtualKeyboardMenuExpandedChanged(false)
                    },
                    onVirtualKeyboardOpen = {
                        onMenuExpandedChanged(false)
                        onVirtualKeyboardMenuExpandedChanged(true)
                    },
                    onVirtualKeyboardDismiss = {
                        onVirtualKeyboardMenuExpandedChanged(false)
                    },
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun MicroActivityOverflowMenu(
    state: MicroToolbarState,
    expanded: Boolean,
    virtualKeyboardExpanded: Boolean,
    orientationLocked: Boolean,
    onDismiss: () -> Unit,
    onVirtualKeyboardOpen: () -> Unit,
    onVirtualKeyboardDismiss: () -> Unit,
    onAction: (Int) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        MicroMenuItem(R.string.exit, R.id.action_exit_midlet, onAction)
        MicroMenuItem(R.string.save_log, R.id.action_save_log, onAction)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_lock_orientation)) },
            leadingIcon = {
                Checkbox(
                    checked = orientationLocked,
                    onCheckedChange = null,
                )
            },
            onClick = {
                onAction(R.id.action_lock_orientation)
            },
        )
        if (state.canvasActionsVisible) {
            if (state.imeAvailable) {
                MicroMenuItem(R.string.action_keyboard_ime, R.id.action_ime_keyboard, onAction)
            }
            MicroMenuItem(R.string.take_screenshot, R.id.action_take_screenshot, onAction)
            MicroMenuItem(R.string.PREF_LIMIT_FPS, R.id.action_limit_fps, onAction)
            if (state.timingAvailable) {
                MicroMenuItem(
                    label = if (state.speedLabel.isEmpty()) {
                        stringResource(R.string.emulation_speed)
                    } else {
                        state.speedLabel
                    },
                    actionId = R.id.action_emulation_speed,
                    onAction = onAction,
                )
            }
            if (state.memoryEditorAvailable) {
                MicroMenuItem(R.string.memory_editor, R.id.action_memory_editor, onAction)
            }
            if (state.virtualKeyboardAvailable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS)) },
                    onClick = onVirtualKeyboardOpen,
                )
            }
        }
    }
    DropdownMenu(
        expanded = virtualKeyboardExpanded,
        onDismissRequest = onVirtualKeyboardDismiss,
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        MicroMenuItem(
            R.string.layout_edit_mode,
            R.id.action_layout_edit_mode,
            onAction,
        )
        MicroMenuItem(
            R.string.layout_scale_mode,
            R.id.action_layout_scale_mode,
            onAction,
        )
        if (state.layoutEditFinishVisible) {
            MicroMenuItem(
                R.string.layout_edit_finish,
                R.id.action_layout_edit_finish,
                onAction,
            )
        }
        MicroMenuItem(R.string.layout_switch, R.id.action_layout_switch, onAction)
        MicroMenuItem(R.string.hide_buttons, R.id.action_hide_buttons, onAction)
    }
}

@Composable
private fun MicroMenuItem(
    label: String,
    actionId: Int,
    onAction: (Int) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onAction(actionId) },
    )
}

@Composable
private fun MicroMenuItem(
    labelRes: Int,
    actionId: Int,
    onAction: (Int) -> Unit,
) {
    MicroMenuItem(stringResource(labelRes), actionId, onAction)
}

@Composable
private fun MicroMoreGlyph(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val radius = min(size.width, size.height) * 0.1f
        val x = size.width / 2f
        drawCircle(color, radius, androidx.compose.ui.geometry.Offset(x, size.height * 0.25f))
        drawCircle(color, radius, androidx.compose.ui.geometry.Offset(x, size.height * 0.5f))
        drawCircle(color, radius, androidx.compose.ui.geometry.Offset(x, size.height * 0.75f))
    }
}

@Preview(name = "MicroActivity toolbar", showBackground = true, widthDp = 420, heightDp = 64)
@Composable
internal fun MicroActivityToolbarPreview() {
    AppComposeTheme {
        MicroActivityToolbarContent(
            state = MicroToolbarState(
                title = "Demo MIDlet",
                visible = true,
                canvasActionsVisible = true,
                imeAvailable = true,
                virtualKeyboardAvailable = true,
                timingAvailable = true,
                speedLabel = "Emulation speed: X1",
                layoutEditFinishVisible = true,
            ),
            menuExpanded = false,
            virtualKeyboardMenuExpanded = false,
            orientationLocked = false,
            onMenuExpandedChanged = {},
            onVirtualKeyboardMenuExpandedChanged = {},
            onAction = {},
        )
    }
}

@Preview(
    name = "MicroActivity toolbar dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 64,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun MicroActivityToolbarDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        MicroActivityToolbarContent(
            state = MicroToolbarState(title = "Demo MIDlet", visible = true),
            menuExpanded = false,
            virtualKeyboardMenuExpanded = false,
            orientationLocked = false,
            onMenuExpandedChanged = {},
            onVirtualKeyboardMenuExpandedChanged = {},
            onAction = {},
        )
    }
}
