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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.availableWindowWidthDp
import ru.playsoftware.j2meloader.ui.AdaptiveAlertDialog as AlertDialog
import java.nio.ByteBuffer
import java.nio.ByteOrder

private data class MemoryNearbyAnchor(
    val candidateId: Long,
    val type: Int,
    val label: String,
    val address: Long,
)

internal data class MemoryInspectorCell(
    val offset: Int,
    val address: Long,
    val bits: Long,
    val value: String,
)

internal data object InspectorCellsRoute : NavKey
internal data object InspectorControlsRoute : NavKey

internal fun encodeInspectorBackStack(stack: Iterable<NavKey>): List<String> = stack.map { route ->
    when (route) {
        InspectorCellsRoute -> "cells"
        InspectorControlsRoute -> "controls"
        else -> error("Unsupported Inspector route: $route")
    }
}

internal fun decodeInspectorBackStack(saved: Iterable<String>): NavBackStack<NavKey> =
    NavBackStack<NavKey>(*saved.map { route ->
        when (route) {
            "cells" -> InspectorCellsRoute
            "controls" -> InspectorControlsRoute
            else -> error("Unsupported saved Inspector route: $route")
        }
    }.toTypedArray())

private val inspectorBackStackSaver = Saver<NavBackStack<NavKey>, List<String>>(
    save = { stack -> encodeInspectorBackStack(stack) },
    restore = { saved -> decodeInspectorBackStack(saved) },
)

private enum class MemoryEditorDestination(
    val labelRes: Int,
    val iconRes: Int,
) {
    SEARCH(R.string.memory_editor_search_tab, R.drawable.ic_memory_editor_search),
    WATCH(R.string.memory_editor_watch, R.drawable.ic_memory_editor_watch),
    INSPECTOR(R.string.memory_editor_inspector, R.drawable.ic_memory_editor_inspector),
}

/**
 * The top-level workspace owns exactly three destinations. It follows the app's adaptive
 * navigation pattern: a bottom bar on compact windows and a rail on wider windows.
 */

@Composable
internal fun MemoryEditorStage3Root(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
) {
    var nearbyAnchor by remember { mutableStateOf<MemoryNearbyAnchor?>(null) }
    var destination by remember {
        mutableStateOf(
            when {
                state.inspectorLoading || state.inspector != null -> MemoryEditorDestination.INSPECTOR
                state.watchTab -> MemoryEditorDestination.WATCH
                else -> MemoryEditorDestination.SEARCH
            },
        )
    }
    LaunchedEffect(state.visible, state.runtimeToken) {
        if (!state.visible || state.runtimeToken == 0L) {
            nearbyAnchor = null
            destination = MemoryEditorDestination.SEARCH
        }
    }
    LaunchedEffect(state.inspectorLoading, state.inspector) {
        if (state.inspectorLoading || state.inspector != null) {
            destination = MemoryEditorDestination.INSPECTOR
        }
    }
    LaunchedEffect(state.visible, state.watchTab) {
        if (state.visible && !state.inspectorLoading && state.inspector == null) {
            destination = if (state.watchTab) {
                MemoryEditorDestination.WATCH
            } else {
                MemoryEditorDestination.SEARCH
            }
        }
    }

    val useNavigationRail = availableWindowWidthDp() >= 600.dp
    val selectDestination: (MemoryEditorDestination) -> Unit = { item ->
        destination = item
        when (item) {
            MemoryEditorDestination.SEARCH -> actions.setWatchTab(false)
            MemoryEditorDestination.WATCH -> actions.setWatchTab(true)
            MemoryEditorDestination.INSPECTOR -> Unit
        }
    }

    MemoryEditorSurface(state = state, actions = actions) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                MemoryEditorNavigationRail(
                    destination = destination,
                    onSelect = selectDestination,
                )
            }
            Scaffold(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!useNavigationRail) {
                        MemoryEditorNavigationBar(
                            destination = destination,
                            onSelect = selectDestination,
                        )
                    }
                },
            ) { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                ) {
                MemoryEditorWorkspaceHeader(destination = destination, actions = actions)
                when (destination) {
                    MemoryEditorDestination.SEARCH,
                    MemoryEditorDestination.WATCH -> MemoryEditorContent(
                        state = state,
                        actions = actions,
                        showHeader = false,
                        showWorkspaceTabs = false,
                    )
                    MemoryEditorDestination.INSPECTOR -> when {
                        state.inspectorLoading -> MemoryInspectorLoadingPane()
                        state.inspector != null -> {
                            val snapshot = requireNotNull(state.inspector)
                            MemoryInspectorWorkspace(
                                snapshot = snapshot,
                                actions = actions,
                                onBack = {
                                    actions.closeInspector()
                                    destination = if (state.watchTab) {
                                        MemoryEditorDestination.WATCH
                                    } else {
                                        MemoryEditorDestination.SEARCH
                                    }
                                },
                                onRefresh = { radius ->
                                    actions.inspectCandidate(snapshot.candidateId, radius)
                                },
                                onNearby = {
                                    actions.closeInspector()
                                    actions.setWatchTab(false)
                                    destination = MemoryEditorDestination.SEARCH
                                    nearbyAnchor = MemoryNearbyAnchor(
                                        candidateId = snapshot.candidateId,
                                        type = snapshot.type,
                                        label = snapshot.label,
                                        address = snapshot.anchorAddress,
                                    )
                                },
                            )
                        }
                        else -> MemoryInspectorEmptyPane(
                            onOpenSearch = {
                                destination = MemoryEditorDestination.SEARCH
                                actions.setWatchTab(false)
                            },
                        )
                    }
                }
            }
        }
    }
    }

    nearbyAnchor?.let { anchor ->
        MemoryNearbySearchDialog(
            anchor = anchor,
            busy = state.busy,
            onDismiss = { nearbyAnchor = null },
            onSearch = { radius, type, predicate, first, second ->
                nearbyAnchor = null
                actions.startNearbySearch(
                    anchor.candidateId,
                    radius,
                    type,
                    predicate,
                    first,
                    second,
                )
            },
        )
    }
}

