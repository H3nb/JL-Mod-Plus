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

internal data class MemoryCandidateRow(
    val id: Long,
    val address: Long,
    val previousAddress: Long,
    val type: Int,
    val state: Int,
    val relocations: Int,
    val initialBits: Long,
    val previousBits: Long,
    val currentBits: Long,
    val label: String = "",
    val freezeMode: Int = -1,
    val freezePaused: Boolean = false,
)

internal data class MemoryAddressGroup(
    val address: Long,
    val aliases: List<MemoryCandidateRow>,
) {
    val primary: MemoryCandidateRow get() = aliases.first()
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

internal fun groupCandidateRows(rows: List<MemoryCandidateRow>): List<MemoryAddressGroup> =
    rows.groupBy { it.address }.map { (address, aliases) -> MemoryAddressGroup(address, aliases) }

internal fun commonTypesForSelection(
    rows: List<MemoryCandidateRow>,
    selected: Set<Long>,
): List<Int> {
    val selectedAddresses = rows.filter { it.id in selected }.mapTo(linkedSetOf()) { it.address }
    return selectedAddresses.map { address ->
        rows.filter { it.address == address }.mapTo(linkedSetOf()) { it.type }
    }.reduceOrNull { common, types -> common.apply { retainAll(types) } }?.toList().orEmpty()
}

internal object MemoryEditorPageParser {
    fun parse(rows: LongArray?): List<MemoryCandidateRow> {
        if (rows == null || rows.isEmpty()) return emptyList()
        val count = rows[0].coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val required = 1L + count.toLong() * MemoryEngineContract.RESULT_PAGE_STRIDE
        if (required != rows.size.toLong()) return emptyList()
        return List(count) { index ->
            val base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE
            MemoryCandidateRow(
                id = rows[base],
                address = rows[base + 1],
                previousAddress = rows[base + 2],
                type = rows[base + 3].toInt(),
                state = rows[base + 4].toInt(),
                relocations = rows[base + 5].toInt(),
                initialBits = rows[base + 6],
                previousBits = rows[base + 7],
                currentBits = rows[base + 8],
            )
        }
    }

    fun value(row: MemoryCandidateRow): String = when (row.type) {
        MemoryEngineContract.TYPE_BYTE -> row.currentBits.toByte().toString()
        MemoryEngineContract.TYPE_SHORT -> row.currentBits.toShort().toString()
        MemoryEngineContract.TYPE_CHAR -> (row.currentBits and 0xffffL).toString()
        MemoryEngineContract.TYPE_INT -> row.currentBits.toInt().toString()
        MemoryEngineContract.TYPE_LONG -> row.currentBits.toString()
        MemoryEngineContract.TYPE_FLOAT -> Float.fromBits(row.currentBits.toInt()).toString()
        MemoryEngineContract.TYPE_DOUBLE -> Double.fromBits(row.currentBits).toString()
        else -> "?"
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
    val results: List<MemoryCandidateRow> = emptyList(),
    val watches: List<MemoryCandidateRow> = emptyList(),
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
