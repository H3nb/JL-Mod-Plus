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
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Lightweight UI bridge. Heavy scans, recovery and Freeze remain in :memory_engine. */
class MemoryEditorComposeController(
    private val composeView: ComposeView,
    private val bubbleView: ComposeView,
) : MemoryEditorActions {
    private val context = composeView.context
    private val ipc: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MemoryEditorUiIpc").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var state by mutableStateOf(MemoryEditorUiState())
    private var service: IMemoryEngineService? = null
    private var bound = false
    private var destroyed = false
    private var refreshInFlight = false
    private var pendingStage: MemorySessionStage? = null
    private var pendingMode: MemorySearchMode? = null
    private var pendingUndoStage: MemorySessionStage? = null
    private var pendingPreviousStage: MemorySessionStage? = null
    private var pendingResetHistory = false
    private val stageHistory = ArrayDeque<MemorySessionStage>()

    private var bubbleOnRight = true
    private var bubbleVerticalFraction = 0.5f
    private var bubbleDownRawX = 0f
    private var bubbleDownRawY = 0f
    private var bubbleDragOffsetX = 0f
    private var bubbleDragOffsetY = 0f
    private var bubbleMoved = false
    private val bubbleTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val bubbleHost: View? = bubbleView.parent as? View
    private val bubbleLayoutListener = View.OnLayoutChangeListener {
            _, _, _, _, _, _, _, _, _ -> positionBubble(false)
    }

    private val callback = object : IMemoryEngineCallback.Stub() {
        override fun onOperationProgress(operationId: Long, scannedBytes: Long, totalBytes: Long) {
            post {
                if (state.busy && state.searching && totalBytes > 0L) {
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
                                message ?: resultMessage(resultCode)
                            },
                        )
                        reload()
                    }
                    return@post
                }

                val succeeded = resultCode == MemoryEngineContract.RESULT_OK
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

                state = state.copy(
                    busy = false,
                    searching = false,
                    scanBytesScanned = 0L,
                    scanBytesTotal = 0L,
                    resultCount = resultCount,
                    message = if (succeeded) {
                        message?.takeIf(String::isNotBlank)
                    } else {
                        message ?: resultMessage(resultCode)
                    },
                )
                reload()
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

    init {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        bubbleView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        installBubblePositioning()
    }

    fun toggleBubble(): Boolean {
        if (destroyed) return false
        val enabled = !state.bubbleEnabled
        state = state.copy(
            bubbleEnabled = enabled,
            message = null,
        )
        closeEditorUi()
        if (enabled) {
            showBubbleUi()
            bubbleView.post { positionBubble(false) }
        } else {
            bubbleView.visibility = View.GONE
            bubbleView.disposeComposition()
            disconnectEngine()
        }
        return enabled
    }

    fun open() {
        if (destroyed || !state.bubbleEnabled) return
        hidePlatformKeyboard()
        showEditorUi()
        composeView.visibility = View.VISIBLE
        bubbleView.visibility = View.GONE
        state = state.copy(visible = true, connecting = true, message = null)
        if (service == null) connectEngine() else refreshCapabilities()
    }

    override fun close() {
        closeEditorUi()
        bubbleView.visibility = if (state.bubbleEnabled) View.VISIBLE else View.GONE
    }

    fun isVisible(): Boolean = state.visible
    fun isBubbleEnabled(): Boolean = state.bubbleEnabled

    fun destroy() {
        if (destroyed) return
        destroyed = true
        bubbleHost?.removeOnLayoutChangeListener(bubbleLayoutListener)
        bubbleView.removeOnLayoutChangeListener(bubbleLayoutListener)
        composeView.disposeComposition()
        bubbleView.disposeComposition()
        disconnectEngine()
        ipc.shutdownNow()
    }

    private fun showEditorUi() {
        composeView.setContent {
            JLModPlusTheme {
                MemoryEditorStage3Root(state = state, actions = this)
            }
        }
    }

    private fun showBubbleUi() {
        bubbleView.setContent {
            JLModPlusTheme {
                MemoryEditorBubble(
                    visible = state.bubbleEnabled && !state.visible,
                    onOpen = ::open,
                    onTouch = ::handleBubbleTouch,
                )
            }
        }
        bubbleView.visibility = View.VISIBLE
    }

    private fun closeEditorUi() {
        clearSearchPresentation()
        state = state.copy(
            visible = false,
        )
        composeView.visibility = View.GONE
        composeView.disposeComposition()
    }

    private fun hidePlatformKeyboard() {
        val windowToken = composeView.windowToken ?: return
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun clearSearchPresentation() {
        state = state.copy(
            pageOffset = 0,
            results = emptyList(),
            watches = emptyList(),
            selected = emptySet(),
            watchTab = false,
            inspectorLoading = false,
            inspector = null,
        )
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
            // The isolated engine may already be gone.
        }
        service = null
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
    }

    override fun refreshCapabilities() = runIpc {
        val capabilities = service?.capabilities ?: return@runIpc
        post { applyCapabilities(capabilities) }
    }

    private fun installBubblePositioning() {
        bubbleHost?.addOnLayoutChangeListener(bubbleLayoutListener)
        bubbleView.addOnLayoutChangeListener(bubbleLayoutListener)
    }

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        val host = bubbleHost ?: return false
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bubbleView.animate().cancel()
                bubbleDownRawX = event.rawX
                bubbleDownRawY = event.rawY
                bubbleDragOffsetX = event.rawX - hostLocation[0] - bubbleView.x
                bubbleDragOffsetY = event.rawY - hostLocation[1] - bubbleView.y
                bubbleMoved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!bubbleMoved) {
                    val dx = event.rawX - bubbleDownRawX
                    val dy = event.rawY - bubbleDownRawY
                    bubbleMoved = dx * dx + dy * dy >
                        (bubbleTouchSlop * bubbleTouchSlop).toFloat()
                }
                if (bubbleMoved) {
                    bubbleBounds()?.let { bounds ->
                        bubbleView.x = (event.rawX - hostLocation[0] - bubbleDragOffsetX)
                            .coerceIn(bounds.left, bounds.right)
                        bubbleView.y = (event.rawY - hostLocation[1] - bubbleDragOffsetY)
                            .coerceIn(bounds.top, bounds.bottom)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (bubbleMoved) {
                    rememberBubblePosition()
                    positionBubble(true)
                } else {
                    open()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                rememberBubblePosition()
                positionBubble(true)
                true
            }
            else -> false
        }
    }

    private data class BubbleBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun bubbleBounds(): BubbleBounds? {
        val host = bubbleHost ?: return null
        val params = bubbleView.layoutParams as? FrameLayout.LayoutParams ?: return null
        if (host.width <= 0 || host.height <= 0 || bubbleView.width <= 0 || bubbleView.height <= 0) return null
        val left = params.leftMargin.toFloat()
        val top = params.topMargin.toFloat()
        return BubbleBounds(
            left = left,
            top = top,
            right = (host.width - bubbleView.width - params.rightMargin).coerceAtLeast(params.leftMargin).toFloat(),
            bottom = (host.height - bubbleView.height - params.bottomMargin).coerceAtLeast(params.topMargin).toFloat(),
        )
    }

    private fun rememberBubblePosition() {
        val bounds = bubbleBounds() ?: return
        bubbleOnRight = bubbleView.x + bubbleView.width / 2f >=
            (bounds.left + bounds.right + bubbleView.width) / 2f
        bubbleVerticalFraction = if (bounds.bottom > bounds.top) {
            ((bubbleView.y - bounds.top) / (bounds.bottom - bounds.top)).coerceIn(0f, 1f)
        } else 0.5f
    }

    private fun positionBubble(animate: Boolean) {
        val bounds = bubbleBounds() ?: return
        val targetX = if (bubbleOnRight) bounds.right else bounds.left
        val targetY = bounds.top + (bounds.bottom - bounds.top) * bubbleVerticalFraction
        if (animate) {
            bubbleView.animate().x(targetX).y(targetY).setDuration(180L).start()
        } else {
            bubbleView.animate().cancel()
            bubbleView.x = targetX
            bubbleView.y = targetY
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
            MemoryEngineContract.PREDICATE_EQUAL..MemoryEngineContract.PREDICATE_BETWEEN) {
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
            candidateId <= 0L || !MemoryEngineContract.isInspectRadius(radius)) return
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
                    bytes != null && bytes.size <= MemoryEngineContract.MAX_INSPECT_BYTES) {
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
                        message = message ?: resultMessage(result),
                    )
                }
            }
        }
    }

    override fun closeInspector() {
        state = state.copy(inspectorLoading = false, inspector = null)
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
        operate {
            if (state.watchTab) editCandidates(state.runtimeToken, ids, value.trim())
            else editResultGroups(state.runtimeToken, ids, type, value.trim())
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
                if (addresses) "0x${it.address.toULong().toString(16).uppercase()}"
                else MemoryEditorPageParser.value(it)
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
        state = state.copy(
            busy = true,
            searching = searching,
            scanBytesScanned = 0L,
            scanBytesTotal = 0L,
            message = null,
        )
        runIpc {
            val engine = service ?: throw RemoteException("Engine disconnected")
            engine.block()
        }
    }

    private fun reload(refreshAfterLoad: Boolean = false) = runIpc {
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
            offset.coerceAtMost(lastOffset.coerceAtMost(
                (Int.MAX_VALUE / PAGE_SIZE * PAGE_SIZE).toLong(),
            ).toInt())
        } else 0
        val resultRows = if (sessionStage == MemorySessionStage.CANDIDATES) {
            MemoryResultPageParser.parse(engine.getResultPage(token, safeOffset, PAGE_SIZE))
        } else emptyList()
        val watchRows = attachWatchMetadata(engine.getWatchPage(token))
        post {
            if (!state.visible) return@post
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
                selected = state.selected.intersect(
                    (resultRows.map { it.id } + watchRows.map { it.id }).toMutableSet(),
                ),
            )
            if (refreshAfterLoad && sessionStage == MemorySessionStage.CANDIDATES) refresh()
        }
    }

    private fun attachWatchMetadata(bundle: Bundle?): List<MemoryCandidateRow> {
        val rows = MemoryEditorPageParser.parse(bundle?.getLongArray(MemoryEngineContract.KEY_WATCH_ROWS))
        val labels = bundle?.getStringArray(MemoryEngineContract.KEY_WATCH_LABELS) ?: emptyArray()
        val modes = bundle?.getIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES) ?: intArrayOf()
        val paused = bundle?.getBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED) ?: booleanArrayOf()
        return rows.mapIndexed { index, row ->
            row.copy(
                label = labels.getOrElse(index) { "" },
                freezeMode = modes.getOrElse(index) { -1 },
                freezePaused = paused.getOrElse(index) { false },
            )
        }
    }

    private fun applyCapabilities(bundle: Bundle?) {
        val supported = bundle?.getBoolean(MemoryEngineContract.KEY_SUPPORTED) == true
        val token = bundle?.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN) ?: 0L
        val runtimeChanged = token != state.runtimeToken
        if (runtimeChanged) {
            stageHistory.clear()
            clearPendingTransition()
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
        if (!destroyed) composeView.post { if (!destroyed) block() }
    }

    private fun resultMessage(code: Int): String = when (code) {
        MemoryEngineContract.RESULT_CANCELLED -> context.getString(R.string.memory_editor_cancelled)
        MemoryEngineContract.RESULT_RESOURCE_LIMIT -> context.getString(R.string.memory_editor_resource_limit)
        MemoryEngineContract.RESULT_TARGET_LOST -> context.getString(R.string.memory_editor_target_lost)
        MemoryEngineContract.RESULT_IDENTITY_UNSAFE -> context.getString(R.string.memory_editor_identity_unsafe)
        MemoryEngineContract.RESULT_SAFETY_LIMIT -> context.getString(R.string.memory_editor_safety_limit)
        MemoryEngineContract.RESULT_UNSUPPORTED -> context.getString(R.string.memory_editor_write_unsupported)
        else -> context.getString(R.string.memory_editor_invalid_request)
    }

    internal companion object {
        const val PAGE_SIZE = 100
    }
}

private data class MemoryCandidatePresentation(
    val type: Int,
    val label: String,
)
