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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.clearNavigationFocusOnTouch
import io.github.h3nb.jlmodplus.ui.showNavigationFocusForKey
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward
import io.github.h3nb.jlmodplus.input.HostCommand
import javax.microedition.shell.timing.EmulationSpeed
import android.view.MotionEvent
import kotlin.math.roundToInt

/** Android-host menu state only; Java ME Displayable and Command state stay in the runtime. */
internal data class RuntimeMenuUiState(
    val title: String = "",
    val isCanvas: Boolean = false,
    val toolbarVisible: Boolean = true,
    val imeAvailable: Boolean = false,
    val virtualKeyboardAvailable: Boolean = false,
    val virtualKeyboardEditing: Boolean = false,
    val orientationLocked: Boolean = false,
    val emulationSpeedAvailable: Boolean = false,
    val emulationSpeedPercent: Int = EmulationSpeed.NORMAL_PERCENT,
    val emulationSpeedAuto: Boolean = false,
    val memoryEditorBubbleEnabled: Boolean = false,
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
    fun onEmulationSpeed()
    fun onSetEmulationSpeed(value: Int)
    fun onSetAutoEmulationSpeed()
    fun onResetEmulationSpeed()
    fun onMemoryEditor()
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
class RuntimeMenuComposeController @JvmOverloads constructor(
    composeView: ComposeView,
    private val actions: RuntimeMenuActions,
    private val hostDialogActions: RuntimeHostDialogActions? = null,
) {
    private var state by mutableStateOf(RuntimeMenuUiState())
    private var menuVisible by mutableStateOf(false)
    private var limitFpsVisible by mutableStateOf(false)
    private var emulationSpeedVisible by mutableStateOf(false)
    private var hostDialogState by mutableStateOf<RuntimeHostDialogState?>(null)
    private var virtualKeyboardPage by mutableStateOf(false)
    private var controllerFocusIndex by mutableIntStateOf(0)
    private var controllerFocusVisible by mutableStateOf(false)
    private val menuActions = object : RuntimeMenuActions by actions {
        override fun onLimitFps() {
            closeMenu()
            limitFpsVisible = true
        }

        override fun onEmulationSpeed() {
            closeMenu()
            actions.onEmulationSpeed()
            emulationSpeedVisible = true
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
                    virtualKeyboardPage = virtualKeyboardPage,
                    controllerFocusIndex = controllerFocusIndex.takeIf { controllerFocusVisible } ?: -1,
                    actions = menuActions,
                    onDismissMenu = ::closeMenu,
                    onOpenVirtualKeyboardPage = {
                        virtualKeyboardPage = true
                        controllerFocusIndex = 0
                    },
                    onCloseVirtualKeyboardPage = {
                        virtualKeyboardPage = false
                        controllerFocusIndex = 0
                    },
                    onNavigationFocusChanged = { controllerFocusVisible = it },
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
                if (emulationSpeedVisible) {
                    RuntimeEmulationSpeedDialog(
                        currentPercent = state.emulationSpeedPercent,
                        currentAutoEnabled = state.emulationSpeedAuto,
                        onDismiss = { emulationSpeedVisible = false },
                        onConfirm = { value, autoEnabled ->
                            emulationSpeedVisible = false
                            if (autoEnabled) actions.onSetAutoEmulationSpeed()
                            else actions.onSetEmulationSpeed(value)
                        },
                        onReset = {
                            emulationSpeedVisible = false
                            actions.onResetEmulationSpeed()
                        },
                    )
                }
                if (hostDialogActions != null) {
                    RuntimeHostDialogs(
                        state = hostDialogState,
                        actions = hostDialogActions,
                        onDismiss = { hostDialogState = null },
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
        emulationSpeedAvailable: Boolean,
        emulationSpeedPercent: Int,
        emulationSpeedAuto: Boolean,
        memoryEditorBubbleEnabled: Boolean,
    ) {
        state = RuntimeMenuUiState(
            title = title,
            isCanvas = isCanvas,
            toolbarVisible = toolbarVisible,
            imeAvailable = imeAvailable,
            virtualKeyboardAvailable = virtualKeyboardAvailable,
            virtualKeyboardEditing = virtualKeyboardEditing,
            orientationLocked = orientationLocked,
            emulationSpeedAvailable = emulationSpeedAvailable,
            emulationSpeedPercent = emulationSpeedPercent,
            emulationSpeedAuto = emulationSpeedAuto,
            memoryEditorBubbleEnabled = memoryEditorBubbleEnabled,
        )
    }

    fun openMenu() {
        menuVisible = true
        virtualKeyboardPage = false
        controllerFocusIndex = 0
        controllerFocusVisible = false
    }

    /** Allows the Activity's legacy Back/key paths to dismiss an already-open host menu. */
    fun isMenuVisible(): Boolean = menuVisible
            || limitFpsVisible
            || emulationSpeedVisible
            || hostDialogState != null

    fun closeMenu() {
        menuVisible = false
        limitFpsVisible = false
        emulationSpeedVisible = false
        hostDialogState = null
        virtualKeyboardPage = false
        controllerFocusIndex = 0
        controllerFocusVisible = false
    }

    /** Routes controller focus while the runtime menu or one of its dialogs owns the input. */
    fun handleHostCommand(command: HostCommand, pressed: Boolean): Boolean {
        if (!isMenuVisible()) return false
        if (!pressed) return true
        controllerFocusVisible = true
        if (hostDialogState != null || limitFpsVisible || emulationSpeedVisible) {
            if (command == HostCommand.Back ||
                command == HostCommand.OpenMenu ||
                command == HostCommand.OpenKeypad
            ) {
                dismissControllerDialog()
            } else if (command == HostCommand.Activate) {
                activateControllerDialog()
            }
            return true
        }
        when (command) {
            HostCommand.NavigateUp,
            HostCommand.NavigateLeft,
            HostCommand.PreviousTab,
            -> moveControllerFocus(-1)
            HostCommand.NavigateDown,
            HostCommand.NavigateRight,
            HostCommand.NextTab,
            -> moveControllerFocus(1)
            HostCommand.Back,
            HostCommand.OpenMenu,
            HostCommand.OpenKeypad,
            -> closeMenu()
            HostCommand.Activate -> controllerItems().getOrNull(controllerFocusIndex)?.activate?.invoke()
        }
        return true
    }

    /** Converts a centered left-stick/HAT sample to one menu-focus step. */
    fun handleControllerMotion(event: MotionEvent): Boolean {
        if (!isMenuVisible()) return false
        if (hostDialogState != null || limitFpsVisible || emulationSpeedVisible) return true
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (kotlin.math.abs(hatX) >= 0.5f) hatX else event.getAxisValue(MotionEvent.AXIS_X)
        val y = if (kotlin.math.abs(hatY) >= 0.5f) hatY else event.getAxisValue(MotionEvent.AXIS_Y)
        if (kotlin.math.abs(y) >= 0.5f) {
            controllerFocusVisible = true
            moveControllerFocus(if (y < 0f) -1 else 1)
        } else if (kotlin.math.abs(x) >= 0.5f) {
            controllerFocusVisible = true
            moveControllerFocus(if (x < 0f) -1 else 1)
        }
        return true
    }

    private fun dismissControllerDialog() {
        when (hostDialogState) {
            is RuntimeHostDialogState.MidletSelection -> hostDialogActions?.onMidletCancelled()
            is RuntimeHostDialogState.Error -> hostDialogActions?.onErrorAcknowledged()
            else -> Unit
        }
        closeMenu()
    }

    private fun activateControllerDialog() {
        when (hostDialogState) {
            is RuntimeHostDialogState.Error -> {
                val error = hostDialogState is RuntimeHostDialogState.Error
                closeMenu()
                if (error) hostDialogActions?.onErrorAcknowledged()
            }
            RuntimeHostDialogState.ExitConfirmation -> {
                closeMenu()
                hostDialogActions?.onExitConfirmed(false)
            }
            else -> Unit
        }
    }

    private fun moveControllerFocus(delta: Int) {
        val count = controllerItems().size
        if (count == 0) return
        controllerFocusIndex = (controllerFocusIndex + delta).mod(count)
    }

    private data class ControllerMenuItem(val id: String, val activate: () -> Unit)

    private fun controllerItems(): List<ControllerMenuItem> {
        if (virtualKeyboardPage) {
            return buildList {
                add(ControllerMenuItem("vk.back") { virtualKeyboardPage = false; controllerFocusIndex = 0 })
                add(ControllerMenuItem("vk.edit") { closeMenu(); actions.onEditVirtualKeyboardLayout() })
                add(ControllerMenuItem("vk.resize") { closeMenu(); actions.onResizeVirtualKeyboardLayout() })
                if (state.virtualKeyboardEditing) {
                    add(ControllerMenuItem("vk.finish") { closeMenu(); actions.onFinishVirtualKeyboardLayout() })
                }
                add(ControllerMenuItem("vk.switch") { closeMenu(); actions.onSwitchVirtualKeyboardLayout() })
                add(ControllerMenuItem("vk.hide") { closeMenu(); actions.onHideVirtualKeyboardButtons() })
            }
        }
        return buildList {
            add(ControllerMenuItem("exit") { closeMenu(); actions.onExit() })
            add(ControllerMenuItem("save") { closeMenu(); actions.onSaveLog() })
            add(ControllerMenuItem("orientation") { closeMenu(); actions.onToggleOrientationLock() })
            add(ControllerMenuItem("memory") { closeMenu(); actions.onMemoryEditor() })
            if (state.isCanvas) {
                if (state.imeAvailable) add(ControllerMenuItem("ime") { closeMenu(); actions.onOpenImeKeyboard() })
                add(ControllerMenuItem("screenshot") { closeMenu(); actions.onTakeScreenshot() })
                add(ControllerMenuItem("fps") { menuActions.onLimitFps() })
                if (state.emulationSpeedAvailable) add(ControllerMenuItem("speed") { menuActions.onEmulationSpeed() })
                if (state.virtualKeyboardAvailable) add(ControllerMenuItem("vk") {
                    virtualKeyboardPage = true
                    controllerFocusIndex = 0
                })
            }
        }
    }

    fun showMidletDialog(names: Array<String>) {
        hostDialogState = RuntimeHostDialogState.MidletSelection(names.toList())
    }

    fun showErrorDialog(message: String) {
        hostDialogState = RuntimeHostDialogState.Error(message)
    }

    fun showExitConfirmation() {
        hostDialogState = RuntimeHostDialogState.ExitConfirmation
    }

    fun showHideButtons(names: Array<String>, checked: BooleanArray) {
        hostDialogState = RuntimeHostDialogState.HideButtons(names.toList(), checked.copyOf())
    }

    fun showSaveVirtualKeyboard(phone: Boolean, keepScreenPreferred: Boolean) {
        hostDialogState = RuntimeHostDialogState.SaveVirtualKeyboard(phone, keepScreenPreferred)
    }

    fun showLayoutSelection(entries: Array<String>, selected: Int) {
        hostDialogState = RuntimeHostDialogState.LayoutSelection(entries.toList(), selected)
    }
}

private data class RuntimeMenuDialogLayout(
    val modifier: Modifier,
    val properties: DialogProperties,
)

@Composable
private fun runtimeMenuDialogLayout(): RuntimeMenuDialogLayout {
    return RuntimeMenuDialogLayout(
        modifier = Modifier,
        properties = DialogProperties(),
    )
}

@Composable
private fun runtimeMenuDialogContentHeight() =
    adaptiveDialogLayout().maxHeight

@Composable
internal fun RuntimeLimitFpsDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onReset: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val layout = runtimeMenuDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
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
internal fun RuntimeEmulationSpeedDialog(
    currentPercent: Int,
    currentAutoEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val manualPercent = currentPercent.takeIf(EmulationSpeed::isValidPercent)
        ?: EmulationSpeed.NORMAL_PERCENT
    val presetValues = remember(manualPercent) {
        (EmulationSpeed.presets().toList() + manualPercent).distinct().sorted()
    }
    val currentIndex = presetValues.indexOf(manualPercent).coerceAtLeast(0)
    var draftIndex by remember(currentIndex, presetValues) { mutableFloatStateOf(currentIndex.toFloat()) }
    var autoEnabled by remember(currentAutoEnabled) { mutableStateOf(currentAutoEnabled) }
    val selectedIndex = draftIndex.roundToInt().coerceIn(presetValues.indices)
    val selectedValue = presetValues[selectedIndex]
    val layout = runtimeMenuDialogLayout()
    val dialogBounds = adaptiveDialogLayout()
    val scrollState = rememberScrollState()
    val canScrollForward by remember(scrollState) {
        derivedStateOf { scrollState.canScrollForward }
    }
    AlertDialog(
        textScrollable = false,
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.PREF_EMULATION_SPEED)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = dialogBounds.maxHeight),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                ) {
                    Text(
                        text = stringResource(R.string.config_help_emulation_speed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = if (autoEnabled) {
                            stringResource(
                                R.string.emulation_speed_auto_value,
                                EmulationSpeed.formatRuntimeMultiplier(currentPercent),
                            )
                        } else {
                            EmulationSpeed.formatMultiplier(selectedValue)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = autoEnabled,
                                role = Role.Switch,
                                onValueChange = { autoEnabled = it },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.emulation_speed_auto),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.emulation_speed_auto_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoEnabled, onCheckedChange = null)
                    }
                    Slider(
                        value = draftIndex,
                        onValueChange = { draftIndex = it },
                        enabled = !autoEnabled,
                        valueRange = 0f..presetValues.lastIndex.toFloat(),
                        steps = (presetValues.size - 2).coerceAtLeast(0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("runtime_emulation_speed_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = EmulationSpeed.formatMultiplier(presetValues.first()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = EmulationSpeed.formatMultiplier(presetValues.last()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedValue, autoEnabled) }) {
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
    virtualKeyboardPage: Boolean = false,
    controllerFocusIndex: Int = -1,
    actions: RuntimeMenuActions,
    onDismissMenu: () -> Unit,
    onOpenVirtualKeyboardPage: () -> Unit = {},
    onCloseVirtualKeyboardPage: () -> Unit = {},
    modifier: Modifier = Modifier,
    onNavigationFocusChanged: (Boolean) -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clearNavigationFocusOnTouch { onNavigationFocusChanged(false) }
            .showNavigationFocusForKey { onNavigationFocusChanged(true) },
    ) {
        if (state.toolbarVisible) {
            RuntimeToolbar(
                state = state,
                actions = actions,
            )
        }

        if (menuVisible) {
            RuntimeMenuDialog(
                state = state,
                actions = actions,
                virtualKeyboardPage = virtualKeyboardPage,
                controllerFocusIndex = controllerFocusIndex,
                onDismiss = onDismissMenu,
                onOpenVirtualKeyboardPage = onOpenVirtualKeyboardPage,
                onCloseVirtualKeyboardPage = onCloseVirtualKeyboardPage,
                onNavigationFocusChanged = onNavigationFocusChanged,
            )
        }
    }
}

@Composable
private fun RuntimeToolbar(
    state: RuntimeMenuUiState,
    actions: RuntimeMenuActions,
) {
    // Keep canvas chrome compact in content, but never make its touch targets smaller than 48dp.
    val actionSize = 48.dp
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
    virtualKeyboardPage: Boolean,
    controllerFocusIndex: Int,
    onDismiss: () -> Unit,
    onOpenVirtualKeyboardPage: () -> Unit,
    onCloseVirtualKeyboardPage: () -> Unit,
    onNavigationFocusChanged: (Boolean) -> Unit,
) {
    val layout = runtimeMenuDialogLayout()
    AlertDialog(
        textScrollable = false,
        modifier = layout.modifier
            .clearNavigationFocusOnTouch { onNavigationFocusChanged(false) }
            .showNavigationFocusForKey { onNavigationFocusChanged(true) },
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (virtualKeyboardPage) {
                    stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS)
                } else {
                    state.title
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            val listState = rememberLazyListState()
            val maxListHeight = runtimeMenuDialogContentHeight()
            val canScrollForward = rememberLazyListCanScrollForward(listState)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                ) {
                    runtimeMenuItems(
                        state = state,
                        includeCanvasShortcuts = true,
                        virtualKeyboardPage = virtualKeyboardPage,
                        focusedIndex = controllerFocusIndex,
                        actions = actions,
                        onOpenVirtualKeyboardPage = onOpenVirtualKeyboardPage,
                        onCloseVirtualKeyboardPage = onCloseVirtualKeyboardPage,
                        onDismiss = onDismiss,
                    )
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier
                        .align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = null,
    )
}

private fun LazyListScope.runtimeMenuItems(
    state: RuntimeMenuUiState,
    includeCanvasShortcuts: Boolean,
    virtualKeyboardPage: Boolean,
    focusedIndex: Int,
    actions: RuntimeMenuActions,
    onOpenVirtualKeyboardPage: () -> Unit,
    onCloseVirtualKeyboardPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var rowIndex = 0
    fun nextFocused(): Boolean = focusedIndex == rowIndex++

    if (virtualKeyboardPage) {
        val backFocused = nextFocused()
        item {
            RuntimeMenuItem(
                label = R.string.action_back,
                leadingIcon = R.drawable.ic_arrow_back,
                focused = backFocused,
                onClick = onCloseVirtualKeyboardPage,
            )
        }
        val editFocused = nextFocused()
        item {
            RuntimeActionItem(R.string.layout_edit_mode, onDismiss, actions::onEditVirtualKeyboardLayout,
                leadingIcon = R.drawable.ic_edit, focused = editFocused)
        }
        val resizeFocused = nextFocused()
        item {
            RuntimeActionItem(
                R.string.layout_scale_mode,
                onDismiss,
                actions::onResizeVirtualKeyboardLayout,
                leadingIcon = R.drawable.ic_runtime_resize,
                focused = resizeFocused,
            )
        }
        if (state.virtualKeyboardEditing) {
            val finishFocused = nextFocused()
            item {
                RuntimeActionItem(
                    R.string.layout_edit_finish,
                    onDismiss,
                    actions::onFinishVirtualKeyboardLayout,
                    leadingIcon = R.drawable.ic_runtime_done,
                    focused = finishFocused,
                )
            }
        }
        val switchFocused = nextFocused()
        item {
            RuntimeActionItem(R.string.layout_switch, onDismiss, actions::onSwitchVirtualKeyboardLayout,
                leadingIcon = R.drawable.ic_runtime_switch, focused = switchFocused)
        }
        val hideFocused = nextFocused()
        item {
            RuntimeActionItem(
                R.string.hide_buttons,
                onDismiss,
                actions::onHideVirtualKeyboardButtons,
                leadingIcon = R.drawable.ic_runtime_hide,
                focused = hideFocused,
            )
        }
        return
    }

    val exitFocused = nextFocused()
    item {
        RuntimeActionItem(R.string.exit, onDismiss, actions::onExit, leadingIcon = R.drawable.ic_logout,
            focused = exitFocused)
    }
    val saveFocused = nextFocused()
    item {
        RuntimeActionItem(R.string.save_log, onDismiss, actions::onSaveLog, leadingIcon = R.drawable.ic_save,
            focused = saveFocused)
    }
    val orientationFocused = nextFocused()
    item {
        RuntimeToggleItem(
            label = R.string.action_lock_orientation,
            checked = state.orientationLocked,
            leadingIcon = R.drawable.ic_screen_lock_rotation,
            focused = orientationFocused,
            onClick = {
                onDismiss()
                actions.onToggleOrientationLock()
            },
        )
    }
    val memoryFocused = nextFocused()
    item {
        RuntimeToggleItem(
            label = R.string.memory_editor_bubble,
            checked = state.memoryEditorBubbleEnabled,
            leadingIcon = R.drawable.ic_runtime_memory,
            focused = memoryFocused,
            onClick = {
                onDismiss()
                actions.onMemoryEditor()
            },
        )
    }
    if (state.isCanvas) {
        if (includeCanvasShortcuts && state.imeAvailable) {
            val imeFocused = nextFocused()
            item {
                RuntimeMenuItem(
                    label = R.string.action_keyboard_ime,
                    leadingIcon = R.drawable.ic_action_keyboard,
                    focused = imeFocused,
                    onClick = {
                        onDismiss()
                        actions.onOpenImeKeyboard()
                    },
                )
            }
        }
        if (includeCanvasShortcuts) {
            val screenshotFocused = nextFocused()
            item {
                RuntimeMenuItem(
                    label = R.string.take_screenshot,
                    leadingIcon = R.drawable.ic_action_screenshot,
                    focused = screenshotFocused,
                    onClick = {
                        onDismiss()
                        actions.onTakeScreenshot()
                    },
                )
            }
        }
        val fpsFocused = nextFocused()
        item {
            RuntimeActionItem(R.string.PREF_LIMIT_FPS, onDismiss, actions::onLimitFps,
                leadingIcon = R.drawable.ic_runtime_fps, focused = fpsFocused)
        }
        if (state.emulationSpeedAvailable) {
            val speedFocused = nextFocused()
            item {
                RuntimeActionItem(
                    R.string.PREF_EMULATION_SPEED,
                    onDismiss,
                    actions::onEmulationSpeed,
                    leadingIcon = R.drawable.ic_speed,
                    focused = speedFocused,
                )
            }
        }
        if (state.virtualKeyboardAvailable) {
            val vkFocused = nextFocused()
            item {
                RuntimeMenuItem(
                    label = R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
                    leadingIcon = R.drawable.ic_runtime_virtual_keyboard,
                    focused = vkFocused,
                    onClick = onOpenVirtualKeyboardPage,
                )
            }
        }
    }
}

@Composable
private fun RuntimeActionItem(
    label: Int,
    onDismiss: () -> Unit,
    action: () -> Unit,
    leadingIcon: Int? = null,
    focused: Boolean = false,
) {
    RuntimeMenuItem(label = label, leadingIcon = leadingIcon, focused = focused) {
        onDismiss()
        action()
    }
}

@Composable
private fun RuntimeToggleItem(
    label: Int,
    checked: Boolean,
    leadingIcon: Int? = null,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (focused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else androidx.compose.ui.graphics.Color.Transparent,
        ),
        headlineContent = {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        leadingContent = leadingIcon?.let { icon ->
            {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focused) Modifier.testTag("runtime-controller-focus-indicator")
                else Modifier,
            )
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { onClick() },
            ),
    )
}

@Composable
private fun RuntimeMenuItem(
    label: Int,
    leadingIcon: Int? = null,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (focused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else androidx.compose.ui.graphics.Color.Transparent,
        ),
        headlineContent = {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        leadingContent = leadingIcon?.let { icon ->
            {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focused) Modifier.testTag("runtime-controller-focus-indicator")
                else Modifier,
            )
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
    )
}
