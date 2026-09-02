/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.playsoftware.j2meloader.memory

import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.AdaptiveAlertDialog as AlertDialog
import kotlin.math.roundToInt

private enum class OverlayDestination { SEARCH, RESULTS, WATCH }
private enum class SearchEntryMode { KNOWN, UNKNOWN }
private enum class SearchField { FIRST, SECOND }

/** Production in-game UI. Legacy screenshot surfaces intentionally remain separate. */
@Composable
internal fun MemoryEditorOverlayRoot(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
) {
    if (state.inspectorLoading || state.inspector != null) {
        MemoryEditorStage3Root(state = state, actions = actions)
        return
    }

    var destination by remember(state.runtimeToken) {
        mutableStateOf(
            when {
                state.watchTab -> OverlayDestination.WATCH
                state.sessionStage == MemorySessionStage.CANDIDATES -> OverlayDestination.RESULTS
                else -> OverlayDestination.SEARCH
            },
        )
    }
    var searchWasBusy by remember { mutableStateOf(false) }

    LaunchedEffect(state.busy, state.searching, state.sessionStage) {
        val finishedSearch = searchWasBusy && !state.busy &&
            state.sessionStage == MemorySessionStage.CANDIDATES
        if (finishedSearch) destination = OverlayDestination.RESULTS
        searchWasBusy = state.busy && state.searching
    }

    LaunchedEffect(destination) {
        actions.setWatchTab(destination == OverlayDestination.WATCH)
    }

    LaunchedEffect(
        state.visible,
        state.connected,
        state.supported,
        state.busy,
        state.sessionStage,
        destination,
    ) {
        if (!state.visible || !state.connected || !state.supported) return@LaunchedEffect
        while (state.visible) {
            if (!state.busy && (
                    destination == OverlayDestination.WATCH ||
                        (destination == OverlayDestination.RESULTS &&
                            state.sessionStage == MemorySessionStage.CANDIDATES)
                    )
            ) {
                actions.refresh()
            }
            delay(1_000)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.985f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            OverlayHeader(actions)
            OverlayNavigation(
                destination = destination,
                resultCount = state.resultCount,
                watchCount = state.watches.size,
                resultsEnabled = state.sessionStage == MemorySessionStage.CANDIDATES,
                onDestination = { next ->
                    if (next != OverlayDestination.RESULTS ||
                        state.sessionStage == MemorySessionStage.CANDIDATES
                    ) {
                        destination = next
                    }
                },
            )
            if (state.busy) CompactOperationStatus(state, actions)
            state.message?.takeIf(String::isNotBlank)?.let { message -> OverlayMessage(message) }

            when (destination) {
                OverlayDestination.SEARCH -> OverlaySearchWorkspace(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
                OverlayDestination.RESULTS -> {
                    if (state.sessionStage == MemorySessionStage.CANDIDATES) {
                        OverlayResultsWorkspace(
                            state = state.copy(watchTab = false),
                            actions = actions,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        OverlayEmptyResults(
                            modifier = Modifier.weight(1f),
                            onSearch = { destination = OverlayDestination.SEARCH },
                        )
                    }
                }
                OverlayDestination.WATCH -> OverlayWatchWorkspace(
                    state = state.copy(watchTab = true),
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OverlayHeader(actions: MemoryEditorActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.memory_editor),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = actions::close,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_memory_editor_close),
                contentDescription = stringResource(R.string.memory_editor_close),
            )
        }
    }
}

@Composable
private fun OverlayNavigation(
    destination: OverlayDestination,
    resultCount: Long,
    watchCount: Int,
    resultsEnabled: Boolean,
    onDestination: (OverlayDestination) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OverlayDestinationButton(
            selected = destination == OverlayDestination.SEARCH,
            icon = R.drawable.ic_memory_editor_search,
            label = stringResource(R.string.memory_editor_search_tab),
            onClick = { onDestination(OverlayDestination.SEARCH) },
            modifier = Modifier.weight(1f),
        )
        OverlayDestinationButton(
            selected = destination == OverlayDestination.RESULTS,
            icon = R.drawable.ic_memory_editor_refine,
            label = if (resultsEnabled) {
                "${stringResource(R.string.memory_editor_results_tab)} ${formatCompactCount(resultCount)}"
            } else {
                stringResource(R.string.memory_editor_results_tab)
            },
            enabled = resultsEnabled,
            onClick = { onDestination(OverlayDestination.RESULTS) },
            modifier = Modifier.weight(1f),
        )
        OverlayDestinationButton(
            selected = destination == OverlayDestination.WATCH,
            icon = R.drawable.ic_memory_editor_watch,
            label = if (watchCount > 0) {
                "${stringResource(R.string.memory_editor_watch)} $watchCount"
            } else {
                stringResource(R.string.memory_editor_watch)
            },
            onClick = { onDestination(OverlayDestination.WATCH) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OverlayDestinationButton(
    selected: Boolean,
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minHeight = 52.dp),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompactOperationStatus(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    val progress = if (state.scanBytesTotal > 0L) {
        (state.scanBytesScanned.toFloat() / state.scanBytesTotal.toFloat()).coerceIn(0f, 1f)
    } else null
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        if (state.searching) R.string.memory_editor_searching
                        else R.string.memory_editor_working,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.searching) {
                    TextButton(onClick = actions::close) {
                        Text(stringResource(R.string.memory_editor_overlay_hide))
                    }
                }
                TextButton(onClick = actions::cancel) {
                    Text(stringResource(R.string.memory_editor_cancel))
                }
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun OverlayMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun OverlaySearchWorkspace(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            state.connecting -> CenteredStatus(
                text = stringResource(R.string.memory_editor_working),
                progress = true,
            )
            !state.supported -> UnsupportedSearchState(state, actions)
            state.busy && state.searching -> SearchProgressPane(state, actions)
            state.sessionStage == MemorySessionStage.UNKNOWN_BASELINE -> UnknownBaselineWorkspace(actions)
            else -> SearchStartWorkspace(state, actions)
        }
    }
}

@Composable
private fun SearchStartWorkspace(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    var mode by remember(state.runtimeToken) {
        mutableStateOf(
            if (state.searchMode == MemorySearchMode.UNKNOWN) SearchEntryMode.UNKNOWN
            else SearchEntryMode.KNOWN,
        )
    }
    var first by remember(state.runtimeToken) { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var second by remember(state.runtimeToken) { mutableStateOf(TextFieldValue("", TextRange(0))) }
    var activeField by remember { mutableStateOf(SearchField.FIRST) }
    var type by remember(state.runtimeToken) { mutableIntStateOf(state.requestedType) }
    var predicate by remember { mutableIntStateOf(MemoryEngineContract.PREDICATE_EQUAL) }
    var scope by remember(state.runtimeToken) { mutableIntStateOf(state.searchScope) }
    var advanced by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.memory_editor_overlay_find_value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == SearchEntryMode.KNOWN,
                onClick = { mode = SearchEntryMode.KNOWN },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_memory_editor_search), contentDescription = null)
                },
                label = { Text(stringResource(R.string.memory_editor_known)) },
            )
            FilterChip(
                selected = mode == SearchEntryMode.UNKNOWN,
                onClick = { mode = SearchEntryMode.UNKNOWN },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_memory_editor_search_unknown),
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.memory_editor_unknown_short)) },
            )
        }

        if (mode == SearchEntryMode.UNKNOWN) {
            UnknownStartPane(
                type = type,
                onType = { type = it },
                scope = scope,
                onScope = { scope = it },
                advanced = advanced,
                onAdvanced = { advanced = !advanced },
                onCapture = {
                    actions.startSearch(
                        "", "", type, MemoryEngineContract.PREDICATE_EQUAL, true, scope,
                    )
                },
            )
            return@Column
        }

        val expression = parseMemorySearchExpression(first.text)
        val group = expression as? MemorySearchExpression.Group
        val firstSpec = MemoryInputSpec.forType(type)
        val secondNeeded = group == null && predicate == MemoryEngineContract.PREDICATE_BETWEEN
        val singleValid = expression is MemorySearchExpression.Single && firstSpec.isComplete(expression.value)
        val groupValid = group != null && type != MemoryEngineContract.TYPE_AUTO &&
            group.values.all(firstSpec::isComplete)
        val secondValid = !secondNeeded || firstSpec.isComplete(second.text)
        val canSearch = (singleValid || groupValid) && secondValid

        SearchValueField(
            label = stringResource(R.string.memory_editor_overlay_query_label),
            value = first.text,
            active = activeField == SearchField.FIRST,
            onClick = { activeField = SearchField.FIRST },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group == null) {
                PredicatePicker(predicate = predicate, onPredicate = { predicate = it })
            } else {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        stringResource(R.string.memory_editor_group_search),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            TypePicker(type = type, onType = { type = it }, modifier = Modifier.weight(1f))
        }
        if (secondNeeded) {
            SearchValueField(
                label = stringResource(R.string.memory_editor_max_value),
                value = second.text,
                active = activeField == SearchField.SECOND,
                onClick = { activeField = SearchField.SECOND },
            )
        }
        SearchExpressionHint(expression = expression, selectedType = type)
        if (advanced) ScopePicker(scope = scope, onScope = { scope = it })
        TextButton(onClick = { advanced = !advanced }) {
            Text(
                stringResource(
                    if (advanced) R.string.memory_editor_overlay_less_options
                    else R.string.memory_editor_advanced,
                ),
            )
        }
        SearchKeypad(
            onToken = { token ->
                if (activeField == SearchField.FIRST) {
                    first = insertSearchToken(first, token, allowGroup = true)
                } else {
                    second = insertSearchToken(second, token, allowGroup = false)
                }
            },
            onBackspace = {
                if (activeField == SearchField.FIRST) first = backspaceSearchValue(first)
                else second = backspaceSearchValue(second)
            },
            onMove = { delta ->
                if (activeField == SearchField.FIRST) first = moveSearchCursor(first, delta)
                else second = moveSearchCursor(second, delta)
            },
            onClear = {
                if (activeField == SearchField.FIRST) first = TextFieldValue("", TextRange(0))
                else second = TextFieldValue("", TextRange(0))
            },
        )
        Button(
            onClick = {
                when (expression) {
                    is MemorySearchExpression.Single -> actions.startSearch(
                        expression.value, second.text, type, predicate, false, scope,
                    )
                    is MemorySearchExpression.Group -> actions.groupSearch(
                        IntArray(expression.values.size) { type },
                        expression.values.toTypedArray(),
                        expression.maxDistance,
                        scope,
                    )
                    is MemorySearchExpression.Invalid -> Unit
                }
            },
            enabled = canSearch,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        ) {
            Icon(painterResource(R.drawable.ic_memory_editor_search), contentDescription = null)
            Text(
                stringResource(R.string.memory_editor_search_action),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun UnknownStartPane(
    type: Int,
    onType: (Int) -> Unit,
    scope: Int,
    onScope: (Int) -> Unit,
    advanced: Boolean,
    onAdvanced: () -> Unit,
    onCapture: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Text(
            stringResource(R.string.memory_editor_unknown_explanation),
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TypePicker(type = type, onType = onType, modifier = Modifier.fillMaxWidth())
    if (advanced) ScopePicker(scope = scope, onScope = onScope)
    TextButton(onClick = onAdvanced) {
        Text(
            stringResource(
                if (advanced) R.string.memory_editor_overlay_less_options
                else R.string.memory_editor_advanced,
            ),
        )
    }
    Button(onClick = onCapture, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
        Icon(painterResource(R.drawable.ic_memory_editor_search_unknown), contentDescription = null)
        Text(
            stringResource(R.string.memory_editor_capture_baseline),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun UnknownBaselineWorkspace(actions: MemoryEditorActions) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        Text(
            stringResource(R.string.memory_editor_what_changed),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelativeScanButton(
                text = stringResource(R.string.memory_editor_predicate_increased),
                modifier = Modifier.weight(1f),
            ) {
                actions.nextScan("", "", MemoryEngineContract.PREDICATE_INCREASED, MemoryEngineContract.COMPARE_PREVIOUS)
            }
            RelativeScanButton(
                text = stringResource(R.string.memory_editor_predicate_decreased),
                modifier = Modifier.weight(1f),
            ) {
                actions.nextScan("", "", MemoryEngineContract.PREDICATE_DECREASED, MemoryEngineContract.COMPARE_PREVIOUS)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelativeScanButton(
                text = stringResource(R.string.memory_editor_predicate_changed),
                modifier = Modifier.weight(1f),
            ) {
                actions.nextScan("", "", MemoryEngineContract.PREDICATE_CHANGED, MemoryEngineContract.COMPARE_PREVIOUS)
            }
            RelativeScanButton(
                text = stringResource(R.string.memory_editor_predicate_unchanged),
                modifier = Modifier.weight(1f),
            ) {
                actions.nextScan("", "", MemoryEngineContract.PREDICATE_UNCHANGED, MemoryEngineContract.COMPARE_PREVIOUS)
            }
        }
        TextButton(onClick = actions::startOver) {
            Text(stringResource(R.string.memory_editor_start_over))
        }
    }
}

@Composable
private fun RelativeScanButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.sizeIn(minHeight = 52.dp)) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchProgressPane(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    val progress = if (state.scanBytesTotal > 0L) {
        (state.scanBytesScanned.toFloat() / state.scanBytesTotal.toFloat()).coerceIn(0f, 1f)
    } else null
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress == null) {
            CircularProgressIndicator()
        } else {
            Text(
                "${(progress * 100f).roundToInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.memory_editor_search_progress,
                    state.scanBytesScanned / (1024L * 1024L),
                    state.scanBytesTotal / (1024L * 1024L),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.memory_editor_overlay_search_background_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = actions::close) {
                Text(stringResource(R.string.memory_editor_overlay_hide))
            }
            Button(onClick = actions::cancel) {
                Text(stringResource(R.string.memory_editor_cancel))
            }
        }
    }
}

@Composable
private fun UnsupportedSearchState(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            state.message ?: stringResource(R.string.memory_editor_unsupported),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = actions::refreshCapabilities) {
            Text(stringResource(R.string.memory_editor_refresh))
        }
    }
}

