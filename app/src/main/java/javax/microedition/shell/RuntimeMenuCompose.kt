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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.HostCommand
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.ControllerDialogInputScope
import io.github.h3nb.jlmodplus.ui.ControllerHostCommandHandler
import io.github.h3nb.jlmodplus.ui.DialogWindowPolicy
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.ProvideDialogWindowPolicy
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.clearNavigationFocusOnTouch
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward
import io.github.h3nb.jlmodplus.ui.showNavigationFocusForKey
import javax.microedition.shell.timing.EmulationSpeed
import kotlin.math.max
import kotlin.math.roundToInt

/** Android-host menu state only; Java ME Displayable and Command state stay in the runtime. */
internal data class RuntimeMenuUiState(
    val title: String = "",
    val isCanvas: Boolean = false,
    val toolbarVisible: Boolean = true,
    val statusBarVisible: Boolean = true,
    val navigationBarVisible: Boolean = true,
    val imeAvailable: Boolean = false,
    val virtualKeyboardAvailable: Boolean = false,
    val virtualKeyboardEditing: Boolean = false,
    val orientationLocked: Boolean = false,
    val emulationSpeedAvailable: Boolean = false,
    val emulationSpeedPercent: Int = EmulationSpeed.NORMAL_PERCENT,
    val memoryEditorBubbleEnabled: Boolean = false,
    val toolbarWidthDp: Float? = null,
    val toolbarFontScale: Float = 1f,
    val toolbarInsetLeftPx: Int = 0,
    val toolbarInsetTopPx: Int = 0,
    val toolbarInsetRightPx: Int = 0,
    val toolbarInsetBottomPx: Int = 0,
    val toolbarDensity: Float = 1f,
)

interface RuntimeMenuActions {
    fun onExit()
    fun onSaveLog()
    fun onToggleOrientationLock()
    fun onToggleFullscreen() {}
    fun onOpenImeKeyboard()
    fun onTakeScreenshot()
    fun onLimitFps()
    fun onSetFpsLimit(value: Int)
    fun onResetFpsLimit()
    fun onEmulationSpeed()
    fun onSetEmulationSpeed(value: Int)
    fun onResetEmulationSpeed()
    fun onMemoryEditor()
    fun onEditVirtualKeyboardLayout()

    fun onFinishVirtualKeyboardLayout()
    fun onSwitchVirtualKeyboardLayout()
    fun onShowControls()
}

/**
 * Interop owner for the app-owned runtime chrome. Rendering, input dispatch, and MIDP
 * Displayable transitions deliberately remain in [MicroActivity].
 */
