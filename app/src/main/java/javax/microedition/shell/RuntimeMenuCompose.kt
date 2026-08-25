/*
 * Modified in 2026 for the runtime Memory Editor host dialog.
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.ui.ScrollableContentHint
import ru.playsoftware.j2meloader.ui.availableWindowHeightDp
import ru.playsoftware.j2meloader.ui.availableWindowWidthDp
import ru.playsoftware.j2meloader.ui.rememberLazyListCanScrollForward
import javax.microedition.shell.timing.EmulationSpeed
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val memoryEditorAvailable: Boolean = false,
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
    fun onResetEmulationSpeed()
    fun onMemoryEditor() {}
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
    private var memoryEditorSession by mutableStateOf<MemoryEditorSession?>(null)
    private var hostDialogState by mutableStateOf<RuntimeHostDialogState?>(null)
    private val menuActions = object : RuntimeMenuActions by actions {
        override fun onLimitFps() {
            closeMenu()
            limitFpsVisible = true
        }

        override fun onEmulationSpeed() {
            closeMenu()
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
                    actions = menuActions,
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
                if (emulationSpeedVisible) {
                    RuntimeEmulationSpeedDialog(
                        currentPercent = state.emulationSpeedPercent,
                        onDismiss = { emulationSpeedVisible = false },
                        onConfirm = { value ->
                            emulationSpeedVisible = false
                            actions.onSetEmulationSpeed(value)
                        },
                        onReset = {
                            emulationSpeedVisible = false
                            actions.onResetEmulationSpeed()
                        },
                    )
                }
                memoryEditorSession?.let { session ->
                    MemoryEditorDialog(
                        session = session,
                        onDismiss = { memoryEditorSession = null },
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
        memoryEditorAvailable: Boolean,
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
            memoryEditorAvailable = memoryEditorAvailable,
        )
    }

    fun openMenu() {
        menuVisible = true
    }

    /** Allows the Activity's legacy Back/key paths to dismiss an already-open host menu. */
    fun isMenuVisible(): Boolean = menuVisible
            || limitFpsVisible
            || emulationSpeedVisible
            || memoryEditorSession != null
            || hostDialogState != null

    fun closeMenu() {
        menuVisible = false
        limitFpsVisible = false
        emulationSpeedVisible = false
        memoryEditorSession = null
        hostDialogState = null
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

    fun showMemoryEditor(session: MemoryEditorSession) {
        closeMenu()
        memoryEditorSession = session
    }
}

private data class RuntimeMenuDialogLayout(
    val modifier: Modifier,
    val properties: DialogProperties,
)

private enum class MemoryRefineMode(val labelRes: Int) {
    CHANGED(R.string.memory_editor_mode_changed),
    UNCHANGED(R.string.memory_editor_mode_unchanged),
    INCREASED(R.string.memory_editor_mode_increased),
    DECREASED(R.string.memory_editor_mode_decreased),
    EXACT(R.string.memory_editor_mode_exact),
    ;

    fun query(value: String): MemoryEditorSession.Query? = when (this) {
        CHANGED -> MemoryEditorSession.Query.changed()
        UNCHANGED -> MemoryEditorSession.Query.unchanged()
        INCREASED -> MemoryEditorSession.Query.increased()
        DECREASED -> MemoryEditorSession.Query.decreased()
        EXACT -> value.trim().takeIf(String::isNotEmpty)?.let(MemoryEditorSession.Query::exact)
    }
}

private fun memoryRefineMode(query: MemoryEditorSession.Query?): MemoryRefineMode = when (query?.getMode()) {
    MemoryEditorSession.SearchMode.UNCHANGED -> MemoryRefineMode.UNCHANGED
    MemoryEditorSession.SearchMode.INCREASED -> MemoryRefineMode.INCREASED
    MemoryEditorSession.SearchMode.DECREASED -> MemoryRefineMode.DECREASED
    MemoryEditorSession.SearchMode.EXACT -> MemoryRefineMode.EXACT
    else -> MemoryRefineMode.CHANGED
}

@Composable
private fun runtimeMenuDialogLayout(): RuntimeMenuDialogLayout {
    val wide = availableWindowWidthDp() >= 600.dp
    return RuntimeMenuDialogLayout(
        modifier = if (wide) {
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp)
        } else {
            Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !wide),
    )
}

