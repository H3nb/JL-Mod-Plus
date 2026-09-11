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

internal fun memorySessionHasActiveSearch(stage: MemorySessionStage): Boolean =
    stage != MemorySessionStage.EMPTY

/** Predicates that describe a change from the captured Unknown-search baseline. */
internal fun memoryUnknownSearchPredicates(): IntArray = intArrayOf(
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

internal fun memoryUnknownPredicateOrDefault(predicate: Int): Int =
    predicate.takeIf {
        it in MemoryEngineContract.PREDICATE_CHANGED..
            MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE
    } ?: MemoryEngineContract.PREDICATE_CHANGED

internal fun memoryRelativePredicateNeedsValue(predicate: Int): Boolean = when (predicate) {
    MemoryEngineContract.PREDICATE_CHANGED,
    MemoryEngineContract.PREDICATE_UNCHANGED,
    MemoryEngineContract.PREDICATE_INCREASED,
    MemoryEngineContract.PREDICATE_DECREASED -> false
    else -> true
}

internal data class MemoryNextScanInput(
    val first: String,
    val second: String,
)

/** Sanitizes the UI payload before it crosses the controller-to-engine boundary. */
internal fun memoryNextScanInputForPredicate(
    predicate: Int,
    first: String,
    second: String,
): MemoryNextScanInput {
    val firstValue = first.trim()
    val secondValue = second.trim()
    return when (predicate) {
        MemoryEngineContract.PREDICATE_CHANGED,
        MemoryEngineContract.PREDICATE_UNCHANGED,
        MemoryEngineContract.PREDICATE_INCREASED,
        MemoryEngineContract.PREDICATE_DECREASED -> MemoryNextScanInput("", "")
        MemoryEngineContract.PREDICATE_BETWEEN,
        MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE,
        MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE ->
            MemoryNextScanInput(firstValue, secondValue)
        else -> MemoryNextScanInput(firstValue, "")
    }
}

internal fun memoryUnknownPredicateForRuntime(predicate: Int, newRuntime: Boolean): Int =
    if (newRuntime) MemoryEngineContract.PREDICATE_CHANGED
    else memoryUnknownPredicateOrDefault(predicate)

/**
 * Process-local preferences for the currently running MIDlet session.
 *
 * The editor controller is recreated with the Activity, but the MIDlet runtime remains the
 * owner of this short-lived preference. Keeping it here avoids persisting a session choice
 * beyond the runtime while allowing a recreated controller to restore it.
 */
internal object MemoryEditorRuntimePreferences {
    private var runtimeToken: Long = 0L
    private var unknownPredicate: Int = MemoryEngineContract.PREDICATE_CHANGED

    @Synchronized
    fun unknownPredicate(token: Long): Int {
        if (token == 0L) return MemoryEngineContract.PREDICATE_CHANGED
        if (token != runtimeToken) {
            runtimeToken = token
            unknownPredicate = MemoryEngineContract.PREDICATE_CHANGED
        }
        return memoryUnknownPredicateOrDefault(unknownPredicate)
    }

    @Synchronized
    fun setUnknownPredicate(token: Long, predicate: Int) {
        if (token == 0L || memoryUnknownPredicateOrDefault(predicate) != predicate) return
        if (token != runtimeToken) {
            runtimeToken = token
            unknownPredicate = MemoryEngineContract.PREDICATE_CHANGED
        }
        unknownPredicate = predicate
    }
}

/** One engine-formatted Watch row used by the :memory_engine presentation. */
internal data class MemoryWatchRow(
    val id: Long,
    val type: Int,
    val state: Int,
    val relocations: Int,
    val valueText: String,
    val initialValueText: String,
    val previousValueText: String,
    val locationText: String,
    val label: String = "",
    val freezeMode: Int = -1,
    val freezePaused: Boolean = false,
)

/** One engine-formatted logical address result used by the :memory_engine presentation. */
internal data class MemoryResultRow(
    val id: Long,
    val valueText: String,
    val locationText: String,
    val aliasMask: Int,
    val primaryType: Int,
    val state: Int,
    val relocations: Int,
) {
    val aliasTypes: List<Int>
        get() = (MemoryEngineContract.TYPE_BYTE..MemoryEngineContract.TYPE_DOUBLE)
            .filter { aliasMask and (1 shl it) != 0 }
}

/** Immutable row provenance captured when an edit dialog is opened. */
internal data class MemoryEditTarget(
    val id: Long,
    val type: Int,
    val valueText: String,
    val aliasTypes: List<Int> = emptyList(),
    val watch: Boolean = false,
)

/** Bounded logical snapshot resolved from a verified Managed Java candidate. */
internal data class MemoryInspectorSnapshot(
    val candidateId: Long,
    val type: Int,
    val label: String,
    val logicalRows: List<MemoryInspectorLogicalRow>,
    val expectedRevision: Long = 0L,
    val provenance: String = "",
    val watchAnchor: Boolean = false,
)

internal data class MemoryInspectorLogicalRow(
    val id: Long,
    val relativeOffset: Int,
    val type: Int,
    val state: Int,
    val valueText: String,
    val initialValueText: String,
    val previousValueText: String,
    val label: String,
    val expectedBits: Long,
    val editable: Boolean = true,
)

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
                    locationText = addresses[index],
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
            if (ids[index] <= 0L || !MemoryEngineContract.isCandidateType(type)) {
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
                    locationText = addresses[index],
                    label = labels[index],
                    freezeMode = freezeModes[index],
                    freezePaused = freezePaused[index],
                )
            }
        }.takeIf { it.size == ids.size }.orEmpty()
    }
}

