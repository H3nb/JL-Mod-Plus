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
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

internal data class MemoryEditorUiState(
    val visible: Boolean = false,
    val connected: Boolean = false,
    val supported: Boolean = false,
    val writeSupported: Boolean = false,
    val runtimeToken: Long = 0,
    val busy: Boolean = false,
    val resultCount: Long = 0,
    val pageOffset: Int = 0,
    val results: List<MemoryCandidateRow> = emptyList(),
    val watches: List<MemoryCandidateRow> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val watchTab: Boolean = false,
    val live: Boolean = false,
    val message: String? = null,
)

internal interface MemoryEditorActions {
    fun close()
    fun refreshCapabilities()
    fun startSearch(value: String, secondValue: String, type: Int, predicate: Int, unknown: Boolean, scope: Int)
    fun nextScan(value: String, secondValue: String, predicate: Int, compare: Int)
    fun groupSearch(types: IntArray, values: Array<String>, distance: Int, scope: Int)
    fun undo()
    fun refresh()
    fun setLive(enabled: Boolean)
    fun setWatchTab(watch: Boolean)
    fun toggleSelection(id: Long)
    fun selectVisible()
    fun invertVisible()
    fun clearSelection()
    fun editSelected(value: String)
    fun removeSelected(keep: Boolean)
    fun watchSelected(add: Boolean)
    fun labelWatch(id: Long, label: String)
    fun freezeSelected(mode: Int, first: String, second: String)
    fun clearFreezeSelected()
    fun copySelected(addresses: Boolean)
    fun previousPage()
    fun nextPage()
    fun cancel()
}

/** Lightweight UI bridge. Heavy scans, recovery and Freeze remain in :memory_engine. */
class MemoryEditorComposeController(private val composeView: ComposeView) : MemoryEditorActions {
    private val context = composeView.context
    private val ipc: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MemoryEditorUiIpc").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var state by mutableStateOf(MemoryEditorUiState())
    private var service: IMemoryEngineService? = null
    private var bound = false
    private var destroyed = false

