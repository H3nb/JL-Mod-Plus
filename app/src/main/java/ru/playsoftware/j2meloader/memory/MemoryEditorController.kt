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
import android.widget.Toast
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Presentation controller hosted in :memory_engine. The MIDlet process owns the target-side
 * Managed Java engine; Compose state and interaction allocations stay beside this coordinator.
 */
internal class MemoryEditorComposeController(
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
    private var liveRefreshPending = false
    private var visiblePageRequestPending = false
    private var visiblePageRequestGeneration = 0L
    // Modified: closing/replacing Inspector invalidates asynchronous read replies.
    private var inspectorRequestGeneration = 0L
    private var visiblePageRequestInFlightGeneration = 0L
    private val stateRequestGeneration = AtomicLong()
    private val liveRefreshRunnable = Runnable(::runLiveRefreshTick)

    private data class PendingEditFollowUp(
        val id: Long,
        val type: Int,
        val value: String,
        val addToWatch: Boolean,
        val freezeAfter: Boolean,
        val resultGroup: Boolean,
        val expectedRevision: Long,
    )
    private data class PendingInspectorRefresh(
        val candidateId: Long,
        val radius: Int,
        val watchAnchor: Boolean,
    )
    private data class PendingOperationFeedback(
        val kind: OperationFeedbackKind,
        val resultCountBefore: Long,
    )
    private enum class OperationFeedbackKind { NEXT_SCAN }

    private val callback = object : IMemoryEngineCallback.Stub() {
        override fun onOperationProgress(
            operationId: Long,
            scannedBytes: Long,
            totalBytes: Long,
            searchOperation: Boolean,
        ) {
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
            searchOperation: Boolean,
        ) {
            post {
                if (passiveRefresh) {
                    liveRefreshPending = false
                    when {
                        resultCode == MemoryEngineContract.RESULT_TARGET_LOST -> close()
                        resultCode == MemoryEngineContract.RESULT_OK && state.visible && !state.busy ->
                            reloadVisibleRows()
                        state.visible && !state.busy ->
                            // A stale page/session can race navigation or search teardown. Reconcile
                            // metadata only on this exceptional path; normal live ticks never pay for
                            // capabilities, session info, result count, and both page fetches.
                            reloadState()
                    }
                    return@post
                }

                // Binder can finish a tiny refine before the main-thread operation-id post.
                // Accept that first completion while busy, then invalidate the delayed id post.
                if (!state.busy && activeOperationId == 0L) return@post
                if (activeOperationId != 0L && operationId != activeOperationId) return@post

                val feedback = pendingOperationFeedback
                pendingOperationFeedback = null
                operationGeneration++

                if (resultCode == MemoryEngineContract.RESULT_TARGET_LOST) {
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
                if (succeeded && searchOperation) showSearchCompleteToast(resultCount)
                val editFollowUp = if (succeeded) pendingEditFollowUp else null
                val inspectorFollowUp = if (succeeded) pendingInspectorRefresh else null
                pendingEditFollowUp = null
                pendingInspectorRefresh = null
                activeOperationId = 0L
                val engineSuccessMessage = message?.takeIf(String::isNotBlank)
                val uiSuccessMessage = if (succeeded && feedback?.kind == OperationFeedbackKind.NEXT_SCAN) {
                    "${context.getString(R.string.memory_editor_next_scan)}: ${feedback.resultCountBefore} → $resultCount"
                } else null
                val displayMessage = when {
                    !succeeded -> operationMessage(resultCode, message)
                    uiSuccessMessage != null -> uiSuccessMessage
                    searchOperation -> null
                    else -> engineSuccessMessage
                }
                state = state.copy(
                    busy = false,
                    searching = false,
                    scanBytesScanned = 0L,
                    scanBytesTotal = 0L,
                    resultCount = resultCount,
                    message = displayMessage,
                    messageIsError = !succeeded && displayMessage != null,
                )

                when {
                    editFollowUp != null -> completeEditFlow(editFollowUp)
                    inspectorFollowUp != null -> {
                        reloadState()
                        inspectCandidate(
                            inspectorFollowUp.candidateId,
                            inspectorFollowUp.radius,
                            inspectorFollowUp.watchAnchor,
                        )
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
        composeView.removeCallbacks(liveRefreshRunnable)
        composeView.postDelayed(liveRefreshRunnable, LIVE_REFRESH_INITIAL_DELAY_MS)
    }

    override fun close() {
        if (destroyed) return
        inspectorRequestGeneration++
        composeView.removeCallbacks(liveRefreshRunnable)
        liveRefreshPending = false
        stateRequestGeneration.incrementAndGet()
        visiblePageRequestGeneration++
        visiblePageRequestPending = false
        visiblePageRequestInFlightGeneration = 0L
        state = state.copy(visible = false, selected = emptySet())
        composeView.visibility = View.GONE
        closeHost()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        connectionGeneration++
        stateRequestGeneration.incrementAndGet()
        composeView.removeCallbacks(liveRefreshRunnable)
        liveRefreshPending = false
        visiblePageRequestGeneration++
        visiblePageRequestPending = false
        visiblePageRequestInFlightGeneration = 0L
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
            liveRefreshPending = false
            stateRequestGeneration.incrementAndGet()
            visiblePageRequestGeneration++
            visiblePageRequestPending = false
            visiblePageRequestInFlightGeneration = 0L
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
            post {
                if (state.visible && ownedRuntimeToken == 0L) close()
            }
            return@runIpc
        }

        val engine = service ?: run {
            post { scheduleReconnect() }
            return@runIpc
        }
        val stateRequest = stateRequestGeneration.incrementAndGet()
        val connection = connectionGeneration
        val capabilities = engine.capabilities
        val supported = capabilities.getBoolean(MemoryEngineContract.KEY_SUPPORTED, false) ||
            capabilities.getBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, false)
        val writeSupported = capabilities.getBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED, false) ||
            capabilities.getBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED, false)
        val revision = capabilities.getLong(
            MemoryEngineContract.KEY_MANAGED_REVISION, 0L,
        )
        val baselineCount = capabilities.getLong(
            MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT, 0L,
        )
        val token = capabilities.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, 0L)
        val capabilityMessage = capabilities.getString(MemoryEngineContract.KEY_MESSAGE)

        // The target bridge binds asynchronously. Token 0 is transient/unknown here; runtime
        // teardown has a dedicated lifecycle callback and is the authoritative close signal.
        // A different nonzero token, however, is a genuinely different MIDlet generation.
        if (token != localToken) {
            if (token == 0L) {
                val delay = if (retry < CAPABILITY_RETRY_DELAYS_MS.size) {
                    CAPABILITY_RETRY_DELAYS_MS[retry]
                } else {
                    CAPABILITY_RETRY_IDLE_MS
                }
                val generation = connectionGeneration
                post {
                    if (!state.visible || service !== engine || connection != connectionGeneration ||
                        stateRequest != stateRequestGeneration.get()
                    ) return@post
                    state = state.copy(
                        connecting = true,
                        connected = true,
                        message = capabilityMessage?.takeIf(String::isNotBlank)
                            ?: context.getString(R.string.memory_editor_engine_reconnecting),
                    )
                    composeView.postDelayed({
                        if (!destroyed && state.visible && generation == connectionGeneration &&
                            service === engine && stateRequest == stateRequestGeneration.get()
                        ) {
                            reloadState((retry + 1).coerceAtMost(CAPABILITY_RETRY_DELAYS_MS.size))
                        }
                    }, delay)
                }
            } else {
                post {
                    if (!state.visible || service !== engine || connection != connectionGeneration ||
                        stateRequest != stateRequestGeneration.get()
                    ) return@post
                    close()
                }
            }
            return@runIpc
        }

        if (!supported) {
            post {
                if (!state.visible || service !== engine || connection != connectionGeneration ||
                    stateRequest != stateRequestGeneration.get()
                ) return@post
                state = state.copy(
                    connecting = false,
                    connected = true,
                    supported = false,
                    writeSupported = false,
                    revision = revision,
                    baselineCount = baselineCount,
                    runtimeToken = token,
                    results = emptyList(),
                    watches = emptyList(),
                    resultCount = 0L,
                    unknownPredicate = if (token == 0L) {
                        MemoryEngineContract.PREDICATE_CHANGED
                    } else {
                        MemoryEditorRuntimePreferences.unknownPredicate(token)
                    },
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
        val canUndo = session.getInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH, 0) > 0
        val resultCount = engine.getResultCount(token)
        val requestedOffset = state.pageOffset
        val maxOffset = if (resultCount <= 0L) 0 else {
            (((resultCount - 1L) / PAGE_SIZE) * PAGE_SIZE)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val pageOffset = requestedOffset.coerceIn(0, maxOffset)
        val results = if (stage != MemorySessionStage.EMPTY) {
            MemoryResultPageParser.parse(engine.getResultPage(token, pageOffset, PAGE_SIZE))
        } else {
            emptyList()
        }
        val watches = MemoryWatchPageParser.parse(engine.getWatchPage(token))

        post {
            if (!state.visible || service !== engine || connection != connectionGeneration ||
                stateRequest != stateRequestGeneration.get()
            ) return@post
            val validIds = buildSet {
                results.forEach { add(it.id) }
                watches.forEach { add(it.id) }
            }
            state = state.copy(
                connecting = false,
                connected = true,
                supported = true,
                writeSupported = writeSupported,
                revision = session.getLong(
                    MemoryEngineContract.KEY_MANAGED_REVISION, revision,
                ),
                baselineCount = session.getLong(
                    MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT, baselineCount,
                ),
                runtimeToken = token,
                resultCount = resultCount,
                pageOffset = pageOffset,
                results = results,
                watches = watches,
                selected = state.selected.filterTo(mutableSetOf()) { it in validIds },
                searchMode = mode,
                sessionStage = stage,
                requestedType = requestedType,
                unknownPredicate = MemoryEditorRuntimePreferences.unknownPredicate(token),
                canUndo = canUndo,
                message = capabilityMessage?.takeIf(String::isNotBlank) ?: state.message,
            )
        }
    }

    /** Reads only the visible page; managed reads are getters and never refresh search baselines. */
    private fun reloadVisibleRows() {
        if (destroyed || visiblePageRequestPending) return
        val token = state.runtimeToken
        val engine = service
        if (token == 0L || engine == null) return
        val watchTab = state.watchTab
        val pageOffset = state.pageOffset
        val sessionStage = state.sessionStage
        val revision = state.revision
        val requestGeneration = ++visiblePageRequestGeneration
        val connection = connectionGeneration
        visiblePageRequestPending = true
        visiblePageRequestInFlightGeneration = requestGeneration
        runIpc {
            var completionPosted = false
            try {
                val resultBundle = if (!watchTab && sessionStage != MemorySessionStage.EMPTY) {
                    engine.getResultPage(token, pageOffset, PAGE_SIZE)
                } else {
                    null
                }
                val pageRevision = if (!watchTab && sessionStage != MemorySessionStage.EMPTY) {
                    resultBundle?.getLong(MemoryEngineContract.KEY_MANAGED_REVISION, Long.MIN_VALUE)
                } else null
                val results = MemoryResultPageParser.parse(resultBundle)
                val watches = if (watchTab) {
                    MemoryWatchPageParser.parse(engine.getWatchPage(token))
                } else {
                    emptyList()
                }
                post {
                    val isInFlight = visiblePageRequestInFlightGeneration == requestGeneration
                    if (isInFlight) {
                        visiblePageRequestPending = false
                        visiblePageRequestInFlightGeneration = 0L
                    }
                    if (!isInFlight || requestGeneration != visiblePageRequestGeneration ||
                        service !== engine || connection != connectionGeneration
                    ) return@post
                    if (!state.visible || state.busy || state.runtimeToken != token ||
                        state.watchTab != watchTab || state.pageOffset != pageOffset ||
                        state.sessionStage != sessionStage || state.revision != revision ||
                        (pageRevision != null && pageRevision != revision)
                    ) return@post
                    if (watchTab) {
                        val validIds = watches.mapTo(mutableSetOf(), MemoryWatchRow::id)
                        state = state.copy(
                            watches = watches,
                            selected = state.selected.filterTo(mutableSetOf()) { it in validIds },
                        )
                    } else {
                        val validIds = results.mapTo(mutableSetOf(), MemoryResultRow::id)
                        state = state.copy(
                            results = results,
                            selected = state.selected.filterTo(mutableSetOf()) { it in validIds },
                        )
                    }
                }
                completionPosted = true
            } finally {
                if (!completionPosted) {
                    post {
                        if (visiblePageRequestInFlightGeneration == requestGeneration) {
                            visiblePageRequestPending = false
                            visiblePageRequestInFlightGeneration = 0L
                        }
                    }
                }
            }
        }
    }

    override fun startSearch(
        value: String,
        secondValue: String,
        type: Int,
        predicate: Int,
        unknown: Boolean,
    ) {
        invalidateVisiblePageRequest()
        state = state.copy(pageOffset = 0, selected = emptySet(), inspector = null)
        launchOperation(searching = true) { engine, token ->
            if (unknown) {
                engine.startUnknownSearch(token, type)
            } else {
                engine.startKnownSearch(
                    token,
                    type,
                    predicate.coerceAtMost(MemoryEngineContract.PREDICATE_BETWEEN),
                    value.trim(),
                    secondValue.trim(),
                )
            }
        }
    }

    override fun setUnknownSearchPredicate(predicate: Int) {
        if (memoryUnknownPredicateOrDefault(predicate) != predicate) return
        val token = state.runtimeToken
        if (token == 0L) return
        MemoryEditorRuntimePreferences.setUnknownPredicate(token, predicate)
        state = state.copy(
            unknownPredicate = MemoryEditorRuntimePreferences.unknownPredicate(token),
        )
    }

    override fun groupSearch(type: Int, values: Array<String>) {
        invalidateVisiblePageRequest()
        state = state.copy(pageOffset = 0, selected = emptySet(), inspector = null)
        launchOperation(searching = true) { engine, token ->
            engine.startGroupSearch(token, type, values)
        }
    }

    override fun nextScan(
        value: String,
        secondValue: String,
        predicate: Int,
        compare: Int,
        type: Int,
    ) {
        invalidateVisiblePageRequest()
        state = state.copy(pageOffset = 0, selected = emptySet(), inspector = null)
        launchOperation(
            searching = true,
            feedback = PendingOperationFeedback(OperationFeedbackKind.NEXT_SCAN, state.resultCount),
        ) { engine, token ->
            if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) {
                engine.refineRelative(token, type, predicate, compare,
                    value.trim(), secondValue.trim())
            } else {
                engine.refineKnown(token, type, predicate,
                    value.trim(), secondValue.trim())
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
        launchOperation { engine, token -> engine.refreshCandidates(token, ids, false) }
    }

    override fun setWatchTab(watch: Boolean) {
        if (state.watchTab == watch) return
        invalidateVisiblePageRequest()
        state = state.copy(watchTab = watch, selected = emptySet())
    }

    override fun toggleSelection(id: Long) {
        val selectionLimit = if (state.watchTab) MemoryEngineContract.MAX_WATCH_RECORDS
        else MemoryEngineContract.MAX_RESULT_PAGE_SIZE
        if (id !in state.selected && state.selected.size >= selectionLimit) {
            state = state.copy(
                message = if (state.watchTab) {
                    "Watch selection is limited to $selectionLimit rows"
                } else {
                    "Selection is limited to $selectionLimit visible result rows"
                },
                messageIsError = true,
            )
            return
        }
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
                engine.editResults(
                    token, state.revision, longArrayOf(id), type, value.trim(),
                )
            } else {
                engine.editCandidates(token, longArrayOf(id), type, value.trim())
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
        val resultGroup = !state.watchTab
        val target = if (resultGroup) {
            state.results.firstOrNull { it.id == id }?.let { row ->
                MemoryEditTarget(id, row.primaryType, row.valueText, row.aliasTypes)
            }
        } else {
            state.watches.firstOrNull { it.id == id }?.let { row ->
                MemoryEditTarget(id, row.type, row.valueText, listOf(row.type), true)
            }
        } ?: return
        editSingleTargetWithOptions(target, if (resultGroup) state.revision else 0L,
            value, type, addToWatch, freezeAfter)
    }

    override fun editTargetsWithOptions(
        targets: List<MemoryEditTarget>,
        expectedRevision: Long,
        value: String,
        type: Int,
        addToWatch: Boolean,
        freezeAfter: Boolean,
    ) {
        if (targets.size == 1 && (addToWatch || freezeAfter)) {
            editSingleTargetWithOptions(targets.first(), expectedRevision, value, type,
                addToWatch, freezeAfter)
        } else {
            editTargets(targets, expectedRevision, value, type)
        }
    }

    private fun editSingleTargetWithOptions(
        target: MemoryEditTarget,
        expectedRevision: Long,
        value: String,
        type: Int,
        addToWatch: Boolean,
        freezeAfter: Boolean,
    ) {
        if (!canLaunchOperation()) return
        val replacement = value.trim()
        val resultGroup = !target.watch
        pendingEditFollowUp = if (addToWatch || freezeAfter) {
            PendingEditFollowUp(target.id, type, replacement, addToWatch, freezeAfter,
                resultGroup, expectedRevision)
        } else null
        launchOperation { engine, token ->
            if (!resultGroup) {
                engine.editCandidates(token, longArrayOf(target.id), type, replacement)
            } else {
                engine.editResults(token, expectedRevision, longArrayOf(target.id),
                    type, replacement)
            }
        }
    }

    private fun completeEditFlow(followUp: PendingEditFollowUp) {
        when {
            followUp.freezeAfter && followUp.resultGroup -> launchOperation { engine, token ->
                engine.setFreezeResults(
                    token,
                    followUp.expectedRevision,
                    longArrayOf(followUp.id),
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
                engine.addWatchResults(
                    token, followUp.expectedRevision, longArrayOf(followUp.id),
                )
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
                engine.filterResultGroups(token, state.revision, ids, keep)
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

    override fun copySelected(locations: Boolean) {
        val selected = state.selected
        val rows = if (state.watchTab) {
            state.watches.filter { it.id in selected }.map {
                if (locations) it.locationText else it.valueText
            }
        } else {
            state.results.filter { it.id in selected }.map {
                if (locations) it.locationText else it.valueText
            }
        }
        if (rows.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText("Memory Editor", rows.joinToString("\n")),
        )
    }

    override fun previousPage() {
        if (state.pageOffset <= 0) return
        invalidateVisiblePageRequest()
        state = state.copy(pageOffset = (state.pageOffset - PAGE_SIZE).coerceAtLeast(0), selected = emptySet())
        reloadState()
    }

    override fun nextPage() {
        if (state.pageOffset.toLong() + PAGE_SIZE >= state.resultCount) return
        invalidateVisiblePageRequest()
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
        invalidateVisiblePageRequest()
        closeInspector()
        runIpc {
            engine.clearSearch(token)
            post {
                state = state.copy(
                    pageOffset = 0,
                    resultCount = 0L,
                    baselineCount = 0L,
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

    override fun inspectCandidate(candidateId: Long, radius: Int, watchAnchor: Boolean) {
        if (candidateId <= 0L || !MemoryEngineContract.isInspectRadius(radius) || state.busy) return
        val rowResult = state.results.firstOrNull { it.id == candidateId }
        val rowWatch = state.watches.firstOrNull { it.id == candidateId }
        if (rowWatch == null && rowResult == null) return
        val token = state.runtimeToken
        val engine = service ?: return
        if (token == 0L) return
        val request = ++inspectorRequestGeneration
        val connection = connectionGeneration
        val revision = state.revision
        val operation = operationGeneration
        state = state.copy(inspectorLoading = true, inspector = null, message = null, messageIsError = false)
        runIpc {
            val bundle = engine.inspectCandidate(token, candidateId, radius, watchAnchor)
            val result = bundle.getInt(
                MemoryEngineContract.KEY_INSPECT_RESULT,
                MemoryEngineContract.RESULT_INVALID_REQUEST,
            )
            val logicalIds = bundle.getLongArray(MemoryEngineContract.KEY_INSPECT_IDS)
            val logicalValues = bundle.getStringArray(MemoryEngineContract.KEY_INSPECT_VALUES)
            val logicalInitial = bundle.getStringArray(MemoryEngineContract.KEY_INSPECT_INITIAL_VALUES)
            val logicalPrevious = bundle.getStringArray(MemoryEngineContract.KEY_INSPECT_PREVIOUS_VALUES)
            val logicalTypes = bundle.getIntArray(MemoryEngineContract.KEY_INSPECT_TYPES)
            val logicalStates = bundle.getIntArray(MemoryEngineContract.KEY_INSPECT_STATES)
            val logicalOffsets = bundle.getIntArray(MemoryEngineContract.KEY_INSPECT_RELATIVE_OFFSETS)
            val logicalExpected = bundle.getLongArray(MemoryEngineContract.KEY_INSPECT_EXPECTED_BITS)
            val logicalLabels = bundle.getStringArray(MemoryEngineContract.KEY_INSPECT_LABELS)
            val logicalEditable = bundle.getBooleanArray(MemoryEngineContract.KEY_INSPECT_EDITABLE)
            val logicalRows = if (logicalIds != null && logicalValues != null && logicalInitial != null &&
                logicalPrevious != null && logicalTypes != null && logicalStates != null &&
                logicalOffsets != null && logicalExpected != null && logicalLabels != null &&
                logicalEditable != null && listOf(logicalValues.size, logicalInitial.size,
                    logicalPrevious.size, logicalTypes.size, logicalStates.size, logicalOffsets.size,
                    logicalExpected.size, logicalLabels.size,
                    logicalEditable.size).all { it == logicalIds.size } &&
                logicalIds.size <= MemoryEngineContract.MAX_RESULT_PAGE_SIZE &&
                logicalIds.indices.all { index ->
                    ManagedJavaMemoryIds.hasValidNamespace(logicalIds[index]) &&
                        ManagedJavaMemoryIds.ownerHandle(logicalIds[index]) ==
                        ManagedJavaMemoryIds.ownerHandle(candidateId) &&
                        MemoryEngineContract.isCandidateType(logicalTypes[index])
                } && logicalIds.contains(candidateId)
            ) {
                logicalIds.indices.map { index ->
                    MemoryInspectorLogicalRow(logicalIds[index], logicalOffsets[index], logicalTypes[index],
                        logicalStates[index], logicalValues[index], logicalInitial[index],
                        logicalPrevious[index], logicalLabels[index], logicalExpected[index],
                        logicalEditable[index])
                }
            } else emptyList()
            post {
                if (request != inspectorRequestGeneration || connection != connectionGeneration ||
                    service !== engine || state.runtimeToken != token || !state.visible
                ) return@post
                if (operation != operationGeneration ||
                    (!watchAnchor && state.revision != revision) ||
                    (!watchAnchor && logicalRows.isNotEmpty() && bundle.getLong(
                        MemoryEngineContract.KEY_INSPECT_EXPECTED_REVISION, 0L,
                    ) != revision)
                ) {
                    closeInspector()
                    return@post
                }
                if (result == MemoryEngineContract.RESULT_TARGET_LOST) {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = null,
                        message = null,
                    )
                    close()
                } else if (result == MemoryEngineContract.RESULT_OK && logicalRows.isNotEmpty()) {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = MemoryInspectorSnapshot(
                            candidateId = candidateId,
                            type = logicalRows.first().type,
                            label = logicalRows.firstOrNull { it.relativeOffset == 0 }?.label.orEmpty(),
                            logicalRows = logicalRows,
                            expectedRevision = bundle.getLong(
                                MemoryEngineContract.KEY_INSPECT_EXPECTED_REVISION, 0L,
                            ),
                            provenance = bundle.getString(MemoryEngineContract.KEY_INSPECT_PROVENANCE).orEmpty(),
                            watchAnchor = watchAnchor,
                        ),
                    )
                } else {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = null,
                        message = operationMessage(result, bundle.getString(MemoryEngineContract.KEY_MESSAGE)),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    override fun closeInspector() {
        inspectorRequestGeneration++
        state = state.copy(inspectorLoading = false, inspector = null)
    }

    override fun editInspectorValue(
        anchorCandidateId: Long,
        relativeOffset: Int,
        type: Int,
        expectedBits: Long,
        replacementValue: String,
        watchAnchor: Boolean,
    ) {
        val snapshot = state.inspector ?: return
        if (snapshot.candidateId != anchorCandidateId || !MemoryEngineContract.isCandidateType(type)) return
        if (!canLaunchOperation()) return
        pendingInspectorRefresh = PendingInspectorRefresh(
            anchorCandidateId,
            MemoryEngineContract.DEFAULT_INSPECT_RADIUS,
            watchAnchor,
        )
        launchOperation { engine, token ->
            engine.editInspectorValue(
                token,
                anchorCandidateId,
                relativeOffset,
                type,
                expectedBits,
                replacementValue.trim(),
                watchAnchor,
                if (watchAnchor) 0L else snapshot.expectedRevision,
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
        invalidateVisiblePageRequest()
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

    private fun runLiveRefreshTick() {
        if (destroyed || !state.visible) return
        if (!state.busy && !liveRefreshPending && !visiblePageRequestPending && state.inspector == null) {
            if (state.runtimeToken != 0L && service != null) reloadVisibleRows()
        }
        if (!destroyed && state.visible) {
            composeView.postDelayed(liveRefreshRunnable, LIVE_REFRESH_INTERVAL_MS)
        }
    }

    override fun editTargets(
        targets: List<MemoryEditTarget>,
        expectedRevision: Long,
        value: String,
        type: Int,
    ) {
        val snapshot = targets.toList()
        val watch = snapshot.all(MemoryEditTarget::watch)
        val actionLimit = if (watch) MemoryEngineContract.MAX_WATCH_RECORDS
        else MemoryEngineContract.MAX_RESULT_PAGE_SIZE
        if (snapshot.isEmpty() || snapshot.size > actionLimit) return
        val eligible = if (watch) snapshot.filter { it.type == type } else snapshot
        if (watch && eligible.isEmpty()) {
            state = state.copy(
                message = context.getString(R.string.memory_editor_edit_batch_no_common_type),
                messageIsError = true,
            )
            return
        }
        val ids = eligible.map(MemoryEditTarget::id).toLongArray()
        if (!watch && snapshot.none { type in it.aliasTypes }) {
            state = state.copy(
                message = context.getString(R.string.memory_editor_edit_batch_no_common_type),
                messageIsError = true,
            )
            return
        }
        launchOperation { engine, token ->
            if (watch) {
                engine.editCandidates(token, ids, type, value.trim())
            } else {
                engine.editResults(token, expectedRevision, ids, type, value.trim())
            }
        }
    }

    /** Invalidates the apply side of an in-flight page read without creating a second request. */
    private fun invalidateVisiblePageRequest() {
        visiblePageRequestGeneration++
    }

    private fun showSearchCompleteToast(resultCount: Long) {
        val count = resultCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        Toast.makeText(
            context,
            context.resources.getQuantityString(
                R.plurals.memory_editor_search_complete,
                count,
                resultCount,
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun operationMessage(result: Int, engineMessage: String?): String = when (result) {
        MemoryEngineContract.RESULT_CANCELLED -> context.getString(R.string.memory_editor_cancelled)
        MemoryEngineContract.RESULT_RESOURCE_LIMIT -> context.getString(R.string.memory_editor_resource_limit)
        MemoryEngineContract.RESULT_TARGET_LOST -> context.getString(R.string.memory_editor_engine_reconnecting)
        MemoryEngineContract.RESULT_NO_SESSION -> engineMessage?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.memory_editor_invalid_request)
        MemoryEngineContract.RESULT_IDENTITY_UNSAFE -> context.getString(R.string.memory_editor_identity_unsafe)
        MemoryEngineContract.RESULT_SAFETY_LIMIT -> context.getString(R.string.memory_editor_safety_limit)
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
        private const val LIVE_REFRESH_INITIAL_DELAY_MS = 100L
        private const val LIVE_REFRESH_INTERVAL_MS = 250L
        private const val CAPABILITY_RETRY_IDLE_MS = 1_000L
        private val CAPABILITY_RETRY_DELAYS_MS = longArrayOf(80L, 160L, 320L, 640L)
    }
}
