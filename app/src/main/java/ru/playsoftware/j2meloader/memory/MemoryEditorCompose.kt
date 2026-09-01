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

package ru.playsoftware.j2meloader.memory

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.playsoftware.j2meloader.R

@Composable
internal fun MemoryEditorScreen(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
) {
    // Pre-compose while the bubble is enabled so the first open does not inflate the full tree
    // over a running MIDlet frame.
    if (!state.visible && !state.bubbleEnabled) return

    LaunchedEffect(state.visible, state.watchTab, state.connecting, state.supported, state.sessionStage) {
        if (!state.visible || state.connecting || !state.supported) return@LaunchedEffect
        while (state.visible) {
            if (!state.busy && (state.watchTab || state.sessionStage == MemorySessionStage.CANDIDATES)) {
                actions.refresh()
            }
            delay(1_000)
        }
    }
    LaunchedEffect(state.visible, state.connected, state.supported) {
        if (state.visible && state.connected && !state.supported) {
            repeat(4) {
                delay(250)
                actions.refreshCapabilities()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
            .windowInsetsPadding(WindowInsets.safeContent),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
        ) {
            MemoryInputArea(modifier = Modifier.fillMaxSize(), active = state.visible) {
                MemoryEditorContent(state, actions)
            }
        }
        if (state.busy) BusyOverlay(state, actions)
    }
}

@Composable
internal fun MemoryEditorBubble(
    visible: Boolean,
    onOpen: () -> Unit,
    onTouch: (MotionEvent) -> Boolean,
) {
    if (!visible) return
    val description = stringResource(R.string.memory_editor)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter(onTouchEvent = onTouch)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                onClick {
                    onOpen()
                    true
                }
            }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
            shadowElevation = 8.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_memory_editor_search),
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun BusyOverlay(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            shadowElevation = 12.dp,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val progress = if (state.scanBytesTotal > 0L) {
                    state.scanBytesScanned.toFloat() / state.scanBytesTotal.toFloat()
                } else {
                    null
                }
                if (progress == null) {
                    CircularProgressIndicator()
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    stringResource(
                        if (state.searching) R.string.memory_editor_searching
                        else R.string.memory_editor_working,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.searching) {
                    Text(
                        stringResource(
                            if (progress == null) R.string.memory_editor_searching_detail
                            else R.string.memory_editor_search_progress,
                            state.scanBytesScanned / (1024L * 1024L),
                            state.scanBytesTotal / (1024L * 1024L),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = actions::cancel) {
                    Text(stringResource(R.string.memory_editor_cancel))
                }
            }
        }
    }
}