    private val callback = object : IMemoryEngineCallback.Stub() {
        override fun onOperationFinished(
            operationId: Long,
            resultCode: Int,
            resultCount: Long,
            message: String?,
        ) {
            post {
                state = state.copy(
                    busy = false,
                    resultCount = resultCount,
                    message = if (resultCode == MemoryEngineContract.RESULT_OK) {
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
        composeView.setContent {
            JLModPlusTheme {
                MemoryEditorScreen(
                    state = state,
                    actions = this,
                )
            }
        }
    }

    fun open() {
        if (destroyed) return
        composeView.visibility = View.VISIBLE
        state = state.copy(visible = true, message = null)
        if (!bound) {
            bound = context.bindService(
                Intent(context, MemoryEngineService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) state = state.copy(message = context.getString(R.string.memory_editor_unsupported))
        } else {
            refreshCapabilities()
        }
    }

    override fun close() {
        state = state.copy(visible = false, live = false, selected = emptySet())
        composeView.visibility = View.GONE
    }

    fun isVisible(): Boolean = state.visible

    fun destroy() {
        if (destroyed) return
        destroyed = true
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
        ipc.shutdownNow()
    }

    override fun refreshCapabilities() = runIpc {
        val capabilities = service?.capabilities
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
        operate {
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
        operate {
            if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) {
                refineRelative(state.runtimeToken, predicate, compare, value.trim(), secondValue.trim())
            } else {
                refineKnown(state.runtimeToken, predicate, value.trim(), secondValue.trim())
            }
        }
    }

    override fun groupSearch(types: IntArray, values: Array<String>, distance: Int, scope: Int) {
        operate { startGroupSearch(state.runtimeToken, scope, types, values, distance) }
    }

    override fun undo() = operate { undoSearch(state.runtimeToken) }

    override fun refresh() {
        if (state.busy) return
        val ids = (if (state.watchTab) state.watches else state.results).map { it.id }.toLongArray()
        if (ids.isEmpty()) {
            reload()
        } else {
            operate { refreshCandidates(state.runtimeToken, ids) }
        }
    }

    override fun setLive(enabled: Boolean) {
        state = state.copy(live = enabled)
    }

    override fun setWatchTab(watch: Boolean) {
        state = state.copy(watchTab = watch, selected = emptySet())
        reload()
    }

    override fun toggleSelection(id: Long) {
        state = state.copy(selected = state.selected.toMutableSet().apply {
            if (!add(id)) remove(id)
        })
    }

    override fun selectVisible() {
        val visible = (if (state.watchTab) state.watches else state.results).mapTo(mutableSetOf()) { it.id }
        state = state.copy(selected = visible)
    }

    override fun invertVisible() {
        val visible = (if (state.watchTab) state.watches else state.results).map { it.id }
        state = state.copy(selected = state.selected.toMutableSet().apply {
            for (id in visible) if (!add(id)) remove(id)
        })
    }

    override fun clearSelection() {
        state = state.copy(selected = emptySet())
    }

    override fun editSelected(value: String) {
        val ids = selectedIds(max = MemoryEngineContract.MAX_MULTI_WRITE) ?: return
        operate { editCandidates(state.runtimeToken, ids, value.trim()) }
    }

    override fun removeSelected(keep: Boolean) {
        val ids = selectedIds() ?: return
        operate {
            if (keep) keepCandidates(state.runtimeToken, ids)
            else removeCandidates(state.runtimeToken, ids)
        }
        clearSelection()
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
        val ids = selectedIds() ?: return
        operate { clearFreeze(state.runtimeToken, ids) }
    }

    override fun copySelected(addresses: Boolean) {
        val rows = (state.results + state.watches).distinctBy { it.id }
            .filter { it.id in state.selected }
        if (rows.isEmpty()) return
        val text = rows.joinToString("\n") {
            if (addresses) "0x${it.address.toULong().toString(16).uppercase()}"
            else MemoryEditorPageParser.value(it)
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.memory_editor), text))
    }

    override fun previousPage() {
        state = state.copy(pageOffset = (state.pageOffset - PAGE_SIZE).coerceAtLeast(0), selected = emptySet())
        reload()
    }

    override fun nextPage() {
        if (state.pageOffset.toLong() + PAGE_SIZE < state.resultCount) {
            state = state.copy(pageOffset = state.pageOffset + PAGE_SIZE, selected = emptySet())
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

    private fun operate(block: IMemoryEngineService.() -> Long) {
        if (state.busy || state.runtimeToken == 0L) return
        state = state.copy(busy = true, message = null)
        runIpc {
            val engine = service ?: throw RemoteException("Engine disconnected")
            engine.block()
        }
    }

    private fun reload() = runIpc {
        val engine = service ?: return@runIpc
        val token = state.runtimeToken
        if (token == 0L) return@runIpc
        val offset = state.pageOffset
        val count = engine.getResultCount(token)
        val lastOffset = if (count == 0L) 0L else (count - 1L) / PAGE_SIZE * PAGE_SIZE
        val safeOffset = offset.coerceAtMost(lastOffset.coerceAtMost(
            (Int.MAX_VALUE / PAGE_SIZE * PAGE_SIZE).toLong(),
        ).toInt())
        val resultRows = MemoryEditorPageParser.parse(engine.getResultPage(token, safeOffset, PAGE_SIZE))
        val watchBundle = engine.getWatchPage(token)
        val watchRows = attachWatchMetadata(watchBundle)
        post {
            state = state.copy(
                resultCount = count,
                pageOffset = safeOffset,
                results = resultRows,
                watches = watchRows,
                selected = state.selected.intersect((resultRows + watchRows).mapTo(mutableSetOf()) { it.id }),
            )
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
        state = state.copy(
            connected = bundle != null,
            supported = supported,
            writeSupported = bundle?.getBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED) == true,
            runtimeToken = bundle?.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN) ?: 0L,
            message = if (supported) null else bundle?.getString(MemoryEngineContract.KEY_MESSAGE),
        )
        if (supported) reload()
    }

    private fun disconnected() = post {
        service = null
        state = state.copy(connected = false, supported = false, busy = false,
            message = context.getString(R.string.memory_editor_unsupported))
    }

    private fun runIpc(block: () -> Unit) {
        if (destroyed || ipc.isShutdown) return
        ipc.execute {
            try {
                block()
            } catch (_: RemoteException) {
                disconnected()
            } catch (exception: RuntimeException) {
                post { state = state.copy(busy = false, message = exception.message) }
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

@Composable
internal fun MemoryEditorScreen(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    if (!state.visible) return
    LaunchedEffect(state.visible, state.live, state.watchTab, state.busy) {
        while (state.visible && state.live) {
            delay(1_000)
            if (!state.busy) actions.refresh()
        }
    }
    LaunchedEffect(state.visible, state.connected, state.supported) {
        if (state.visible && state.connected && !state.supported) {
            repeat(4) {
                delay(250)
                actions.refreshCapabilities()
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .widthIn(max = 920.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.large,
        ) {
            MemoryEditorContent(state, actions)
        }
    }
}

@Composable
private fun MemoryEditorContent(state: MemoryEditorUiState, actions: MemoryEditorActions) {
    var value by remember { mutableStateOf("") }
    var secondValue by remember { mutableStateOf("") }
    var unknown by remember { mutableStateOf(false) }
    var type by remember { mutableIntStateOf(MemoryEngineContract.TYPE_AUTO) }
    var predicate by remember { mutableIntStateOf(MemoryEngineContract.PREDICATE_EQUAL) }
    var compare by remember { mutableIntStateOf(MemoryEngineContract.COMPARE_PREVIOUS) }
    var advanced by remember { mutableStateOf(false) }
    var scope by remember { mutableIntStateOf(MemoryEngineContract.SCOPE_JAVA_FAST) }
    var editDialog by remember { mutableStateOf(false) }
    var freezeDialog by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var searchControlsExpanded by remember(landscape) { mutableStateOf(!landscape) }
    LaunchedEffect(landscape, state.resultCount) {
        if (landscape && state.resultCount > 0L) searchControlsExpanded = false
        if (state.resultCount == 0L) searchControlsExpanded = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.memory_editor),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = actions::close,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_memory_editor_close),
                    contentDescription = stringResource(R.string.memory_editor_close),
                )
            }
        }
        if (!state.supported) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message ?: stringResource(R.string.memory_editor_unsupported))
                Button(onClick = actions::refreshCapabilities, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.memory_editor_refresh))
                }
            }
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !state.watchTab,
                onClick = { actions.setWatchTab(false) },
                label = { Text(stringResource(R.string.memory_editor_results_tab)) },
            )
            FilterChip(
                selected = state.watchTab,
                onClick = { actions.setWatchTab(true) },
                label = { Text(stringResource(R.string.memory_editor_watch)) },
            )
        }

