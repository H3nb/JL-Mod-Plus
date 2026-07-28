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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    REFINE,
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
    val status: MemoryEditorRuntime.OperationStatus = MemoryEditorRuntime.OperationStatus.SUCCESS,
)

internal data class MemoryEditorSnapshot(
    val gameGeneration: Long = 0,
    val searchSessionId: Long = 0,
    val candidates: Int = 0,
    val frozen: Int = 0,
    val saved: Int = 0,
    val candidateBytes: Long = 0,
    val candidateByteBudget: Long = 0,
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
    val canContinueCollection: Boolean
        get() = !collecting && !limitReached && when (mode) {
            MemoryEditorRuntime.SearchMode.EXACT,
            MemoryEditorRuntime.SearchMode.NOT_EQUAL,
            MemoryEditorRuntime.SearchMode.LESS_THAN,
            MemoryEditorRuntime.SearchMode.GREATER_THAN,
            MemoryEditorRuntime.SearchMode.UNKNOWN,
            MemoryEditorRuntime.SearchMode.RANGE,
            -> true
            else -> false
        }

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

internal data class MemoryOperationProgress(
    val searchSessionId: Long,
    val operationId: Long,
    val completed: Int,
    val total: Int,
) {
    val fraction: Float
        get() = if (total == 0) 0f else completed.toFloat() / total.toFloat()
}

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
    val layoutTransparency: Float = 0f,
    val progress: MemoryOperationProgress? = null,
    val preparingSearch: Boolean = false,
)

internal class MemoryEditorViewModel : ViewModel() {
    private val operationRunning = AtomicBoolean()
    private val existingSessionLoadStarted = AtomicBoolean()
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

    fun setLayoutTransparency(transparency: Float) {
        _state.update {
            it.copy(layoutTransparency = transparency.coerceIn(0f, MAX_LAYOUT_TRANSPARENCY))
        }
    }

