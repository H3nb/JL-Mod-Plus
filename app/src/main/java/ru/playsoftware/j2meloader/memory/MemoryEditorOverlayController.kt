/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.playsoftware.j2meloader.memory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** UI/engine bridge that lives entirely in :memory_editor. */
internal class MemoryEditorOverlayController(
    private val context: Context,
    private val editorView: ComposeView,
    private val bubbleView: ComposeView,
    private val bubbleTouchHandler: (MotionEvent) -> Boolean,
) : MemoryEditorActions {
    private val ipc: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MemoryEditorOverlayIpc").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var state by mutableStateOf(MemoryEditorUiState(bubbleEnabled = true))
    private var service: IMemoryEngineService? = null
    private var bound = false
    private var destroyed = false
    private var refreshInFlight = false
    private var activeOperationId = 0L
    private var operationGeneration = 0L
    private var pendingStage: MemorySessionStage? = null
    private var pendingMode: MemorySearchMode? = null
    private var pendingUndoStage: MemorySessionStage? = null
    private var pendingPreviousStage: MemorySessionStage? = null
    private var pendingResetHistory = false
    private var pendingInspectorRefresh: MemoryInspectorRefresh? = null
    private var pendingEditFollowUp: PendingEditFollowUp? = null
    private val stageHistory = ArrayDeque<MemorySessionStage>()

    private data class PendingEditFollowUp(
        val candidateIds: LongArray,
        val replacementValue: String,
        val addToWatch: Boolean,
        val freezeAfter: Boolean,
    )

    private val callback = object : IMemoryEngineCallback.Stub() {
        override fun onOperationProgress(operationId: Long, scannedBytes: Long, totalBytes: Long) {
            post {
                if (operationId == activeOperationId && state.busy && state.searching && totalBytes > 0L) {
                    state = state.copy(
                        scanBytesScanned = scannedBytes.coerceIn(0L, totalBytes),
                        scanBytesTotal = totalBytes,
                    )
                }
            }
        }

        override fun onOperationFinished(
            operationId: Long,
            resultCode: Int,
            resultCount: Long,
            message: String?,
            passiveRefresh: Boolean,
        ) {
            post {
                if (passiveRefresh) {
                    refreshInFlight = false
                    if (!state.busy && state.visible) {
                        state = state.copy(
                            resultCount = resultCount,
                            message = if (resultCode == MemoryEngineContract.RESULT_OK) {
                                state.message
                            } else {
                                operationMessage(resultCode, message)
                            },
                        )
                        reload()
                    }
                    return@post
                }

                if (activeOperationId != 0L && operationId != activeOperationId) return@post

                val succeeded = resultCode == MemoryEngineContract.RESULT_OK
                val gcSafetyFailure = resultCode == MemoryEngineContract.RESULT_GC_REVALIDATED ||
                    resultCode == MemoryEngineContract.RESULT_GC_RACE ||
                    resultCode == MemoryEngineContract.RESULT_GC_BASELINE_INVALIDATED
                val editFollowUp = if (succeeded) pendingEditFollowUp else null
                pendingEditFollowUp = null
                if (succeeded) {
                    when {
                        pendingUndoStage != null -> {
                            if (stageHistory.isNotEmpty() && stageHistory.peekLast() == pendingUndoStage) {
                                stageHistory.removeLast()
                            }
                        }
                        pendingResetHistory -> stageHistory.clear()
                        pendingPreviousStage != null -> stageHistory.addLast(pendingPreviousStage)
                    }
                    state = state.copy(
                        sessionStage = pendingUndoStage ?: pendingStage ?: state.sessionStage,
                        searchMode = pendingMode ?: state.searchMode,
                        pageOffset = if (pendingStage != null || pendingUndoStage != null) 0 else state.pageOffset,
                        selected = if (pendingStage != null || pendingUndoStage != null) emptySet() else state.selected,
                    )
                }
                clearPendingTransition()

                val inspectorRefresh = if (succeeded) pendingInspectorRefresh else null
                pendingInspectorRefresh = null

                state = state.copy(
                    busy = false,
                    searching = false,
                    scanBytesScanned = 0L,
                    scanBytesTotal = 0L,
                    resultCount = resultCount,
                    inspectorLoading = if (gcSafetyFailure) false else state.inspectorLoading,
                    inspector = if (gcSafetyFailure) null else state.inspector,
                    message = if (succeeded) message?.takeIf(String::isNotBlank)
                    else operationMessage(resultCode, message),
                )
                activeOperationId = 0L
                if (state.visible) reload()
                inspectorRefresh?.let { refresh -> inspectCandidate(refresh.candidateId, refresh.radius) }
                editFollowUp?.let(::scheduleEditFollowUp)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IMemoryEngineService.Stub.asInterface(binder)
            ipc.execute {
                try {
                    service?.registerCallback(callback)
                    val capabilities = service?.capabilities
                    post { applyCapabilities(capabilities) }
                } catch (_: RemoteException) {
                    post { disconnected() }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    fun enable() {
        if (destroyed) return
        state = state.copy(bubbleEnabled = true)
        showBubbleUi()
    }

    fun open() {
        if (destroyed) return
        state = state.copy(visible = true, connecting = service == null, message = null)
        MemoryEditorOverlayState.markVisible(context, true)
        bubbleView.visibility = View.GONE
        showEditorUi()
        editorView.visibility = View.VISIBLE
        editorView.requestFocus()
        if (service == null) connectEngine() else refreshCapabilities()
    }

    override fun close() {
        if (destroyed) return
        state = state.copy(visible = false, selected = emptySet())
        MemoryEditorOverlayState.markVisible(context, false)
        editorView.visibility = View.GONE
        editorView.disposeComposition()
        showBubbleUi()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        MemoryEditorOverlayState.markVisible(context, false)
        editorView.disposeComposition()
        bubbleView.disposeComposition()
        disconnectEngine()
        ipc.shutdownNow()
    }

    private fun showEditorUi() {
        editorView.setContent {
            JLModPlusTheme {
                MemoryEditorOverlayRoot(state = state, actions = this)
            }
        }
    }

    private fun showBubbleUi() {
        bubbleView.setContent {
            JLModPlusTheme {
                MemoryEditorOverlayBubble(
                    state = state,
                    onTouch = bubbleTouchHandler,
                )
            }
        }
        bubbleView.visibility = if (state.visible) View.GONE else View.VISIBLE
    }

    private fun connectEngine() {
        if (bound || destroyed) return
        bound = context.bindService(
            Intent(context, MemoryEngineService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
            state = state.copy(
                connecting = false,
                message = context.getString(R.string.memory_editor_unsupported),
            )
        }
    }

    private fun disconnectEngine() {
        try {
            service?.unregisterCallback(callback)
        } catch (_: RemoteException) {
            // Engine may already be gone.
        }
        service = null
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
    }

    override fun refreshCapabilities() = runIpc {
        val capabilities = service?.capabilities ?: return@runIpc
        post { applyCapabilities(capabilities) }
    }

    override fun startSearch(
        value: String,
        secondValue: String,
        type: Int,
        predicate: Int,
        unknown: Boolean,
        scope: Int,
    ) {
        clearPendingTransition()
        pendingResetHistory = true
        pendingStage = if (unknown) MemorySessionStage.UNKNOWN_BASELINE else MemorySessionStage.CANDIDATES
        pendingMode = if (unknown) MemorySearchMode.UNKNOWN else MemorySearchMode.KNOWN
        clearSearchPresentation()
        operate(searching = true) {
            if (unknown) startUnknownSearch(state.runtimeToken, scope, type)
            else startKnownSearch(
                state.runtimeToken,
                scope,
                type,
                predicate.coerceAtMost(MemoryEngineContract.PREDICATE_BETWEEN),
                value.trim(),
                secondValue.trim(),
            )
        }
    }

    override fun nextScan(value: String, secondValue: String, predicate: Int, compare: Int) {
        clearPendingTransition()
        pendingPreviousStage = state.sessionStage
        pendingStage = MemorySessionStage.CANDIDATES
        pendingMode = state.searchMode
        operate(searching = true) {
            if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) {
                refineRelative(state.runtimeToken, predicate, compare, value.trim(), secondValue.trim())
            } else {
                refineKnown(state.runtimeToken, predicate, value.trim(), secondValue.trim())
            }
        }
    }

    override fun groupSearch(types: IntArray, values: Array<String>, distance: Int, scope: Int) {
        clearPendingTransition()
        pendingResetHistory = true
        pendingStage = MemorySessionStage.CANDIDATES
        pendingMode = MemorySearchMode.GROUP
        clearSearchPresentation()
        operate(searching = true) {
            startGroupSearch(state.runtimeToken, scope, types, values, distance)
        }
    }

    override fun startNearbySearch(
        anchorCandidateId: Long,
        radius: Int,
        type: Int,
        predicate: Int,
        value: String,
        secondValue: String,
    ) {
        if (anchorCandidateId <= 0L || !MemoryEngineContract.isNearbyRadius(radius) ||
            !MemoryEngineContract.isValueType(type) || predicate !in
            MemoryEngineContract.PREDICATE_EQUAL..MemoryEngineContract.PREDICATE_BETWEEN
        ) {
            state = state.copy(message = resultMessage(MemoryEngineContract.RESULT_INVALID_REQUEST))
            return
        }
        clearPendingTransition()
        pendingResetHistory = true
        pendingStage = MemorySessionStage.CANDIDATES
        pendingMode = MemorySearchMode.KNOWN
        clearSearchPresentation()
        operate(searching = true) {
            startNearbySearch(
                state.runtimeToken,
                anchorCandidateId,
                radius,
                type,
                predicate,
                value.trim(),
                secondValue.trim(),
            )
        }
    }

    override fun inspectCandidate(candidateId: Long, radius: Int) {
        if (state.busy || state.inspectorLoading || state.runtimeToken == 0L ||
            candidateId <= 0L || !MemoryEngineContract.isInspectRadius(radius)
        ) return
        val row = findCandidate(candidateId) ?: run {
            state = state.copy(message = resultMessage(MemoryEngineContract.RESULT_INVALID_REQUEST))
            return
        }
        val token = state.runtimeToken
        state = state.copy(inspectorLoading = true, inspector = null, message = null)
        runIpc {
            val engine = service ?: throw RemoteException("Engine disconnected")
            val bundle = engine.inspectCandidate(token, candidateId, radius)
            val result = bundle?.getInt(
                MemoryEngineContract.KEY_INSPECT_RESULT,
                MemoryEngineContract.RESULT_INVALID_REQUEST,
            ) ?: MemoryEngineContract.RESULT_INVALID_REQUEST
            val message = bundle?.getString(MemoryEngineContract.KEY_MESSAGE)
            val start = bundle?.getLong(MemoryEngineContract.KEY_INSPECT_START, 0L) ?: 0L
            val anchor = bundle?.getLong(MemoryEngineContract.KEY_INSPECT_ANCHOR, 0L) ?: 0L
            val bytes = bundle?.getByteArray(MemoryEngineContract.KEY_INSPECT_BYTES)
            post {
                if (state.runtimeToken != token) return@post
                if (result == MemoryEngineContract.RESULT_OK && start > 0L && anchor > 0L &&
                    bytes != null && bytes.size <= MemoryEngineContract.MAX_INSPECT_BYTES
                ) {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = MemoryInspectorSnapshot(
                            candidateId = candidateId,
                            type = row.type,
                            label = row.label,
                            startAddress = start,
                            anchorAddress = anchor,
                            bytes = bytes,
                        ),
                    )
                } else {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = null,
                        message = operationMessage(result, message),
                    )
                }
            }
        }
    }

    override fun closeInspector() {
        pendingInspectorRefresh = null
        state = state.copy(inspectorLoading = false, inspector = null)
    }

    override fun editInspectorValue(
        anchorCandidateId: Long,
        relativeOffset: Int,
        type: Int,
        expectedBits: Long,
        replacementValue: String,
    ) {
        val snapshot = state.inspector ?: return
        if (state.busy || state.inspectorLoading || state.runtimeToken == 0L ||
            anchorCandidateId != snapshot.candidateId ||
            !MemoryEngineContract.isCandidateType(type) ||
            !MemoryInputSpec.forType(type).isComplete(replacementValue)
        ) return
        val radius = maxOf(
            snapshot.anchorAddress - snapshot.startAddress,
            snapshot.startAddress + snapshot.bytes.size - snapshot.anchorAddress,
        ).toInt().coerceIn(1, MemoryEngineContract.MAX_INSPECT_RADIUS)
        pendingInspectorRefresh = MemoryInspectorRefresh(anchorCandidateId, radius)
        operate {
            editInspectorValue(
                state.runtimeToken,
                anchorCandidateId,
                relativeOffset,
                type,
                expectedBits,
                replacementValue.trim(),
            )
        }
    }

    override fun undo() {
        clearPendingTransition()
        pendingUndoStage = if (stageHistory.isEmpty()) null else stageHistory.peekLast()
        operate { undoSearch(state.runtimeToken) }
    }

    override fun startOver() {
        if (state.busy || state.runtimeToken == 0L) return
        stageHistory.clear()
        clearPendingTransition()
        val token = state.runtimeToken
        state = state.copy(
            sessionStage = MemorySessionStage.EMPTY,
            searchMode = MemorySearchMode.KNOWN,
            requestedType = MemoryEngineContract.TYPE_AUTO,
            searchScope = MemoryEngineContract.SCOPE_JAVA_FAST,
            canUndo = false,
            resultCount = 0,
            pageOffset = 0,
            results = emptyList(),
            selected = emptySet(),
            inspectorLoading = false,
            inspector = null,
            message = null,
        )
        runIpc { service?.clearSearch(token) }
    }

    override fun refresh() {
        if (state.busy || refreshInFlight || state.sessionStage == MemorySessionStage.UNKNOWN_BASELINE) return
        val ids = visiblePrimaryIds().toLongArray()
        if (ids.isEmpty()) {
            reload()
        } else {
            refreshInFlight = true
            runIpc {
                val engine = service ?: throw RemoteException("Engine disconnected")
                engine.refreshCandidates(state.runtimeToken, ids)
            }
        }
    }

    override fun setWatchTab(watch: Boolean) {
        state = state.copy(watchTab = watch, selected = emptySet(), inspectorLoading = false, inspector = null)
        reload()
    }

    override fun toggleSelection(id: Long) {
        state = state.copy(selected = state.selected.toMutableSet().apply {
            if (!add(id)) remove(id)
        })
    }

    override fun selectVisible() {
        state = state.copy(selected = visiblePrimaryIds().toMutableSet())
    }

    override fun invertVisible() {
        val visible = visiblePrimaryIds()
        state = state.copy(selected = state.selected.toMutableSet().apply {
            for (id in visible) if (!add(id)) remove(id)
        })
    }

    override fun clearSelection() {
        state = state.copy(selected = emptySet())
    }

    private fun visiblePrimaryIds(): List<Long> = if (state.watchTab) {
        state.watches.map { it.id }
    } else {
        state.results.map { it.id }
    }

    private fun findCandidate(id: Long): MemoryCandidatePresentation? =
        state.results.firstOrNull { it.id == id }?.let {
            MemoryCandidatePresentation(it.primaryType, "")
        } ?: state.watches.firstOrNull { it.id == id }?.let {
            MemoryCandidatePresentation(it.type, it.label)
        }

    override fun editSelected(value: String, type: Int) {
        editSelectedWithOptions(value, type, addToWatch = false, freezeAfter = false)
    }

    override fun editSelectedWithOptions(
        value: String,
        type: Int,
        addToWatch: Boolean,
        freezeAfter: Boolean,
    ) {
        val ids: LongArray
        if (state.watchTab) {
            val selectedRows = state.watches.filter { it.id in state.selected }
            val typed = selectedRows.filter { it.type == type }
            if (typed.size != selectedRows.size) {
                state = state.copy(message = resultMessage(MemoryEngineContract.RESULT_INVALID_REQUEST))
                return
            }
            ids = typed.map { it.id }.distinct().toLongArray()
        } else {
            ids = state.results.asSequence()
                .filter { it.id in state.selected }
                .map { it.id }
                .toList()
                .toLongArray()
        }
        if (ids.isEmpty()) return
        if (ids.size > MemoryEngineContract.MAX_MULTI_WRITE) {
            state = state.copy(message = resultMessage(MemoryEngineContract.RESULT_SAFETY_LIMIT))
            return
        }
        if (state.busy || state.runtimeToken == 0L) return
        pendingEditFollowUp = PendingEditFollowUp(
            candidateIds = ids.copyOf(),
            replacementValue = value.trim(),
            addToWatch = addToWatch && !freezeAfter,
            freezeAfter = freezeAfter,
        )
        operate {
            if (state.watchTab) editCandidates(state.runtimeToken, ids, value.trim())
            else editResultGroups(state.runtimeToken, ids, type, value.trim())
        }
    }

    private fun scheduleEditFollowUp(followUp: PendingEditFollowUp) {
        if (state.runtimeToken == 0L || followUp.candidateIds.isEmpty()) return
        when {
            followUp.freezeAfter -> operate {
                setFreeze(
                    state.runtimeToken,
                    followUp.candidateIds,
                    MemoryEngineContract.FREEZE_LOCK,
                    followUp.replacementValue,
                    "",
                )
            }
            followUp.addToWatch -> operate {
                addWatch(state.runtimeToken, followUp.candidateIds)
            }
        }
    }

    override fun removeSelected(keep: Boolean) {
        val ids = if (state.watchTab) {
            selectedIds()
        } else {
            state.results.asSequence()
                .filter { it.id in state.selected }
                .map { it.id }
                .distinct()
                .toList()
                .takeIf { it.isNotEmpty() }
                ?.toLongArray()
        } ?: return
        operate {
            if (state.watchTab) {
                if (keep) keepCandidates(state.runtimeToken, ids)
                else removeCandidates(state.runtimeToken, ids)
            } else {
                filterResultGroups(state.runtimeToken, ids, keep)
            }
        }
    }

    override fun watchSelected(add: Boolean) {
        val ids = selectedIds() ?: return
        operate {
            if (add) addWatch(state.runtimeToken, ids) else removeWatch(state.runtimeToken, ids)
        }
    }

    override fun labelWatch(id: Long, label: String) = operate {
        setWatchLabel(state.runtimeToken, id, label.trim())
    }

    override fun freezeSelected(mode: Int, first: String, second: String) {
        val ids = selectedIds(max = MemoryEngineContract.MAX_FREEZE_RECORDS) ?: return
        operate { setFreeze(state.runtimeToken, ids, mode, first.trim(), second.trim()) }
    }

    override fun clearFreezeSelected() {
        val ids = state.watches.asSequence()
            .filter { it.id in state.selected && it.freezeMode >= 0 }
            .map { it.id }
            .distinct()
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.toLongArray() ?: return
        operate { clearFreeze(state.runtimeToken, ids) }
    }

    override fun copySelected(addresses: Boolean) {
        val text = if (state.watchTab) {
            state.watches.filter { it.id in state.selected }.joinToString("\n") {
                if (addresses) it.addressText else it.valueText
            }
        } else {
            state.results.filter { it.id in state.selected }.joinToString("\n") {
                if (addresses) it.addressText else it.valueText
            }
        }
        if (text.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.memory_editor), text))
    }

    override fun previousPage() {
        state = state.copy(
            pageOffset = (state.pageOffset - PAGE_SIZE).coerceAtLeast(0),
            selected = emptySet(),
            inspectorLoading = false,
            inspector = null,
        )
        reload()
    }

    override fun nextPage() {
        if (state.pageOffset.toLong() + PAGE_SIZE < state.resultCount) {
            state = state.copy(
                pageOffset = state.pageOffset + PAGE_SIZE,
                selected = emptySet(),
                inspectorLoading = false,
                inspector = null,
            )
            reload()
        }
    }

    override fun cancel() {
        val token = state.runtimeToken
        runIpc { service?.cancelOperation(token) }
    }

    private fun selectedIds(max: Int = Int.MAX_VALUE): LongArray? {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return null
        if (ids.size > max) {
            state = state.copy(message = resultMessage(MemoryEngineContract.RESULT_SAFETY_LIMIT))
            return null
        }
        return ids
    }

    private fun operate(
        searching: Boolean = false,
        block: IMemoryEngineService.() -> Long,
    ) {
        if (state.busy || state.runtimeToken == 0L) return
        val generation = ++operationGeneration
        activeOperationId = 0L
        state = state.copy(
            busy = true,
            searching = searching,
            scanBytesScanned = 0L,
            scanBytesTotal = 0L,
            message = null,
        )
        runIpc {
            val engine = service ?: throw RemoteException("Engine disconnected")
            val operationId = engine.block()
            post {
                if (generation == operationGeneration && state.busy) activeOperationId = operationId
            }
        }
    }

    private fun reload(refreshAfterLoad: Boolean = false) {
        val requestedWatchTab = state.watchTab
        runIpc {
            if (!state.visible) return@runIpc
            val engine = service ?: return@runIpc
            val token = state.runtimeToken
            if (token == 0L) return@runIpc
            val session = engine.getSearchSessionInfo(token)
            val sessionStage = memorySessionStageFromEngine(
                session?.getInt(
                    MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
                    MemoryEngineContract.SEARCH_SESSION_EMPTY,
                ) ?: MemoryEngineContract.SEARCH_SESSION_EMPTY,
            )
            val sessionMode = memorySearchModeFromEngine(
                session?.getInt(
                    MemoryEngineContract.KEY_SEARCH_MODE,
                    MemoryEngineContract.SEARCH_MODE_KNOWN,
                ) ?: MemoryEngineContract.SEARCH_MODE_KNOWN,
            )
            val requestedType = session?.getInt(
                MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
                MemoryEngineContract.TYPE_AUTO,
            )?.takeIf(MemoryEngineContract::isValueType) ?: MemoryEngineContract.TYPE_AUTO
            val searchScope = session?.getInt(
                MemoryEngineContract.KEY_SEARCH_SCOPE,
                MemoryEngineContract.SCOPE_JAVA_FAST,
            )?.takeIf(MemoryEngineContract::isScope) ?: MemoryEngineContract.SCOPE_JAVA_FAST
            val canUndo = (session?.getInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH, 0) ?: 0) > 0
            val nativeCount = engine.getResultCount(token)
            val count = if (sessionStage == MemorySessionStage.EMPTY) 0L else nativeCount
            val offset = state.pageOffset
            val lastOffset = if (count == 0L) 0L else (count - 1L) / PAGE_SIZE * PAGE_SIZE
            val safeOffset = if (sessionStage == MemorySessionStage.CANDIDATES) {
                offset.coerceAtMost(
                    lastOffset.coerceAtMost((Int.MAX_VALUE / PAGE_SIZE * PAGE_SIZE).toLong()).toInt(),
                )
            } else 0
            val resultRows = if (!requestedWatchTab && sessionStage == MemorySessionStage.CANDIDATES) {
                MemoryResultPageParser.parse(engine.getResultPage(token, safeOffset, PAGE_SIZE))
            } else emptyList()
            val watchRows = if (requestedWatchTab) attachWatchMetadata(engine.getWatchPage(token)) else emptyList()
            val visibleIds = if (requestedWatchTab) watchRows.map { it.id } else resultRows.map { it.id }
            post {
                if (!state.visible || state.watchTab != requestedWatchTab) return@post
                state = state.copy(
                    resultCount = count,
                    pageOffset = safeOffset,
                    results = resultRows,
                    watches = watchRows,
                    sessionStage = sessionStage,
                    searchMode = sessionMode,
                    requestedType = requestedType,
                    searchScope = searchScope,
                    canUndo = canUndo,
                    selected = state.selected.intersect(visibleIds.toSet()),
                )
                if (refreshAfterLoad && sessionStage == MemorySessionStage.CANDIDATES) refresh()
            }
        }
    }

    private fun clearSearchPresentation() {
        state = state.copy(
            pageOffset = 0,
            results = emptyList(),
            selected = emptySet(),
            inspectorLoading = false,
            inspector = null,
        )
    }

    private fun attachWatchMetadata(bundle: Bundle?): List<MemoryWatchRow> = MemoryWatchPageParser.parse(bundle)

    private fun applyCapabilities(bundle: Bundle?) {
        val supported = bundle?.getBoolean(MemoryEngineContract.KEY_SUPPORTED) == true
        val token = bundle?.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN) ?: 0L
        val runtimeChanged = token != state.runtimeToken
        if (runtimeChanged) {
            stageHistory.clear()
            clearPendingTransition()
            activeOperationId = 0L
            ++operationGeneration
        }
        state = state.copy(
            connected = bundle != null,
            connecting = false,
            supported = supported,
            writeSupported = bundle?.getBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED) == true,
            runtimeToken = token,
            sessionStage = if (runtimeChanged) MemorySessionStage.EMPTY else state.sessionStage,
            searchMode = if (runtimeChanged) MemorySearchMode.KNOWN else state.searchMode,
            requestedType = if (runtimeChanged) MemoryEngineContract.TYPE_AUTO else state.requestedType,
            searchScope = if (runtimeChanged) MemoryEngineContract.SCOPE_JAVA_FAST else state.searchScope,
            canUndo = if (runtimeChanged) false else state.canUndo,
            resultCount = if (runtimeChanged) 0L else state.resultCount,
            pageOffset = if (runtimeChanged) 0 else state.pageOffset,
            results = if (runtimeChanged) emptyList() else state.results,
            watches = if (runtimeChanged) emptyList() else state.watches,
            selected = if (runtimeChanged) emptySet() else state.selected,
            inspectorLoading = if (runtimeChanged) false else state.inspectorLoading,
            inspector = if (runtimeChanged) null else state.inspector,
            message = if (supported) null else bundle?.getString(MemoryEngineContract.KEY_MESSAGE),
        )
        if (supported && state.visible) reload(refreshAfterLoad = true)
    }

    private fun disconnected() = post {
        service = null
        refreshInFlight = false
        activeOperationId = 0L
        ++operationGeneration
        stageHistory.clear()
        clearPendingTransition()
        state = state.copy(
            connected = false,
            connecting = false,
            supported = false,
            busy = false,
            searching = false,
            sessionStage = MemorySessionStage.EMPTY,
            searchMode = MemorySearchMode.KNOWN,
            requestedType = MemoryEngineContract.TYPE_AUTO,
            searchScope = MemoryEngineContract.SCOPE_JAVA_FAST,
            canUndo = false,
            resultCount = 0L,
            pageOffset = 0,
            results = emptyList(),
            watches = emptyList(),
            selected = emptySet(),
            inspectorLoading = false,
            inspector = null,
            message = context.getString(R.string.memory_editor_unsupported),
        )
    }

    private fun clearPendingTransition() {
        pendingStage = null
        pendingMode = null
        pendingUndoStage = null
        pendingPreviousStage = null
        pendingResetHistory = false
        pendingEditFollowUp = null
    }

    private fun runIpc(block: () -> Unit) {
        if (destroyed || ipc.isShutdown) return
        ipc.execute {
            try {
                block()
            } catch (_: RemoteException) {
                disconnected()
            } catch (exception: RuntimeException) {
                post {
                    refreshInFlight = false
                    activeOperationId = 0L
                    ++operationGeneration
                    clearPendingTransition()
                    state = state.copy(
                        busy = false,
                        searching = false,
                        inspectorLoading = false,
                        message = exception.message,
                    )
                }
            }
        }
    }

    private fun post(block: () -> Unit) {
        if (!destroyed) editorView.post { if (!destroyed) block() }
    }

    private fun resultMessage(code: Int): String = when (code) {
        MemoryEngineContract.RESULT_CANCELLED -> context.getString(R.string.memory_editor_cancelled)
        MemoryEngineContract.RESULT_RESOURCE_LIMIT -> context.getString(R.string.memory_editor_resource_limit)
        MemoryEngineContract.RESULT_TARGET_LOST -> context.getString(R.string.memory_editor_target_lost)
        MemoryEngineContract.RESULT_IDENTITY_UNSAFE -> context.getString(R.string.memory_editor_identity_unsafe)
        MemoryEngineContract.RESULT_SAFETY_LIMIT -> context.getString(R.string.memory_editor_safety_limit)
        MemoryEngineContract.RESULT_UNSUPPORTED -> context.getString(R.string.memory_editor_write_unsupported)
        MemoryEngineContract.RESULT_GC_REVALIDATED -> context.getString(R.string.memory_editor_gc_revalidated)
        MemoryEngineContract.RESULT_GC_RACE -> context.getString(R.string.memory_editor_gc_race)
        MemoryEngineContract.RESULT_GC_BASELINE_INVALIDATED ->
            context.getString(R.string.memory_editor_gc_baseline_invalidated)
        else -> context.getString(R.string.memory_editor_invalid_request)
    }

    private fun operationMessage(resultCode: Int, message: String?): String = when (resultCode) {
        MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
        MemoryEngineContract.RESULT_GC_REVALIDATED,
        MemoryEngineContract.RESULT_GC_RACE,
        MemoryEngineContract.RESULT_GC_BASELINE_INVALIDATED -> resultMessage(resultCode)
        else -> message ?: resultMessage(resultCode)
    }

    internal companion object {
        const val PAGE_SIZE = 100
    }
}

private data class MemoryCandidatePresentation(
    val type: Int,
    val label: String,
)

private data class MemoryInspectorRefresh(
    val candidateId: Long,
    val radius: Int,
)
