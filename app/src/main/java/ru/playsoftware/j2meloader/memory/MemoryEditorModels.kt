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

internal enum class MemorySearchMode {
    KNOWN,
    UNKNOWN,
    GROUP,
}

internal enum class MemorySessionStage {
    EMPTY,
    UNKNOWN_BASELINE,
    CANDIDATES,
}

internal fun memorySessionStageFromEngine(value: Int): MemorySessionStage = when (value) {
    MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE -> MemorySessionStage.UNKNOWN_BASELINE
    MemoryEngineContract.SEARCH_SESSION_CANDIDATES -> MemorySessionStage.CANDIDATES
    else -> MemorySessionStage.EMPTY
}

internal fun memorySearchModeFromEngine(value: Int): MemorySearchMode = when (value) {
    MemoryEngineContract.SEARCH_MODE_UNKNOWN -> MemorySearchMode.UNKNOWN
    MemoryEngineContract.SEARCH_MODE_GROUP -> MemorySearchMode.GROUP
    else -> MemorySearchMode.KNOWN
}

/** One engine-formatted Watch row. Raw target addresses and numeric bits stay in :memory_engine. */
internal data class MemoryWatchRow(
    val id: Long,
    val type: Int,
    val state: Int,
    val relocations: Int,
    val valueText: String,
    val initialValueText: String,
    val previousValueText: String,
    val addressText: String,
    val label: String = "",
    val freezeMode: Int = -1,
    val freezePaused: Boolean = false,
)

/** One engine-formatted logical address result. No raw address or numeric bits cross into :midlet. */
internal data class MemoryResultRow(
    val id: Long,
    val valueText: String,
    val addressText: String,
    val aliasMask: Int,
    val primaryType: Int,
    val state: Int,
    val relocations: Int,
) {
    val aliasTypes: List<Int>
        get() = (MemoryEngineContract.TYPE_BYTE..MemoryEngineContract.TYPE_DOUBLE)
            .filter { aliasMask and (1 shl it) != 0 }
}

internal data class MemoryGroupDraft(
    val value: String = "",
    val type: Int = MemoryEngineContract.TYPE_INT,
)

/** Bounded read-only snapshot whose address is resolved from a verified CandidateId. */
internal data class MemoryInspectorSnapshot(
    val candidateId: Long,
    val type: Int,
    val label: String,
    val startAddress: Long,
    val anchorAddress: Long,
    val bytes: ByteArray,
)

internal fun commonTypesForSelection(
    rows: List<MemoryResultRow>,
    selected: Set<Long>,
): List<Int> {
    return rows.filter { it.id in selected }
        .map { it.aliasTypes.toMutableSet() }
        .reduceOrNull { common, types -> common.apply { retainAll(types) } }
        ?.toList()
        .orEmpty()
}

internal object MemoryResultPageParser {
    fun parse(bundle: android.os.Bundle?): List<MemoryResultRow> {
        val ids = bundle?.getLongArray(MemoryEngineContract.KEY_RESULT_IDS) ?: return emptyList()
        val values = bundle.getStringArray(MemoryEngineContract.KEY_RESULT_VALUES) ?: return emptyList()
        val addresses = bundle.getStringArray(MemoryEngineContract.KEY_RESULT_ADDRESSES) ?: return emptyList()
        val aliasMasks = bundle.getIntArray(MemoryEngineContract.KEY_RESULT_ALIAS_MASKS) ?: return emptyList()
        val types = bundle.getIntArray(MemoryEngineContract.KEY_RESULT_TYPES) ?: return emptyList()
        val states = bundle.getIntArray(MemoryEngineContract.KEY_RESULT_STATES) ?: return emptyList()
        val relocations = bundle.getIntArray(MemoryEngineContract.KEY_RESULT_RELOCATIONS) ?: return emptyList()
        if (listOf(values.size, addresses.size, aliasMasks.size, types.size, states.size, relocations.size)
                .any { it != ids.size }) {
            return emptyList()
        }
        return ids.indices.mapNotNull { index ->
            val type = types[index]
            val mask = aliasMasks[index]
            if (ids[index] <= 0L || values[index] == null || addresses[index] == null ||
                !MemoryEngineContract.isCandidateType(type) ||
                mask and (1 shl type) == 0) {
                null
            } else {
                MemoryResultRow(
                    id = ids[index],
                    valueText = values[index],
                    addressText = addresses[index],
                    aliasMask = mask,
                    primaryType = type,
                    state = states[index],
                    relocations = relocations[index],
                )
            }
        }.takeIf { it.size == ids.size }.orEmpty()
    }
}

