/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

// Modified for JL-Mod Plus.

package io.github.h3nb.jlmodplus.memory

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
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
import io.github.h3nb.jlmodplus.ui.PAGE_TRAVEL_DP
import io.github.h3nb.jlmodplus.ui.pageMotionDirection
import io.github.h3nb.jlmodplus.ui.tabEnter
import io.github.h3nb.jlmodplus.ui.tabExit
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
    MemoryEngineContract.TYPE_BOOLEAN,
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
        val tabTravelPx = with(LocalDensity.current) { PAGE_TRAVEL_DP.dp.roundToPx() }
        var opacityDialog by remember { mutableStateOf(false) }
        // The Activity must finish to return input to the game. These fields survive its
        // destruction in the memory-engine process, scoped to this MIDlet runtime token.
        val savedScript = remember(state.runtimeToken) {
            MemoryEditorSessionNavigation.script(state.runtimeToken)
        }
        var scriptEditing by rememberSaveable(state.runtimeToken) {
            mutableStateOf(savedScript.editing)
        }
        var scriptReplacingId by rememberSaveable(state.runtimeToken) {
            mutableStateOf(savedScript.replacingId)
        }
        var scriptInitialText by rememberSaveable(state.runtimeToken) {
            mutableStateOf(savedScript.initialText)
        }
        var scriptText by rememberSaveable(state.runtimeToken) {
            mutableStateOf(savedScript.text)
        }
        var selectedScriptId by rememberSaveable(state.runtimeToken) {
            mutableStateOf(savedScript.selectedId)
        }
        SideEffect {
            MemoryEditorSessionNavigation.rememberScript(
                state.runtimeToken,
                MemoryEditorScriptWorkspace(
                    selectedId = selectedScriptId,
                    editing = scriptEditing,
                    replacingId = scriptReplacingId,
                    initialText = scriptInitialText,
                    text = scriptText,
                ),
            )
        }
        val scriptDraft = if (scriptEditing) {
            MemoryScriptDraft(scriptReplacingId, scriptInitialText, scriptText)
        } else null
        val setScriptDraft: (MemoryScriptDraft?) -> Unit = { draft ->
            scriptEditing = draft != null
            scriptReplacingId = draft?.replacingId
            scriptInitialText = draft?.initialText.orEmpty()
            scriptText = draft?.text.orEmpty()
        }
        var scriptDiscardMode by remember { mutableStateOf<String?>(null) }
        val backFromScriptEditor = {
            if (scriptDraft?.dirty == true) scriptDiscardMode = "back"
            else setScriptDraft(null)
        }
        val closeWorkspace = { actions.close() }
        val resultsListState = rememberLazyListState()
        val variablesListState = rememberLazyListState()
        var relinkingBookmarkId by rememberSaveable(state.runtimeToken) {
            mutableStateOf<String?>(null)
        }
        val listEntries = remember(state.savedVariables, state.watches) {
            memoryListEntries(state.savedVariables, state.watches)
        }
        var listSelection by remember { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(listEntries.map(MemoryListEntry::key)) {
            listSelection = listSelection.intersect(listEntries.mapTo(HashSet()) { it.key })
        }
        val selectedListEntry = listEntries.singleOrNull { it.key in listSelection }
            ?.takeIf { listSelection.size == 1 }
        val canInspect = if (state.variablesTab) {
            selectedListEntry != null && !state.busy &&
                (selectedListEntry.saved?.let {
                    state.savedValues[it.id]?.resolved == true
                } == true || selectedListEntry.watch?.let {
                    it.state != MemoryEngineContract.CANDIDATE_LOST
                } == true)
        } else state.selected.size == 1 && !state.busy
        val inspectSelected = {
            if (state.variablesTab) {
                selectedListEntry?.let { entry ->
                    val saved = entry.saved?.takeIf {
                        state.savedValues[it.id]?.resolved == true
                    }
                    if (saved != null) actions.inspectSavedVariable(saved.id)
                    else entry.watch?.takeIf {
                        it.state != MemoryEngineContract.CANDIDATE_LOST
                    }?.let { actions.inspectCandidate(it.id, watchAnchor = true) }
                }
            } else {
                state.selected.singleOrNull()?.let {
                    actions.inspectCandidate(it, watchAnchor = false)
                }
            }
            Unit
        }
        val selectTab: (MemoryEditorTab) -> Unit = {
            when (it) {
                MemoryEditorTab.RESULTS -> actions.setVariablesTab(false)
                MemoryEditorTab.VARIABLES -> actions.setVariablesTab(true)
                MemoryEditorTab.SCRIPTS -> actions.setScriptsTab()
                MemoryEditorTab.INSPECTOR -> if (state.activeTab != it) inspectSelected()
            }
        }
        BackHandler(enabled = tab == MemoryEditorTab.INSPECTOR || overlay.peeking ||
            scriptDraft?.dirty == true ||
            (tab == MemoryEditorTab.SCRIPTS &&
                (scriptDraft != null || selectedScriptId != null)) ||
            (state.variablesTab && listSelection.isNotEmpty())) {
            when {
                overlay.peeking -> overlay.peeking = false
                tab == MemoryEditorTab.INSPECTOR -> actions.closeInspector()
                tab == MemoryEditorTab.SCRIPTS && scriptDraft != null -> backFromScriptEditor()
                tab == MemoryEditorTab.SCRIPTS -> selectedScriptId = null
                scriptDraft?.dirty == true -> actions.close()
                else -> listSelection = emptySet()
            }
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
                        .windowInsetsPadding(
                            memoryEditorWorkspaceInsets().only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                            ),
                        )
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
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(
                            WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal),
                        )) {
                            RuntimeMemoryHeader(state, showLabels || !showTitle, selectTab, canInspect,
                                closeWorkspace, showTitle)
                        }
                        if (state.busy) RuntimeOperationStrip(state, actions)
                        state.message?.takeIf(String::isNotBlank)?.let {
                            RuntimeMessage(it, state.messageIsError)
                        }
                        // Use tab identity for motion, not the frequently changing memory snapshot.
                        // The outgoing snapshot is retained briefly so Inspector can fade out
                        // without showing an empty state after its target is closed.
                        AnimatedContent(
                            targetState = state,
                            contentKey = { it.activeTab },
                            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                            transitionSpec = {
                                val direction = pageMotionDirection(
                                    0, 0, initialState.activeTab.ordinal, targetState.activeTab.ordinal,
                                )
                                tabEnter(direction, tabTravelPx).togetherWith(tabExit(direction, tabTravelPx))
                            },
                            label = "Memory editor tab",
                        ) { shownState ->
                            val active = shownState.activeTab == tab
                            Box(
                                Modifier.fillMaxSize().then(
                                    if (active) Modifier else Modifier.clearAndSetSemantics {},
                                ),
                            ) {
                                when (shownState.activeTab) {
                                    MemoryEditorTab.RESULTS -> RuntimeSearchResultsTab(
                                        shownState, actions, inspectSelected, relinkingBookmarkId,
                                        { relinkingBookmarkId = null }, resultsListState,
                                        compactResults, Modifier.fillMaxSize(),
                                    )
                                    MemoryEditorTab.VARIABLES -> RuntimeVariablesTab(
                                        shownState, actions, listEntries, listSelection,
                                        { listSelection = it },
                                        { id ->
                                            relinkingBookmarkId = id
                                            actions.setVariablesTab(false)
                                        },
                                        variablesListState, compactResults, Modifier.fillMaxSize(),
                                    )
                                    MemoryEditorTab.SCRIPTS -> RuntimeScriptsTab(
                                        shownState, actions, selectedScriptId,
                                        { selectedScriptId = it }, scriptDraft,
                                        { setScriptDraft(it) }, backFromScriptEditor,
                                        Modifier.fillMaxSize(),
                                    )
                                    MemoryEditorTab.INSPECTOR -> RuntimeInspectorTab(
                                        shownState, actions, Modifier.fillMaxSize(),
                                    )
                                }
                                if (!active) {
                                    Box(
                                        Modifier.matchParentSize().clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {},
                                    )
                                }
                            }
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
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                                .union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                        )
                        .padding(memoryEditorBottomCornerPadding()),
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
        if (scriptDiscardMode != null) {
            AlertDialog(
                onDismissRequest = { scriptDiscardMode = null },
                title = { Text(stringResource(R.string.memory_scripts_unsaved_title)) },
                text = { Text(stringResource(R.string.memory_scripts_unsaved_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        scriptDiscardMode = null
                        setScriptDraft(null)
                    }) { Text(stringResource(R.string.memory_scripts_discard)) }
                },
                dismissButton = {
                    TextButton(onClick = { scriptDiscardMode = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun RuntimeMemoryHeader(
    state: MemoryEditorUiState,
    inlineNavigation: Boolean,
    onTab: (MemoryEditorTab) -> Unit,
    canInspect: Boolean,
    onClose: () -> Unit,
    showTitle: Boolean = true,
) {
    val density = LocalDensity.current
    val topPadding = if (inlineNavigation) {
        with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
    } else memoryEditorTitleTopPadding()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackedTitleMaxWidth = (maxWidth * 0.44f - 16.dp).coerceAtLeast(0.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = topPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTitle) Text(
                stringResource(R.string.memory_editor),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (inlineNavigation) Modifier.padding(end = 16.dp)
                    else Modifier.widthIn(max = stackedTitleMaxWidth),
            )
            if (inlineNavigation) {
                RuntimeMemoryTabs(state, onTab, canInspect, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close),
                    stringResource(R.string.memory_editor_minimize))
            }
        }
    }
    if (!inlineNavigation) RuntimeMemoryTabs(state, onTab, canInspect)
}

@Composable
private fun RuntimeMemoryTabs(
    state: MemoryEditorUiState,
    onTab: (MemoryEditorTab) -> Unit,
    canInspect: Boolean,
    modifier: Modifier = Modifier,
) {
    SecondaryScrollableTabRow(
        selectedTabIndex = state.activeTab.ordinal,
        modifier = modifier.fillMaxWidth(),
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
    ) {
        MemoryEditorTab.entries.forEach { tab ->
            val label = when (tab) {
                MemoryEditorTab.RESULTS -> R.string.memory_editor_search_tab
                MemoryEditorTab.VARIABLES -> R.string.memory_variables_tab
                MemoryEditorTab.SCRIPTS -> R.string.memory_scripts_title
                MemoryEditorTab.INSPECTOR -> R.string.memory_editor_inspector
            }
            val enabled = tab != MemoryEditorTab.INSPECTOR ||
                state.activeTab == tab || canInspect
            val description = stringResource(label)
            Tab(
                selected = state.activeTab == tab,
                enabled = enabled,
                onClick = { onTab(tab) },
                modifier = Modifier.semantics { contentDescription = description },
                text = {
                    Text(description, color = if (enabled) Color.Unspecified
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
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
    relinkingBookmarkId: String?,
    onRelinkComplete: () -> Unit,
    listState: LazyListState,
    compactRows: Boolean,
    modifier: Modifier = Modifier,
) {
    var knownDialog by remember { mutableStateOf(false) }
    var unknownDialog by remember { mutableStateOf(false) }
    var relinkCandidate by remember { mutableStateOf<Long?>(null) }
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
        if (relinkingBookmarkId != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.memory_bookmark_relink_hint),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRelinkComplete) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
        val quickActions = listOf(
            RuntimeToolbarAction(R.drawable.ic_search, R.string.memory_editor_search_known_values, state.canSearch) {
                knownDialog = true
            },
            RuntimeToolbarAction(R.drawable.ic_memory_editor_search_unknown,
                R.string.memory_editor_search_unknown_values, state.canSearch) {
                unknownDialog = true
            },
            RuntimeToolbarAction(R.drawable.ic_memory_editor_edit_value, R.string.memory_editor_edit, selectedWriteSupported && !state.busy) {
                editTargets = selectedRows.map { MemoryEditTarget(it.id, it.primaryType, it.valueText, it.aliasTypes) }
                editRevision = state.revision
            },
            RuntimeToolbarAction(R.drawable.ic_memory_editor_watch_add, R.string.memory_list_add,
                selectedRows.isNotEmpty() && !state.busy) { actions.watchSelected(true) },
            RuntimeToolbarAction(R.drawable.ic_refresh, R.string.memory_editor_refresh,
                state.results.isNotEmpty() && !state.busy, actions::refresh),
        )
        val extraActions = listOf(
            RuntimeToolbarAction(R.drawable.ic_memory_editor_inspector, R.string.memory_editor_inspect_memory,
                selectedRows.size == 1 && !state.busy, onInspectSelected),
            RuntimeToolbarAction(R.drawable.ic_memory_editor_watch_add,
                R.string.memory_bookmark_relink,
                relinkingBookmarkId != null && selectedRows.size == 1 && !state.busy) {
                relinkCandidate = selectedRows.single().id
            },
            RuntimeToolbarAction(R.drawable.ic_undo, R.string.memory_editor_undo,
                state.canUndo && !state.busy, actions::undo),
            RuntimeToolbarAction(R.drawable.ic_delete, R.string.memory_editor_remove,
                selectedRows.isNotEmpty() && !unknownBaseline && !state.busy) {
                deleteSnapshot = state.revision to selectedRows.map(MemoryResultRow::id)
            },
        )
        // Pack actions from the real toolbar width. A 48 dp button is always
        // preferred over More when it fits; selected actions may appear directly
        // without moving the five frequent actions in a spacious window.
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(
            WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal),
        )) {
            val showSummary = compactRows && (state.results.isNotEmpty() || state.resultCount > 0L)
            val summaryReserve = if (showSummary) {
                160.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            } else 0.dp
            val slotCount = ((maxWidth - 16.dp - summaryReserve).value / 48f)
                .toInt().coerceAtLeast(2)
            val availableActions = quickActions + extraActions.filter { it.enabled }
            val directLimit = if (availableActions.size > slotCount) slotCount - 1 else slotCount
            val primaryActions = if (directLimit >= quickActions.size) {
                availableActions.take(directLimit)
            } else {
                // Prioritize known search, edit and refresh when space is scarce.
                val priority = listOf(0, 2, 4, 1, 3).take(directLimit)
                quickActions.filterIndexed { index, _ -> index in priority }
            }
            val overflowActions = availableActions.filterNot { it in primaryActions }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = if (compactRows) Arrangement.End else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSummary) {
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
    if (relinkingBookmarkId != null && relinkCandidate != null) {
        val candidate = relinkCandidate!!
        AlertDialog(
            onDismissRequest = { relinkCandidate = null },
            title = { Text(stringResource(R.string.memory_bookmark_relink)) },
            text = { Text(stringResource(R.string.memory_bookmark_relink_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.relinkBookmark(relinkingBookmarkId, candidate, state.revision)
                    relinkCandidate = null
                    onRelinkComplete()
                }) { Text(stringResource(R.string.memory_bookmark_relink)) }
            },
            dismissButton = {
                TextButton(onClick = { relinkCandidate = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
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


private data class SavedEditDraft(
    val row: SavedVariableRow,
    val targets: List<MemoryEditTarget>,
)

@Composable
private fun RuntimeVariablesTab(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    entries: List<MemoryListEntry>,
    selectedKeys: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onRelink: (String) -> Unit,
    listState: LazyListState,
    compactRows: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuKey by remember { mutableStateOf<String?>(null) }
    var renameKey by remember { mutableStateOf<String?>(null) }
    var removeKeys by remember { mutableStateOf(emptySet<String>()) }
    var savedDraft by remember { mutableStateOf<SavedEditDraft?>(null) }
    var watchDraft by remember { mutableStateOf<List<MemoryEditTarget>?>(null) }

    val selectedEntries = entries.filter { it.key in selectedKeys }
    fun select(entry: MemoryListEntry) {
        onSelectionChange(if (entry.key in selectedKeys) selectedKeys - entry.key
            else selectedKeys + entry.key)
    }
    fun edit(entry: MemoryListEntry) {
        if (!state.writeSupported || state.busy) return
        val saved = entry.saved?.takeIf { state.savedValues[it.id]?.resolved == true }
        if (saved != null) {
            actions.beginSavedEdit(saved.id) { current ->
                if (current != null) savedDraft = SavedEditDraft(
                    saved, listOf(MemoryEditTarget(
                        0L, current.type, current.value, listOf(current.type), watch = true,
                    )),
                )
            }
        } else {
            val watch = entry.watch?.takeIf {
                it.state != MemoryEngineContract.CANDIDATE_LOST
            } ?: return
            watchDraft = listOf(MemoryEditTarget(
                watch.id, watch.type, watch.valueText, listOf(watch.type), watch = true,
            ))
        }
    }
    fun inspect(entry: MemoryListEntry) {
        val saved = entry.saved?.takeIf { state.savedValues[it.id]?.resolved == true }
        if (saved != null) actions.inspectSavedVariable(saved.id)
        else entry.watch?.takeIf {
            it.state != MemoryEngineContract.CANDIDATE_LOST
        }?.let { actions.inspectCandidate(it.id, watchAnchor = true) }
    }
    fun toggleFreeze(entry: MemoryListEntry) {
        val saved = entry.saved
        val watch = entry.watch
        val active = (watch?.freezeMode ?: -1) >= 0
        val value = saved?.let { state.savedValues[it.id]?.value } ?: watch?.valueText
        if (active) {
            if (saved != null) actions.clearSavedWatchFreeze(saved.id)
            else watch?.let { actions.setListWatchFreeze(it.id, "", false) }
        } else if (!value.isNullOrBlank()) {
            if (saved != null) actions.freezeListSaved(saved.id, value)
            else watch?.let { actions.setListWatchFreeze(it.id, value, true) }
        }
    }
    val menuEntry = entries.firstOrNull { it.key == menuKey }
    val renameEntry = entries.firstOrNull { it.key == renameKey }
    val removal = entries.filter { it.key in removeKeys }

    val single = selectedEntries.singleOrNull()
    val singleReady = single != null &&
        (single.saved?.let { state.savedValues[it.id]?.resolved == true } == true ||
            single.watch?.let { it.state != MemoryEngineContract.CANDIDATE_LOST } == true)
    val savedReady = single?.saved?.let { state.savedValues[it.id]?.resolved == true } == true
    val canBatchEdit = selectedEntries.size > 1 &&
        selectedEntries.all { it.saved == null && it.watch != null }
    val editEnabled = state.writeSupported && !state.busy && (singleReady || canBatchEdit)
    val freezeEnabled = single != null && !state.busy &&
        ((single.watch?.freezeMode ?: -1) >= 0 ||
            (state.writeSupported && (single?.saved == null || savedReady) &&
                (single?.saved?.let { state.savedValues[it.id]?.value }
                    ?: single?.watch?.valueText)?.isNotBlank() == true))
    fun editSelection() {
        if (single != null) edit(single)
        else if (canBatchEdit && editEnabled) {
            watchDraft = selectedEntries.mapNotNull { entry ->
                entry.watch?.let { watch ->
                    MemoryEditTarget(watch.id, watch.type, watch.valueText,
                        listOf(watch.type), watch = true)
                }
            }
        }
    }
    var moreActions by remember { mutableStateOf(false) }
    var recipeDialog by remember { mutableStateOf(false) }
    var recipeSourceKey by remember { mutableStateOf<String?>(null) }
    val primaryActions = listOf(
        RuntimeToolbarAction(R.drawable.ic_memory_editor_edit_value,
            R.string.memory_editor_edit, editEnabled, ::editSelection),
        RuntimeToolbarAction(R.drawable.ic_refresh,
            R.string.memory_editor_refresh, !state.busy) {
            actions.refresh()
            actions.refreshSavedVariables()
        },
    )
    val contextualActions = listOf(
        RuntimeToolbarAction(R.drawable.ic_memory_editor_inspector,
            R.string.memory_editor_inspect_memory, singleReady && !state.busy) {
            single?.let(::inspect)
        },
        RuntimeToolbarAction(R.drawable.ic_memory_editor_watch_add,
            R.string.memory_bookmark_relink,
            single?.saved != null && !state.busy) {
            single?.saved?.let { onRelink(it.id) }
        },
        RuntimeToolbarAction(
            if ((single?.watch?.freezeMode ?: -1) >= 0) R.drawable.ic_unlock
            else R.drawable.ic_lock,
            if ((single?.watch?.freezeMode ?: -1) >= 0) R.string.memory_editor_unfreeze
            else R.string.memory_editor_freeze,
            freezeEnabled,
        ) { single?.let(::toggleFreeze) },
        RuntimeToolbarAction(R.drawable.ic_memory_editor_rename,
            R.string.memory_saved_rename, single != null && !state.busy) {
            renameKey = single?.key
        },
        RuntimeToolbarAction(R.drawable.ic_delete,
            R.string.memory_editor_remove, selectedEntries.isNotEmpty() && !state.busy) {
            removeKeys = selectedKeys
        },
    ).filter { it.enabled }

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(
            WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal),
        )) {
            // Reserve touch space for select-all and the count; keep frequent actions
            // in a stable order and move extras to More only when width requires it.
            val slots = ((maxWidth - 152.dp).value / 48f).toInt().coerceAtLeast(2)
            val available = primaryActions + contextualActions
            val directCount = if (available.size > slots) (slots - 1).coerceAtLeast(1)
                else slots
            val direct = available.take(directCount)
            val overflow = available.drop(directCount)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val allSelected = entries.isNotEmpty() &&
                    entries.all { it.key in selectedKeys }
                RuntimePageSelectionCheckbox(
                    checked = allSelected,
                    enabled = entries.isNotEmpty() && !state.busy,
                    description = stringResource(if (allSelected)
                        R.string.memory_editor_clear_selection
                        else R.string.library_selection_select_all),
                    onToggle = { checked ->
                        onSelectionChange(if (checked) entries.mapTo(HashSet()) { it.key }
                            else emptySet())
                    },
                )
                Text(
                    if (selectedEntries.isNotEmpty()) pluralStringResource(
                        R.plurals.memory_editor_selected, selectedEntries.size, selectedEntries.size,
                    ) else stringResource(R.string.memory_variables_tab) + " · " + entries.size,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                direct.forEach { RuntimeActionIcon(it.icon, it.label, it.enabled, it.onClick) }
                if (overflow.isNotEmpty()) {
                    RuntimeActionIcon(R.drawable.ic_more_vert, R.string.more, !state.busy) {
                        moreActions = true
                    }
                }
            }
            LaunchedEffect(overflow.isEmpty()) {
                if (overflow.isEmpty()) moreActions = false
            }
            if (moreActions && overflow.isNotEmpty()) {
                RuntimeOverflowActionsDialog(overflow) { moreActions = false }
            }
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state.savedError != null) item(key = "list-error") {
                Text(state.savedError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp))
            }
            if (entries.isEmpty()) item(key = "list-empty") {
                Column(Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.memory_list_empty),
                        style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.memory_list_empty_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !state.busy, onClick = {
                        recipeSourceKey = null
                        recipeDialog = true
                    }) { Text(stringResource(R.string.memory_recipes_import)) }
                }
            }
            items(entries, key = MemoryListEntry::key) { entry ->
                val current = entry.saved?.let { state.savedValues[it.id] }
                val editable = !state.busy && state.writeSupported &&
                    (current?.resolved == true ||
                        entry.watch?.let { it.state != MemoryEngineContract.CANDIDATE_LOST } == true)
                RuntimeListRow(
                    entry = entry,
                    value = current,
                    selected = entry.key in selectedKeys,
                    selectionActive = selectedKeys.isNotEmpty(),
                    selectEnabled = !state.busy,
                    compact = compactRows,
                    editable = editable,
                    onSelect = { select(entry) },
                    onEdit = { edit(entry) },
                    onMore = { menuKey = entry.key },
                )
            }
        }
    }

    if (recipeDialog) {
        val saved = entries.firstOrNull { it.key == recipeSourceKey }?.saved
        MemoryRecipeDialog(
            saved = saved,
            savedValue = saved?.let { state.savedValues[it.id]?.value }.orEmpty(),
            writeSupported = state.writeSupported && !state.busy,
            actions = actions,
            onDismiss = { recipeDialog = false },
        )
    }

    if (menuEntry != null) {
        val saved = menuEntry.saved
        val watch = menuEntry.watch
        val savedReady = saved != null && state.savedValues[saved.id]?.resolved == true
        val ready = savedReady ||
            watch?.let { it.state != MemoryEngineContract.CANDIDATE_LOST } == true
        val actionsInMenu = listOf(
            RuntimeToolbarAction(R.drawable.ic_memory_editor_edit_value, R.string.memory_editor_edit,
                state.writeSupported && ready && !state.busy) { edit(menuEntry) },
            RuntimeToolbarAction(R.drawable.ic_memory_editor_inspector, R.string.memory_editor_inspect_memory,
                ready && !state.busy) { inspect(menuEntry) },
            RuntimeToolbarAction(R.drawable.ic_memory_editor_watch_add,
                R.string.memory_bookmark_relink, saved != null && !state.busy) {
                saved?.let { onRelink(it.id) }
            },
            RuntimeToolbarAction(
                if ((watch?.freezeMode ?: -1) >= 0) R.drawable.ic_unlock
                else R.drawable.ic_lock,
                if ((watch?.freezeMode ?: -1) >= 0) R.string.memory_editor_unfreeze
                else R.string.memory_editor_freeze,
                !state.busy && ((watch?.freezeMode ?: -1) >= 0 ||
                    (state.writeSupported && (saved == null || savedReady) && ready)),
            ) { toggleFreeze(menuEntry) },
            RuntimeToolbarAction(R.drawable.ic_memory_editor_rename, R.string.memory_saved_rename,
                !state.busy) { renameKey = menuEntry.key },
            RuntimeToolbarAction(R.drawable.ic_delete, R.string.memory_editor_remove,
                !state.busy) { removeKeys = setOf(menuEntry.key) },
            RuntimeToolbarAction(R.drawable.ic_auto_mode,
                if (saved != null) R.string.memory_recipes_title else R.string.memory_recipes_import,
                !state.busy) {
                recipeSourceKey = menuEntry.key
                recipeDialog = true
                menuKey = null
            },
        )
        RuntimeOverflowActionsDialog(actionsInMenu) { menuKey = null }
    }
    savedDraft?.let { draft ->
        DisposableEffect(draft.row.id) { onDispose { actions.endSavedEdit() } }
        val live = state.savedValues[draft.row.id]
        RuntimeEditDialog(
            targets = draft.targets,
            writeSupported = state.writeSupported && !state.busy &&
                live?.resolved == true && live.type == draft.targets.single().type,
            title = draft.row.label,
            onDismiss = { savedDraft = null },
            onApply = { text, _, freeze, _, _ ->
                actions.editSavedVariable(draft.row.id, text, freeze)
            },
        )
    }
    watchDraft?.let { targets ->
        RuntimeEditDialog(
            targets = targets,
            writeSupported = state.writeSupported && !state.busy,
            onDismiss = { watchDraft = null },
            onApply = { text, type, freeze, _, _ ->
                actions.editTargetsWithOptions(targets, 0L, text, type, false, freeze)
            },
        )
    }
    renameEntry?.let { entry ->
        var name by remember(entry.key) { mutableStateOf(entry.label) }
        AlertDialog(
            onDismissRequest = { renameKey = null },
            title = { Text(stringResource(R.string.memory_saved_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 64) name = it },
                    label = { Text(stringResource(R.string.memory_saved_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !state.busy, onClick = {
                    entry.saved?.let { actions.renameSavedVariable(it.id, name.trim()) }
                        ?: entry.watch?.let { actions.labelWatch(it.id, name.trim()) }
                    renameKey = null
                }) { Text(stringResource(R.string.memory_saved_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renameKey = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (removeKeys.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { removeKeys = emptySet() },
            title = { Text(stringResource(R.string.memory_editor_remove)) },
            text = { Text(stringResource(R.string.memory_list_remove_confirm)) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    actions.removeListEntries(
                        removal.mapNotNull { it.saved?.id },
                        removal.mapNotNull { it.watch?.id },
                    )
                    removeKeys = emptySet()
                    onSelectionChange(emptySet())
                }) { Text(stringResource(R.string.memory_editor_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeKeys = emptySet() }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RuntimeListRow(
    entry: MemoryListEntry,
    value: SavedVariableRuntimeValue?,
    selected: Boolean,
    selectionActive: Boolean,
    selectEnabled: Boolean,
    compact: Boolean,
    editable: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onMore: () -> Unit,
) {
    val watch = entry.watch
    val currentValue = if (entry.saved == null) watch?.valueText.orEmpty()
        else value?.takeIf { it.resolved }?.value
            ?: watch?.takeIf { it.state != MemoryEngineContract.CANDIDATE_LOST }?.valueText
            ?: "—"
    val name = entry.label.ifBlank {
        watch?.source?.takeIf { it.kind != MemoryEngineContract.SOURCE_UNKNOWN }
            ?.shortFieldName ?: entry.saved?.path ?: watch?.let {
                runtimeTypeShort(it.type)
            }.orEmpty()
    }
    val selectDescription = stringResource(R.string.memory_editor_select_result_value)
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (selectEnabled) {
                            if (selectionActive) onSelect() else if (editable) onEdit()
                        }
                    },
                    onLongClick = { if (selectEnabled) onSelect() },
                )
                .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                enabled = selectEnabled,
                onCheckedChange = { onSelect() },
                modifier = Modifier.semantics { contentDescription = selectDescription },
            )
            Column(Modifier.weight(1f).padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false))
                    if ((watch?.freezeMode ?: -1) >= 0) {
                        Spacer(Modifier.width(6.dp))
                        Icon(painterResource(R.drawable.ic_lock),
                            contentDescription = stringResource(R.string.memory_editor_freeze),
                            modifier = Modifier.size(16.dp),
                            tint = if (watch?.freezePaused == true) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                    }
                }
                val location = entry.saved?.path?.takeUnless { it == "Needs relink" }
                    ?: watch?.let { runtimeCandidateLocation(it.id, it.source, it.locationText) }
                    ?: entry.saved?.path.orEmpty()
                Text(location, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = runtimeMemoryMetaTextStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (watch?.freezePaused == true) Text(
                    stringResource(R.string.memory_editor_freeze_paused),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
                if (entry.saved != null && value?.resolved != true &&
                    (watch == null || watch.state == MemoryEngineContract.CANDIDATE_LOST)) {
                    Text(value?.detail?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.memory_bookmark_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2)
                }
            }
            Column(Modifier.widthIn(min = 64.dp, max = 156.dp),
                horizontalAlignment = Alignment.End) {
                Text(currentValue, style = runtimeMemoryDataTextStyle(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End)
                Text(runtimeTypeShort(value?.takeIf { it.resolved }?.type
                    ?: watch?.type ?: MemoryEngineContract.TYPE_AUTO),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onMore, enabled = selectEnabled) {
                Icon(painterResource(R.drawable.ic_more_vert),
                    stringResource(R.string.more))
            }
        }
    }
    HorizontalDivider()
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
    val isBoolean = type == MemoryEngineContract.TYPE_BOOLEAN
    LaunchedEffect(type) {
        if (isBoolean && predicate !in MemoryEngineContract.PREDICATE_EQUAL..
            MemoryEngineContract.PREDICATE_NOT_EQUAL) predicate = MemoryEngineContract.PREDICATE_EQUAL
        if ((isBoolean && !MemoryInputSpec.forType(type).isComplete(query.text)) ||
            (!isBoolean && query.text.equals("true", true) || !isBoolean && query.text.equals("false", true))) {
            query = TextFieldValue("", TextRange(0))
        }
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
        type in (MemoryEngineContract.TYPE_AUTO..MemoryEngineContract.TYPE_BOOLEAN) &&
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
                showKeypad = !isBoolean,
                controls = { sideDock ->
                    RuntimeSearchControlRow {
                        RuntimePredicateMenu(
                            predicate = effectivePredicate,
                            onPredicate = { predicate = it },
                            enabled = !groupExpression,
                            booleanOnly = isBoolean,
                            modifier = Modifier.widthIn(min = 72.dp, max = 112.dp),
                        )
                        if (isBoolean) {
                            RuntimeBooleanChoice(
                                value = query.text,
                                onSelect = { query = TextFieldValue(it, TextRange(it.length)) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
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
    val isBoolean = type == MemoryEngineContract.TYPE_BOOLEAN
    LaunchedEffect(type) {
        if (isBoolean && predicate != MemoryEngineContract.PREDICATE_CHANGED &&
            predicate != MemoryEngineContract.PREDICATE_UNCHANGED) {
            predicate = MemoryEngineContract.PREDICATE_CHANGED
            actions.setUnknownSearchPredicate(predicate)
        }
    }
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
                showKeypad = needsValue && !isBoolean,
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
                                    booleanOnly = isBoolean,
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
                                booleanOnly = isBoolean,
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
    title: String? = null,
    showFreeze: Boolean = true,
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
                        } else title ?: stringResource(R.string.memory_editor_edit),
                    )
                },
                showKeypad = type != MemoryEngineContract.TYPE_BOOLEAN,
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
                        if (type == MemoryEngineContract.TYPE_BOOLEAN) {
                            RuntimeBooleanChoice(
                                value = replacement.text,
                                onSelect = { replacement = TextFieldValue(it, TextRange(it.length)) },
                                modifier = Modifier.weight(0.58f),
                            )
                        } else {
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
                    if (!batch && showFreeze) {
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
            val sourceUntraced = snapshot.source == MemoryCandidateSource.UNKNOWN
            val hasVerifiedPath = snapshot.pathStatus == MemoryEngineContract.PATH_VERIFIED &&
                snapshot.referencePath.isNotBlank()
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
                                    if (sourceUntraced) {
                                        snapshot.label.ifBlank { runtimeTypeShort(snapshot.type) }
                                    } else {
                                        runtimeCandidateLocation(
                                            snapshot.candidateId, snapshot.source,
                                            snapshot.label.ifBlank { runtimeTypeShort(snapshot.type) },
                                        )
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (!sourceUntraced || !hasVerifiedPath) {
                                    Text(
                                        if (sourceUntraced) {
                                            stringResource(R.string.memory_editor_source_unknown)
                                        } else {
                                            "${snapshot.source.declaringClass}.${snapshot.source.fieldName}: " +
                                                snapshot.source.fieldType
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                                if (snapshot.savedId != null) {
                                    actions.inspectSavedVariable(snapshot.savedId)
                                } else {
                                    actions.inspectCandidate(
                                        snapshot.candidateId,
                                        watchAnchor = snapshot.watchAnchor,
                                    )
                                }
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
                    var rowMenuOpen by remember(row.id) { mutableStateOf(false) }
                    Surface(
                        color = if (row.relativeOffset == 0) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
                        } else Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(enabled = row.editable && state.writeSupported &&
                                    !state.busy) { editLogicalRow = row }
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
                                modifier = Modifier.weight(1f)
                                    .heightIn(min = 48.dp)
                                    .wrapContentHeight(align = Alignment.CenterVertically),
                                textAlign = TextAlign.End,
                                style = runtimeMemoryDataTextStyle(),
                            )
                            Box {
                                RuntimeActionIcon(
                                    R.drawable.ic_more_vert,
                                    R.string.more,
                                    enabled = !state.busy,
                                ) { rowMenuOpen = true }
                                DropdownMenu(
                                    expanded = rowMenuOpen,
                                    onDismissRequest = { rowMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.memory_list_add)) },
                                        enabled = row.editable && !state.busy,
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_memory_editor_watch_add),
                                                contentDescription = null)
                                        },
                                        onClick = {
                                            rowMenuOpen = false
                                            actions.watchInspectorVariable(row.relativeOffset)
                                        },
                                    )
                                }
                            }
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
                    writeSupported = state.writeSupported,
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
    writeSupported: Boolean,
    onDismiss: () -> Unit,
) {
    val target = remember(row) {
        listOf(MemoryEditTarget(row.id, row.type, row.valueText, listOf(row.type), watch = true))
    }
    RuntimeEditDialog(
        targets = target,
        writeSupported = writeSupported && row.editable,
        title = row.label.ifBlank { runtimeTypeShort(row.type) },
        showFreeze = true,
        onDismiss = onDismiss,
        onApply = { replacement, _, freeze, _, _ ->
            if (snapshot.savedId != null) {
                actions.editSavedInspectorValue(
                    snapshot.savedId, row.relativeOffset, row.type,
                    row.expectedBits, replacement, freeze,
                )
            } else {
                actions.editInspectorValue(
                    snapshot.candidateId, row.relativeOffset, row.type,
                    row.expectedBits, replacement, snapshot.watchAnchor, freeze,
                )
            }
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
    booleanOnly: Boolean = false,
) {
    RuntimeChoiceMenu(
        value = predicate,
        values = if (booleanOnly) intArrayOf(MemoryEngineContract.PREDICATE_EQUAL,
            MemoryEngineContract.PREDICATE_NOT_EQUAL) else memoryKnownSearchPredicates(),
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
    booleanOnly: Boolean = false,
) {
    RuntimeChoiceMenu(
        value = predicate,
        values = if (booleanOnly) intArrayOf(MemoryEngineContract.PREDICATE_CHANGED,
            MemoryEngineContract.PREDICATE_UNCHANGED) else memoryUnknownSearchPredicates(),
        label = { runtimePredicateName(it) },
        onChange = onPredicate,
        modifier = modifier,
        centerContent = true,
    )
}

@Composable
private fun RuntimeBooleanChoice(
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("false", "true").forEach { option ->
            FilterChip(
                selected = value.equals(option, ignoreCase = true),
                onClick = { onSelect(option) },
                label = { Text(option) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
        }
    }
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
    MemoryEngineContract.TYPE_BOOLEAN -> "Boolean"
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