internal data class MemoryEditorUiState(
    val visible: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val supported: Boolean = false,
    val writeSupported: Boolean = false,
    val revision: Long = 0L,
    val baselineCount: Long = 0L,
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
    val messageIsError: Boolean = false,
    val searchMode: MemorySearchMode = MemorySearchMode.KNOWN,
    val sessionStage: MemorySessionStage = MemorySessionStage.EMPTY,
    val requestedType: Int = MemoryEngineContract.TYPE_AUTO,
    /** Last relative predicate selected for Unknown search in this MIDlet runtime. */
    val unknownPredicate: Int = MemoryEngineContract.PREDICATE_CHANGED,
    val canUndo: Boolean = false,
    val inspectorLoading: Boolean = false,
    val inspector: MemoryInspectorSnapshot? = null,
)

internal interface MemoryEditorActions {
    fun close()
    fun refreshCapabilities()
    fun startSearch(value: String, secondValue: String, type: Int, predicate: Int, unknown: Boolean)
    fun setUnknownSearchPredicate(predicate: Int) = Unit
    fun nextScan(
        value: String,
        secondValue: String,
        predicate: Int,
        compare: Int,
        type: Int = MemoryEngineContract.TYPE_AUTO,
    )
    fun groupSearch(type: Int, values: Array<String>)
    fun undo()
    fun refresh()
    fun setWatchTab(watch: Boolean)
    fun toggleSelection(id: Long)
    fun selectVisible()
    fun invertVisible()
    fun clearSelection()
    fun editSelected(value: String, type: Int)
    fun editTargets(
        targets: List<MemoryEditTarget>,
        expectedRevision: Long,
        value: String,
        type: Int,
    ) = editSelected(value, type)
    /**
     * Applies an edit and optionally performs the common follow-up actions as one user flow.
     * Implementations that do not support the follow-ups retain the original edit behavior.
     */
    fun editSelectedWithOptions(
        value: String,
        type: Int,
        addToWatch: Boolean,
        freezeAfter: Boolean,
    ) = editSelected(value, type)
    fun editTargetsWithOptions(
        targets: List<MemoryEditTarget>,
        expectedRevision: Long,
        value: String,
        type: Int,
        addToWatch: Boolean,
        freezeAfter: Boolean,
    ) = editTargets(targets, expectedRevision, value, type)
    fun removeSelected(keep: Boolean)
    fun watchSelected(add: Boolean)
    fun labelWatch(id: Long, label: String)
    fun freezeSelected(mode: Int, first: String, second: String)
    fun clearFreezeSelected()
    fun copySelected(locations: Boolean)
    fun previousPage()
    fun nextPage()
    fun cancel()
    fun startOver() = Unit
    fun inspectCandidate(
        candidateId: Long,
        radius: Int = MemoryEngineContract.DEFAULT_INSPECT_RADIUS,
        watchAnchor: Boolean = false,
    ) = Unit
    fun editInspectorValue(
        anchorCandidateId: Long,
        relativeOffset: Int,
        type: Int,
        expectedBits: Long,
        replacementValue: String,
        watchAnchor: Boolean = false,
    ) = Unit
    fun closeInspector() = Unit
}