@Composable
private fun MemoryInspectorLoadingPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.memory_editor_inspector_loading))
        }
    }
}

@Composable
private fun MemoryEditorNavigationRail(
    destination: MemoryEditorDestination,
    onSelect: (MemoryEditorDestination) -> Unit,
) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        MemoryEditorDestination.entries.forEach { item ->
            val label = stringResource(item.labelRes)
            NavigationRailItem(
                selected = destination == item,
                onClick = { onSelect(item) },
                icon = {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = label,
                    )
                },
                label = { Text(label) },
                alwaysShowLabel = false,
            )
        }
    }
}

@Composable
private fun MemoryEditorNavigationBar(
    destination: MemoryEditorDestination,
    onSelect: (MemoryEditorDestination) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        MemoryEditorDestination.entries.forEach { item ->
            val label = stringResource(item.labelRes)
            NavigationBarItem(
                selected = destination == item,
                onClick = { onSelect(item) },
                icon = {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = label,
                    )
                },
                label = { Text(label) },
                alwaysShowLabel = false,
            )
        }
    }
}

@Composable
private fun MemoryEditorWorkspaceHeader(
    destination: MemoryEditorDestination,
    actions: MemoryEditorActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(destination.iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(destination.labelRes),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        IconButton(onClick = actions::close) {
            Icon(
                painter = painterResource(R.drawable.ic_memory_editor_close),
                contentDescription = stringResource(R.string.memory_editor_close),
            )
        }
    }
}

@Composable
private fun MemoryInspectorEmptyPane(onOpenSearch: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.memory_editor_inspector_empty),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onOpenSearch, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.memory_editor_search_tab))
        }
    }
}