@Composable
private fun MemoryEditorContent(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    var searchMode by remember { mutableStateOf(state.searchMode) }
    var value by remember { mutableStateOf("") }
    var secondValue by remember { mutableStateOf("") }
    var type by remember { mutableIntStateOf(MemoryEngineContract.TYPE_AUTO) }
    var predicate by remember { mutableIntStateOf(MemoryEngineContract.PREDICATE_EQUAL) }
    var compare by remember { mutableIntStateOf(MemoryEngineContract.COMPARE_PREVIOUS) }
    var scope by remember { mutableIntStateOf(MemoryEngineContract.SCOPE_JAVA_FAST) }
    var advanced by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var freezeDialog by remember { mutableStateOf(false) }
    var refineDialog by remember { mutableStateOf(false) }
    var detailRow by remember { mutableStateOf<MemoryCandidateRow?>(null) }
    var detailResult by remember { mutableStateOf<MemoryResultRow?>(null) }
    val searchScrollState = rememberScrollState()

    LaunchedEffect(state.sessionStage, state.searchMode, state.requestedType, state.searchScope) {
        searchMode = state.searchMode
        type = state.requestedType
        scope = state.searchScope
        when (state.sessionStage) {
            MemorySessionStage.EMPTY -> {
                value = ""
                secondValue = ""
                type = MemoryEngineContract.TYPE_AUTO
                predicate = MemoryEngineContract.PREDICATE_EQUAL
                compare = MemoryEngineContract.COMPARE_PREVIOUS
                advanced = false
            }
            MemorySessionStage.UNKNOWN_BASELINE -> {
                predicate = MemoryEngineContract.PREDICATE_CHANGED
            }
            MemorySessionStage.CANDIDATES -> Unit
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        EditorHeader(actions)

        if (state.connecting) {
            CenterStatus(progress = true, text = stringResource(R.string.memory_editor_working))
            return
        }
        if (!state.supported) {
            UnsupportedState(state, actions)
            return
        }

        WorkspaceTabs(state, actions)


        if (!state.watchTab) {
            if (state.sessionStage == MemorySessionStage.CANDIDATES) {
                CompactRefineStrip(
                    resultCount = state.resultCount,
                    value = value,
                    onValue = { value = it },
                    secondValue = secondValue,
                    onSecondValue = { secondValue = it },
                    type = type,
                    predicate = predicate,
                    onPredicate = { predicate = it },
                    compare = compare,
                    busy = state.busy,
                    onExpand = { refineDialog = true },
                    actions = actions,
                )
            } else {
                SearchWorkspace(
                    modifier = Modifier.weight(1f).verticalScroll(searchScrollState),
                    state = state,
                    searchMode = searchMode,
                    onSearchMode = { searchMode = it },
                    value = value,
                    onValue = { value = it },
                    secondValue = secondValue,
                    onSecondValue = { secondValue = it },
                    type = type,
                    onType = { type = it },
                    predicate = predicate,
                    onPredicate = { predicate = it },
                    compare = compare,
                    onCompare = { compare = it },
                    scope = scope,
                    onScope = { scope = it },
                    advanced = advanced,
                    onAdvanced = { advanced = !advanced },
                    actions = actions,
                )
            }
        }


        state.message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (state.watchTab) {
            WatchWorkspace(
                state = state,
                actions = actions,
                onOpen = { row -> detailRow = row },
                modifier = Modifier.weight(1f),
            )
        } else if (state.sessionStage == MemorySessionStage.CANDIDATES) {
            ResultsWorkspace(
                state = state,
                actions = actions,
                onOpen = { row -> detailResult = row },
                modifier = Modifier.weight(1f),
            )
        }

        if (state.selected.isNotEmpty()) {
            SelectionActions(
                state = state,
                actions = actions,
                onEdit = { editDialog = true },
                onFreeze = { freezeDialog = true },
            )
        } else if (state.watchTab || state.sessionStage == MemorySessionStage.CANDIDATES) {
            BottomUtilityActions(state, actions)
        }
    }

    if (refineDialog) {
        RefineControlsDialog(
            resultCount = state.resultCount,
            type = type,
            predicate = predicate,
            onPredicate = { predicate = it },
            compare = compare,
            onCompare = { compare = it },
            value = value,
            onValue = { value = it },
            secondValue = secondValue,
            onSecondValue = { secondValue = it },
            busy = state.busy,
            onDismiss = { refineDialog = false },
            onStartOver = {
                refineDialog = false
                actions.startOver()
            },
            actions = actions,
        )
    }

    if (editDialog) {
        val editableTypes = editableTypes(state)
        EditDialog(
            enabled = state.writeSupported,
            types = editableTypes,
            onDismiss = { editDialog = false },
            onApply = { replacement, selectedType ->
                editDialog = false
                actions.editSelected(replacement, selectedType)
            },
        )
    }

if (freezeDialog) {
    val freezeTypes = if (state.watchTab) {
        selectedRows(state).map { it.type }.distinct()
    } else {
        state.results.filter { it.id in state.selected }.map { it.primaryType }.distinct()
    }
    val initialValue = if (state.watchTab) {
        selectedRows(state).firstOrNull()?.let(MemoryEditorPageParser::value).orEmpty()
    } else {
        state.results.firstOrNull { it.id in state.selected }?.valueText.orEmpty()
    }
    FreezeDialog(
        enabled = state.writeSupported,
        types = freezeTypes,
        initialValue = initialValue,
        onDismiss = { freezeDialog = false },
        onApply = { mode, first, second ->
            freezeDialog = false
            actions.freezeSelected(mode, first, second)
        },
    )
}

    detailRow?.let { row ->
        CandidateDetailDialog(
            row = row,
            aliases = listOf(row),
            watch = state.watchTab,
            writeSupported = state.writeSupported,
            onDismiss = { detailRow = null },
            onSelect = {
                detailRow = null
                actions.clearSelection()
                actions.toggleSelection(row.id)
            },
            onEdit = {
                detailRow = null
                actions.clearSelection()
                actions.toggleSelection(row.id)
                editDialog = true
            },
            onWatch = {
                detailRow = null
                actions.clearSelection()
                actions.toggleSelection(row.id)
                actions.watchSelected(!state.watchTab)
            },
            onFreeze = {
                detailRow = null
                actions.clearSelection()
                actions.toggleSelection(row.id)
                freezeDialog = true
            },
        )
    }

    detailResult?.let { row ->
        ResultDetailDialog(
            row = row,
            writeSupported = state.writeSupported,
            onDismiss = { detailResult = null },
            onSelect = {
                detailResult = null
                actions.clearSelection()
                actions.toggleSelection(row.id)
            },
            onEdit = {
                detailResult = null
                actions.clearSelection()
                actions.toggleSelection(row.id)
                editDialog = true
            },
            onWatch = {
                detailResult = null
                actions.clearSelection()
                actions.toggleSelection(row.id)
                actions.watchSelected(true)
            },
            onFreeze = {
                detailResult = null
                actions.clearSelection()
                actions.toggleSelection(row.id)
                freezeDialog = true
            },
        )
    }
}

@Composable
private fun EditorHeader(actions: MemoryEditorActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.memory_editor),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = actions::close,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_memory_editor_close),
                contentDescription = stringResource(R.string.memory_editor_close),
            )
        }
    }
}

@Composable
private fun CenterStatus(progress: Boolean, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) CircularProgressIndicator()
        Text(text, modifier = Modifier.padding(top = if (progress) 16.dp else 0.dp))
    }
}

@Composable
private fun UnsupportedState(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.message ?: stringResource(R.string.memory_editor_unsupported))
        Button(onClick = actions::refreshCapabilities, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.memory_editor_refresh))
        }
    }
}

@Composable
private fun WorkspaceTabs(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = !state.watchTab,
            onClick = { actions.setWatchTab(false) },
            label = { Text(stringResource(R.string.memory_editor_search_tab)) },
        )
        FilterChip(
            selected = state.watchTab,
            onClick = { actions.setWatchTab(true) },
            label = { Text(stringResource(R.string.memory_editor_watch)) },
        )
    }
}

@Composable
private fun SearchWorkspace(
    modifier: Modifier = Modifier,
    state: MemoryEditorUiState,
    searchMode: MemorySearchMode,
    onSearchMode: (MemorySearchMode) -> Unit,
    value: String,
    onValue: (String) -> Unit,
    secondValue: String,
    onSecondValue: (String) -> Unit,
    type: Int,
    onType: (Int) -> Unit,
    predicate: Int,
    onPredicate: (Int) -> Unit,
    compare: Int,
    onCompare: (Int) -> Unit,
    scope: Int,
    onScope: (Int) -> Unit,
    advanced: Boolean,
    onAdvanced: () -> Unit,
    actions: MemoryEditorActions,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.sessionStage) {
            MemorySessionStage.EMPTY -> {
                SearchModeSelector(searchMode, onSearchMode)
                when (searchMode) {
                    MemorySearchMode.KNOWN -> KnownSearchPane(
                        value, onValue, secondValue, onSecondValue, type, onType, predicate, onPredicate,
                        scope, onScope, advanced, onAdvanced, state.busy, actions,
                    )
                    MemorySearchMode.UNKNOWN -> UnknownSearchPane(
                        type, onType, scope, onScope, advanced, onAdvanced, state.busy, actions,
                    )
                    MemorySearchMode.GROUP -> GroupSearchPane(
                        scope, onScope, state.busy, actions,
                    )
                }
            }
            MemorySessionStage.UNKNOWN_BASELINE -> UnknownBaselinePane(
                type = type,
                predicate = predicate,
                onPredicate = onPredicate,
                compare = compare,
                onCompare = onCompare,
                value = value,
                onValue = onValue,
                secondValue = secondValue,
                onSecondValue = onSecondValue,
                advanced = advanced,
                onAdvanced = onAdvanced,
                busy = state.busy,
                actions = actions,
            )
            MemorySessionStage.CANDIDATES -> RefinePane(
                resultCount = state.resultCount,
                type = type,
                predicate = predicate,
                onPredicate = onPredicate,
                compare = compare,
                onCompare = onCompare,
                value = value,
                onValue = onValue,
                secondValue = secondValue,
                onSecondValue = onSecondValue,
                advanced = advanced,
                onAdvanced = onAdvanced,
                busy = state.busy,
                actions = actions,
            )
        }
    }
}