@Composable
private fun runtimeMenuDialogContentHeight(maxHeight: Int = 420) =
    (availableWindowHeightDp() - 220.dp)
        .coerceAtLeast(120.dp)
        .coerceAtMost(maxHeight.dp)

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
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val presetValues = remember(currentPercent) {
        (EmulationSpeed.presets().toList() + currentPercent).distinct().sorted()
    }
    val currentIndex = presetValues.indexOf(currentPercent).coerceAtLeast(0)
    var draftIndex by remember(currentIndex, presetValues) { mutableFloatStateOf(currentIndex.toFloat()) }
    val selectedIndex = draftIndex.roundToInt().coerceIn(presetValues.indices)
    val selectedValue = presetValues[selectedIndex]
    val layout = runtimeMenuDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.PREF_EMULATION_SPEED)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedValue) }) {
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
    onDismissMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
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
                onDismiss = onDismissMenu,
            )
        }
    }
}

@Composable
private fun RuntimeToolbar(
    state: RuntimeMenuUiState,
    actions: RuntimeMenuActions,
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
    val layout = runtimeMenuDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
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
                        actions = actions,
                        onOpenVirtualKeyboardPage = { virtualKeyboardPage = true },
                        onCloseVirtualKeyboardPage = { virtualKeyboardPage = false },
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
        confirmButton = {},
    )
}

