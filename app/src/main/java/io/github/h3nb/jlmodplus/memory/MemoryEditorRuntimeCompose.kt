/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

// Modifications: custom keypad-only value input and IME-safe dialogs.

package io.github.h3nb.jlmodplus.memory

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.availableWindowHeightDp
import io.github.h3nb.jlmodplus.ui.availableWindowWidthDp
import io.github.h3nb.jlmodplus.ui.jlModPlusFilterChipColors
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class RuntimeMemoryTab { SEARCH_RESULTS, WATCH, INSPECTOR }
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

private const val RuntimeKeypadTransitionDurationMillis = 240
// Material3 text fields use a 56dp intrinsic height. Sharing it with the outlined menus keeps
// their top/bottom strokes aligned without clipping the value baseline or blinking caret.
private val RuntimeInputControlHeight = 56.dp
// OutlinedTextField reserves an 8dp top inset for its floating label. The surrounding selector
// controls do not have that inset, so compensate inside the fixed 56dp row slot. Extending by
// the inset and shifting by half keeps both the outline and its text baseline centered together.
private val RuntimeOutlinedFieldLabelInset = 8.dp
private val RuntimeKeypadButtonHeight = 48.dp
private val RuntimeKeypadPortraitHeight = 256.dp
private val RuntimeKeypadLandscapeHeight = 198.dp

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

