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

package javax.microedition.shell.memory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.findViewTreeLifecycleOwner
import io.github.h3nb.jlmodplus.R
import javax.microedition.shell.memory.MemoryEditorRuntime

internal data class MemoryEditorActions(
    val setKind: (MemoryEditorRuntime.SearchType) -> Unit = {},
    val setInitialMode: (MemoryEditorRuntime.SearchMode) -> Unit = {},
    val setRefineMode: (MemoryEditorRuntime.SearchMode) -> Unit = {},
    val setFirstValue: (String) -> Unit = {},
    val setSecondValue: (String) -> Unit = {},
    val startSearch: () -> Unit = {},
    val continueCollection: () -> Unit = {},
    val refine: () -> Unit = {},
    val toggleSelection: (Long) -> Unit = {},
    val clearSelection: () -> Unit = {},
    val toggleAllLoaded: () -> Unit = {},
    val editSelected: (String) -> Unit = {},
    val freezeSelected: (String) -> Unit = {},
    val unfreezeSelected: () -> Unit = {},
    val unfreezeSavedSelected: () -> Unit = {},
    val deleteSavedSelected: () -> Unit = {},
    val loadMore: () -> Unit = {},
    val undo: () -> Unit = {},
    val reset: () -> Unit = {},
    val cancelOperation: () -> Unit = {},
    val clearMessage: () -> Unit = {},
    val setPauseEnabled: (Boolean) -> Unit = {},
    val setLayoutTransparency: (Float) -> Unit = {},
    val setPeeking: (Boolean) -> Unit = {},
)

private enum class EditorTab { SEARCH, SAVED, SETTINGS }

private enum class ActiveInput { FIRST, SECOND }

private const val PEEK_LONG_PRESS_TIMEOUT_MS = 300L

@Composable
internal fun MemoryEditorScreen(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableStateOf(EditorTab.SEARCH) }
    var activeInput by remember { mutableStateOf<ActiveInput?>(null) }
    var peeking by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    val operationMessage = state.operation?.let {
        if (it.status == MemoryEditorRuntime.OperationStatus.CANCELLED) {
            stringResource(R.string.memory_editor_operation_cancelled)
        } else if (
            it.status == MemoryEditorRuntime.OperationStatus.STALE_SESSION ||
            it.status == MemoryEditorRuntime.OperationStatus.NO_SESSION
        ) {
            stringResource(R.string.memory_editor_stale_session)
        } else if (it.status == MemoryEditorRuntime.OperationStatus.BUSY) {
            stringResource(R.string.memory_editor_operation_busy)
        } else if (it.kind == OperationKind.UNDO) {
            stringResource(
                if (it.succeeded == 1) {
                    R.string.memory_editor_undo_success
                } else {
                    R.string.memory_editor_undo_empty
                },
            )
        } else {
            stringResource(
                R.string.memory_editor_operation_result,
                it.succeeded,
                it.requested,
            )
        }
    }
    LaunchedEffect(state.error, operationMessage) {
        val message = state.error ?: operationMessage
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.clearMessage()
        }
    }
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP ||
                    event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY
                ) {
                    activeInput = null
                    peeking = false
                    actions.setPeeking(false)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    val onPeekChanged: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            // A separate input Dialog is not a child of this Compose layer;
            // close it before the editor becomes transparent so it cannot
            // leave an opaque surface over the game.
            activeInput = null
        }
        peeking = enabled
        actions.setPeeking(enabled)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    alpha = if (peeking) 0f else 1f - state.layoutTransparency,
                ),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        EditorHeader(onClose = onClose)
                        HorizontalDivider()
                        if (state.busy) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.progress == null) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("memory_busy"),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        progress = {
                                            state.progress.fraction.coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("memory_busy"),
                                    )
                                }
                                if (state.progress != null) {
                                    TextButton(
                                        onClick = actions.cancelOperation,
                                        modifier = Modifier.testTag("memory_cancel_operation"),
                                    ) {
                                        Text(stringResource(android.R.string.cancel))
                                    }
                                }
                            }
                        }
                        MemoryEditorTabs(
                            selected = selectedTab,
                            onSelected = {
                                selectedTab = it
                                activeInput = null
                                actions.clearSelection()
                            },
                        )
                        when (selectedTab) {
                            EditorTab.SEARCH -> when (state.phase) {
                                MemoryEditorPhase.SETUP -> SetupContent(
                                    state = state,
                                    actions = actions,
                                    onInputRequested = { activeInput = it },
                                    modifier = Modifier.weight(1f),
                                )
                                MemoryEditorPhase.COLLECTING -> CollectingContent(
                                    onClose = onClose,
                                    modifier = Modifier.weight(1f),
                                )
                                MemoryEditorPhase.RESULTS -> ResultsContent(
                                    state = state,
                                    actions = actions,
                                    onClose = onClose,
                                    onInputRequested = { activeInput = it },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            EditorTab.SAVED -> SavedContent(
                                state = state,
                                actions = actions,
                                modifier = Modifier.weight(1f),
                            )
                            EditorTab.SETTINGS -> SettingsContent(
                                state = state,
                                actions = actions,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        PeekButton(
            onPeekChanged = onPeekChanged,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 56.dp),
        )
        activeInput?.let { input ->
            val isRange = if (state.phase == MemoryEditorPhase.RESULTS) {
                state.refineMode == MemoryEditorRuntime.SearchMode.RANGE
            } else {
                state.initialMode == MemoryEditorRuntime.SearchMode.RANGE
            }
            val label = stringResource(
                when {
                    input == ActiveInput.SECOND -> R.string.memory_editor_second_value
                    isRange -> R.string.memory_editor_minimum
                    else -> R.string.memory_editor_value
                },
            )
            NumericInputDialog(
                title = stringResource(R.string.memory_editor_input_title),
                label = label,
                initialValue = if (input == ActiveInput.FIRST) {
                    state.firstValue
                } else {
                    state.secondValue
                },
                kind = state.kind,
                confirmLabel = stringResource(R.string.memory_editor_done),
                onDismiss = { activeInput = null },
                onConfirm = { value ->
                    if (input == ActiveInput.FIRST) {
                        actions.setFirstValue(value)
                    } else {
                        actions.setSecondValue(value)
                    }
                    activeInput = null
                },
            )
        }
        if (state.preparingSearch && !peeking) {
            AlertDialog(
                onDismissRequest = {},
                modifier = Modifier.testTag("memory_preparing_search"),
                title = {
                    Text(stringResource(R.string.memory_editor_preparing_title))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.memory_editor_preparing_help))
                    }
                },
                confirmButton = {},
            )
        }
    }
}