internal object MemoryWatchPageParser {
    fun parse(bundle: android.os.Bundle?): List<MemoryWatchRow> {
        val ids = bundle?.getLongArray(MemoryEngineContract.KEY_WATCH_IDS) ?: return emptyList()
        val values = bundle.getStringArray(MemoryEngineContract.KEY_WATCH_VALUES) ?: return emptyList()
        val initialValues = bundle.getStringArray(MemoryEngineContract.KEY_WATCH_INITIAL_VALUES) ?: return emptyList()
        val previousValues = bundle.getStringArray(MemoryEngineContract.KEY_WATCH_PREVIOUS_VALUES) ?: return emptyList()
        val addresses = bundle.getStringArray(MemoryEngineContract.KEY_WATCH_ADDRESSES) ?: return emptyList()
        val types = bundle.getIntArray(MemoryEngineContract.KEY_WATCH_TYPES) ?: return emptyList()
        val states = bundle.getIntArray(MemoryEngineContract.KEY_WATCH_STATES) ?: return emptyList()
        val relocations = bundle.getIntArray(MemoryEngineContract.KEY_WATCH_RELOCATIONS) ?: return emptyList()
        val labels = bundle.getStringArray(MemoryEngineContract.KEY_WATCH_LABELS) ?: return emptyList()
        val freezeModes = bundle.getIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES) ?: return emptyList()
        val freezePaused = bundle.getBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED) ?: return emptyList()
        return parse(
            ids, values, initialValues, previousValues, addresses, types, states, relocations,
            labels, freezeModes, freezePaused,
        )
    }

    internal fun parse(
        ids: LongArray,
        values: Array<String>,
        initialValues: Array<String>,
        previousValues: Array<String>,
        addresses: Array<String>,
        types: IntArray,
        states: IntArray,
        relocations: IntArray,
        labels: Array<String>,
        freezeModes: IntArray,
        freezePaused: BooleanArray,
    ): List<MemoryWatchRow> {
        if (listOf(
                values.size, initialValues.size, previousValues.size, addresses.size, types.size,
                states.size, relocations.size, labels.size, freezeModes.size, freezePaused.size,
            ).any { it != ids.size }) {
            return emptyList()
        }
        return ids.indices.mapNotNull { index ->
            val type = types[index]
            if (ids[index] <= 0L || values[index] == null || initialValues[index] == null ||
                previousValues[index] == null || addresses[index] == null || labels[index] == null ||
                !MemoryEngineContract.isCandidateType(type)) {
                null
            } else {
                MemoryWatchRow(
                    id = ids[index],
                    type = type,
                    state = states[index],
                    relocations = relocations[index],
                    valueText = values[index],
                    initialValueText = initialValues[index],
                    previousValueText = previousValues[index],
                    addressText = addresses[index],
                    label = labels[index],
                    freezeMode = freezeModes[index],
                    freezePaused = freezePaused[index],
                )
            }
        }.takeIf { it.size == ids.size }.orEmpty()
    }
}

internal fun newSearchPredicate(selectedPredicate: Int): Int =
    selectedPredicate.takeIf { it <= MemoryEngineContract.PREDICATE_BETWEEN }
        ?: MemoryEngineContract.PREDICATE_EQUAL

