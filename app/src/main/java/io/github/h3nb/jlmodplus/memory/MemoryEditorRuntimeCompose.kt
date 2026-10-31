/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

// Modified for JL-Mod Plus.

package io.github.h3nb.jlmodplus.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeGestures
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import java.text.NumberFormat
import java.util.Locale
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.memory.MemoryEditorDialog as AlertDialog
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first

private enum class RuntimeInputField { FIRST, SECOND }

private data class RuntimeKeypadCell(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val span: Int = 1,
)

private val RuntimeMemoryValueTypes = intArrayOf(
    MemoryEngineContract.TYPE_AUTO,
    MemoryEngineContract.TYPE_BYTE,
    MemoryEngineContract.TYPE_SHORT,
    MemoryEngineContract.TYPE_CHAR,
    MemoryEngineContract.TYPE_INT,
    MemoryEngineContract.TYPE_LONG,
    MemoryEngineContract.TYPE_FLOAT,
    MemoryEngineContract.TYPE_DOUBLE,
)

@Composable
private fun runtimeMemoryControlTextStyle() = MaterialTheme.typography.labelLarge

@Composable
private fun runtimeMemoryDataTextStyle() = MaterialTheme.typography.bodyLarge.copy(
    fontFamily = FontFamily.Monospace,
)

@Composable
private fun runtimeMemoryMetaTextStyle() = MaterialTheme.typography.bodySmall.copy(
    fontFamily = FontFamily.Monospace,
)

@Composable
private fun runtimeMemoryKeypadTextStyle() = MaterialTheme.typography.labelLarge.copy(
    fontFamily = FontFamily.Monospace,
)

/** Production Memory Editor shell hosted in the dedicated :memory_engine Activity. */
@Composable
internal fun MemoryEditorRuntimeRoot(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    overlayState: MemoryEditorOverlayState? = null,
) {
    if (!state.visible) return
    MemoryEditorOverlayScope(overlayState) { overlay ->
        val tab = state.activeTab
        var opacityDialog by remember { mutableStateOf(false) }
        val resultsListState = rememberLazyListState()
        val watchListState = rememberLazyListState()
        val inspectSelected = {
            state.selected.singleOrNull()?.let { actions.inspectCandidate(it, watchAnchor = state.watchTab) }
            Unit
        }
        val selectTab: (MemoryEditorTab) -> Unit = {
            when (it) {
                MemoryEditorTab.RESULTS -> actions.setWatchTab(false)
                MemoryEditorTab.WATCH -> actions.setWatchTab(true)
                MemoryEditorTab.INSPECTOR -> if (state.activeTab != it) inspectSelected()
            }
        }
        BackHandler(enabled = tab == MemoryEditorTab.INSPECTOR || overlay.peeking) {
            if (overlay.peeking) overlay.peeking = false else actions.closeInspector()
        }
        // The workspace is full-bleed; only interactive content is inset.
        // Its bounds are the Activity window, never the (possibly letterboxed) MIDlet viewport.
        Box(
            modifier = Modifier.fillMaxSize().memoryEditorPeekKeys(overlay),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize()
                    .testTag("memory-editor-workspace")
                    .alpha(if (overlay.peeking) 0f else 1f),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = overlay.opacity),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize()
                        .windowInsetsPadding(memoryEditorWorkspaceInsets())
                        .padding(horizontal = 8.dp),
                ) {
                    val wideWorkspace = maxWidth >=
                        600.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val compactResults = wideWorkspace
                    val showLabels = wideWorkspace
                    val showTitle = maxHeight >= 360.dp || LocalDensity.current.fontScale <= 1.3f
                    Column(
                        Modifier.fillMaxSize().padding(bottom = 48.dp)
                            .then(if (overlay.peeking) Modifier.clearAndSetSemantics {} else Modifier),
                    ) {
                        Box(Modifier.windowInsetsPadding(
                            WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal),
                        )) {
                            RuntimeMemoryHeader(state, showLabels || !showTitle, selectTab, actions::close, showTitle)
                        }
                        if (state.busy) RuntimeOperationStrip(state, actions)
                        state.message?.takeIf(String::isNotBlank)?.let {
                            RuntimeMessage(it, state.messageIsError)
                        }
                        when (tab) {
                            MemoryEditorTab.RESULTS -> RuntimeSearchResultsTab(
                                state, actions, inspectSelected, resultsListState, compactResults, Modifier.weight(1f),
                            )
                            MemoryEditorTab.WATCH -> RuntimeWatchTab(
                                state, actions, watchListState, compactResults, Modifier.weight(1f),
                            )
                            MemoryEditorTab.INSPECTOR -> RuntimeInspectorTab(
                                state, actions, Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            MemoryEditorPeekShield(overlay)
            // The footer paints edge-to-edge, but its controls respect the safe
            // horizontal and bottom gesture areas (including cutouts and waterfall).
            if (overlay.layers.isEmpty()) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            if (overlay.peeking) Color.Transparent
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .windowInsetsPadding(
                            memoryEditorSafeWindowInsets().only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                            ),
                        )
                        .padding(horizontal = 8.dp),
                ) {
                    MemoryEditorPeekDock(
                        overlay = overlay,
                        leadingContent = {
                            RuntimeActionIcon(R.drawable.ic_settings, R.string.memory_editor_panel_opacity) {
                                opacityDialog = true
                            }
                        },
                        centerContent = {
                            if (tab == MemoryEditorTab.RESULTS) RuntimePager(state, actions)
                        },
                    )
                }
            }
        }
        if (opacityDialog) MemoryEditorOpacityDialog(overlay) { opacityDialog = false }
    }
}

@Composable
private fun RuntimeMemoryHeader(
    state: MemoryEditorUiState,
    inlineNavigation: Boolean,
    onTab: (MemoryEditorTab) -> Unit,
    onClose: () -> Unit,
    showTitle: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTitle) Text(
            stringResource(R.string.memory_editor),
            style = MaterialTheme.typography.titleMedium,
            modifier = if (inlineNavigation) Modifier.padding(end = 16.dp) else Modifier.weight(1f),
        )
        if (inlineNavigation) {
            RuntimeMemoryTabs(state, onTab, Modifier.weight(1f))
        }
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.memory_editor_close))
        }
    }
    if (!inlineNavigation) RuntimeMemoryTabs(state, onTab)
}

