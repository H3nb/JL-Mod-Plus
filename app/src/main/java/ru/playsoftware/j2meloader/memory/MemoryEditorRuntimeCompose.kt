/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.playsoftware.j2meloader.memory

import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.AdaptiveAlertDialog as AlertDialog
import ru.playsoftware.j2meloader.ui.availableWindowHeightDp
import ru.playsoftware.j2meloader.ui.availableWindowWidthDp
import ru.playsoftware.j2meloader.ui.jlModPlusFilterChipColors
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
    var peekingUnderlay by remember(state.runtimeToken) { mutableStateOf(false) }
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
            actions.inspectCandidate(selectedId, watchAnchor = state.watchTab)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .alpha(if (peekingUnderlay) 0f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        val landscape = maxWidth > maxHeight
        val panelModifier = if (landscape) {
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)
        } else {
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
                .widthIn(max = 720.dp)
        }
        Surface(
            modifier = panelModifier,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                alpha = if (peekingUnderlay) 0.42f else 0.84f,
            ),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            if (landscape) {
                Column(modifier = Modifier.fillMaxSize()) {
                    RuntimeMemoryHeader(actions)
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        RuntimeMemoryTabRail(
                            tab = tab,
                            results = state.resultCount,
                            watches = state.watches.size,
                            inspectorEnabled = selectedId != null,
                            onTab = { tab = it },
                            modifier = Modifier.width(176.dp).fillMaxHeight(),
                        )
                        VerticalDivider()
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (state.busy) RuntimeOperationStrip(state, actions)
                            state.message?.takeIf(String::isNotBlank)?.let {
                                RuntimeMessage(it, state.messageIsError)
                            }
                            RuntimeMemoryTabContent(
                                tab = tab,
                                state = state,
                                actions = actions,
                                onPeekUnderlayChanged = { peekingUnderlay = it },
                                onInspectSelected = { tab = RuntimeMemoryTab.INSPECTOR },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    RuntimeMemoryHeader(actions)
                    RuntimeMemoryTabs(
                        tab = tab,
                        results = state.resultCount,
                        watches = state.watches.size,
                        inspectorEnabled = selectedId != null,
                        onTab = { tab = it },
                    )
                    if (state.busy) RuntimeOperationStrip(state, actions)
                    state.message?.takeIf(String::isNotBlank)?.let {
                        RuntimeMessage(it, state.messageIsError)
                    }
                    RuntimeMemoryTabContent(
                        tab = tab,
                        state = state,
                        actions = actions,
                        onPeekUnderlayChanged = { peekingUnderlay = it },
                        onInspectSelected = { tab = RuntimeMemoryTab.INSPECTOR },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeMemoryTabContent(
    tab: RuntimeMemoryTab,
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onPeekUnderlayChanged: (Boolean) -> Unit,
    onInspectSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        RuntimeMemoryTab.SEARCH_RESULTS -> RuntimeSearchResultsTab(
            state = state.copy(watchTab = false),
            actions = actions,
            onPeekUnderlayChanged = onPeekUnderlayChanged,
            onInspectSelected = onInspectSelected,
            modifier = modifier,
        )
        RuntimeMemoryTab.WATCH -> RuntimeWatchTab(
            state = state.copy(watchTab = true),
            actions = actions,
            onPeekUnderlayChanged = onPeekUnderlayChanged,
            modifier = modifier,
        )
        RuntimeMemoryTab.INSPECTOR -> RuntimeInspectorTab(
            state = state,
            actions = actions,
            onPeekUnderlayChanged = onPeekUnderlayChanged,
            modifier = modifier,
        )
    }
}

@Composable
private fun RuntimeMemoryTabRail(
    tab: RuntimeMemoryTab,
    results: Long,
    watches: Int,
    inspectorEnabled: Boolean,
    onTab: (RuntimeMemoryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = tab == RuntimeMemoryTab.SEARCH_RESULTS,
            onClick = { onTab(RuntimeMemoryTab.SEARCH_RESULTS) },
            colors = jlModPlusFilterChipColors(),
            leadingIcon = { Icon(painterResource(R.drawable.ic_memory_editor_search), null) },
            label = {
                Text(
                    if (results > 0L) "${stringResource(R.string.memory_editor_search_tab)} · ${compactCount(results)}"
                    else stringResource(R.string.memory_editor_search_tab),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        FilterChip(
            selected = tab == RuntimeMemoryTab.WATCH,
            onClick = { onTab(RuntimeMemoryTab.WATCH) },
            colors = jlModPlusFilterChipColors(),
            leadingIcon = { Icon(painterResource(R.drawable.ic_memory_editor_watch), null) },
            label = {
                Text(
                    if (watches > 0) "${stringResource(R.string.memory_editor_watch)} · $watches"
                    else stringResource(R.string.memory_editor_watch),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        FilterChip(
            selected = tab == RuntimeMemoryTab.INSPECTOR,
            onClick = { onTab(RuntimeMemoryTab.INSPECTOR) },
            enabled = inspectorEnabled,
            colors = jlModPlusFilterChipColors(),
            leadingIcon = { Icon(painterResource(R.drawable.ic_memory_editor_inspector), null) },
            label = {
                Text(
                    stringResource(R.string.memory_editor_inspector),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RuntimeMemoryHeader(
    actions: MemoryEditorActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
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
    inspectorEnabled: Boolean,
    onTab: (RuntimeMemoryTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = tab == RuntimeMemoryTab.SEARCH_RESULTS,
            onClick = { onTab(RuntimeMemoryTab.SEARCH_RESULTS) },
            colors = jlModPlusFilterChipColors(),
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
            colors = jlModPlusFilterChipColors(),
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
            enabled = inspectorEnabled,
            colors = jlModPlusFilterChipColors(),
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
private fun RuntimeMessage(message: String, isError: Boolean) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = container.copy(alpha = 0.82f)) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
    }
}

@Composable
private fun RuntimeSearchResultsTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onPeekUnderlayChanged: (Boolean) -> Unit,
    onInspectSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var knownDialog by remember { mutableStateOf(false) }
    var unknownDialog by remember { mutableStateOf(false) }
    var editTargets by remember { mutableStateOf<List<MemoryEditTarget>?>(null) }
    var editRevision by remember { mutableStateOf(0L) }
    val baselineOnly = state.sessionStage == MemorySessionStage.UNKNOWN_BASELINE
    val selectedRows = state.results.filter { it.id in state.selected }
    val selectedWriteSupported = selectedRows.isNotEmpty() && selectedRows.all { row ->
        if (ManagedJavaMemoryIds.isManaged(row.id)) state.managedWriteSupported
        else state.writeSupported
    }
    val allVisibleSelected = state.results.isNotEmpty() &&
        state.results.all { it.id in state.selected }

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
                enabled = !state.busy && (state.supported || state.managedSupported),
                onClick = { unknownDialog = true },
            )
            RuntimeActionIcon(
                icon = R.drawable.ic_memory_editor_watch_add,
                description = R.string.memory_editor_add_to_watch,
                enabled = state.selected.isNotEmpty() && !state.busy,
                onClick = { actions.watchSelected(true) },
            )
            RuntimeActionIcon(
                icon = R.drawable.ic_edit,
                description = R.string.memory_editor_edit,
                enabled = selectedWriteSupported && !state.busy && !baselineOnly,
                onClick = {
                    editTargets = selectedRows.map { row ->
                        MemoryEditTarget(row.id, row.primaryType, row.valueText,
                            if (ManagedJavaMemoryIds.isManaged(row.id)) {
                                MemoryEngineContract.BACKEND_MANAGED
                            } else MemoryEngineContract.BACKEND_RAW,
                            row.aliasTypes)
                    }
                    editRevision = state.managedRevision
                },
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
                enabled = selectedRows.size == 1 && !state.busy,
                onClick = onInspectSelected,
            )
            RuntimeActionIcon(
                icon = R.drawable.ic_delete,
                description = R.string.memory_editor_remove,
                enabled = state.selected.isNotEmpty() && !state.busy && !baselineOnly,
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
                    if (state.sessionStage == MemorySessionStage.UNKNOWN_BASELINE) {
                        R.plurals.memory_editor_candidates
                    } else {
                        R.plurals.memory_editor_results
                    },
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
                if (state.sessionStage == MemorySessionStage.UNKNOWN_BASELINE) {
                    val baselineCount = managedBaselineCountForPresentation(state) ?: state.resultCount
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.memory_editor_baseline_captured_count,
                                baselineCount,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.memory_editor_baseline_instruction),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.memory_editor_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.results, key = { it.id }) { row ->
                    RuntimeResultRow(
                        row = row,
                        selected = row.id in state.selected,
                        onToggle = { actions.toggleSelection(row.id) },
                        onClick = {
                            if (baselineOnly) {
                                actions.toggleSelection(row.id)
                            } else {
                                editTargets = listOf(
                                    MemoryEditTarget(row.id, row.primaryType, row.valueText,
                                        if (ManagedJavaMemoryIds.isManaged(row.id)) {
                                            MemoryEngineContract.BACKEND_MANAGED
                                        } else MemoryEngineContract.BACKEND_RAW,
                                        row.aliasTypes),
                                )
                                editRevision = state.managedRevision
                            }
                        },
                    )
                }
            }
        }
        RuntimePager(state, actions)
    }

    if (knownDialog) {
        RuntimeKnownSearchDialog(
            state = state,
            actions = actions,
            onPeekUnderlayChanged = onPeekUnderlayChanged,
            onDismiss = {
                onPeekUnderlayChanged(false)
                knownDialog = false
            },
        )
    }
    if (unknownDialog) {
        RuntimeUnknownSearchDialog(
            state = state,
            actions = actions,
            onPeekUnderlayChanged = onPeekUnderlayChanged,
            onDismiss = {
                onPeekUnderlayChanged(false)
                unknownDialog = false
            },
        )
    }
    val currentEditTargets = editTargets
    if (currentEditTargets != null) {
        RuntimeEditDialog(
            targets = currentEditTargets,
            writeSupported = currentEditTargets.all { it.supportsWrite(state) },
            onPeekUnderlayChanged = onPeekUnderlayChanged,
            onDismiss = {
                onPeekUnderlayChanged(false)
                editTargets = null
            },
            onApply = { value, type, freeze ->
                actions.editTargetsWithOptions(
                    currentEditTargets, editRevision, value, type, false, freeze,
                )
                onPeekUnderlayChanged(false)
                editTargets = null
            },
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
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
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
    onPeekUnderlayChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editTargets by remember { mutableStateOf<List<MemoryEditTarget>?>(null) }
    val selectedRows = state.watches.filter { it.id in state.selected }
    val selectedWriteSupported = selectedRows.isNotEmpty() && selectedRows.all { row ->
        if (row.backend == MemoryEngineContract.BACKEND_MANAGED) state.managedWriteSupported
        else state.writeSupported
    }

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
                enabled = selectedWriteSupported && !state.busy,
            ) {
                editTargets = selectedRows.map { row ->
                    MemoryEditTarget(row.id, row.type, row.valueText,
                        row.backend, listOf(row.type), watch = true)
                }
            }
            RuntimeActionIcon(
                R.drawable.ic_restart_alt,
                R.string.memory_editor_refresh,
                enabled = state.watches.isNotEmpty() && !state.busy,
                onClick = actions::refresh,
            )
            when {
                selectedRows.size == 1 && selectedRows.single().freezeMode >= 0 -> RuntimeActionIcon(
                    R.drawable.ic_screen_lock_rotation,
                    R.string.memory_editor_unfreeze,
                    enabled = !state.busy,
                    onClick = actions::clearFreezeSelected,
                )
                selectedRows.size == 1 -> RuntimeActionIcon(
                    R.drawable.ic_screen_lock_rotation,
                    R.string.memory_editor_freeze,
                    enabled = selectedWriteSupported && !state.busy,
                ) {
                    actions.freezeSelected(
                        MemoryEngineContract.FREEZE_LOCK,
                        selectedRows.single().valueText,
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
                        onToggle = { actions.toggleSelection(row.id) },
                        onClick = {
                            editTargets = listOf(
                                MemoryEditTarget(row.id, row.type, row.valueText,
                                    row.backend, listOf(row.type), watch = true),
                            )
                        },
                    )
                }
            }
        }
    }

    val currentEditTargets = editTargets
    if (currentEditTargets != null) {
        RuntimeEditDialog(
            targets = currentEditTargets,
            writeSupported = currentEditTargets.all { it.supportsWrite(state) },
            onPeekUnderlayChanged = onPeekUnderlayChanged,
            onDismiss = {
                onPeekUnderlayChanged(false)
                editTargets = null
            },
            onApply = { value, type, freeze ->
                actions.editTargetsWithOptions(
                    currentEditTargets, 0L, value, type, false, freeze,
                )
                onPeekUnderlayChanged(false)
                editTargets = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuntimeWatchRow(
    row: MemoryWatchRow,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val selectDescription = stringResource(R.string.memory_editor_select_result_value)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics {
                    contentDescription = selectDescription
                },
            )
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
    controls: @Composable (sideDock: Boolean) -> Unit,
    keypad: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val landscape = availableWindowWidthDp() > availableWindowHeightDp()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val sideDock = landscape && showKeypad && maxWidth >= 480.dp
        if (sideDock) {
            val keypadWidth = (maxWidth * 0.42f).coerceIn(220.dp, 280.dp)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        controls(true)
                        supportingContent?.invoke()
                    }
                    Box(modifier = Modifier.width(keypadWidth)) { keypad() }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                controls(false)
                supportingContent?.invoke()
                if (showKeypad) keypad()
            }
        }
    }
}

@Composable
internal fun RuntimeKnownSearchDialog(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onPeekUnderlayChanged: (Boolean) -> Unit,
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
    var scope by remember(
        state.runtimeToken,
        state.sessionStage,
        state.searchScope,
        state.knownScopePreference,
    ) {
        mutableIntStateOf(
            if (state.sessionStage == MemorySessionStage.EMPTY) state.knownScopePreference
            else state.searchScope,
        )
    }
    var peekingUnderlay by remember { mutableStateOf(false) }

    val managedScope = scope == MemoryEngineContract.SCOPE_MANAGED_JAVA
    val expression = parseMemorySearchExpression(query.text)
    val relative = predicate >= MemoryEngineContract.PREDICATE_CHANGED
    val spec = if (relative) MemoryInputSpec.relativeMagnitudeForType(type)
    else MemoryInputSpec.forType(type)
    LaunchedEffect(expression, type) {
        if (expression is MemorySearchExpression.Group && type == MemoryEngineContract.TYPE_AUTO) {
            inferMemoryGroupType(expression.values)?.let { type = it }
        }
    }
    val needsSecond = (predicate == MemoryEngineContract.PREDICATE_BETWEEN && !relative &&
        expression !is MemorySearchExpression.Group) ||
        predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE ||
        predicate == MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE
    val firstValid = if (relative && !runtimeRelativeNeedsValue(predicate)) {
        true
    } else {
        spec.isComplete(query.text)
    }
    val secondValid = !needsSecond || spec.isComplete(second.text)
    val singleValid = expression is MemorySearchExpression.Single && firstValid
    val groupValid = expression is MemorySearchExpression.Group && type != MemoryEngineContract.TYPE_AUTO &&
        expression.values.all(spec::isComplete)
    val newSearchValid = !relative && if (managedScope) singleValid && secondValid &&
        MemoryEngineContract.isValueType(type) &&
        predicate in MemoryEngineContract.PREDICATE_EQUAL..MemoryEngineContract.PREDICATE_BETWEEN
    else (singleValid || groupValid) && secondValid
    val nextScanValid = state.sessionStage != MemorySessionStage.EMPTY &&
        type in (MemoryEngineContract.TYPE_AUTO..MemoryEngineContract.TYPE_DOUBLE) &&
        scope == state.searchScope &&
        (if (relative && !runtimeRelativeNeedsValue(predicate)) true else firstValid) && secondValid

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(if (peekingUnderlay) 0f else 1f),
        title = {
            RuntimeInputDialogTitle(
                title = stringResource(R.string.memory_editor_search_known_values),
                peeking = peekingUnderlay,
                onPeekingChanged = {
                    peekingUnderlay = it
                    onPeekUnderlayChanged(it)
                },
            )
        },
        text = {
            RuntimeSearchDialogBody(
                showKeypad = true,
                controls = { sideDock ->
                    RuntimeSearchControlRow {
                        RuntimePredicateMenu(
                            predicate = predicate,
                            onPredicate = { predicate = it },
                            includeRelative = state.sessionStage != MemorySessionStage.EMPTY,
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
                    if (sideDock) {
                        RuntimeSearchControlRow {
                            RuntimeTypeMenu(
                                type = type,
                                onType = { type = it },
                                managed = managedScope,
                                modifier = Modifier.weight(1f),
                            )
                            RuntimeScopeMenu(
                                scope = scope,
                                onScope = {
                                    scope = it
                                    actions.setKnownSearchScope(it)
                                },
                                managedSupported = state.managedSupported,
                                enabled = state.sessionStage == MemorySessionStage.EMPTY,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        RuntimeTypeMenu(
                            type = type,
                            onType = { type = it },
                            managed = managedScope,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RuntimeScopeMenu(
                            scope = scope,
                            onScope = {
                                scope = it
                                actions.setKnownSearchScope(it)
                            },
                            managedSupported = state.managedSupported,
                            enabled = state.sessionStage == MemorySessionStage.EMPTY,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                keypad = {
                    RuntimeSearchKeypad(
                        allowGroup = activeField == RuntimeInputField.FIRST && !managedScope,
                        valueSpec = if (managedScope || relative || activeField == RuntimeInputField.SECOND) spec else null,
                        onToken = { token ->
                            if (activeField == RuntimeInputField.FIRST) {
                                query = if (managedScope) runtimeInsertValidated(query, token, spec)
                                else runtimeInsert(query, token)
                            } else {
                                second = runtimeInsertValidated(second, token, spec)
                            }
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
                supportingContent = {
                    RuntimeExpressionHint(expression = expression, type = type)
                },
            )
        },
        textScrollable = false,
        dismissButtonBelowWrappedActions = state.sessionStage != MemorySessionStage.EMPTY,
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
                if (memorySessionHasActiveSearch(state.sessionStage)) {
                    Button(
                        enabled = nextScanValid && !state.busy,
                        onClick = {
                            val parsed = expression as MemorySearchExpression.Single
                            actions.nextScan(
                                parsed.value,
                                second.text,
                                predicate,
                                MemoryEngineContract.COMPARE_PREVIOUS,
                                type,
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
    onPeekUnderlayChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val activeSession = state.sessionStage != MemorySessionStage.EMPTY
    var type by remember(state.runtimeToken) {
        mutableIntStateOf(
            state.requestedType.takeIf(MemoryEngineContract::isValueType) ?: MemoryEngineContract.TYPE_AUTO,
        )
    }
    var scope by remember(state.runtimeToken) {
        mutableIntStateOf(
            state.searchScope.takeIf(MemoryEngineContract::isScope)
                ?: if (state.managedSupported) MemoryEngineContract.SCOPE_MANAGED_JAVA
                else MemoryEngineContract.SCOPE_JAVA_FAST,
        )
    }
    var predicate by remember(state.runtimeToken) {
        mutableIntStateOf(memoryUnknownPredicateOrDefault(state.unknownPredicate))
    }
    var first by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var second by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var activeField by remember { mutableStateOf(RuntimeInputField.FIRST) }
    var peekingUnderlay by remember { mutableStateOf(false) }
    val relative = predicate >= MemoryEngineContract.PREDICATE_CHANGED
    val needsValue = if (relative) runtimeRelativeNeedsValue(predicate) else true
    val needsSecond = predicate == MemoryEngineContract.PREDICATE_BETWEEN ||
        predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE ||
        predicate == MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE
    val spec = if (relative) MemoryInputSpec.relativeMagnitudeForType(type)
    else MemoryInputSpec.forType(type)
    val firstValid = !needsValue || spec.isComplete(first.text)
    val secondValid = !needsSecond || spec.isComplete(second.text)
    val refineValid = activeSession && firstValid && secondValid

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(if (peekingUnderlay) 0f else 1f),
        title = {
            RuntimeInputDialogTitle(
                title = stringResource(R.string.memory_editor_search_unknown_values),
                peeking = peekingUnderlay,
                onPeekingChanged = {
                    peekingUnderlay = it
                    onPeekUnderlayChanged(it)
                },
            )
        },
        text = {
            RuntimeSearchDialogBody(
                showKeypad = activeSession && needsValue,
                controls = { sideDock ->
                    if (!activeSession) {
                        if (sideDock) {
                            RuntimeSearchControlRow {
                                RuntimeTypeMenu(
                                    type = type,
                                    onType = { type = it },
                                    managed = scope == MemoryEngineContract.SCOPE_MANAGED_JAVA,
                                    modifier = Modifier.weight(1f),
                                )
                                RuntimeScopeMenu(
                                    scope = scope,
                                    onScope = { scope = it },
                                    managedSupported = state.managedSupported,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            RuntimeTypeMenu(
                                type = type,
                                onType = { type = it },
                                managed = scope == MemoryEngineContract.SCOPE_MANAGED_JAVA,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            RuntimeScopeMenu(
                                scope = scope,
                                onScope = { scope = it },
                                managedSupported = state.managedSupported,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        if (sideDock) {
                            RuntimeSearchControlRow {
                                RuntimeRelativeMenu(
                                    predicate = predicate,
                                    onPredicate = {
                                        predicate = it
                                        actions.setUnknownSearchPredicate(it)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                RuntimeTypeMenu(
                                    type = type,
                                    onType = { type = it },
                                    managed = scope == MemoryEngineContract.SCOPE_MANAGED_JAVA,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            RuntimeScopeMenu(
                                scope = scope,
                                onScope = {},
                                managedSupported = state.managedSupported,
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            RuntimeRelativeMenu(
                                predicate = predicate,
                                onPredicate = {
                                    predicate = it
                                    actions.setUnknownSearchPredicate(it)
                                },
                            )
                            RuntimeTypeMenu(
                                type = type,
                                onType = { type = it },
                                managed = scope == MemoryEngineContract.SCOPE_MANAGED_JAVA,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            RuntimeScopeMenu(
                                scope = scope,
                                onScope = {},
                                managedSupported = state.managedSupported,
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (needsValue) {
                            if (sideDock && needsSecond) {
                                RuntimeSearchControlRow {
                                    RuntimeSearchField(
                                        label = stringResource(R.string.memory_editor_search_hint),
                                        value = first,
                                        active = activeField == RuntimeInputField.FIRST,
                                        onClick = { activeField = RuntimeInputField.FIRST },
                                        modifier = Modifier.weight(1f),
                                    )
                                    RuntimeSearchField(
                                        label = stringResource(R.string.memory_editor_max_value),
                                        value = second,
                                        active = activeField == RuntimeInputField.SECOND,
                                        onClick = { activeField = RuntimeInputField.SECOND },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
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
                    }
                },
                keypad = {
                    RuntimeSearchKeypad(
                        allowGroup = false,
                        valueSpec = spec,
                        onToken = { token ->
                            if (activeField == RuntimeInputField.FIRST) {
                                first = runtimeInsertValidated(first, token, spec)
                            } else {
                                second = runtimeInsertValidated(second, token, spec)
                            }
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
        dismissButtonBelowWrappedActions = activeSession,
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
                            type,
                            MemoryEngineContract.PREDICATE_EQUAL,
                            true,
                            scope,
                        )
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.memory_editor_new_search))
                }
                if (activeSession) {
                    Button(
                        enabled = refineValid && !state.busy,
                        onClick = {
                            actions.nextScan(
                                first.text,
                                second.text,
                                predicate,
                                MemoryEngineContract.COMPARE_PREVIOUS,
                                type,
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
    targets: List<MemoryEditTarget>,
    writeSupported: Boolean,
    onPeekUnderlayChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onApply: (String, Int, Boolean) -> Unit,
) {
    val firstTarget = targets.firstOrNull() ?: return
    val initialType = firstTarget.type
    val initialValue = targets.map(MemoryEditTarget::valueText).distinct().singleOrNull().orEmpty()
    val batch = targets.size > 1
    val editableTypes = remember(targets) {
        targets.asSequence()
            .flatMap { it.aliasTypes.asSequence() }
            .filter(MemoryEngineContract::isCandidateType)
            .distinct()
            .toList()
            .ifEmpty { listOf(initialType) }
    }
    var type by remember(targets, initialType, editableTypes) {
        mutableIntStateOf(initialType.takeIf { it in editableTypes } ?: editableTypes.first())
    }
    val hasCompatibleType = editableTypes.any { it == type }
    var replacement by remember(targets, initialType, editableTypes) {
        mutableStateOf(TextFieldValue(initialValue, TextRange(initialValue.length)))
    }
    var freeze by remember { mutableStateOf(false) }
    var peekingUnderlay by remember { mutableStateOf(false) }
    val spec = MemoryInputSpec.forType(type)
    val replacementValid = if (targets.any(MemoryEditTarget::watch)) {
        targets.any { it.type == type } && spec.isComplete(replacement.text)
    } else {
        spec.isComplete(replacement.text)
    }
    val selectType: (Int) -> Unit = { selectedType ->
        if (selectedType != type) {
            type = selectedType
            val next = if (selectedType == initialType) initialValue else ""
            replacement = TextFieldValue(next, TextRange(next.length))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(if (peekingUnderlay) 0f else 1f),
        title = {
            RuntimeInputDialogTitle(
                title = if (batch) {
                    stringResource(R.string.memory_editor_edit_batch, targets.size)
                } else stringResource(R.string.memory_editor_edit),
                peeking = peekingUnderlay,
                onPeekingChanged = {
                    peekingUnderlay = it
                    onPeekUnderlayChanged(it)
                },
            )
        },
        text = {
            RuntimeSearchDialogBody(
                showKeypad = true,
                controls = { sideDock ->
                    if (sideDock) {
                        RuntimeSearchControlRow {
                            RuntimeEditTypeMenu(
                                type = type,
                                types = editableTypes,
                                onType = selectType,
                                modifier = Modifier.weight(1f),
                            )
                            RuntimeSearchField(
                                label = stringResource(
                                    if (type == initialType) R.string.memory_editor_current_value
                                    else R.string.memory_editor_replacement,
                                ),
                                value = replacement,
                                active = true,
                                onClick = {},
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        RuntimeEditTypeMenu(
                            type = type,
                            types = editableTypes,
                            onType = selectType,
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
                    }
                    if (!batch) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = freeze, onCheckedChange = { freeze = it })
                            Text(stringResource(R.string.memory_editor_freeze))
                        }
                    }
                },
                supportingContent = if (batch) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.memory_editor_edit_batch_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!hasCompatibleType) {
                                Text(
                                    stringResource(R.string.memory_editor_edit_batch_no_common_type),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                } else if (freeze) {
                    {
                        Text(
                            stringResource(R.string.memory_editor_freeze_after_edit_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else null,
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
                enabled = writeSupported && hasCompatibleType && replacementValid,
                onClick = {
                    onApply(replacement.text, type, freeze)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.memory_editor_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun MemoryEditTarget.supportsWrite(state: MemoryEditorUiState): Boolean =
    if (backend == MemoryEngineContract.BACKEND_MANAGED) state.managedWriteSupported
    else state.writeSupported

@Composable
private fun RuntimeInspectorTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onPeekUnderlayChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editCell by remember { mutableStateOf<MemoryInspectorCell?>(null) }
    var editLogicalRow by remember { mutableStateOf<MemoryInspectorLogicalRow?>(null) }
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
            val managed = snapshot.backend == MemoryEngineContract.BACKEND_MANAGED
            val cells = remember(snapshot) { buildInspectorCells(snapshot) }
            val logicalRows = remember(snapshot) { snapshot.logicalRows }
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
                        if (managed) {
                            Text(
                                "Managed · ${snapshot.provenance.ifBlank { "logical" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "${stringResource(R.string.memory_editor_anchor)} 0x${snapshot.anchorAddress.toString(16).uppercase(Locale.ROOT)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    RuntimeActionIcon(
                        R.drawable.ic_restart_alt,
                        R.string.memory_editor_refresh_snapshot,
                        enabled = !state.busy,
                    ) {
                        actions.inspectCandidate(
                            snapshot.candidateId,
                            watchAnchor = snapshot.watchAnchor,
                        )
                    }
                }
                HorizontalDivider()
                if (managed) {
                    if (logicalRows.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.memory_editor_inspector_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            val anchorIndex = logicalRows.indexOfFirst { it.relativeOffset == 0 }
                                .coerceAtLeast(0)
                            val visibleRows = (maxHeight.value / 55f).toInt().coerceAtLeast(1)
                            val firstIndex = inspectorCenteredFirstIndex(
                                cellCount = logicalRows.size,
                                anchorIndex = anchorIndex,
                                visibleRows = visibleRows,
                            )
                            val listState = rememberLazyListState()
                            LaunchedEffect(snapshot.candidateId, snapshot.expectedRevision, firstIndex) {
                                listState.scrollToItem(firstIndex)
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                userScrollEnabled = logicalRows.size > visibleRows,
                            ) {
                                items(logicalRows, key = { it.id }) { row ->
                                    Surface(
                                        color = if (row.relativeOffset == 0) {
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
                                        } else Color.Transparent,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    enabled = row.editable && state.managedWriteSupported && !state.busy,
                                                    onClick = { editLogicalRow = row },
                                                    onLongClick = { editLogicalRow = row },
                                                )
                                                .heightIn(min = 48.dp)
                                                .padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                if (row.relativeOffset >= 0) "+${row.relativeOffset}"
                                                else row.relativeOffset.toString(),
                                                modifier = Modifier.widthIn(min = 48.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    row.label,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    runtimeTypeShort(row.type),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                row.valueText,
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
                } else if (cells.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.memory_editor_inspector_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val anchorIndex = cells.indexOfFirst { it.offset == 0 }.coerceAtLeast(0)
                        val visibleRows = (maxHeight.value / 49f).toInt().coerceAtLeast(1)
                        val firstIndex = inspectorCenteredFirstIndex(
                            cellCount = cells.size,
                            anchorIndex = anchorIndex,
                            visibleRows = visibleRows,
                        )
                        val listState = rememberLazyListState()
                        LaunchedEffect(snapshot.candidateId, snapshot.anchorAddress, firstIndex) {
                            listState.scrollToItem(firstIndex)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = cells.size > visibleRows,
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
            }

            editCell?.let { cell ->
                RuntimeInspectorEditDialog(
                    snapshot = snapshot,
                    cell = cell,
                    actions = actions,
                    onPeekUnderlayChanged = onPeekUnderlayChanged,
                    onDismiss = {
                        onPeekUnderlayChanged(false)
                        editCell = null
                    },
                )
            }
            editLogicalRow?.let { row ->
                RuntimeInspectorLogicalEditDialog(
                    snapshot = snapshot,
                    row = row,
                    actions = actions,
                    onPeekUnderlayChanged = onPeekUnderlayChanged,
                    onDismiss = {
                        onPeekUnderlayChanged(false)
                        editLogicalRow = null
                    },
                )
            }
        }
    }
}

internal fun inspectorCenteredFirstIndex(
    cellCount: Int,
    anchorIndex: Int,
    visibleRows: Int,
): Int {
    if (cellCount <= 0) return 0
    val rows = visibleRows.coerceAtLeast(1)
    val safeAnchor = anchorIndex.coerceIn(0, cellCount - 1)
    val maxFirst = (cellCount - rows).coerceAtLeast(0)
    return (safeAnchor - rows / 2).coerceIn(0, maxFirst)
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
    onPeekUnderlayChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val spec = MemoryInputSpec.forType(snapshot.type)
    var value by remember(cell) {
        mutableStateOf(TextFieldValue(cell.value, TextRange(cell.value.length)))
    }
    var peekingUnderlay by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(if (peekingUnderlay) 0f else 1f),
        title = {
            RuntimeInputDialogTitle(
                title = "0x${cell.address.toString(16).uppercase(Locale.ROOT)}",
                peeking = peekingUnderlay,
                onPeekingChanged = {
                    peekingUnderlay = it
                    onPeekUnderlayChanged(it)
                },
            )
        },
        text = {
            RuntimeSearchDialogBody(
                showKeypad = true,
                controls = { _ ->
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
                        snapshot.watchAnchor,
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
private fun RuntimeInspectorLogicalEditDialog(
    snapshot: MemoryInspectorSnapshot,
    row: MemoryInspectorLogicalRow,
    actions: MemoryEditorActions,
    onPeekUnderlayChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val spec = MemoryInputSpec.forType(row.type)
    var value by remember(row) {
        mutableStateOf(TextFieldValue(row.valueText, TextRange(row.valueText.length)))
    }
    var peekingUnderlay by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(if (peekingUnderlay) 0f else 1f),
        title = {
            RuntimeInputDialogTitle(
                title = row.label.ifBlank { runtimeTypeShort(row.type) },
                peeking = peekingUnderlay,
                onPeekingChanged = {
                    peekingUnderlay = it
                    onPeekUnderlayChanged(it)
                },
            )
        },
        text = {
            RuntimeSearchDialogBody(
                showKeypad = true,
                controls = { _ ->
                    RuntimeSearchField(
                        label = "${runtimeTypeShort(row.type)} · ${row.relativeOffset.formatRelativeOffset()}",
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
                enabled = row.editable && spec.isComplete(value.text),
                onClick = {
                    actions.editInspectorValue(
                        snapshot.candidateId,
                        row.relativeOffset,
                        row.type,
                        row.expectedBits,
                        value.text,
                        snapshot.watchAnchor,
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

private fun Int.formatRelativeOffset(): String = if (this >= 0) "+$this" else toString()

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
    val landscape = availableWindowWidthDp() > availableWindowHeightDp()
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
    val landscape = availableWindowWidthDp() > availableWindowHeightDp()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.weight(weight).sizeIn(minHeight = if (landscape) 40.dp else 42.dp),
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RuntimeInputDialogTitle(
    title: String,
    peeking: Boolean,
    onPeekingChanged: (Boolean) -> Unit,
) {
    RuntimeInputDialogWindowEffect(peeking)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        RuntimePeekUnderlayButton(
            peeking = peeking,
            onPeekingChanged = onPeekingChanged,
        )
    }
}

private data class RuntimeInputDialogWindowBaseline(
    val flags: Int,
    val dimAmount: Float,
    val alpha: Float,
)

/** Keep the platform dialog hidden for the whole Peek hold without resetting it per recomposition. */
@Composable
internal fun RuntimeInputDialogWindowEffect(
    peeking: Boolean,
) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val baseline = remember(view, window) {
        window?.attributes?.let { attributes ->
            RuntimeInputDialogWindowBaseline(
                flags = attributes.flags,
                dimAmount = attributes.dimAmount,
                alpha = attributes.alpha,
            )
        }
    }

    DisposableEffect(view, window, baseline) {
        onDispose {
            if (window != null && baseline != null) {
                applyRuntimeInputDialogWindowState(window, baseline, peeking = false)
            }
        }
    }
    SideEffect {
        if (window != null && baseline != null) {
            applyRuntimeInputDialogWindowState(window, baseline, peeking)
        }
    }
}

private fun applyRuntimeInputDialogWindowState(
    window: android.view.Window,
    baseline: RuntimeInputDialogWindowBaseline,
    peeking: Boolean,
) {
    val targetAlpha = if (peeking) 0f else baseline.alpha
    val targetDimAmount = if (peeking) 0f else baseline.dimAmount
    val targetDimEnabled = !peeking &&
        baseline.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
    val currentFlags = window.attributes.flags
    val currentDimEnabled = currentFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
    if (currentDimEnabled != targetDimEnabled) {
        if (targetDimEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
    val attributes = window.attributes
    if (attributes.alpha != targetAlpha || attributes.dimAmount != targetDimAmount) {
        window.attributes = attributes.apply {
            alpha = targetAlpha
            dimAmount = targetDimAmount
        }
    }
}

/** Temporarily hides the editor while held so the MIDlet remains visible underneath. */
@Composable
private fun RuntimePeekUnderlayButton(
    peeking: Boolean,
    onPeekingChanged: (Boolean) -> Unit,
) {
    val description = stringResource(
        if (peeking) R.string.memory_editor_peek_underlay_active
        else R.string.memory_editor_peek_underlay,
    )
    DisposableEffect(Unit) {
        onDispose { onPeekingChanged(false) }
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = description
                onClick {
                    onPeekingChanged(!peeking)
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPeekingChanged(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            onPeekingChanged(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(
                if (peeking) R.drawable.ic_memory_editor_visibility_off
                else R.drawable.ic_memory_editor_visibility,
            ),
            contentDescription = null,
        )
    }
}

@Composable
private fun RuntimeSearchControlRow(
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RuntimePredicateMenu(
    predicate: Int,
    onPredicate: (Int) -> Unit,
    includeRelative: Boolean = false,
    modifier: Modifier = Modifier,
) {
    RuntimeChoiceMenu(
        value = predicate,
        values = (if (includeRelative) intArrayOf(
            MemoryEngineContract.PREDICATE_EQUAL,
            MemoryEngineContract.PREDICATE_NOT_EQUAL,
            MemoryEngineContract.PREDICATE_GREATER,
            MemoryEngineContract.PREDICATE_LESS,
            MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL,
            MemoryEngineContract.PREDICATE_LESS_OR_EQUAL,
            MemoryEngineContract.PREDICATE_BETWEEN,
            MemoryEngineContract.PREDICATE_CHANGED,
            MemoryEngineContract.PREDICATE_UNCHANGED,
            MemoryEngineContract.PREDICATE_INCREASED,
            MemoryEngineContract.PREDICATE_DECREASED,
            MemoryEngineContract.PREDICATE_INCREASED_BY,
            MemoryEngineContract.PREDICATE_DECREASED_BY,
            MemoryEngineContract.PREDICATE_CHANGED_BY,
            MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE,
            MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE,
        ) else intArrayOf(
            MemoryEngineContract.PREDICATE_EQUAL,
            MemoryEngineContract.PREDICATE_NOT_EQUAL,
            MemoryEngineContract.PREDICATE_GREATER,
            MemoryEngineContract.PREDICATE_LESS,
            MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL,
            MemoryEngineContract.PREDICATE_LESS_OR_EQUAL,
            MemoryEngineContract.PREDICATE_BETWEEN,
        )),
        label = { runtimePredicateName(it) },
        onChange = onPredicate,
        modifier = modifier,
    )
}

@Composable
private fun RuntimeRelativeMenu(
    predicate: Int,
    onPredicate: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    RuntimeChoiceMenu(
        value = predicate,
        values = memoryUnknownSearchPredicates(),
        label = { runtimePredicateName(it) },
        onChange = onPredicate,
        modifier = modifier,
    )
}

@Composable
private fun RuntimeTypeMenu(
    type: Int,
    onType: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    managed: Boolean = false,
) {
    RuntimeChoiceMenu(
        value = type,
        values = if (managed) {
            intArrayOf(
                MemoryEngineContract.TYPE_AUTO,
                MemoryEngineContract.TYPE_BYTE,
                MemoryEngineContract.TYPE_SHORT,
                MemoryEngineContract.TYPE_CHAR,
                MemoryEngineContract.TYPE_INT,
                MemoryEngineContract.TYPE_LONG,
                MemoryEngineContract.TYPE_FLOAT,
                MemoryEngineContract.TYPE_DOUBLE,
            )
        } else {
            intArrayOf(
                MemoryEngineContract.TYPE_AUTO,
                MemoryEngineContract.TYPE_BYTE,
                MemoryEngineContract.TYPE_SHORT,
                MemoryEngineContract.TYPE_CHAR,
                MemoryEngineContract.TYPE_INT,
                MemoryEngineContract.TYPE_LONG,
                MemoryEngineContract.TYPE_FLOAT,
                MemoryEngineContract.TYPE_DOUBLE,
            )
        },
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
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    RuntimeChoiceMenu(
        value = type,
        values = types.toIntArray(),
        label = { runtimeTypeName(it) },
        onChange = onType,
        modifier = modifier,
        enabled = types.size > 1,
    )
}

@Composable
private fun RuntimeScopeMenu(
    scope: Int,
    onScope: (Int) -> Unit,
    modifier: Modifier = Modifier,
    managedSupported: Boolean = false,
    enabled: Boolean = true,
) {
    RuntimeChoiceMenu(
        value = scope,
        values = if (managedSupported) {
            intArrayOf(
                MemoryEngineContract.SCOPE_JAVA_FAST,
                MemoryEngineContract.SCOPE_JAVA_THOROUGH,
                MemoryEngineContract.SCOPE_MANAGED_JAVA,
            )
        } else {
            intArrayOf(MemoryEngineContract.SCOPE_JAVA_FAST, MemoryEngineContract.SCOPE_JAVA_THOROUGH)
        },
        label = {
            when (it) {
                MemoryEngineContract.SCOPE_JAVA_FAST -> stringResource(R.string.memory_editor_scope_fast)
                MemoryEngineContract.SCOPE_MANAGED_JAVA -> stringResource(R.string.memory_editor_scope_managed)
                else -> stringResource(R.string.memory_editor_scope_thorough)
            }
        },
        onChange = onScope,
        modifier = modifier,
        enabled = enabled,
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
