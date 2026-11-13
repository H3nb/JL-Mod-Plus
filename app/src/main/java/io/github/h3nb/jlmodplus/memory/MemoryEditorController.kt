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
import android.os.SystemClock
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

    private val previousNavigation = MemoryEditorSessionNavigation.read(ownedRuntimeToken)
    private var pendingInspectorRestore = previousNavigation.inspectorTarget
        .takeIf { previousNavigation.tab == MemoryEditorTab.INSPECTOR }
    private var inspectorRequestTarget: MemoryEditorInspectorTarget? = pendingInspectorRestore
    private var state by mutableStateOf(MemoryEditorUiState(
        pageOffset = previousNavigation.pageOffset,
        variablesTab = previousNavigation.underlyingTab == MemoryEditorTab.VARIABLES,
        scriptsTab = previousNavigation.underlyingTab == MemoryEditorTab.SCRIPTS,
        inspectorLoading = pendingInspectorRestore != null,
    ))
    private var service: IMemoryEngineService? = null
    private var savedAuthority: ISavedVariablesAuthority? = null
    private var savedBound = false
    private var savedIdentity: MidletSessionStore.State? = null
    @Volatile private var savedGeneration = 0L
    private var savedLocators: Map<String, Bundle> = emptyMap()
    private var savedRefreshPending = false
    private var savedCatalogDirty = false
    private var scriptsGeneration = 0L
    private val pendingRecipeDocuments = mutableSetOf<String>()
    private var recipeFreezeRefreshPending = false
    private var lastRecipeFreezeRefreshMs = 0L
    @Volatile private var savedEditId: String? = null
    private var savedEditReady = false
    private var hostForeground = true
    private var nextSavedResolveAt = 0L
    private val savedConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            savedAuthority = ISavedVariablesAuthority.Stub.asInterface(binder)
            if (state.visible && (state.variablesTab || state.scriptsTab)) refreshSavedVariables()
            if (state.visible && state.scriptsTab) refreshMemoryScripts()
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
        val savedRemovalError: String? = null,
    )
    private enum class OperationFeedbackKind { NEXT_SCAN, REVERT_REMOVE, LIST_REMOVE }

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
                val listRemoval = if (feedback?.kind == OperationFeedbackKind.LIST_REMOVE) {
                    memoryListRemovalOutcome(feedback.savedRemovalError,
                        if (succeeded) null else operationFeedbackMessage ?: "Unknown Watch removal error")
                } else null
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
                    listRemoval != null -> listRemoval.message
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
                    messageIsError = listRemoval?.isError ?: (!succeeded && displayMessage != null),
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

    fun setHostForeground(foreground: Boolean) {
        if (destroyed || hostForeground == foreground) return
        hostForeground = foreground
        composeView.removeCallbacks(liveRefreshRunnable)
        if (foreground && state.visible) {
            // A Saved mutation may have committed while the UI was backgrounded and
            // its callback was discarded. Reconcile from the authority on return.
            if (state.variablesTab && savedAuthority != null) refreshSavedVariables()
            if (state.scriptsTab && savedAuthority != null) refreshMemoryScripts()
            composeView.postDelayed(liveRefreshRunnable, LIVE_REFRESH_INITIAL_DELAY_MS)
        } else {
            // If backgrounding interrupts the queued binding snapshot, no dialog
            // owns its pause yet. An already-open edit keeps its binding pinned.
            if (!savedEditReady) savedEditId = null
            savedGeneration++
            savedRefreshPending = false
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
        if (hostForeground) composeView.postDelayed(liveRefreshRunnable, LIVE_REFRESH_INITIAL_DELAY_MS)
    }

    private fun rememberNavigation() {
        val inspector = inspectorRequestTarget
            .takeIf { state.inspectorLoading || state.inspector != null }
        val tab = when {
            inspector != null -> MemoryEditorTab.INSPECTOR
            state.scriptsTab -> MemoryEditorTab.SCRIPTS
            state.variablesTab -> MemoryEditorTab.VARIABLES
            else -> MemoryEditorTab.RESULTS
        }
        MemoryEditorSessionNavigation.remember(ownedRuntimeToken,
            MemoryEditorNavigationState(tab, state.pageOffset, inspector))
    }

    override fun close() {
        if (destroyed) return
        rememberNavigation()
        inspectorRequestGeneration++
        composeView.removeCallbacks(liveRefreshRunnable)
        liveRefreshPending = false
        stateRequestGeneration.incrementAndGet()
        savedGeneration++
        scriptsGeneration++
        savedRefreshPending = false
        savedEditId = null
        savedEditReady = false
        visiblePageRequestGeneration++
        visiblePageRequestPending = false
        visiblePageRequestInFlightGeneration = 0L
        state = state.copy(visible = false, selected = emptySet())
        composeView.visibility = View.GONE
        closeHost()
    }

    fun destroy() {
        if (destroyed) return
        if (state.visible) rememberNavigation()
        destroyed = true
        savedGeneration++
        scriptsGeneration++
        savedRefreshPending = false
        savedEditId = null
        savedEditReady = false
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
                else if (pendingInspectorRestore != null) closeInspector()
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
                messageIsError = if (capabilityMessage.isNullOrBlank()) state.messageIsError else false,
            )
            if (state.variablesTab && state.savedVariables.isNotEmpty()
                && state.savedValues.isEmpty() && savedAuthority != null) refreshSavedVariables()
            restoreInspectorIfNeeded()
        }
    }

    private fun restoreInspectorIfNeeded() {
        val target = pendingInspectorRestore ?: return
        pendingInspectorRestore = null
        val available = if (target.savedId != null) {
            true // The target-side Saved binding is verified by inspectSaved.
        } else if (target.watchAnchor) {
            state.watches.any { it.id == target.candidateId }
        } else {
            state.results.any { it.id == target.candidateId }
        }
        if (!available) {
            closeInspector()
            state = state.copy(
                message = context.getString(R.string.memory_editor_identity_unsafe),
                messageIsError = true,
            )
            return
        }
        openInspector(target.candidateId, target.savedId, target.radius,
            target.watchAnchor, restoring = true)
    }

    /** Reads only the visible page; managed reads are getters and never refresh search baselines. */
    private fun reloadVisibleRows() {
        if (destroyed || visiblePageRequestPending) return
        val token = state.runtimeToken
        val engine = service
        if (token == 0L || engine == null) return
        val watchTab = state.variablesTab
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
                        state.variablesTab != watchTab || state.pageOffset != pageOffset ||
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
        val ids = if (state.variablesTab) {
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
    private fun savedRows(response: Bundle): List<Pair<SavedVariableRow, Bundle?>> {
        if (!response.getBoolean("ok", false)) {
            throw IllegalStateException(response.getString("error") ?: "Saved storage is unavailable")
        }
        return response.getParcelableArrayList<Bundle>("items").orEmpty().map { record ->
            val locator = record.getBundle("locator")
            val steps = locator?.getParcelableArrayList<Bundle>("savedSteps").orEmpty()
            val description = steps.joinToString(" → ") { step ->
                when (step.getInt("kind")) {
                    1 -> step.getString("name").orEmpty()
                    2 -> "[${step.getInt("index")}]"
                    else -> "?"
                }
            }
            SavedVariableRow(record.getString("id").orEmpty(),
                record.getString("label").orEmpty(),
                description.ifBlank { "Needs relink" }, locator != null) to locator
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
        // A pinned Saved edit owns its binding and displayed snapshot until dismissal.
        if (savedEditId != null) return
        val generation = ++savedGeneration
        val authority = savedAuthority
        val connection = connectionGeneration
        savedLocators = emptyMap()
        savedRefreshPending = false
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
                if (destroyed || !state.visible ||
                    (!state.variablesTab && !state.scriptsTab) ||
                    generation != savedGeneration || connection != connectionGeneration) return@post
                if (records.isSuccess) {
                    val rows = records.getOrThrow()
                    savedLocators = rows.mapNotNull { (row, locator) ->
                        locator?.let { row.id to it }
                    }.toMap()
                    savedCatalogDirty = false
                    nextSavedResolveAt = 0L
                    state = state.copy(
                        savedVariables = rows.map { it.first },
                        savedValues = emptyMap(),
                        savedError = null,
                    )
                    refreshSavedLive()
                } else {
                    state = state.copy(
                        savedVariables = emptyList(),
                        savedValues = emptyMap(),
                        savedError = records.exceptionOrNull()?.message ?: "Unable to load Saved definitions",
                    )
                }
            }
        }
    }

    /** The existing editor tick owns Saved polling; never reload definitions on every frame. */
    private fun refreshSavedLive() {
        if (savedRefreshPending || savedEditId != null || destroyed || !hostForeground ||
            !state.visible || !state.variablesTab || state.busy || savedLocators.isEmpty()) return
        val engine = service ?: return
        val token = state.runtimeToken
        if (token == 0L) return
        val connection = connectionGeneration
        val generation = savedGeneration
        val retry = android.os.SystemClock.uptimeMillis() >= nextSavedResolveAt
        if (retry) nextSavedResolveAt = android.os.SystemClock.uptimeMillis() + SAVED_RESOLVE_INTERVAL_MS
        val locators = savedLocators.toList()
        savedRefreshPending = true
        runIpc {
            try {
                locators.chunked(8).forEach { batch ->
                    if (generation != savedGeneration || savedEditId != null) return@runIpc
                    val values = batch.associate { (id, locator) ->
                        val observed = runCatching {
                            var value = savedValue(engine.readSaved(token, id), bound = true)
                            if (retry && savedEditId == null &&
                                (value.code == MemoryEngineContract.RESULT_NO_SESSION ||
                                value.code == MemoryEngineContract.RESULT_IDENTITY_UNSAFE)) {
                                if (value.code == MemoryEngineContract.RESULT_IDENTITY_UNSAFE) {
                                    engine.releaseSaved(token, id)
                                }
                                val attempt = engine.resolveSavedLocator(token, locator, id)
                                value = if (attempt.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                                    MemoryEngineContract.RESULT_OK
                                ) savedValue(engine.readSaved(token, id), bound = true)
                                    .copy(rebound = value.code ==
                                        MemoryEngineContract.RESULT_IDENTITY_UNSAFE)
                                else savedValue(attempt)
                            }
                            value
                        }.getOrElse {
                            SavedVariableRuntimeValue(MemoryEngineContract.RESULT_TARGET_LOST,
                                detail = it.message.orEmpty())
                        }
                        id to observed
                    }
                    post {
                        if (destroyed || !state.visible || !state.variablesTab ||
                            generation != savedGeneration || connection != connectionGeneration ||
                            service !== engine || state.runtimeToken != token) return@post
                        val now = android.os.SystemClock.uptimeMillis()
                        val merged = values.mapValues { (id, current) ->
                            savedValueAfterRefresh(
                                current, state.savedValues[id], now, SAVED_REBOUND_NOTICE_MS,
                            )
                        }
                        state = state.copy(savedValues = state.savedValues + merged)
                    }
                }
            } finally {
                post {
                    if (generation == savedGeneration) savedRefreshPending = false
                }
            }
        }
    }

    /**
     * Bookmark capture is opportunistic: an untraced but valid runtime candidate still receives
     * a durable record. The target-side link is always checked by exact session CandidateId.
     */
    private fun persistBookmarkCandidate(
        engine: IMemoryEngineService, authority: ISavedVariablesAuthority, token: Long,
        revision: Long, candidate: Long, name: String,
    ): String {
        val captured = engine.captureSavedLocator(token, revision, candidate)
        val locator = captured.takeIf {
            it.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                MemoryEngineContract.RESULT_OK
        }
        val (path, appId, entry) = savedTarget()
        val response = authority.bookmark(path, appId, entry, name.take(64), locator)
        savedRows(response) // The authority must acknowledge its durable commit.
        val bookmarkId = response.getString("bookmarkId")
            ?: error("Bookmark was stored without an identity")
        val linked = engine.attachBookmark(token, revision, candidate, bookmarkId, name.take(64))
        if (linked.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                MemoryEngineContract.RESULT_TARGET_LOST) != MemoryEngineContract.RESULT_OK) {
            error("Bookmark saved but its runtime target could not be linked: " +
                linked.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL).orEmpty())
        }
        return bookmarkId
    }

    private fun bookmarkCandidateLabel(id: Long): String {
        val watch = state.watches.firstOrNull { it.id == id }
        val result = state.results.firstOrNull { it.id == id }
        return watch?.label?.takeIf(String::isNotBlank)
            ?: watch?.source?.shortFieldName?.takeIf(String::isNotBlank)
            ?: result?.source?.shortFieldName?.takeIf(String::isNotBlank)
            ?: "Variable"
    }

    /**
     * A user-visible Freeze/Add to List first persists and links every target. This prevents
     * successful guest writes from continuing invisibly when storage or identity checks fail.
     * A partial catalog commit is retained and reported; it never starts a partial new Freeze.
     */
    private fun bookmarkTargetsBeforeAction(
        ids: LongArray, revision: Long,
        maxTargets: Int = SavedVariablesAuthority.MAX_ITEMS,
        afterStored: () -> Unit,
    ) {
        val engine = service ?: return
        val authority = savedAuthority
        val token = state.runtimeToken
        if (state.busy || token == 0L || ids.isEmpty()) return
        if (ids.size > maxTargets) {
            state = state.copy(message = "Select at most $maxTargets Bookmark targets",
                messageIsError = true)
            return
        }
        if (authority == null) {
            state = state.copy(message = "Bookmark storage unavailable", messageIsError = true)
            return
        }
        val known = state.watches.filter {
            it.id in ids && !it.savedSourceId.isNullOrBlank()
        }.mapTo(HashSet()) { it.id }
        val targets = ids.filterNot { it in known }.map { it to bookmarkCandidateLabel(it) }
        if (targets.isEmpty()) {
            afterStored()
            return
        }
        state = state.copy(busy = true)
        runIpc {
            val result = runCatching {
                targets.forEach { (id, label) ->
                    persistBookmarkCandidate(engine, authority, token, revision, id, label)
                }
            }
            post {
                if (destroyed || state.runtimeToken != token || service !== engine) return@post
                state = state.copy(busy = false)
                if (state.variablesTab) refreshSavedVariables()
                if (result.isSuccess) afterStored()
                else {
                    state = state.copy(
                        message = result.exceptionOrNull()?.message ?: "Bookmark failed",
                        messageIsError = true,
                    )
                    reloadState()
                }
            }
        }
    }

    override fun saveSelectedVariable(name: String) {
        val candidate = state.selected.singleOrNull() ?: return
        val token = state.runtimeToken
        val revision = if (state.variablesTab) 0L else state.revision
        val engine = service ?: return
        val authority = savedAuthority ?: return
        if (state.busy || token == 0L || name.isBlank()) return
        state = state.copy(busy = true)
        runIpc {
            val outcome = runCatching {
                persistBookmarkCandidate(engine, authority, token, revision, candidate, name.trim())
            }
            post {
                if (destroyed || state.runtimeToken != token || service !== engine) return@post
                state = state.copy(
                    busy = false,
                    message = if (outcome.isSuccess) "Bookmarked"
                        else outcome.exceptionOrNull()?.message,
                    messageIsError = outcome.isFailure,
                )
                if (state.variablesTab) refreshSavedVariables()
                reloadState()
            }
        }
    }

    override fun beginSavedEdit(id: String, onReady: (SavedVariableRuntimeValue?) -> Unit) {
        val engine = service ?: return
        val token = state.runtimeToken
        if (savedEditId != null || !state.visible || !state.variablesTab || !state.writeSupported ||
            state.busy || state.savedValues[id]?.resolved != true || token == 0L) return

        savedEditId = id
        savedEditReady = false
        // Stop new refreshes immediately; the single-threaded IPC queue drains the
        // preceding refresh before taking the dialog's authoritative binding snapshot.
        val generation = ++savedGeneration
        savedRefreshPending = false
        val connection = connectionGeneration
        runIpc {
            val value = runCatching { savedValue(engine.readSaved(token, id), bound = true) }
                .getOrElse {
                    SavedVariableRuntimeValue(MemoryEngineContract.RESULT_TARGET_LOST,
                        detail = it.message.orEmpty())
                }
            post {
                if (savedEditId != id || generation != savedGeneration) return@post
                if (destroyed || !state.visible || !state.variablesTab || state.runtimeToken != token ||
                    connection != connectionGeneration || service !== engine ||
                    state.savedVariables.none { it.id == id }) {
                    endSavedEdit()
                    return@post
                }
                if (!value.resolved) {
                    state = state.copy(
                        savedValues = state.savedValues + (id to value),
                        message = value.detail,
                        messageIsError = true,
                    )
                    endSavedEdit()
                    return@post
                }
                savedEditReady = true
                onReady(value)
            }
        }
    }

    override fun endSavedEdit() {
        if (savedEditId == null) return
        savedEditId = null
        savedEditReady = false
        nextSavedResolveAt = 0L
        if (savedCatalogDirty ||
            (savedLocators.isEmpty() && state.savedVariables.isNotEmpty())) {
            // Apply may already be queued on IPC. Deliver its feedback before a
            // catalog reload advances savedGeneration and discards that response.
            runIpc {
                post {
                    if (hostForeground && state.visible && state.variablesTab &&
                        savedEditId == null && (savedCatalogDirty ||
                            (savedLocators.isEmpty() && state.savedVariables.isNotEmpty()))) {
                        refreshSavedVariables()
                    }
                }
            }
        } else {
            refreshSavedLive()
        }
    }

    override fun editSavedVariable(id: String, replacement: String, freezeAfter: Boolean) {
        val engine = service ?: return
        val token = state.runtimeToken
        val generation = savedGeneration
        val label = state.savedVariables.firstOrNull { it.id == id }?.label ?: return
        if (token == 0L || !state.writeSupported ||
            state.savedValues[id]?.resolved != true || state.busy) return
        runIpc {
            val result = runCatching {
                val edited = savedValue(engine.editSaved(token, id, replacement.trim()), bound = true)
                val freezeResult = if (freezeAfter && edited.resolved) {
                    engine.freezeSaved(token, id, label, replacement.trim())
                } else null
                edited to freezeResult
            }
            post {
                if (destroyed || generation != savedGeneration || !state.visible ||
                    state.runtimeToken != token || service !== engine ||
                    state.savedVariables.none { it.id == id }) return@post
                val pair = result.getOrNull()
                val edited = pair?.first ?: SavedVariableRuntimeValue(
                    MemoryEngineContract.RESULT_TARGET_LOST,
                    detail = result.exceptionOrNull()?.message.orEmpty(),
                )
                val freezeCode = pair?.second?.getInt(
                    MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                    MemoryEngineContract.RESULT_TARGET_LOST,
                )
                val freezeFailed = freezeAfter && edited.resolved &&
                    freezeCode != MemoryEngineContract.RESULT_OK
                val error = !edited.resolved || freezeFailed
                val message = when {
                    !edited.resolved -> edited.detail.ifBlank { "Saved edit was rejected" }
                    freezeFailed -> "Value updated, but Freeze failed: " +
                        (pair?.second?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                            ?: "Watch/Freeze is unavailable")
                    freezeAfter -> "Saved value updated and frozen in Watch"
                    else -> "Saved value updated"
                }
                state = state.copy(
                    savedValues = state.savedValues + (id to edited),
                    message = message,
                    messageIsError = error,
                )
                if (freezeCode == MemoryEngineContract.RESULT_OK) reloadState()
            }
        }
    }

    override fun relinkBookmark(id: String, candidateId: Long, expectedRevision: Long) {
        val record = state.savedVariables.firstOrNull { it.id == id } ?: return
        val engine = service ?: return
        val authority = savedAuthority ?: return
        val token = state.runtimeToken
        if (state.busy || token == 0L || expectedRevision == 0L ||
            state.results.none { it.id == candidateId }) return
        state = state.copy(busy = true)
        runIpc {
            val outcome = runCatching {
                val captured = engine.captureSavedLocator(token, expectedRevision, candidateId)
                val locator = captured.takeIf {
                    it.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                        MemoryEngineContract.RESULT_OK
                }
                val detached = engine.detachBookmark(token, id)
                if (detached.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                        MemoryEngineContract.RESULT_TARGET_LOST) != MemoryEngineContract.RESULT_OK)
                    error("Cannot stop the old Bookmark Freeze")
                val (path, appId, entry) = savedTarget()
                savedRows(authority.relinkBookmark(path, appId, entry, id, locator))
                val attached = engine.attachBookmark(token, expectedRevision, candidateId,
                    id, record.label)
                if (attached.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                        MemoryEngineContract.RESULT_TARGET_LOST) != MemoryEngineContract.RESULT_OK)
                    error("Bookmark locator updated; runtime target changed. Try relinking again.")
            }
            post {
                if (destroyed || state.runtimeToken != token || service !== engine) return@post
                state = state.copy(busy = false,
                    message = if (outcome.isSuccess) "Bookmark relinked"
                        else outcome.exceptionOrNull()?.message,
                    messageIsError = outcome.isFailure)
                if (state.variablesTab) refreshSavedVariables()
                reloadState()
            }
        }
    }

    override fun renameSavedVariable(id: String, name: String) = renameSaved(id, name)
    override fun deleteSavedVariable(id: String) = deleteSavedVariables(listOf(id))
    override fun deleteSavedVariables(ids: List<String>) = deleteSavedEntries(ids)

    private fun deleteSavedEntries(ids: List<String>, onComplete: ((String?) -> Unit)? = null) {
        val authority = savedAuthority ?: return
        val token = state.runtimeToken
        val engine = service
        val current = state.savedVariables.map { it.id }.toSet()
        val unique = ids.distinct()
        if (!state.variablesTab || unique.isEmpty() || unique.size > 64 ||
            unique.any { it !in current }) return

        // Prevent queued polling from rebinding IDs while deletion is in flight.
        val removed = unique.toSet()
        val generation = ++savedGeneration
        savedRefreshPending = false
        savedCatalogDirty = true
        savedLocators = savedLocators.filterKeys { it !in removed }
        state = state.copy(
            savedVariables = state.savedVariables.filterNot { it.id in removed },
            savedValues = state.savedValues.filterKeys { it !in removed },
        )
        runIpc {
            val result = runCatching {
                if (token != 0L) {
                    val current = engine ?: error("Cannot stop Bookmark Freeze while disconnected")
                    unique.forEach { id ->
                        val detached = current.detachBookmark(token, id)
                        if (detached.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                                MemoryEngineContract.RESULT_TARGET_LOST) !=
                            MemoryEngineContract.RESULT_OK) {
                            error("Could not stop the old Bookmark Freeze")
                        }
                    }
                }
                val (path, appId, entry) = savedTarget()
                savedRows(authority.removeMany(path, appId, entry, unique.toTypedArray()))
            }
            post {
                if (state.variablesTab && state.runtimeToken == token) {
                    if (generation != savedGeneration) {
                        if (result.isFailure) {
                            state = state.copy(message = result.exceptionOrNull()?.message,
                                messageIsError = true)
                        }
                    } else {
                        val rows = result.getOrNull()
                        if (rows != null) {
                            val retained = rows.mapTo(HashSet()) { it.first.id }
                            savedLocators = rows.mapNotNull { (row, locator) ->
                        locator?.let { row.id to it }
                    }.toMap()
                            savedCatalogDirty = false
                            state = state.copy(
                                savedVariables = rows.map { it.first },
                                savedValues = state.savedValues.filterKeys { it in retained },
                                message = if (unique.size == 1) "Bookmark removed"
                                    else "Bookmarks removed",
                                messageIsError = false,
                            )
                        } else {
                            state = state.copy(
                                message = result.exceptionOrNull()?.message, messageIsError = true,
                            )
                            refreshSavedVariables()
                        }
                    }
                }
                onComplete?.invoke(result.exceptionOrNull()?.message
                    ?: if (result.isFailure) "Unknown Saved removal error" else null)
            }
        }
    }

    private fun renameSaved(id: String, name: String) {
        val authority = savedAuthority ?: return
        if (!state.variablesTab || state.savedVariables.none { it.id == id }) return
        val token = state.runtimeToken
        val generation = ++savedGeneration
        savedRefreshPending = false
        savedCatalogDirty = true
        runIpc {
            val result = runCatching {
                val (path, appId, entry) = savedTarget()
                savedRows(authority.rename(path, appId, entry, id, name.trim()))
            }
            post {
                if (destroyed || !state.variablesTab || state.runtimeToken != token) return@post
                if (generation != savedGeneration) {
                    if (result.isFailure) {
                        state = state.copy(message = result.exceptionOrNull()?.message,
                            messageIsError = true)
                    }
                    return@post // A newer mutation or reload owns the final catalog.
                }
                val rows = result.getOrNull()
                if (rows != null) {
                    val retained = rows.mapTo(HashSet()) { it.first.id }
                    savedLocators = rows.mapNotNull { (row, locator) ->
                        locator?.let { row.id to it }
                    }.toMap()
                    savedCatalogDirty = false
                    state = state.copy(
                        savedVariables = rows.map { it.first },
                        savedValues = state.savedValues.filterKeys { it in retained },
                        message = "Saved variable renamed", messageIsError = false,
                    )
                } else {
                    state = state.copy(message = result.exceptionOrNull()?.message,
                        messageIsError = true)
                    refreshSavedVariables()
                }
            }
        }
    }

    override fun setVariablesTab(variables: Boolean) {
        closeInspector()
        if (state.variablesTab == variables && !state.scriptsTab) return
        invalidateVisiblePageRequest()
        savedGeneration++
        savedRefreshPending = false
        savedEditId = null
        savedEditReady = false
        state = state.copy(variablesTab = variables, scriptsTab = false, selected = emptySet())
        if (variables) refreshSavedVariables()
    }

    override fun setScriptsTab() {
        closeInspector()
        if (state.scriptsTab) return
        invalidateVisiblePageRequest()
        state = state.copy(variablesTab = false, scriptsTab = true, selected = emptySet())
        refreshMemoryScripts()
        refreshSavedVariables()
        refreshRecipeFreezeState(force = true)
    }

    override fun toggleSelection(id: Long) {
        val selectionLimit = if (state.variablesTab) MemoryEngineContract.MAX_WATCH_RECORDS
        else MemoryEngineContract.MAX_RESULT_PAGE_SIZE
        if (id !in state.selected && state.selected.size >= selectionLimit) {
            state = state.copy(
                message = if (state.variablesTab) {
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
        val ids = if (state.variablesTab) state.watches.map(MemoryWatchRow::id)
        else state.results.map(MemoryResultRow::id)
        state = state.copy(selected = ids.toSet())
    }

    override fun invertVisible() {
        val ids = if (state.variablesTab) state.watches.map(MemoryWatchRow::id)
        else state.results.map(MemoryResultRow::id)
        state = state.copy(selected = ids.filterNot(state.selected::contains).toSet())
    }

    override fun clearSelection() {
        state = state.copy(selected = emptySet())
    }

    override fun editSelected(value: String, type: Int) {
        val id = state.selected.singleOrNull() ?: return
        val resultGroup = !state.variablesTab
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
        val resultGroup = !state.variablesTab
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
        if (!followUp.addToWatch && !followUp.freezeAfter) {
            reloadState()
            return
        }
        val revision = if (followUp.resultGroup) followUp.expectedRevision else 0L
        val id = longArrayOf(followUp.id)
        bookmarkTargetsBeforeAction(id, revision) {
            if (followUp.freezeAfter) launchOperation { engine, token ->
                if (followUp.resultGroup) engine.setFreezeResults(token, revision, id,
                    MemoryEngineContract.FREEZE_LOCK, followUp.value, "")
                else engine.setFreeze(token, id, MemoryEngineContract.FREEZE_LOCK,
                    followUp.value, "")
            } else reloadState()
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
        val watch = state.variablesTab
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
        if (add) {
            bookmarkTargetsBeforeAction(ids,
                if (state.variablesTab) 0L else state.revision) { reloadState() }
        } else if (state.variablesTab) {
            val linked = state.watches.filter { it.id in ids }
            removeListEntries(linked.mapNotNull { it.savedSourceId },
                linked.filter { it.savedSourceId == null }.map { it.id })
        }
    }

    override fun labelWatch(id: Long, label: String) {
        launchOperation { engine, token -> engine.setWatchLabel(token, id, label.take(64)) }
    }

    override fun freezeSelected(mode: Int, first: String, second: String) {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return
        val revision = if (state.variablesTab) 0L else state.revision
        bookmarkTargetsBeforeAction(ids, revision,
            maxTargets = MemoryEngineContract.MAX_FREEZE_RECORDS) {
            launchOperation { engine, token ->
                if (revision != 0L) engine.setFreezeResults(token, revision, ids, mode, first, second)
                else engine.setFreeze(token, ids, mode, first, second)
            }
        }
    }

    override fun clearFreezeSelected() {
        val ids = state.selected.toLongArray()
        if (ids.isEmpty()) return
        launchOperation { engine, token -> engine.clearFreeze(token, ids) }
    }

    override fun clearSavedWatchFreeze(savedId: String) {
        val row = state.watches.singleOrNull { it.savedSourceId == savedId } ?: return
        if (row.freezeMode < 0) return
        launchOperation { engine, token -> engine.clearFreeze(token, longArrayOf(row.id)) }
    }

    override fun freezeListSaved(id: String, expectedValue: String) {
        val row = state.savedVariables.firstOrNull { it.id == id } ?: return
        val engine = service ?: return
        val token = state.runtimeToken
        if (state.busy || !state.writeSupported || token == 0L) return
        if (state.savedValues[id]?.resolved != true) {
            val active = state.watches.firstOrNull {
                it.savedSourceId == id && it.state != MemoryEngineContract.CANDIDATE_LOST
            } ?: return
            launchOperation { current, currentToken ->
                current.setFreeze(currentToken, longArrayOf(active.id),
                    MemoryEngineContract.FREEZE_LOCK, expectedValue, "")
            }
            return
        }
        runIpc {
            val result = runCatching { engine.freezeSaved(token, id, row.label, expectedValue) }
            post {
                if (destroyed || !state.visible || state.runtimeToken != token ||
                    state.savedVariables.none { it.id == id } || service !== engine) return@post
                val response = result.getOrNull()
                val success = response?.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                    MemoryEngineContract.RESULT_OK
                state = state.copy(
                    message = if (success) "Freeze enabled"
                        else response?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                            ?: result.exceptionOrNull()?.message ?: "Freeze unavailable",
                    messageIsError = !success,
                )
                if (success) reloadState()
            }
        }
    }

    override fun setListWatchFreeze(id: Long, expectedValue: String, freeze: Boolean) {
        if (!state.watches.any { it.id == id }) return
        if (!freeze) {
            launchOperation { engine, token -> engine.clearFreeze(token, longArrayOf(id)) }
        } else if (state.writeSupported) {
            launchOperation { engine, token ->
                engine.setFreeze(token, longArrayOf(id),
                    MemoryEngineContract.FREEZE_LOCK, expectedValue, "")
            }
        }
    }

    override fun keepListWatch(id: Long, name: String) {
        val watched = state.watches.firstOrNull { it.id == id } ?: return
        val authority = savedAuthority ?: return
        val engine = service ?: return
        val token = state.runtimeToken
        if (token == 0L || state.busy || name.isBlank()) return
        val existingIds = state.savedVariables.mapTo(HashSet()) { it.id }
        val expected = LongArray(1)
        if (!ManagedJavaValue.parse(watched.valueText, watched.type, expected)) return
        val connection = connectionGeneration
        val generation = savedGeneration
        runIpc {
            val outcome = runCatching {
                val locator = engine.captureSavedLocator(token, 0L, id)
                if (locator.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) !=
                    MemoryEngineContract.RESULT_OK) error("A verified path is needed to keep this value")
                val (path, appId, entry) = savedTarget()
                val rows = savedRows(authority.save(path, appId, entry, name.take(64), locator))
                val keptId = rows.singleOrNull { it.first.id !in existingIds }?.first?.id
                    ?: error("Saved catalog changed; refresh List before keeping this value")
                val resolved = engine.resolveSavedLocator(token, locator, keptId)
                val link = if (resolved.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                    MemoryEngineContract.RESULT_OK) {
                    engine.watchInspector(token, keptId, 0L, 0L, false, 0, watched.type, expected[0])
                } else resolved
                link.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                    MemoryEngineContract.RESULT_OK
            }
            post {
                if (destroyed || !state.visible || state.runtimeToken != token ||
                    connection != connectionGeneration || service !== engine) return@post
                state = state.copy(
                    message = when {
                        outcome.isFailure -> outcome.exceptionOrNull()?.message
                        outcome.getOrNull() == true -> "Kept for next launch"
                        else -> "Kept, but its current Watch could not be linked"
                    },
                    messageIsError = outcome.isFailure || outcome.getOrNull() != true,
                )
                if (generation == savedGeneration) refreshSavedVariables()
                reloadState()
            }
        }
    }

    override fun unkeepListSaved(id: String) {
        val saved = state.savedVariables.firstOrNull { it.id == id } ?: return
        val linked = state.watches.any { it.savedSourceId == id }
        if (linked) {
            val watch = state.watches.firstOrNull { it.savedSourceId == id }
            if (watch != null && watch.label.isBlank()) labelWatch(watch.id, saved.label)
            deleteSavedVariable(id)
            return
        }
        val current = state.savedValues[id] ?: return
        val engine = service ?: return
        val token = state.runtimeToken
        if (!current.resolved || token == 0L || state.busy) return
        val bits = LongArray(1)
        if (!ManagedJavaValue.parse(current.value, current.type, bits)) return
        runIpc {
            val result = runCatching {
                engine.watchInspector(token, id, 0L, 0L, false, 0, current.type, bits[0])
            }
            post {
                if (destroyed || !state.visible || state.runtimeToken != token ||
                    state.savedVariables.none { it.id == id }) return@post
                val success = result.getOrNull()?.getInt(
                    MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                    MemoryEngineContract.RESULT_OK
                if (success) deleteSavedVariable(id)
                else state = state.copy(
                    message = "Cannot make a session-only entry from this target",
                    messageIsError = true,
                )
            }
        }
    }

    override fun removeListEntries(savedIds: List<String>, watchIds: List<Long>) {
        val saved = savedIds.distinct()
        // Persistent removal detaches its own session targets synchronously first.
        val watches = watchIds.filter { watchId ->
            state.watches.firstOrNull { it.id == watchId }?.savedSourceId !in saved
        }.distinct()
        if (!state.variablesTab || state.busy ||
            saved.size > 64 || watches.size > MemoryEngineContract.MAX_WATCH_RECORDS ||
            saved.any { id -> state.savedVariables.none { it.id == id } } ||
            watches.any { id -> state.watches.none { it.id == id } } ||
            (saved.isEmpty() && watches.isEmpty())) return
        if (saved.isNotEmpty() && savedAuthority == null ||
            watches.isNotEmpty() && (service == null || state.runtimeToken == 0L)) {
            state = state.copy(message = "Removal unavailable: backend disconnected", messageIsError = true)
            return
        }

        if (saved.isNotEmpty() && watches.isNotEmpty()) {
            val token = state.runtimeToken
            activeOperationId = -1L // No engine operation yet; reject unrelated late completions.
            state = state.copy(busy = true, message = null)
            deleteSavedEntries(saved) { savedError ->
                // A delayed main-process reply must not affect a newer MIDlet session.
                if (state.runtimeToken != token) return@deleteSavedEntries
                activeOperationId = 0L
                state = state.copy(busy = false)
                if (state.runtimeToken != token || !canLaunchOperation()) {
                    val outcome = memoryListRemovalOutcome(savedError, "Session unavailable")
                    state = state.copy(message = outcome.message, messageIsError = true)
                } else {
                    launchOperation(
                        feedback = PendingOperationFeedback(
                            OperationFeedbackKind.LIST_REMOVE, state.resultCount, savedError,
                        ),
                    ) { engine, currentToken ->
                        engine.removeWatch(currentToken, watches.toLongArray())
                    }
                }
            }
            return
        }
        if (saved.isNotEmpty()) deleteSavedVariables(saved)
        if (watches.isNotEmpty()) {
            launchOperation { engine, token -> engine.removeWatch(token, watches.toLongArray()) }
        }
    }

    @Suppress("DEPRECATION")
    private fun recipeRows(response: Bundle): List<MemoryScriptRow> {
        if (!response.getBoolean("ok", false)) {
            error(response.getString("error") ?: "Script catalog unavailable")
        }
        return response.getParcelableArrayList<Bundle>("items").orEmpty().map { row ->
            MemoryScriptRow(
                id = row.getString("id").orEmpty(),
                name = row.getString("name").orEmpty(),
                document = row.getString("document").orEmpty(),
            )
        }
    }

    override fun refreshMemoryScripts() {
        val authority = savedAuthority ?: return
        val generation = ++scriptsGeneration
        val token = ownedRuntimeToken
        runIpc {
            val result = runCatching {
                val (path, appId, entry) = savedTarget()
                recipeRows(authority.listRecipes(path, appId, entry))
            }
            post {
                if (destroyed || token != ownedRuntimeToken ||
                    generation != scriptsGeneration) return@post
                state = state.copy(
                    scripts = result.getOrNull() ?: state.scripts,
                    scriptsLoaded = result.isSuccess || state.scriptsLoaded,
                    scriptsError = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    /**
     * An edited or deleted recipe must not leave an old Freeze running. Stop it on the same
     * serialized IPC worker before publishing the catalog change; fail closed if stop is unknown.
     * A storage error can leave the old recipe saved but its Freeze stopped.
     */
    private fun stopRecipeFreezeBeforeMutation(
        token: Long, engine: IMemoryEngineService?, id: String,
    ) {
        if (token == 0L) return
        val currentEngine = engine ?: error("Cannot verify that the old Freeze has stopped")
        val reply = currentEngine.clearMemoryRecipeFreeze(token, id)
        if (reply.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                MemoryEngineContract.RESULT_TARGET_LOST) != MemoryEngineContract.RESULT_OK) {
            error(reply.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                ?: "Cannot stop the old Freeze")
        }
    }

    private fun refreshRecipeFreezeState(force: Boolean = false) {
        val engine = service ?: return
        val token = state.runtimeToken
        if (token == 0L || recipeFreezeRefreshPending) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRecipeFreezeRefreshMs < SCRIPT_FREEZE_REFRESH_INTERVAL_MS) return
        lastRecipeFreezeRefreshMs = now
        recipeFreezeRefreshPending = true
        runIpc {
            val reply = runCatching { engine.getCapabilities() }.getOrNull()
            post {
                recipeFreezeRefreshPending = false
                if (destroyed || state.runtimeToken != token || service !== engine) return@post
                if (reply != null) {
                    val ids = reply.getStringArray("recipeFreezeIds").orEmpty()
                    val names = reply.getStringArray("recipeFreezeNames").orEmpty()
                    state = state.copy(scriptActiveFreezes = ids.mapIndexed { index, id ->
                        id to names.getOrNull(index).orEmpty().ifBlank { id }
                    })
                }
            }
        }
    }

    override fun saveMemoryScript(
        document: String, replacingId: String?, onComplete: (Boolean) -> Unit,
    ) {
        val authority = savedAuthority
        if (authority == null) {
            state = state.copy(message = "Script storage unavailable", messageIsError = true)
            onComplete(false)
            return
        }
        scriptsGeneration++ // Invalidate old catalog reads, not mutation completion.
        val token = state.runtimeToken
        val engine = service
        runIpc {
            val result = runCatching {
                MemoryRecipeRuntime.validateDocument(document)
                val (path, appId, entry) = savedTarget()
                if (replacingId != null) stopRecipeFreezeBeforeMutation(token, engine, replacingId)
                recipeRows(authority.putRecipe(path, appId, entry, document, replacingId))
            }
            post {
                if (destroyed || token != state.runtimeToken) return@post
                val success = result.isSuccess
                state = state.copy(
                    scripts = result.getOrNull() ?: state.scripts,
                    scriptsError = if (success) null else result.exceptionOrNull()?.message,
                    message = if (success) context.getString(R.string.memory_scripts_saved)
                        else result.exceptionOrNull()?.message,
                    messageIsError = !success,
                )
                refreshRecipeFreezeState(force = true)
                onComplete(success)
            }
        }
    }

    override fun deleteMemoryScript(id: String, onComplete: (Boolean) -> Unit) {
        val authority = savedAuthority
        if (authority == null) {
            state = state.copy(message = "Script storage unavailable", messageIsError = true)
            onComplete(false)
            return
        }
        scriptsGeneration++ // A refresh must not invalidate Delete's callback.
        val token = state.runtimeToken
        val engine = service
        runIpc {
            val result = runCatching {
                val (path, appId, entry) = savedTarget()
                stopRecipeFreezeBeforeMutation(token, engine, id)
                recipeRows(authority.deleteRecipe(path, appId, entry, id))
            }
            post {
                if (destroyed || token != state.runtimeToken) return@post
                val success = result.isSuccess
                state = state.copy(
                    scripts = result.getOrNull() ?: state.scripts,
                    scriptsError = if (success) null else result.exceptionOrNull()?.message,
                    message = if (success) context.getString(R.string.memory_scripts_deleted)
                        else result.exceptionOrNull()?.message,
                    messageIsError = !success,
                )
                refreshRecipeFreezeState(force = true)
                onComplete(success)
            }
        }
    }

    override fun generateMemoryScript(
        draft: MemoryScriptBuilderDraft, onReady: (String?, String?) -> Unit,
    ) {
        val generated = runCatching {
            MemoryRecipeGenerator.fromBuilder(draft, savedTarget().third, savedLocators)
        }
        onReady(generated.getOrNull(), generated.exceptionOrNull()?.message)
    }

    override fun generateSimpleMemoryRecipe(savedId: String, value: String, freeze: Boolean) {
        if (state.busy || !state.writeSupported ||
            state.savedValues[savedId]?.resolved != true) return
        val document = runCatching {
            val locator = savedLocators[savedId] ?: error("Saved locator unavailable")
            MemoryRecipeGenerator.fromSaved(
                locator, savedTarget().third, savedId, value.trim(),
                state.savedVariables.firstOrNull { it.id == savedId }?.label.orEmpty()
            )
        }.getOrElse {
            state = state.copy(message = it.message ?: "Cannot generate automation", messageIsError = true)
            return
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText("Memory Recipe", document)
        )
        runMemoryRecipe(document, emptyMap(), freeze, false)
    }

    override fun readCurrentMemoryRecipe(
        document: String, inputs: Map<String, String>,
        onReady: (List<MemoryRecipeReadValue>?, String?) -> Unit,
    ) {
        val token = state.runtimeToken
        val engine = service
        if (engine == null || state.busy || token == 0L || !state.supported) {
            onReady(null, "MIDlet memory is not available")
            return
        }
        val parameters = Bundle().apply {
            inputs.forEach { (key, value) -> putString(key, value) }
        }
        runIpc {
            val outcome = runCatching {
                engine.runMemoryRecipe(token, document, parameters, false, true)
            }
            post {
                if (destroyed || state.runtimeToken != token || service !== engine) return@post
                val reply = outcome.getOrNull()
                val code = reply?.getInt(
                    MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                    MemoryEngineContract.RESULT_TARGET_LOST,
                ) ?: MemoryEngineContract.RESULT_TARGET_LOST
                if (code != MemoryEngineContract.RESULT_OK) {
                    onReady(null, reply?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                        ?: outcome.exceptionOrNull()?.message ?: "Cannot read current value")
                    return@post
                }
                val names = reply?.getStringArray("recipeReadInputs")
                val values = reply?.getStringArray("recipeReadValues")
                if (names == null || values == null || names.size != values.size) {
                    onReady(null, "Recipe returned invalid read results")
                } else {
                    onReady(names.indices.map { index ->
                        MemoryRecipeReadValue(
                            names[index], values[index], reply?.getString("recipeReadTicket"),
                        )
                    }, null)
                }
            }
        }
    }

    override fun runMemoryRecipe(
        document: String, inputs: Map<String, String>, freeze: Boolean, preview: Boolean,
    ) = executeMemoryRecipe(document, inputs, freeze, preview, null, null, null)

    override fun runMemoryRecipeChecked(
        document: String, inputs: Map<String, String>, freeze: Boolean,
        expectedValues: List<String>?, snapshotTicket: String?,
        onComplete: (Boolean) -> Unit,
    ) = executeMemoryRecipe(
        document, inputs, freeze, false, expectedValues, snapshotTicket, onComplete,
    )

    private fun executeMemoryRecipe(
        document: String, inputs: Map<String, String>, freeze: Boolean, preview: Boolean,
        expectedValues: List<String>?, snapshotTicket: String?,
        onComplete: ((Boolean) -> Unit)?,
    ) {
        val token = state.runtimeToken
        val engine = service
        if (engine == null || state.busy || token == 0L || !state.writeSupported ||
            !pendingRecipeDocuments.add(document)) {
            onComplete?.invoke(false)
            return
        }
        val parameters = Bundle().apply {
            inputs.forEach { (key, value) -> putString(key, value) }
            expectedValues?.let { putStringArray("recipeExpectedValues", it.toTypedArray()) }
            snapshotTicket?.let { putString("recipeReadTicket", it) }
        }
        runIpc {
            val result = runCatching {
                engine.runMemoryRecipe(token, document, parameters, freeze, preview)
            }
            post {
                pendingRecipeDocuments.remove(document)
                if (destroyed || state.runtimeToken != token || service !== engine) {
                    onComplete?.invoke(false)
                    return@post
                }
                val response = result.getOrNull()
                val code = response?.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                    MemoryEngineContract.RESULT_TARGET_LOST)
                    ?: MemoryEngineContract.RESULT_TARGET_LOST
                val success = code == MemoryEngineContract.RESULT_OK
                state = state.copy(
                    message = response?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                        ?: result.exceptionOrNull()?.message ?: "Recipe operation finished",
                    messageIsError = !success,
                )
                if (success && !preview) {
                    refreshSavedVariables()
                    refreshRecipeFreezeState(force = true)
                }
                onComplete?.invoke(success)
            }
        }
    }

    override fun listActiveMemoryRecipeFreezes(onReady: (List<Pair<String, String>>) -> Unit) {
        val engine = service
        val token = state.runtimeToken
        if (engine == null || token == 0L) {
            onReady(emptyList())
            return
        }
        runIpc {
            val response = runCatching { engine.getCapabilities() }.getOrNull()
            post {
                if (destroyed || service !== engine || state.runtimeToken != token) return@post
                val ids = response?.getStringArray("recipeFreezeIds").orEmpty()
                val names = response?.getStringArray("recipeFreezeNames").orEmpty()
                onReady(ids.mapIndexed { index, id ->
                    id to names.getOrNull(index).orEmpty().ifBlank { id }
                })
            }
        }
    }

    override fun clearMemoryRecipeFreeze(recipeId: String) {
        val token = state.runtimeToken
        val engine = service ?: return
        if (token == 0L || state.busy) return
        runIpc {
            val result = runCatching { engine.clearMemoryRecipeFreeze(token, recipeId) }
            post {
                if (destroyed || state.runtimeToken != token || service !== engine) return@post
                val response = result.getOrNull()
                val code = response?.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                    MemoryEngineContract.RESULT_TARGET_LOST)
                    ?: MemoryEngineContract.RESULT_TARGET_LOST
                state = state.copy(
                    message = response?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                        ?: result.exceptionOrNull()?.message ?: "Recipe Freeze stopped",
                    messageIsError = code != MemoryEngineContract.RESULT_OK,
                )
                refreshRecipeFreezeState(force = true)
            }
        }
    }

    override fun copySelected(locations: Boolean) {
        val selected = state.selected
        val rows = if (state.variablesTab) {
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
        if (state.busy || !state.variablesTab || state.savedValues[id]?.resolved != true) return
        if (state.savedVariables.none { it.id == id }) return
        openInspector(0L, id, MemoryEngineContract.DEFAULT_INSPECT_RADIUS, true)
    }

    private fun openInspector(candidateId: Long, savedId: String?, radius: Int,
                              watchAnchor: Boolean, partialWriteMessage: String? = null,
                              restoring: Boolean = false) {
        val token = state.runtimeToken
        val engine = service ?: return
        if (token == 0L) return
        val request = ++inspectorRequestGeneration
        val connection = connectionGeneration
        val revision = state.revision
        val operation = operationGeneration
        inspectorRequestTarget = MemoryEditorInspectorTarget(candidateId, savedId, watchAnchor, radius)
        state = state.copy(
            inspectorLoading = true,
            inspector = null,
            message = partialWriteMessage,
            messageIsError = partialWriteMessage != null,
        )
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
                if (result == MemoryEngineContract.RESULT_TARGET_LOST && !restoring) {
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


    private fun persistInspectorBookmark(
        engine: IMemoryEngineService, authority: ISavedVariablesAuthority, token: Long,
        snapshot: MemoryInspectorSnapshot, relativeOffset: Int, name: String,
    ): String {
        if (snapshot.savedId != null && relativeOffset == 0) return snapshot.savedId
        val captured = if (snapshot.savedId != null) {
            engine.captureSavedNeighborLocator(token, snapshot.savedId, relativeOffset)
        } else engine.captureInspectorLocator(token, snapshot.expectedRevision,
            snapshot.candidateId, snapshot.watchAnchor, relativeOffset)
        val locator = captured.takeIf {
            it.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT) ==
                MemoryEngineContract.RESULT_OK
        }
        val (path, appId, entry) = savedTarget()
        val response = authority.bookmark(path, appId, entry, name.take(64), locator)
        savedRows(response)
        return response.getString("bookmarkId") ?: error("Bookmark identity missing")
    }

    override fun saveInspectorVariable(name: String, relativeOffset: Int) {
        val snapshot = state.inspector ?: return
        val row = snapshot.logicalRows.firstOrNull { it.relativeOffset == relativeOffset } ?: return
        if (!row.editable || state.busy) return
        val token = state.runtimeToken
        val engine = service ?: return
        val authority = savedAuthority ?: return
        val request = inspectorRequestGeneration
        val connection = connectionGeneration
        if (name.isBlank()) return
        state = state.copy(busy = true)
        runIpc {
            val result = runCatching {
                val bookmarkId = persistInspectorBookmark(engine, authority, token,
                    snapshot, relativeOffset, name.trim())
                val linked = engine.bookmarkInspector(token, snapshot.savedId,
                    snapshot.expectedRevision, snapshot.candidateId, snapshot.watchAnchor,
                    relativeOffset, row.type, row.expectedBits, bookmarkId)
                if (linked.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
                        MemoryEngineContract.RESULT_TARGET_LOST) != MemoryEngineContract.RESULT_OK) {
                    error("Bookmarked, but the Inspector target is unavailable: " +
                        linked.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL).orEmpty())
                }
            }
            post {
                if (destroyed || state.runtimeToken != token || service !== engine) return@post
                state = state.copy(busy = false,
                    message = if (result.isSuccess) "Bookmarked"
                        else result.exceptionOrNull()?.message,
                    messageIsError = result.isFailure)
                if (state.variablesTab) refreshSavedVariables()
                reloadState()
            }
        }
    }

    override fun watchInspectorVariable(relativeOffset: Int) {
        val row = state.inspector?.logicalRows?.firstOrNull {
            it.relativeOffset == relativeOffset
        } ?: return
        saveInspectorVariable(row.label, relativeOffset)
    }

    override fun editSavedInspectorValue(
        id: String,
        relativeOffset: Int,
        type: Int,
        expectedBits: Long,
        replacement: String,
        freezeAfter: Boolean,
    ) {
        val snapshot = state.inspector ?: return
        if (snapshot.savedId != id || !state.writeSupported || state.busy ||
            snapshot.logicalRows.none {
                it.relativeOffset == relativeOffset && it.type == type &&
                    it.expectedBits == expectedBits && it.editable
            }) return
        if (!canLaunchOperation()) return
        val token = state.runtimeToken
        val engine = service ?: return
        val request = inspectorRequestGeneration
        val connection = connectionGeneration
        runIpc {
            val outcome = runCatching {
                if (freezeAfter) {
                    val authority = savedAuthority ?: error("Bookmark storage unavailable")
                    val label = snapshot.logicalRows.first {
                        it.relativeOffset == relativeOffset
                    }.label
                    val bookmarkId = persistInspectorBookmark(engine, authority, token,
                        snapshot, relativeOffset, label)
                    engine.editInspectorAndFreezeBookmark(
                        token, id, snapshot.expectedRevision, snapshot.candidateId,
                        snapshot.watchAnchor, relativeOffset, type, expectedBits, replacement.trim(),
                        bookmarkId,
                    )
                } else engine.editSavedInspector(
                    token, id, relativeOffset, type, expectedBits, replacement.trim(),
                )
            }
            post {
                if (destroyed || !state.visible || request != inspectorRequestGeneration ||
                    connection != connectionGeneration || service !== engine ||
                    state.runtimeToken != token || state.inspector?.savedId != id) return@post
                val result = outcome.getOrNull()
                val code = result?.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT)
                    ?: MemoryEngineContract.RESULT_TARGET_LOST
                if (code == MemoryEngineContract.RESULT_OK ||
                    code == MemoryEngineContract.RESULT_PARTIAL_WRITE) {
                    openInspector(
                        snapshot.candidateId, id, MemoryEngineContract.DEFAULT_INSPECT_RADIUS,
                        snapshot.watchAnchor,
                        if (code == MemoryEngineContract.RESULT_PARTIAL_WRITE)
                            result?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                                ?: "Value edited, but Freeze could not start"
                        else null,
                    )
                } else {
                    state = state.copy(
                        message = result?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                            ?: outcome.exceptionOrNull()?.message
                            ?: "Saved Inspector edit was rejected",
                        messageIsError = true,
                    )
                }
            }
        }
    }

    override fun closeInspector() {
        inspectorRequestGeneration++
        pendingInspectorRestore = null
        inspectorRequestTarget = null
        state = state.copy(inspectorLoading = false, inspector = null)
    }

    override fun editInspectorValue(
        anchorCandidateId: Long,
        relativeOffset: Int,
        type: Int,
        expectedBits: Long,
        replacementValue: String,
        watchAnchor: Boolean,
        freezeAfter: Boolean,
    ) {
        val snapshot = state.inspector ?: return
        if (snapshot.savedId != null || snapshot.candidateId != anchorCandidateId ||
            !MemoryEngineContract.isCandidateType(type)) return
        if (!canLaunchOperation()) return
        if (freezeAfter) {
            val token = state.runtimeToken
            val engine = service ?: return
            val request = inspectorRequestGeneration
            val connection = connectionGeneration
            runIpc {
                val outcome = runCatching {
                    val authority = savedAuthority ?: error("Bookmark storage unavailable")
                    val label = snapshot.logicalRows.firstOrNull {
                        it.relativeOffset == relativeOffset
                    }?.label ?: "Variable"
                    val bookmarkId = persistInspectorBookmark(engine, authority, token,
                        snapshot, relativeOffset, label)
                    engine.editInspectorAndFreezeBookmark(
                        token, null, snapshot.expectedRevision, anchorCandidateId,
                        watchAnchor, relativeOffset, type, expectedBits, replacementValue.trim(),
                        bookmarkId,
                    )
                }
                post {
                    if (destroyed || !state.visible || request != inspectorRequestGeneration ||
                        connection != connectionGeneration || service !== engine ||
                        state.runtimeToken != token || state.inspector != snapshot) return@post
                    val result = outcome.getOrNull()
                    val code = result?.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT)
                        ?: MemoryEngineContract.RESULT_TARGET_LOST
                    if (code == MemoryEngineContract.RESULT_OK ||
                        code == MemoryEngineContract.RESULT_PARTIAL_WRITE) {
                        reloadState()
                        openInspector(
                            anchorCandidateId, null, MemoryEngineContract.DEFAULT_INSPECT_RADIUS,
                            watchAnchor,
                            if (code == MemoryEngineContract.RESULT_PARTIAL_WRITE)
                                result?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                                    ?: "Value edited, but Freeze could not start"
                            else null,
                        )
                    } else {
                        state = state.copy(
                            message = result?.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL)
                                ?: outcome.exceptionOrNull()?.message
                                ?: "Inspector edit/Freeze was rejected",
                            messageIsError = true,
                        )
                    }
                }
            }
            return
        }
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
        if (destroyed || !hostForeground || !state.visible) return
        if (!state.busy && !liveRefreshPending && !visiblePageRequestPending) {
            when (state.activeTab) {
                MemoryEditorTab.RESULTS ->
                    if (state.runtimeToken != 0L && service != null) reloadVisibleRows()
                MemoryEditorTab.VARIABLES -> {
                    if (state.runtimeToken != 0L && service != null) reloadVisibleRows()
                    refreshSavedLive()
                }
                MemoryEditorTab.SCRIPTS -> refreshRecipeFreezeState()
                MemoryEditorTab.INSPECTOR -> Unit
            }
        }
        if (!destroyed && hostForeground && state.visible) {
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
        private const val SCRIPT_FREEZE_REFRESH_INTERVAL_MS = 1_000L
        private const val SAVED_RESOLVE_INTERVAL_MS = 1_000L
        private const val SAVED_REBOUND_NOTICE_MS = 4_000L
        private const val CAPABILITY_RETRY_IDLE_MS = 1_000L
        private val CAPABILITY_RETRY_DELAYS_MS = longArrayOf(80L, 160L, 320L, 640L)
    }
}