@Composable
private fun SearchModeSelector(selected: MemorySearchMode, onChange: (MemorySearchMode) -> Unit) {
    Text(stringResource(R.string.memory_editor_search_mode), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == MemorySearchMode.KNOWN,
            onClick = { onChange(MemorySearchMode.KNOWN) },
            label = { Text(stringResource(R.string.memory_editor_known)) },
        )
        FilterChip(
            selected = selected == MemorySearchMode.UNKNOWN,
            onClick = { onChange(MemorySearchMode.UNKNOWN) },
            label = { Text(stringResource(R.string.memory_editor_unknown_short)) },
        )
        FilterChip(
            selected = selected == MemorySearchMode.GROUP,
            onClick = { onChange(MemorySearchMode.GROUP) },
            label = { Text(stringResource(R.string.memory_editor_group_short)) },
        )
    }
}


@Composable
private fun KnownSearchPane(
    value: String,
    onValue: (String) -> Unit,
    secondValue: String,
    onSecondValue: (String) -> Unit,
    type: Int,
    onType: (Int) -> Unit,
    predicate: Int,
    onPredicate: (Int) -> Unit,
    scope: Int,
    onScope: (Int) -> Unit,
    advanced: Boolean,
    onAdvanced: () -> Unit,
    busy: Boolean,
    actions: MemoryEditorActions,
) {
    val spec = MemoryInputSpec.forType(type)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MemoryValueInput(
            value = value,
            onValueChange = onValue,
            spec = spec,
            label = stringResource(R.string.memory_editor_search_hint),
            modifier = Modifier.weight(1f),
        )
        ChoiceMenu(type, VALUE_TYPES, { typeName(it) }, onType)
    }
    QuickKnownPredicates(predicate, onPredicate)
    if (advanced) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceMenu(predicate, KNOWN_PREDICATES, { predicateName(it) }, onPredicate)
            ScopeMenu(scope, onScope)
        }
    }
    if (predicate == MemoryEngineContract.PREDICATE_BETWEEN) {
        MemoryValueInput(
            value = secondValue,
            onValueChange = onSecondValue,
            spec = spec,
            label = stringResource(R.string.memory_editor_max_value),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    TextButton(onClick = onAdvanced) {
        Text(stringResource(R.string.memory_editor_advanced))
    }
    Button(
        onClick = {
            actions.startSearch(value, secondValue, type, newSearchPredicate(predicate), false, scope)
        },
        enabled = !busy && spec.isComplete(value) &&
            (predicate != MemoryEngineContract.PREDICATE_BETWEEN || spec.isComplete(secondValue)),
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
    ) {
        Text(stringResource(R.string.memory_editor_search_action))
    }
}

@Composable
private fun UnknownSearchPane(
    type: Int,
    onType: (Int) -> Unit,
    scope: Int,
    onScope: (Int) -> Unit,
    advanced: Boolean,
    onAdvanced: () -> Unit,
    busy: Boolean,
    actions: MemoryEditorActions,
) {
    Text(
        stringResource(R.string.memory_editor_unknown_explanation),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.memory_editor_data_type), modifier = Modifier.weight(1f))
        ChoiceMenu(type, VALUE_TYPES, { typeName(it) }, onType)
    }
    if (advanced) ScopeMenu(scope, onScope)
    TextButton(onClick = onAdvanced) {
        Text(stringResource(R.string.memory_editor_advanced))
    }
    Button(
        onClick = { actions.startSearch("", "", type, MemoryEngineContract.PREDICATE_EQUAL, true, scope) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
    ) {
        Text(stringResource(R.string.memory_editor_capture_baseline))
    }
}