        if (!state.watchTab && landscape && state.resultCount > 0L) {
            TextButton(
                onClick = { searchControlsExpanded = !searchControlsExpanded },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(
                    if (searchControlsExpanded) R.string.memory_editor_hide_search
                    else R.string.memory_editor_show_search,
                ))
            }
        }

        if (!state.watchTab && searchControlsExpanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.weight(1f),
                        enabled = !unknown && !state.busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.memory_editor_search_hint)) },
                    )
                    ChoiceMenu(
                        value = type,
                        values = VALUE_TYPES,
                        label = { typeName(it) },
                        onChange = { type = it },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f).clickable { unknown = !unknown }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = unknown, onCheckedChange = null)
                        Text(stringResource(R.string.memory_editor_unknown))
                    }
                    ChoiceMenu(
                        value = predicate,
                        values = if (state.resultCount > 0) REFINE_PREDICATES else KNOWN_PREDICATES,
                        label = { predicateName(it) },
                        onChange = { predicate = it },
                    )
                }
                val needsSecondValue = predicate == MemoryEngineContract.PREDICATE_BETWEEN ||
                    predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE ||
                    predicate == MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE
                if (!unknown && needsSecondValue) {
                    OutlinedTextField(
                        value = secondValue,
                        onValueChange = { secondValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.memory_editor_max_value)) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { actions.startSearch(value, secondValue, type, predicate, unknown, scope) },
                        enabled = !state.busy && (unknown || value.isNotBlank()) &&
                            (!needsSecondValue || secondValue.isNotBlank()),
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text(stringResource(R.string.memory_editor_new_search)) }
                    Button(
                        onClick = { actions.nextScan(value, secondValue, predicate, compare) },
                        enabled = !state.busy && state.resultCount > 0 &&
                            (predicate in MemoryEngineContract.PREDICATE_CHANGED..MemoryEngineContract.PREDICATE_DECREASED ||
                                value.isNotBlank()) && (!needsSecondValue || secondValue.isNotBlank()),
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text(stringResource(R.string.memory_editor_next_scan)) }
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(stringResource(R.string.memory_editor_advanced))
                }
                if (advanced) {
                    AdvancedSearch(scope, { scope = it }, compare, { compare = it }, actions, state.busy)
                }
            }
        }

        state.message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        val rows = if (state.watchTab) state.watches else state.results
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.watchTab) stringResource(R.string.memory_editor_watch)
                else pluralStringResource(
                    R.plurals.memory_editor_results,
                    state.resultCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    state.resultCount,
                ),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions::selectVisible, enabled = rows.isNotEmpty()) {
                Text(stringResource(R.string.memory_editor_select_visible))
            }
            TextButton(onClick = actions::invertVisible, enabled = rows.isNotEmpty()) {
                Text(stringResource(R.string.memory_editor_invert_visible))
            }
        }

        CandidateList(
            rows = rows,
            selected = state.selected,
            watch = state.watchTab,
            onToggle = actions::toggleSelection,
            onLabel = actions::labelWatch,
            modifier = Modifier.weight(1f),
        )

        if (!state.watchTab && state.resultCount > MemoryEditorComposeController.PAGE_SIZE) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = actions::previousPage, enabled = state.pageOffset > 0) {
                    Text(stringResource(R.string.memory_editor_previous_page))
                }
                Text("${state.pageOffset + 1}–${(state.pageOffset + rows.size).coerceAtMost(Int.MAX_VALUE)}")
                TextButton(
                    onClick = actions::nextPage,
                    enabled = state.pageOffset.toLong() + rows.size < state.resultCount,
                ) { Text(stringResource(R.string.memory_editor_next_page)) }
            }
        }

        if (state.selected.isNotEmpty()) {
            SelectionActions(
                state = state,
                actions = actions,
                onEdit = { editDialog = true },
                onFreeze = { freezeDialog = true },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = actions::undo, enabled = !state.busy && !state.watchTab) {
                    Text(stringResource(R.string.memory_editor_undo))
                }
                TextButton(onClick = actions::refresh, enabled = !state.busy) {
                    Text(stringResource(R.string.memory_editor_refresh))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.memory_editor_live))
                    Switch(checked = state.live, onCheckedChange = actions::setLive)
                }
                if (state.busy) TextButton(onClick = actions::cancel) {
                    Text(stringResource(R.string.memory_editor_cancel))
                }
            }
        }
    }

    if (editDialog) EditDialog(
        enabled = state.writeSupported,
        onDismiss = { editDialog = false },
        onApply = { editDialog = false; actions.editSelected(it) },
    )
    if (freezeDialog) FreezeDialog(
        enabled = state.writeSupported,
        onDismiss = { freezeDialog = false },
        onApply = { mode, first, second ->
            freezeDialog = false
            actions.freezeSelected(mode, first, second)
        },
    )
}