    fun startSearch(onPrepared: () -> Unit) {
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
            ).copy(preparingSearch = true)
            viewModelScope.launch {
                delay(SEARCH_PREPARATION_DELAY_MS)
                _state.update { it.copy(preparingSearch = false) }
                onPrepared()
            }
        }.onFailure(::showError)
    }

    fun loadExistingSession() {
        val current = _state.value
        val sessionId = current.snapshot.searchSessionId
        if (sessionId == 0L || current.preparingSearch ||
            !existingSessionLoadStarted.compareAndSet(false, true)
        ) {
            return
        }
        runOperation(operation = {
            if (current.phase == MemoryEditorPhase.COLLECTING) {
                MemoryEditorRuntime.finishCollection(sessionId)
            }
            LoadedResults(
                snapshot = MemoryEditorRuntime.snapshot(),
                candidates = MemoryEditorRuntime.results(0, PAGE_SIZE),
                savedCandidates = MemoryEditorRuntime.savedResults(0, PAGE_SIZE),
            )
        }, onSuccess = { loaded ->
            if (loaded.snapshot.searchSessionId != sessionId) {
                existingSessionLoadStarted.set(false)
                return@runOperation
            }
            _state.update {
                it.copy(
                    phase = phaseOf(loaded.snapshot),
                    snapshot = loaded.snapshot.toUi(),
                    candidates = loaded.candidates.map { candidate -> candidate.toUi() },
                    savedCandidates = loaded.savedCandidates.map { candidate -> candidate.toUi() },
                    selectedIds = emptySet(),
                    hasMore = loaded.candidates.size < loaded.snapshot.candidates,
                    busy = false,
                    error = null,
                )
            }
        }, onFailure = { error ->
            existingSessionLoadStarted.set(false)
            showError(error)
        })
    }

    fun continueCollection(onStarted: () -> Unit) {
        val current = _state.value
        if (!current.snapshot.canContinueCollection) {
            return
        }
        if (MemoryEditorRuntime.resumeCollection(current.snapshot.searchSessionId)) {
            _state.value = stateFromRuntime(
                previous = current,
                phase = MemoryEditorPhase.COLLECTING,
            )
            onStarted()
        } else {
            showError(IllegalStateException("Search session can no longer collect values"))
        }
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
            val result = MemoryEditorRuntime.refine(
                current.snapshot.searchSessionId,
                current.refineMode,
                current.firstValue,
                current.secondValue,
            )
            LoadedResults(
                snapshot = MemoryEditorRuntime.snapshot(),
                candidates = MemoryEditorRuntime.results(0, PAGE_SIZE),
                savedCandidates = MemoryEditorRuntime.savedResults(0, PAGE_SIZE),
                operation = result,
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
                    operation = loaded.operation
                        ?.takeIf { result ->
                            result.status != MemoryEditorRuntime.OperationStatus.SUCCESS
                        }
                        ?.toSummary(OperationKind.REFINE),
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
        selectedOperation(OperationKind.EDIT) { sessionId, ids ->
            MemoryEditorRuntime.editCandidates(sessionId, ids, replacement)
        }
    }

    fun freezeSelected(replacement: String) {
        selectedOperation(OperationKind.FREEZE) { sessionId, ids ->
            MemoryEditorRuntime.freezeCandidates(sessionId, ids, replacement)
        }
    }

    fun unfreezeSelected() {
        selectedOperation(OperationKind.UNFREEZE) { sessionId, ids ->
            MemoryEditorRuntime.clearFreeze(sessionId, ids)
        }
    }

    fun unfreezeSavedSelected() {
        selectedSavedOperation(OperationKind.UNFREEZE) { sessionId, ids ->
            MemoryEditorRuntime.clearFreeze(sessionId, ids)
        }
    }

    fun deleteSavedSelected() {
        selectedSavedOperation(OperationKind.DELETE_SAVED) { sessionId, ids ->
            MemoryEditorRuntime.deleteSaved(sessionId, ids)
        }
    }

    fun cancelOperation() {
        val progress = _state.value.progress ?: return
        MemoryEditorRuntime.cancelOperation(progress.searchSessionId, progress.operationId)
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
        MemoryEditorRuntime.resetSearch()
        _state.value = MemoryEditorUiState(
            pauseEnabled = _state.value.pauseEnabled,
            layoutTransparency = _state.value.layoutTransparency,
        )
    }

    fun clearMessage() {
        _state.update { it.copy(error = null, operation = null) }
    }

    private fun selectedOperation(
        kind: OperationKind,
        operation: (Long, LongArray) -> MemoryEditorRuntime.OperationResult,
    ) {
        val ids = _state.value.selectedIds.toLongArray()
        if (ids.isEmpty()) {
            return
        }
        runSelectedOperation(kind, ids, operation)
    }

    private fun selectedSavedOperation(
        kind: OperationKind,
        operation: (Long, LongArray) -> MemoryEditorRuntime.OperationResult,
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
        operation: (Long, LongArray) -> MemoryEditorRuntime.OperationResult,
    ) {
        val sessionId = _state.value.snapshot.searchSessionId
        runOperation(operation = {
            val result = operation(sessionId, ids)
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
                        status = payload.result.status,
                    ),
                )
            }
        })
    }

    private fun <T> runOperation(
        operation: suspend () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit = ::showError,
    ) {
        if (!operationRunning.compareAndSet(false, true)) {
            return
        }
        _state.update { it.copy(busy = true, error = null, operation = null) }
        viewModelScope.launch {
            val progressPoller = launch {
                while (operationRunning.get()) {
                    val progress = MemoryEditorRuntime.operationProgress()
                    _state.update {
                        it.copy(
                            progress = progress.takeIf { current -> current.cancellable }?.let {
                                current ->
                                MemoryOperationProgress(
                                    searchSessionId = current.searchSessionId,
                                    operationId = current.operationId,
                                    completed = current.completed,
                                    total = current.total,
                                )
                            },
                        )
                    }
                    delay(PROGRESS_POLL_INTERVAL_MS)
                }
            }
            try {
                onSuccess(withContext(Dispatchers.Default) { operation() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onFailure(error)
            } finally {
                operationRunning.set(false)
                progressPoller.cancel()
                _state.update { it.copy(busy = false, progress = null) }
            }
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
        val operation: MemoryEditorRuntime.OperationResult? = null,
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
        const val PROGRESS_POLL_INTERVAL_MS = 100L
        const val SEARCH_PREPARATION_DELAY_MS = 450L
        const val MAX_LAYOUT_TRANSPARENCY = 0.8f

        fun stateFromRuntime(
            previous: MemoryEditorUiState? = null,
            phase: MemoryEditorPhase? = null,
        ): MemoryEditorUiState {
            val snapshot = MemoryEditorRuntime.snapshot()
            val actualPhase = phase ?: phaseOf(snapshot)
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
                candidates = emptyList(),
                savedCandidates = emptyList(),
                selectedIds = emptySet(),
                hasMore = snapshot.candidates > 0,
                pauseEnabled = previous?.pauseEnabled ?: false,
                layoutTransparency = previous?.layoutTransparency ?: 0f,
            )
        }

        fun phaseOf(snapshot: MemoryEditorRuntime.Snapshot): MemoryEditorPhase = when {
            snapshot.kind == null -> MemoryEditorPhase.SETUP
            snapshot.collecting -> MemoryEditorPhase.COLLECTING
            else -> MemoryEditorPhase.RESULTS
        }

        fun MemoryEditorRuntime.Snapshot.toUi() = MemoryEditorSnapshot(
            gameGeneration = gameGeneration,
            searchSessionId = searchSessionId,
            candidates = candidates,
            frozen = frozen,
            saved = saved,
            candidateBytes = candidateBytes,
            candidateByteBudget = candidateByteBudget,
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

        fun MemoryEditorRuntime.OperationResult.toSummary(kind: OperationKind) = OperationSummary(
            kind = kind,
            requested = requested,
            succeeded = succeeded,
            status = status,
        )
    }
}