@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun MemoryInspectorWorkspace(
    snapshot: MemoryInspectorSnapshot,
    actions: MemoryEditorActions,
    onBack: () -> Unit,
    onRefresh: (Int) -> Unit,
    onNearby: () -> Unit,
) {
    var viewType by remember(snapshot.candidateId) {
        mutableIntStateOf(
            snapshot.type.takeIf(MemoryEngineContract::isCandidateType)
                ?: MemoryEngineContract.TYPE_INT,
        )
    }
    var radius by remember(snapshot.candidateId) {
        mutableIntStateOf(MemoryEngineContract.DEFAULT_INSPECT_RADIUS)
    }
    val cells = remember(snapshot, viewType) { buildInspectorCells(snapshot, viewType) }
    var editingCell by remember(snapshot, viewType) { mutableStateOf<MemoryInspectorCell?>(null) }
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(adaptiveInfo) {
        calculatePaneScaffoldDirective(adaptiveInfo).copy(
            horizontalPartitionSpacerSize = 0.dp,
            verticalPartitionSpacerSize = 0.dp,
        )
    }
    val showSupportingPane = directive.maxHorizontalPartitions > 1
    val backStack = rememberSaveable(
        snapshot.candidateId,
        showSupportingPane,
        saver = inspectorBackStackSaver,
    ) {
        NavBackStack<NavKey>(InspectorCellsRoute).apply {
            if (showSupportingPane) add(InspectorControlsRoute)
        }
    }
    val sceneStrategy = rememberSupportingPaneSceneStrategy<NavKey>(
        backNavigationBehavior = BackNavigationBehavior.PopUntilCurrentDestinationChange,
        directive = directive,
    )
    val navigationEventDispatcherOwner = rememberNavigationEventDispatcherOwner(parent = null)

    LaunchedEffect(snapshot.candidateId, showSupportingPane) {
        if (showSupportingPane && InspectorControlsRoute !in backStack) {
            backStack.add(InspectorControlsRoute)
        } else if (!showSupportingPane && backStack.lastOrNull() == InspectorControlsRoute) {
            backStack.removeLastOrNull()
        }
    }

    val openControls = {
        if (InspectorControlsRoute !in backStack) backStack.add(InspectorControlsRoute)
    }
    val returnToCells = {
        if (backStack.lastOrNull() == InspectorControlsRoute) backStack.removeLastOrNull()
    }

    Surface(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 8.dp,
    ) {
        CompositionLocalProvider(
            LocalNavigationEventDispatcherOwner provides navigationEventDispatcherOwner,
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = {
                    if (backStack.lastOrNull() == InspectorControlsRoute) returnToCells()
                    else onBack()
                },
                modifier = Modifier.fillMaxSize(),
                sceneStrategies = listOf(sceneStrategy),
                entryProvider = { key ->
                    when (key) {
                        InspectorCellsRoute -> NavEntry(
                            key = key,
                            metadata = SupportingPaneSceneStrategy.mainPane(),
                        ) {
                            InspectorMainPane(
                                snapshot = snapshot,
                                cells = cells,
                                showControlsButton = !showSupportingPane,
                                onOpenControls = openControls,
                                onEdit = { editingCell = it },
                                onDismiss = onBack,
                            )
                        }
                        InspectorControlsRoute -> NavEntry(
                            key = key,
                            metadata = SupportingPaneSceneStrategy.supportingPane(),
                        ) {
                            InspectorControlsPane(
                                snapshot = snapshot,
                                viewType = viewType,
                                onViewType = { viewType = it },
                                radius = radius,
                                onRadius = { radius = it },
                                onRefresh = { onRefresh(radius) },
                                onNearby = onNearby,
                                showBackToMemory = !showSupportingPane,
                                onBackToMemory = returnToCells,
                                onDismiss = onBack,
                            )
                        }
                        else -> error("Unsupported Inspector route: $key")
                    }
                },
            )
        }
    }
    editingCell?.let { cell ->
        InspectorEditDialog(
            cell = cell,
            type = viewType,
            onDismiss = { editingCell = null },
            onApply = { replacement ->
                editingCell = null
                actions.editInspectorValue(
                    snapshot.candidateId,
                    cell.offset,
                    viewType,
                    cell.bits,
                    replacement,
                )
            },
        )
    }
}

@Composable
private fun InspectorMainPane(
    snapshot: MemoryInspectorSnapshot,
    cells: List<MemoryInspectorCell>,
    showControlsButton: Boolean,
    onOpenControls: () -> Unit,
    onEdit: (MemoryInspectorCell) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    snapshot.label.ifBlank { stringResource(R.string.memory_editor_inspector) },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "0x${snapshot.anchorAddress.toULong().toString(16).uppercase()}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showControlsButton) {
                TextButton(onClick = onOpenControls) {
                    Text(stringResource(R.string.memory_editor_inspector_controls))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.memory_editor_done))
            }
        }
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.memory_editor_relative_offset),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(0.20f),
            )
            Text(
                stringResource(R.string.memory_editor_address),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(0.42f),
            )
            Text(
                stringResource(R.string.memory_editor_current_value),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(0.38f),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(cells, key = { it.address }) { cell ->
                InspectorCellRow(cell = cell, onEdit = { onEdit(cell) })
            }
        }
    }
}

