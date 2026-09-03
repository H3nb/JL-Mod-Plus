/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Presentation controller hosted in :memory_engine. The MIDlet process owns only the small bubble;
 * all Compose state and interaction allocations stay beside the engine rather than the target heap.
 */
class MemoryEditorComposeController(
    private val composeView: ComposeView,
    private val ownedRuntimeToken: Long,
    private val closeHost: () -> Unit,
) : MemoryEditorActions {
    private val context = composeView.context
    private val ipc: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MemoryEditorUiIpc").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var state by mutableStateOf(MemoryEditorUiState())
    private var service: IMemoryEngineService? = null
    private var bound = false
    private var destroyed = false
    private var activeOperationId = 0L
    private var operationGeneration = 0L
    @Volatile private var connectionGeneration = 0
    private var pendingEditFollowUp: PendingEditFollowUp? = null
    private var pendingInspectorRefresh: PendingInspectorRefresh? = null
    private var pendingOperationFeedback: PendingOperationFeedback? = null

    private data class PendingEditFollowUp(
        val id: Long,
        val type: Int,
        val value: String,
        val addToWatch: Boolean,
        val freezeAfter: Boolean,
        val resultGroup: Boolean,
    )
    private data class PendingInspectorRefresh(val candidateId: Long, val radius: Int)
    private data class PendingOperationFeedback(
        val kind: OperationFeedbackKind,
        val resultCountBefore: Long,
    )
    private enum class OperationFeedbackKind { NEXT_SCAN }

    private val callback = object : IMemoryEngineCallback.Stub() {
        override fun onOperationProgress(operationId: Long, scannedBytes: Long, totalBytes: Long) {
            post {
                if (state.busy && (activeOperationId == 0L || operationId == activeOperationId) &&
                    totalBytes > 0L
                ) {
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
                // Binder can finish a tiny refine before the main-thread operation-id post.
                // Accept that first completion while busy, then invalidate the delayed id post.
                if (!state.busy && activeOperationId == 0L) return@post
                if (activeOperationId != 0L && operationId != activeOperationId) return@post

                val feedback = pendingOperationFeedback
                pendingOperationFeedback = null
                operationGeneration++

                if (resultCode == MemoryEngineContract.RESULT_TARGET_LOST ||
                    resultCode == MemoryEngineContract.RESULT_NO_SESSION
                ) {
                    pendingEditFollowUp = null
                    pendingInspectorRefresh = null
                    activeOperationId = 0L
                    state = state.copy(
                        busy = false,
                        searching = false,
                        scanBytesScanned = 0L,
                        scanBytesTotal = 0L,
                        message = null,
                    )
                    close()
                    return@post
                }

                val succeeded = resultCode == MemoryEngineContract.RESULT_OK
                val editFollowUp = if (succeeded) pendingEditFollowUp else null
                val inspectorFollowUp = if (succeeded) pendingInspectorRefresh else null
                pendingEditFollowUp = null
                pendingInspectorRefresh = null
                activeOperationId = 0L
                val engineSuccessMessage = message?.takeIf(String::isNotBlank)
                val uiSuccessMessage = if (succeeded && feedback?.kind == OperationFeedbackKind.NEXT_SCAN) {
                    "${context.getString(R.string.memory_editor_next_scan)}: ${feedback.resultCountBefore} → $resultCount"
                } else null
                state = state.copy(
                    busy = false,
                    searching = false,
                    scanBytesScanned = 0L,
                    scanBytesTotal = 0L,
                    resultCount = resultCount,
                    message = if (succeeded) {
                        listOfNotNull(uiSuccessMessage, engineSuccessMessage)
                            .distinct()
                            .joinToString(" · ")
                            .takeIf(String::isNotBlank)
                    } else {
                        operationMessage(resultCode, message)
                    },
                )

                when {
                    editFollowUp != null -> completeEditFlow(editFollowUp)
                    inspectorFollowUp != null -> {
                        reloadState()
                        inspectCandidate(inspectorFollowUp.candidateId, inspectorFollowUp.radius)
                    }
                    else -> reloadState()
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connectionGeneration++
            service = IMemoryEngineService.Stub.asInterface(binder)
            runIpc {
                val engine = service ?: return@runIpc
                engine.registerCallback(callback)
                post { state = state.copy(connected = true, connecting = true, message = null) }
                reloadState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    init {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.visibility = View.GONE
        composeView.setContent {
            JLModPlusTheme(paintWindowBackground = false) {
                MemoryEditorRuntimeRoot(state = state, actions = this)
            }
        }
    }

    fun open() {
        if (destroyed || ownedRuntimeToken == 0L) return
        state = state.copy(visible = true, connecting = service == null, message = null)
        composeView.visibility = View.VISIBLE
        composeView.requestFocus()
        if (service == null) connectEngine() else reloadState()
    }

    override fun close() {
        if (destroyed) return
        state = state.copy(visible = false, selected = emptySet())
        composeView.visibility = View.GONE
        closeHost()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        connectionGeneration++
        composeView.visibility = View.GONE
        composeView.disposeComposition()
        disconnectEngine()
        ipc.shutdownNow()
    }

    private fun runtimeStillActive(): Boolean = ownedRuntimeToken != 0L && !destroyed

    private fun connectEngine() {
        if (bound || destroyed || !runtimeStillActive()) return
        state = state.copy(connecting = true, message = null)
        bound = context.bindService(
            Intent(context, MemoryEngineService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
            state = state.copy(
                connecting = false,
                connected = false,
                supported = false,
                message = context.getString(R.string.memory_editor_engine_unavailable),
            )
        }
    }

    private fun disconnectEngine() {
        connectionGeneration++
        runCatching { service?.unregisterCallback(callback) }
        service = null
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
    }

    private fun disconnected() {
        post {
            service = null
            activeOperationId = 0L
            pendingEditFollowUp = null
            pendingInspectorRefresh = null
            if (!runtimeStillActive()) {
                destroy()
                return@post
            }
            state = state.copy(
                connecting = true,
                connected = false,
                busy = false,
                searching = false,
                scanBytesScanned = 0L,
                scanBytesTotal = 0L,
                message = null,
            )
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (destroyed || !runtimeStillActive()) return
        val generation = ++connectionGeneration
        composeView.postDelayed({
            if (destroyed || generation != connectionGeneration || !runtimeStillActive()) {
                return@postDelayed
            }
            disconnectEngine()
            connectEngine()
        }, ENGINE_RECONNECT_DELAY_MS)
    }

    override fun refreshCapabilities() = reloadState()

    private fun reloadState(retry: Int = 0): Unit = runIpc {
        val localToken = ownedRuntimeToken
        if (localToken == 0L) {
            post { close() }
            return@runIpc
        }

        val engine = service ?: run {
            post { scheduleReconnect() }
            return@runIpc
        }
        val capabilities = engine.capabilities
        val supported = capabilities.getBoolean(MemoryEngineContract.KEY_SUPPORTED, false)
        val writeSupported = capabilities.getBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED, false)
        val token = capabilities.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, 0L)
        val capabilityMessage = capabilities.getString(MemoryEngineContract.KEY_MESSAGE)

        // The target bridge binds asynchronously. A zero token gets a short bounded handshake
        // window; a different nonzero token is a different MIDlet generation and closes this UI.
        if (token != localToken) {
            if (token == 0L && retry < CAPABILITY_RETRY_DELAYS_MS.size) {
                val delay = CAPABILITY_RETRY_DELAYS_MS[retry]
                val generation = connectionGeneration
                post {
                    state = state.copy(connecting = true, connected = true, message = null)
                    composeView.postDelayed({
                        if (!destroyed && generation == connectionGeneration) {
                            reloadState(retry + 1)
                        }
                    }, delay)
                }
            } else {
                post { close() }
            }
            return@runIpc
        }

        if (!supported) {
            post {
                state = state.copy(
                    connecting = false,
                    connected = true,
                    supported = false,
                    writeSupported = false,
                    runtimeToken = token,
                    results = emptyList(),
                    watches = emptyList(),
                    resultCount = 0L,
                    message = capabilityMessage ?: context.getString(R.string.memory_editor_unsupported),
                )
            }
            return@runIpc
        }

        val session = engine.getSearchSessionInfo(token)
        val stage = memorySessionStageFromEngine(
            session.getInt(
                MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
                MemoryEngineContract.SEARCH_SESSION_EMPTY,
            ),
        )
        val mode = memorySearchModeFromEngine(
            session.getInt(
                MemoryEngineContract.KEY_SEARCH_MODE,
                MemoryEngineContract.SEARCH_MODE_KNOWN,
            ),
        )
        val requestedType = session.getInt(
            MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
            MemoryEngineContract.TYPE_AUTO,
        )
        val scope = session.getInt(
            MemoryEngineContract.KEY_SEARCH_SCOPE,
            MemoryEngineContract.SCOPE_JAVA_FAST,
        )
        val canUndo = session.getInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH, 0) > 0
        val resultCount = engine.getResultCount(token)
        val requestedOffset = state.pageOffset
        val maxOffset = if (resultCount <= 0L) 0 else {
            (((resultCount - 1L) / PAGE_SIZE) * PAGE_SIZE)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val pageOffset = requestedOffset.coerceIn(0, maxOffset)
        val results = if (stage == MemorySessionStage.CANDIDATES) {
            MemoryResultPageParser.parse(engine.getResultPage(token, pageOffset, PAGE_SIZE))
        } else {
            emptyList()
        }
        val watches = MemoryWatchPageParser.parse(engine.getWatchPage(token))

        post {
            val validIds = buildSet {
                results.forEach { add(it.id) }
                watches.forEach { add(it.id) }
            }
            state = state.copy(
                connecting = false,
                connected = true,
                supported = true,
                writeSupported = writeSupported,
                runtimeToken = token,
                resultCount = resultCount,
                pageOffset = pageOffset,
                results = results,
                watches = watches,
                selected = state.selected.filterTo(mutableSetOf()) { it in validIds },
                searchMode = mode,
                sessionStage = stage,
                requestedType = requestedType,
                searchScope = scope,
                canUndo = canUndo,
                message = capabilityMessage?.takeIf(String::isNotBlank) ?: state.message,
            )
        }
    }

    override fun startSearch(
        value: String,
        secondValue: String,
        type: Int,
        predicate: Int,
        unknown: Boolean,
        scope: Int,
    ) {
        state = state.copy(pageOffset = 0, selected = emptySet(), inspector = null)
        launchOperation(searching = true) { engine, token ->
            if (unknown) {
                engine.startUnknownSearch(token, scope, type)
            } else {
                engine.startKnownSearch(
                    token,
                    scope,
                    type,
                    predicate.coerceAtMost(MemoryEngineContract.PREDICATE_BETWEEN),
                    value.trim(),
                    secondValue.trim(),
                )
            }
        }
    }

    override fun groupSearch(types: IntArray, values: Array<String>, distance: Int, scope: Int) {
        state = state.copy(pageOffset = 0, selected = emptySet(), inspector = null)
        launchOperation(searching = true) { engine, token ->
            engine.startGroupSearch(token, scope, types, values, distance)
        }
    }

    override fun nextScan(value: String, secondValue: String, predicate: Int, compare: Int) {
        state = state.copy(pageOffset = 0, selected = emptySet(), inspector = null)
        launchOperation(
            searching = true,
            feedback = PendingOperationFeedback(OperationFeedbackKind.NEXT_SCAN, state.resultCount),
        ) { engine, token ->
            if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) {
                engine.refineRelative(token, predicate, compare, value.trim(), secondValue.trim())
            } else {
                engine.refineKnown(token, predicate, value.trim(), secondValue.trim())
            }
        }
    }

    override fun undo() {
        launchOperation { engine, token -> engine.undoSearch(token) }
    }

    /** Explicit refresh is also the relocation/rebind action after Java GC. */
    override fun refresh() {
        if (state.busy) return
        val ids = if (state.watchTab) {
            state.watches.map(MemoryWatchRow::id)
        } else {
            state.results.map(MemoryResultRow::id)
        }.toLongArray()
        if (ids.isEmpty()) {
            reloadState()
            return
        }
        launchOperation { engine, token -> engine.refreshCandidates(token, ids) }
    }

    override fun setWatchTab(watch: Boolean) {
        if (state.watchTab == watch) return
        state = state.copy(watchTab = watch, selected = emptySet())
    }

    override fun toggleSelection(id: Long) {
        state = state.copy(
            selected = state.selected.toMutableSet().also { selected ->
                if (!selected.add(id)) selected.remove(id)
            },
        )
    }

    override fun selectVisible() {
        val ids = if (state.watchTab) state.watches.map(MemoryWatchRow::id)
        else state.results.map(MemoryResultRow::id)
        state = state.copy(selected = ids.toSet())
    }

    override fun invertVisible() {
        val ids = if (state.watchTab) state.watches.map(MemoryWatchRow::id)
        else state.results.map(MemoryResultRow::id)
        state = state.copy(selected = ids.filterNot(state.selected::contains).toSet())
    }

    override fun clearSelection() {
        state = state.copy(selected = emptySet())
    }

    override fun editSelected(value: String, type: Int) {
        val id = state.selected.singleOrNull() ?: return
        val resultGroup = !state.watchTab
        launchOperation { engine, token ->
            if (resultGroup) {
                engine.editResultGroups(token, longArrayOf(id), type, value.trim())
            } else {
                engine.editCandidates(token, longArrayOf(id), value.trim())
            }
        }
    }

    override fun editSelectedWithOptions(
        value: String,
        type: Int,
        addToWatch: Boolean,
        freezeAfter: Boolean,
    ) {
        val id = state.selected.singleOrNull() ?: return
        if (!canLaunchOperation()) return
        val resultGroup = !state.watchTab
        val replacement = value.trim()
        pendingEditFollowUp = if (addToWatch || freezeAfter) {
            PendingEditFollowUp(id, type, replacement, addToWatch, freezeAfter, resultGroup)
        } else {
            null
        }
        launchOperation { engine, token ->
            if (resultGroup) {
                engine.editResultGroups(token, longArrayOf(id), type, replacement)
            } else {
                engine.editCandidates(token, longArrayOf(id), replacement)
            }
        }
    }

    private fun completeEditFlow(followUp: PendingEditFollowUp) {
        when {
            followUp.freezeAfter && followUp.resultGroup -> launchOperation { engine, token ->
                engine.setFreezeResultGroups(
                    token,
                    longArrayOf(followUp.id),
                    followUp.type,
                    MemoryEngineContract.FREEZE_LOCK,
                    followUp.value,
                    "",
                )
            }
            followUp.freezeAfter -> launchOperation { engine, token ->
                engine.setFreeze(
                    token,
                    longArrayOf(followUp.id),
                    MemoryEngineContract.FREEZE_LOCK,
                    followUp.value,
                    "",
                )
            }
            followUp.addToWatch && followUp.resultGroup -> launchOperation { engine, token ->
                engine.addWatchResultGroups(token, longArrayOf(followUp.id), followUp.type)
            }
            else -> reloadState()
        }
    }

    override fun removeSelected(keep: Boolean) {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return
        val watch = state.watchTab
        launchOperation { engine, token ->
            if (watch) {
                if (keep) MemoryEngineContract.RESULT_INVALID_REQUEST.toLong()
                else engine.removeWatch(token, ids)
            } else {
                engine.filterResultGroups(token, ids, keep)
            }
        }
    }

    override fun watchSelected(add: Boolean) {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return
        launchOperation { engine, token ->
            if (add) engine.addWatch(token, ids) else engine.removeWatch(token, ids)
        }
    }

    override fun labelWatch(id: Long, label: String) {
        launchOperation { engine, token -> engine.setWatchLabel(token, id, label.take(64)) }
    }

    override fun freezeSelected(mode: Int, first: String, second: String) {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return
        launchOperation { engine, token -> engine.setFreeze(token, ids, mode, first, second) }
    }

    override fun clearFreezeSelected() {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return
        launchOperation { engine, token -> engine.clearFreeze(token, ids) }
    }

    override fun copySelected(addresses: Boolean) {
        val selected = state.selected
        val rows = if (state.watchTab) {
            state.watches.filter { it.id in selected }.map {
                if (addresses) it.addressText else it.valueText
            }
        } else {
            state.results.filter { it.id in selected }.map {
                if (addresses) it.addressText else it.valueText
            }
        }
        if (rows.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText("Memory Editor", rows.joinToString("\n")),
        )
    }

    override fun previousPage() {
        if (state.pageOffset <= 0) return
        state = state.copy(pageOffset = (state.pageOffset - PAGE_SIZE).coerceAtLeast(0), selected = emptySet())
        reloadState()
    }

    override fun nextPage() {
        if (state.pageOffset.toLong() + PAGE_SIZE >= state.resultCount) return
        state = state.copy(pageOffset = state.pageOffset + PAGE_SIZE, selected = emptySet())
        reloadState()
    }

    override fun cancel() {
        val token = state.runtimeToken
        val engine = service
        if (token == 0L || engine == null) return
        runIpc { engine.cancelOperation(token) }
    }

    override fun startOver() {
        val token = state.runtimeToken
        val engine = service ?: return
        if (token == 0L) return
        runIpc {
            engine.clearSearch(token)
            post {
                state = state.copy(
                    pageOffset = 0,
                    resultCount = 0L,
                    results = emptyList(),
                    selected = emptySet(),
                    sessionStage = MemorySessionStage.EMPTY,
                    searchMode = MemorySearchMode.KNOWN,
                    inspector = null,
                    inspectorLoading = false,
                    message = null,
                )
            }
            reloadState()
        }
    }

    override fun inspectCandidate(candidateId: Long, radius: Int) {
        if (candidateId <= 0L || !MemoryEngineContract.isInspectRadius(radius) || state.busy) return
        val rowResult = state.results.firstOrNull { it.id == candidateId }
        val rowWatch = state.watches.firstOrNull { it.id == candidateId }
        val type = rowWatch?.type ?: rowResult?.primaryType ?: return
        val label = rowWatch?.label.orEmpty()
        val token = state.runtimeToken
        val engine = service ?: return
        if (token == 0L) return
        state = state.copy(inspectorLoading = true, inspector = null, message = null)
        runIpc {
            val bundle = engine.inspectCandidate(token, candidateId, radius)
            val result = bundle.getInt(
                MemoryEngineContract.KEY_INSPECT_RESULT,
                MemoryEngineContract.RESULT_INVALID_REQUEST,
            )
            val start = bundle.getLong(MemoryEngineContract.KEY_INSPECT_START, 0L)
            val anchor = bundle.getLong(MemoryEngineContract.KEY_INSPECT_ANCHOR, 0L)
            val bytes = bundle.getByteArray(MemoryEngineContract.KEY_INSPECT_BYTES)
            post {
                if (result == MemoryEngineContract.RESULT_TARGET_LOST ||
                    result == MemoryEngineContract.RESULT_NO_SESSION
                ) {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = null,
                        message = null,
                    )
                    close()
                } else if (result == MemoryEngineContract.RESULT_OK && start > 0L && anchor > 0L &&
                    bytes != null && bytes.size <= MemoryEngineContract.MAX_INSPECT_BYTES
                ) {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = MemoryInspectorSnapshot(
                            candidateId = candidateId,
                            type = type,
                            label = label,
                            startAddress = start,
                            anchorAddress = anchor,
                            bytes = bytes,
                        ),
                    )
                } else {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = null,
                        message = operationMessage(result, bundle.getString(MemoryEngineContract.KEY_MESSAGE)),
                    )
                }
            }
        }
    }

    override fun closeInspector() {
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
        if (snapshot.candidateId != anchorCandidateId || !MemoryEngineContract.isCandidateType(type)) return
        val radius = maxOf(
            snapshot.anchorAddress - snapshot.startAddress,
            snapshot.startAddress + snapshot.bytes.size - snapshot.anchorAddress,
        ).toInt().coerceIn(1, MemoryEngineContract.MAX_INSPECT_RADIUS)
        if (!canLaunchOperation()) return
        pendingInspectorRefresh = PendingInspectorRefresh(anchorCandidateId, radius)
        launchOperation { engine, token ->
            engine.editInspectorValue(
                token,
                anchorCandidateId,
                relativeOffset,
                type,
                expectedBits,
                replacementValue.trim(),
            )
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
        state = state.copy(pageOffset = 0, selected = emptySet(), inspector = null)
        launchOperation(searching = true) { engine, token ->
            engine.startNearbySearch(
                token,
                anchorCandidateId,
                radius,
                type,
                predicate,
                value.trim(),
                secondValue.trim(),
            )
        }
    }

    private fun canLaunchOperation(): Boolean {
        if (state.busy || destroyed || !runtimeStillActive()) return false
        if (state.runtimeToken == 0L || service == null) {
            refreshCapabilities()
            return false
        }
        return true
    }

    private fun launchOperation(
        searching: Boolean = false,
        feedback: PendingOperationFeedback? = null,
        operation: (IMemoryEngineService, Long) -> Long,
    ) {
        if (state.busy || destroyed || !runtimeStillActive()) return
        val token = state.runtimeToken
        val engine = service
        if (token == 0L || engine == null) {
            refreshCapabilities()
            return
        }
        val generation = ++operationGeneration
        pendingOperationFeedback = feedback
        activeOperationId = 0L
        state = state.copy(
            busy = true,
            searching = searching,
            scanBytesScanned = 0L,
            scanBytesTotal = 0L,
            message = null,
        )
        runIpc {
            val operationId = operation(engine, token)
            post {
                // If completion won the race, it already advanced operationGeneration.
                if (generation == operationGeneration && state.busy && activeOperationId == 0L) {
                    activeOperationId = operationId
                }
            }
        }
    }

    private fun operationMessage(result: Int, engineMessage: String?): String = when (result) {
        MemoryEngineContract.RESULT_CANCELLED -> context.getString(R.string.memory_editor_cancelled)
        MemoryEngineContract.RESULT_RESOURCE_LIMIT -> context.getString(R.string.memory_editor_resource_limit)
        MemoryEngineContract.RESULT_TARGET_LOST,
        MemoryEngineContract.RESULT_NO_SESSION -> context.getString(R.string.memory_editor_engine_reconnecting)
        MemoryEngineContract.RESULT_IDENTITY_UNSAFE -> context.getString(R.string.memory_editor_identity_unsafe)
        MemoryEngineContract.RESULT_SAFETY_LIMIT -> context.getString(R.string.memory_editor_safety_limit)
        MemoryEngineContract.RESULT_GC_REVALIDATED -> context.getString(R.string.memory_editor_gc_revalidated)
        MemoryEngineContract.RESULT_GC_RACE -> context.getString(R.string.memory_editor_gc_race)
        MemoryEngineContract.RESULT_GC_BASELINE_INVALIDATED ->
            context.getString(R.string.memory_editor_gc_baseline_invalidated)
        MemoryEngineContract.RESULT_UNSUPPORTED -> context.getString(R.string.memory_editor_unsupported)
        MemoryEngineContract.RESULT_PARTIAL_WRITE -> engineMessage
            ?: context.getString(R.string.memory_editor_identity_unsafe)
        else -> engineMessage?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.memory_editor_invalid_request)
    }

    private fun runIpc(block: () -> Unit) {
        if (destroyed) return
        try {
            ipc.execute {
                try {
                    block()
                } catch (_: RemoteException) {
                    disconnected()
                } catch (_: SecurityException) {
                    disconnected()
                }
            }
        } catch (_: RejectedExecutionException) {
            // Activity/runtime teardown won the race.
        }
    }

    private fun post(block: () -> Unit) {
        if (!destroyed) composeView.post { if (!destroyed) block() }
    }

    internal companion object {
        const val PAGE_SIZE = MemoryEngineContract.MAX_RESULT_PAGE_SIZE
        private const val ENGINE_RECONNECT_DELAY_MS = 250L
        private val CAPABILITY_RETRY_DELAYS_MS = longArrayOf(80L, 160L, 320L, 640L)
    }
}
