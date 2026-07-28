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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import kotlinx.coroutines.launch
import javax.microedition.shell.memory.MemoryEditorRuntime

internal data class MemoryEditorActions(
    val setKind: (MemoryEditorRuntime.ValueKind) -> Unit = {},
    val setInitialMode: (MemoryEditorRuntime.SearchMode) -> Unit = {},
    val setRefineMode: (MemoryEditorRuntime.SearchMode) -> Unit = {},
    val setFirstValue: (String) -> Unit = {},
    val setSecondValue: (String) -> Unit = {},
    val startSearch: () -> Unit = {},
    val finishCollection: () -> Unit = {},
    val refresh: () -> Unit = {},
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
    val clearMessage: () -> Unit = {},
    val setPauseEnabled: (Boolean) -> Unit = {},
    val setPeeking: (Boolean) -> Unit = {},
)

private enum class EditorTab { SEARCH, RESULTS, SAVED, SETTINGS }

private enum class ActiveInput { FIRST, SECOND }

@Composable
internal fun MemoryEditorScreen(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable {
        mutableStateOf(
            if (state.phase == MemoryEditorPhase.RESULTS) EditorTab.RESULTS
            else EditorTab.SEARCH,
        )
    }
    var activeInput by remember { mutableStateOf<ActiveInput?>(null) }
    var peeking by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val operationMessage = state.operation?.let {
        if (it.kind == OperationKind.UNDO) {
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
    LaunchedEffect(state.phase) {
        if (state.phase == MemoryEditorPhase.RESULTS) {
            selectedTab = EditorTab.RESULTS
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (activeInput != null && !peeking) {
                InputDock(
                    label = stringResource(
                        if (activeInput == ActiveInput.SECOND) {
                            R.string.memory_editor_second_value
                        } else if (
                            (state.phase == MemoryEditorPhase.SETUP &&
                                state.initialMode == MemoryEditorRuntime.SearchMode.RANGE) ||
                            (state.phase == MemoryEditorPhase.RESULTS &&
                                state.refineMode == MemoryEditorRuntime.SearchMode.RANGE)
                        ) {
                            R.string.memory_editor_minimum
                        } else {
                            R.string.memory_editor_value
                        },
                    ),
                    value = if (activeInput == ActiveInput.SECOND) {
                        state.secondValue
                    } else {
                        state.firstValue
                    },
                    onDone = { activeInput = null },
                )
            }
        },
    ) { contentPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .graphicsLayer(alpha = if (peeking) 0.12f else 1f),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                EditorHeader(
                    onClose = onClose,
                    onPeekChanged = { enabled ->
                        peeking = enabled
                        actions.setPeeking(enabled)
                    },
                )
                HorizontalDivider()
                if (state.busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("memory_busy"),
                    )
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
                            onInputFocused = { activeInput = it },
                            modifier = Modifier.weight(1f),
                        )
                        MemoryEditorPhase.COLLECTING -> CollectingContent(
                            state = state,
                            actions = actions,
                            onClose = onClose,
                            modifier = Modifier.weight(1f),
                        )
                        MemoryEditorPhase.RESULTS -> RefineContent(
                            state = state,
                            actions = actions,
                            onInputFocused = { activeInput = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    EditorTab.RESULTS -> ResultsContent(
                        state = state,
                        actions = actions,
                        onClose = onClose,
                        modifier = Modifier.weight(1f),
                    )
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

@Composable
private fun EditorHeader(
    onClose: () -> Unit,
    onPeekChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.memory_editor_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.memory_editor_preview_badge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(
            onClick = {},
            modifier = Modifier
                .testTag("memory_peek")
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onPeekChanged(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                onPeekChanged(false)
                            }
                        },
                    )
                },
        ) {
            Text(stringResource(R.string.memory_editor_peek))
        }
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.memory_editor_close))
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
                                EditorTab.RESULTS -> R.string.memory_editor_tab_results
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
private fun InputDock(
    label: String,
    value: String,
    onDone: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .testTag("memory_input_dock"),
        tonalElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = value.ifEmpty { "—" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onDone()
                },
            ) {
                Text(stringResource(R.string.memory_editor_done))
            }
        }
    }
}

@Composable
private fun SetupContent(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onInputFocused: (ActiveInput?) -> Unit,
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
            options = MemoryEditorRuntime.ValueKind.values().toList(),
            labelOf = ::kindLabel,
            onSelect = actions.setKind,
            testTag = "memory_type",
        )
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
            onFirstChanged = actions.setFirstValue,
            onSecondChanged = actions.setSecondValue,
            onInputFocused = onInputFocused,
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
private fun RefineContent(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onInputFocused: (ActiveInput?) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_editor_refine_title),
            style = MaterialTheme.typography.titleMedium,
        )
        RefinePanel(state, actions, onInputFocused)
    }
}