@Composable
private fun EditorHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_editor_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                modifier = Modifier.testTag("memory_experimental_badge"),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.memory_editor_preview_badge),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.testTag("memory_close"),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_memory_editor_close_24),
                contentDescription = stringResource(R.string.memory_editor_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeekButton(
    onPeekChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val currentOnPeekChanged by rememberUpdatedState(onPeekChanged)
    val description = stringResource(R.string.memory_editor_peek_content_description)
    val baseViewConfiguration = LocalViewConfiguration.current
    val peekViewConfiguration = remember(baseViewConfiguration) {
        object : ViewConfiguration {
            override val longPressTimeoutMillis: Long = PEEK_LONG_PRESS_TIMEOUT_MS
            override val doubleTapTimeoutMillis: Long = baseViewConfiguration.doubleTapTimeoutMillis
            override val doubleTapMinTimeMillis: Long = baseViewConfiguration.doubleTapMinTimeMillis
            override val touchSlop: Float = baseViewConfiguration.touchSlop
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides peekViewConfiguration) {
        Box(
            modifier = modifier
                .width(48.dp)
                .height(48.dp)
                .testTag("memory_peek")
                .semantics {
                    contentDescription = description
                }
                .pointerInput(Unit) {
                    var peekActive = false
                    detectTapGestures(
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            peekActive = true
                            currentOnPeekChanged(true)
                        },
                        onPress = {
                            try {
                                tryAwaitRelease()
                            } finally {
                                if (peekActive) {
                                    currentOnPeekChanged(false)
                                    peekActive = false
                                }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .width(32.dp)
                    .height(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                tonalElevation = 1.dp,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_memory_editor_peek_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(7.dp)
                        .fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MemoryEditorTabs(
    selected: EditorTab,
    onSelected: (EditorTab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        EditorTab.values().forEach { tab ->
            Tab(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                text = {
                    Text(
                        stringResource(
                            when (tab) {
                                EditorTab.SEARCH -> R.string.memory_editor_tab_search
                                EditorTab.SAVED -> R.string.memory_editor_tab_saved
                                EditorTab.SETTINGS -> R.string.memory_editor_tab_settings
                            },
                        ),
                    )
                },
                modifier = Modifier.testTag("memory_tab_${tab.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun NumericKeypad(
    value: String,
    kind: MemoryEditorRuntime.SearchType,
    cursorPosition: Int,
    onValueChanged: (String) -> Unit,
    onCursorChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val supportsFraction = kind == MemoryEditorRuntime.SearchType.AUTO ||
        kind == MemoryEditorRuntime.SearchType.FLOAT ||
        kind == MemoryEditorRuntime.SearchType.DOUBLE
    val booleanInput = kind == MemoryEditorRuntime.SearchType.BOOLEAN
    val cursor = cursorPosition.coerceIn(0, value.length)
    val insert: (String) -> Unit = { text ->
        if (value.length + text.length <= MAX_INPUT_LENGTH) {
            onValueChanged(value.substring(0, cursor) + text + value.substring(cursor))
            onCursorChanged(cursor + text.length)
        }
    }
    val rows = if (booleanInput) {
        listOf(
            listOf("TRUE", "FALSE", "", "", ""),
            listOf("CLR", "", "", "", ""),
        )
    } else {
        listOf(
            listOf("7", "8", "9", "←", "⌫"),
            listOf("4", "5", "6", "→", "CLR"),
            listOf("1", "2", "3", "-", if (supportsFraction) "." else ""),
            listOf("0", if (supportsFraction) "E" else "", "", "", ""),
        )
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 230.dp)
            .testTag("memory_numeric_keypad"),
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { key ->
                        val enabled = key.isNotEmpty() &&
                            (key != "." || supportsFraction)
                        OutlinedButton(
                            onClick = {
                                when (key) {
                                    "←" -> onCursorChanged((cursor - 1).coerceAtLeast(0))
                                    "→" -> onCursorChanged((cursor + 1).coerceAtMost(value.length))
                                    "⌫" -> if (cursor > 0) {
                                        onValueChanged(
                                            value.removeRange(cursor - 1, cursor),
                                        )
                                        onCursorChanged(cursor - 1)
                                    }
                                    "CLR" -> {
                                        onValueChanged("")
                                        onCursorChanged(0)
                                    }
                                    "-" -> {
                                        if (cursor > 0 && value[cursor - 1] == 'E') {
                                            insert("-")
                                        } else {
                                            val next = if (value.startsWith("-")) {
                                                value.drop(1)
                                            } else {
                                                "-$value"
                                            }
                                            onValueChanged(next)
                                            onCursorChanged(
                                                if (value.startsWith("-")) {
                                                    (cursor - 1).coerceAtLeast(0)
                                                } else {
                                                    cursor + 1
                                                },
                                            )
                                        }
                                    }
                                    "." -> {
                                        if ("." !in value) {
                                            insert(key)
                                        }
                                    }
                                    "E" -> {
                                        if ("E" !in value) {
                                            insert(key)
                                        }
                                    }
                                    "TRUE", "FALSE" -> {
                                        val next = key.lowercase()
                                        onValueChanged(next)
                                        onCursorChanged(next.length)
                                    }
                                    "" -> Unit
                                    else -> insert(key)
                                }
                            },
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics {
                                    contentDescription = when (key) {
                                        "←" -> "Kursor kiri"
                                        "→" -> "Kursor kanan"
                                        "⌫" -> "Hapus satu karakter"
                                        "CLR" -> "Hapus semua"
                                        else -> key
                                    }
                                },
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = key.ifEmpty { " " },
                                style = if (key == "CLR") {
                                    MaterialTheme.typography.labelMedium
                                } else {
                                    MaterialTheme.typography.labelLarge
                                },
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupContent(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onInputRequested: (ActiveInput) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_editor_setup_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.memory_editor_setup_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnumSelector(
            label = stringResource(R.string.memory_editor_type),
            selected = state.kind,
            options = MemoryEditorRuntime.SearchType.values().toList(),
            labelOf = ::kindLabel,
            onSelect = actions.setKind,
            testTag = "memory_type",
        )
        if (state.kind == MemoryEditorRuntime.SearchType.AUTO) {
            Text(
                text = stringResource(R.string.memory_editor_auto_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EnumSelector(
            label = stringResource(R.string.memory_editor_initial_mode),
            selected = state.initialMode,
            options = INITIAL_MODES,
            labelOf = ::modeLabel,
            onSelect = actions.setInitialMode,
            testTag = "memory_initial_mode",
        )
        SearchValueFields(
            mode = state.initialMode,
            first = state.firstValue,
            second = state.secondValue,
            onInputRequested = onInputRequested,
        )
        Button(
            onClick = actions.startSearch,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("memory_start"),
        ) {
            Text(stringResource(R.string.memory_editor_start_and_play))
        }
    }
}

@Composable
private fun CollectingContent(
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .testTag("memory_collecting_status"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.memory_editor_collecting_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.memory_editor_collecting_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.memory_editor_return_to_game))
        }
    }
}

@Composable
private fun ResultsContent(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onClose: () -> Unit,
    onInputRequested: (ActiveInput) -> Unit,
    modifier: Modifier,
) {
    var replacementAction by remember { mutableStateOf<OperationKind?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("memory_results"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "results_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.memory_editor_results_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.memory_editor_results_summary,
                            state.snapshot.candidates,
                            state.snapshot.frozen,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(onClick = onClose) {
                    Text(stringResource(R.string.memory_editor_return_to_game))
                }
            }
        }
        if (state.snapshot.limitReached) {
            item(key = "limit_warning") {
                Text(
                    text = stringResource(R.string.memory_editor_limit_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.snapshot.canContinueCollection) {
            item(key = "continue_collection") {
                OutlinedButton(
                    onClick = actions.continueCollection,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_continue_collection"),
                ) {
                    Text(stringResource(R.string.memory_editor_continue_collection))
                }
            }
        }
        item(key = "refine_panel") {
            RefinePanel(state, actions, onInputRequested)
        }
        item(key = "selection_toolbar") {
            SelectionToolbar(state, actions)
        }
        if (state.candidates.isEmpty()) {
            item(key = "empty_results") {
                EmptyResults(state.snapshot)
            }
        } else {
            items(state.candidates, key = { it.id }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    selected = candidate.id in state.selectedIds,
                    onToggle = { actions.toggleSelection(candidate.id) },
                )
            }
            if (state.hasMore) {
                item(key = "load_more") {
                    OutlinedButton(
                        onClick = actions.loadMore,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.memory_editor_load_more))
                    }
                }
            }
        }
        item(key = "action_toolbar") {
            ActionToolbar(
                state = state,
                onEdit = { replacementAction = OperationKind.EDIT },
                onFreeze = { replacementAction = OperationKind.FREEZE },
                onUnfreeze = actions.unfreezeSelected,
                onUndo = actions.undo,
                onReset = { showResetConfirmation = true },
            )
        }
    }
    replacementAction?.let { action ->
        ReplacementDialog(
            action = action,
            kind = state.kind,
            onDismiss = { replacementAction = null },
            onConfirm = { replacement ->
                if (action == OperationKind.EDIT) {
                    actions.editSelected(replacement)
                } else {
                    actions.freezeSelected(replacement)
                }
                replacementAction = null
            },
        )
    }
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.memory_editor_reset_confirm_title)) },
            text = { Text(stringResource(R.string.memory_editor_reset_confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    showResetConfirmation = false
                    actions.reset()
                }) {
                    Text(stringResource(R.string.memory_editor_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RefinePanel(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onInputRequested: (ActiveInput) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_editor_refine_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EnumSelector(
                label = stringResource(R.string.memory_editor_mode),
                selected = state.refineMode,
                options = REFINE_MODES,
                labelOf = ::modeLabel,
                onSelect = actions.setRefineMode,
                testTag = "memory_refine_mode",
            )
            SearchValueFields(
                mode = state.refineMode,
                first = state.firstValue,
                second = state.secondValue,
                onInputRequested = onInputRequested,
            )
            Button(
                onClick = actions.refine,
                enabled = !state.busy && state.snapshot.candidates > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.memory_editor_refine))
            }
        }
    }
}

@Composable
private fun SavedContent(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    modifier: Modifier,
) {
    val savedIds = state.savedCandidates.mapTo(mutableSetOf()) { it.id }
    val selectedSaved = state.selectedIds.intersect(savedIds)
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("memory_saved"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.memory_editor_saved_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.memory_editor_saved_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.savedCandidates.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.memory_editor_saved_empty),
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            items(state.savedCandidates, key = { "saved_${it.id}" }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    selected = candidate.id in state.selectedIds,
                    onToggle = { actions.toggleSelection(candidate.id) },
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = actions.unfreezeSavedSelected,
                        enabled = selectedSaved.any { id ->
                            state.savedCandidates.any { it.id == id && it.frozen }
                        } && !state.busy,
                    ) {
                        Text(stringResource(R.string.memory_editor_unfreeze))
                    }
                    Button(
                        onClick = actions.deleteSavedSelected,
                        enabled = selectedSaved.isNotEmpty() && !state.busy,
                        modifier = Modifier.testTag("memory_delete_saved"),
                    ) {
                        Text(stringResource(R.string.memory_editor_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_editor_settings_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.setPauseEnabled(!state.pauseEnabled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.memory_editor_pause))
                Text(
                    text = stringResource(R.string.memory_editor_pause_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.pauseEnabled,
                onCheckedChange = actions.setPauseEnabled,
                modifier = Modifier.testTag("memory_pause"),
            )
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.memory_editor_layout_transparency),
                    modifier = Modifier.weight(1f),
                )
                Text("${(state.layoutTransparency * 100).toInt()}%")
            }
            Text(
                text = stringResource(R.string.memory_editor_layout_transparency_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = state.layoutTransparency,
                onValueChange = actions.setLayoutTransparency,
                valueRange = 0f..0.8f,
                steps = 15,
                modifier = Modifier.testTag("memory_layout_transparency"),
            )
        }
        Text(
            text = stringResource(R.string.memory_editor_peek_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectionToolbar(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    val editableIds = state.candidates.filter { it.editable }.map { it.id }
    val allSelected = editableIds.isNotEmpty() && state.selectedIds.containsAll(editableIds)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.memory_editor_selected, state.selectedIds.size),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(
                R.string.memory_editor_loaded,
                state.candidates.size,
                state.snapshot.candidates,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = actions.toggleAllLoaded, enabled = editableIds.isNotEmpty()) {
            Text(
                stringResource(
                    if (allSelected) {
                        R.string.memory_editor_clear_selection
                    } else {
                        R.string.memory_editor_select_loaded
                    },
                ),
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: MemoryCandidate,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val description = "${candidate.value}, ${candidate.storageType}, ${candidate.location}"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_candidate_${candidate.id}")
            .semantics { contentDescription = description }
            .clickable(enabled = candidate.editable, onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                enabled = candidate.editable,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = candidate.value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.memory_editor_storage_type,
                        candidate.storageType,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = candidate.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (candidate.status == MemoryEditorRuntime.CandidateStatus.READ_FAILED) {
                Text(
                    text = stringResource(R.string.memory_editor_candidate_read_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (candidate.status == MemoryEditorRuntime.CandidateStatus.WRITE_FAILED) {
                Text(
                    text = stringResource(R.string.memory_editor_candidate_write_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (candidate.frozen) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.memory_editor_frozen)) },
                )
            } else if (!candidate.editable) {
                Text(
                    text = stringResource(R.string.memory_editor_read_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ActionToolbar(
    state: MemoryEditorUiState,
    onEdit: () -> Unit,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onEdit,
            enabled = state.selectedIds.isNotEmpty() && !state.busy,
            modifier = Modifier.testTag("memory_edit_selected"),
        ) {
            Text(stringResource(R.string.memory_editor_edit_selected))
        }
        Button(
            onClick = onFreeze,
            enabled = state.selectedIds.isNotEmpty() && !state.busy,
        ) {
            Text(stringResource(R.string.memory_editor_freeze_selected))
        }
        OutlinedButton(
            onClick = onUnfreeze,
            enabled = state.selectedIds.isNotEmpty() && !state.busy,
        ) {
            Text(stringResource(R.string.memory_editor_unfreeze_selected))
        }
        OutlinedButton(
            onClick = onUndo,
            enabled = state.snapshot.undoAvailable && !state.busy,
        ) {
            Text(stringResource(R.string.memory_editor_undo))
        }
        TextButton(onClick = onReset, enabled = !state.busy) {
            Text(stringResource(R.string.memory_editor_reset))
        }
    }
}

@Composable
private fun EmptyResults(snapshot: MemoryEditorSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.memory_editor_empty_results),
            style = MaterialTheme.typography.titleMedium,
        )
        DiagnosticsCard(snapshot)
    }
}

@Composable
private fun DiagnosticsCard(snapshot: MemoryEditorSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.memory_editor_observed,
                    snapshot.selectedObservations,
                    snapshot.totalObservations,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.memory_editor_observed_paths,
                    snapshot.fieldObservations,
                    snapshot.arrayObservations,
                    snapshot.readObservations,
                    snapshot.writeObservations,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.candidateByteBudget > 0) {
                Text(
                    text = stringResource(
                        R.string.memory_editor_candidate_budget,
                        snapshot.candidateBytes / 1024,
                        snapshot.candidateByteBudget / 1024,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val advice = when {
                snapshot.totalObservations == 0L -> R.string.memory_editor_no_hooks
                snapshot.selectedObservations == 0L -> R.string.memory_editor_wrong_type
                else -> null
            }
            advice?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SearchValueFields(
    mode: MemoryEditorRuntime.SearchMode,
    first: String,
    second: String,
    onInputRequested: (ActiveInput) -> Unit,
) {
    if (mode == MemoryEditorRuntime.SearchMode.EXACT ||
        mode == MemoryEditorRuntime.SearchMode.NOT_EQUAL ||
        mode == MemoryEditorRuntime.SearchMode.LESS_THAN ||
        mode == MemoryEditorRuntime.SearchMode.GREATER_THAN
    ) {
        ValueField(
            value = first,
            label = stringResource(R.string.memory_editor_value),
            testTag = "memory_value",
            onClick = { onInputRequested(ActiveInput.FIRST) },
        )
    } else if (mode == MemoryEditorRuntime.SearchMode.RANGE) {
        ValueField(
            value = first,
            label = stringResource(R.string.memory_editor_minimum),
            testTag = "memory_minimum",
            onClick = { onInputRequested(ActiveInput.FIRST) },
        )
        ValueField(
            value = second,
            label = stringResource(R.string.memory_editor_second_value),
            testTag = "memory_maximum",
            onClick = { onInputRequested(ActiveInput.SECOND) },
        )
    }
}

@Composable
private fun ValueField(
    value: String,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            singleLine = true,
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick)
                .semantics { contentDescription = label }
                .testTag(testTag),
        )
    }
}

@Composable
private fun ReplacementDialog(
    action: OperationKind,
    kind: MemoryEditorRuntime.SearchType,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    NumericInputDialog(
        title = stringResource(
            if (action == OperationKind.EDIT) {
                R.string.memory_editor_edit_selected
            } else {
                R.string.memory_editor_freeze_selected
            },
        ),
        label = stringResource(R.string.memory_editor_replacement),
        initialValue = "",
        kind = kind,
        confirmLabel = stringResource(
            if (action == OperationKind.EDIT) {
                R.string.memory_editor_apply_edit
            } else {
                R.string.memory_editor_apply_freeze
            },
        ),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun NumericInputDialog(
    title: String,
    label: String,
    initialValue: String,
    kind: MemoryEditorRuntime.SearchType,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }
    var cursorPosition by remember(initialValue) { mutableStateOf(initialValue.length) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val landscape = maxWidth > maxHeight
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (landscape) 0.92f else 0.98f)
                    .widthIn(max = 980.dp)
                    .heightIn(max = maxHeight),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 12.dp,
                shadowElevation = 18.dp,
            ) {
                if (landscape) {
                    Row(Modifier.fillMaxWidth()) {
                        InputDialogDetails(
                            title = title,
                            label = label,
                            value = draft,
                            kind = kind,
                            confirmLabel = confirmLabel,
                            onDismiss = onDismiss,
                            onConfirm = { onConfirm(draft) },
                            modifier = Modifier
                                .weight(0.9f)
                                .padding(20.dp),
                        )
                        NumericKeypad(
                            value = draft,
                            kind = kind,
                            cursorPosition = cursorPosition,
                            onValueChanged = { draft = it },
                            onCursorChanged = { cursorPosition = it },
                            modifier = Modifier
                                .weight(1.2f)
                                .heightIn(min = 320.dp),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        InputDialogDetails(
                            title = title,
                            label = label,
                            value = draft,
                            kind = kind,
                            confirmLabel = confirmLabel,
                            onDismiss = onDismiss,
                            onConfirm = { onConfirm(draft) },
                            modifier = Modifier.padding(20.dp),
                        )
                        NumericKeypad(
                            value = draft,
                            kind = kind,
                            cursorPosition = cursorPosition,
                            onValueChanged = { draft = it },
                            onCursorChanged = { cursorPosition = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputDialogDetails(
    title: String,
    label: String,
    value: String,
    kind: MemoryEditorRuntime.SearchType,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.memory_editor_input_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            singleLine = true,
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("memory_input_dialog_value"),
        )
        Text(
            text = stringResource(R.string.memory_editor_input_type, kindLabel(kind)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            Button(
                onClick = onConfirm,
                enabled = value.isNotBlank(),
                modifier = Modifier.testTag("memory_input_dialog_confirm"),
            ) {
                Text(confirmLabel)
            }
        }
    }
}

@Composable
private fun <T> EnumSelector(
    label: String,
    selected: T,
    options: List<T>,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    testTag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag),
            ) {
                Text(
                    text = labelOf(selected),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("▾")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelOf(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: MemoryEditorRuntime.SearchType): String = stringResource(
    when (kind) {
        MemoryEditorRuntime.SearchType.AUTO -> R.string.memory_editor_type_auto
        MemoryEditorRuntime.SearchType.BOOLEAN -> R.string.memory_editor_type_boolean
        MemoryEditorRuntime.SearchType.BYTE -> R.string.memory_editor_type_byte
        MemoryEditorRuntime.SearchType.CHAR -> R.string.memory_editor_type_char
        MemoryEditorRuntime.SearchType.SHORT -> R.string.memory_editor_type_short
        MemoryEditorRuntime.SearchType.INT -> R.string.memory_editor_type_int
        MemoryEditorRuntime.SearchType.LONG -> R.string.memory_editor_type_long
        MemoryEditorRuntime.SearchType.FLOAT -> R.string.memory_editor_type_float
        MemoryEditorRuntime.SearchType.DOUBLE -> R.string.memory_editor_type_double
    },
)

@Composable
private fun modeLabel(mode: MemoryEditorRuntime.SearchMode): String = stringResource(
    when (mode) {
        MemoryEditorRuntime.SearchMode.EXACT -> R.string.memory_editor_mode_exact
        MemoryEditorRuntime.SearchMode.NOT_EQUAL -> R.string.memory_editor_mode_not_equal
        MemoryEditorRuntime.SearchMode.LESS_THAN -> R.string.memory_editor_mode_less_than
        MemoryEditorRuntime.SearchMode.GREATER_THAN -> R.string.memory_editor_mode_greater_than
        MemoryEditorRuntime.SearchMode.UNKNOWN -> R.string.memory_editor_mode_unknown
        MemoryEditorRuntime.SearchMode.CHANGED -> R.string.memory_editor_mode_changed
        MemoryEditorRuntime.SearchMode.UNCHANGED -> R.string.memory_editor_mode_unchanged
        MemoryEditorRuntime.SearchMode.INCREASED -> R.string.memory_editor_mode_increased
        MemoryEditorRuntime.SearchMode.DECREASED -> R.string.memory_editor_mode_decreased
        MemoryEditorRuntime.SearchMode.RANGE -> R.string.memory_editor_mode_range
    },
)

private const val MAX_INPUT_LENGTH = 64

private val INITIAL_MODES = listOf(
    MemoryEditorRuntime.SearchMode.EXACT,
    MemoryEditorRuntime.SearchMode.NOT_EQUAL,
    MemoryEditorRuntime.SearchMode.LESS_THAN,
    MemoryEditorRuntime.SearchMode.GREATER_THAN,
    MemoryEditorRuntime.SearchMode.UNKNOWN,
    MemoryEditorRuntime.SearchMode.RANGE,
)

private val REFINE_MODES = listOf(
    MemoryEditorRuntime.SearchMode.EXACT,
    MemoryEditorRuntime.SearchMode.NOT_EQUAL,
    MemoryEditorRuntime.SearchMode.LESS_THAN,
    MemoryEditorRuntime.SearchMode.GREATER_THAN,
    MemoryEditorRuntime.SearchMode.RANGE,
    MemoryEditorRuntime.SearchMode.CHANGED,
    MemoryEditorRuntime.SearchMode.UNCHANGED,
    MemoryEditorRuntime.SearchMode.INCREASED,
    MemoryEditorRuntime.SearchMode.DECREASED,
)

@Preview(name = "Memory editor setup", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun MemoryEditorSetupPreview() {
    MemoryEditorTheme {
        MemoryEditorScreen(
            state = MemoryEditorUiState(),
            actions = MemoryEditorActions(),
            onClose = {},
        )
    }
}

@Preview(
    name = "Memory editor setup dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun MemoryEditorSetupDarkPreview() {
    MemoryEditorTheme(darkTheme = true) {
        MemoryEditorScreen(
            state = MemoryEditorUiState(),
            actions = MemoryEditorActions(),
            onClose = {},
        )
    }
}

@Preview(name = "Memory editor collecting", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun MemoryEditorCollectingPreview() {
    MemoryEditorTheme {
        MemoryEditorScreen(
            state = MemoryEditorUiState(
                phase = MemoryEditorPhase.COLLECTING,
                snapshot = MemoryEditorSnapshot(
                    kind = MemoryEditorRuntime.ValueKind.INT,
                    collecting = true,
                    intObservations = 132,
                    fieldObservations = 90,
                    arrayObservations = 42,
                    readObservations = 120,
                    writeObservations = 12,
                    candidates = 34,
                ),
            ),
            actions = MemoryEditorActions(),
            onClose = {},
        )
    }
}

@Preview(name = "Memory editor results", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun MemoryEditorResultsPreview() {
    MemoryEditorTheme {
        MemoryEditorScreen(
            state = MemoryEditorUiState(
                phase = MemoryEditorPhase.RESULTS,
                snapshot = MemoryEditorSnapshot(
                    kind = MemoryEditorRuntime.ValueKind.INT,
                    candidates = 2,
                    frozen = 1,
                    intObservations = 500,
                ),
                candidates = listOf(
                    MemoryCandidate(
                        id = 1,
                        value = "750",
                        storageType = "int",
                        location = "game.Player.coins",
                        frozen = true,
                        editable = true,
                    ),
                    MemoryCandidate(
                        id = 2,
                        value = "12",
                        storageType = "short",
                        location = "short[][4]",
                        frozen = false,
                        editable = true,
                    ),
                ),
                selectedIds = setOf(1),
            ),
            actions = MemoryEditorActions(),
            onClose = {},
        )
    }
}

@Preview(
    name = "Memory editor results dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun MemoryEditorResultsDarkPreview() {
    MemoryEditorTheme(darkTheme = true) {
        MemoryEditorScreen(
            state = MemoryEditorUiState(
                phase = MemoryEditorPhase.RESULTS,
                snapshot = MemoryEditorSnapshot(
                    kind = MemoryEditorRuntime.ValueKind.INT,
                    candidates = 2,
                    frozen = 1,
                    intObservations = 500,
                ),
                candidates = listOf(
                    MemoryCandidate(
                        id = 1,
                        value = "750",
                        storageType = "int",
                        location = "game.Player.coins",
                        frozen = true,
                        editable = true,
                    ),
                    MemoryCandidate(
                        id = 2,
                        value = "12",
                        storageType = "short",
                        location = "short[][4]",
                        frozen = false,
                        editable = true,
                    ),
                ),
                selectedIds = setOf(1),
            ),
            actions = MemoryEditorActions(),
            onClose = {},
        )
    }
}

@Preview(name = "Memory editor saved", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun MemoryEditorSavedPreview() {
    MemoryEditorTheme {
        Surface {
            SavedContent(
                state = MemoryEditorUiState(
                    snapshot = MemoryEditorSnapshot(
                        candidates = 2,
                        frozen = 1,
                        saved = 2,
                    ),
                    savedCandidates = listOf(
                        MemoryCandidate(
                            id = 10,
                            value = "750",
                            storageType = "int",
                            location = "game.Player.coins",
                            frozen = true,
                            saved = true,
                            editable = true,
                        ),
                        MemoryCandidate(
                            id = 11,
                            value = "12",
                            storageType = "short",
                            location = "short[][4]",
                            frozen = false,
                            saved = true,
                            editable = true,
                        ),
                    ),
                    selectedIds = setOf(10),
                ),
                actions = MemoryEditorActions(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            )
        }
    }
}

@Preview(name = "Memory editor settings", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun MemoryEditorSettingsPreview() {
    MemoryEditorTheme {
        Surface {
            SettingsContent(
                state = MemoryEditorUiState(
                    pauseEnabled = true,
                    layoutTransparency = 0.25f,
                ),
                actions = MemoryEditorActions(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            )
        }
    }
}

@Preview(name = "Memory editor keypad", showBackground = true, widthDp = 760, heightDp = 420)
@Composable
internal fun MemoryEditorKeypadPreview() {
    MemoryEditorTheme {
        NumericInputDialog(
            title = "Edit selected",
            label = "Replacement",
            initialValue = "750",
            kind = MemoryEditorRuntime.SearchType.INT,
            confirmLabel = "Apply edit",
            onDismiss = {},
            onConfirm = {},
        )
    }
}

@Preview(name = "Memory editor replacement", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun MemoryEditorReplacementPreview() {
    MemoryEditorTheme {
        ReplacementDialog(
            action = OperationKind.FREEZE,
            kind = MemoryEditorRuntime.SearchType.FLOAT,
            onDismiss = {},
            onConfirm = {},
        )
    }
}