private fun LazyListScope.runtimeMenuItems(
    state: RuntimeMenuUiState,
    includeCanvasShortcuts: Boolean,
    virtualKeyboardPage: Boolean,
    actions: RuntimeMenuActions,
    onOpenVirtualKeyboardPage: () -> Unit,
    onCloseVirtualKeyboardPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (virtualKeyboardPage) {
        item {
            RuntimeMenuItem(
                label = R.string.action_back,
                leadingIcon = R.drawable.ic_arrow_back,
                onClick = onCloseVirtualKeyboardPage,
            )
        }
        item {
            RuntimeActionItem(R.string.layout_edit_mode, onDismiss, actions::onEditVirtualKeyboardLayout, leadingIcon = R.drawable.ic_edit)
        }
        item {
            RuntimeActionItem(R.string.layout_scale_mode, onDismiss, actions::onResizeVirtualKeyboardLayout)
        }
        if (state.virtualKeyboardEditing) {
            item {
                RuntimeActionItem(
                    R.string.layout_edit_finish,
                    onDismiss,
                    actions::onFinishVirtualKeyboardLayout,
                )
            }
        }
        item {
            RuntimeActionItem(R.string.layout_switch, onDismiss, actions::onSwitchVirtualKeyboardLayout, leadingIcon = R.drawable.ic_restart_alt)
        }
        item {
            RuntimeActionItem(R.string.hide_buttons, onDismiss, actions::onHideVirtualKeyboardButtons)
        }
        return
    }

    item {
        RuntimeActionItem(R.string.exit, onDismiss, actions::onExit, leadingIcon = R.drawable.ic_logout)
    }
    item {
        RuntimeActionItem(R.string.save_log, onDismiss, actions::onSaveLog, leadingIcon = R.drawable.ic_save)
    }
    if (state.memoryEditorAvailable) {
        item {
            RuntimeActionItem(
                R.string.memory_editor,
                onDismiss,
                actions::onMemoryEditor,
                leadingIcon = R.drawable.ic_search,
            )
        }
    }
    item {
        RuntimeToggleItem(
            label = R.string.action_lock_orientation,
            checked = state.orientationLocked,
            leadingIcon = R.drawable.ic_screen_lock_rotation,
            onClick = {
                onDismiss()
                actions.onToggleOrientationLock()
            },
        )
    }
    if (state.isCanvas) {
        if (includeCanvasShortcuts && state.imeAvailable) {
            item {
                RuntimeMenuItem(
                    label = R.string.action_keyboard_ime,
                    leadingIcon = R.drawable.ic_action_keyboard,
                    onClick = {
                        onDismiss()
                        actions.onOpenImeKeyboard()
                    },
                )
            }
        }
        if (includeCanvasShortcuts) {
            item {
                RuntimeMenuItem(
                    label = R.string.take_screenshot,
                    leadingIcon = R.drawable.ic_action_screenshot,
                    onClick = {
                        onDismiss()
                        actions.onTakeScreenshot()
                    },
                )
            }
        }
        item {
            RuntimeActionItem(R.string.PREF_LIMIT_FPS, onDismiss, actions::onLimitFps, leadingIcon = R.drawable.ic_speed)
        }
        if (state.emulationSpeedAvailable) {
            item {
                RuntimeActionItem(
                    R.string.PREF_EMULATION_SPEED,
                    onDismiss,
                    actions::onEmulationSpeed,
                    leadingIcon = R.drawable.ic_speed,
                )
            }
        }
        if (state.virtualKeyboardAvailable) {
            item {
                RuntimeMenuItem(
                    label = R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
                    leadingIcon = R.drawable.ic_action_keyboard,
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
) {
    RuntimeMenuItem(label = label, leadingIcon = leadingIcon) {
        onDismiss()
        action()
    }
}

@Composable
private fun RuntimeToggleItem(
    label: Int,
    checked: Boolean,
    leadingIcon: Int? = null,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
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
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
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
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
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
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
    )
}

@Composable
private fun MemoryEditorDialog(
    session: MemoryEditorSession,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val savedQuery = remember(session) { session.getLastQuery() }
    val savedResult = remember(session) { session.getLastScanResult() }
    var queryText by remember(session) { mutableStateOf(savedQuery?.getFirst().orEmpty()) }
    var refineMode by remember(session) { mutableStateOf(memoryRefineMode(savedQuery)) }
    var refineMenuExpanded by remember { mutableStateOf(false) }
    var result by remember(session) { mutableStateOf(savedResult) }
    var status by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var visibleLimit by remember { mutableStateOf(300) }
    var adaptiveEvidence by remember {
        mutableStateOf(session.evidencePolicy == MemoryEditorSession.EvidencePolicy.ADAPTIVE)
    }
    val drafts = remember { mutableStateMapOf<Long, String>() }
    val regionIndexes = remember { mutableStateMapOf<Long, String>() }
    val regionDrafts = remember { mutableStateMapOf<Long, String>() }
    val layout = runtimeMenuDialogLayout()
    val snapshotReady = stringResource(R.string.memory_editor_snapshot_ready)
    val exactEmpty = stringResource(R.string.memory_editor_exact_empty)

    fun runScan(query: MemoryEditorSession.Query, reset: Boolean) {
        if (scanning) return
        if (reset) {
            session.resetSearch()
            result = null
        }
        scanning = true
        status = null
        visibleLimit = 300
        scope.launch {
            val next = withContext(Dispatchers.Default) { session.scanNow(query) }
            result = next
            scanning = false
            status = when {
                next.isCancelled -> "CANCELLED"
                query.getMode() == MemoryEditorSession.SearchMode.ALL -> snapshotReady
                query.getMode() == MemoryEditorSession.SearchMode.EXACT && next.candidates.isEmpty() -> exactEmpty
                next.isCoverageIncomplete -> "${next.candidates.size} results; coverage incomplete (${next.scannedObjects} objects, ${next.scannedFields} fields)"
                else -> "${next.candidates.size} results (${next.scannedObjects} objects, ${next.scannedFields} fields)"
            }
        }
    }

    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.memory_editor_workflow_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_editor_query"),
                    label = { Text(stringResource(R.string.memory_editor_value)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.memory_editor_refine_mode),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        TextButton(onClick = { refineMenuExpanded = true }) {
                            Text(stringResource(refineMode.labelRes))
                        }
                        DropdownMenu(
                            expanded = refineMenuExpanded,
                            onDismissRequest = { refineMenuExpanded = false },
                        ) {
                            MemoryRefineMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(mode.labelRes)) },
                                    onClick = {
                                        refineMode = mode
                                        refineMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.memory_editor_adaptive_threads))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.memory_editor_adaptive_threads_help))
                    },
                    trailingContent = {
                        Switch(
                            checked = adaptiveEvidence,
                            onCheckedChange = {
                                adaptiveEvidence = it
                                session.evidencePolicy = if (it) {
                                    MemoryEditorSession.EvidencePolicy.ADAPTIVE
                                } else {
                                    MemoryEditorSession.EvidencePolicy.PASSIVE
                                }
                            },
                        )
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        enabled = !scanning,
                        onClick = {
                            val query = if (queryText.isBlank()) {
                                MemoryEditorSession.Query.all()
                            } else {
                                MemoryEditorSession.Query.exact(queryText)
                            }
                            runScan(query, reset = true)
                        },
                    ) {
                        Text(stringResource(R.string.memory_editor_new_search))
                    }
                    TextButton(
                        enabled = !scanning && session.hasSearchSession(),
                        onClick = {
                            val query = refineMode.query(queryText)
                            if (query == null) {
                                status = "Enter a value for Exact refine"
                            } else {
                                runScan(query, reset = false)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.memory_editor_refine))
                    }
                    if (session.hasPreviousSearchStep()) {
                        TextButton(
                            enabled = !scanning,
                            onClick = {
                                result = session.undoSearch()
                                val restoredQuery = session.getLastQuery()
                                queryText = restoredQuery?.getFirst().orEmpty()
                                refineMode = memoryRefineMode(restoredQuery)
                                status = "Previous search step restored"
                            },
                        ) {
                            Text(stringResource(R.string.memory_editor_undo))
                        }
                    }
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                status?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result == null) {
                    Text(
                        text = stringResource(R.string.memory_editor_no_session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val scan = result
                if (scan != null && scan.diagnostics.isNotEmpty()) {
                    Text(
                        text = scan.diagnostics.take(5).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val candidates = scan?.candidates ?: emptyList()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = runtimeMenuDialogContentHeight()),
                ) {
                    items(candidates.take(visibleLimit), key = { it.id }) { candidate ->
                        var editing by remember(candidate.id) { mutableStateOf(false) }
                        val draft = drafts[candidate.id] ?: candidate.value
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            headlineContent = {
                                Text(
                                    text = candidate.path,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text("${candidate.typeName}: ${candidate.value}")
                                    if (candidate.isRegion) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            OutlinedTextField(
                                                value = regionIndexes[candidate.id] ?: "0",
                                                onValueChange = { regionIndexes[candidate.id] = it.filter(Char::isDigit) },
                                                label = { Text(stringResource(R.string.memory_editor_index)) },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            )
                                            OutlinedTextField(
                                                value = regionDrafts[candidate.id]
                                                    ?: candidate.readRegionElement(0).orEmpty(),
                                                onValueChange = { regionDrafts[candidate.id] = it },
                                                label = { Text(stringResource(R.string.memory_editor_value)) },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    } else if (editing && !candidate.isReadOnly) {
                                        OutlinedTextField(
                                            value = draft,
                                            onValueChange = { drafts[candidate.id] = it },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    if (candidate.isRegion && !candidate.isStale) {
                                        TextButton(onClick = {
                                            val index = regionIndexes[candidate.id]?.toIntOrNull() ?: 0
                                            val value = regionDrafts[candidate.id]
                                                ?: try {
                                                    candidate.readRegionElement(index).orEmpty()
                                                } catch (_: RuntimeException) {
                                                    ""
                                                }
                                            scope.launch {
                                                val write = withContext(Dispatchers.Default) {
                                                    session.writeRegionElementNow(candidate.id, index, value)
                                                }
                                                status = write.message
                                            }
                                        }) {
                                            Text(stringResource(R.string.memory_editor_write))
                                        }
                                    } else if (!candidate.isReadOnly && !candidate.isStale) {
                                        TextButton(onClick = {
                                            if (!editing) {
                                                editing = true
                                            } else {
                                                val value = drafts[candidate.id] ?: candidate.value
                                                scope.launch {
                                                    val write = withContext(Dispatchers.Default) {
                                                        session.writeNow(candidate.id, value)
                                                    }
                                                    status = write.message
                                                    if (write.isSuccess) editing = false
                                                }
                                            }
                                        }) {
                                            Text(if (editing) stringResource(android.R.string.ok) else stringResource(R.string.edit))
                                        }
                                        TextButton(onClick = {
                                            val currentlyFrozen = session.isFrozen(candidate.id)
                                            if (currentlyFrozen) {
                                                session.unfreeze(candidate.id)
                                                status = "Unfrozen ${candidate.path}"
                                            } else {
                                                scope.launch {
                                                    val frozen = withContext(Dispatchers.Default) {
                                                        session.freeze(candidate.id)
                                                    }
                                                    status = if (frozen) {
                                                        session.getFreezeStatus(candidate.id)
                                                    } else {
                                                        "Freeze failed: ${session.getFreezeStatus(candidate.id)}"
                                                    }
                                                }
                                            }
                                        }) {
                                            Text(stringResource(if (session.isFrozen(candidate.id)) R.string.memory_editor_unfreeze else R.string.memory_editor_freeze))
                                        }
                                    }
                                }
                            },
                        )
                    }
                    if (candidates.size > visibleLimit) {
                        item {
                            TextButton(
                                onClick = { visibleLimit += 300 },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.memory_editor_show_more))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