@Composable
private fun RuntimeMemoryTabs(
    state: MemoryEditorUiState,
    onTab: (MemoryEditorTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchLabel = stringResource(R.string.memory_editor_search_tab)
    val watchLabel = stringResource(R.string.memory_editor_watch)
    val inspectorLabel = stringResource(R.string.memory_editor_inspector)
    SecondaryScrollableTabRow(
        selectedTabIndex = state.activeTab.ordinal,
        modifier = modifier.fillMaxWidth(),
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
    ) {
        MemoryEditorTab.entries.forEach { tab ->
            Tab(
                selected = state.activeTab == tab,
                onClick = { onTab(tab) },
                enabled = tab != MemoryEditorTab.INSPECTOR ||
                    state.activeTab == tab || state.selected.size == 1 && !state.busy,
                modifier = Modifier.semantics { contentDescription = when (tab) {
                    MemoryEditorTab.RESULTS -> searchLabel
                    MemoryEditorTab.WATCH -> watchLabel
                    MemoryEditorTab.INSPECTOR -> inspectorLabel
                } },
                text = {
                    Text(stringResource(when (tab) {
                        MemoryEditorTab.RESULTS -> R.string.memory_editor_search_tab
                        MemoryEditorTab.WATCH -> R.string.memory_editor_watch
                        MemoryEditorTab.INSPECTOR -> R.string.memory_editor_inspector
                    }))
                },
            )
        }
    }
}

private data class RuntimeToolbarAction(
    val icon: Int,
    val label: Int,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

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
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
    }
}