class RuntimeMenuComposeController @JvmOverloads constructor(
    composeView: ComposeView,
    private val actions: RuntimeMenuActions,
    private val hostDialogActions: RuntimeHostDialogActions? = null,
    private val dialogKeyDispatcher: (KeyEvent) -> Boolean = { false },
    private val dialogMotionDispatcher: (MotionEvent) -> Boolean = { false },
    private val onControllerTargetChanging: Runnable = Runnable {},
) {
    private var state by mutableStateOf(RuntimeMenuUiState())
    private var menuVisible by mutableStateOf(false)
    private var limitFpsVisible by mutableStateOf(false)
    private var emulationSpeedVisible by mutableStateOf(false)
    private var hostDialogState by mutableStateOf<RuntimeHostDialogState?>(null)
    private var virtualKeyboardPage by mutableStateOf(false)
    private var controllerFocusIndex by mutableIntStateOf(0)
    private var controllerFocusVisible by mutableStateOf(false)
    private var dialogHostCommandHandler: ControllerHostCommandHandler? = null
    private fun changeControllerSurface(change: () -> Unit) {
        // Release the outgoing host input domain before mutating state; this can be re-entrant from DOWN.
        onControllerTargetChanging.run()
        dialogHostCommandHandler = null
        change()
    }

    private val menuActions = object : RuntimeMenuActions by actions {
        override fun onLimitFps() {
            changeControllerSurface {
                menuVisible = false
                virtualKeyboardPage = false
                limitFpsVisible = true
            }
        }

        override fun onEmulationSpeed() {
            actions.onEmulationSpeed()
            changeControllerSurface {
                menuVisible = false
                virtualKeyboardPage = false
                emulationSpeedVisible = true
            }
        }
    }

    init {
        fun updateToolbarSize(width: Int) {
            updateState(state.copy(
                toolbarWidthDp = width / composeView.resources.displayMetrics.density,
                toolbarDensity = composeView.resources.displayMetrics.density,
                toolbarFontScale = composeView.resources.configuration.fontScale,
            ))
        }
        updateToolbarSize(composeView.width)
        composeView.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            updateToolbarSize(right - left)
        }
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            val statusBarVisible = state.statusBarVisible
            val navigationBarVisible = state.navigationBarVisible
            val dialogWindowPolicy = remember(statusBarVisible, navigationBarVisible) {
                DialogWindowPolicy { window ->
                    RuntimeSystemBarController.apply(
                        window,
                        statusBarVisible,
                        navigationBarVisible,
                    )
                }
            }
            ProvideDialogWindowPolicy(dialogWindowPolicy) {
                JLModPlusTheme {
                    RuntimeMenuHost(
                        state = state,
                        menuVisible = menuVisible,
                        virtualKeyboardPage = virtualKeyboardPage,
                        controllerFocusIndex = controllerFocusIndex.takeIf { controllerFocusVisible } ?: -1,
                        actions = menuActions,
                        onDismissMenu = ::closeMenu,
                        onOpenMenu = ::openMenu,
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
                        ControllerDialogInputScope(
                            onControllerKeyEvent = dialogKeyDispatcher,
                            onControllerMotionEvent = dialogMotionDispatcher,
                            onControllerHostCommandHandlerChanged = { dialogHostCommandHandler = it },
                        ) {
                            RuntimeLimitFpsDialog(
                                onDismiss = { changeControllerSurface { limitFpsVisible = false } },
                                onConfirm = { value ->
                                    changeControllerSurface { limitFpsVisible = false }
                                    actions.onSetFpsLimit(value)
                                },
                                onReset = {
                                    changeControllerSurface { limitFpsVisible = false }
                                    actions.onResetFpsLimit()
                                },
                            )
                        }
                    }
                    if (emulationSpeedVisible) {
                        ControllerDialogInputScope(
                            onControllerKeyEvent = dialogKeyDispatcher,
                            onControllerMotionEvent = dialogMotionDispatcher,
                            onControllerHostCommandHandlerChanged = { dialogHostCommandHandler = it },
                        ) {
                            RuntimeEmulationSpeedDialog(
                                currentPercent = state.emulationSpeedPercent,
                                onDismiss = { changeControllerSurface { emulationSpeedVisible = false } },
                                onConfirm = { value ->
                                    changeControllerSurface { emulationSpeedVisible = false }
                                    actions.onSetEmulationSpeed(value)
                                },
                                onReset = {
                                    changeControllerSurface { emulationSpeedVisible = false }
                                    actions.onResetEmulationSpeed()
                                },
                            )
                        }
                    }
                    if (hostDialogActions != null) {
                        ControllerDialogInputScope(
                            onControllerKeyEvent = dialogKeyDispatcher,
                            onControllerMotionEvent = dialogMotionDispatcher,
                            onControllerHostCommandHandlerChanged = { dialogHostCommandHandler = it },
                        ) {
                            RuntimeHostDialogs(
                                state = hostDialogState,
                                actions = hostDialogActions,
                                onDismiss = { changeControllerSurface { hostDialogState = null } },
                            )
                        }
                    }
                }

            }
        }
    }

    fun update(
        title: String,
        isCanvas: Boolean,
        toolbarVisible: Boolean,
        statusBarVisible: Boolean,
        navigationBarVisible: Boolean,
        imeAvailable: Boolean,
        virtualKeyboardAvailable: Boolean,
        virtualKeyboardEditing: Boolean,
        orientationLocked: Boolean,
        emulationSpeedAvailable: Boolean,
        emulationSpeedPercent: Int,
        memoryEditorBubbleEnabled: Boolean,
    ) {
        updateState(state.copy(
            title = title,
            isCanvas = isCanvas,
            toolbarVisible = toolbarVisible,
            statusBarVisible = statusBarVisible,
            navigationBarVisible = navigationBarVisible,
            imeAvailable = imeAvailable,
            virtualKeyboardAvailable = virtualKeyboardAvailable,
            virtualKeyboardEditing = virtualKeyboardEditing,
            orientationLocked = orientationLocked,
            emulationSpeedAvailable = emulationSpeedAvailable,
            emulationSpeedPercent = emulationSpeedPercent,
            memoryEditorBubbleEnabled = memoryEditorBubbleEnabled,
        ))
    }

    /** Host-safe insets affect content only; the toolbar background still fills the window. */
    fun updateToolbarInsets(leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int) {
        updateState(state.copy(
            toolbarInsetLeftPx = leftPx.coerceAtLeast(0),
            toolbarInsetTopPx = topPx.coerceAtLeast(0),
            toolbarInsetRightPx = rightPx.coerceAtLeast(0),
            toolbarInsetBottomPx = bottomPx.coerceAtLeast(0),
        ))
    }

    private fun updateState(next: RuntimeMenuUiState) {
        if (state == next) return
        val focusedLabel = controllerItems().getOrNull(controllerFocusIndex)?.label
        state = next
        val entries = controllerItems()
        val previousActionIndex = entries.indexOfFirst { it.label == focusedLabel }
        controllerFocusIndex = if (previousActionIndex >= 0) previousActionIndex
        else controllerFocusIndex.coerceIn(0, entries.lastIndex.coerceAtLeast(0))
    }

    fun openMenu() {
        changeControllerSurface {
            menuVisible = true
            virtualKeyboardPage = false
            controllerFocusIndex = 0
            controllerFocusVisible = false
        }
    }

    /** Allows the Activity's legacy Back/key paths to dismiss an already-open host menu. */
    fun isMenuVisible(): Boolean = menuVisible
            || limitFpsVisible
            || emulationSpeedVisible
            || hostDialogState != null

    fun closeMenu() {
        if (!isMenuVisible()) return
        val continueVirtualKeyboardEdit =
            hostDialogState is RuntimeHostDialogState.FinishVirtualKeyboardEdit
        changeControllerSurface {
            menuVisible = false
            limitFpsVisible = false
            emulationSpeedVisible = false
            hostDialogState = null
            virtualKeyboardPage = false
            controllerFocusIndex = 0
            controllerFocusVisible = false
        }
        if (continueVirtualKeyboardEdit) {
            hostDialogActions?.onVirtualKeyboardEditContinued()
        }
    }

    /** Routes controller focus while the runtime menu or one of its dialogs owns the input. */
    fun handleHostCommand(command: HostCommand, pressed: Boolean): Boolean {
        if (!isMenuVisible()) return false
        if (hostDialogState != null || limitFpsVisible || emulationSpeedVisible) {
            // Dialog semantics stay inside Compose. Preserve both DOWN and UP so a focused Compose
            // control receives a complete Activate key sequence. Consume during composition
            // hand-off as well so no controller edge can leak into the MIDlet behind the modal.
            return dialogHostCommandHandler?.invoke(command, pressed) ?: true
        }
        if (!pressed) return true
        controllerFocusVisible = true
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
            HostCommand.OpenKeypad,
            -> if (virtualKeyboardPage) {
                virtualKeyboardPage = false
                controllerFocusIndex = 0
            } else {
                closeMenu()
            }
            HostCommand.OpenMenu -> closeMenu()
            HostCommand.Activate -> controllerItems().getOrNull(controllerFocusIndex)?.activate?.invoke()
        }
        return true
    }

    private fun moveControllerFocus(delta: Int) {
        val count = controllerItems().size
        if (count == 0) return
        controllerFocusIndex = (controllerFocusIndex + delta).mod(count)
    }

    private fun controllerItems(): List<RuntimeMenuEntry> = runtimeMenuEntries(
        state = state,
        shortcuts = runtimeToolbarShortcuts(state, state.toolbarWidthDp ?: 0f),
        virtualKeyboardPage = virtualKeyboardPage,
        actions = menuActions,
        onDismiss = ::closeMenu,
        onOpenVirtualKeyboardPage = {
            virtualKeyboardPage = true
            controllerFocusIndex = 0
        },
    )

    fun showMidletDialog(names: Array<String>) {
        changeControllerSurface {
            hostDialogState = RuntimeHostDialogState.MidletSelection(names.toList())
        }
    }

    fun showErrorDialog(message: String) {
        changeControllerSurface { hostDialogState = RuntimeHostDialogState.Error(message) }
    }

    fun showExitConfirmation() {
        changeControllerSurface { hostDialogState = RuntimeHostDialogState.ExitConfirmation }
    }

    fun showControls(names: Array<String>, hidden: BooleanArray) {
        changeControllerSurface {
            hostDialogState = RuntimeHostDialogState.ShowControls(names.toList(), hidden.copyOf())
        }
    }

    fun showSaveVirtualKeyboard(updateTarget: String? = null) {
        changeControllerSurface {
            hostDialogState = RuntimeHostDialogState.SaveVirtualKeyboard(updateTarget)
        }
    }

    fun showFinishVirtualKeyboardEdit(updateTarget: String? = null) {
        changeControllerSurface {
            hostDialogState = RuntimeHostDialogState.FinishVirtualKeyboardEdit(updateTarget)
        }
    }

    fun showLayoutSelection(
        entries: Array<String>,
        selected: Int,
        updateTarget: String? = null,
    ) {
        changeControllerSurface {
            hostDialogState = RuntimeHostDialogState.LayoutSelection(
                entries.toList(),
                selected,
                updateTarget,
            )
        }
    }

    fun showLayoutEditGuide() {
        changeControllerSurface { hostDialogState = RuntimeHostDialogState.LayoutEditGuide }
    }
}