@Composable
private fun CollectingContent(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onClose: () -> Unit,
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
            text = stringResource(R.string.memory_editor_collecting_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.memory_editor_collecting_help),
            style = MaterialTheme.typography.bodyMedium,
        )
        DiagnosticsCard(state.snapshot)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = actions.refresh,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.memory_editor_refresh))
            }
            Button(
                onClick = actions.finishCollection,
                enabled = !state.busy,
                modifier = Modifier
                    .weight(1f)
                    .testTag("memory_finish"),
            ) {
                Text(stringResource(R.string.memory_editor_finish_baseline))
            }
        }
        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End),
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
    onInputFocused: (ActiveInput?) -> Unit,
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
                onFirstChanged = actions.setFirstValue,
                onSecondChanged = actions.setSecondValue,
                onInputFocused = onInputFocused,
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
            if (candidate.frozen) {
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
    onFirstChanged: (String) -> Unit,
    onSecondChanged: (String) -> Unit,
    onInputFocused: (ActiveInput?) -> Unit,
) {
    if (mode == MemoryEditorRuntime.SearchMode.EXACT ||
        mode == MemoryEditorRuntime.SearchMode.NOT_EQUAL ||
        mode == MemoryEditorRuntime.SearchMode.LESS_THAN ||
        mode == MemoryEditorRuntime.SearchMode.GREATER_THAN
    ) {
        ValueField(
            value = first,
            label = stringResource(R.string.memory_editor_value),
            onValueChanged = onFirstChanged,
            testTag = "memory_value",
            onFocusChanged = {
                onInputFocused(if (it) ActiveInput.FIRST else null)
            },
        )
    } else if (mode == MemoryEditorRuntime.SearchMode.RANGE) {
        ValueField(
            value = first,
            label = stringResource(R.string.memory_editor_minimum),
            onValueChanged = onFirstChanged,
            testTag = "memory_minimum",
            onFocusChanged = {
                onInputFocused(if (it) ActiveInput.FIRST else null)
            },
        )
        ValueField(
            value = second,
            label = stringResource(R.string.memory_editor_second_value),
            onValueChanged = onSecondChanged,
            testTag = "memory_maximum",
            onFocusChanged = {
                onInputFocused(if (it) ActiveInput.SECOND else null)
            },
        )
    }
}

@Composable
private fun ValueField(
    value: String,
    label: String,
    onValueChanged: (String) -> Unit,
    testTag: String,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChanged,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { focusState ->
                onFocusChanged(focusState.isFocused)
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
            .testTag(testTag),
    )
}

@Composable
private fun ReplacementDialog(
    action: OperationKind,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var replacement by remember { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (action == OperationKind.EDIT) {
                        R.string.memory_editor_edit_selected
                    } else {
                        R.string.memory_editor_freeze_selected
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                ValueField(
                    value = replacement,
                    label = stringResource(R.string.memory_editor_replacement),
                    onValueChanged = { replacement = it },
                    testTag = "memory_replacement",
                    onFocusChanged = { inputFocused = it },
                )
                if (inputFocused) {
                    InputDock(
                        label = stringResource(R.string.memory_editor_replacement),
                        value = replacement,
                        onDone = { inputFocused = false },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(replacement) },
                enabled = replacement.isNotBlank(),
            ) {
                Text(
                    stringResource(
                        if (action == OperationKind.EDIT) {
                            R.string.memory_editor_apply_edit
                        } else {
                            R.string.memory_editor_apply_freeze
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
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
private fun kindLabel(kind: MemoryEditorRuntime.ValueKind): String = stringResource(
    when (kind) {
        MemoryEditorRuntime.ValueKind.INT -> R.string.memory_editor_type_int
        MemoryEditorRuntime.ValueKind.LONG -> R.string.memory_editor_type_long
        MemoryEditorRuntime.ValueKind.FLOAT -> R.string.memory_editor_type_float
        MemoryEditorRuntime.ValueKind.DOUBLE -> R.string.memory_editor_type_double
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
private fun SetupPreview() {
    MemoryEditorTheme {
        MemoryEditorScreen(
            state = MemoryEditorUiState(),
            actions = MemoryEditorActions(),
            onClose = {},
        )
    }
}

@Preview(name = "Memory editor collecting", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
private fun CollectingPreview() {
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
private fun ResultsPreview() {
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
