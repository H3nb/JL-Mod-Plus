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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

/** Android-host menu state only; Java ME Displayable and Command state stay in the runtime. */
internal data class RuntimeMenuUiState(
    val title: String = "",
    val isCanvas: Boolean = false,
    val toolbarVisible: Boolean = true,
    val imeAvailable: Boolean = false,
    val virtualKeyboardAvailable: Boolean = false,
    val virtualKeyboardEditing: Boolean = false,
    val orientationLocked: Boolean = false,
)

interface RuntimeMenuActions {
    fun onExit()
    fun onSaveLog()
    fun onToggleOrientationLock()
    fun onOpenImeKeyboard()
    fun onTakeScreenshot()
    fun onLimitFps()
    fun onSetFpsLimit(value: Int)
    fun onResetFpsLimit()
    fun onEditVirtualKeyboardLayout()
    fun onResizeVirtualKeyboardLayout()
    fun onFinishVirtualKeyboardLayout()
    fun onSwitchVirtualKeyboardLayout()
    fun onHideVirtualKeyboardButtons()
}

/**
 * Interop owner for the app-owned runtime chrome. Rendering, input dispatch, and MIDP
 * Displayable transitions deliberately remain in [MicroActivity].
 */
class RuntimeMenuComposeController(
    composeView: ComposeView,
    private val actions: RuntimeMenuActions,
) {
    private var state by mutableStateOf(RuntimeMenuUiState())
    private var menuVisible by mutableStateOf(false)
    private var limitFpsVisible by mutableStateOf(false)
    private val menuActions = object : RuntimeMenuActions by actions {
        override fun onLimitFps() {
            closeMenu()
            limitFpsVisible = true
        }
    }

    init {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                RuntimeMenuHost(
                    state = state,
                    menuVisible = menuVisible,
                    actions = menuActions,
                    onOpenMenu = ::openMenu,
                    onDismissMenu = ::closeMenu,
                )
                if (limitFpsVisible) {
                    RuntimeLimitFpsDialog(
                        onDismiss = { limitFpsVisible = false },
                        onConfirm = { value ->
                            limitFpsVisible = false
                            actions.onSetFpsLimit(value)
                        },
                        onReset = {
                            limitFpsVisible = false
                            actions.onResetFpsLimit()
                        },
                    )
                }
            }
        }
    }

    fun update(
        title: String,
        isCanvas: Boolean,
        toolbarVisible: Boolean,
        imeAvailable: Boolean,
        virtualKeyboardAvailable: Boolean,
        virtualKeyboardEditing: Boolean,
        orientationLocked: Boolean,
    ) {
        state = RuntimeMenuUiState(
            title = title,
            isCanvas = isCanvas,
            toolbarVisible = toolbarVisible,
            imeAvailable = imeAvailable,
            virtualKeyboardAvailable = virtualKeyboardAvailable,
            virtualKeyboardEditing = virtualKeyboardEditing,
            orientationLocked = orientationLocked,
        )
    }

    fun openMenu() {
        menuVisible = true
    }

    /** Allows the Activity's legacy Back/key paths to dismiss an already-open host menu. */
    fun isMenuVisible(): Boolean = menuVisible

    fun closeMenu() {
        menuVisible = false
    }
}

@Composable
internal fun RuntimeLimitFpsDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onReset: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.PREF_LIMIT_FPS)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { next -> value = next.filter(Char::isDigit) },
                modifier = Modifier.testTag("runtime_fps_input"),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.unlimited)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim().toIntOrNull() ?: 0) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.reset))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

@Composable
internal fun RuntimeMenuHost(
    state: RuntimeMenuUiState,
    menuVisible: Boolean,
    actions: RuntimeMenuActions,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (state.toolbarVisible) {
            RuntimeToolbar(
                state = state,
                actions = actions,
                onOpenMenu = onOpenMenu,
            )
        }

        if (menuVisible) {
            RuntimeMenuDialog(
                state = state,
                actions = actions,
                onDismiss = onDismissMenu,
            )
        }
    }
}

