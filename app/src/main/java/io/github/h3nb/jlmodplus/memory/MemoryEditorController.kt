/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.memory

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.os.RemoteException
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.crashes.MidletSessionStore
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
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
    private val systemBars: MemoryEditorSystemBars? = null,
    private val closeHost: () -> Unit,
) : MemoryEditorActions {
    private val context = composeView.context
    private val ipc: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MemoryEditorUiIpc").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var state by mutableStateOf(MemoryEditorUiState())
    private var service: IMemoryEngineService? = null
    private var savedAuthority: ISavedVariablesAuthority? = null
    private var savedBound = false
    private var savedIdentity: MidletSessionStore.State? = null
    @Volatile private var savedGeneration = 0L
    private val savedConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            savedAuthority = ISavedVariablesAuthority.Stub.asInterface(binder)
            if (state.visible && state.savedTab) refreshSavedVariables()
        }
        override fun onServiceDisconnected(name: ComponentName) { savedAuthority = null }
        override fun onBindingDied(name: ComponentName) { savedAuthority = null }
        override fun onNullBinding(name: ComponentName) { savedAuthority = null }
    }
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
    // Closing or replacing Inspector invalidates asynchronous read replies.
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
    private enum class OperationFeedbackKind { NEXT_SCAN, REVERT_REMOVE }

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
            details: Bundle?,
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
                val operationFeedbackMessage = operationMessage(resultCode, details)
                val uiSuccessMessage = when {
                    succeeded && feedback?.kind == OperationFeedbackKind.NEXT_SCAN ->
                        context.getString(
                            R.string.memory_editor_next_scan_result,
                            feedback.resultCountBefore, resultCount,
                        )
                    feedback?.kind == OperationFeedbackKind.REVERT_REMOVE &&
                        details != null &&
                        details.getInt(MemoryEngineContract.KEY_OPERATION_REASON) !=
                            MemoryEngineContract.REASON_REVERT_REMOVE_COMMIT_FAILED &&
                        (succeeded || resultCode == MemoryEngineContract.RESULT_PARTIAL_WRITE) ->
                        context.getString(
                            R.string.memory_editor_revert_remove_outcome,
                            details.getInt(MemoryEngineContract.KEY_MANAGED_WRITTEN),
                            (feedback.resultCountBefore - resultCount).coerceAtLeast(0),
                            details.getInt(MemoryEngineContract.KEY_MANAGED_SKIPPED),
                            details.getInt(MemoryEngineContract.KEY_MANAGED_UNCONFIRMED),
                            details.getInt(MemoryEngineContract.KEY_MANAGED_NOT_ATTEMPTED),
                        )
                    else -> null
                }
                val displayMessage = when {
                    feedback?.kind == OperationFeedbackKind.REVERT_REMOVE && uiSuccessMessage != null ->
                        uiSuccessMessage
                    !succeeded -> operationFeedbackMessage
                    uiSuccessMessage != null -> uiSuccessMessage
                    searchOperation -> null
                    else -> operationFeedbackMessage
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
            CompositionLocalProvider(LocalMemoryEditorSystemBars provides systemBars) {
                JLModPlusTheme(paintWindowBackground = false) {
                    MemoryEditorRuntimeRoot(state = state, actions = this)
                }
            }
        }
    }

    fun open() {
        if (destroyed || ownedRuntimeToken == 0L) return
        if (savedIdentity == null) savedIdentity = MidletSessionStore.read(context)
        if (!savedBound) {
            savedBound = context.bindService(Intent(context, SavedVariablesAuthority::class.java),
                savedConnection, Context.BIND_AUTO_CREATE)
        }
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
        savedGeneration++
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
        savedGeneration++
        savedAuthority = null
        if (savedBound) {
            runCatching { context.unbindService(savedConnection) }
            savedBound = false
        }
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
                supported = false,
                writeSupported = false,
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
        val reconversionRequired = capabilities.getBoolean(
            MemoryEngineContract.KEY_MANAGED_RECONVERSION_REQUIRED, false,
        )
        val capabilityMessage = if (reconversionRequired) {
            context.getString(R.string.memory_editor_reconversion_required)
        } else {
            operationMessage(
                capabilities.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT, MemoryEngineContract.RESULT_OK),
                capabilities,
            )
        }

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
                        message = context.getString(R.string.memory_editor_engine_reconnecting),
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
                // A loaded runtime token can precede the MIDlet constructor publishing its root.
                // Retry that transient gap, but do not retry a known incompatible conversion.
                val retrying = !reconversionRequired && retry < CAPABILITY_RETRY_DELAYS_MS.size
                state = state.copy(
                    connecting = retrying,
                    connected = true,
                    supported = false,
                    writeSupported = false,
                    messageIsError = !retrying,
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
                    message = if (retrying) context.getString(R.string.memory_editor_engine_reconnecting)
                        else capabilityMessage ?: context.getString(R.string.memory_editor_unsupported),
                )
                if (retrying) composeView.postDelayed({
                    if (!destroyed && state.visible && service === engine &&
                        connection == connectionGeneration && stateRequest == stateRequestGeneration.get()
                    ) reloadState(retry + 1)
                }, CAPABILITY_RETRY_DELAYS_MS[retry])
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
                messageIsError = false,
            )
            if (state.savedTab && state.savedVariables.isNotEmpty()
                && state.savedValues.isEmpty() && savedAuthority != null) refreshSavedVariables()
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
        val input = memoryNextScanInputForPredicate(predicate, value, secondValue)
        invalidateVisiblePageRequest()
        state = state.copy(pageOffset = 0, selected = emptySet(), inspector = null)
        launchOperation(
            searching = true,
            feedback = PendingOperationFeedback(OperationFeedbackKind.NEXT_SCAN, state.resultCount),
        ) { engine, token ->
            if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) {
                engine.refineRelative(token, type, predicate, compare,
                    input.first, input.second)
            } else {
                engine.refineKnown(token, type, predicate,
                    input.first, input.second)
            }
        }
    }

    override fun undo() {
        launchOperation { engine, token -> engine.undoSearch(token) }
    }

    /** Refresh rereads visible logical identities; collected owners remain LOST and are not rebound. */
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


    override fun setSavedTab() {
        closeInspector()
        if (state.savedTab) return
        invalidateVisiblePageRequest()
        state = state.copy(watchTab = false, savedTab = true, selected = emptySet())
        refreshSavedVariables()
    }

    private fun savedValue(value: Bundle, bound: Boolean = false): SavedVariableRuntimeValue {
        val code = value.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
            MemoryEngineContract.RESULT_TARGET_LOST)
        return SavedVariableRuntimeValue(
            code = code,
            type = value.getInt("savedValueType", MemoryEngineContract.TYPE_AUTO),
            value = value.getString("savedValue").orEmpty(),
            detail = value.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL).orEmpty(),
            changedOwner = bound && code == MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
        )
    }

    @Suppress("DEPRECATION")
    private fun savedRows(response: Bundle): List<Pair<SavedVariableRow, Bundle>> {
        if (!response.getBoolean("ok", false)) {
            throw IllegalStateException(response.getString("error") ?: "Saved storage is unavailable")
        }
        return response.getParcelableArrayList<Bundle>("items").orEmpty().map { record ->
            val locator = record.getBundle("locator") ?: error("Saved locator is missing")
            val names = locator.getStringArray("savedNames").orEmpty()
            val indices = locator.getIntArray("savedFieldIndices")
            val description = names.mapIndexed { index, name ->
                name + (indices?.getOrNull(index)?.takeIf { it >= 0 }?.let { "[$it]" } ?: "")
            }.joinToString(" → ") +
                (locator.getInt("savedArrayIndex", -1).takeIf { it >= 0 }?.let { "[$it]" } ?: "")
            SavedVariableRow(record.getString("id").orEmpty(),
                record.getString("label").orEmpty(), description) to locator
        }
    }

    private fun savedTarget(): Triple<String, Long, String> {
        val identity = savedIdentity ?: error("Installed MIDlet identity unavailable")
        val path = identity.appPath ?: error("Installed workdir unavailable")
        val entry = identity.mainClass ?: error("MIDlet entry class unavailable")
        if (identity.appId <= 0L) error("Installed Library ID unavailable")
        return Triple(path, identity.appId, entry)
    }

    override fun refreshSavedVariables() {
        val generation = ++savedGeneration
        val authority = savedAuthority
        val engine = service
        val token = state.runtimeToken
        val connection = connectionGeneration
        if (authority == null) {
            state = state.copy(savedError = "Saved storage is connecting")
            return
        }
        runIpc {
            val records = runCatching {
                val (path, appId, entry) = savedTarget()
                savedRows(authority.list(path, appId, entry))
            }
            post {
                if (destroyed || !state.visible || !state.savedTab ||
                    generation != savedGeneration || connection != connectionGeneration) return@post
                state = if (records.isSuccess) state.copy(
                    savedVariables = records.getOrThrow().map { it.first },
                    savedSelection = state.savedSelection.intersect(
                        records.getOrThrow().map { it.first.id }.toSet()),
                    savedValues = emptyMap(), savedError = null,
                ) else state.copy(savedVariables = emptyList(), savedSelection = emptySet(), savedValues = emptyMap(),
                    savedError = records.exceptionOrNull()?.message ?: "Unable to load Saved definitions")
            }
            if (engine == null || token == 0L) return@runIpc
            val all = records.getOrNull() ?: return@runIpc
            all.chunked(8).forEach { batch ->
                if (generation != savedGeneration) return@runIpc
                val values = batch.associate { (row, locator) ->
                    val observed = runCatching {
                        var value = savedValue(engine.readSaved(token, row.id), bound = true)
                        if (value.code == MemoryEngineContract.RESULT_NO_SESSION) {
                            val attempt = engine.resolveSavedLocator(token, locator, row.id)
                            value = if (attempt.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                                MemoryEngineContract.RESULT_OK
                            ) savedValue(engine.readSaved(token, row.id), bound = true) else savedValue(attempt)
                        }
                        value
                    }.getOrElse { SavedVariableRuntimeValue(MemoryEngineContract.RESULT_TARGET_LOST,
                        detail = it.message.orEmpty()) }
                    row.id to observed
                }
                post {
                    if (destroyed || !state.visible || !state.savedTab ||
                        generation != savedGeneration || connection != connectionGeneration ||
                        service !== engine || state.runtimeToken != token) return@post
                    state = state.copy(savedValues = state.savedValues + values)
                }
            }
        }
    }

    override fun saveSelectedVariable(name: String) {
        val candidate = state.selected.singleOrNull() ?: return
        val token = state.runtimeToken
        val revision = state.revision
        val engine = service ?: return
        val authority = savedAuthority ?: return
        val generation = savedGeneration
        if (state.busy || state.watchTab || state.savedTab || token == 0L) return
        runIpc {
            val outcome = runCatching {
                val locator = engine.captureSavedLocator(token, revision, candidate)
                if (locator.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) !=
                    MemoryEngineContract.RESULT_OK) error("No verified Saved path for this value")
                val (path, appId, entry) = savedTarget()
                savedRows(authority.save(path, appId, entry, name.trim(), locator))
            }
            post {
                if (destroyed || !state.visible || generation != savedGeneration ||
                    service !== engine || state.runtimeToken != token) return@post
                state = state.copy(
                    message = outcome.exceptionOrNull()?.message ?: "Saved variable created",
                    messageIsError = outcome.isFailure)
            }
        }
    }

    override fun resolveSavedVariable(id: String) {
        val engine = service ?: return
        val authority = savedAuthority ?: return
        val token = state.runtimeToken
        val generation = savedGeneration
        if (token == 0L || state.busy) return
        runIpc {
            val value = runCatching {
                val (path, appId, entry) = savedTarget()
                val record = savedRows(authority.list(path, appId, entry))
                    .firstOrNull { it.first.id == id } ?: error("Saved definition missing")
                var current = savedValue(engine.readSaved(token, id), bound = true)
                if (current.code == MemoryEngineContract.RESULT_NO_SESSION) {
                    val attempt = engine.resolveSavedLocator(token, record.second, id)
                    current = if (attempt.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                        MemoryEngineContract.RESULT_OK
                    ) savedValue(engine.readSaved(token, id), bound = true) else savedValue(attempt)
                }
                current
            }.getOrElse { SavedVariableRuntimeValue(MemoryEngineContract.RESULT_TARGET_LOST,
                detail = it.message.orEmpty()) }
            post {
                if (destroyed || generation != savedGeneration || !state.savedTab ||
                    state.runtimeToken != token || service !== engine ||
                    state.savedVariables.none { it.id == id }) return@post
                state = state.copy(savedValues = state.savedValues + (id to value),
                    message = if (value.resolved) null else value.detail,
                    messageIsError = !value.resolved)
            }
        }
    }

    override fun editSavedVariable(id: String, replacement: String) {
        val engine = service ?: return
        val token = state.runtimeToken
        val generation = savedGeneration
        if (token == 0L || state.savedValues[id]?.resolved != true || state.busy) return
        runIpc {
            val value = runCatching { savedValue(engine.editSaved(token, id, replacement.trim()), bound = true) }
                .getOrElse { SavedVariableRuntimeValue(MemoryEngineContract.RESULT_TARGET_LOST,
                    detail = it.message.orEmpty()) }
            post {
                if (destroyed || generation != savedGeneration || !state.savedTab ||
                    state.runtimeToken != token || service !== engine ||
                    state.savedVariables.none { it.id == id }) return@post
                state = state.copy(savedValues = state.savedValues + (id to value),
                    message = if (value.resolved) "Saved value updated" else value.detail,
                    messageIsError = !value.resolved)
            }
        }
    }

    override fun freezeSavedVariable(id: String) {
        if (!state.writeSupported || state.savedValues[id]?.resolved != true || state.busy) return
        val engine = service ?: return
        val token = state.runtimeToken
        val label = state.savedVariables.firstOrNull { it.id == id }?.label ?: return
        val generation = savedGeneration
        runIpc {
            val answer = runCatching { engine.freezeSaved(token, id, label) }
            post {
                if (destroyed || generation != savedGeneration || state.runtimeToken != token
                    || service !== engine) return@post
                val result = answer.getOrNull()
                val code = result?.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT)
                    ?: MemoryEngineContract.RESULT_TARGET_LOST
                state = state.copy(
                    message = if (code == MemoryEngineContract.RESULT_OK)
                        "Saved value added to Watch and frozen"
                    else result?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                        ?: answer.exceptionOrNull()?.message ?: "Unable to freeze Saved variable",
                    messageIsError = code != MemoryEngineContract.RESULT_OK,
                )
                if (code == MemoryEngineContract.RESULT_OK) reloadState()
            }
        }
    }

    override fun renameSavedVariable(id: String, name: String) = mutateSaved(id, name, false)
    override fun deleteSavedVariable(id: String) = deleteSavedVariables(listOf(id))
    override fun deleteSavedVariables(ids: List<String>) {
        val authority = savedAuthority ?: return
        val token = state.runtimeToken
        val engine = service
        val generation = savedGeneration
        val current = state.savedVariables.map { it.id }.toSet()
        val unique = ids.distinct()
        if (unique.isEmpty() || unique.size > 64 || unique.any { it !in current }) return
        runIpc {
            val result = runCatching {
                val (path, appId, entry) = savedTarget()
                val rows = savedRows(authority.removeMany(path, appId, entry, unique.toTypedArray()))
                    .map { it.first }
                if (engine != null && token != 0L) {
                    unique.forEach { engine.releaseSaved(token, it) }
                }
                rows
            }
            post {
                if (destroyed || generation != savedGeneration || !state.savedTab ||
                    state.runtimeToken != token) return@post
                val rows = result.getOrNull()
                state = if (rows != null) state.copy(
                    savedVariables = rows,
                    savedSelection = state.savedSelection - unique.toSet(),
                    savedValues = state.savedValues.filterKeys { id -> rows.any { it.id == id } },
                    message = if (unique.size == 1) "Saved variable removed" else "Saved variables removed",
                    messageIsError = false,
                ) else state.copy(
                    message = result.exceptionOrNull()?.message, messageIsError = true)
            }
        }
    }

    override fun toggleSavedSelection(id: String) {
        if (!state.savedTab || state.savedVariables.none { it.id == id }) return
        state = state.copy(savedSelection = if (id in state.savedSelection)
            state.savedSelection - id else state.savedSelection + id)
    }

    override fun selectAllSaved() {
        state = state.copy(savedSelection = state.savedVariables.map { it.id }.toSet())
    }

    override fun clearSavedSelection() {
        state = state.copy(savedSelection = emptySet())
    }

    private fun mutateSaved(id: String, name: String, remove: Boolean) {
        val authority = savedAuthority ?: return
        val token = state.runtimeToken
        val generation = savedGeneration
        val engine = service
        runIpc {
            val result = runCatching {
                val (path, appId, entry) = savedTarget()
                val result = if (remove) authority.remove(path, appId, entry, id)
                    else authority.rename(path, appId, entry, id, name.trim())
                val rows = savedRows(result).map { it.first }
                if (remove && engine != null && token != 0L) engine.releaseSaved(token, id)
                rows
            }
            post {
                if (destroyed || generation != savedGeneration || !state.savedTab ||
                    state.runtimeToken != token) return@post
                val rows = result.getOrNull()
                state = if (rows != null) state.copy(savedVariables = rows,
                    savedValues = state.savedValues.filterKeys { key -> rows.any { it.id == key } },
                    message = if (remove) "Saved variable removed" else "Saved variable renamed",
                    messageIsError = false)
                else state.copy(message = result.exceptionOrNull()?.message, messageIsError = true)
            }
        }
    }

    override fun setWatchTab(watch: Boolean) {
        closeInspector()
        if (state.watchTab == watch && !state.savedTab) return
        invalidateVisiblePageRequest()
        savedGeneration++
        state = state.copy(watchTab = watch, savedTab = false, savedSelection = emptySet(), selected = emptySet())
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

    override fun editIncrementalTargets(
        targets: List<MemoryEditTarget>,
        expectedRevision: Long,
        base: String,
        step: String,
        type: Int,
    ) {
        val snapshot = targets.toList()
        if (snapshot.isEmpty() || snapshot.size > MemoryEngineContract.MAX_RESULT_PAGE_SIZE ||
            snapshot.any { it.watch || it.type != type } ||
            memoryIncrementalPreview(snapshot, type, base, step) == null
        ) {
            state = state.copy(
                message = context.getString(R.string.memory_editor_increment_invalid),
                messageIsError = true,
            )
            return
        }
        val ids = snapshot.map(MemoryEditTarget::id).toLongArray()
        launchOperation { engine, token ->
            engine.editResultsIncremental(token, expectedRevision, ids, type, base.trim(), step.trim())
        }
    }

    override fun removeResultTargets(ids: List<Long>, expectedRevision: Long, revert: Boolean) {
        val snapshot = ids.toLongArray()
        if (snapshot.isEmpty() || snapshot.size > MemoryEngineContract.MAX_RESULT_PAGE_SIZE ||
            snapshot.toSet().size != snapshot.size
        ) return
        launchOperation(
            feedback = if (revert) PendingOperationFeedback(
                OperationFeedbackKind.REVERT_REMOVE, state.resultCount,
            ) else null,
        ) { engine, token ->
            if (revert) engine.revertAndRemoveResults(token, expectedRevision, snapshot)
            else engine.filterResultGroups(token, expectedRevision, snapshot, false)
        }
    }

    override fun removeSelected(keep: Boolean) {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return
        val watch = state.watchTab
        if (watch && keep) return
        launchOperation { engine, token ->
            if (watch) {
                engine.removeWatch(token, ids)
            } else {
                engine.filterResultGroups(token, state.revision, ids, keep)
            }
        }
    }

    override fun watchSelected(add: Boolean) {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return
        val resultGroup = !state.watchTab
        val expectedRevision = state.revision
        launchOperation { engine, token ->
            if (add) {
                if (resultGroup) engine.addWatchResults(token, expectedRevision, ids)
                else engine.addWatch(token, ids)
            } else {
                engine.removeWatch(token, ids)
            }
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
        val clip = ClipData.newPlainText(context.getString(R.string.memory_editor), rows.joinToString("\n"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
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
        openInspector(candidateId, null, radius, watchAnchor)
    }

    override fun inspectSavedVariable(id: String) {
        if (state.busy || !state.savedTab || state.savedValues[id]?.resolved != true) return
        if (state.savedVariables.none { it.id == id }) return
        openInspector(0L, id, MemoryEngineContract.DEFAULT_INSPECT_RADIUS, true)
    }

    private fun openInspector(candidateId: Long, savedId: String?, radius: Int,
                              watchAnchor: Boolean) {
        val token = state.runtimeToken
        val engine = service ?: return
        if (token == 0L) return
        val request = ++inspectorRequestGeneration
        val connection = connectionGeneration
        val revision = state.revision
        val operation = operationGeneration
        state = state.copy(inspectorLoading = true, inspector = null, message = null, messageIsError = false)
        runIpc {
            val bundle = if (savedId == null) engine.inspectCandidate(token, candidateId, radius, watchAnchor)
                else engine.inspectSaved(token, savedId, radius)
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
            val inspectedId = if (savedId == null) candidateId else {
                logicalIds?.getOrNull(logicalOffsets?.indexOfFirst { it == 0 } ?: -1) ?: 0L
            }
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
                        ManagedJavaMemoryIds.ownerHandle(inspectedId) &&
                        MemoryEngineContract.isCandidateType(logicalTypes[index])
                } && logicalIds.contains(inspectedId)
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
                    (savedId == null && !watchAnchor && state.revision != revision) ||
                    (savedId == null && !watchAnchor && logicalRows.isNotEmpty() && bundle.getLong(
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
                            candidateId = inspectedId,
                            savedId = savedId,
                            type = logicalRows.first().type,
                            label = logicalRows.firstOrNull { it.relativeOffset == 0 }?.label.orEmpty(),
                            logicalRows = logicalRows,
                            expectedRevision = bundle.getLong(
                                MemoryEngineContract.KEY_INSPECT_EXPECTED_REVISION, 0L,
                            ),
                            provenance = bundle.getString(MemoryEngineContract.KEY_INSPECT_PROVENANCE).orEmpty(),
                            watchAnchor = watchAnchor,
                            pathStatus = bundle.getInt(MemoryEngineContract.KEY_INSPECT_PATH_STATUS),
                            referencePath = bundle.getString(MemoryEngineContract.KEY_INSPECT_PATH).orEmpty(),
                            source = MemoryCandidateSource.checked(
                                bundle.getInt(MemoryEngineContract.KEY_INSPECT_SOURCE_KIND),
                                bundle.getString(MemoryEngineContract.KEY_INSPECT_SOURCE_CLASS),
                                bundle.getString(MemoryEngineContract.KEY_INSPECT_SOURCE_FIELD),
                                bundle.getString(MemoryEngineContract.KEY_INSPECT_SOURCE_TYPE),
                            ),
                        ),
                    )
                } else {
                    state = state.copy(
                        inspectorLoading = false,
                        inspector = null,
                        message = operationMessage(result, bundle)
                            ?: context.getString(R.string.memory_editor_invalid_request),
                        messageIsError = true,
                    )
                }
            }
        }
    }


    override fun saveInspectorVariable(name: String, relativeOffset: Int) {
        val snapshot = state.inspector ?: return
        val row = snapshot.logicalRows.firstOrNull { it.relativeOffset == relativeOffset } ?: return
        if (!row.editable || state.busy) return
        val token = state.runtimeToken
        val engine = service ?: return
        val authority = savedAuthority ?: return
        val generation = inspectorRequestGeneration
        val connection = connectionGeneration
        runIpc {
            val result = runCatching {
                val locator = if (snapshot.savedId != null) {
                    engine.captureSavedNeighborLocator(token, snapshot.savedId, relativeOffset)
                } else {
                    engine.captureInspectorLocator(token, snapshot.expectedRevision,
                        snapshot.candidateId, snapshot.watchAnchor, relativeOffset)
                }
                if (locator.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) !=
                    MemoryEngineContract.RESULT_OK) {
                    error("Inspector cannot verify a persistent path to this field")
                }
                val (path, appId, entry) = savedTarget()
                savedRows(authority.save(path, appId, entry, name.trim(), locator))
            }
            post {
                if (destroyed || generation != inspectorRequestGeneration ||
                    connection != connectionGeneration || !state.visible ||
                    service !== engine || state.runtimeToken != token) return@post
                state = state.copy(
                    message = result.exceptionOrNull()?.message ?: "Variable saved",
                    messageIsError = result.isFailure)
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
        if (!state.busy && !liveRefreshPending && !visiblePageRequestPending && state.activeTab != MemoryEditorTab.INSPECTOR) {
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

    private fun operationMessage(result: Int, details: Bundle?): String? {
        val message = memoryOperationMessage(
            result,
            reason = details?.getInt(MemoryEngineContract.KEY_OPERATION_REASON) ?: MemoryEngineContract.REASON_NONE,
            written = details?.getInt(MemoryEngineContract.KEY_MANAGED_WRITTEN) ?: 0,
            unconfirmed = details?.getInt(MemoryEngineContract.KEY_MANAGED_UNCONFIRMED) ?: 0,
            skipped = details?.getInt(MemoryEngineContract.KEY_MANAGED_SKIPPED) ?: 0,
            notAttempted = details?.getInt(MemoryEngineContract.KEY_MANAGED_NOT_ATTEMPTED) ?: 0,
            skippedByType = details?.getInt(MemoryEngineContract.KEY_MANAGED_SKIPPED_BY_TYPE) ?: 0,
        ) ?: return null
        val arguments = buildList<Any> {
            message.explanation?.let { add(context.getString(it)) }
            addAll(message.arguments)
        }
        return context.getString(message.resource, *arguments.toTypedArray())
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