@Composable
private fun CandidateList(
    rows: List<MemoryCandidateRow>,
    selected: Set<Long>,
    watch: Boolean,
    onToggle: (Long) -> Unit,
    onLabel: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = rows.groupBy { it.address }.toList()
    if (groups.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.memory_editor_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(groups, key = { it.first }) { (address, aliases) ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val primary = aliases.first()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            primary.label.ifBlank { MemoryEditorPageParser.value(primary) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "0x${address.toULong().toString(16).uppercase()} · ${stateName(primary.state)}" +
                                if (primary.relocations > 0) " · ↪${primary.relocations}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (watch) WatchLabelButton(primary, onLabel)
                }
                aliases.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onToggle(row.id) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = row.id in selected, onCheckedChange = null)
                        Text(typeName(row.type), modifier = Modifier.widthIn(min = 52.dp), fontWeight = FontWeight.Medium)
                        Text(MemoryEditorPageParser.value(row), modifier = Modifier.weight(1f))
                        if (row.freezeMode >= 0) {
                            Text(
                                "❄ ${freezeName(row.freezeMode)}" +
                                    if (row.freezePaused) " · ${stringResource(R.string.memory_editor_freeze_paused)}" else "",
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WatchLabelButton(row: MemoryCandidateRow, onLabel: (Long, String) -> Unit) {
    var dialog by remember(row.id) { mutableStateOf(false) }
    TextButton(onClick = { dialog = true }) { Text(stringResource(R.string.memory_editor_watch_label)) }
    if (dialog) {
        var value by remember(row.label) { mutableStateOf(row.label) }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(stringResource(R.string.memory_editor_watch_label)) },
            text = { OutlinedTextField(value, { value = it.take(64) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { dialog = false; onLabel(row.id, value) }) {
                Text(stringResource(R.string.memory_editor_apply))
            } },
            dismissButton = { TextButton(onClick = { dialog = false }) {
                Text(stringResource(android.R.string.cancel))
            } },
        )
    }
}

@Composable
private fun SelectionActions(
    state: MemoryEditorUiState,
    actions: MemoryEditorActions,
    onEdit: () -> Unit,
    onFreeze: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Text(
            pluralStringResource(R.plurals.memory_editor_selected, state.selected.size, state.selected.size),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!state.watchTab) {
                TextButton(onClick = onEdit, enabled = state.writeSupported) { Text(stringResource(R.string.memory_editor_edit)) }
                TextButton(onClick = { actions.watchSelected(true) }) { Text(stringResource(R.string.memory_editor_watch)) }
                TextButton(onClick = onFreeze, enabled = state.writeSupported) { Text(stringResource(R.string.memory_editor_freeze)) }
                TextButton(onClick = { actions.removeSelected(false) }) { Text(stringResource(R.string.memory_editor_remove)) }
                TextButton(onClick = { actions.removeSelected(true) }) { Text(stringResource(R.string.memory_editor_keep)) }
            } else {
                TextButton(onClick = { actions.watchSelected(false) }) { Text(stringResource(R.string.memory_editor_remove)) }
                TextButton(onClick = onFreeze, enabled = state.writeSupported) { Text(stringResource(R.string.memory_editor_freeze)) }
                TextButton(onClick = actions::clearFreezeSelected) { Text(stringResource(R.string.memory_editor_unfreeze)) }
            }
            TextButton(onClick = { actions.copySelected(false) }) { Text(stringResource(R.string.memory_editor_copy_values)) }
            TextButton(onClick = { actions.copySelected(true) }) { Text(stringResource(R.string.memory_editor_copy_addresses)) }
            TextButton(onClick = actions::clearSelection) { Text(stringResource(R.string.memory_editor_clear_selection)) }
        }
    }
}

@Composable
private fun EditDialog(enabled: Boolean, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_edit)) },
        text = {
            Column {
                if (!enabled) Text(stringResource(R.string.memory_editor_write_unsupported), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.memory_editor_replacement)) })
            }
        },
        confirmButton = { TextButton(onClick = { onApply(value) }, enabled = enabled && value.isNotBlank()) {
            Text(stringResource(R.string.memory_editor_apply))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun FreezeDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (Int, String, String) -> Unit,
) {
    var mode by remember { mutableIntStateOf(MemoryEngineContract.FREEZE_LOCK) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_freeze)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceMenu(mode, FREEZE_MODES, { freezeName(it) }) { mode = it }
                OutlinedTextField(
                    first, { first = it },
                    label = { Text(stringResource(if (mode == MemoryEngineContract.FREEZE_RANGE) R.string.memory_editor_min_value else R.string.memory_editor_search_hint)) },
                )
                if (mode == MemoryEngineContract.FREEZE_RANGE) {
                    OutlinedTextField(second, { second = it }, label = { Text(stringResource(R.string.memory_editor_max_value)) })
                }
            }
        },
        confirmButton = { TextButton(
            onClick = { onApply(mode, first, second) },
            enabled = enabled && first.isNotBlank() && (mode != MemoryEngineContract.FREEZE_RANGE || second.isNotBlank()),
        ) { Text(stringResource(R.string.memory_editor_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun AdvancedSearch(
    scope: Int,
    onScope: (Int) -> Unit,
    compare: Int,
    onCompare: (Int) -> Unit,
    actions: MemoryEditorActions,
    busy: Boolean,
) {
    var group by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("128") }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceMenu(scope, intArrayOf(0, 1), {
                stringResource(if (it == 0) R.string.memory_editor_scope_fast else R.string.memory_editor_scope_thorough)
            }, onScope)
            ChoiceMenu(compare, intArrayOf(0, 1), {
                stringResource(if (it == 0) R.string.memory_editor_previous else R.string.memory_editor_initial)
            }, onCompare)
        }
        OutlinedTextField(
            group, { group = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.memory_editor_group_search)) },
            placeholder = { Text(stringResource(R.string.memory_editor_group_hint)) },
        )
        OutlinedTextField(
            distance, { distance = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.memory_editor_group_distance)) },
            singleLine = true,
        )
        val parsed = remember(group) { parseGroup(group) }
        OutlinedButton(
            onClick = { actions.groupSearch(parsed!!.first, parsed.second, distance.toIntOrNull() ?: 128, scope) },
            enabled = !busy && parsed != null && (distance.toIntOrNull() ?: 0) in 1..4096,
        ) { Text(stringResource(R.string.memory_editor_group_search)) }
    }
}

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
            "short", "i16" -> MemoryEngineContract.TYPE_SHORT
            "char", "u16" -> MemoryEngineContract.TYPE_CHAR
            "int", "i32" -> MemoryEngineContract.TYPE_INT
            "long", "i64" -> MemoryEngineContract.TYPE_LONG
            "float", "f32" -> MemoryEngineContract.TYPE_FLOAT
            "double", "f64" -> MemoryEngineContract.TYPE_DOUBLE
            else -> return null
        }
        values[index] = part.substring(separator + 1).trim()
    }
    return types to values
}