internal data class MemoryEditorUiState(
    val bubbleEnabled: Boolean = false,
    val visible: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val supported: Boolean = false,
    val writeSupported: Boolean = false,
    val runtimeToken: Long = 0,
    val busy: Boolean = false,
    val searching: Boolean = false,
    val scanBytesScanned: Long = 0L,
    val scanBytesTotal: Long = 0L,
    val resultCount: Long = 0,
    val pageOffset: Int = 0,
    val results: List<MemoryResultRow> = emptyList(),
    val watches: List<MemoryWatchRow> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val watchTab: Boolean = false,
    val message: String? = null,
    val searchMode: MemorySearchMode = MemorySearchMode.KNOWN,
    val sessionStage: MemorySessionStage = MemorySessionStage.EMPTY,
    val requestedType: Int = MemoryEngineContract.TYPE_AUTO,
    val searchScope: Int = MemoryEngineContract.SCOPE_JAVA_FAST,
    val canUndo: Boolean = false,
    val inspectorLoading: Boolean = false,
    val inspector: MemoryInspectorSnapshot? = null,
)

internal interface MemoryEditorActions {
    fun close()
    fun refreshCapabilities()
    fun startSearch(value: String, secondValue: String, type: Int, predicate: Int, unknown: Boolean, scope: Int)
    fun nextScan(value: String, secondValue: String, predicate: Int, compare: Int)
    fun groupSearch(types: IntArray, values: Array<String>, distance: Int, scope: Int)
    fun undo()
    fun refresh()
    fun setWatchTab(watch: Boolean)
    fun toggleSelection(id: Long)
    fun selectVisible()
    fun invertVisible()
    fun clearSelection()
    fun editSelected(value: String, type: Int)
    fun removeSelected(keep: Boolean)
    fun watchSelected(add: Boolean)
    fun labelWatch(id: Long, label: String)
    fun freezeSelected(mode: Int, first: String, second: String)
    fun clearFreezeSelected()
    fun copySelected(addresses: Boolean)
    fun previousPage()
    fun nextPage()
    fun cancel()
    fun startOver() = Unit
    fun inspectCandidate(candidateId: Long, radius: Int = MemoryEngineContract.DEFAULT_INSPECT_RADIUS) = Unit
    fun editInspectorValue(
        anchorCandidateId: Long,
        relativeOffset: Int,
        type: Int,
        expectedBits: Long,
        replacementValue: String,
    ) = Unit
    fun closeInspector() = Unit
    fun startNearbySearch(
        anchorCandidateId: Long,
        radius: Int,
        type: Int,
        predicate: Int,
        value: String,
        secondValue: String,
    ) = Unit
}

/**
 * Legacy typed group parser retained for unit tests and compatibility with saved/debug input.
 * The production UI now uses a visual group builder instead of requiring this mini-language.
 */
internal fun parseGroup(input: String): Pair<IntArray, Array<String>>? {
    val parts = input.split(',').map(String::trim).filter(String::isNotEmpty)
    if (parts.size !in 2..MemoryEngineContract.MAX_GROUP_VALUES) return null
    val types = IntArray(parts.size)
    val values = Array(parts.size) { "" }
    for ((index, part) in parts.withIndex()) {
        val separator = part.indexOf(':')
        if (separator <= 0 || separator == part.lastIndex) return null
        types[index] = when (part.substring(0, separator).trim().lowercase()) {
            "byte", "i8" -> MemoryEngineContract.TYPE_BYTE
            "short", "i16", "word" -> MemoryEngineContract.TYPE_SHORT
            "char", "u16", "uword", "word unsigned" -> MemoryEngineContract.TYPE_CHAR
            "int", "i32", "dword" -> MemoryEngineContract.TYPE_INT
            "long", "i64", "qword" -> MemoryEngineContract.TYPE_LONG
            "float", "f32" -> MemoryEngineContract.TYPE_FLOAT
            "double", "f64" -> MemoryEngineContract.TYPE_DOUBLE
            else -> return null
        }
        values[index] = part.substring(separator + 1).trim()
    }
    return types to values
}
