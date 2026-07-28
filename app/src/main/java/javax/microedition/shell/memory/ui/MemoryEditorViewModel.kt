/*
 * Copyright 2026 H3NB
 *
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

package javax.microedition.shell.memory.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.shell.memory.MemoryEditorRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MemoryEditorPhase {
    SETUP,
    COLLECTING,
    RESULTS,
}

internal enum class OperationKind {
    EDIT,
    FREEZE,
    UNFREEZE,
    DELETE_SAVED,
    UNDO,
}

internal data class OperationSummary(
    val kind: OperationKind,
    val requested: Int,
    val succeeded: Int,
)

internal data class MemoryEditorSnapshot(
    val candidates: Int = 0,
    val frozen: Int = 0,
    val saved: Int = 0,
    val kind: MemoryEditorRuntime.ValueKind? = null,
    val mode: MemoryEditorRuntime.SearchMode? = null,
    val limitReached: Boolean = false,
    val collecting: Boolean = false,
    val undoAvailable: Boolean = false,
    val intObservations: Long = 0,
    val longObservations: Long = 0,
    val floatObservations: Long = 0,
    val doubleObservations: Long = 0,
    val fieldObservations: Long = 0,
    val arrayObservations: Long = 0,
    val readObservations: Long = 0,
    val writeObservations: Long = 0,
) {
    val selectedObservations: Long
        get() = when (kind) {
            MemoryEditorRuntime.ValueKind.INT -> intObservations
            MemoryEditorRuntime.ValueKind.LONG -> longObservations
            MemoryEditorRuntime.ValueKind.FLOAT -> floatObservations
            MemoryEditorRuntime.ValueKind.DOUBLE -> doubleObservations
            null -> 0
        }

    val totalObservations: Long
        get() = intObservations + longObservations + floatObservations + doubleObservations
}

internal data class MemoryCandidate(
    val id: Long,
    val value: String,
    val storageType: String,
    val location: String,
    val frozen: Boolean,
    val saved: Boolean = false,
    val editable: Boolean,
)

internal data class MemoryEditorUiState(
    val phase: MemoryEditorPhase = MemoryEditorPhase.SETUP,
    val snapshot: MemoryEditorSnapshot = MemoryEditorSnapshot(),
    val kind: MemoryEditorRuntime.ValueKind = MemoryEditorRuntime.ValueKind.INT,
    val initialMode: MemoryEditorRuntime.SearchMode = MemoryEditorRuntime.SearchMode.EXACT,
    val refineMode: MemoryEditorRuntime.SearchMode = MemoryEditorRuntime.SearchMode.EXACT,
    val firstValue: String = "",
    val secondValue: String = "",
    val candidates: List<MemoryCandidate> = emptyList(),
    val savedCandidates: List<MemoryCandidate> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val hasMore: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val operation: OperationSummary? = null,
    val pauseEnabled: Boolean = false,
)

internal class MemoryEditorViewModel : ViewModel() {
    private val operationRunning = AtomicBoolean()
    private val _state = MutableStateFlow(stateFromRuntime())
    val state: StateFlow<MemoryEditorUiState> = _state.asStateFlow()

    fun setKind(kind: MemoryEditorRuntime.ValueKind) {
        _state.update { it.copy(kind = kind, error = null) }
    }

    fun setInitialMode(mode: MemoryEditorRuntime.SearchMode) {
        _state.update { it.copy(initialMode = mode, error = null) }
    }

    fun setRefineMode(mode: MemoryEditorRuntime.SearchMode) {
        _state.update { it.copy(refineMode = mode, error = null) }
    }

    fun setFirstValue(value: String) {
        _state.update { it.copy(firstValue = value, error = null) }
    }

    fun setSecondValue(value: String) {
        _state.update { it.copy(secondValue = value, error = null) }
    }

    fun setPauseEnabled(enabled: Boolean) {
        _state.update { it.copy(pauseEnabled = enabled) }
    }

    fun startSearch(onStarted: () -> Unit) {
        val current = _state.value
        runCatching {
            MemoryEditorRuntime.begin(
                current.kind,
                current.initialMode,
                current.firstValue,
                current.secondValue,
            )
        }.onSuccess {
            _state.value = stateFromRuntime(
                previous = current,
                phase = MemoryEditorPhase.COLLECTING,
            )
            onStarted()
        }.onFailure(::showError)
    }

    fun finishCollection() {
        MemoryEditorRuntime.finishCollection()
        _state.value = stateFromRuntime(
            previous = _state.value,
            phase = MemoryEditorPhase.RESULTS,
        )
    }

    fun refreshResults() {
        runOperation(operation = {
            LoadedResults(
                snapshot = MemoryEditorRuntime.snapshot(),
                candidates = MemoryEditorRuntime.results(0, PAGE_SIZE),
                savedCandidates = MemoryEditorRuntime.savedResults(0, PAGE_SIZE),
            )
        }, onSuccess = { loaded ->
            _state.update { current ->
                current.copy(
                    phase = phaseOf(loaded.snapshot),
                    snapshot = loaded.snapshot.toUi(),
                    kind = loaded.snapshot.kind ?: current.kind,
                    candidates = loaded.candidates.map { it.toUi() },
                    savedCandidates = loaded.savedCandidates.map { it.toUi() },
                    selectedIds = current.selectedIds.intersect(
                        loaded.candidates.mapTo(mutableSetOf()) { it.id },
                    ),
                    hasMore = loaded.candidates.size < loaded.snapshot.candidates,
                    busy = false,
                    error = null,
                )
            }
        })
    }

    fun loadMore() {
        val offset = _state.value.candidates.size
        runOperation(operation = {
            LoadedResults(
                snapshot = MemoryEditorRuntime.snapshot(),
                candidates = MemoryEditorRuntime.results(offset, PAGE_SIZE),
                savedCandidates = MemoryEditorRuntime.savedResults(0, PAGE_SIZE),
            )
        }, onSuccess = { loaded ->
            _state.update { current ->
                    val combined = (current.candidates + loaded.candidates.map { it.toUi() })
                    .distinctBy { it.id }
                current.copy(
                    snapshot = loaded.snapshot.toUi(),
                    candidates = combined,
                    savedCandidates = loaded.savedCandidates.map { it.toUi() },
                    hasMore = combined.size < loaded.snapshot.candidates,
                    busy = false,
                    error = null,
                )
            }
        })
    }

    fun refine() {
        val current = _state.value
        runOperation(operation = {
            MemoryEditorRuntime.refine(
                current.refineMode,
                current.firstValue,
                current.secondValue,
            )
            LoadedResults(
                snapshot = MemoryEditorRuntime.snapshot(),
                candidates = MemoryEditorRuntime.results(0, PAGE_SIZE),
                savedCandidates = MemoryEditorRuntime.savedResults(0, PAGE_SIZE),
            )
        }, onSuccess = { loaded ->
            _state.update {
                it.copy(
                    phase = MemoryEditorPhase.RESULTS,
                    snapshot = loaded.snapshot.toUi(),
                    candidates = loaded.candidates.map { it.toUi() },
                    savedCandidates = loaded.savedCandidates.map { it.toUi() },
                    selectedIds = emptySet(),
                    hasMore = loaded.candidates.size < loaded.snapshot.candidates,
                    busy = false,
                    error = null,
                    operation = null,
                )
            }
        })
    }

    fun toggleSelection(id: Long) {
        _state.update { current ->
            val next = current.selectedIds.toMutableSet()
            if (!next.add(id)) {
                next.remove(id)
            }
            current.copy(selectedIds = next)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    fun toggleAllLoaded() {
        _state.update { current ->
            val editable = current.candidates.filter { it.editable }.mapTo(mutableSetOf()) { it.id }
            val allSelected = editable.isNotEmpty() && current.selectedIds.containsAll(editable)
            current.copy(selectedIds = if (allSelected) emptySet() else editable)
        }
    }

    fun editSelected(replacement: String) {
        selectedOperation(OperationKind.EDIT) { ids ->
            MemoryEditorRuntime.editCandidates(ids, replacement)
        }
    }

    fun freezeSelected(replacement: String) {
        selectedOperation(OperationKind.FREEZE) { ids ->
            MemoryEditorRuntime.freezeCandidates(ids, replacement)
        }
    }

    fun unfreezeSelected() {
        selectedOperation(OperationKind.UNFREEZE) { ids ->
            MemoryEditorRuntime.clearFreeze(ids)
        }
    }

    fun unfreezeSavedSelected() {
        selectedSavedOperation(OperationKind.UNFREEZE) { ids ->
            MemoryEditorRuntime.clearFreeze(ids)
        }
    }

    fun deleteSavedSelected() {
        selectedSavedOperation(OperationKind.DELETE_SAVED) { ids ->
            MemoryEditorRuntime.deleteSaved(ids)
        }
    }

    fun undo() {
        runOperation(operation = {
            val succeeded = MemoryEditorRuntime.undo()
            val snapshot = MemoryEditorRuntime.snapshot()
            val candidates = MemoryEditorRuntime.results(0, PAGE_SIZE)
            val savedCandidates = MemoryEditorRuntime.savedResults(0, PAGE_SIZE)
            UndoPayload(succeeded, snapshot, candidates, savedCandidates)
        }, onSuccess = { payload ->
            _state.update {
                it.copy(
                    snapshot = payload.snapshot.toUi(),
                    candidates = payload.candidates.map { candidate -> candidate.toUi() },
                    savedCandidates = payload.savedCandidates.map { candidate -> candidate.toUi() },
                    selectedIds = it.selectedIds.intersect(
                        payload.candidates.mapTo(mutableSetOf()) { candidate -> candidate.id },
                    ),
                    hasMore = payload.candidates.size < payload.snapshot.candidates,
                    busy = false,
                    error = null,
                    operation = OperationSummary(
                        kind = OperationKind.UNDO,
                        requested = 1,
                        succeeded = if (payload.succeeded) 1 else 0,
                    ),
                )
            }
        })
    }

    fun reset() {
        MemoryEditorRuntime.clear()
        _state.value = MemoryEditorUiState(pauseEnabled = _state.value.pauseEnabled)
    }

    fun clearMessage() {
        _state.update { it.copy(error = null, operation = null) }
    }

    private fun selectedOperation(
        kind: OperationKind,
        operation: (LongArray) -> MemoryEditorRuntime.OperationResult,
    ) {
        val ids = _state.value.selectedIds.toLongArray()
        if (ids.isEmpty()) {
            return
        }
        runSelectedOperation(kind, ids, operation)
    }

    private fun selectedSavedOperation(
        kind: OperationKind,
        operation: (LongArray) -> MemoryEditorRuntime.OperationResult,
    ) {
        val savedIds = _state.value.savedCandidates.mapTo(mutableSetOf()) { it.id }
        val ids = _state.value.selectedIds.intersect(savedIds).toLongArray()
        if (ids.isEmpty()) {
            return
        }
        runSelectedOperation(kind, ids, operation)
    }

    private fun runSelectedOperation(
        kind: OperationKind,
        ids: LongArray,
        operation: (LongArray) -> MemoryEditorRuntime.OperationResult,
    ) {
        runOperation(operation = {
            val result = operation(ids)
            val snapshot = MemoryEditorRuntime.snapshot()
            val candidates = MemoryEditorRuntime.results(0, PAGE_SIZE)
            val savedCandidates = MemoryEditorRuntime.savedResults(0, PAGE_SIZE)
            OperationPayload(result, snapshot, candidates, savedCandidates)
        }, onSuccess = { payload ->
            _state.update {
                it.copy(
                    snapshot = payload.snapshot.toUi(),
                    candidates = payload.candidates.map { candidate -> candidate.toUi() },
                    savedCandidates = payload.savedCandidates.map { candidate -> candidate.toUi() },
                    selectedIds = it.selectedIds.intersect(
                        (payload.candidates + payload.savedCandidates)
                            .mapTo(mutableSetOf()) { candidate -> candidate.id },
                    ),
                    hasMore = payload.candidates.size < payload.snapshot.candidates,
                    busy = false,
                    error = null,
                    operation = OperationSummary(
                        kind = kind,
                        requested = payload.result.requested,
                        succeeded = payload.result.succeeded,
                    ),
                )
            }
        })
    }

    private fun <T> runOperation(
        operation: suspend () -> T,
        onSuccess: (T) -> Unit,
    ) {
        if (!operationRunning.compareAndSet(false, true)) {
            return
        }
        _state.update { it.copy(busy = true, error = null, operation = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) { operation() }
            }.onSuccess(onSuccess)
                .onFailure(::showError)
            operationRunning.set(false)
            _state.update { it.copy(busy = false) }
        }
    }

    private fun showError(error: Throwable) {
        _state.update {
            it.copy(
                busy = false,
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private data class LoadedResults(
        val snapshot: MemoryEditorRuntime.Snapshot,
        val candidates: List<MemoryEditorRuntime.CandidateView>,
        val savedCandidates: List<MemoryEditorRuntime.CandidateView>,
    )

    private data class OperationPayload(
        val result: MemoryEditorRuntime.OperationResult,
        val snapshot: MemoryEditorRuntime.Snapshot,
        val candidates: List<MemoryEditorRuntime.CandidateView>,
        val savedCandidates: List<MemoryEditorRuntime.CandidateView>,
    )

    private data class UndoPayload(
        val succeeded: Boolean,
        val snapshot: MemoryEditorRuntime.Snapshot,
        val candidates: List<MemoryEditorRuntime.CandidateView>,
        val savedCandidates: List<MemoryEditorRuntime.CandidateView>,
    )

    private companion object {
        const val PAGE_SIZE = 200

        fun stateFromRuntime(
            previous: MemoryEditorUiState? = null,
            phase: MemoryEditorPhase? = null,
        ): MemoryEditorUiState {
            val snapshot = MemoryEditorRuntime.snapshot()
            val actualPhase = phase ?: phaseOf(snapshot)
            val candidates = if (actualPhase == MemoryEditorPhase.RESULTS) {
                MemoryEditorRuntime.results(0, PAGE_SIZE)
            } else {
                emptyList()
            }
            val savedCandidates = MemoryEditorRuntime.savedResults(0, PAGE_SIZE)
            return MemoryEditorUiState(
                phase = actualPhase,
                snapshot = snapshot.toUi(),
                kind = snapshot.kind ?: previous?.kind ?: MemoryEditorRuntime.ValueKind.INT,
                initialMode = if (snapshot.kind == null) {
                    previous?.initialMode ?: MemoryEditorRuntime.SearchMode.EXACT
                } else {
                    snapshot.mode.takeIf {
                        it == MemoryEditorRuntime.SearchMode.EXACT ||
                            it == MemoryEditorRuntime.SearchMode.NOT_EQUAL ||
                            it == MemoryEditorRuntime.SearchMode.LESS_THAN ||
                            it == MemoryEditorRuntime.SearchMode.GREATER_THAN ||
                            it == MemoryEditorRuntime.SearchMode.UNKNOWN ||
                            it == MemoryEditorRuntime.SearchMode.RANGE
                    } ?: MemoryEditorRuntime.SearchMode.EXACT
                },
                refineMode = previous?.refineMode ?: MemoryEditorRuntime.SearchMode.EXACT,
                firstValue = previous?.firstValue.orEmpty(),
                secondValue = previous?.secondValue.orEmpty(),
                candidates = candidates.map { it.toUi() },
                savedCandidates = savedCandidates.map { it.toUi() },
                selectedIds = previous?.selectedIds.orEmpty().intersect(
                    candidates.mapTo(mutableSetOf()) { it.id },
                ),
                hasMore = candidates.size < snapshot.candidates,
                pauseEnabled = previous?.pauseEnabled ?: false,
            )
        }

        fun phaseOf(snapshot: MemoryEditorRuntime.Snapshot): MemoryEditorPhase = when {
            snapshot.kind == null -> MemoryEditorPhase.SETUP
            snapshot.collecting -> MemoryEditorPhase.COLLECTING
            else -> MemoryEditorPhase.RESULTS
        }

        fun MemoryEditorRuntime.Snapshot.toUi() = MemoryEditorSnapshot(
            candidates = candidates,
            frozen = frozen,
            saved = saved,
            kind = kind,
            mode = mode,
            limitReached = limitReached,
            collecting = collecting,
            undoAvailable = undoAvailable,
            intObservations = intObservations,
            longObservations = longObservations,
            floatObservations = floatObservations,
            doubleObservations = doubleObservations,
            fieldObservations = fieldObservations,
            arrayObservations = arrayObservations,
            readObservations = readObservations,
            writeObservations = writeObservations,
        )

        fun MemoryEditorRuntime.CandidateView.toUi() = MemoryCandidate(
            id = id,
            value = value,
            storageType = storageType,
            location = location,
            frozen = frozen,
            saved = saved,
            editable = editable,
        )
    }
}