@Composable
private fun CenteredStatus(text: String, progress: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) CircularProgressIndicator()
        if (progress) Spacer(Modifier.height(16.dp))
        Text(text)
    }
}

@Composable
private fun OverlayEmptyResults(modifier: Modifier = Modifier, onSearch: () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.memory_editor_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSearch) {
            Text(stringResource(R.string.memory_editor_new_search))
        }
    }
}

@Composable
private fun SearchValueField(label: String, value: String, active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
        border = BorderStroke(
            width = if (active) 2.dp else 1.dp,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchExpressionHint(expression: MemorySearchExpression, selectedType: Int) {
    val text = when (expression) {
        is MemorySearchExpression.Single -> stringResource(R.string.memory_editor_overlay_single_hint)
        is MemorySearchExpression.Group -> if (selectedType == MemoryEngineContract.TYPE_AUTO) {
            stringResource(R.string.memory_editor_overlay_group_needs_type)
        } else {
            stringResource(
                R.string.memory_editor_overlay_group_hint,
                expression.values.size,
                expression.maxDistance,
            )
        }
        is MemorySearchExpression.Invalid -> when (expression.reason) {
            MemorySearchExpression.Reason.EMPTY -> stringResource(R.string.memory_editor_overlay_query_help)
            MemorySearchExpression.Reason.ORDERED_GROUP_UNSUPPORTED ->
                stringResource(R.string.memory_editor_overlay_ordered_group_unsupported)
            MemorySearchExpression.Reason.INVALID_DISTANCE,
            MemorySearchExpression.Reason.TOO_MANY_VALUES,
            MemorySearchExpression.Reason.EMPTY_GROUP_VALUE ->
                stringResource(R.string.memory_editor_overlay_invalid_group)
            else -> stringResource(R.string.memory_editor_overlay_invalid_value)
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            expression is MemorySearchExpression.Invalid && expression.reason != MemorySearchExpression.Reason.EMPTY ->
                MaterialTheme.colorScheme.error
            expression is MemorySearchExpression.Group && selectedType == MemoryEngineContract.TYPE_AUTO ->
                MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun TypePicker(
    type: Int,
    onType: (Int) -> Unit,
    modifier: Modifier = Modifier,
    options: IntArray = OVERLAY_VALUE_TYPES,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.memory_editor_data_type), style = MaterialTheme.typography.labelSmall)
                Text(if (options.isEmpty()) "—" else overlayTypeLabel(type), maxLines = 1)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                overlayTypeLabel(option),
                                fontWeight = if (option == type) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                overlayTypeDescription(option),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onType(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun PredicatePicker(predicate: Int, onPredicate: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp)) {
            Text(overlayPredicateLabel(predicate))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            INITIAL_PREDICATES.forEach { option ->
                DropdownMenuItem(
                    text = { Text(overlayPredicateDescription(option)) },
                    onClick = {
                        expanded = false
                        onPredicate(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ScopePicker(scope: Int, onScope: (Int) -> Unit) {
    Text(stringResource(R.string.memory_editor_overlay_memory_scope), style = MaterialTheme.typography.labelLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = scope == MemoryEngineContract.SCOPE_JAVA_FAST,
            onClick = { onScope(MemoryEngineContract.SCOPE_JAVA_FAST) },
            label = { Text(stringResource(R.string.memory_editor_scope_fast)) },
        )
        FilterChip(
            selected = scope == MemoryEngineContract.SCOPE_JAVA_THOROUGH,
            onClick = { onScope(MemoryEngineContract.SCOPE_JAVA_THOROUGH) },
            label = { Text(stringResource(R.string.memory_editor_scope_thorough)) },
        )
    }
}

@Composable
private fun SearchKeypad(
    onToken: (String) -> Unit,
    onBackspace: () -> Unit,
    onMove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KeypadRow {
                KeypadKey("1") { onToken("1") }; KeypadKey("2") { onToken("2") }
                KeypadKey("3") { onToken("3") }; KeypadKey("4") { onToken("4") }
                KeypadKey("5") { onToken("5") }; KeypadKey("⌫", onBackspace)
            }
            KeypadRow {
                KeypadKey("6") { onToken("6") }; KeypadKey("7") { onToken("7") }
                KeypadKey("8") { onToken("8") }; KeypadKey("9") { onToken("9") }
                KeypadKey("0") { onToken("0") }; KeypadKey(";") { onToken(";") }
            }
            KeypadRow {
                KeypadKey(".") { onToken(".") }; KeypadKey("-") { onToken("-") }
                KeypadKey("E") { onToken("E") }; KeypadKey(":") { onToken(":") }
                KeypadKey("←") { onMove(-1) }; KeypadKey("→") { onMove(1) }
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.memory_editor_keypad_clear))
                }
            }
        }
    }
}

@Composable
private fun KeypadRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeypadKey(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f).sizeIn(minHeight = 46.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
private fun OverlayResultsWorkspace(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    modifier: Modifier = Modifier,
) {
    var refineDialog by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MemoryResultRow?>(null) }
    var editDialog by remember { mutableStateOf(false) }
    var freezeDialog by remember { mutableStateOf(false) }
    val selectionMode = state.selected.isNotEmpty()

    Column(modifier = modifier.fillMaxWidth()) {
        OverlayListHeader(
            title = pluralStringResource(
                R.plurals.memory_editor_results,
                state.resultCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.resultCount,
            ),
            selectionMode = selectionMode,
            selectedCount = state.selected.size,
            primaryLabel = stringResource(R.string.memory_editor_refine_results),
            primaryIcon = R.drawable.ic_memory_editor_refine,
            onPrimary = { refineDialog = true },
            onClearSelection = actions::clearSelection,
        )
        if (state.results.isEmpty()) {
            OverlayEmptyList(Modifier.weight(1f), R.string.memory_editor_no_results)
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.results, key = { it.id }) { row ->
                    OverlayResultRow(
                        row = row,
                        selected = row.id in state.selected,
                        selectionMode = selectionMode,
                        onToggle = { actions.toggleSelection(row.id) },
                        onOpen = { detail = row },
                    )
                }
            }
        }
        OverlayPager(state, actions)
        if (selectionMode) {
            OverlaySelectionBar(
                state = state,
                actions = actions,
                onEdit = { editDialog = true },
                onFreeze = { freezeDialog = true },
            )
        } else {
            OverlayUtilityBar(state, actions)
        }
    }

    if (refineDialog) {
        OverlayRefineDialog(state, actions) { refineDialog = false }
    }
    detail?.let { row ->
        OverlayResultDetailDialog(
            row = row,
            writeSupported = state.writeSupported,
            onDismiss = { detail = null },
            onEdit = {
                detail = null
                actions.clearSelection(); actions.toggleSelection(row.id); editDialog = true
            },
            onWatch = {
                detail = null
                actions.clearSelection(); actions.toggleSelection(row.id)
                actions.watchSelected(true); actions.clearSelection()
            },
            onFreeze = {
                detail = null
                actions.clearSelection(); actions.toggleSelection(row.id); freezeDialog = true
            },
            onInspect = {
                detail = null
                actions.inspectCandidate(row.id)
            },
        )
    }
    if (editDialog) {
        OverlayEditDialog(state, actions) {
            editDialog = false
        }
    }
    if (freezeDialog) {
        OverlayFreezeDialog(state, actions) {
            freezeDialog = false
        }
    }
}

@Composable
private fun OverlayWatchWorkspace(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<MemoryWatchRow?>(null) }
    var editDialog by remember { mutableStateOf(false) }
    var freezeDialog by remember { mutableStateOf(false) }
    val selectionMode = state.selected.isNotEmpty()

    Column(modifier = modifier.fillMaxWidth()) {
        OverlayListHeader(
            title = if (state.watches.isEmpty()) stringResource(R.string.memory_editor_watch)
            else "${stringResource(R.string.memory_editor_watch)} · ${state.watches.size}",
            selectionMode = selectionMode,
            selectedCount = state.selected.size,
            primaryLabel = stringResource(R.string.memory_editor_refresh),
            primaryIcon = R.drawable.ic_restart_alt,
            onPrimary = actions::refresh,
            onClearSelection = actions::clearSelection,
        )
        if (state.watches.isEmpty()) {
            OverlayEmptyList(Modifier.weight(1f), R.string.memory_editor_no_watch)
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.watches, key = { it.id }) { row ->
                    OverlayWatchRow(
                        row = row,
                        selected = row.id in state.selected,
                        selectionMode = selectionMode,
                        onToggle = { actions.toggleSelection(row.id) },
                        onOpen = { detail = row },
                    )
                }
            }
        }
        if (selectionMode) {
            OverlaySelectionBar(
                state = state,
                actions = actions,
                onEdit = { editDialog = true },
                onFreeze = { freezeDialog = true },
            )
        } else {
            OverlayUtilityBar(state, actions)
        }
    }

    detail?.let { row ->
        OverlayWatchDetailDialog(
            row = row,
            writeSupported = state.writeSupported,
            onDismiss = { detail = null },
            onEdit = {
                detail = null
                actions.clearSelection(); actions.toggleSelection(row.id); editDialog = true
            },
            onRemove = {
                detail = null
                actions.clearSelection(); actions.toggleSelection(row.id)
                actions.watchSelected(false); actions.clearSelection()
            },
            onFreeze = {
                detail = null
                actions.clearSelection(); actions.toggleSelection(row.id); freezeDialog = true
            },
            onInspect = {
                detail = null
                actions.inspectCandidate(row.id)
            },
            onLabel = { label -> actions.labelWatch(row.id, label) },
        )
    }
    if (editDialog) {
        OverlayEditDialog(state, actions) { editDialog = false }
    }
    if (freezeDialog) {
        OverlayFreezeDialog(state, actions) { freezeDialog = false }
    }
}