@Composable
private fun runtimeMemoryInspectorValueTextStyle() = MaterialTheme.typography.titleSmall.copy(
    fontFamily = FontFamily.Monospace,
)

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
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
    onPeekUnderlayChanged: (Boolean) -> Unit,
    onInspectSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var knownDialog by remember { mutableStateOf(false) }
    var unknownDialog by remember { mutableStateOf(false) }
    var editTargets by remember { mutableStateOf<List<MemoryEditTarget>?>(null) }
    var editRevision by remember { mutableStateOf(0L) }
    val unknownBaseline = state.sessionStage == MemorySessionStage.UNKNOWN_BASELINE
    val selectedRows = state.results.filter { it.id in state.selected }
    val selectedWriteSupported = selectedRows.isNotEmpty() && state.writeSupported
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
                enabled = !state.busy && state.supported,
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
                enabled = selectedWriteSupported && !state.busy,
                onClick = {
                    editTargets = selectedRows.map { row ->
                        MemoryEditTarget(row.id, row.primaryType, row.valueText, row.aliasTypes)
                    }
                    editRevision = state.revision
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
                enabled = state.selected.isNotEmpty() && !state.busy && !unknownBaseline,
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
                    Text(
                        stringResource(R.string.memory_editor_no_candidates),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        style = runtimeMemoryDataTextStyle(),
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
                        row.locationText,
                        style = runtimeMemoryMetaTextStyle(),
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
    val selectedWriteSupported = selectedRows.isNotEmpty() && state.writeSupported

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
                    MemoryEditTarget(row.id, row.type, row.valueText, listOf(row.type), watch = true)
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
                        style = runtimeMemoryDataTextStyle(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${row.locationText} · ${runtimeTypeShort(row.type)}",
                        style = runtimeMemoryMetaTextStyle(),
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


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuntimeSearchDialogBody(
    showKeypad: Boolean,
    controls: @Composable (sideDock: Boolean) -> Unit,
    keypad: @Composable (landscape: Boolean) -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val landscape = availableWindowWidthDp() > availableWindowHeightDp()
    val imeVisible = WindowInsets.isImeVisible
    // BasicAlertDialog's platform window already moves its frame above the IME. Applying
    // imePadding here as well reserves that height a second time and creates a white band.
    // Keep the native IME and custom keypad mutually exclusive. The keypad is given a fixed
    // viewport and only its layer is animated, so the dialog window is not resized and recentered
    // on every animation frame.
    val targetKeypadVisible = showKeypad && !imeVisible
    var keypadMounted by remember { mutableStateOf(targetKeypadVisible) }
    // The default virtual keypad is part of the dialog's first frame. Only later mode changes
    // animate; opening New Search/Edit must not briefly compose an empty, differently sized body.
    val keypadProgress = remember { Animatable(if (targetKeypadVisible) 1f else 0f) }

    LaunchedEffect(targetKeypadVisible, imeVisible) {
        if (targetKeypadVisible) {
            keypadMounted = true
            keypadProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = RuntimeKeypadTransitionDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else if (imeVisible) {
            // Once the platform IME takes over, remove the custom keypad immediately. The
            // system owns the window resize animation and there is no competing layout motion.
            keypadProgress.snapTo(0f)
            keypadMounted = false
        } else {
            keypadProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = RuntimeKeypadTransitionDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            )
            keypadMounted = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Keep the layout mode stable for the entire custom-keypad transition. A fixed viewport
        // prevents the platform dialog from repeatedly measuring and moving while the layer
        // fades/scales in or out.
        val sideDock = landscape && maxWidth >= 480.dp &&
            (targetKeypadVisible || (keypadMounted && !imeVisible))
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
                    if (keypadMounted && !imeVisible) {
                        Box(
                            modifier = Modifier
                                .width(keypadWidth)
                                .height(RuntimeKeypadLandscapeHeight)
                                .graphicsLayer {
                                    val progress = keypadProgress.value
                                    alpha = progress
                                    translationX = (1f - progress) * 20.dp.toPx()
                                },
                        ) {
                            keypad(landscape)
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                controls(false)
                supportingContent?.invoke()
                if (keypadMounted && !imeVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(RuntimeKeypadPortraitHeight)
                            .graphicsLayer {
                                val progress = keypadProgress.value
                                alpha = progress
                                translationY = (1f - progress) * 12.dp.toPx()
                            },
                    ) {
                        keypad(landscape)
                    }
                }
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
    var peekingUnderlay by remember { mutableStateOf(false) }

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
                            predicate = effectivePredicate,
                            onPredicate = { predicate = it },
                            enabled = !groupExpression,
                            modifier = Modifier.widthIn(min = 72.dp, max = 112.dp),
                        )
                        RuntimeSearchField(
                            label = stringResource(R.string.memory_editor_search_hint),
                            value = query,
                            active = activeField == RuntimeInputField.FIRST,
                            onClick = { activeField = RuntimeInputField.FIRST },
                            onValueChange = { query = it },
                            valueSpec = spec,
                            modifier = Modifier.weight(1f),
                            initialFocus = true,
                        )
                    }
                    if (needsSecond) {
                        RuntimeSearchField(
                            label = stringResource(R.string.memory_editor_max_value),
                            value = second,
                            active = activeField == RuntimeInputField.SECOND,
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
                        enabled = nextScanValid && !state.busy,
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
    onPeekUnderlayChanged: (Boolean) -> Unit,
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
    var peekingUnderlay by remember { mutableStateOf(false) }
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
                                        label = stringResource(R.string.memory_editor_search_hint),
                                        value = first,
                                        active = activeField == RuntimeInputField.FIRST,
                                        onClick = { activeField = RuntimeInputField.FIRST },
                                        onValueChange = { first = it },
                                        valueSpec = spec,
                                        modifier = Modifier.weight(1f),
                                        initialFocus = true,
                                    )
                                    RuntimeSearchField(
                                        label = stringResource(R.string.memory_editor_max_value),
                                        value = second,
                                        active = activeField == RuntimeInputField.SECOND,
                                        onClick = { activeField = RuntimeInputField.SECOND },
                                        onValueChange = { second = it },
                                        valueSpec = spec,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                RuntimeSearchField(
                                    label = stringResource(R.string.memory_editor_search_hint),
                                    value = first,
                                    active = activeField == RuntimeInputField.FIRST,
                                    onClick = { activeField = RuntimeInputField.FIRST },
                                    onValueChange = { first = it },
                                    valueSpec = spec,
                                    modifier = Modifier.fillMaxWidth(),
                                    initialFocus = true,
                                )
                                if (needsSecond) {
                                    RuntimeSearchField(
                                        label = stringResource(R.string.memory_editor_max_value),
                                        value = second,
                                        active = activeField == RuntimeInputField.SECOND,
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
    val spec = remember(type) { MemoryInputSpec.forType(type) }
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
                                onValueChange = { replacement = it },
                                valueSpec = spec,
                                modifier = Modifier.weight(1f),
                                initialFocus = true,
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
                            onValueChange = { replacement = it },
                            valueSpec = spec,
                            modifier = Modifier.fillMaxWidth(),
                            initialFocus = true,
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
                keypad = { landscape ->
                    RuntimeSearchKeypad(
                        landscape = landscape,
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
    state.writeSupported

@Composable
private fun RuntimeInspectorTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onPeekUnderlayChanged: (Boolean) -> Unit,
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
                            snapshot.provenance.ifBlank { "logical" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                                enabled = row.editable &&
                                                    state.writeSupported && !state.busy,
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
                                            style = runtimeMemoryMetaTextStyle(),
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
                                            style = runtimeMemoryInspectorValueTextStyle(),
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
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

@Composable
private fun RuntimeInspectorLogicalEditDialog(
    snapshot: MemoryInspectorSnapshot,
    row: MemoryInspectorLogicalRow,
    actions: MemoryEditorActions,
    onPeekUnderlayChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val spec = remember(row.type) { MemoryInputSpec.forType(row.type) }
    var value by remember(row) {
        mutableStateOf(TextFieldValue(row.valueText, TextRange(row.valueText.length)))
    }
    var peekingUnderlay by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(if (peekingUnderlay) 0f else 1f),
        title = {
            RuntimeInputDialogTitle(
                title = (row.label.ifBlank { runtimeTypeShort(row.type) })
                    .memoryEditorTitleCase(),
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

private fun String.memoryEditorTitleCase(): String = trim()
    .split(Regex("\\s+"))
    .filter(String::isNotEmpty)
    .joinToString(" ") { word ->
        word.replaceFirstChar { first -> first.titlecase(Locale.getDefault()) }
    }

@Composable
private fun RuntimeSearchField(
    label: String,
    value: TextFieldValue,
    active: Boolean,
    onClick: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    valueSpec: MemoryInputSpec,
    modifier: Modifier = Modifier,
    initialFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var focused by remember { mutableStateOf(false) }
    var caretVisible by remember { mutableStateOf(true) }
    val accent = MaterialTheme.colorScheme.primary
    val textStyle = runtimeMemoryDataTextStyle().copy(
        color = if (active) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val caretIndex = value.selection.start.coerceIn(0, value.text.length)
    val caretPrefixWidth = with(density) {
        textMeasurer.measure(
            text = value.text.take(caretIndex),
            style = textStyle,
        ).size.width.toDp()
    }
    val textWidth = with(density) {
        textMeasurer.measure(
            text = value.text,
            style = textStyle,
        ).size.width.toDp()
    }
    LaunchedEffect(initialFocus) {
        if (initialFocus) {
            // Keep focus for the custom caret and keypad routing without starting a platform IME.
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(focused, caretIndex) {
        caretVisible = true
        if (!focused) return@LaunchedEffect
        while (isActive) {
            delay(500L)
            caretVisible = !caretVisible
        }
    }
    BoxWithConstraints(
        modifier = modifier.requiredHeight(RuntimeInputControlHeight),
    ) {
        val caretStart = 16.dp
        val caretEnd = (maxWidth - 18.dp).coerceAtLeast(caretStart)
        val visibleTextWidth = (caretEnd - caretStart).coerceAtLeast(1.dp)
        val caretUnscrolledOffset = caretStart + caretPrefixWidth
        val maxTextScroll = (textWidth - visibleTextWidth).coerceAtLeast(0.dp)
        // Mirror the single-line field's horizontal scroll just enough to keep the custom
        // caret attached to a long value instead of letting it run beyond the outline.
        val textScroll = (caretUnscrolledOffset - caretEnd)
            .coerceAtLeast(0.dp)
            .coerceAtMost(maxTextScroll)
        val visibleCaretOffset = (caretUnscrolledOffset - textScroll)
            .coerceIn(caretStart, caretEnd)
        OutlinedTextField(
            value = value,
            onValueChange = { updated ->
                if (valueSpec.acceptsPartial(updated.text)) onValueChange(updated)
            },
            modifier = Modifier
                .fillMaxWidth()
                .requiredHeight(RuntimeInputControlHeight + RuntimeOutlinedFieldLabelInset)
                .offset(y = -(RuntimeOutlinedFieldLabelInset / 2))
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    // Hide any already-visible IME when focus moves between the value field and
                    // predicate/type controls. All value editing is intentionally keypad-only.
                    keyboardController?.hide()
                    if (it.isFocused) onClick()
                },
            label = { Text(label) },
            singleLine = true,
            readOnly = true,
            shape = MaterialTheme.shapes.extraLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                focusedLabelColor = accent,
                unfocusedBorderColor = accent.copy(alpha = 0.62f),
                unfocusedLabelColor = accent,
                cursorColor = accent,
            ),
            textStyle = textStyle,
        )
        if (focused && caretVisible) {
            // Read-only TextField intentionally owns focus so the custom keypad can edit it
            // without starting the platform IME. Material3 does not draw a caret for every
            // read-only configuration, therefore render the insertion marker in the same
            // content inset and blink it independently of keyboard visibility.
            Box(
                modifier = Modifier
                    .offset(x = visibleCaretOffset, y = 17.dp)
                    .width(2.dp)
                    .height(22.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rows = if (landscape) 4 else 5
        val verticalSpacing = if (landscape) 2.dp else 4.dp
        // Short landscape dialogs can have less height than the preferred 48dp x 4 table.
        // Scale every cell from the same available row height so the final row is never clipped.
        val cellHeight = (
            (maxHeight - (verticalSpacing * (rows - 1))) / rows
        ).coerceAtMost(RuntimeKeypadButtonHeight).coerceAtLeast(32.dp)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            userScrollEnabled = false,
        ) {
            cells.forEach { cell ->
                item(span = { GridItemSpan(cell.span) }) {
                    RuntimeKeypadButton(cell, height = cellHeight)
                }
            }
        }
    }
}

@Composable
private fun RuntimeKeypadButton(
    cell: RuntimeKeypadCell,
    height: Dp,
) {
    OutlinedButton(
        onClick = cell.onClick,
        enabled = cell.enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.36f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(0.dp),
        // Keep the text field as the focus owner while the virtual keypad is tapped so the
        // insertion caret remains visible without handing input to the platform IME.
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .focusProperties { canFocus = false },
    ) {
        Text(
            cell.label,
            modifier = Modifier.fillMaxWidth(),
            style = runtimeMemoryKeypadTextStyle(),
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
        Text(
            title,
            modifier = Modifier.weight(1f),
        )
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
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
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
    modifier: Modifier = Modifier.fillMaxWidth(),
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
            R.drawable.ic_auto_awesome
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
    modifier: Modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth().height(RuntimeInputControlHeight),
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            if (centerContent && showTrailingIcon) {
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leadingIcon?.let { icon ->
                            Icon(
                                painter = painterResource(icon),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            label(value),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = runtimeMemoryControlTextStyle(),
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterEnd).size(24.dp),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (centerContent) {
                        Arrangement.Center
                    } else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIcon?.let { icon ->
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        label(value),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = runtimeMemoryControlTextStyle(),
                    )
                    if (showTrailingIcon) {
                        Spacer(Modifier.weight(1f))
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { item ->
                DropdownMenuItem(
                    text = { Text(label(item), style = runtimeMemoryControlTextStyle()) },
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = actions::previousPage,
            enabled = state.pageOffset > 0,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Text("‹")
        }
        Text(
            "${state.pageOffset + 1}–${minOf(state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE, state.resultCount)}",
            style = runtimeMemoryMetaTextStyle(),
        )
        TextButton(
            onClick = actions::nextPage,
            enabled = state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE < state.resultCount,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
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

private fun compactCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