@Composable
private fun InspectorControlsPane(
    snapshot: MemoryInspectorSnapshot,
    viewType: Int,
    onViewType: (Int) -> Unit,
    radius: Int,
    onRadius: (Int) -> Unit,
    onRefresh: () -> Unit,
    onNearby: () -> Unit,
    showBackToMemory: Boolean,
    onBackToMemory: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.memory_editor_inspector_controls),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "0x${snapshot.anchorAddress.toULong().toString(16).uppercase()}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Stage3ChoiceMenu(
                    value = viewType,
                    values = STAGE3_VIEW_TYPES,
                    label = ::stage3TypeName,
                    onChange = onViewType,
                )
                Stage3ChoiceMenu(
                    value = radius,
                    values = INSPECT_RADIUS_PRESETS,
                    label = { "±$it B" },
                    onChange = onRadius,
                )
            }
            Text(
                stringResource(R.string.memory_editor_inspector_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.memory_editor_refresh_snapshot))
            }
            TextButton(onClick = onNearby) {
                Text(stringResource(R.string.memory_editor_search_nearby))
            }
            if (showBackToMemory) {
                TextButton(onClick = onBackToMemory) {
                    Text(stringResource(R.string.memory_editor_back_to_memory))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.memory_editor_done))
            }
        }
    }
}