@Composable
private fun OverlayListHeader(
    title: String,
    selectionMode: Boolean,
    selectedCount: Int,
    primaryLabel: String,
    primaryIcon: Int,
    onPrimary: () -> Unit,
    onClearSelection: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selectionMode) "$selectedCount ${stringResource(R.string.memory_editor_select).lowercase()}" else title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selectionMode) {
            IconButton(onClick = onClearSelection) {
                Icon(
                    painterResource(R.drawable.ic_deselect),
                    contentDescription = stringResource(R.string.memory_editor_clear_selection),
                )
            }
        } else {
            TextButton(onClick = onPrimary) {
                Icon(painterResource(primaryIcon), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(primaryLabel, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OverlayResultRow(
    row: MemoryResultRow,
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(
                onClick = { if (selectionMode) onToggle() else onOpen() },
                onLongClick = onToggle,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Spacer(Modifier.size(4.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.valueText,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.aliasTypes.joinToString(" · ") { overlayTypeLabel(it) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.addressText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                candidateStateText(row.state, row.relocations)?.let { stateText ->
                    Text(
                        stateText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OverlayWatchRow(
    row: MemoryWatchRow,
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(
                onClick = { if (selectionMode) onToggle() else onOpen() },
                onLongClick = onToggle,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Spacer(Modifier.size(4.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label.ifBlank { overlayTypeLabel(row.type) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.valueText,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                if (row.freezeMode >= 0) {
                    Icon(
                        painterResource(R.drawable.ic_screen_lock_rotation),
                        contentDescription = stringResource(R.string.memory_editor_freeze),
                        modifier = Modifier.padding(start = 6.dp).size(18.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${overlayTypeLabel(row.type)} · ${row.addressText}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    row.freezePaused -> Text(
                        stringResource(R.string.memory_editor_freeze_paused),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> candidateStateText(row.state, row.relocations)?.let { stateText ->
                        Text(
                            stateText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun OverlayEmptyList(modifier: Modifier, textRes: Int) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(stringResource(textRes), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverlayPager(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    if (state.resultCount <= MemoryEditorOverlayController.PAGE_SIZE) return
    val end = minOf(
        state.pageOffset.toLong() + MemoryEditorOverlayController.PAGE_SIZE,
        state.resultCount,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = actions::previousPage, enabled = state.pageOffset > 0) {
            Text(stringResource(R.string.memory_editor_previous_page))
        }
        Text(
            "${state.pageOffset + 1}–$end / ${state.resultCount}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        TextButton(
            onClick = actions::nextPage,
            enabled = state.pageOffset.toLong() + MemoryEditorOverlayController.PAGE_SIZE < state.resultCount,
        ) {
            Text(stringResource(R.string.memory_editor_next_page))
        }
    }
}

@Composable
private fun OverlayUtilityBar(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.watchTab) {
            TextButton(onClick = actions::undo, enabled = !state.busy && state.canUndo) {
                Icon(painterResource(R.drawable.ic_history), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.memory_editor_undo), modifier = Modifier.padding(start = 6.dp))
            }
        }
        TextButton(onClick = actions::refresh, enabled = !state.busy) {
            Icon(painterResource(R.drawable.ic_restart_alt), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.memory_editor_refresh), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun OverlaySelectionBar(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onEdit: () -> Unit,
    onFreeze: () -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionPrimaryAction(
                    icon = R.drawable.ic_edit,
                    label = stringResource(R.string.memory_editor_edit),
                    onClick = onEdit,
                    enabled = state.writeSupported,
                    modifier = Modifier.weight(1f),
                )
                SelectionPrimaryAction(
                    icon = R.drawable.ic_memory_editor_watch,
                    label = stringResource(
                        if (state.watchTab) R.string.memory_editor_remove else R.string.memory_editor_watch,
                    ),
                    onClick = {
                        actions.watchSelected(!state.watchTab)
                        actions.clearSelection()
                    },
                    modifier = Modifier.weight(1f),
                )
                SelectionPrimaryAction(
                    icon = R.drawable.ic_screen_lock_rotation,
                    label = stringResource(R.string.memory_editor_freeze),
                    onClick = onFreeze,
                    enabled = state.writeSupported,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { overflow = true }) {
                        Icon(
                            painterResource(R.drawable.ic_options),
                            contentDescription = stringResource(R.string.memory_editor_overlay_more_actions),
                        )
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        if (state.selected.size == 1) {
                            OverlayMenuItem(
                                R.drawable.ic_memory_editor_inspector,
                                stringResource(R.string.memory_editor_inspect_memory),
                            ) {
                                overflow = false
                                actions.inspectCandidate(state.selected.single())
                            }
                        }
                        if (state.watchTab) {
                            OverlayMenuItem(
                                R.drawable.ic_screen_lock_rotation,
                                stringResource(R.string.memory_editor_unfreeze),
                            ) {
                                overflow = false
                                actions.clearFreezeSelected(); actions.clearSelection()
                            }
                        } else {
                            OverlayMenuItem(R.drawable.ic_check, stringResource(R.string.memory_editor_keep)) {
                                overflow = false
                                actions.removeSelected(true); actions.clearSelection()
                            }
                            OverlayMenuItem(R.drawable.ic_delete, stringResource(R.string.memory_editor_remove)) {
                                overflow = false
                                actions.removeSelected(false); actions.clearSelection()
                            }
                        }
                        OverlayMenuItem(
                            R.drawable.ic_content_copy,
                            stringResource(R.string.memory_editor_copy_values),
                        ) {
                            overflow = false
                            actions.copySelected(false)
                        }
                        OverlayMenuItem(
                            R.drawable.ic_content_copy,
                            stringResource(R.string.memory_editor_copy_addresses),
                        ) {
                            overflow = false
                            actions.copySelected(true)
                        }
                        HorizontalDivider()
                        OverlayMenuItem(R.drawable.ic_select_all, stringResource(R.string.memory_editor_select_visible)) {
                            overflow = false
                            actions.selectVisible()
                        }
                        OverlayMenuItem(R.drawable.ic_swap, stringResource(R.string.memory_editor_invert_visible)) {
                            overflow = false
                            actions.invertVisible()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionPrimaryAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.sizeIn(minHeight = 48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun OverlayMenuItem(icon: Int, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun OverlayRefineDialog(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    var predicate by remember(state.searchMode) {
        mutableIntStateOf(
            if (state.searchMode == MemorySearchMode.UNKNOWN) MemoryEngineContract.PREDICATE_CHANGED
            else MemoryEngineContract.PREDICATE_EQUAL,
        )
    }
    var compare by remember { mutableIntStateOf(MemoryEngineContract.COMPARE_PREVIOUS) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val spec = MemoryInputSpec.forType(state.requestedType)
    val needsValue = predicateNeedsValue(predicate)
    val needsSecond = predicateNeedsSecondValue(predicate)
    val valid = (!needsValue || spec.isComplete(first)) && (!needsSecond || spec.isComplete(second))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_refine_results)) },
        text = {
            MemoryInputArea(
                active = needsValue,
                sideDockInLandscape = false,
                alwaysShowKeypad = needsValue,
                keypadSpec = spec,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        overlayTypeLabel(state.requestedType),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RefineChip("=", MemoryEngineContract.PREDICATE_EQUAL, predicate) { predicate = it }
                        RefineChip("≠", MemoryEngineContract.PREDICATE_NOT_EQUAL, predicate) { predicate = it }
                        RefineChip(stringResource(R.string.memory_editor_predicate_changed), MemoryEngineContract.PREDICATE_CHANGED, predicate) { predicate = it }
                        RefineChip(stringResource(R.string.memory_editor_predicate_unchanged), MemoryEngineContract.PREDICATE_UNCHANGED, predicate) { predicate = it }
                        RefineChip(stringResource(R.string.memory_editor_predicate_increased), MemoryEngineContract.PREDICATE_INCREASED, predicate) { predicate = it }
                        RefineChip(stringResource(R.string.memory_editor_predicate_decreased), MemoryEngineContract.PREDICATE_DECREASED, predicate) { predicate = it }
                    }
                    FullPredicatePicker(predicate = predicate, onPredicate = { predicate = it })
                    if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) {
                        ComparePicker(compare = compare, onCompare = { compare = it })
                    }
                    if (needsValue) {
                        MemoryValueInput(
                            value = first,
                            onValueChange = { first = it },
                            spec = spec,
                            label = stringResource(R.string.memory_editor_search_hint),
                            modifier = Modifier.fillMaxWidth(),
                            activateOnStart = true,
                        )
                    }
                    if (needsSecond) {
                        MemoryValueInput(
                            value = second,
                            onValueChange = { second = it },
                            spec = spec,
                            label = stringResource(R.string.memory_editor_max_value),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    actions.nextScan(first, second, predicate, compare)
                },
                enabled = !state.busy && state.resultCount > 0L && valid,
            ) {
                Text(stringResource(R.string.memory_editor_next_scan))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDismiss(); actions.startOver()
                }) {
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
private fun RefineChip(label: String, value: Int, selected: Int, onChange: (Int) -> Unit) {
    FilterChip(selected = selected == value, onClick = { onChange(value) }, label = { Text(label, maxLines = 1) })
}

@Composable
private fun FullPredicatePicker(predicate: Int, onPredicate: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(overlayPredicateDescription(predicate), modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            REFINE_PREDICATES.forEach { option ->
                DropdownMenuItem(
                    text = { Text(overlayPredicateDescription(option)) },
                    onClick = { expanded = false; onPredicate(option) },
                )
            }
        }
    }
}

@Composable
private fun ComparePicker(compare: Int, onCompare: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = compare == MemoryEngineContract.COMPARE_PREVIOUS,
            onClick = { onCompare(MemoryEngineContract.COMPARE_PREVIOUS) },
            label = { Text(stringResource(R.string.memory_editor_previous)) },
        )
        FilterChip(
            selected = compare == MemoryEngineContract.COMPARE_INITIAL,
            onClick = { onCompare(MemoryEngineContract.COMPARE_INITIAL) },
            label = { Text(stringResource(R.string.memory_editor_initial)) },
        )
    }
}

@Composable
private fun OverlayEditDialog(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    val types = editableTypesForOverlay(state)
    var type by remember(types) {
        mutableIntStateOf(types.firstOrNull() ?: MemoryEngineContract.TYPE_INT)
    }
    var value by remember { mutableStateOf("") }
    var addToWatch by remember(state.watchTab) { mutableStateOf(false) }
    var freezeAfter by remember { mutableStateOf(false) }
    val spec = MemoryInputSpec.forType(type)
    val canFreeze = state.selected.size in 1..MemoryEngineContract.MAX_FREEZE_RECORDS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_edit)) },
        text = {
            MemoryInputArea(
                active = true,
                sideDockInLandscape = false,
                alwaysShowKeypad = true,
                keypadSpec = spec,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.writeSupported) {
                        Text(
                            stringResource(R.string.memory_editor_write_unsupported),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TypePicker(
                        type = type,
                        onType = { type = it },
                        modifier = Modifier.fillMaxWidth(),
                        options = types.toIntArray(),
                    )
                    MemoryValueInput(
                        value = value,
                        onValueChange = { value = it },
                        spec = spec,
                        label = stringResource(R.string.memory_editor_replacement),
                        modifier = Modifier.fillMaxWidth(),
                        activateOnStart = true,
                    )
                    if (!state.watchTab) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = addToWatch,
                                onCheckedChange = { addToWatch = it },
                                enabled = !freezeAfter,
                            )
                            Text(stringResource(R.string.memory_editor_add_to_watch_after_edit))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = freezeAfter,
                            onCheckedChange = { freezeAfter = it },
                            enabled = canFreeze && state.writeSupported,
                        )
                        Text(stringResource(R.string.memory_editor_freeze_after_edit))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    actions.editSelectedWithOptions(value, type, addToWatch, freezeAfter)
                    actions.clearSelection()
                    onDismiss()
                },
                enabled = state.writeSupported && types.isNotEmpty() && spec.isComplete(value),
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
private fun OverlayFreezeDialog(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onDismiss: () -> Unit,
) {
    val types = editableTypesForOverlay(state)
    val initial = if (state.watchTab) {
        state.watches.firstOrNull { it.id in state.selected }?.valueText.orEmpty()
    } else {
        state.results.firstOrNull { it.id in state.selected }?.valueText.orEmpty()
    }
    var mode by remember { mutableIntStateOf(MemoryEngineContract.FREEZE_LOCK) }
    var first by remember(initial, types) {
        mutableStateOf(initial.takeIf { memoryInputCompleteForTypes(it, types) }.orEmpty())
    }
    var second by remember { mutableStateOf("") }
    val spec = MemoryInputSpec.forType(types.singleOrNull() ?: MemoryEngineContract.TYPE_AUTO)
    val valid = memoryInputCompleteForTypes(first, types) &&
        (mode != MemoryEngineContract.FREEZE_RANGE || memoryInputCompleteForTypes(second, types))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_freeze)) },
        text = {
            MemoryInputArea(
                active = true,
                sideDockInLandscape = false,
                alwaysShowKeypad = true,
                keypadSpec = spec,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FreezeModePicker(mode = mode, onMode = { mode = it })
                    Text(freezeDescription(mode), style = MaterialTheme.typography.bodySmall)
                    MemoryValueInput(
                        value = first,
                        onValueChange = { first = it },
                        spec = spec,
                        label = stringResource(
                            if (mode == MemoryEngineContract.FREEZE_RANGE) R.string.memory_editor_min_value
                            else R.string.memory_editor_freeze_target,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        activateOnStart = true,
                    )
                    if (mode == MemoryEngineContract.FREEZE_RANGE) {
                        MemoryValueInput(
                            value = second,
                            onValueChange = { second = it },
                            spec = spec,
                            label = stringResource(R.string.memory_editor_max_value),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    actions.freezeSelected(mode, first, second)
                    actions.clearSelection()
                    onDismiss()
                },
                enabled = state.writeSupported && types.isNotEmpty() && valid,
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
private fun FreezeModePicker(mode: Int, onMode: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(freezeName(mode), modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FREEZE_MODES.forEach { option ->
                DropdownMenuItem(
                    text = { Text(freezeName(option)) },
                    onClick = { expanded = false; onMode(option) },
                )
            }
        }
    }
}

@Composable
private fun OverlayResultDetailDialog(
    row: MemoryResultRow,
    writeSupported: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onWatch: () -> Unit,
    onFreeze: () -> Unit,
    onInspect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.valueText, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OverlayDetailLine(stringResource(R.string.memory_editor_address), row.addressText)
                OverlayDetailLine(
                    stringResource(R.string.memory_editor_data_type),
                    row.aliasTypes.joinToString(" · ") { overlayTypeLabel(it) },
                )
                candidateStateText(row.state, row.relocations)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onEdit, enabled = writeSupported) { Text(stringResource(R.string.memory_editor_edit)) }
                    TextButton(onClick = onWatch) { Text(stringResource(R.string.memory_editor_watch)) }
                    TextButton(onClick = onFreeze, enabled = writeSupported) { Text(stringResource(R.string.memory_editor_freeze)) }
                    TextButton(onClick = onInspect) { Text(stringResource(R.string.memory_editor_explore)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_editor_done)) }
        },
    )
}

@Composable
private fun OverlayWatchDetailDialog(
    row: MemoryWatchRow,
    writeSupported: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onFreeze: () -> Unit,
    onInspect: () -> Unit,
    onLabel: (String) -> Unit,
) {
    var editingLabel by remember(row.id) { mutableStateOf(false) }
    var label by remember(row.id, row.label) { mutableStateOf(row.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.label.ifBlank { row.valueText }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (editingLabel) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(64) },
                        label = { Text(stringResource(R.string.memory_editor_watch_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { editingLabel = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        TextButton(onClick = { onLabel(label); editingLabel = false }) {
                            Text(stringResource(R.string.memory_editor_apply))
                        }
                    }
                } else {
                    TextButton(onClick = { editingLabel = true }) {
                        Text(stringResource(R.string.memory_editor_watch_label))
                    }
                }
                OverlayDetailLine(stringResource(R.string.memory_editor_current_value), row.valueText)
                OverlayDetailLine(stringResource(R.string.memory_editor_previous_value), row.previousValueText)
                OverlayDetailLine(stringResource(R.string.memory_editor_initial_value), row.initialValueText)
                OverlayDetailLine(stringResource(R.string.memory_editor_address), row.addressText)
                OverlayDetailLine(stringResource(R.string.memory_editor_data_type), overlayTypeLabel(row.type))
                if (row.freezeMode >= 0) {
                    OverlayDetailLine(
                        stringResource(R.string.memory_editor_freeze),
                        if (row.freezePaused) stringResource(R.string.memory_editor_freeze_paused)
                        else freezeName(row.freezeMode),
                    )
                }
                candidateStateText(row.state, row.relocations)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onEdit, enabled = writeSupported) { Text(stringResource(R.string.memory_editor_edit)) }
                    TextButton(onClick = onFreeze, enabled = writeSupported) { Text(stringResource(R.string.memory_editor_freeze)) }
                    TextButton(onClick = onInspect) { Text(stringResource(R.string.memory_editor_explore)) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.memory_editor_remove)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_editor_done)) }
        },
    )
}

@Composable
private fun OverlayDetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun editableTypesForOverlay(state: MemoryEditorUiState): List<Int> {
    if (!state.watchTab) return commonTypesForSelection(state.results, state.selected)
    val selected = state.watches.filter { it.id in state.selected }
    val types = selected.map { it.type }.distinct()
    return if (selected.isNotEmpty() && types.size == 1) types else emptyList()
}

@Composable
private fun candidateStateText(state: Int, relocations: Int): String? = when (state) {
    MemoryEngineContract.CANDIDATE_STABLE -> if (relocations > 0) {
        stringResource(R.string.memory_editor_candidate_moved)
    } else null
    MemoryEngineContract.CANDIDATE_RELOCATING -> stringResource(R.string.memory_editor_candidate_relocating)
    MemoryEngineContract.CANDIDATE_AMBIGUOUS -> stringResource(R.string.memory_editor_candidate_ambiguous)
    else -> stringResource(R.string.memory_editor_candidate_lost)
}

@Composable
private fun freezeName(mode: Int): String = stringResource(
    when (mode) {
        MemoryEngineContract.FREEZE_LOCK -> R.string.memory_editor_freeze_lock
        MemoryEngineContract.FREEZE_MINIMUM -> R.string.memory_editor_freeze_minimum
        MemoryEngineContract.FREEZE_MAXIMUM -> R.string.memory_editor_freeze_maximum
        else -> R.string.memory_editor_freeze_range
    },
)

@Composable
private fun freezeDescription(mode: Int): String = stringResource(
    when (mode) {
        MemoryEngineContract.FREEZE_LOCK -> R.string.memory_editor_freeze_lock_help
        MemoryEngineContract.FREEZE_MINIMUM -> R.string.memory_editor_freeze_minimum_help
        MemoryEngineContract.FREEZE_MAXIMUM -> R.string.memory_editor_freeze_maximum_help
        else -> R.string.memory_editor_freeze_range_help
    },
)

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

@Composable
internal fun MemoryEditorOverlayBubble(
    state: MemoryEditorUiState,
    onTouch: (MotionEvent) -> Boolean,
) {
    val context = LocalContext.current
    val description = stringResource(R.string.memory_editor)
    val progress = if (state.busy && state.searching && state.scanBytesTotal > 0L) {
        (state.scanBytesScanned.toFloat() / state.scanBytesTotal.toFloat()).coerceIn(0f, 1f)
    } else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter(onTouchEvent = onTouch)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                onClick {
                    MemoryEditorOverlayService.open(context)
                    true
                }
            }
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 8.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_memory_editor_search),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
                when {
                    progress != null -> Text(
                        "${(progress * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                    )
                    state.sessionStage == MemorySessionStage.CANDIDATES && state.resultCount > 0L -> Text(
                        formatCompactCount(state.resultCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

private fun insertSearchToken(value: TextFieldValue, token: String, allowGroup: Boolean): TextFieldValue {
    if (!allowGroup && (token == ";" || token == ":")) return value
    val allowed = token.all { char ->
        char.isDigit() || char in charArrayOf('-', '+', '.', 'e', 'E', ';', ':')
    }
    if (!allowed) return value
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val next = value.text.replaceRange(start, end, token)
    if (next.length > 96) return value
    return TextFieldValue(next, TextRange(start + token.length))
}

private fun backspaceSearchValue(value: TextFieldValue): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    if (start != end) return TextFieldValue(value.text.removeRange(start, end), TextRange(start))
    if (start == 0) return value
    return TextFieldValue(value.text.removeRange(start - 1, start), TextRange(start - 1))
}

private fun moveSearchCursor(value: TextFieldValue, delta: Int): TextFieldValue {
    val current = if (delta < 0) minOf(value.selection.start, value.selection.end)
    else maxOf(value.selection.start, value.selection.end)
    return value.copy(selection = TextRange((current + delta).coerceIn(0, value.text.length)))
}

private fun overlayTypeLabel(type: Int): String = when (type) {
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

@Composable
private fun overlayTypeDescription(type: Int): String = when (type) {
    MemoryEngineContract.TYPE_AUTO -> stringResource(R.string.memory_editor_overlay_auto_description)
    MemoryEngineContract.TYPE_BYTE -> "−128 … 127"
    MemoryEngineContract.TYPE_SHORT -> "−32,768 … 32,767"
    MemoryEngineContract.TYPE_CHAR -> "0 … 65,535"
    MemoryEngineContract.TYPE_INT -> "−2,147,483,648 … 2,147,483,647"
    MemoryEngineContract.TYPE_LONG -> stringResource(R.string.memory_editor_overlay_int64_description)
    MemoryEngineContract.TYPE_FLOAT -> stringResource(R.string.memory_editor_overlay_float32_description)
    MemoryEngineContract.TYPE_DOUBLE -> stringResource(R.string.memory_editor_overlay_float64_description)
    else -> ""
}

private fun overlayPredicateLabel(predicate: Int): String = when (predicate) {
    MemoryEngineContract.PREDICATE_EQUAL -> "="
    MemoryEngineContract.PREDICATE_NOT_EQUAL -> "≠"
    MemoryEngineContract.PREDICATE_GREATER -> ">"
    MemoryEngineContract.PREDICATE_LESS -> "<"
    MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL -> "≥"
    MemoryEngineContract.PREDICATE_LESS_OR_EQUAL -> "≤"
    MemoryEngineContract.PREDICATE_BETWEEN -> "↔"
    else -> "="
}

@Composable
private fun overlayPredicateDescription(predicate: Int): String = when (predicate) {
    MemoryEngineContract.PREDICATE_EQUAL -> "Equal (=)"
    MemoryEngineContract.PREDICATE_NOT_EQUAL -> "Not equal (≠)"
    MemoryEngineContract.PREDICATE_GREATER -> "Greater than (>)"
    MemoryEngineContract.PREDICATE_LESS -> "Less than (<)"
    MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL -> "Greater or equal (≥)"
    MemoryEngineContract.PREDICATE_LESS_OR_EQUAL -> "Less or equal (≤)"
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

private fun formatCompactCount(value: Long): String = when {
    value >= 1_000_000L -> "${value / 1_000_000L}M+"
    value >= 10_000L -> "${value / 1_000L}K+"
    else -> value.toString()
}

private val OVERLAY_VALUE_TYPES = intArrayOf(
    MemoryEngineContract.TYPE_AUTO,
    MemoryEngineContract.TYPE_BYTE,
    MemoryEngineContract.TYPE_SHORT,
    MemoryEngineContract.TYPE_CHAR,
    MemoryEngineContract.TYPE_INT,
    MemoryEngineContract.TYPE_LONG,
    MemoryEngineContract.TYPE_FLOAT,
    MemoryEngineContract.TYPE_DOUBLE,
)

private val INITIAL_PREDICATES = intArrayOf(
    MemoryEngineContract.PREDICATE_EQUAL,
    MemoryEngineContract.PREDICATE_NOT_EQUAL,
    MemoryEngineContract.PREDICATE_GREATER,
    MemoryEngineContract.PREDICATE_LESS,
    MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL,
    MemoryEngineContract.PREDICATE_LESS_OR_EQUAL,
    MemoryEngineContract.PREDICATE_BETWEEN,
)

private val REFINE_PREDICATES = intArrayOf(
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
)

private val FREEZE_MODES = intArrayOf(
    MemoryEngineContract.FREEZE_LOCK,
    MemoryEngineContract.FREEZE_MINIMUM,
    MemoryEngineContract.FREEZE_MAXIMUM,
    MemoryEngineContract.FREEZE_RANGE,
)
