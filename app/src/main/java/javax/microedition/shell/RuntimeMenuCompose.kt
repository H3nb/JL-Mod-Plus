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

import android.content.res.Resources
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.ceil
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
    val cameraSwitchAvailable: Boolean = false,
    val cameraFrontFacing: Boolean = false,
    val cameraSwitching: Boolean = false,
    val toolbarWidthDp: Float? = null,
    val toolbarFontScale: Float = 1f,
    val toolbarSpeedWidthDp: Float = 48f,
    val toolbarRoundedLeftPx: Int = 0,
    val toolbarRoundedRightPx: Int = 0,
    val toolbarInsetLeftPx: Int = 0,
    val toolbarInsetTopPx: Int = 0,
    val toolbarInsetRightPx: Int = 0,
    val toolbarInsetBottomPx: Int = 0,
    val toolbarDensity: Float = 1f,
    val toolbarRtl: Boolean = false,
    val toolbarCutouts: List<RuntimeToolbarOcclusion> = emptyList(),
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
    fun onSwitchCamera() {}
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
    private val composeView: ComposeView,
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
    private var filePermissionPrompt by mutableStateOf<FilePermissionPrompt?>(null)
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
        updateState(state)
        composeView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateState(state)
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
                    filePermissionPrompt?.let { prompt ->
                        ControllerDialogInputScope(
                            onControllerKeyEvent = dialogKeyDispatcher,
                            onControllerMotionEvent = dialogMotionDispatcher,
                            onControllerHostCommandHandlerChanged = { dialogHostCommandHandler = it },
                        ) {
                            FilePermissionDialog(write = prompt.write, onChoice = { choice ->
                                changeControllerSurface { filePermissionPrompt = null }
                                prompt.onChoice.accept(choice)
                            })
                        }
                    }
                    if (hostDialogActions != null && filePermissionPrompt == null) {
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

    /** Camera actions are host-owned and available on both Canvas and Form. */
    fun updateCameraState(available: Boolean, frontFacing: Boolean, switching: Boolean) {
        updateState(state.copy(
            cameraSwitchAvailable = available,
            cameraFrontFacing = frontFacing,
            cameraSwitching = switching,
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

    /** Rounded corners constrain visible content; button padding may occupy the curved margin. */
    fun updateToolbarRoundedInsets(leftPx: Int, rightPx: Int) {
        updateState(state.copy(
            toolbarRoundedLeftPx = leftPx.coerceAtLeast(0),
            toolbarRoundedRightPx = rightPx.coerceAtLeast(0),
        ))
    }

    /** Physical, toolbar-local [left, right] pairs intersecting the content row. */
    fun updateToolbarCutouts(horizontalBoundsPx: IntArray) {
        require(horizontalBoundsPx.size % 2 == 0)
        val cutouts = horizontalBoundsPx.asList().chunked(2).mapNotNull { (left, right) ->
            if (right > left) RuntimeToolbarOcclusion(left, right) else null
        }
        updateState(state.copy(toolbarCutouts = cutouts))
    }

    private fun updateState(requested: RuntimeMenuUiState) {
        val resources = composeView.resources
        val density = resources.displayMetrics.density
        // Insets are also refreshed on configuration changes where native bounds stay identical.
        val next = requested.copy(
            toolbarWidthDp = composeView.width / density,
            toolbarDensity = density,
            toolbarFontScale = resources.configuration.fontScale,
            toolbarSpeedWidthDp = measureRuntimeSpeedWidth(resources, requested.emulationSpeedPercent),
            toolbarRtl = resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL,
        )
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
            || filePermissionPrompt != null

    fun closeMenu() {
        if (!isMenuVisible()) return
        val cancelledPermission = filePermissionPrompt
        val continueVirtualKeyboardEdit =
            hostDialogState is RuntimeHostDialogState.FinishVirtualKeyboardEdit
        changeControllerSurface {
            menuVisible = false
            limitFpsVisible = false
            emulationSpeedVisible = false
            hostDialogState = null
            filePermissionPrompt = null
            virtualKeyboardPage = false
            controllerFocusIndex = 0
            controllerFocusVisible = false
        }
        cancelledPermission?.onChoice?.accept(MidletPermissionManager.CANCEL)
        if (continueVirtualKeyboardEdit) {
            hostDialogActions?.onVirtualKeyboardEditContinued()
        }
    }

    /** Routes controller focus while the runtime menu or one of its dialogs owns the input. */
    fun handleHostCommand(command: HostCommand, pressed: Boolean): Boolean {
        if (!isMenuVisible()) return false
        if (filePermissionPrompt != null || hostDialogState != null || limitFpsVisible || emulationSpeedVisible) {
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

    /** Called only on the host thread. The returned handle dismisses only this request. */
    fun showFilePermissionPrompt(write: Boolean, onChoice: MidletPermissionManager.Choice): Runnable? {
        // Do not replace an unrelated host decision with a permission prompt.
        if (isMenuVisible()) return null
        val prompt = FilePermissionPrompt(write, onChoice)
        changeControllerSurface { filePermissionPrompt = prompt }
        return Runnable {
            if (filePermissionPrompt === prompt) {
                changeControllerSurface { filePermissionPrompt = null }
            }
        }
    }

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
            toolbarSpeedWidthDp = measureRuntimeSpeedWidth(LocalContext.current.resources, state.emulationSpeedPercent),
            toolbarRtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
        ) else state
        val toolbarLayout = runtimeToolbarLayout(layoutState, layoutState.toolbarWidthDp ?: 0f)
        val shortcuts = toolbarLayout.actions.map { it.shortcut }
        if (state.toolbarVisible) {
            RuntimeToolbar(state = layoutState, toolbarLayout = toolbarLayout, actions = actions)
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

private const val RUNTIME_SPEED_LABEL_SP = 14f

/** Matches the explicit labelLarge metrics below; width is shared by rendering and navigation. */
internal fun measureRuntimeSpeedWidth(resources: Resources, percent: Int): Float {
    val paint = TextPaint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            RUNTIME_SPEED_LABEL_SP, resources.displayMetrics)
    }
    val textWidthDp = ceil(paint.measureText(EmulationSpeed.formatMultiplier(percent)).toDouble()).toFloat() /
        resources.displayMetrics.density
    return max(48f, textWidthDp + 16f)
}

internal enum class RuntimeToolbarShortcut { Speed, Ime, Screenshot }
internal data class RuntimeToolbarOcclusion(val leftPx: Int, val rightPx: Int)
internal data class RuntimeToolbarSpan(val left: Float, val right: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
}
internal data class RuntimeToolbarPlacement(val shortcut: RuntimeToolbarShortcut, val span: RuntimeToolbarSpan)
internal data class RuntimeToolbarLayout(
    val title: RuntimeToolbarSpan?,
    val actions: List<RuntimeToolbarPlacement>,
)

internal fun runtimeToolbarShortcuts(state: RuntimeMenuUiState, widthDp: Float): List<RuntimeToolbarShortcut> =
    runtimeToolbarLayout(state, widthDp).actions.map { it.shortcut }

/** Fill the toolbar background while packing whole touch targets around physical occlusions. */
internal fun runtimeToolbarLayout(state: RuntimeMenuUiState, widthDp: Float): RuntimeToolbarLayout {
    if (!state.toolbarVisible || widthDp <= 0f) return RuntimeToolbarLayout(null, emptyList())
    val strictLeft = state.toolbarInsetLeftPx / state.toolbarDensity
    val strictRight = state.toolbarInsetRightPx / state.toolbarDensity
    val roundedLeft = state.toolbarRoundedLeftPx / state.toolbarDensity
    val roundedRight = state.toolbarRoundedRightPx / state.toolbarDensity
    val physicalLeft = maxOf(4f, strictLeft, roundedLeft - 8f)
    val physicalRight = widthDp - maxOf(4f, strictRight, roundedRight - 8f)
    fun logical(span: RuntimeToolbarSpan) = if (state.toolbarRtl) {
        RuntimeToolbarSpan(widthDp - span.right, widthDp - span.left)
    } else span
    val bounds = logical(RuntimeToolbarSpan(physicalLeft, physicalRight))
    if (bounds.width <= 0f) return RuntimeToolbarLayout(null, emptyList())
    val cutouts = state.toolbarCutouts.map {
        logical(RuntimeToolbarSpan(it.leftPx / state.toolbarDensity - 4f, it.rightPx / state.toolbarDensity + 4f))
    }.sortedBy { it.left }
    val segments = buildList {
        var cursor = bounds.left
        cutouts.forEach { cutout ->
            if (cutout.right > cursor && cutout.left < bounds.right) {
                if (cutout.left > cursor) add(RuntimeToolbarSpan(cursor, cutout.left.coerceAtMost(bounds.right)))
                cursor = max(cursor, cutout.right).coerceAtMost(bounds.right)
            }
        }
        if (cursor < bounds.right) add(RuntimeToolbarSpan(cursor, bounds.right))
    }
    if (segments.isEmpty()) return RuntimeToolbarLayout(null, emptyList())
    fun actionWidth(shortcut: RuntimeToolbarShortcut): Float = when (shortcut) {
        RuntimeToolbarShortcut.Speed -> state.toolbarSpeedWidthDp.coerceAtLeast(48f)
        else -> 48f
    }
    fun pack(shortcuts: List<RuntimeToolbarShortcut>): List<RuntimeToolbarPlacement>? {
        val ends = segments.map { it.right }.toMutableList()
        val placements = arrayOfNulls<RuntimeToolbarPlacement>(shortcuts.size)
        // At most three actions: try another segment before dropping an otherwise reachable action.
        fun place(actionIndex: Int): Boolean {
            if (actionIndex < 0) return true
            val shortcut = shortcuts[actionIndex]
            val width = actionWidth(shortcut)
            for (index in segments.indices.reversed()) {
                if (ends[index] - segments[index].left < width) continue
                val right = ends[index]
                ends[index] = right - width
                placements[actionIndex] = RuntimeToolbarPlacement(shortcut, RuntimeToolbarSpan(right - width, right))
                if (place(actionIndex - 1)) return true
                ends[index] = right
            }
            return false
        }
        return if (place(shortcuts.lastIndex)) placements.filterNotNull() else null
    }
    val selected = mutableListOf<RuntimeToolbarShortcut>()
    if (state.isCanvas) {
        val candidates = buildList {
            if (state.emulationSpeedAvailable) add(RuntimeToolbarShortcut.Speed)
            if (state.imeAvailable) add(RuntimeToolbarShortcut.Ime)
            add(RuntimeToolbarShortcut.Screenshot)
        }
        candidates.forEach { candidate ->
            if (pack(selected + candidate) != null) selected += candidate
        }
    }
    val placements = pack(selected).orEmpty()
    val titleBounds = logical(RuntimeToolbarSpan(
        maxOf(8f, strictLeft, roundedLeft),
        widthDp - maxOf(8f, strictRight, roundedRight),
    ))
    val titleSegment = segments.map { segment ->
        val firstAction = placements.map { it.span.left }
            .filter { it >= segment.left && it < segment.right }.minOrNull() ?: segment.right
        RuntimeToolbarSpan(max(segment.left, titleBounds.left), minOf(firstAction, titleBounds.right))
    }.firstOrNull { it.width > 0f }
    return RuntimeToolbarLayout(
        title = titleSegment?.let(::logical),
        actions = placements.map { it.copy(span = logical(it.span)) },
    )
}

@Composable
private fun RuntimeToolbar(
    state: RuntimeMenuUiState,
    toolbarLayout: RuntimeToolbarLayout,
    actions: RuntimeMenuActions,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Layout(
                modifier = Modifier.fillMaxSize().clipToBounds(),
                content = {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    toolbarLayout.actions.forEach { placement ->
                        when (placement.shortcut) {
                            RuntimeToolbarShortcut.Speed -> {
                                val label = stringResource(R.string.PREF_EMULATION_SPEED)
                                val multiplier = EmulationSpeed.formatMultiplier(state.emulationSpeedPercent)
                                TextButton(
                                    onClick = actions::onEmulationSpeed,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.fillMaxSize()
                                        .semantics { contentDescription = "$label: $multiplier" },
                                ) {
                                    Text(
                                        multiplier,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontSize = RUNTIME_SPEED_LABEL_SP.sp,
                                            fontFamily = FontFamily.SansSerif,
                                            fontWeight = FontWeight.Medium,
                                            letterSpacing = 0.sp,
                                        ),
                                    )
                                }
                            }
                            RuntimeToolbarShortcut.Ime -> RuntimeToolbarAction(
                                icon = R.drawable.ic_action_keyboard,
                                label = R.string.action_keyboard_ime,
                                size = 48.dp,
                                onClick = actions::onOpenImeKeyboard,
                            )
                            RuntimeToolbarShortcut.Screenshot -> RuntimeToolbarAction(
                                icon = R.drawable.ic_action_screenshot,
                                label = R.string.take_screenshot,
                                size = 48.dp,
                                onClick = actions::onTakeScreenshot,
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val top = state.toolbarInsetTopPx.coerceAtMost(height)
                val contentHeight = (height - top - state.toolbarInsetBottomPx).coerceAtLeast(0)
                val titleSpan = toolbarLayout.title
                val titleWidth = titleSpan?.width?.dp?.roundToPx() ?: 0
                val title = measurables.first().measure(Constraints(
                    minWidth = titleWidth,
                    maxWidth = titleWidth,
                    maxHeight = contentHeight,
                ))
                val buttons = measurables.drop(1).mapIndexed { index, measurable ->
                    measurable.measure(Constraints.fixed(
                        toolbarLayout.actions[index].span.width.dp.roundToPx(),
                        minOf(contentHeight, 48.dp.roundToPx()),
                    ))
                }
                layout(width, height) {
                    if (titleSpan != null) title.place(
                        titleSpan.left.dp.roundToPx(), top + (contentHeight - title.height) / 2,
                    )
                    buttons.forEachIndexed { index, button ->
                        button.place(
                            toolbarLayout.actions[index].span.left.dp.roundToPx(),
                            top + (contentHeight - button.height).coerceAtLeast(0) / 2,
                        )
                    }
                }
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
                                    controllerFocusIndex == index, entry.enabled, entry.activate,
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
    val enabled: Boolean = true,
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
    fun action(
        label: Int, icon: Int, checked: Boolean? = null,
        enabled: Boolean = true, callback: () -> Unit,
    ) {
        add(RuntimeMenuEntry(label, icon, checked, enabled) {
            if (enabled) { onDismiss(); callback() }
        })
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
    action(R.string.action_lock_orientation, R.drawable.ic_screen_lock_rotation, state.orientationLocked, callback = actions::onToggleOrientationLock)
    action(R.string.memory_editor_bubble, R.drawable.ic_runtime_memory, state.memoryEditorBubbleEnabled, callback = actions::onMemoryEditor)
    if (state.cameraSwitchAvailable) {
        action(
            if (state.cameraFrontFacing) R.string.runtime_camera_use_rear else R.string.runtime_camera_use_front,
            R.drawable.ic_camera_switch,
            enabled = !state.cameraSwitching,
            callback = actions::onSwitchCamera,
        )
    }
    if (state.isCanvas) {
        action(R.string.pref_runtime_fullscreen_title, R.drawable.ic_runtime_fullscreen, !state.toolbarVisible, callback = actions::onToggleFullscreen)
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
    enabled: Boolean = true,
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            )
        },
        leadingContent = leadingIcon?.let { icon ->
            {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focused) Modifier.testTag("runtime-controller-focus-indicator") else Modifier,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}