@Composable
private fun RuntimeToolbar(
    state: RuntimeMenuUiState,
    actions: RuntimeMenuActions,
    onOpenMenu: () -> Unit,
) {
    val actionSize = if (state.isCanvas) 36.dp else 48.dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.isCanvas && state.imeAvailable) {
                RuntimeToolbarAction(
                    icon = R.drawable.ic_action_keyboard,
                    label = R.string.action_keyboard_ime,
                    size = actionSize,
                    onClick = actions::onOpenImeKeyboard,
                )
            }
            if (state.isCanvas) {
                RuntimeToolbarAction(
                    icon = R.drawable.ic_action_screenshot,
                    label = R.string.take_screenshot,
                    size = actionSize,
                    onClick = actions::onTakeScreenshot,
                )
            }
            Box {
                RuntimeToolbarAction(
                    icon = R.drawable.ic_more_vert,
                    label = androidx.appcompat.R.string.abc_action_menu_overflow_description,
                    size = actionSize,
                    onClick = onOpenMenu,
                )
            }
        }
    }
}

@Composable
private fun RuntimeToolbarAction(
    icon: Int,
    label: Int,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(label),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RuntimeMenuDialog(
    state: RuntimeMenuUiState,
    actions: RuntimeMenuActions,
    onDismiss: () -> Unit,
) {
    var virtualKeyboardPage by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = state.title,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider()
                    RuntimeMenuItems(
                        state = state,
                        includeCanvasShortcuts = true,
                        virtualKeyboardPage = virtualKeyboardPage,
                        actions = actions,
                        onOpenVirtualKeyboardPage = { virtualKeyboardPage = true },
                        onCloseVirtualKeyboardPage = { virtualKeyboardPage = false },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeMenuItems(
    state: RuntimeMenuUiState,
    includeCanvasShortcuts: Boolean,
    virtualKeyboardPage: Boolean,
    actions: RuntimeMenuActions,
    onOpenVirtualKeyboardPage: () -> Unit,
    onCloseVirtualKeyboardPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (virtualKeyboardPage) {
        RuntimeMenuItem(
            label = R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
            leadingIcon = R.drawable.ic_arrow_back,
            onClick = onCloseVirtualKeyboardPage,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        RuntimeActionItem(R.string.layout_edit_mode, onDismiss, actions::onEditVirtualKeyboardLayout)
        RuntimeActionItem(R.string.layout_scale_mode, onDismiss, actions::onResizeVirtualKeyboardLayout)
        if (state.virtualKeyboardEditing) {
            RuntimeActionItem(
                R.string.layout_edit_finish,
                onDismiss,
                actions::onFinishVirtualKeyboardLayout,
            )
        }
        RuntimeActionItem(R.string.layout_switch, onDismiss, actions::onSwitchVirtualKeyboardLayout)
        RuntimeActionItem(R.string.hide_buttons, onDismiss, actions::onHideVirtualKeyboardButtons)
        return
    }

    RuntimeActionItem(R.string.exit, onDismiss, actions::onExit)
    RuntimeActionItem(R.string.save_log, onDismiss, actions::onSaveLog)
    RuntimeToggleItem(
        label = R.string.action_lock_orientation,
        checked = state.orientationLocked,
        onClick = {
            onDismiss()
            actions.onToggleOrientationLock()
        },
    )
    if (state.isCanvas) {
        if (includeCanvasShortcuts && state.imeAvailable) {
            RuntimeMenuItem(
                label = R.string.action_keyboard_ime,
                onClick = {
                    onDismiss()
                    actions.onOpenImeKeyboard()
                },
            )
        }
        if (includeCanvasShortcuts) {
            RuntimeMenuItem(
                label = R.string.take_screenshot,
                onClick = {
                    onDismiss()
                    actions.onTakeScreenshot()
                },
            )
        }
        RuntimeActionItem(R.string.PREF_LIMIT_FPS, onDismiss, actions::onLimitFps)
        if (state.virtualKeyboardAvailable) {
            RuntimeMenuItem(
                label = R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
                onClick = onOpenVirtualKeyboardPage,
            )
        }
    }
}

@Composable
private fun RuntimeActionItem(
    label: Int,
    onDismiss: () -> Unit,
    action: () -> Unit,
) {
    RuntimeMenuItem(label = label) {
        onDismiss()
        action()
    }
}

@Composable
private fun RuntimeToggleItem(
    label: Int,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = onClick,
        trailingIcon = {
            Switch(
                checked = checked,
                onCheckedChange = { onClick() },
            )
        },
    )
}

@Composable
private fun RuntimeMenuItem(
    label: Int,
    leadingIcon: Int? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = onClick,
        leadingIcon = leadingIcon?.let { icon ->
            {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                )
            }
        },
    )
}