@Composable
private fun ChoiceMenu(
    value: Int,
    values: IntArray,
    label: @Composable (Int) -> String,
    onChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
            Text(label(value), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { expanded = false; onChange(option) },
                )
            }
        }
    }
}

private fun typeName(type: Int): String = when (type) {
    MemoryEngineContract.TYPE_AUTO -> "Auto"
    MemoryEngineContract.TYPE_BYTE -> "I8"
    MemoryEngineContract.TYPE_SHORT -> "I16"
    MemoryEngineContract.TYPE_CHAR -> "U16"
    MemoryEngineContract.TYPE_INT -> "I32"
    MemoryEngineContract.TYPE_LONG -> "I64"
    MemoryEngineContract.TYPE_FLOAT -> "F32"
    MemoryEngineContract.TYPE_DOUBLE -> "F64"
    else -> "?"
}

@Composable
private fun predicateName(predicate: Int): String = when (predicate) {
    0 -> "="; 1 -> "≠"; 2 -> ">"; 3 -> "<"; 4 -> "≥"; 5 -> "≤"
    6 -> stringResource(R.string.memory_editor_predicate_between)
    7 -> stringResource(R.string.memory_editor_predicate_changed)
    8 -> stringResource(R.string.memory_editor_predicate_unchanged)
    9 -> stringResource(R.string.memory_editor_predicate_increased)
    10 -> stringResource(R.string.memory_editor_predicate_decreased)
    11 -> stringResource(R.string.memory_editor_predicate_increased_by)
    12 -> stringResource(R.string.memory_editor_predicate_decreased_by)
    13 -> stringResource(R.string.memory_editor_predicate_changed_by)
    14 -> stringResource(R.string.memory_editor_predicate_increased_range)
    15 -> stringResource(R.string.memory_editor_predicate_decreased_range)
    else -> "?"
}

@Composable
private fun stateName(state: Int): String = stringResource(when (state) {
    MemoryEngineContract.CANDIDATE_STABLE -> R.string.memory_editor_candidate_stable
    MemoryEngineContract.CANDIDATE_RELOCATING -> R.string.memory_editor_candidate_relocating
    MemoryEngineContract.CANDIDATE_AMBIGUOUS -> R.string.memory_editor_candidate_ambiguous
    else -> R.string.memory_editor_candidate_lost
})

@Composable
private fun freezeName(mode: Int): String = stringResource(when (mode) {
    MemoryEngineContract.FREEZE_LOCK -> R.string.memory_editor_freeze_lock
    MemoryEngineContract.FREEZE_MINIMUM -> R.string.memory_editor_freeze_minimum
    MemoryEngineContract.FREEZE_MAXIMUM -> R.string.memory_editor_freeze_maximum
    else -> R.string.memory_editor_freeze_range
})

private val VALUE_TYPES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
private val KNOWN_PREDICATES = intArrayOf(0, 1, 2, 3, 4, 5, 6)
private val REFINE_PREDICATES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
private val FREEZE_MODES = intArrayOf(0, 1, 2, 3)