private data class RuntimeMenuDialogLayout(
    val modifier: Modifier,
    val properties: DialogProperties,
)

@Composable
private fun runtimeMenuDialogLayout(): RuntimeMenuDialogLayout = RuntimeMenuDialogLayout(
    modifier = Modifier,
    properties = DialogProperties(),
)

@Composable
private fun runtimeMenuDialogContentHeight() = adaptiveDialogLayout().maxHeight

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
                placeholder = { Text(stringResource(R.string.fps_limit_maximum)) },
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
                TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
internal fun RuntimeEmulationSpeedDialog(
    currentPercent: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val manualPercent = currentPercent.takeIf(EmulationSpeed::isValidPercent)
        ?: EmulationSpeed.NORMAL_PERCENT
    val presetValues = remember(manualPercent) {
        (EmulationSpeed.presets().toList() + manualPercent).distinct().sorted()
    }
    val currentIndex = presetValues.indexOf(manualPercent).coerceAtLeast(0)
    var draftIndex by remember(currentIndex, presetValues) { mutableFloatStateOf(currentIndex.toFloat()) }
    val selectedIndex = draftIndex.roundToInt().coerceIn(presetValues.indices)
    val selectedValue = presetValues[selectedIndex]
    val layout = runtimeMenuDialogLayout()
    val dialogBounds = adaptiveDialogLayout()
    val scrollState = rememberScrollState()
    val canScrollForward by remember(scrollState) { derivedStateOf { scrollState.canScrollForward } }
    AlertDialog(
        textScrollable = false,
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.PREF_EMULATION_SPEED)) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(max = dialogBounds.maxHeight),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                ) {
                    Text(
                        text = stringResource(R.string.config_help_emulation_speed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = EmulationSpeed.formatMultiplier(selectedValue),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Slider(
                        value = draftIndex,
                        onValueChange = { draftIndex = it },
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
            TextButton(onClick = { onConfirm(selectedValue) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
internal fun RuntimeMenuHost(
    state: RuntimeMenuUiState,
    menuVisible: Boolean,
    actions: RuntimeMenuActions,
    onDismissMenu: () -> Unit,
    onOpenMenu: () -> Unit = {},
    modifier: Modifier = Modifier,
    virtualKeyboardPage: Boolean = false,
    controllerFocusIndex: Int = -1,
    onOpenVirtualKeyboardPage: () -> Unit = {},
    onCloseVirtualKeyboardPage: () -> Unit = {},
    onNavigationFocusChanged: (Boolean) -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clearNavigationFocusOnTouch { onNavigationFocusChanged(false) }
            .showNavigationFocusForKey { onNavigationFocusChanged(true) },
    ) {
        val density = LocalDensity.current
        val layoutState = if (state.toolbarWidthDp == null) state.copy(
            toolbarWidthDp = maxWidth.value,
            toolbarDensity = density.density,
            toolbarFontScale = density.fontScale,
        ) else state
        val shortcuts = runtimeToolbarShortcuts(layoutState, layoutState.toolbarWidthDp ?: 0f)
        if (state.toolbarVisible) {
            RuntimeToolbar(state = layoutState, shortcuts = shortcuts, actions = actions, onOpenMenu = onOpenMenu)
        }

        if (menuVisible) {
            RuntimeMenuDialog(
                state = state,
                entries = runtimeMenuEntries(
                    state, shortcuts, virtualKeyboardPage, actions,
                    onDismissMenu, onOpenVirtualKeyboardPage,
                ),
                virtualKeyboardPage = virtualKeyboardPage,
                controllerFocusIndex = controllerFocusIndex,
                onDismiss = onDismissMenu,
                onCloseVirtualKeyboardPage = onCloseVirtualKeyboardPage,
                onNavigationFocusChanged = onNavigationFocusChanged,
            )
        }
    }
}

internal enum class RuntimeToolbarShortcut { Speed, Ime, Screenshot }

/** Reserve a readable title and overflow before assigning optional 48dp touch targets. */
internal fun runtimeToolbarShortcuts(state: RuntimeMenuUiState, widthDp: Float): List<RuntimeToolbarShortcut> {
    if (!state.toolbarVisible || !state.isCanvas) return emptyList()
    val leftPadding = max(16f, state.toolbarInsetLeftPx / state.toolbarDensity)
    val rightPadding = max(16f, state.toolbarInsetRightPx / state.toolbarDensity)
    var remaining = widthDp - leftPadding - rightPadding - 72f - 48f
    return buildList {
        fun include(shortcut: RuntimeToolbarShortcut, width: Float) {
            if (remaining >= width) {
                add(shortcut)
                remaining -= width
            }
        }
        if (state.emulationSpeedAvailable) include(RuntimeToolbarShortcut.Speed, 64f * state.toolbarFontScale.coerceAtLeast(1f))
        if (state.imeAvailable) include(RuntimeToolbarShortcut.Ime, 48f)
        include(RuntimeToolbarShortcut.Screenshot, 48f)
    }
}

@Composable
private fun RuntimeToolbar(
    state: RuntimeMenuUiState,
    shortcuts: List<RuntimeToolbarShortcut>,
    actions: RuntimeMenuActions,
    onOpenMenu: () -> Unit,
) {
    val actionSize = 48.dp
    val density = LocalDensity.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(
                start = maxOf(16.dp, with(density) { state.toolbarInsetLeftPx.toDp() }),
                top = with(density) { state.toolbarInsetTopPx.toDp() },
                end = maxOf(16.dp, with(density) { state.toolbarInsetRightPx.toDp() }),
                bottom = with(density) { state.toolbarInsetBottomPx.toDp() },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            shortcuts.forEach { shortcut ->
                when (shortcut) {
                    RuntimeToolbarShortcut.Speed -> {
                        val label = stringResource(R.string.PREF_EMULATION_SPEED)
                        val multiplier = EmulationSpeed.formatMultiplier(state.emulationSpeedPercent)
                        TextButton(
                            onClick = actions::onEmulationSpeed,
                            modifier = Modifier
                                .width((64f * state.toolbarFontScale.coerceAtLeast(1f)).dp)
                                .heightIn(min = actionSize)
                                .semantics { contentDescription = "$label: $multiplier" },
                        ) {
                            Text(multiplier, maxLines = 1)
                        }
                    }
                    RuntimeToolbarShortcut.Ime -> RuntimeToolbarAction(
                        icon = R.drawable.ic_action_keyboard,
                        label = R.string.action_keyboard_ime,
                        size = actionSize,
                        onClick = actions::onOpenImeKeyboard,
                    )
                    RuntimeToolbarShortcut.Screenshot -> RuntimeToolbarAction(
                        icon = R.drawable.ic_action_screenshot,
                        label = R.string.take_screenshot,
                        size = actionSize,
                        onClick = actions::onTakeScreenshot,
                    )
                }
            }
            RuntimeToolbarAction(
                icon = R.drawable.ic_more_vert,
                label = R.string.more,
                size = actionSize,
                onClick = onOpenMenu,
            )
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
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
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
    entries: List<RuntimeMenuEntry>,
    virtualKeyboardPage: Boolean,
    controllerFocusIndex: Int,
    onDismiss: () -> Unit,
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
            if (virtualKeyboardPage) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = onCloseVirtualKeyboardPage,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                    Text(
                        text = stringResource(R.string.runtime_virtual_controls_title),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = state.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            val listState = rememberLazyListState()
            val maxListHeight = runtimeMenuDialogContentHeight()
            val canScrollForward = rememberLazyListCanScrollForward(listState)
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight),
                ) {
                    entries.forEachIndexed { index, entry ->
                        item(key = entry.label) {
                            if (entry.checked != null) {
                                RuntimeToggleItem(
                                    entry.label, entry.checked, entry.icon,
                                    controllerFocusIndex == index, entry.activate,
                                )
                            } else {
                                RuntimeMenuItem(
                                    entry.label, entry.icon,
                                    controllerFocusIndex == index, entry.activate,
                                )
                            }
                        }
                    }
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = null,
    )
}

internal data class RuntimeMenuEntry(
    val label: Int,
    val icon: Int,
    val checked: Boolean? = null,
    val activate: () -> Unit,
)

/** One ordered action model drives both the visible menu and controller navigation. */
internal fun runtimeMenuEntries(
    state: RuntimeMenuUiState,
    shortcuts: List<RuntimeToolbarShortcut>,
    virtualKeyboardPage: Boolean,
    actions: RuntimeMenuActions,
    onDismiss: () -> Unit,
    onOpenVirtualKeyboardPage: () -> Unit,
): List<RuntimeMenuEntry> = buildList {
    fun action(label: Int, icon: Int, checked: Boolean? = null, callback: () -> Unit) {
        add(RuntimeMenuEntry(label, icon, checked) { onDismiss(); callback() })
    }
    if (virtualKeyboardPage) {
        action(
            if (state.virtualKeyboardEditing) R.string.layout_edit_finish else R.string.runtime_virtual_controls_edit,
            if (state.virtualKeyboardEditing) R.drawable.ic_runtime_done else R.drawable.ic_edit,
            callback = if (state.virtualKeyboardEditing) actions::onFinishVirtualKeyboardLayout else actions::onEditVirtualKeyboardLayout,
        )
        action(R.string.runtime_virtual_controls_switch_layout, R.drawable.ic_control_layout, callback = actions::onSwitchVirtualKeyboardLayout)
        action(R.string.runtime_virtual_controls_show_controls, R.drawable.ic_visibility, callback = actions::onShowControls)
        return@buildList
    }
    action(R.string.exit, R.drawable.ic_exit, callback = actions::onExit)
    action(R.string.save_log, R.drawable.ic_save, callback = actions::onSaveLog)
    action(R.string.action_lock_orientation, R.drawable.ic_screen_lock_rotation, state.orientationLocked, actions::onToggleOrientationLock)
    action(R.string.memory_editor_bubble, R.drawable.ic_runtime_memory, state.memoryEditorBubbleEnabled, actions::onMemoryEditor)
    if (state.isCanvas) {
        action(R.string.pref_runtime_fullscreen_title, R.drawable.ic_action_screenshot, !state.toolbarVisible, actions::onToggleFullscreen)
        if (state.imeAvailable && RuntimeToolbarShortcut.Ime !in shortcuts) {
            action(R.string.action_keyboard_ime, R.drawable.ic_action_keyboard, callback = actions::onOpenImeKeyboard)
        }
        if (RuntimeToolbarShortcut.Screenshot !in shortcuts) {
            action(R.string.take_screenshot, R.drawable.ic_action_screenshot, callback = actions::onTakeScreenshot)
        }
        action(R.string.PREF_LIMIT_FPS, R.drawable.ic_runtime_fps, callback = actions::onLimitFps)
        if (state.emulationSpeedAvailable && RuntimeToolbarShortcut.Speed !in shortcuts) {
            action(R.string.PREF_EMULATION_SPEED, R.drawable.ic_speed, callback = actions::onEmulationSpeed)
        }
        if (state.virtualKeyboardAvailable) {
            add(RuntimeMenuEntry(R.string.runtime_virtual_controls_title, R.drawable.ic_virtual_controls,
                activate = onOpenVirtualKeyboardPage))
        }
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
                style = MaterialTheme.typography.bodyLarge,
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
            Switch(checked = checked, onCheckedChange = null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focused) Modifier.testTag("runtime-controller-focus-indicator") else Modifier,
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
                style = MaterialTheme.typography.bodyLarge,
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
                if (focused) Modifier.testTag("runtime-controller-focus-indicator") else Modifier,
            )
            .clickable(role = Role.Button, onClick = onClick),
    )
}