@Composable
private fun RuntimeSearchResultsTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onInspectSelected: () -> Unit,
    listState: LazyListState,
    compactRows: Boolean,
    modifier: Modifier = Modifier,
) {
    var knownDialog by remember { mutableStateOf(false) }
    var unknownDialog by remember { mutableStateOf(false) }
    var editTargets by remember { mutableStateOf<List<MemoryEditTarget>?>(null) }
    var editRevision by remember { mutableStateOf(0L) }
    var deleteSnapshot by remember { mutableStateOf<Pair<Long, List<Long>>?>(null) }
    val unknownBaseline = state.sessionStage == MemorySessionStage.UNKNOWN_BASELINE
    val selectedRows = state.results.filter { it.id in state.selected }
    val selectedWriteSupported = selectedRows.isNotEmpty() && state.writeSupported
    val allVisibleSelected = state.results.isNotEmpty() &&
        state.results.all { it.id in state.selected }

    val visibleSelectionDescription = stringResource(
        if (allVisibleSelected) R.string.memory_editor_clear_selection else R.string.memory_editor_select_visible,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        var moreMenu by remember { mutableStateOf(false) }
        val quickActions = listOf(
            RuntimeToolbarAction(R.drawable.ic_search, R.string.memory_editor_search_known_values, state.canSearch) {
                knownDialog = true
            },
            RuntimeToolbarAction(R.drawable.ic_memory_editor_search_unknown,
                R.string.memory_editor_search_unknown_values, state.canSearch) {
                unknownDialog = true
            },
            RuntimeToolbarAction(R.drawable.ic_edit, R.string.memory_editor_edit, selectedWriteSupported && !state.busy) {
                editTargets = selectedRows.map { MemoryEditTarget(it.id, it.primaryType, it.valueText, it.aliasTypes) }
                editRevision = state.revision
            },
            RuntimeToolbarAction(R.drawable.ic_memory_editor_watch_add, R.string.memory_editor_add_to_watch,
                selectedRows.isNotEmpty() && !state.busy) { actions.watchSelected(true) },
            RuntimeToolbarAction(R.drawable.ic_refresh, R.string.memory_editor_refresh,
                state.results.isNotEmpty() && !state.busy, actions::refresh),
        )
        val extraActions = listOf(
            RuntimeToolbarAction(R.drawable.ic_memory_editor_inspector, R.string.memory_editor_inspect_memory,
                selectedRows.size == 1 && !state.busy, onInspectSelected),
            RuntimeToolbarAction(R.drawable.ic_undo, R.string.memory_editor_undo,
                state.canUndo && !state.busy, actions::undo),
            RuntimeToolbarAction(R.drawable.ic_delete, R.string.memory_editor_remove,
                selectedRows.isNotEmpty() && !unknownBaseline && !state.busy) {
                deleteSnapshot = state.revision to selectedRows.map(MemoryResultRow::id)
            },
        )
        // Keep the frequent actions at fixed positions regardless of selection.
        // Only genuinely narrow windows move secondary actions into More.
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(
            WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal),
        )) {
            val narrowActions = maxWidth < (if (compactRows) 500.dp else 304.dp)
            val primaryActions = if (narrowActions) {
                listOf(quickActions[0], quickActions[2], quickActions[4])
            } else quickActions
            val overflowActions = quickActions.filterNot { it in primaryActions } +
                extraActions.filter { it.enabled }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = if (compactRows) Arrangement.End else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compactRows && (state.results.isNotEmpty() || state.resultCount > 0L)) {
                    RuntimePageSelectionCheckbox(
                        checked = allVisibleSelected,
                        enabled = state.results.isNotEmpty() && !state.busy,
                        description = visibleSelectionDescription,
                        onToggle = { if (it) actions.selectVisible() else actions.clearSelection() },
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    ) {
                        Text(
                            pluralStringResource(
                                if (unknownBaseline) R.plurals.memory_editor_candidates
                                else R.plurals.memory_editor_results,
                                state.resultCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                state.resultCount,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.selected.isNotEmpty()) {
                            Text(
                                pluralStringResource(
                                    R.plurals.memory_editor_selected,
                                    state.selected.size, state.selected.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (compactRows) {
                    Spacer(Modifier.weight(1f))
                }
                primaryActions.forEach { action ->
                    RuntimeActionIcon(action.icon, action.label, action.enabled, action.onClick)
                }
                if (overflowActions.isNotEmpty()) {
                    RuntimeActionIcon(R.drawable.ic_more_vert, R.string.more, enabled = !state.busy) {
                        moreMenu = true
                    }
                }
            }
            LaunchedEffect(overflowActions.isEmpty()) {
                if (overflowActions.isEmpty()) moreMenu = false
            }
            if (moreMenu && overflowActions.isNotEmpty()) {
                RuntimeOverflowActionsDialog(overflowActions) { moreMenu = false }
            }
        }
        HorizontalDivider()

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!compactRows && (state.results.isNotEmpty() || state.resultCount > 0)) item(key = "summary") {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RuntimePageSelectionCheckbox(
                        checked = allVisibleSelected,
                        enabled = state.results.isNotEmpty() && !state.busy,
                        description = visibleSelectionDescription,
                        onToggle = { if (it) actions.selectVisible() else actions.clearSelection() },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            pluralStringResource(
                                if (unknownBaseline) R.plurals.memory_editor_candidates else R.plurals.memory_editor_results,
                                state.resultCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), state.resultCount,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (state.selected.isNotEmpty()) {
                            Text(
                                pluralStringResource(R.plurals.memory_editor_selected, state.selected.size, state.selected.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (state.results.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(
                                if (state.sessionStage == MemorySessionStage.EMPTY) R.string.memory_editor_new_search
                                else if (unknownBaseline) R.string.memory_editor_no_candidates
                                else R.string.memory_editor_no_results,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        quickActions.take(2).forEachIndexed { index, action ->
                            val label = stringResource(action.label)
                            val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { contentDescription = label }
                            val content: @Composable () -> Unit = {
                                Icon(painterResource(action.icon), contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text(label, modifier = Modifier.weight(1f))
                            }
                            if (index == 0) {
                                Button(onClick = action.onClick, enabled = action.enabled,
                                    modifier = buttonModifier, shape = MaterialTheme.shapes.medium) { content() }
                            } else {
                                OutlinedButton(onClick = action.onClick, enabled = action.enabled,
                                    modifier = buttonModifier, shape = MaterialTheme.shapes.medium) { content() }
                            }
                        }
                    }
                }
            } else {
                items(state.results, key = { it.id }) { row ->
                    RuntimeResultRow(
                        row = row,
                        compactRows = compactRows,
                        selected = row.id in state.selected,
                        onToggle = { actions.toggleSelection(row.id) },
                        onClick = {
                            if (state.writeSupported) {
                                editTargets = listOf(
                                    MemoryEditTarget(row.id, row.primaryType, row.valueText, row.aliasTypes),
                                )
                                editRevision = state.revision
                            }
                        },
                    )
                }
            }
        }
    }

    if (knownDialog) {
        RuntimeKnownSearchDialog(
            state = state,
            actions = actions,
            onDismiss = {
                knownDialog = false
            },
        )
    }
    if (unknownDialog) {
        RuntimeUnknownSearchDialog(
            state = state,
            actions = actions,
            onDismiss = {
                unknownDialog = false
            },
        )
    }
    val currentEditTargets = editTargets
    if (currentEditTargets != null) {
        RuntimeEditDialog(
            targets = currentEditTargets,
            writeSupported = state.writeSupported,
            onDismiss = {
                editTargets = null
            },
            onApply = { value, type, freeze, incremental, step ->
                if (incremental) {
                    actions.editIncrementalTargets(
                        currentEditTargets, editRevision, value, step, type,
                    )
                } else {
                    actions.editTargetsWithOptions(
                        currentEditTargets, editRevision, value, type, false, freeze,
                    )
                }
                editTargets = null
            },
        )
    }
    deleteSnapshot?.let { (revision, ids) ->
        AlertDialog(
            onDismissRequest = { deleteSnapshot = null },
            title = { Text(stringResource(R.string.memory_editor_remove_candidates_title)) },
            text = {
                Text(
                    stringResource(R.string.memory_editor_remove_candidates_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.removeResultTargets(ids, revision, false)
                        deleteSnapshot = null
                    },
                ) { Text(stringResource(R.string.memory_editor_remove)) }
                TextButton(
                    onClick = {
                        actions.removeResultTargets(ids, revision, true)
                        deleteSnapshot = null
                    },
                ) { Text(stringResource(R.string.memory_editor_revert_and_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSnapshot = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun runtimeCandidateLocation(id: Long, source: MemoryCandidateSource, fallback: String): String {
    if (source.kind == MemoryEngineContract.SOURCE_UNKNOWN) {
        return if (ManagedJavaMemoryIds.kind(id) == ManagedJavaMemoryEngine.KIND_ARRAY) {
            stringResource(
                R.string.memory_editor_candidate_untraced,
                ManagedJavaMemoryIds.ownerHandle(id),
                ManagedJavaMemoryIds.slot(id),
            )
        } else fallback
    }
    val kind = stringResource(
        if (source.kind == MemoryEngineContract.SOURCE_STATIC_FIELD) {
            R.string.memory_editor_reference_static
        } else R.string.memory_editor_reference_instance
    )
    val name = if (ManagedJavaMemoryIds.kind(id) == ManagedJavaMemoryEngine.KIND_ARRAY) {
        "${source.shortFieldName}[${ManagedJavaMemoryIds.slot(id)}]"
    } else source.shortFieldName
    return if (source.kind == MemoryEngineContract.SOURCE_INSTANCE_FIELD) {
        stringResource(
            R.string.memory_editor_candidate_instance,
            name, kind, ManagedJavaMemoryIds.ownerHandle(id),
        )
    } else stringResource(R.string.memory_editor_candidate_static, name, kind)
}

@Composable
private fun RuntimeResultRow(
    row: MemoryResultRow,
    compactRows: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = if (compactRows) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            val aliasCount = row.aliasTypes.size
            val typeLabel = if (aliasCount > 1) "${runtimeTypeShort(row.primaryType)} +${aliasCount - 1}"
            else runtimeTypeShort(row.primaryType)
            val location = runtimeCandidateLocation(row.id, row.source, row.locationText)
            if (compactRows) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.weight(0.46f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.valueText, style = runtimeMemoryDataTextStyle(),
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            typeLabel, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        location, style = runtimeMemoryMetaTextStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.54f).padding(start = 16.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    RuntimeCandidateStatus(row.state, row.relocations)
                }
            } else {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.valueText, style = runtimeMemoryDataTextStyle(),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            typeLabel, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            location, style = runtimeMemoryMetaTextStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        RuntimeCandidateStatus(row.state, row.relocations)
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun RuntimePageSelectionCheckbox(
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onToggle: (Boolean) -> Unit,
) {
    Checkbox(
        checked = checked,
        enabled = enabled,
        onCheckedChange = onToggle,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun RuntimeOverflowActionsDialog(
    actions: List<RuntimeToolbarAction>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        maxDialogWidth = 720.dp,
        text = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val twoColumns = maxWidth >= 520.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                val gap = 8.dp
                val itemWidth = if (twoColumns) (maxWidth - gap) / 2 else maxWidth
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            modifier = Modifier.width(itemWidth),
                            text = {
                                Text(
                                    stringResource(action.label),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(painterResource(action.icon), contentDescription = null)
                            },
                            enabled = action.enabled,
                            onClick = { onDismiss(); action.onClick() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun RuntimeWatchTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    listState: LazyListState,
    compactRows: Boolean,
    modifier: Modifier = Modifier,
) {
    var editTargets by remember { mutableStateOf<List<MemoryEditTarget>?>(null) }
    val selectedRows = state.watches.filter { it.id in state.selected }
    val selectedWriteSupported = selectedRows.isNotEmpty() && state.writeSupported
    val allWatchesSelected = state.watches.isNotEmpty() &&
        state.watches.all { it.id in state.selected }
    val selectionDescription = stringResource(
        if (allWatchesSelected) R.string.memory_editor_clear_selection
        else R.string.memory_editor_select_visible,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimePageSelectionCheckbox(
                checked = allWatchesSelected,
                enabled = state.watches.isNotEmpty() && !state.busy,
                description = selectionDescription,
                onToggle = { if (it) actions.selectVisible() else actions.clearSelection() },
            )
            Text(
                if (state.selected.isEmpty()) stringResource(R.string.memory_editor_watch)
                else pluralStringResource(
                    R.plurals.memory_editor_selected, state.selected.size, state.selected.size,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            RuntimeActionIcon(
                R.drawable.ic_edit,
                R.string.memory_editor_edit,
                enabled = selectedWriteSupported && !state.busy,
            ) {
                editTargets = selectedRows.map { row ->
                    MemoryEditTarget(row.id, row.type, row.valueText, listOf(row.type), watch = true)
                }
            }
            RuntimeActionIcon(
                R.drawable.ic_refresh,
                R.string.memory_editor_refresh,
                enabled = state.watches.isNotEmpty() && !state.busy,
                onClick = actions::refresh,
            )
            when {
                selectedRows.size == 1 && selectedRows.single().freezeMode >= 0 -> RuntimeActionIcon(
                    R.drawable.ic_unlock,
                    R.string.memory_editor_unfreeze,
                    enabled = !state.busy,
                    onClick = actions::clearFreezeSelected,
                )
                selectedRows.size == 1 -> RuntimeActionIcon(
                    R.drawable.ic_lock,
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
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.watches, key = { it.id }) { row ->
                    RuntimeWatchRow(
                        row = row,
                        compactRows = compactRows,
                        selected = row.id in state.selected,
                        onToggle = { actions.toggleSelection(row.id) },
                        onClick = {
                            if (state.writeSupported) {
                                editTargets = listOf(
                                    MemoryEditTarget(row.id, row.type, row.valueText,
                                        listOf(row.type), watch = true),
                                )
                            }
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
            writeSupported = state.writeSupported,
            onDismiss = {
                editTargets = null
            },
            onApply = { value, type, freeze, _, _ ->
                actions.editTargetsWithOptions(
                    currentEditTargets, 0L, value, type, false, freeze,
                )
                editTargets = null
            },
        )
    }
}

@Composable
private fun RuntimeWatchRow(
    row: MemoryWatchRow,
    compactRows: Boolean,
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
                .padding(horizontal = 12.dp, vertical = if (compactRows) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics {
                    contentDescription = selectDescription
                },
            )
            if (compactRows) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label.ifBlank { runtimeTypeShort(row.type) },
                        modifier = Modifier.weight(0.32f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        row.valueText, modifier = Modifier.weight(0.25f).padding(start = 12.dp),
                        style = runtimeMemoryDataTextStyle(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${runtimeCandidateLocation(row.id, row.source, row.locationText)} · " +
                            runtimeTypeShort(row.type),
                        modifier = Modifier.weight(0.43f).padding(start = 12.dp),
                        style = runtimeMemoryMetaTextStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        row.freezePaused -> Text(
                            stringResource(R.string.memory_editor_freeze_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        row.freezeMode >= 0 -> Icon(
                            painterResource(R.drawable.ic_lock),
                            contentDescription = stringResource(R.string.memory_editor_freeze),
                        )
                        else -> RuntimeCandidateStatus(row.state, row.relocations)
                    }
                }
            } else {
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
                        style = runtimeMemoryDataTextStyle(),
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        textAlign = TextAlign.End,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${runtimeCandidateLocation(row.id, row.source, row.locationText)} · " +
                            runtimeTypeShort(row.type),
                        style = runtimeMemoryMetaTextStyle(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        row.freezePaused -> Text(
                            stringResource(R.string.memory_editor_freeze_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        row.freezeMode >= 0 -> Icon(
                            painterResource(R.drawable.ic_lock),
                            contentDescription = stringResource(R.string.memory_editor_freeze),
                        )
                        else -> RuntimeCandidateStatus(row.state, row.relocations)
                    }
                }
            }
            }
        }
    }
    HorizontalDivider()
}


@Composable
private fun RuntimeSearchDialogBody(
    title: @Composable () -> Unit,
    showKeypad: Boolean,
    controls: @Composable (sideDock: Boolean) -> Unit,
    keypad: @Composable (landscape: Boolean) -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    // The dialog owns the only scroll container. Controls and keypad keep their intrinsic
    // height, including at large font scales; a short window scrolls instead of shrinking keys.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideDock = showKeypad &&
            maxWidth >= 560.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (sideDock) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(0.96f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    title()
                    controls(true)
                    supportingContent?.invoke()
                }
                Box(Modifier.weight(1.04f)) { keypad(true) }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                title()
                controls(false)
                supportingContent?.invoke()
                if (showKeypad) keypad(false)
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

    val expression = remember(query.text) { parseMemorySearchExpression(query.text) }
    val groupExpression = expression is MemorySearchExpression.Group
    val effectivePredicate = if (groupExpression) {
        MemoryEngineContract.PREDICATE_EQUAL
    } else {
        predicate
    }
    LaunchedEffect(groupExpression) {
        if (groupExpression) predicate = MemoryEngineContract.PREDICATE_EQUAL
    }
    val spec = remember(type) { MemoryInputSpec.forType(type) }
    LaunchedEffect(expression, type) {
        if (expression is MemorySearchExpression.Group && type == MemoryEngineContract.TYPE_AUTO) {
            inferMemoryGroupType(expression.values)?.let { type = it }
        }
    }
    val needsSecond = effectivePredicate == MemoryEngineContract.PREDICATE_BETWEEN &&
        expression !is MemorySearchExpression.Group
    val firstValid = spec.isComplete(query.text)
    val secondValid = !needsSecond || spec.isComplete(second.text)
    val singleValid = expression is MemorySearchExpression.Single && firstValid
    val managedGroupValid = expression is MemorySearchExpression.Group &&
        type != MemoryEngineContract.TYPE_AUTO && expression.values.all(spec::isComplete)
    val newSearchValid = (singleValid || managedGroupValid) && secondValid &&
        MemoryEngineContract.isValueType(type) &&
        effectivePredicate in MemoryEngineContract.PREDICATE_EQUAL..MemoryEngineContract.PREDICATE_BETWEEN
    val nextScanValid = state.sessionStage != MemorySessionStage.EMPTY &&
        type in (MemoryEngineContract.TYPE_AUTO..MemoryEngineContract.TYPE_DOUBLE) &&
        expression is MemorySearchExpression.Single && firstValid && secondValid

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            RuntimeSearchDialogBody(
                title = {
                    RuntimeInputDialogTitle(
                        title = stringResource(R.string.memory_editor_search_known_values),
                    )
                },
                showKeypad = true,
                controls = { sideDock ->
                    RuntimeSearchControlRow {
                        RuntimePredicateMenu(
                            predicate = effectivePredicate,
                            onPredicate = { predicate = it },
                            enabled = !groupExpression,
                            modifier = Modifier.widthIn(min = 72.dp, max = 112.dp),
                        )
                        RuntimeSearchField(
                            label = stringResource(memorySearchFieldLabel(effectivePredicate, second = false)),
                            value = query,
                            onClick = { activeField = RuntimeInputField.FIRST },
                            onValueChange = { query = it },
                            valueSpec = spec,
                            modifier = Modifier.weight(1f),
                            initialFocus = true,
                        )
                    }
                    if (needsSecond) {
                        RuntimeSearchField(
                            label = stringResource(memorySearchFieldLabel(effectivePredicate, second = true)),
                            value = second,
                            onClick = { activeField = RuntimeInputField.SECOND },
                            onValueChange = { second = it },
                            valueSpec = spec,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (sideDock) {
                        RuntimeSearchControlRow {
                            RuntimeTypeMenu(
                                type = type,
                                onType = { type = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        RuntimeTypeMenu(
                            type = type,
                            onType = { type = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                keypad = { landscape ->
                    RuntimeSearchKeypad(
                        landscape = landscape,
                        allowGroup = activeField == RuntimeInputField.FIRST,
                        valueSpec = spec,
                        onToken = { token ->
                            if (activeField == RuntimeInputField.FIRST) {
                                query = runtimeInsertValidated(query, token, spec)
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
        textScrollable = true,
        dismissButtonBelowWrappedActions = state.sessionStage != MemorySessionStage.EMPTY,
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    enabled = newSearchValid && state.canSearch,
                    onClick = {
                        when (val parsed = expression) {
                            is MemorySearchExpression.Single -> actions.startSearch(
                                parsed.value,
                                second.text,
                                type,
                                effectivePredicate,
                                false,
                            )
                            is MemorySearchExpression.Group -> actions.groupSearch(
                                type,
                                parsed.values.toTypedArray(),
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
                        enabled = nextScanValid && state.canSearch,
                        onClick = {
                            val nextValue = (expression as? MemorySearchExpression.Single)?.value
                                ?: return@Button
                            actions.nextScan(
                                nextValue,
                                second.text,
                                effectivePredicate,
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
    onDismiss: () -> Unit,
) {
    val activeSession = state.sessionStage != MemorySessionStage.EMPTY
    var type by remember(state.runtimeToken) {
        mutableIntStateOf(
            state.requestedType.takeIf(MemoryEngineContract::isValueType) ?: MemoryEngineContract.TYPE_AUTO,
        )
    }
    var predicate by remember(state.runtimeToken) {
        mutableIntStateOf(memoryUnknownPredicateOrDefault(state.unknownPredicate))
    }
    var first by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var second by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var activeField by remember { mutableStateOf(RuntimeInputField.FIRST) }
    val relative = predicate >= MemoryEngineContract.PREDICATE_CHANGED
    val needsValue = if (relative) memoryRelativePredicateNeedsValue(predicate) else true
    val needsSecond = predicate == MemoryEngineContract.PREDICATE_BETWEEN ||
        predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE ||
        predicate == MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE
    val spec = remember(type, relative) {
        if (relative) MemoryInputSpec.relativeMagnitudeForType(type)
        else MemoryInputSpec.forType(type)
    }
    val firstValid = !needsValue || spec.isComplete(first.text)
    val secondValid = !needsSecond || spec.isComplete(second.text)
    val refineValid = activeSession && firstValid && secondValid

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            RuntimeSearchDialogBody(
                title = {
                    RuntimeInputDialogTitle(
                        title = stringResource(R.string.memory_editor_search_unknown_values),
                    )
                },
                showKeypad = needsValue,
                controls = { sideDock ->
                    if (!activeSession) {
                        if (sideDock) {
                            RuntimeSearchControlRow {
                                RuntimeTypeMenu(
                                    type = type,
                                    onType = { type = it },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            RuntimeTypeMenu(
                                type = type,
                                onType = { type = it },
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
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            RuntimeRelativeMenu(
                                predicate = predicate,
                                onPredicate = {
                                    predicate = it
                                    actions.setUnknownSearchPredicate(it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            RuntimeTypeMenu(
                                type = type,
                                onType = { type = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (needsValue) {
                            if (sideDock && needsSecond) {
                                RuntimeSearchControlRow {
                                    RuntimeSearchField(
                                        label = stringResource(memorySearchFieldLabel(predicate, second = false)),
                                        value = first,
                                        onClick = { activeField = RuntimeInputField.FIRST },
                                        onValueChange = { first = it },
                                        valueSpec = spec,
                                        modifier = Modifier.weight(1f),
                                        initialFocus = true,
                                    )
                                    RuntimeSearchField(
                                        label = stringResource(memorySearchFieldLabel(predicate, second = true)),
                                        value = second,
                                        onClick = { activeField = RuntimeInputField.SECOND },
                                        onValueChange = { second = it },
                                        valueSpec = spec,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                RuntimeSearchField(
                                    label = stringResource(memorySearchFieldLabel(predicate, second = false)),
                                    value = first,
                                    onClick = { activeField = RuntimeInputField.FIRST },
                                    onValueChange = { first = it },
                                    valueSpec = spec,
                                    modifier = Modifier.fillMaxWidth(),
                                    initialFocus = true,
                                )
                                if (needsSecond) {
                                    RuntimeSearchField(
                                        label = stringResource(memorySearchFieldLabel(predicate, second = true)),
                                        value = second,
                                        onClick = { activeField = RuntimeInputField.SECOND },
                                        onValueChange = { second = it },
                                        valueSpec = spec,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                },
                keypad = { landscape ->
                    RuntimeSearchKeypad(
                        landscape = landscape,
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
        textScrollable = true,
        dismissButtonBelowWrappedActions = activeSession,
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    enabled = state.canSearch,
                    onClick = {
                        actions.startSearch(
                            "",
                            "",
                            type,
                            MemoryEngineContract.PREDICATE_EQUAL,
                            true,
                        )
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.memory_editor_new_search))
                }
                if (activeSession) {
                    Button(
                        enabled = refineValid && state.canSearch,
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
    onDismiss: () -> Unit,
    onApply: (String, Int, Boolean, Boolean, String) -> Unit,
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
    val incrementalAvailable = batch && targets.none(MemoryEditTarget::watch) &&
        firstTarget.type in MemoryEngineContract.TYPE_BYTE..MemoryEngineContract.TYPE_LONG &&
        targets.all { it.type == firstTarget.type } && type == firstTarget.type
    var replacement by remember(targets, initialType, editableTypes) {
        mutableStateOf(TextFieldValue(initialValue, TextRange(initialValue.length)))
    }
    var freeze by remember { mutableStateOf(false) }
    var incremental by remember(targets) { mutableStateOf(false) }
    var editingStep by remember(targets) { mutableStateOf(false) }
    var step by remember(targets) { mutableStateOf(TextFieldValue("1", TextRange(1))) }
    val spec = remember(type) { MemoryInputSpec.forType(type) }
    val preview = remember(targets, type, replacement.text, step.text, incremental) {
        if (incremental) memoryIncrementalPreview(targets, type, replacement.text, step.text)
        else null
    }
    val stepSpec = remember { MemoryInputSpec.forType(MemoryEngineContract.TYPE_LONG) }
    val replacementValid = if (incremental) {
        preview != null
    } else if (targets.any(MemoryEditTarget::watch)) {
        targets.any { it.type == type } && spec.isComplete(replacement.text)
    } else {
        spec.isComplete(replacement.text)
    }
    val selectType: (Int) -> Unit = { selectedType ->
        if (selectedType != type) {
            type = selectedType
            incremental = false
            editingStep = false
            val next = if (selectedType == initialType) initialValue else ""
            replacement = TextFieldValue(next, TextRange(next.length))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            RuntimeSearchDialogBody(
                title = {
                    RuntimeInputDialogTitle(
                        title = if (batch) {
                            pluralStringResource(
                                R.plurals.memory_editor_edit_batch,
                                targets.size,
                                targets.size,
                            )
                        } else stringResource(R.string.memory_editor_edit),
                    )
                },
                showKeypad = true,
                controls = { _ ->
                    if (incrementalAvailable) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = !incremental,
                                onClick = {
                                    incremental = false
                                    editingStep = false
                                },
                                label = { Text(stringResource(R.string.memory_editor_set_all)) },
                            )
                            FilterChip(
                                selected = incremental,
                                onClick = { incremental = true },
                                label = { Text(stringResource(R.string.memory_editor_incremental_edit)) },
                            )
                        }
                    }
                    RuntimeSearchControlRow {
                        RuntimeEditTypeMenu(
                            type = type,
                            types = editableTypes,
                            onType = selectType,
                            modifier = Modifier.weight(0.42f),
                        )
                        RuntimeSearchField(
                            label = stringResource(
                                if (incremental) R.string.memory_editor_increment_start
                                else R.string.memory_editor_replacement
                            ),
                            value = replacement,
                            onClick = { editingStep = false },
                            onValueChange = { replacement = it },
                            valueSpec = spec,
                            modifier = Modifier.weight(0.58f),
                            initialFocus = true,
                        )
                    }
                    if (incremental) {
                        RuntimeSearchField(
                            label = stringResource(R.string.memory_editor_increment_step),
                            value = step,
                            onClick = { editingStep = true },
                            onValueChange = { step = it },
                            valueSpec = stepSpec,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!batch) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = freeze, onCheckedChange = { freeze = it })
                            Text(stringResource(R.string.memory_editor_freeze_after_edit))
                        }
                    }
                },
                supportingContent = if (batch) {
                    {
                        when {
                            incremental && preview != null -> {
                                val values = preview.values
                                val sample = if (values.size <= 3) {
                                    values.joinToString(" → ")
                                } else {
                                    "${values[0]} → ${values[1]} → … → ${values.last()}"
                                }
                                Text(
                                    stringResource(
                                        R.string.memory_editor_increment_preview,
                                        sample,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            incremental && (replacement.text.isNotBlank() || step.text.isBlank()) -> {
                                Text(
                                    stringResource(R.string.memory_editor_increment_invalid),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            !incremental && targets.any { it.type != type } -> {
                                Text(
                                    stringResource(R.string.memory_editor_edit_batch_type_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                keypad = { landscape ->
                    RuntimeSearchKeypad(
                        landscape = landscape,
                        allowGroup = false,
                        valueSpec = if (incremental && editingStep) stepSpec else spec,
                        onToken = {
                            if (incremental && editingStep) {
                                step = runtimeInsertValidated(step, it, stepSpec)
                            } else {
                                replacement = runtimeInsertValidated(replacement, it, spec)
                            }
                        },
                        onBackspace = {
                            if (incremental && editingStep) step = runtimeBackspace(step)
                            else replacement = runtimeBackspace(replacement)
                        },
                        onMove = {
                            if (incremental && editingStep) step = runtimeMove(step, it)
                            else replacement = runtimeMove(replacement, it)
                        },
                        onClear = {
                            if (incremental && editingStep) step = TextFieldValue("", TextRange(0))
                            else replacement = TextFieldValue("", TextRange(0))
                        },
                    )
                },
            )
        },
        textScrollable = true,
        confirmButton = {
            Button(
                enabled = writeSupported && hasCompatibleType && replacementValid,
                onClick = {
                    onApply(replacement.text, type, freeze, incremental, step.text)
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
internal fun RuntimeInspectorTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    modifier: Modifier = Modifier,
) {
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
            val snapshot = requireNotNull(state.inspector)
            val logicalRows = snapshot.logicalRows
            val listState = rememberLazyListState()
            LaunchedEffect(snapshot.candidateId) {
                val anchorIndex = logicalRows.indexOfFirst { it.relativeOffset == 0 }
                if (anchorIndex > 0) {
                    // Measure the anchor after bringing it into view; never infer row height
                    // from font size or disable scrolling because an estimate says it fits.
                    listState.scrollToItem(anchorIndex + 1)
                    val layout = snapshotFlow { listState.layoutInfo }
                        .first { info -> info.visibleItemsInfo.any { it.key == logicalRows[anchorIndex].id } }
                    val anchor = layout.visibleItemsInfo.first { it.key == logicalRows[anchorIndex].id }
                    listState.scrollBy(
                        (anchor.offset - (layout.viewportEndOffset - layout.viewportStartOffset - anchor.size) / 2).toFloat(),
                    )
                }
            }
            LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
                item(key = "source") {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    runtimeCandidateLocation(
                                        snapshot.candidateId, snapshot.source,
                                        snapshot.label.ifBlank { runtimeTypeShort(snapshot.type) },
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (snapshot.source == MemoryCandidateSource.UNKNOWN) {
                                        stringResource(R.string.memory_editor_source_unknown)
                                    } else {
                                        "${snapshot.source.declaringClass}.${snapshot.source.fieldName}: " +
                                            snapshot.source.fieldType
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val pathSummary = when (snapshot.pathStatus) {
                                    MemoryEngineContract.PATH_VERIFIED ->
                                        snapshot.referencePath.takeIf { it.isNotEmpty() }
                                    MemoryEngineContract.PATH_CHANGED ->
                                        stringResource(R.string.memory_editor_path_changed)
                                    MemoryEngineContract.PATH_INCOMPLETE ->
                                        stringResource(R.string.memory_editor_path_incomplete)
                                    else -> null
                                }
                                if (pathSummary != null) {
                                    Text(
                                        pathSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            RuntimeActionIcon(
                                R.drawable.ic_refresh,
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
                    }
                }
                if (logicalRows.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.memory_editor_inspector_no_rows),
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(logicalRows, key = { it.id }) { row ->
                    Surface(
                        color = if (row.relativeOffset == 0) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
                        } else Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = row.editable &&
                                        state.writeSupported && !state.busy,
                                    onClick = { editLogicalRow = row },
                                )
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (row.relativeOffset >= 0) "+${row.relativeOffset}"
                                else row.relativeOffset.toString(),
                                modifier = Modifier.widthIn(min = 48.dp),
                                style = runtimeMemoryMetaTextStyle(),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.label,
                                    style = MaterialTheme.typography.bodyMedium,
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
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                                style = runtimeMemoryDataTextStyle(),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

            editLogicalRow?.let { row ->
                RuntimeInspectorLogicalEditDialog(
                    snapshot = snapshot,
                    row = row,
                    actions = actions,
                    onDismiss = {
                        editLogicalRow = null
                    },
                )
            }
        }
    }
}

@Composable
private fun RuntimeInspectorLogicalEditDialog(
    snapshot: MemoryInspectorSnapshot,
    row: MemoryInspectorLogicalRow,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    val spec = remember(row.type) { MemoryInputSpec.forType(row.type) }
    var value by remember(row) {
        mutableStateOf(TextFieldValue(row.valueText, TextRange(row.valueText.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            RuntimeSearchDialogBody(
                title = {
                    RuntimeInputDialogTitle(
                        title = row.label.ifBlank { runtimeTypeShort(row.type) },
                    )
                },
                showKeypad = true,
                controls = { _ ->
                    RuntimeSearchField(
                        label = "${runtimeTypeShort(row.type)} · ${row.relativeOffset.formatRelativeOffset()}",
                        value = value,
                        onClick = {},
                        onValueChange = { value = it },
                        valueSpec = spec,
                        modifier = Modifier.fillMaxWidth(),
                        initialFocus = true,
                    )
                },
                keypad = { landscape ->
                    RuntimeSearchKeypad(
                        landscape = landscape,
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
        textScrollable = true,
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
    onClick: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    valueSpec: MemoryInputSpec,
    modifier: Modifier = Modifier,
    initialFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(initialFocus) {
        if (initialFocus) focusRequester.requestFocus()
    }
    // Keep Compose's editing, selection, cursor and horizontal scrolling. Only the platform
    // IME session is suppressed: this editor supplies its own numeric keypad.
    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (valueSpec.acceptsPartial(it.text)) onValueChange(it) },
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        keyboardController?.hide()
                        onClick()
                    }
                },
            label = { Text(label) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            textStyle = runtimeMemoryDataTextStyle(),
        )
    }
}

@Composable
private fun RuntimeSearchKeypad(
    landscape: Boolean,
    allowGroup: Boolean,
    valueSpec: MemoryInputSpec? = null,
    onToken: (String) -> Unit,
    onBackspace: () -> Unit,
    onMove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    fun token(label: String, enabled: Boolean = true) = RuntimeKeypadCell(
        label = label,
        enabled = enabled,
        onClick = { onToken(label) },
    )

    val clearLabel = stringResource(R.string.memory_editor_keypad_clear)
    val columns = if (landscape) 5 else 4
    val cells = if (landscape) {
        listOf(
            token("1"), token("2"), token("3"),
            RuntimeKeypadCell("⌫", onClick = onBackspace),
            RuntimeKeypadCell("←", onClick = { onMove(-1) }),
            token("4"), token("5"), token("6"),
            RuntimeKeypadCell("→", onClick = { onMove(1) }),
            token("-", enabled = valueSpec?.signed ?: true),
            token("7"), token("8"), token("9"),
            token(".", enabled = valueSpec?.decimal ?: true),
            token("E", enabled = valueSpec?.exponent ?: true),
            token("0"),
            token(";", enabled = allowGroup),
            RuntimeKeypadCell(clearLabel, onClick = onClear, span = 3),
        )
    } else {
        listOf(
            token("1"), token("2"), token("3"),
            RuntimeKeypadCell("⌫", onClick = onBackspace),
            token("4"), token("5"), token("6"),
            RuntimeKeypadCell("←", onClick = { onMove(-1) }),
            token("7"), token("8"), token("9"),
            RuntimeKeypadCell("→", onClick = { onMove(1) }),
            token("-", enabled = valueSpec?.signed ?: true),
            token("0"),
            token(".", enabled = valueSpec?.decimal ?: true),
            token("E", enabled = valueSpec?.exponent ?: true),
            token(";", enabled = allowGroup),
            RuntimeKeypadCell(clearLabel, onClick = onClear, span = 3),
        )
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 4.dp
        val cellWidth = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            cells.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { cell ->
                        RuntimeKeypadButton(
                            cell,
                            Modifier.width(cellWidth * cell.span + gap * (cell.span - 1)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeKeypadButton(cell: RuntimeKeypadCell, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = cell.onClick,
        enabled = cell.enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        // Retain text-field focus while entering a number with the on-screen keypad.
        modifier = modifier.heightIn(min = 48.dp).focusProperties { canFocus = false },
    ) {
        Text(cell.label, style = runtimeMemoryKeypadTextStyle(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun RuntimeInputDialogTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
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
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RuntimeChoiceMenu(
        value = predicate,
        values = memoryKnownSearchPredicates(),
        label = { runtimePredicateName(it) },
        onChange = onPredicate,
        modifier = modifier,
        enabled = enabled,
        centerContent = true,
    )
}

@Composable
private fun RuntimeRelativeMenu(
    predicate: Int,
    onPredicate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    RuntimeChoiceMenu(
        value = predicate,
        values = memoryUnknownSearchPredicates(),
        label = { runtimePredicateName(it) },
        onChange = onPredicate,
        modifier = modifier,
        centerContent = true,
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
        values = RuntimeMemoryValueTypes,
        label = { runtimeTypeName(it) },
        onChange = onType,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = if (type == MemoryEngineContract.TYPE_AUTO) {
            R.drawable.ic_auto_mode
        } else null,
        centerContent = true,
        showTrailingIcon = false,
    )
}

@Composable
private fun RuntimeEditTypeMenu(
    type: Int,
    types: List<Int>,
    onType: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    RuntimeChoiceMenu(
        value = type,
        values = types.toIntArray(),
        label = { runtimeTypeName(it) },
        onChange = onType,
        modifier = modifier,
        enabled = types.size > 1,
        centerContent = true,
        showTrailingIcon = false,
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
    leadingIcon: Int? = null,
    centerContent: Boolean = false,
    showTrailingIcon: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val containerColor = accent.copy(alpha = 0.10f)
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            leadingIcon?.let { icon ->
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label(value),
                modifier = Modifier.weight(1f),
                style = runtimeMemoryControlTextStyle(),
                textAlign = if (centerContent) TextAlign.Center else TextAlign.Start,
            )
            if (showTrailingIcon) {
                Spacer(Modifier.width(4.dp))
                Icon(painterResource(R.drawable.ic_keyboard_arrow_down), contentDescription = null)
            }
        }
        if (expanded) {
            AlertDialog(
                onDismissRequest = { expanded = false },
                maxDialogWidth = 400.dp,
                text = {
                    Column {
                        values.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(label(item), style = runtimeMemoryControlTextStyle()) },
                                leadingIcon = {
                                    RadioButton(selected = item == value, onClick = null)
                                },
                                onClick = { expanded = false; onChange(item) },
                            )
                        }
                    }
                },
            )
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
            pluralStringResource(
                R.plurals.memory_editor_expression_group_hint,
                expression.values.size,
                expression.values.size,
            )
        }
        is MemorySearchExpression.Invalid -> when (expression.reason) {
            else -> stringResource(R.string.memory_editor_expression_query_help)
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RuntimePager(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    if (state.resultCount <= MemoryEditorComposeController.PAGE_SIZE) return
    val previousPageDescription = stringResource(R.string.memory_editor_previous_results_page_action)
    val nextPageDescription = stringResource(R.string.memory_editor_next_results_page_action)
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = actions::previousPage,
            enabled = state.pageOffset > 0,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = previousPageDescription },
        ) {
            Text("‹", modifier = Modifier.clearAndSetSemantics {})
        }
        Text(
            "${numberFormat.format(state.pageOffset + 1)}–" +
                "${numberFormat.format(minOf(state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE, state.resultCount))}" +
                " / ${numberFormat.format(state.resultCount)}",
            style = runtimeMemoryMetaTextStyle(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(
            onClick = actions::nextPage,
            enabled = state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE < state.resultCount,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = nextPageDescription },
        ) {
            Text("›", modifier = Modifier.clearAndSetSemantics {})
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
    val label = stringResource(description)
    RuntimeIconTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(painterResource(icon), contentDescription = label)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeIconTooltip(label: String, content: @Composable () -> Unit) {
    val state = rememberTooltipState()
    val peeking = LocalMemoryEditorOverlay.current?.peeking == true
    LaunchedEffect(peeking) { if (peeking) state.dismiss() }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            androidx.compose.material3.TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = state,
        focusable = false,
        enableUserInput = !peeking,
        content = content,
    )
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

private fun memorySearchFieldLabel(predicate: Int, second: Boolean): Int = when (predicate) {
    MemoryEngineContract.PREDICATE_BETWEEN -> if (second) {
        R.string.memory_editor_max_value
    } else {
        R.string.memory_editor_min_value
    }
    MemoryEngineContract.PREDICATE_INCREASED_BY,
    MemoryEngineContract.PREDICATE_DECREASED_BY,
    MemoryEngineContract.PREDICATE_CHANGED_BY,
    -> R.string.memory_editor_change_amount
    MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE,
    MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE,
    -> if (second) {
        R.string.memory_editor_max_change
    } else {
        R.string.memory_editor_min_change
    }
    else -> R.string.memory_editor_search_hint
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

@Composable
private fun runtimeTypeName(type: Int): String = when (type) {
    MemoryEngineContract.TYPE_AUTO -> stringResource(R.string.memory_editor_type_auto)
    MemoryEngineContract.TYPE_BYTE -> "Int8"
    MemoryEngineContract.TYPE_SHORT -> "Int16"
    MemoryEngineContract.TYPE_CHAR -> "UInt16"
    MemoryEngineContract.TYPE_INT -> "Int32"
    MemoryEngineContract.TYPE_LONG -> "Int64"
    MemoryEngineContract.TYPE_FLOAT -> "Float32"
    MemoryEngineContract.TYPE_DOUBLE -> "Float64"
    else -> "?"
}

@Composable
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
