/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.playsoftware.j2meloader.memory

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.AdaptiveAlertDialog as AlertDialog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

private enum class RuntimeMemoryTab { SEARCH_RESULTS, WATCH, INSPECTOR }
private enum class RuntimeInputField { FIRST, SECOND }

/** Production Memory Editor shell hosted in the dedicated :memory_engine Activity. */
@Composable
internal fun MemoryEditorRuntimeRoot(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
) {
    if (!state.visible) return

    val initialTab = when {
        state.inspector != null -> RuntimeMemoryTab.INSPECTOR
        state.watchTab -> RuntimeMemoryTab.WATCH
        else -> RuntimeMemoryTab.SEARCH_RESULTS
    }
    var tab by remember(state.runtimeToken) { mutableStateOf(initialTab) }
    val selectedId = state.selected.singleOrNull()

    LaunchedEffect(tab) {
        when (tab) {
            RuntimeMemoryTab.SEARCH_RESULTS -> actions.setWatchTab(false)
            RuntimeMemoryTab.WATCH -> actions.setWatchTab(true)
            RuntimeMemoryTab.INSPECTOR -> Unit
        }
    }
    LaunchedEffect(tab, selectedId, state.inspector?.candidateId, state.inspectorLoading) {
        if (tab != RuntimeMemoryTab.INSPECTOR || selectedId == null || state.inspectorLoading) {
            return@LaunchedEffect
        }
        if (state.inspector?.candidateId != selectedId) {
            actions.closeInspector()
            actions.inspectCandidate(selectedId)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.04f)),
        contentAlignment = Alignment.Center,
    ) {
        val landscape = maxWidth > maxHeight
        val panelModifier = if (landscape) {
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        } else {
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
                .widthIn(max = 720.dp)
        }
        Surface(
            modifier = panelModifier,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                RuntimeMemoryHeader(actions)
                RuntimeMemoryTabs(
                    tab = tab,
                    results = state.resultCount,
                    watches = state.watches.size,
                    onTab = { tab = it },
                )
                if (state.busy) RuntimeOperationStrip(state, actions)
                state.message?.takeIf(String::isNotBlank)?.let { RuntimeMessage(it) }

                when (tab) {
                    RuntimeMemoryTab.SEARCH_RESULTS -> RuntimeSearchResultsTab(
                        state = state.copy(watchTab = false),
                        actions = actions,
                        onInspectSelected = { tab = RuntimeMemoryTab.INSPECTOR },
                        modifier = Modifier.weight(1f),
                    )
                    RuntimeMemoryTab.WATCH -> RuntimeWatchTab(
                        state = state.copy(watchTab = true),
                        actions = actions,
                        modifier = Modifier.weight(1f),
                    )
                    RuntimeMemoryTab.INSPECTOR -> RuntimeInspectorTab(
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
private fun RuntimeMemoryHeader(actions: MemoryEditorActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.memory_editor),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = actions::close) {
            Icon(
                painterResource(R.drawable.ic_memory_editor_close),
                contentDescription = stringResource(R.string.memory_editor_close),
            )
        }
    }
}

@Composable
private fun RuntimeMemoryTabs(
    tab: RuntimeMemoryTab,
    results: Long,
    watches: Int,
    onTab: (RuntimeMemoryTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = tab == RuntimeMemoryTab.SEARCH_RESULTS,
            onClick = { onTab(RuntimeMemoryTab.SEARCH_RESULTS) },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_memory_editor_search), contentDescription = null)
            },
            label = {
                Text(
                    if (results > 0L) {
                        "${stringResource(R.string.memory_editor_search_tab)} · ${compactCount(results)}"
                    } else {
                        stringResource(R.string.memory_editor_search_tab)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = tab == RuntimeMemoryTab.WATCH,
            onClick = { onTab(RuntimeMemoryTab.WATCH) },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_memory_editor_watch), contentDescription = null)
            },
            label = {
                Text(
                    if (watches > 0) "${stringResource(R.string.memory_editor_watch)} · $watches"
                    else stringResource(R.string.memory_editor_watch),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = tab == RuntimeMemoryTab.INSPECTOR,
            onClick = { onTab(RuntimeMemoryTab.INSPECTOR) },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_memory_editor_inspector), contentDescription = null)
            },
            label = {
                Text(
                    stringResource(R.string.memory_editor_inspector),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RuntimeOperationStrip(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    val progress = if (state.scanBytesTotal > 0L) {
        (state.scanBytesScanned.toFloat() / state.scanBytesTotal.toFloat()).coerceIn(0f, 1f)
    } else null
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (progress == null) {
                    CircularProgressIndicator(modifier = Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (state.searching) R.string.memory_editor_searching else R.string.memory_editor_working,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (progress != null) {
                        Text(
                            stringResource(
                                R.string.memory_editor_search_progress,
                                state.scanBytesScanned / (1024L * 1024L),
                                state.scanBytesTotal / (1024L * 1024L),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (progress != null) {
                    Text(
                        "${(progress * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = actions::cancel) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RuntimeMessage(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.82f)) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun RuntimeSearchResultsTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onInspectSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var knownDialog by remember { mutableStateOf(false) }
    var unknownDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    val selectedRow = state.selected.singleOrNull()?.let { id -> state.results.firstOrNull { it.id == id } }
    val allVisibleSelected = state.results.isNotEmpty() &&
        state.results.all { it.id in state.selected }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeActionIcon(
                icon = R.drawable.ic_memory_editor_search,
                description = R.string.memory_editor_search_known_values,
                enabled = !state.busy,
                onClick = { knownDialog = true },
            )
            RuntimeActionIcon(
                icon = R.drawable.ic_memory_editor_search_unknown,
                description = R.string.memory_editor_search_unknown_values,
                enabled = !state.busy,
                onClick = { unknownDialog = true },
            )
            RuntimeActionIcon(
                icon = R.drawable.ic_edit,
                description = R.string.memory_editor_edit,
                enabled = selectedRow != null && state.writeSupported && !state.busy,
                onClick = { editDialog = true },
            )
            RuntimeActionIcon(
                icon = R.drawable.ic_restart_alt,
                description = R.string.memory_editor_refresh,
                enabled = state.results.isNotEmpty() && !state.busy,
                onClick = actions::refresh,
            )
            RuntimeActionIcon(
                icon = R.drawable.ic_memory_editor_inspector,
                description = R.string.memory_editor_inspect_memory,
                enabled = selectedRow != null && !state.busy,
                onClick = onInspectSelected,
            )
            RuntimeActionIcon(
                icon = R.drawable.ic_delete,
                description = R.string.memory_editor_remove,
                enabled = state.selected.isNotEmpty() && !state.busy,
                onClick = { actions.removeSelected(false) },
            )
            val visibleSelectionDescription = stringResource(
                if (allVisibleSelected) R.string.memory_editor_clear_selection
                else R.string.memory_editor_select_visible,
            )
            Checkbox(
                checked = allVisibleSelected,
                enabled = state.results.isNotEmpty() && !state.busy,
                onCheckedChange = { checked ->
                    if (checked) actions.selectVisible() else actions.clearSelection()
                },
                modifier = Modifier.semantics {
                    contentDescription = visibleSelectionDescription
                },
            )
            Spacer(Modifier.weight(1f))
            if (state.canUndo) {
                TextButton(onClick = actions::undo, enabled = !state.busy) {
                    Text(stringResource(R.string.memory_editor_undo))
                }
            }
        }
        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(
                    R.plurals.memory_editor_results,
                    state.resultCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    state.resultCount,
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.selected.isNotEmpty()) {
                Text(
                    pluralStringResource(
                        R.plurals.memory_editor_selected,
                        state.selected.size,
                        state.selected.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (state.results.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.memory_editor_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.results, key = { it.id }) { row ->
                    RuntimeResultRow(
                        row = row,
                        selected = row.id in state.selected,
                        onToggle = { actions.toggleSelection(row.id) },
                        onClick = {
                            if (row.id in state.selected && state.selected.size == 1) {
                                actions.clearSelection()
                            } else {
                                if (state.selected.size <= 1) actions.clearSelection()
                                actions.toggleSelection(row.id)
                            }
                        },
                        onLongClick = { actions.toggleSelection(row.id) },
                    )
                }
            }
        }
        RuntimePager(state, actions)
    }

    if (knownDialog) {
        RuntimeKnownSearchDialog(state = state, actions = actions, onDismiss = { knownDialog = false })
    }
    if (unknownDialog) {
        RuntimeUnknownSearchDialog(state = state, actions = actions, onDismiss = { unknownDialog = false })
    }
    if (editDialog && selectedRow != null) {
        RuntimeEditDialog(
            value = selectedRow.valueText,
            initialType = selectedRow.primaryType,
            types = selectedRow.aliasTypes,
            writeSupported = state.writeSupported,
            actions = actions,
            onDismiss = { editDialog = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuntimeResultRow(
    row: MemoryResultRow,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.valueText,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val aliasCount = row.aliasTypes.size
                    Text(
                        if (aliasCount > 1) "${runtimeTypeShort(row.primaryType)} +${aliasCount - 1}"
                        else runtimeTypeShort(row.primaryType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
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
                    RuntimeCandidateStatus(row.state, row.relocations)
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun RuntimeWatchTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    modifier: Modifier = Modifier,
) {
    var editDialog by remember { mutableStateOf(false) }
    val selectedRow = state.selected.singleOrNull()?.let { id -> state.watches.firstOrNull { it.id == id } }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.memory_editor_watch),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            RuntimeActionIcon(
                R.drawable.ic_edit,
                R.string.memory_editor_edit,
                enabled = selectedRow != null && state.writeSupported && !state.busy,
            ) { editDialog = true }
            RuntimeActionIcon(
                R.drawable.ic_restart_alt,
                R.string.memory_editor_refresh,
                enabled = state.watches.isNotEmpty() && !state.busy,
                onClick = actions::refresh,
            )
            when {
                selectedRow?.freezeMode?.let { it >= 0 } == true -> RuntimeActionIcon(
                    R.drawable.ic_screen_lock_rotation,
                    R.string.memory_editor_unfreeze,
                    enabled = !state.busy,
                    onClick = actions::clearFreezeSelected,
                )
                selectedRow != null -> RuntimeActionIcon(
                    R.drawable.ic_screen_lock_rotation,
                    R.string.memory_editor_freeze,
                    enabled = state.writeSupported && !state.busy,
                ) {
                    actions.freezeSelected(
                        MemoryEngineContract.FREEZE_LOCK,
                        selectedRow.valueText,
                        "",
                    )
                }
            }
            RuntimeActionIcon(
                R.drawable.ic_delete,
                R.string.memory_editor_remove,
                enabled = state.selected.isNotEmpty() && !state.busy,
            ) { actions.watchSelected(false) }
        }
        HorizontalDivider()

        if (state.watches.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.memory_editor_no_watch),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.watches, key = { it.id }) { row ->
                    RuntimeWatchRow(
                        row = row,
                        selected = row.id in state.selected,
                        onClick = {
                            if (row.id in state.selected && state.selected.size == 1) {
                                actions.clearSelection()
                            } else {
                                if (state.selected.size <= 1) actions.clearSelection()
                                actions.toggleSelection(row.id)
                            }
                        },
                        onLongClick = { actions.toggleSelection(row.id) },
                    )
                }
            }
        }
    }

    if (editDialog && selectedRow != null) {
        RuntimeEditDialog(
            value = selectedRow.valueText,
            initialType = selectedRow.type,
            types = listOf(selectedRow.type),
            writeSupported = state.writeSupported,
            actions = actions,
            onDismiss = { editDialog = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuntimeWatchRow(
    row: MemoryWatchRow,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label.ifBlank { runtimeTypeShort(row.type) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        row.valueText,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${row.addressText} · ${runtimeTypeShort(row.type)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        row.freezePaused -> Text(
                            stringResource(R.string.memory_editor_freeze_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        row.freezeMode >= 0 -> Icon(
                            painterResource(R.drawable.ic_screen_lock_rotation),
                            contentDescription = stringResource(R.string.memory_editor_freeze),
                        )
                        else -> RuntimeCandidateStatus(row.state, row.relocations)
                    }
                }
            }
        }
    }
    HorizontalDivider()
}


@Composable
private fun RuntimeSearchDialogBody(
    showKeypad: Boolean,
    controls: @Composable () -> Unit,
    keypad: @Composable () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val sideDock = landscape && showKeypad && maxWidth >= 480.dp
        if (sideDock) {
            val keypadWidth = (maxWidth * 0.42f).coerceIn(220.dp, 280.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { controls() }
                Box(modifier = Modifier.width(keypadWidth)) { keypad() }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                controls()
                if (showKeypad) keypad()
            }
        }
    }
}

@Composable
internal fun RuntimeKnownSearchDialog(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var second by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var activeField by remember { mutableStateOf(RuntimeInputField.FIRST) }
    var predicate by remember { mutableIntStateOf(MemoryEngineContract.PREDICATE_EQUAL) }
    var type by remember(state.runtimeToken) {
        mutableIntStateOf(
            state.requestedType.takeIf(MemoryEngineContract::isValueType) ?: MemoryEngineContract.TYPE_AUTO,
        )
    }
    var scope by remember(state.runtimeToken) { mutableIntStateOf(state.searchScope) }

    val expression = parseMemorySearchExpression(query.text)
    val spec = MemoryInputSpec.forType(type)
    val needsSecond = predicate == MemoryEngineContract.PREDICATE_BETWEEN &&
        expression !is MemorySearchExpression.Group
    val secondValid = !needsSecond || spec.isComplete(second.text)
    val singleValid = expression is MemorySearchExpression.Single && spec.isComplete(expression.value)
    val groupValid = expression is MemorySearchExpression.Group && type != MemoryEngineContract.TYPE_AUTO &&
        expression.values.all(spec::isComplete)
    val newSearchValid = (singleValid || groupValid) && secondValid
    val nextScanValid = state.sessionStage == MemorySessionStage.CANDIDATES &&
    type == state.requestedType && scope == state.searchScope && singleValid && secondValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_search_known_values)) },
        text = {
            RuntimeSearchDialogBody(
                showKeypad = true,
                controls = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RuntimePredicateMenu(
                            predicate = predicate,
                            onPredicate = { predicate = it },
                            modifier = Modifier.widthIn(min = 72.dp, max = 112.dp),
                        )
                        RuntimeSearchField(
                            label = stringResource(R.string.memory_editor_search_hint),
                            value = query,
                            active = activeField == RuntimeInputField.FIRST,
                            onClick = { activeField = RuntimeInputField.FIRST },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (needsSecond) {
                        RuntimeSearchField(
                            label = stringResource(R.string.memory_editor_max_value),
                            value = second,
                            active = activeField == RuntimeInputField.SECOND,
                            onClick = { activeField = RuntimeInputField.SECOND },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    RuntimeTypeMenu(type = type, onType = { type = it }, modifier = Modifier.fillMaxWidth())
                    RuntimeScopeMenu(scope = scope, onScope = { scope = it }, modifier = Modifier.fillMaxWidth())
                    RuntimeExpressionHint(expression = expression, type = type)
                },
                keypad = {
                    RuntimeSearchKeypad(
                        allowGroup = activeField == RuntimeInputField.FIRST,
                        onToken = { token ->
                            if (activeField == RuntimeInputField.FIRST) query = runtimeInsert(query, token)
                            else second = runtimeInsert(second, token)
                        },
                        onBackspace = {
                            if (activeField == RuntimeInputField.FIRST) query = runtimeBackspace(query)
                            else second = runtimeBackspace(second)
                        },
                        onMove = { delta ->
                            if (activeField == RuntimeInputField.FIRST) query = runtimeMove(query, delta)
                            else second = runtimeMove(second, delta)
                        },
                        onClear = {
                            if (activeField == RuntimeInputField.FIRST) query = TextFieldValue("", TextRange(0))
                            else second = TextFieldValue("", TextRange(0))
                        },
                    )
                },
            )
        },
        textScrollable = false,
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    enabled = newSearchValid && !state.busy,
                    onClick = {
                        when (val parsed = expression) {
                            is MemorySearchExpression.Single -> actions.startSearch(
                                parsed.value,
                                second.text,
                                type,
                                predicate,
                                false,
                                scope,
                            )
                            is MemorySearchExpression.Group -> actions.groupSearch(
                                IntArray(parsed.values.size) { type },
                                parsed.values.toTypedArray(),
                                parsed.maxDistance,
                                scope,
                            )
                            is MemorySearchExpression.Invalid -> Unit
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.memory_editor_new_search))
                }
                if (state.sessionStage == MemorySessionStage.CANDIDATES) {
                    Button(
                        enabled = nextScanValid && !state.busy,
                        onClick = {
                            val parsed = expression as MemorySearchExpression.Single
                            actions.nextScan(
                                parsed.value,
                                second.text,
                                predicate,
                                MemoryEngineContract.COMPARE_PREVIOUS,
                            )
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.memory_editor_next_scan))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun RuntimeUnknownSearchDialog(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    val hasUnknownSession = state.searchMode == MemorySearchMode.UNKNOWN &&
        state.sessionStage != MemorySessionStage.EMPTY
    var type by remember(state.runtimeToken) {
        mutableIntStateOf(
            state.requestedType.takeIf(MemoryEngineContract::isValueType) ?: MemoryEngineContract.TYPE_AUTO,
        )
    }
    var scope by remember(state.runtimeToken) { mutableIntStateOf(state.searchScope) }
    var predicate by remember { mutableIntStateOf(MemoryEngineContract.PREDICATE_CHANGED) }
    var first by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var second by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var activeField by remember { mutableStateOf(RuntimeInputField.FIRST) }
    val spec = MemoryInputSpec.forType(state.requestedType)
    val needsValue = runtimeRelativeNeedsValue(predicate)
    val needsSecond = predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE ||
        predicate == MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE
    val refineValid = !needsValue || (spec.isComplete(first.text) && (!needsSecond || spec.isComplete(second.text)))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_search_unknown_values)) },
        text = {
            RuntimeSearchDialogBody(
                showKeypad = hasUnknownSession && needsValue,
                controls = {
                    if (!hasUnknownSession) {
                        RuntimeTypeMenu(type = type, onType = { type = it }, modifier = Modifier.fillMaxWidth())
                        RuntimeScopeMenu(scope = scope, onScope = { scope = it }, modifier = Modifier.fillMaxWidth())
                    } else {
                        RuntimeRelativeMenu(predicate = predicate, onPredicate = { predicate = it })
                        RuntimeTypeMenu(
                            type = state.requestedType,
                            onType = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (needsValue) {
                            RuntimeSearchField(
                                label = stringResource(R.string.memory_editor_search_hint),
                                value = first,
                                active = activeField == RuntimeInputField.FIRST,
                                onClick = { activeField = RuntimeInputField.FIRST },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (needsSecond) {
                                RuntimeSearchField(
                                    label = stringResource(R.string.memory_editor_max_value),
                                    value = second,
                                    active = activeField == RuntimeInputField.SECOND,
                                    onClick = { activeField = RuntimeInputField.SECOND },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                },
                keypad = {
                    RuntimeSearchKeypad(
                        allowGroup = false,
                        onToken = { token ->
                            if (activeField == RuntimeInputField.FIRST) first = runtimeInsert(first, token)
                            else second = runtimeInsert(second, token)
                        },
                        onBackspace = {
                            if (activeField == RuntimeInputField.FIRST) first = runtimeBackspace(first)
                            else second = runtimeBackspace(second)
                        },
                        onMove = { delta ->
                            if (activeField == RuntimeInputField.FIRST) first = runtimeMove(first, delta)
                            else second = runtimeMove(second, delta)
                        },
                        onClear = {
                            if (activeField == RuntimeInputField.FIRST) first = TextFieldValue("", TextRange(0))
                            else second = TextFieldValue("", TextRange(0))
                        },
                    )
                },
            )
        },
        textScrollable = false,
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        actions.startSearch(
                            "",
                            "",
                            if (hasUnknownSession) state.requestedType else type,
                            MemoryEngineContract.PREDICATE_EQUAL,
                            true,
                            if (hasUnknownSession) state.searchScope else scope,
                        )
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.memory_editor_new_search))
                }
                if (hasUnknownSession) {
                    Button(
                        enabled = refineValid && !state.busy,
                        onClick = {
                            actions.nextScan(
                                first.text,
                                second.text,
                                predicate,
                                MemoryEngineContract.COMPARE_PREVIOUS,
                            )
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.memory_editor_next_scan))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun RuntimeEditDialog(
    value: String,
    initialType: Int,
    types: List<Int>,
    writeSupported: Boolean,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    val editableTypes = remember(types, initialType) {
        types.filter { MemoryEngineContract.isCandidateType(it) }
  .distinct()
  .ifEmpty { listOf(initialType) }
    }
    var type by remember(value, initialType, editableTypes) {
        mutableIntStateOf(initialType.takeIf { it in editableTypes } ?: editableTypes.first())
    }
    var replacement by remember(value, initialType, editableTypes) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    var freeze by remember { mutableStateOf(false) }
    val spec = MemoryInputSpec.forType(type)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_edit)) },
        text = {
  RuntimeSearchDialogBody(
      showKeypad = true,
      controls = {
          RuntimeEditTypeMenu(
              type = type,
              types = editableTypes,
              onType = { selectedType ->
                  if (selectedType != type) {
                      type = selectedType
                      val next = if (selectedType == initialType) value else ""
                      replacement = TextFieldValue(next, TextRange(next.length))
                  }
              },
          )
          RuntimeSearchField(
              label = stringResource(
                  if (type == initialType) R.string.memory_editor_current_value
                  else R.string.memory_editor_replacement,
              ),
              value = replacement,
              active = true,
              onClick = {},
              modifier = Modifier.fillMaxWidth(),
          )
          Row(verticalAlignment = Alignment.CenterVertically) {
              Checkbox(checked = freeze, onCheckedChange = { freeze = it })
              Text(stringResource(R.string.memory_editor_freeze))
          }
          if (freeze) {
              Text(
                  stringResource(R.string.memory_editor_freeze_after_edit_help),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          }
      },
      keypad = {
          RuntimeSearchKeypad(
              allowGroup = false,
              valueSpec = spec,
              onToken = { replacement = runtimeInsertValidated(replacement, it, spec) },
              onBackspace = { replacement = runtimeBackspace(replacement) },
              onMove = { replacement = runtimeMove(replacement, it) },
              onClear = { replacement = TextFieldValue("", TextRange(0)) },
          )
      },
  )
        },
        textScrollable = false,
        confirmButton = {
  Button(
      enabled = writeSupported && spec.isComplete(replacement.text),
      onClick = {
          actions.editSelectedWithOptions(
              replacement.text,
              type,
              addToWatch = false,
              freezeAfter = freeze,
          )
          onDismiss()
      },
  ) { Text(stringResource(R.string.memory_editor_save)) }
        },
        dismissButton = {
  TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
@Composable
private fun RuntimeInspectorTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    modifier: Modifier = Modifier,
) {
    var editCell by remember { mutableStateOf<MemoryInspectorCell?>(null) }
    when {
        state.inspectorLoading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.memory_editor_inspector_loading))
            }
        }
        state.inspector == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.memory_editor_inspector_empty),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            val snapshot = state.inspector
            val cells = remember(snapshot) { buildInspectorCells(snapshot) }
            Column(modifier = modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            snapshot.label.ifBlank { runtimeTypeShort(snapshot.type) },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${stringResource(R.string.memory_editor_anchor)} 0x${snapshot.anchorAddress.toString(16).uppercase(Locale.ROOT)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RuntimeActionIcon(
                        R.drawable.ic_restart_alt,
                        R.string.memory_editor_refresh_snapshot,
                        enabled = !state.busy,
                    ) {
                        actions.inspectCandidate(snapshot.candidateId)
                    }
                }
                HorizontalDivider()
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val anchorIndex = cells.indexOfFirst { it.offset == 0 }.coerceAtLeast(0)
                    val listState = rememberLazyListState()
                    LaunchedEffect(snapshot.anchorAddress, anchorIndex) {
                        listState.scrollToItem(anchorIndex)
                    }
                    val verticalPadding = (maxHeight / 2 - 24.dp).coerceAtLeast(0.dp)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = verticalPadding),
                    ) {
                        items(cells, key = { it.offset }) { cell ->
                        Surface(
                            color = if (cell.offset == 0) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
                            } else Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        enabled = state.writeSupported && !state.busy,
                                        onClick = { editCell = cell },
                                        onLongClick = { editCell = cell },
                                    )
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (cell.offset >= 0) "+${cell.offset}" else cell.offset.toString(),
                                    modifier = Modifier.widthIn(min = 48.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "0x${cell.address.toString(16).uppercase(Locale.ROOT)}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    cell.value,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                            HorizontalDivider()
                        }
                    }
                }
            }

            editCell?.let { cell ->
                RuntimeInspectorEditDialog(
                    snapshot = snapshot,
                    cell = cell,
                    actions = actions,
                    onDismiss = { editCell = null },
                )
            }
        }
    }
}

internal data class MemoryInspectorCell(
    val offset: Int,
    val address: Long,
    val bits: Long,
    val value: String,
)

@Composable
private fun RuntimeInspectorEditDialog(
    snapshot: MemoryInspectorSnapshot,
    cell: MemoryInspectorCell,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    val spec = MemoryInputSpec.forType(snapshot.type)
    var value by remember(cell) {
        mutableStateOf(TextFieldValue(cell.value, TextRange(cell.value.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("0x${cell.address.toString(16).uppercase(Locale.ROOT)}") },
        text = {
  RuntimeSearchDialogBody(
      showKeypad = true,
      controls = {
          RuntimeSearchField(
              label = stringResource(R.string.memory_editor_current_value),
              value = value,
              active = true,
              onClick = {},
              modifier = Modifier.fillMaxWidth(),
          )
      },
      keypad = {
          RuntimeSearchKeypad(
              allowGroup = false,
              valueSpec = spec,
              onToken = { value = runtimeInsertValidated(value, it, spec) },
              onBackspace = { value = runtimeBackspace(value) },
              onMove = { value = runtimeMove(value, it) },
              onClear = { value = TextFieldValue("", TextRange(0)) },
          )
      },
  )
        },
        textScrollable = false,
        confirmButton = {
  Button(
      enabled = spec.isComplete(value.text),
      onClick = {
          actions.editInspectorValue(
              snapshot.candidateId,
              cell.offset,
              snapshot.type,
              cell.bits,
              value.text,
          )
          onDismiss()
      },
  ) { Text(stringResource(R.string.memory_editor_apply)) }
        },
        dismissButton = {
  TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
@Composable
private fun RuntimeSearchField(
    label: String,
    value: TextFieldValue,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.sizeIn(minHeight = 52.dp)) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            val cursor = value.selection.end.coerceIn(0, value.text.length)
            val visibleValue = when {
                active && value.text.isEmpty() -> "▏"
                active -> value.text.substring(0, cursor) + "▏" + value.text.substring(cursor)
                value.text.isEmpty() -> "—"
                else -> value.text
            }
            Text(
                visibleValue,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RuntimeSearchKeypad(
    allowGroup: Boolean,
    valueSpec: MemoryInputSpec? = null,
    onToken: (String) -> Unit,
    onBackspace: () -> Unit,
    onMove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(verticalArrangement = Arrangement.spacedBy(if (landscape) 2.dp else 4.dp)) {
        if (landscape) {
            RuntimeKeypadRow {
                RuntimeKeypadButton("1") { onToken("1") }
                RuntimeKeypadButton("2") { onToken("2") }
                RuntimeKeypadButton("3") { onToken("3") }
                RuntimeKeypadButton("⌫", onClick = onBackspace)
                RuntimeKeypadButton("←") { onMove(-1) }
            }
            RuntimeKeypadRow {
                RuntimeKeypadButton("4") { onToken("4") }
                RuntimeKeypadButton("5") { onToken("5") }
                RuntimeKeypadButton("6") { onToken("6") }
                RuntimeKeypadButton("→") { onMove(1) }
                RuntimeKeypadButton("-", enabled = valueSpec?.signed ?: true) { onToken("-") }
            }
            RuntimeKeypadRow {
                RuntimeKeypadButton("7") { onToken("7") }
                RuntimeKeypadButton("8") { onToken("8") }
                RuntimeKeypadButton("9") { onToken("9") }
                RuntimeKeypadButton(".", enabled = valueSpec?.decimal ?: true) { onToken(".") }
                RuntimeKeypadButton("E", enabled = valueSpec?.exponent ?: true) { onToken("E") }
            }
            RuntimeKeypadRow {
                RuntimeKeypadButton("0") { onToken("0") }
                RuntimeKeypadButton(";", enabled = allowGroup) { onToken(";") }
                RuntimeKeypadButton(":", enabled = allowGroup) { onToken(":") }
                RuntimeKeypadButton(
                    stringResource(R.string.memory_editor_keypad_clear),
                    weight = 2f,
                    onClick = onClear,
                )
            }
        } else {
            RuntimeKeypadRow {
                RuntimeKeypadButton("1") { onToken("1") }
                RuntimeKeypadButton("2") { onToken("2") }
                RuntimeKeypadButton("3") { onToken("3") }
                RuntimeKeypadButton("⌫", onClick = onBackspace)
            }
            RuntimeKeypadRow {
                RuntimeKeypadButton("4") { onToken("4") }
                RuntimeKeypadButton("5") { onToken("5") }
                RuntimeKeypadButton("6") { onToken("6") }
                RuntimeKeypadButton("←") { onMove(-1) }
            }
            RuntimeKeypadRow {
                RuntimeKeypadButton("7") { onToken("7") }
                RuntimeKeypadButton("8") { onToken("8") }
                RuntimeKeypadButton("9") { onToken("9") }
                RuntimeKeypadButton("→") { onMove(1) }
            }
            RuntimeKeypadRow {
                RuntimeKeypadButton("-", enabled = valueSpec?.signed ?: true) { onToken("-") }
                RuntimeKeypadButton("0") { onToken("0") }
                RuntimeKeypadButton(".", enabled = valueSpec?.decimal ?: true) { onToken(".") }
                RuntimeKeypadButton("E", enabled = valueSpec?.exponent ?: true) { onToken("E") }
            }
            RuntimeKeypadRow {
                RuntimeKeypadButton(";", enabled = allowGroup) { onToken(";") }
                RuntimeKeypadButton(":", enabled = allowGroup) { onToken(":") }
                RuntimeKeypadButton(
                    stringResource(R.string.memory_editor_keypad_clear),
                    weight = 2f,
                    onClick = onClear,
                )
            }
        }
    }
}

@Composable
private fun RuntimeKeypadRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RuntimeKeypadButton(
    label: String,
    enabled: Boolean = true,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(weight).sizeIn(minHeight = if (landscape) 40.dp else 42.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun RuntimePredicateMenu(
    predicate: Int,
    onPredicate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    RuntimeChoiceMenu(
        value = predicate,
        values = intArrayOf(
            MemoryEngineContract.PREDICATE_EQUAL,
            MemoryEngineContract.PREDICATE_NOT_EQUAL,
            MemoryEngineContract.PREDICATE_GREATER,
            MemoryEngineContract.PREDICATE_LESS,
            MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL,
            MemoryEngineContract.PREDICATE_LESS_OR_EQUAL,
            MemoryEngineContract.PREDICATE_BETWEEN,
        ),
        label = { runtimePredicateName(it) },
        onChange = onPredicate,
        modifier = modifier,
    )
}

@Composable
private fun RuntimeRelativeMenu(predicate: Int, onPredicate: (Int) -> Unit) {
    RuntimeChoiceMenu(
        value = predicate,
        values = intArrayOf(
            MemoryEngineContract.PREDICATE_CHANGED,
            MemoryEngineContract.PREDICATE_UNCHANGED,
            MemoryEngineContract.PREDICATE_INCREASED,
            MemoryEngineContract.PREDICATE_DECREASED,
            MemoryEngineContract.PREDICATE_INCREASED_BY,
            MemoryEngineContract.PREDICATE_DECREASED_BY,
            MemoryEngineContract.PREDICATE_CHANGED_BY,
            MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE,
            MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE,
        ),
        label = { runtimePredicateName(it) },
        onChange = onPredicate,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RuntimeTypeMenu(
    type: Int,
    onType: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RuntimeChoiceMenu(
        value = type,
        values = intArrayOf(
            MemoryEngineContract.TYPE_AUTO,
            MemoryEngineContract.TYPE_BYTE,
            MemoryEngineContract.TYPE_SHORT,
            MemoryEngineContract.TYPE_CHAR,
            MemoryEngineContract.TYPE_INT,
            MemoryEngineContract.TYPE_LONG,
            MemoryEngineContract.TYPE_FLOAT,
            MemoryEngineContract.TYPE_DOUBLE,
        ),
        label = { runtimeTypeName(it) },
        onChange = onType,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
private fun RuntimeEditTypeMenu(
    type: Int,
    types: List<Int>,
    onType: (Int) -> Unit,
) {
    RuntimeChoiceMenu(
        value = type,
        values = types.toIntArray(),
        label = { runtimeTypeName(it) },
        onChange = onType,
        modifier = Modifier.fillMaxWidth(),
        enabled = types.size > 1,
    )
}

@Composable
private fun RuntimeScopeMenu(
    scope: Int,
    onScope: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    RuntimeChoiceMenu(
        value = scope,
        values = intArrayOf(MemoryEngineContract.SCOPE_JAVA_FAST, MemoryEngineContract.SCOPE_JAVA_THOROUGH),
        label = {
            stringResource(
                if (it == MemoryEngineContract.SCOPE_JAVA_FAST) R.string.memory_editor_scope_fast
                else R.string.memory_editor_scope_thorough,
            )
        },
        onChange = onScope,
        modifier = modifier,
    )
}

@Composable
private fun RuntimeChoiceMenu(
    value: Int,
    values: IntArray,
    label: @Composable (Int) -> String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        ) {
            Text(label(value), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { item ->
                DropdownMenuItem(
                    text = { Text(label(item)) },
                    onClick = {
                        expanded = false
                        onChange(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun RuntimeExpressionHint(expression: MemorySearchExpression, type: Int) {
    val text = when (expression) {
        is MemorySearchExpression.Single -> stringResource(R.string.memory_editor_expression_single_hint)
        is MemorySearchExpression.Group -> if (type == MemoryEngineContract.TYPE_AUTO) {
            stringResource(R.string.memory_editor_expression_group_needs_type)
        } else {
            stringResource(
                R.string.memory_editor_expression_group_hint,
                expression.values.size,
                expression.maxDistance,
            )
        }
        is MemorySearchExpression.Invalid -> when (expression.reason) {
            MemorySearchExpression.Reason.ORDERED_GROUP_UNSUPPORTED ->
                stringResource(R.string.memory_editor_expression_ordered_group_unsupported)
            else -> stringResource(R.string.memory_editor_expression_query_help)
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RuntimePager(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    if (state.resultCount <= MemoryEditorComposeController.PAGE_SIZE) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = actions::previousPage, enabled = state.pageOffset > 0) {
            Text("‹")
        }
        Text(
            "${state.pageOffset + 1}–${minOf(state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE, state.resultCount)}",
            fontFamily = FontFamily.Monospace,
        )
        TextButton(
            onClick = actions::nextPage,
            enabled = state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE < state.resultCount,
        ) {
            Text("›")
        }
    }
}

@Composable
private fun RuntimeActionIcon(
    icon: Int,
    description: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = stringResource(description))
    }
}

@Composable
private fun RuntimeCandidateStatus(state: Int, relocations: Int) {
    val text = runtimeCandidateState(state, relocations) ?: return
    val color = when {
        state == MemoryEngineContract.CANDIDATE_STABLE && relocations > 0 -> MaterialTheme.colorScheme.primary
        state == MemoryEngineContract.CANDIDATE_RELOCATING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

private fun runtimeInsert(value: TextFieldValue, token: String): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val text = value.text.replaceRange(start, end, token).take(96)
    return TextFieldValue(text, TextRange((start + token.length).coerceAtMost(text.length)))
}

private fun runtimeInsertValidated(
    value: TextFieldValue,
    token: String,
    spec: MemoryInputSpec,
): TextFieldValue {
    val updated = runtimeInsert(value, token)
    return if (spec.acceptsPartial(updated.text)) updated else value
}

private fun runtimeBackspace(value: TextFieldValue): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    if (start != end) return TextFieldValue(value.text.removeRange(start, end), TextRange(start))
    if (start == 0) return value
    return TextFieldValue(value.text.removeRange(start - 1, start), TextRange(start - 1))
}

private fun runtimeMove(value: TextFieldValue, delta: Int): TextFieldValue {
    val current = if (delta < 0) minOf(value.selection.start, value.selection.end)
    else maxOf(value.selection.start, value.selection.end)
    return value.copy(selection = TextRange((current + delta).coerceIn(0, value.text.length)))
}

private fun runtimeRelativeNeedsValue(predicate: Int): Boolean = when (predicate) {
    MemoryEngineContract.PREDICATE_CHANGED,
    MemoryEngineContract.PREDICATE_UNCHANGED,
    MemoryEngineContract.PREDICATE_INCREASED,
    MemoryEngineContract.PREDICATE_DECREASED -> false
    else -> true
}

@Composable
private fun runtimePredicateName(predicate: Int): String = when (predicate) {
    MemoryEngineContract.PREDICATE_EQUAL -> "="
    MemoryEngineContract.PREDICATE_NOT_EQUAL -> "≠"
    MemoryEngineContract.PREDICATE_GREATER -> ">"
    MemoryEngineContract.PREDICATE_LESS -> "<"
    MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL -> "≥"
    MemoryEngineContract.PREDICATE_LESS_OR_EQUAL -> "≤"
    MemoryEngineContract.PREDICATE_BETWEEN -> stringResource(R.string.memory_editor_predicate_between)
    MemoryEngineContract.PREDICATE_CHANGED -> stringResource(R.string.memory_editor_predicate_changed)
    MemoryEngineContract.PREDICATE_UNCHANGED -> stringResource(R.string.memory_editor_predicate_unchanged)
    MemoryEngineContract.PREDICATE_INCREASED -> stringResource(R.string.memory_editor_predicate_increased)
    MemoryEngineContract.PREDICATE_DECREASED -> stringResource(R.string.memory_editor_predicate_decreased)
    MemoryEngineContract.PREDICATE_INCREASED_BY -> stringResource(R.string.memory_editor_predicate_increased_by)
    MemoryEngineContract.PREDICATE_DECREASED_BY -> stringResource(R.string.memory_editor_predicate_decreased_by)
    MemoryEngineContract.PREDICATE_CHANGED_BY -> stringResource(R.string.memory_editor_predicate_changed_by)
    MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE -> stringResource(R.string.memory_editor_predicate_increased_range)
    MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE -> stringResource(R.string.memory_editor_predicate_decreased_range)
    else -> "?"
}

private fun runtimeTypeName(type: Int): String = when (type) {
    MemoryEngineContract.TYPE_AUTO -> "Auto"
    MemoryEngineContract.TYPE_BYTE -> "Int8"
    MemoryEngineContract.TYPE_SHORT -> "Int16"
    MemoryEngineContract.TYPE_CHAR -> "UInt16"
    MemoryEngineContract.TYPE_INT -> "Int32"
    MemoryEngineContract.TYPE_LONG -> "Int64"
    MemoryEngineContract.TYPE_FLOAT -> "Float32"
    MemoryEngineContract.TYPE_DOUBLE -> "Float64"
    else -> "?"
}

private fun runtimeTypeShort(type: Int): String = runtimeTypeName(type)

@Composable
private fun runtimeCandidateState(state: Int, relocations: Int): String? = when (state) {
    MemoryEngineContract.CANDIDATE_STABLE -> if (relocations > 0) {
        stringResource(R.string.memory_editor_candidate_moved)
    } else null
    MemoryEngineContract.CANDIDATE_RELOCATING -> stringResource(R.string.memory_editor_candidate_relocating)
    MemoryEngineContract.CANDIDATE_AMBIGUOUS -> stringResource(R.string.memory_editor_candidate_ambiguous)
    else -> stringResource(R.string.memory_editor_candidate_lost)
}

internal fun buildInspectorCells(snapshot: MemoryInspectorSnapshot): List<MemoryInspectorCell> {
    val width = inspectorTypeWidth(snapshot.type)
    val anchorIndex = (snapshot.anchorAddress - snapshot.startAddress).toInt()
    if (width <= 0 || anchorIndex !in snapshot.bytes.indices) return emptyList()
    val firstOffset = -(anchorIndex / width) * width
    val result = ArrayList<MemoryInspectorCell>()
    var offset = firstOffset
    while (true) {
        val index = anchorIndex + offset
        if (index < 0) {
            offset += width
            continue
        }
        if (index + width > snapshot.bytes.size) break
        val bits = inspectorReadBits(snapshot.bytes, index, width)
        result += MemoryInspectorCell(
            offset = offset,
            address = snapshot.anchorAddress + offset,
            bits = bits,
            value = inspectorFormatBits(snapshot.type, bits),
        )
        offset += width
    }
    return result
}

internal fun inspectorTypeWidth(type: Int): Int = when (type) {
    MemoryEngineContract.TYPE_BYTE -> 1
    MemoryEngineContract.TYPE_SHORT,
    MemoryEngineContract.TYPE_CHAR -> 2
    MemoryEngineContract.TYPE_INT,
    MemoryEngineContract.TYPE_FLOAT -> 4
    MemoryEngineContract.TYPE_LONG,
    MemoryEngineContract.TYPE_DOUBLE -> 8
    else -> 0
}

internal fun inspectorReadBits(bytes: ByteArray, index: Int, width: Int): Long {
    val buffer = ByteBuffer.wrap(bytes, index, width).order(ByteOrder.LITTLE_ENDIAN)
    return when (width) {
        1 -> (buffer.get().toInt() and 0xff).toLong()
        2 -> (buffer.short.toInt() and 0xffff).toLong()
        4 -> buffer.int.toLong() and 0xffffffffL
        8 -> buffer.long
        else -> 0L
    }
}

internal fun inspectorFormatBits(type: Int, bits: Long): String = when (type) {
    MemoryEngineContract.TYPE_BYTE -> bits.toByte().toString()
    MemoryEngineContract.TYPE_SHORT -> bits.toShort().toString()
    MemoryEngineContract.TYPE_CHAR -> (bits and 0xffffL).toString()
    MemoryEngineContract.TYPE_INT -> bits.toInt().toString()
    MemoryEngineContract.TYPE_LONG -> bits.toString()
    MemoryEngineContract.TYPE_FLOAT -> Float.intBitsToFloat(bits.toInt()).toString()
    MemoryEngineContract.TYPE_DOUBLE -> Double.longBitsToDouble(bits).toString()
    else -> bits.toString()
}

private fun compactCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