@Composable
private fun GroupSearchPane(
    scope: Int,
    onScope: (Int) -> Unit,
    busy: Boolean,
    actions: MemoryEditorActions,
) {
    var drafts by remember {
        mutableStateOf(listOf(MemoryGroupDraft(), MemoryGroupDraft()))
    }
    var distance by remember { mutableStateOf("128") }

    Text(
        stringResource(R.string.memory_editor_group_builder_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    drafts.forEachIndexed { index, draft ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MemoryValueInput(
                value = draft.value,
                onValueChange = { updated ->
                    drafts = drafts.toMutableList().also { it[index] = draft.copy(value = updated) }
                },
                spec = MemoryInputSpec.forType(draft.type),
                label = stringResource(R.string.memory_editor_search_hint),
                modifier = Modifier.weight(1f),
            )
            ChoiceMenu(
                value = draft.type,
                values = EXPLICIT_VALUE_TYPES,
                label = { typeName(it) },
                onChange = { selected ->
                    drafts = drafts.toMutableList().also { it[index] = draft.copy(type = selected) }
                },
            )
            if (drafts.size > 2) {
                IconButton(onClick = { drafts = drafts.toMutableList().also { it.removeAt(index) } }) {
                    Icon(
                        painterResource(R.drawable.ic_memory_editor_close),
                        contentDescription = stringResource(R.string.memory_editor_remove_group_value),
                    )
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = {
                if (drafts.size < MemoryEngineContract.MAX_GROUP_VALUES) drafts = drafts + MemoryGroupDraft()
            },
            enabled = drafts.size < MemoryEngineContract.MAX_GROUP_VALUES,
        ) {
            Text(stringResource(R.string.memory_editor_add_group_value))
        }
        MemoryValueInput(
            value = distance,
            onValueChange = { distance = it },
            spec = MemoryInputSpec.positiveInteger(1, 4096),
            label = stringResource(R.string.memory_editor_group_distance),
            modifier = Modifier.weight(1f),
        )
    }
    ScopeMenu(scope, onScope)
    val parsedDistance = distance.toIntOrNull()
    val valid = drafts.size in 2..MemoryEngineContract.MAX_GROUP_VALUES &&
        drafts.all { MemoryInputSpec.forType(it.type).isComplete(it.value) } &&
        MemoryInputSpec.positiveInteger(1, 4096).isComplete(distance)
    Button(
        onClick = {
            actions.groupSearch(
                drafts.map { it.type }.toIntArray(),
                drafts.map { it.value.trim() }.toTypedArray(),
                parsedDistance ?: 128,
                scope,
            )
        },
        enabled = !busy && valid,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
    ) {
        Text(stringResource(R.string.memory_editor_group_search))
    }
}


@Composable
private fun UnknownBaselinePane(
    type: Int,
    predicate: Int,
    onPredicate: (Int) -> Unit,
    compare: Int,
    onCompare: (Int) -> Unit,
    value: String,
    onValue: (String) -> Unit,
    secondValue: String,
    onSecondValue: (String) -> Unit,
    advanced: Boolean,
    onAdvanced: () -> Unit,
    busy: Boolean,
    actions: MemoryEditorActions,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.memory_editor_baseline_captured),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.memory_editor_baseline_instruction))
            OutlinedButton(onClick = actions::close) {
                Text(stringResource(R.string.memory_editor_back_to_game))
            }
        }
    }
    Text(stringResource(R.string.memory_editor_what_changed), style = MaterialTheme.typography.labelLarge)
    QuickRelativePredicates(predicate, onPredicate)
    RefineValueFields(type, predicate, value, onValue, secondValue, onSecondValue)
    if (advanced) {
        ChoiceMenu(predicate, REFINE_PREDICATES, { predicateName(it) }, onPredicate)
        CompareMenu(compare, onCompare)
    }
    TextButton(onClick = onAdvanced) {
        Text(stringResource(R.string.memory_editor_more_conditions))
    }
    Button(
        onClick = { actions.nextScan(value, secondValue, predicate, compare) },
        enabled = !busy && refineInputValid(type, predicate, value, secondValue),
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
    ) {
        Text(stringResource(R.string.memory_editor_next_scan))
    }
    TextButton(onClick = actions::startOver) {
        Text(stringResource(R.string.memory_editor_start_over))
    }
}


@Composable
private fun RefinePane(
    resultCount: Long,
    type: Int,
    predicate: Int,
    onPredicate: (Int) -> Unit,
    compare: Int,
    onCompare: (Int) -> Unit,
    value: String,
    onValue: (String) -> Unit,
    secondValue: String,
    onSecondValue: (String) -> Unit,
    advanced: Boolean,
    onAdvanced: () -> Unit,
    busy: Boolean,
    actions: MemoryEditorActions,
) {
    QuickRefinePredicates(predicate, onPredicate)
    RefineValueFields(type, predicate, value, onValue, secondValue, onSecondValue)
    if (advanced) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceMenu(predicate, REFINE_PREDICATES, { predicateName(it) }, onPredicate)
            if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) CompareMenu(compare, onCompare)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { actions.nextScan(value, secondValue, predicate, compare) },
            enabled = !busy && resultCount > 0L && refineInputValid(type, predicate, value, secondValue),
            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.memory_editor_next_scan))
        }
        OutlinedButton(onClick = onAdvanced, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
            Text(stringResource(R.string.memory_editor_more))
        }
    }
    TextButton(onClick = actions::startOver) {
        Text(stringResource(R.string.memory_editor_start_over))
    }
}

@Composable
private fun CompactRefineStrip(
    resultCount: Long,
    value: String,
    onValue: (String) -> Unit,
    secondValue: String,
    onSecondValue: (String) -> Unit,
    type: Int,
    predicate: Int,
    onPredicate: (Int) -> Unit,
    compare: Int,
    busy: Boolean,
    onExpand: () -> Unit,
    actions: MemoryEditorActions,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (predicateNeedsValue(predicate)) {
                    MemoryValueInput(
                        value = value,
                        onValueChange = onValue,
                        spec = MemoryInputSpec.forType(type),
                        label = stringResource(R.string.memory_editor_search_hint),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        predicateName(predicate),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                }
                CompactPredicateMenu(predicate, onPredicate)
                Button(
                    onClick = { actions.nextScan(value, secondValue, predicate, compare) },
                    enabled = !busy && resultCount > 0L &&
                        refineInputValid(type, predicate, value, secondValue),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.memory_editor_next_scan))
                }
                val moreDescription = stringResource(R.string.memory_editor_more)
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier.semantics { contentDescription = moreDescription },
                ) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
            }
            if (predicateNeedsSecondValue(predicate)) {
                MemoryValueInput(
                    value = secondValue,
                    onValueChange = onSecondValue,
                    spec = MemoryInputSpec.forType(type),
                    label = stringResource(R.string.memory_editor_max_value),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RefineControlsDialog(
    resultCount: Long,
    type: Int,
    predicate: Int,
    onPredicate: (Int) -> Unit,
    compare: Int,
    onCompare: (Int) -> Unit,
    value: String,
    onValue: (String) -> Unit,
    secondValue: String,
    onSecondValue: (String) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
    onStartOver: () -> Unit,
    actions: MemoryEditorActions,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_refine_results)) },
        text = {
            MemoryInputArea(sideDockInLandscape = false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickRefinePredicates(predicate, onPredicate)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceMenu(predicate, REFINE_PREDICATES, { predicateName(it) }, onPredicate)
                        if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) {
                            CompareMenu(compare, onCompare)
                        }
                    }
                    RefineValueFields(type, predicate, value, onValue, secondValue, onSecondValue)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    actions.nextScan(value, secondValue, predicate, compare)
                },
                enabled = !busy && resultCount > 0L &&
                    refineInputValid(type, predicate, value, secondValue),
            ) {
                Text(stringResource(R.string.memory_editor_next_scan))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onStartOver, enabled = !busy) {
                    Text(stringResource(R.string.memory_editor_start_over))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.memory_editor_done))
                }
            }
        },
    )
}

