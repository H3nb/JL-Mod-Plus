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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.playsoftware.j2meloader.R
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
    val value: String,
)

/**
 * Stage 3 wrapper. Existing search/watch UI stays untouched; contextual exploration is layered
 * around one explicitly selected CandidateId.
 */
@Composable
internal fun MemoryEditorStage3Root(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
) {
    var exploreRow by remember { mutableStateOf<MemoryCandidateRow?>(null) }
    var nearbyAnchor by remember { mutableStateOf<MemoryNearbyAnchor?>(null) }
    val selectedRow = remember(state.selected, state.results, state.watches) {
        state.selected.singleOrNull()?.let { id ->
            state.results.firstOrNull { it.id == id } ?: state.watches.firstOrNull { it.id == id }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MemoryEditorScreen(state = state, actions = actions)
        if (state.visible && !state.busy && !state.inspectorLoading && state.inspector == null &&
            selectedRow != null) {
            Button(
                onClick = { exploreRow = selectedRow },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 72.dp)
                    .sizeIn(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.memory_editor_explore))
            }
        }
    }

    exploreRow?.let { row ->
        MemoryExploreDialog(
            row = row,
            onDismiss = { exploreRow = null },
            onInspect = {
                exploreRow = null
                actions.inspectCandidate(row.id)
            },
            onNearby = {
                exploreRow = null
                nearbyAnchor = MemoryNearbyAnchor(row.id, row.type, row.label, row.address)
            },
        )
    }

    if (state.inspectorLoading) {
        MemoryInspectorLoadingDialog(onDismiss = actions::closeInspector)
    }

    state.inspector?.let { snapshot ->
        MemoryInspectorDialog(
            snapshot = snapshot,
            onDismiss = actions::closeInspector,
            onRefresh = { radius -> actions.inspectCandidate(snapshot.candidateId, radius) },
            onNearby = {
                actions.closeInspector()
                nearbyAnchor = MemoryNearbyAnchor(
                    candidateId = snapshot.candidateId,
                    type = snapshot.type,
                    label = snapshot.label,
                    address = snapshot.anchorAddress,
                )
            },
        )
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
private fun MemoryExploreDialog(
    row: MemoryCandidateRow,
    onDismiss: () -> Unit,
    onInspect: () -> Unit,
    onNearby: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_explore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.label.ifBlank { MemoryEditorPageParser.value(row) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "0x${row.address.toULong().toString(16).uppercase()} · ${stage3TypeName(row.type)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                HorizontalDivider()
                OutlinedButton(onClick = onInspect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.memory_editor_inspect_memory))
                }
                OutlinedButton(onClick = onNearby, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.memory_editor_search_nearby))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun MemoryInspectorLoadingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_inspector)) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.memory_editor_inspector_loading))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun MemoryInspectorDialog(
    snapshot: MemoryInspectorSnapshot,
    onDismiss: () -> Unit,
    onRefresh: (Int) -> Unit,
    onNearby: () -> Unit,
) {
    var viewType by remember(snapshot.candidateId, snapshot.anchorAddress) {
        mutableIntStateOf(
            snapshot.type.takeIf(MemoryEngineContract::isCandidateType)
                ?: MemoryEngineContract.TYPE_INT,
        )
    }
    var radius by remember(snapshot.candidateId) {
        mutableIntStateOf(MemoryEngineContract.DEFAULT_INSPECT_RADIUS)
    }
    val cells = remember(snapshot, viewType) { buildInspectorCells(snapshot, viewType) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 620.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    snapshot.label.ifBlank { stringResource(R.string.memory_editor_inspector) },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.memory_editor_inspector_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.memory_editor_anchor))
                    Text(
                        "0x${snapshot.anchorAddress.toULong().toString(16).uppercase()}",
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.memory_editor_view_as), modifier = Modifier.weight(1f))
                    Stage3ChoiceMenu(
                        value = viewType,
                        values = STAGE3_VIEW_TYPES,
                        label = ::stage3TypeName,
                        onChange = { viewType = it },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.memory_editor_inspect_radius))
                    INSPECT_RADIUS_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = radius == preset,
                            onClick = { radius = preset },
                            label = { Text("±$preset") },
                        )
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
                        InspectorCellRow(cell = cell)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { onRefresh(radius) }) {
                        Text(stringResource(R.string.memory_editor_refresh_snapshot))
                    }
                    TextButton(onClick = onNearby) {
                        Text(stringResource(R.string.memory_editor_search_nearby))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.memory_editor_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorCellRow(cell: MemoryInspectorCell) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (cell.offset == 0) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
            )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_search_nearby)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.memory_editor_nearby_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "0x${anchor.address.toULong().toString(16).uppercase()}",
                    fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.memory_editor_nearby_radius), style = MaterialTheme.typography.labelMedium)
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
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_editor_search_hint)) },
                )
                if (between) {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.memory_editor_max_value)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSearch(radius, type, predicate, first, second) },
                enabled = !busy && first.isNotBlank() && (!between || second.isNotBlank()),
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
            cells += MemoryInspectorCell(
                offset = offset,
                address = address,
                value = decodeInspectorValue(snapshot.bytes, index, type),
            )
        }
        if (offset > Int.MAX_VALUE - width) break
        offset += width
    }
    return cells
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