@Composable
private fun InspectorCellRow(cell: MemoryInspectorCell, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (cell.offset == 0) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
            )
            .clickable(onClick = onEdit)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (cell.offset >= 0) "+${cell.offset}" else cell.offset.toString(),
            modifier = Modifier.weight(0.20f),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (cell.offset == 0) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            "0x${cell.address.toULong().toString(16).uppercase()}",
            modifier = Modifier.weight(0.42f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            cell.value,
            modifier = Modifier.weight(0.38f),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (cell.offset == 0) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun InspectorEditDialog(
    cell: MemoryInspectorCell,
    type: Int,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var replacement by remember(cell, type) { mutableStateOf(cell.value) }
    val spec = MemoryInputSpec.forType(type)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_edit)) },
        text = {
            MemoryInputArea(sideDockInLandscape = false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "0x${cell.address.toULong().toString(16).uppercase()} · ${stage3TypeName(type)}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MemoryValueInput(
                        value = replacement,
                        onValueChange = { replacement = it },
                        spec = spec,
                        label = stringResource(R.string.memory_editor_current_value),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(replacement) },
                enabled = spec.isComplete(replacement),
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
private fun MemoryNearbySearchDialog(
    anchor: MemoryNearbyAnchor,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSearch: (Int, Int, Int, String, String) -> Unit,
) {
    var radius by remember(anchor.candidateId) { mutableIntStateOf(MemoryEngineContract.DEFAULT_NEARBY_RADIUS) }
    var type by remember(anchor.candidateId) {
        mutableIntStateOf(anchor.type.takeIf(MemoryEngineContract::isValueType) ?: MemoryEngineContract.TYPE_INT)
    }
    var predicate by remember(anchor.candidateId) { mutableIntStateOf(MemoryEngineContract.PREDICATE_EQUAL) }
    var first by remember(anchor.candidateId) { mutableStateOf("") }
    var second by remember(anchor.candidateId) { mutableStateOf("") }
    val between = predicate == MemoryEngineContract.PREDICATE_BETWEEN
    val spec = MemoryInputSpec.forType(type)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_search_nearby)) },
        text = {
            MemoryInputArea(sideDockInLandscape = false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "0x${anchor.address.toULong().toString(16).uppercase()}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NEARBY_RADIUS_PRESETS.take(3).forEach { preset ->
                            FilterChip(
                                selected = radius == preset,
                                onClick = { radius = preset },
                                label = { Text(stage3ByteRadius(preset)) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NEARBY_RADIUS_PRESETS.drop(3).forEach { preset ->
                            FilterChip(
                                selected = radius == preset,
                                onClick = { radius = preset },
                                label = { Text(stage3ByteRadius(preset)) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Stage3ChoiceMenu(
                            value = type,
                            values = STAGE3_SEARCH_TYPES,
                            label = ::stage3TypeName,
                            onChange = { type = it },
                        )
                        Stage3ChoiceMenu(
                            value = predicate,
                            values = STAGE3_KNOWN_PREDICATES,
                            label = ::stage3PredicateName,
                            onChange = { predicate = it },
                        )
                    }
                    MemoryValueInput(
                        value = first,
                        onValueChange = { first = it },
                        spec = spec,
                        label = stringResource(R.string.memory_editor_search_hint),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (between) {
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
                onClick = { onSearch(radius, type, predicate, first, second) },
                enabled = !busy && spec.isComplete(first) && (!between || spec.isComplete(second)),
            ) {
                Text(stringResource(R.string.memory_editor_search_action))
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
private fun Stage3ChoiceMenu(
    value: Int,
    values: IntArray,
    label: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(label(value), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

internal fun buildInspectorCells(
    snapshot: MemoryInspectorSnapshot,
    type: Int,
): List<MemoryInspectorCell> {
    val width = inspectorTypeWidth(type)
    if (width <= 0 || snapshot.bytes.isEmpty()) return emptyList()
    val start = snapshot.startAddress
    val anchor = snapshot.anchorAddress
    val endExclusive = start + snapshot.bytes.size.toLong()
    if (anchor < start || anchor >= endExclusive) return emptyList()

    val before = anchor - start
    var offset = -((before / width).toInt() * width)
    val cells = ArrayList<MemoryInspectorCell>()
    while (true) {
        val address = anchor + offset
        if (address >= endExclusive) break
        if (address >= start && address + width <= endExclusive) {
            val index = (address - start).toInt()
            val bits = inspectorBits(snapshot.bytes, index, width)
            cells += MemoryInspectorCell(
                offset = offset,
                address = address,
                bits = bits,
                value = decodeInspectorValue(snapshot.bytes, index, type),
            )
        }
        if (offset > Int.MAX_VALUE - width) break
        offset += width
    }
    return cells
}

private fun inspectorBits(bytes: ByteArray, index: Int, width: Int): Long {
    var result = 0L
    repeat(width) { byteIndex ->
        result = result or ((bytes[index + byteIndex].toLong() and 0xffL) shl (byteIndex * 8))
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

private fun decodeInspectorValue(bytes: ByteArray, index: Int, type: Int): String {
    val width = inspectorTypeWidth(type)
    if (width <= 0 || index < 0 || index + width > bytes.size) return "?"
    val buffer = ByteBuffer.wrap(bytes, index, width).order(ByteOrder.LITTLE_ENDIAN)
    return when (type) {
        MemoryEngineContract.TYPE_BYTE -> bytes[index].toString()
        MemoryEngineContract.TYPE_SHORT -> buffer.short.toString()
        MemoryEngineContract.TYPE_CHAR -> (buffer.short.toInt() and 0xffff).toString()
        MemoryEngineContract.TYPE_INT -> buffer.int.toString()
        MemoryEngineContract.TYPE_LONG -> buffer.long.toString()
        MemoryEngineContract.TYPE_FLOAT -> buffer.float.toString()
        MemoryEngineContract.TYPE_DOUBLE -> buffer.double.toString()
        else -> "?"
    }
}

private fun stage3TypeName(type: Int): String = when (type) {
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

private fun stage3PredicateName(predicate: Int): String = when (predicate) {
    MemoryEngineContract.PREDICATE_EQUAL -> "="
    MemoryEngineContract.PREDICATE_NOT_EQUAL -> "≠"
    MemoryEngineContract.PREDICATE_GREATER -> ">"
    MemoryEngineContract.PREDICATE_LESS -> "<"
    MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL -> "≥"
    MemoryEngineContract.PREDICATE_LESS_OR_EQUAL -> "≤"
    MemoryEngineContract.PREDICATE_BETWEEN -> "↔"
    else -> "?"
}

private fun stage3ByteRadius(radius: Int): String = when {
    radius >= 1024 -> "±${radius / 1024}K"
    else -> "±$radius"
}

private val STAGE3_VIEW_TYPES = intArrayOf(
    MemoryEngineContract.TYPE_INT,
    MemoryEngineContract.TYPE_FLOAT,
    MemoryEngineContract.TYPE_SHORT,
    MemoryEngineContract.TYPE_CHAR,
    MemoryEngineContract.TYPE_BYTE,
    MemoryEngineContract.TYPE_LONG,
    MemoryEngineContract.TYPE_DOUBLE,
)
private val STAGE3_SEARCH_TYPES = intArrayOf(
    MemoryEngineContract.TYPE_AUTO,
    MemoryEngineContract.TYPE_INT,
    MemoryEngineContract.TYPE_FLOAT,
    MemoryEngineContract.TYPE_SHORT,
    MemoryEngineContract.TYPE_CHAR,
    MemoryEngineContract.TYPE_BYTE,
    MemoryEngineContract.TYPE_LONG,
    MemoryEngineContract.TYPE_DOUBLE,
)
private val STAGE3_KNOWN_PREDICATES = intArrayOf(
    MemoryEngineContract.PREDICATE_EQUAL,
    MemoryEngineContract.PREDICATE_NOT_EQUAL,
    MemoryEngineContract.PREDICATE_GREATER,
    MemoryEngineContract.PREDICATE_LESS,
    MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL,
    MemoryEngineContract.PREDICATE_LESS_OR_EQUAL,
    MemoryEngineContract.PREDICATE_BETWEEN,
)
private val INSPECT_RADIUS_PRESETS = intArrayOf(64, 128, 256)
private val NEARBY_RADIUS_PRESETS = intArrayOf(64, 128, 256, 512, 1024, 4096)