@Composable
private fun CompactPredicateMenu(selected: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Text(compactPredicateName(selected), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            REFINE_PREDICATES.forEach { option ->
                DropdownMenuItem(
                    text = { Text(predicateName(option)) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

private fun compactPredicateName(predicate: Int): String = when (predicate) {
    MemoryEngineContract.PREDICATE_EQUAL -> "="
    MemoryEngineContract.PREDICATE_NOT_EQUAL -> "≠"
    MemoryEngineContract.PREDICATE_GREATER -> ">"
    MemoryEngineContract.PREDICATE_LESS -> "<"
    MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL -> "≥"
    MemoryEngineContract.PREDICATE_LESS_OR_EQUAL -> "≤"
    MemoryEngineContract.PREDICATE_BETWEEN -> "↔"
    MemoryEngineContract.PREDICATE_CHANGED -> "Δ"
    MemoryEngineContract.PREDICATE_UNCHANGED -> "=Δ"
    MemoryEngineContract.PREDICATE_INCREASED -> "↑"
    MemoryEngineContract.PREDICATE_DECREASED -> "↓"
    MemoryEngineContract.PREDICATE_INCREASED_BY -> "+Δ"
    MemoryEngineContract.PREDICATE_DECREASED_BY -> "−Δ"
    MemoryEngineContract.PREDICATE_CHANGED_BY -> "|Δ|"
    MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE -> "↑↔"
    MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE -> "↓↔"
    else -> "?"
}

@Composable
private fun QuickKnownPredicates(selected: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickPredicate("=", MemoryEngineContract.PREDICATE_EQUAL, selected, onChange)
        QuickPredicate("≠", MemoryEngineContract.PREDICATE_NOT_EQUAL, selected, onChange)
        QuickPredicate(">", MemoryEngineContract.PREDICATE_GREATER, selected, onChange)
        QuickPredicate("<", MemoryEngineContract.PREDICATE_LESS, selected, onChange)
    }
}

@Composable
private fun QuickRelativePredicates(selected: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickPredicate(stringResource(R.string.memory_editor_predicate_changed), MemoryEngineContract.PREDICATE_CHANGED, selected, onChange)
        QuickPredicate(stringResource(R.string.memory_editor_predicate_unchanged), MemoryEngineContract.PREDICATE_UNCHANGED, selected, onChange)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickPredicate(stringResource(R.string.memory_editor_predicate_increased), MemoryEngineContract.PREDICATE_INCREASED, selected, onChange)
        QuickPredicate(stringResource(R.string.memory_editor_predicate_decreased), MemoryEngineContract.PREDICATE_DECREASED, selected, onChange)
    }
}

@Composable
private fun QuickRefinePredicates(selected: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickPredicate("=", MemoryEngineContract.PREDICATE_EQUAL, selected, onChange)
        QuickPredicate("≠", MemoryEngineContract.PREDICATE_NOT_EQUAL, selected, onChange)
        QuickPredicate(stringResource(R.string.memory_editor_predicate_changed), MemoryEngineContract.PREDICATE_CHANGED, selected, onChange)
        QuickPredicate(stringResource(R.string.memory_editor_predicate_unchanged), MemoryEngineContract.PREDICATE_UNCHANGED, selected, onChange)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickPredicate(stringResource(R.string.memory_editor_predicate_increased), MemoryEngineContract.PREDICATE_INCREASED, selected, onChange)
        QuickPredicate(stringResource(R.string.memory_editor_predicate_decreased), MemoryEngineContract.PREDICATE_DECREASED, selected, onChange)
    }
}

@Composable
private fun QuickPredicate(label: String, value: Int, selected: Int, onChange: (Int) -> Unit) {
    FilterChip(
        selected = selected == value,
        onClick = { onChange(value) },
        label = { Text(label, maxLines = 1) },
    )
}


@Composable
private fun RefineValueFields(
    type: Int,
    predicate: Int,
    value: String,
    onValue: (String) -> Unit,
    secondValue: String,
    onSecondValue: (String) -> Unit,
) {
    if (!predicateNeedsValue(predicate)) return
    val spec = MemoryInputSpec.forType(type)
    MemoryValueInput(
        value = value,
        onValueChange = onValue,
        spec = spec,
        label = stringResource(R.string.memory_editor_search_hint),
        modifier = Modifier.fillMaxWidth(),
    )
    if (predicateNeedsSecondValue(predicate)) {
        MemoryValueInput(
            value = secondValue,
            onValueChange = onSecondValue,
            spec = spec,
            label = stringResource(R.string.memory_editor_max_value),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun predicateNeedsValue(predicate: Int): Boolean = when (predicate) {
    MemoryEngineContract.PREDICATE_CHANGED,
    MemoryEngineContract.PREDICATE_UNCHANGED,
    MemoryEngineContract.PREDICATE_INCREASED,
    MemoryEngineContract.PREDICATE_DECREASED -> false
    else -> true
}

private fun predicateNeedsSecondValue(predicate: Int): Boolean =
    predicate == MemoryEngineContract.PREDICATE_BETWEEN ||
        predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE ||
        predicate == MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE

private fun refineInputValid(type: Int, predicate: Int, value: String, secondValue: String): Boolean {
    val spec = MemoryInputSpec.forType(type)
    return (!predicateNeedsValue(predicate) || spec.isComplete(value)) &&
        (!predicateNeedsSecondValue(predicate) || spec.isComplete(secondValue))
}

@Composable
private fun ResultsWorkspace(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onOpen: (MemoryResultRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(
                R.plurals.memory_editor_results,
                state.resultCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.resultCount,
            ),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        SelectionHeaderButtons(state.results.isNotEmpty(), actions)
    }
    if (state.results.isEmpty()) {
        EmptyList(modifier)
    } else {
        LazyColumn(modifier = modifier.fillMaxWidth()) {
            items(state.results, key = { it.id }) { row ->
                ResultGroupRow(
                    row = row,
                    selected = row.id in state.selected,
                    onToggle = { actions.toggleSelection(row.id) },
                    onOpen = { onOpen(row) },
                )
            }
        }
    }
    ResultPager(state, actions)
}

@Composable
private fun WatchWorkspace(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onOpen: (MemoryCandidateRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.memory_editor_watch),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        SelectionHeaderButtons(state.watches.isNotEmpty(), actions)
    }
    if (state.watches.isEmpty()) {
        EmptyList(modifier, R.string.memory_editor_no_watch)
    } else {
        LazyColumn(modifier = modifier.fillMaxWidth()) {
            items(state.watches, key = { it.id }) { row ->
                WatchRow(
                    row = row,
                    selected = row.id in state.selected,
                    onToggle = { actions.toggleSelection(row.id) },
                    onOpen = { onOpen(row) },
                    onLabel = actions::labelWatch,
                )
            }
        }
    }
}

@Composable
private fun SelectionHeaderButtons(enabled: Boolean, actions: MemoryEditorActions) {
    IconButton(onClick = actions::selectVisible, enabled = enabled) {
        Icon(
            painterResource(R.drawable.ic_select_all),
            contentDescription = stringResource(R.string.memory_editor_select_visible),
        )
    }
    IconButton(onClick = actions::invertVisible, enabled = enabled) {
        Icon(
            painterResource(R.drawable.ic_swap),
            contentDescription = stringResource(R.string.memory_editor_invert_visible),
        )
    }
}


@Composable
private fun ResultGroupRow(
    row: MemoryResultRow,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.valueText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    row.aliasTypes.joinToString(" · ") { typeShortName(it) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.addressText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                exceptionalState(row.state, row.relocations)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    HorizontalDivider()
}


@Composable
private fun WatchRow(
    row: MemoryCandidateRow,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onLabel: (Long, String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label.ifBlank { typeShortName(row.type) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    MemoryEditorPageParser.value(row),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 156.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "0x${row.address.toULong().toString(16).uppercase()} · ${typeShortName(row.type)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val exceptional = exceptionalState(row)
                if (row.freezePaused) {
                    Text(
                        stringResource(R.string.memory_editor_freeze_paused),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (exceptional != null) {
                    Text(exceptional, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        WatchLabelButton(row, onLabel)
        if (row.freezeMode >= 0) {
            Icon(
                painterResource(R.drawable.ic_screen_lock_rotation),
                contentDescription = stringResource(R.string.memory_editor_freeze),
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyList(modifier: Modifier, text: Int = R.string.memory_editor_no_results) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultPager(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    if (state.resultCount <= MemoryEditorComposeController.PAGE_SIZE) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIconButton(
            icon = R.drawable.ic_arrow_back,
            description = R.string.memory_editor_previous_page,
            onClick = actions::previousPage,
            enabled = state.pageOffset > 0,
        )
        Text(
            "${state.pageOffset + 1}–${minOf(state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE, state.resultCount)}",
        )
        ActionIconButton(
            icon = R.drawable.ic_arrow_downward,
            description = R.string.memory_editor_next_page,
            onClick = actions::nextPage,
            enabled = state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE < state.resultCount,
        )
    }
}

@Composable
private fun WatchLabelButton(row: MemoryCandidateRow, onLabel: (Long, String) -> Unit) {
    var dialog by remember(row.id) { mutableStateOf(false) }
    ActionIconButton(
        icon = R.drawable.ic_edit,
        description = R.string.memory_editor_watch_label,
        onClick = { dialog = true },
    )
    if (dialog) {
        var value by remember(row.label) { mutableStateOf(row.label) }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(stringResource(R.string.memory_editor_watch_label)) },
            text = { OutlinedTextField(value, { value = it.take(64) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { dialog = false; onLabel(row.id, value) }) {
                    Text(stringResource(R.string.memory_editor_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}


@Composable
private fun SelectionActions(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onEdit: () -> Unit,
    onFreeze: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Text(
            pluralStringResource(R.plurals.memory_editor_selected, state.selected.size, state.selected.size),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIconButton(
                R.drawable.ic_edit,
                R.string.memory_editor_edit,
                onEdit,
                enabled = state.writeSupported,
            )
            TextButton(onClick = { actions.watchSelected(!state.watchTab) }) {
                Text(stringResource(if (state.watchTab) R.string.memory_editor_remove else R.string.memory_editor_watch))
            }
            ActionIconButton(
                R.drawable.ic_screen_lock_rotation,
                R.string.memory_editor_freeze,
                onFreeze,
                enabled = state.writeSupported,
            )
            if (state.selected.size == 1) {
                ActionIconButton(
                    R.drawable.ic_memory_editor_search,
                    R.string.memory_editor_inspect_memory,
                    { actions.inspectCandidate(state.selected.single()) },
                )
            }
            Box {
                val moreDescription = stringResource(R.string.memory_editor_more)
                IconButton(
                    onClick = { more = true },
                    modifier = Modifier.semantics { contentDescription = moreDescription },
                ) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (!state.watchTab) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.memory_editor_keep)) },
                            onClick = { more = false; actions.removeSelected(true) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.memory_editor_remove)) },
                            onClick = { more = false; actions.removeSelected(false) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.memory_editor_unfreeze)) },
                            onClick = { more = false; actions.clearFreezeSelected() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.memory_editor_copy_values)) },
                        onClick = { more = false; actions.copySelected(false) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.memory_editor_copy_addresses)) },
                        onClick = { more = false; actions.copySelected(true) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.memory_editor_clear_selection)) },
                        onClick = { more = false; actions.clearSelection() },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomUtilityActions(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIconButton(
            R.drawable.ic_history,
            R.string.memory_editor_undo,
            actions::undo,
            enabled = !state.busy && !state.watchTab && state.canUndo,
        )
        ActionIconButton(
            R.drawable.ic_restart_alt,
            R.string.memory_editor_refresh,
            actions::refresh,
            enabled = !state.busy,
        )
    }
}

@Composable
private fun CandidateDetailDialog(
    row: MemoryCandidateRow,
    aliases: List<MemoryCandidateRow>,
    watch: Boolean,
    writeSupported: Boolean,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onWatch: () -> Unit,
    onFreeze: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(row.label.ifBlank { MemoryEditorPageParser.value(row) })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine(stringResource(R.string.memory_editor_current_value), MemoryEditorPageParser.value(row))
                DetailLine(stringResource(R.string.memory_editor_initial_value), formatBits(row.type, row.initialBits))
                DetailLine(stringResource(R.string.memory_editor_previous_value), formatBits(row.type, row.previousBits))
                DetailLine(stringResource(R.string.memory_editor_address), "0x${row.address.toULong().toString(16).uppercase()}")
                DetailLine(stringResource(R.string.memory_editor_data_type), typeName(row.type))
                if (aliases.size > 1) {
                    Text(
                        stringResource(R.string.memory_editor_interpretations) + ": " +
                            aliases.joinToString(" · ") { typeShortName(it.type) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                exceptionalState(row)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (row.relocations > 0) {
                    DetailLine(stringResource(R.string.memory_editor_relocations), row.relocations.toString())
                }
                if (row.freezeMode >= 0) {
                    DetailLine(
                        stringResource(R.string.memory_editor_freeze),
                        if (row.freezePaused) stringResource(R.string.memory_editor_freeze_paused)
                        else freezeName(row.freezeMode),
                    )
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit, enabled = writeSupported) {
                        Text(stringResource(R.string.memory_editor_edit))
                    }
                    TextButton(onClick = onWatch) {
                        Text(stringResource(if (watch) R.string.memory_editor_remove else R.string.memory_editor_watch))
                    }
                    TextButton(onClick = onFreeze, enabled = writeSupported) {
                        Text(stringResource(R.string.memory_editor_freeze))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSelect) {
                Text(stringResource(R.string.memory_editor_select))
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
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ResultDetailDialog(
    row: MemoryResultRow,
    writeSupported: Boolean,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onWatch: () -> Unit,
    onFreeze: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.valueText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine(stringResource(R.string.memory_editor_current_value), row.valueText)
                DetailLine(stringResource(R.string.memory_editor_address), row.addressText)
                DetailLine(stringResource(R.string.memory_editor_data_type), typeName(row.primaryType))
                if (row.aliasTypes.size > 1) {
                    Text(
                        stringResource(R.string.memory_editor_interpretations) + ": " +
                            row.aliasTypes.joinToString(" · ") { typeShortName(it) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                exceptionalState(row.state, row.relocations)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (row.relocations > 0) {
                    DetailLine(stringResource(R.string.memory_editor_relocations), row.relocations.toString())
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit, enabled = writeSupported) {
                        Text(stringResource(R.string.memory_editor_edit))
                    }
                    TextButton(onClick = onWatch) {
                        Text(stringResource(R.string.memory_editor_watch))
                    }
                    TextButton(onClick = onFreeze, enabled = writeSupported) {
                        Text(stringResource(R.string.memory_editor_freeze))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSelect) {
                Text(stringResource(R.string.memory_editor_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun formatBits(type: Int, bits: Long): String = MemoryEditorPageParser.value(
    MemoryCandidateRow(
        id = 0,
        address = 0,
        previousAddress = 0,
        type = type,
        state = MemoryEngineContract.CANDIDATE_STABLE,
        relocations = 0,
        initialBits = bits,
        previousBits = bits,
        currentBits = bits,
    ),
)

private fun selectedRows(state: MemoryEditorUiState): List<MemoryCandidateRow> =
    state.watches.filter { it.id in state.selected }

private fun editableTypes(state: MemoryEditorUiState): List<Int> {
    if (!state.watchTab) return commonTypesForSelection(state.results, state.selected)
    val selected = state.watches.filter { it.id in state.selected }
    if (selected.isEmpty()) return emptyList()
    val types = selected.map { it.type }.distinct()
    return if (types.size == 1) types else emptyList()
}

@Composable
private fun ActionIconButton(
    icon: Int,
    description: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = stringResource(description))
    }
}


@Composable
private fun EditDialog(
    enabled: Boolean,
    types: List<Int>,
    onDismiss: () -> Unit,
    onApply: (String, Int) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var type by remember(types) { mutableIntStateOf(types.firstOrNull() ?: MemoryEngineContract.TYPE_INT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_edit)) },
        text = {
            MemoryInputArea(sideDockInLandscape = false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!enabled) {
                        Text(stringResource(R.string.memory_editor_write_unsupported), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.memory_editor_data_type), style = MaterialTheme.typography.labelMedium)
                    ChoiceMenu(type, types.toIntArray(), { typeName(it) }) { type = it }
                    MemoryValueInput(
                        value = value,
                        onValueChange = { value = it },
                        spec = MemoryInputSpec.forType(type),
                        label = stringResource(R.string.memory_editor_replacement),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(value, type) },
                enabled = enabled && types.isNotEmpty() && MemoryInputSpec.forType(type).isComplete(value),
            ) {
                Text(stringResource(R.string.memory_editor_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}


@Composable
private fun FreezeDialog(
    enabled: Boolean,
    types: List<Int>,
    initialValue: String,
    onDismiss: () -> Unit,
    onApply: (Int, String, String) -> Unit,
) {
    var mode by remember { mutableIntStateOf(MemoryEngineContract.FREEZE_LOCK) }
    var first by remember(initialValue, types) {
        mutableStateOf(initialValue.takeIf { memoryInputCompleteForTypes(it, types) }.orEmpty())
    }
    var second by remember { mutableStateOf("") }
    val inputSpec = MemoryInputSpec.forType(types.singleOrNull() ?: MemoryEngineContract.TYPE_AUTO)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_freeze)) },
        text = {
            MemoryInputArea(sideDockInLandscape = false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceMenu(mode, FREEZE_MODES, { freezeName(it) }) { mode = it }
                    Text(freezeDescription(mode), style = MaterialTheme.typography.bodySmall)
                    MemoryValueInput(
                        value = first,
                        onValueChange = { first = it },
                        spec = inputSpec,
                        label = stringResource(
                            if (mode == MemoryEngineContract.FREEZE_RANGE) R.string.memory_editor_min_value
                            else R.string.memory_editor_freeze_target,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mode == MemoryEngineContract.FREEZE_RANGE) {
                        MemoryValueInput(
                            value = second,
                            onValueChange = { second = it },
                            spec = inputSpec,
                            label = stringResource(R.string.memory_editor_max_value),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(mode, first, second) },
                enabled = enabled && memoryInputCompleteForTypes(first, types) &&
                    (mode != MemoryEngineContract.FREEZE_RANGE ||
                        memoryInputCompleteForTypes(second, types)),
            ) {
                Text(stringResource(R.string.memory_editor_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ScopeMenu(scope: Int, onChange: (Int) -> Unit) {
    ChoiceMenu(
        scope,
        intArrayOf(MemoryEngineContract.SCOPE_JAVA_FAST, MemoryEngineContract.SCOPE_JAVA_THOROUGH),
        {
            stringResource(
                if (it == MemoryEngineContract.SCOPE_JAVA_FAST) R.string.memory_editor_scope_fast
                else R.string.memory_editor_scope_thorough,
            )
        },
        onChange,
    )
}

@Composable
private fun CompareMenu(compare: Int, onChange: (Int) -> Unit) {
    ChoiceMenu(
        compare,
        intArrayOf(MemoryEngineContract.COMPARE_PREVIOUS, MemoryEngineContract.COMPARE_INITIAL),
        {
            stringResource(
                if (it == MemoryEngineContract.COMPARE_PREVIOUS) R.string.memory_editor_previous
                else R.string.memory_editor_initial,
            )
        },
        onChange,
    )
}

@Composable
private fun ChoiceMenu(
    value: Int,
    values: IntArray,
    label: @Composable (Int) -> String,
    onChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = values.isNotEmpty(),
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        ) {
            Text(if (values.isEmpty()) "—" else label(value), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { expanded = false; onChange(option) },
                )
            }
        }
    }
}

private fun typeName(type: Int): String = when (type) {
    MemoryEngineContract.TYPE_AUTO -> "Auto"
    MemoryEngineContract.TYPE_BYTE -> "Byte (8-bit)"
    MemoryEngineContract.TYPE_SHORT -> "Short (16-bit)"
    MemoryEngineContract.TYPE_CHAR -> "Char / UInt16"
    MemoryEngineContract.TYPE_INT -> "Int (32-bit)"
    MemoryEngineContract.TYPE_LONG -> "Long (64-bit)"
    MemoryEngineContract.TYPE_FLOAT -> "Float (32-bit)"
    MemoryEngineContract.TYPE_DOUBLE -> "Double (64-bit)"
    else -> "?"
}

private fun typeShortName(type: Int): String = when (type) {
    MemoryEngineContract.TYPE_BYTE -> "Int8"
    MemoryEngineContract.TYPE_SHORT -> "Int16"
    MemoryEngineContract.TYPE_CHAR -> "UInt16"
    MemoryEngineContract.TYPE_INT -> "Int32"
    MemoryEngineContract.TYPE_LONG -> "Int64"
    MemoryEngineContract.TYPE_FLOAT -> "Float32"
    MemoryEngineContract.TYPE_DOUBLE -> "Float64"
    else -> "Auto"
}

@Composable
private fun predicateName(predicate: Int): String = when (predicate) {
    0 -> "="
    1 -> "≠"
    2 -> ">"
    3 -> "<"
    4 -> "≥"
    5 -> "≤"
    6 -> stringResource(R.string.memory_editor_predicate_between)
    7 -> stringResource(R.string.memory_editor_predicate_changed)
    8 -> stringResource(R.string.memory_editor_predicate_unchanged)
    9 -> stringResource(R.string.memory_editor_predicate_increased)
    10 -> stringResource(R.string.memory_editor_predicate_decreased)
    11 -> stringResource(R.string.memory_editor_predicate_increased_by)
    12 -> stringResource(R.string.memory_editor_predicate_decreased_by)
    13 -> stringResource(R.string.memory_editor_predicate_changed_by)
    14 -> stringResource(R.string.memory_editor_predicate_increased_range)
    15 -> stringResource(R.string.memory_editor_predicate_decreased_range)
    else -> "?"
}

@Composable
private fun exceptionalState(row: MemoryCandidateRow): String? = when (row.state) {
    MemoryEngineContract.CANDIDATE_STABLE -> if (row.relocations > 0) {
        stringResource(R.string.memory_editor_candidate_moved)
    } else null
    MemoryEngineContract.CANDIDATE_RELOCATING -> stringResource(R.string.memory_editor_candidate_relocating)
    MemoryEngineContract.CANDIDATE_AMBIGUOUS -> stringResource(R.string.memory_editor_candidate_ambiguous)
    else -> stringResource(R.string.memory_editor_candidate_lost)
}

@Composable
private fun exceptionalState(state: Int, relocations: Int): String? = when (state) {
    MemoryEngineContract.CANDIDATE_STABLE -> if (relocations > 0) {
        stringResource(R.string.memory_editor_candidate_moved)
    } else null
    MemoryEngineContract.CANDIDATE_RELOCATING -> stringResource(R.string.memory_editor_candidate_relocating)
    MemoryEngineContract.CANDIDATE_AMBIGUOUS -> stringResource(R.string.memory_editor_candidate_ambiguous)
    else -> stringResource(R.string.memory_editor_candidate_lost)
}

@Composable
private fun freezeName(mode: Int): String = stringResource(when (mode) {
    MemoryEngineContract.FREEZE_LOCK -> R.string.memory_editor_freeze_lock
    MemoryEngineContract.FREEZE_MINIMUM -> R.string.memory_editor_freeze_minimum
    MemoryEngineContract.FREEZE_MAXIMUM -> R.string.memory_editor_freeze_maximum
    else -> R.string.memory_editor_freeze_range
})

@Composable
private fun freezeDescription(mode: Int): String = stringResource(when (mode) {
    MemoryEngineContract.FREEZE_LOCK -> R.string.memory_editor_freeze_lock_help
    MemoryEngineContract.FREEZE_MINIMUM -> R.string.memory_editor_freeze_minimum_help
    MemoryEngineContract.FREEZE_MAXIMUM -> R.string.memory_editor_freeze_maximum_help
    else -> R.string.memory_editor_freeze_range_help
})

private val VALUE_TYPES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
private val EXPLICIT_VALUE_TYPES = intArrayOf(1, 2, 3, 4, 5, 6, 7)
private val KNOWN_PREDICATES = intArrayOf(0, 1, 2, 3, 4, 5, 6)
private val REFINE_PREDICATES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
private val FREEZE_MODES = intArrayOf(0, 1, 2, 3)
