from pathlib import Path


def text(path: str) -> str:
    return Path(path).read_text()


def write(path: str, value: str) -> None:
    Path(path).write_text(value)


def replace_once(path: str, old: str, new: str) -> None:
    value = text(path)
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one marker, found {count}: {old[:120]!r}")
    write(path, value.replace(old, new, 1))


def replace_between(path: str, start: str, end: str, replacement: str) -> None:
    value = text(path)
    a = value.find(start)
    if a < 0:
        raise SystemExit(f"{path}: start marker missing: {start!r}")
    b = value.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f"{path}: end marker missing: {end!r}")
    write(path, value[:a] + replacement + value[b:])


# -----------------------------------------------------------------------------
# MemoryInputCompose: lifecycle-safe internal keypad and mixed-type validation.
# -----------------------------------------------------------------------------
input_path = "app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryInputCompose.kt"
replace_once(
    input_path,
    "import androidx.compose.runtime.CompositionLocalProvider\nimport androidx.compose.runtime.SideEffect\n",
    "import androidx.compose.runtime.CompositionLocalProvider\nimport androidx.compose.runtime.DisposableEffect\nimport androidx.compose.runtime.LaunchedEffect\nimport androidx.compose.runtime.SideEffect\n",
)
replace_once(
    input_path,
    '''internal fun MemoryInputSpec.isComplete(text: String): Boolean {
    if (text.isBlank() || !acceptsPartial(text)) return false
    return when (kind) {
        MemoryInputKind.SIGNED_INTEGER,
        MemoryInputKind.UNSIGNED_INTEGER,
        MemoryInputKind.POSITIVE_INTEGER -> {
            val value = text.toLongOrNull() ?: return false
            (minLong == null || value >= minLong) && (maxLong == null || value <= maxLong)
        }
        MemoryInputKind.FLOATING -> if (floatingBits == 32) {
            text.toFloatOrNull()?.isFinite() == true
        } else {
            text.toDoubleOrNull()?.isFinite() == true
        }
    }
}
''',
    '''internal fun MemoryInputSpec.isComplete(text: String): Boolean {
    if (text.isBlank() || !acceptsPartial(text)) return false
    return when (kind) {
        MemoryInputKind.SIGNED_INTEGER,
        MemoryInputKind.UNSIGNED_INTEGER,
        MemoryInputKind.POSITIVE_INTEGER -> {
            val value = text.toLongOrNull() ?: return false
            (minLong == null || value >= minLong) && (maxLong == null || value <= maxLong)
        }
        MemoryInputKind.FLOATING -> if (floatingBits == 32) {
            text.toFloatOrNull()?.isFinite() == true
        } else {
            text.toDoubleOrNull()?.isFinite() == true
        }
    }
}

internal fun memoryInputCompleteForTypes(value: String, types: Collection<Int>): Boolean =
    types.isNotEmpty() && types.all { MemoryInputSpec.forType(it).isComplete(value) }
''',
)
replace_once(
    input_path,
    '''    fun hide() {
        binding = null
        onTextChange = null
    }
''',
    '''    fun deactivate(id: Any) {
        if (binding?.id === id) hide()
    }

    fun hide() {
        binding = null
        onTextChange = null
    }
''',
)
replace_once(
    input_path,
    '''internal fun MemoryInputArea(
    modifier: Modifier = Modifier,
    sideDockInLandscape: Boolean = true,
    content: @Composable () -> Unit,
) {
    val session = remember { MemoryInputSession() }
    val focusManager = LocalFocusManager.current
    val landscape = sideDockInLandscape &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
''',
    '''internal fun MemoryInputArea(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    sideDockInLandscape: Boolean = true,
    content: @Composable () -> Unit,
) {
    val session = remember { MemoryInputSession() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(active) {
        if (!active) {
            session.hide()
            focusManager.clearFocus(force = true)
        }
    }
    val landscape = sideDockInLandscape &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
''',
)
replace_once(
    input_path,
    '''    val id = remember { Any() }
    SideEffect { session.sync(id, value, spec, onValueChange) }
''',
    '''    val id = remember { Any() }
    DisposableEffect(id) {
        onDispose { session.deactivate(id) }
    }
    LaunchedEffect(spec) {
        if (value.isNotEmpty() && !spec.acceptsPartial(value)) onValueChange("")
    }
    SideEffect { session.sync(id, value, spec, onValueChange) }
''',
)

# -----------------------------------------------------------------------------
# UI models: authoritative service session metadata.
# -----------------------------------------------------------------------------
models_path = "app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryEditorModels.kt"
replace_once(
    models_path,
    '''internal enum class MemorySessionStage {
    EMPTY,
    UNKNOWN_BASELINE,
    CANDIDATES,
}
''',
    '''internal enum class MemorySessionStage {
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
''',
)
replace_once(
    models_path,
    '''    val searchMode: MemorySearchMode = MemorySearchMode.KNOWN,
    val sessionStage: MemorySessionStage = MemorySessionStage.EMPTY,
    val inspectorLoading: Boolean = false,
''',
    '''    val searchMode: MemorySearchMode = MemorySearchMode.KNOWN,
    val sessionStage: MemorySessionStage = MemorySessionStage.EMPTY,
    val requestedType: Int = MemoryEngineContract.TYPE_AUTO,
    val searchScope: Int = MemoryEngineContract.SCOPE_JAVA_FAST,
    val canUndo: Boolean = false,
    val inspectorLoading: Boolean = false,
''',
)

# -----------------------------------------------------------------------------
# Contract + AIDL: expose authoritative search session info.
# -----------------------------------------------------------------------------
contract_path = "app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryEngineContract.java"
replace_once(
    contract_path,
    '''\tpublic static final int COMPARE_PREVIOUS = 0;
\tpublic static final int COMPARE_INITIAL = 1;
''',
    '''\tpublic static final int COMPARE_PREVIOUS = 0;
\tpublic static final int COMPARE_INITIAL = 1;

\tpublic static final int SEARCH_SESSION_EMPTY = 0;
\tpublic static final int SEARCH_SESSION_UNKNOWN_BASELINE = 1;
\tpublic static final int SEARCH_SESSION_CANDIDATES = 2;
\tpublic static final int SEARCH_MODE_KNOWN = 0;
\tpublic static final int SEARCH_MODE_UNKNOWN = 1;
\tpublic static final int SEARCH_MODE_GROUP = 2;
\tpublic static final int MAX_SEARCH_HISTORY = 8;
''',
)
replace_once(
    contract_path,
    '''\tpublic static final String KEY_MESSAGE = "message";
\tpublic static final String KEY_INSPECT_RESULT = "inspectResult";
''',
    '''\tpublic static final String KEY_MESSAGE = "message";
\tpublic static final String KEY_SEARCH_SESSION_STAGE = "searchSessionStage";
\tpublic static final String KEY_SEARCH_MODE = "searchMode";
\tpublic static final String KEY_SEARCH_REQUESTED_TYPE = "searchRequestedType";
\tpublic static final String KEY_SEARCH_SCOPE = "searchScope";
\tpublic static final String KEY_SEARCH_HISTORY_DEPTH = "searchHistoryDepth";
\tpublic static final String KEY_INSPECT_RESULT = "inspectResult";
''',
)
aidl_path = "app/src/main/aidl/ru/playsoftware/j2meloader/memory/IMemoryEngineService.aidl"
replace_once(
    aidl_path,
    '''    long getResultCount(long runtimeToken);
    // Offset/limit count unique raw addresses. Rows may include typed aliases for those addresses.
''',
    '''    long getResultCount(long runtimeToken);
    Bundle getSearchSessionInfo(long runtimeToken);
    // Offset/limit count unique raw addresses. Rows may include typed aliases for those addresses.
''',
)

# -----------------------------------------------------------------------------
# Engine service: session metadata lives beside native state, not in UI guesses.
# -----------------------------------------------------------------------------
service_path = "app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryEngineService.java"
replace_once(
    service_path,
    "import java.util.ArrayList;\nimport java.util.Arrays;\n",
    "import java.util.ArrayDeque;\nimport java.util.ArrayList;\nimport java.util.Arrays;\n",
)
replace_once(
    service_path,
    '''\tprivate volatile ScheduledFuture<?> freezeTask;
\tprivate final IMemoryTargetCallback targetCallback = new IMemoryTargetCallback.Stub() {
''',
    '''\tprivate volatile ScheduledFuture<?> freezeTask;
\tprivate final Object searchSessionLock = new Object();
\tprivate final ArrayDeque<Integer> searchStageHistory = new ArrayDeque<>();
\tprivate int searchSessionStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
\tprivate int searchSessionMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
\tprivate int searchRequestedType = MemoryEngineContract.TYPE_AUTO;
\tprivate int searchSessionScope = MemoryEngineContract.SCOPE_JAVA_FAST;
\tprivate final IMemoryTargetCallback targetCallback = new IMemoryTargetCallback.Stub() {
''',
)
replace_once(
    service_path,
    '''\t\t@Override
\t\tpublic long startKnownSearch(long token, int scope, int type, int predicate,
\t\t                             String first, String second) {
\t\t\treturn enqueue(token, true, scope,
\t\t\t\t\t() -> NativeMemoryEngine.startKnown(type, predicate, first, second));
\t\t}
''',
    '''\t\t@Override
\t\tpublic long startKnownSearch(long token, int scope, int type, int predicate,
\t\t                             String first, String second) {
\t\t\treturn enqueue(token, true, scope, () -> {
\t\t\t\tint result = NativeMemoryEngine.startKnown(type, predicate, first, second);
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tresetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
\t\t\t\t\t\t\tMemoryEngineContract.SEARCH_MODE_KNOWN, type, scope);
\t\t\t\t}
\t\t\t\treturn result;
\t\t\t});
\t\t}
''',
)
replace_once(
    service_path,
    '''\t\t@Override
\t\tpublic long startUnknownSearch(long token, int scope, int type) {
\t\t\treturn enqueue(token, true, scope, () -> NativeMemoryEngine.startUnknown(type));
\t\t}
''',
    '''\t\t@Override
\t\tpublic long startUnknownSearch(long token, int scope, int type) {
\t\t\treturn enqueue(token, true, scope, () -> {
\t\t\t\tint result = NativeMemoryEngine.startUnknown(type);
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tresetSearchSession(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE,
\t\t\t\t\t\t\tMemoryEngineContract.SEARCH_MODE_UNKNOWN, type, scope);
\t\t\t\t}
\t\t\t\treturn result;
\t\t\t});
\t\t}
''',
)
replace_once(
    service_path,
    '''\t\t@Override
\t\tpublic long startGroupSearch(long token, int scope, int[] types,
\t\t                             String[] values, int maxDistance) {
\t\t\treturn enqueue(token, true, scope,
\t\t\t\t\t() -> NativeMemoryEngine.startGroup(types, values, maxDistance));
\t\t}
''',
    '''\t\t@Override
\t\tpublic long startGroupSearch(long token, int scope, int[] types,
\t\t                             String[] values, int maxDistance) {
\t\t\treturn enqueue(token, true, scope, () -> {
\t\t\t\tint result = NativeMemoryEngine.startGroup(types, values, maxDistance);
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tresetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
\t\t\t\t\t\t\tMemoryEngineContract.SEARCH_MODE_GROUP,
\t\t\t\t\t\t\tMemoryEngineContract.TYPE_AUTO, scope);
\t\t\t\t}
\t\t\t\treturn result;
\t\t\t});
\t\t}
''',
)
replace_once(
    service_path,
    '''\t\t\t\tint ready = refreshWithRecovery(token, new long[]{anchorCandidateId});
\t\t\t\treturn ready == MemoryEngineContract.RESULT_OK
\t\t\t\t\t\t? NativeMemoryEngine.startNearby(anchorCandidateId, radius, type, predicate,
\t\t\t\t\t\t\t\tfirst, second)
\t\t\t\t\t\t: ready;
''',
    '''\t\t\t\tint ready = refreshWithRecovery(token, new long[]{anchorCandidateId});
\t\t\t\tif (ready != MemoryEngineContract.RESULT_OK) {
\t\t\t\t\treturn ready;
\t\t\t\t}
\t\t\t\tint result = NativeMemoryEngine.startNearby(anchorCandidateId, radius, type, predicate,
\t\t\t\t\t\tfirst, second);
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tresetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
\t\t\t\t\t\t\tMemoryEngineContract.SEARCH_MODE_KNOWN, type, configuredScope);
\t\t\t\t}
\t\t\t\treturn result;
''',
)
replace_once(
    service_path,
    '''\t\t\t\tresult = configureTarget(token, configuredScope);
\t\t\t\treturn result == MemoryEngineContract.RESULT_OK
\t\t\t\t\t\t? NativeMemoryEngine.recoverKnown(predicate, first, second)
\t\t\t\t\t\t: result;
\t\t\t});
''',
    '''\t\t\t\tresult = configureTarget(token, configuredScope);
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tresult = NativeMemoryEngine.recoverKnown(predicate, first, second);
\t\t\t\t}
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tadvanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
\t\t\t\t}
\t\t\t\treturn result;
\t\t\t});
''',
)
# The normal refineKnown success returns before the recovery block, so account for it too.
replace_once(
    service_path,
    '''\t\t\t\tint result = NativeMemoryEngine.refineKnown(predicate, first, second);
\t\t\t\tif (result != MemoryEngineContract.RESULT_IDENTITY_UNSAFE) {
\t\t\t\t\treturn result;
\t\t\t\t}
''',
    '''\t\t\t\tint result = NativeMemoryEngine.refineKnown(predicate, first, second);
\t\t\t\tif (result != MemoryEngineContract.RESULT_IDENTITY_UNSAFE) {
\t\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\t\tadvanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
\t\t\t\t\t}
\t\t\t\t\treturn result;
\t\t\t\t}
''',
)
replace_once(
    service_path,
    '''\t\t@Override
\t\tpublic long refineRelative(long token, int predicate, int compareTarget,
\t\t                           String first, String second) {
\t\t\treturn enqueue(token, false, 0,
\t\t\t\t\t() -> NativeMemoryEngine.refineRelative(predicate, compareTarget, first, second));
\t\t}

\t\t@Override
\t\tpublic long undoSearch(long token) {
\t\t\treturn enqueue(token, false, 0, NativeMemoryEngine::undo);
\t\t}
''',
    '''\t\t@Override
\t\tpublic long refineRelative(long token, int predicate, int compareTarget,
\t\t                           String first, String second) {
\t\t\treturn enqueue(token, false, 0, () -> {
\t\t\t\tint result = NativeMemoryEngine.refineRelative(predicate, compareTarget, first, second);
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tadvanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
\t\t\t\t}
\t\t\t\treturn result;
\t\t\t});
\t\t}

\t\t@Override
\t\tpublic long undoSearch(long token) {
\t\t\treturn enqueue(token, false, 0, () -> {
\t\t\t\tint result = NativeMemoryEngine.undo();
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) undoSearchSession();
\t\t\t\treturn result;
\t\t\t});
\t\t}
''',
)
replace_once(
    service_path,
    '''\t\t@Override
\t\tpublic long getResultCount(long token) {
\t\t\treturn isCurrentToken(token) ? NativeMemoryEngine.resultCount() : 0L;
\t\t}

\t\t@Override
\t\tpublic long[] getResultPage(long token, int offset, int limit) {
''',
    '''\t\t@Override
\t\tpublic long getResultCount(long token) {
\t\t\treturn isCurrentToken(token) ? NativeMemoryEngine.resultCount() : 0L;
\t\t}

\t\t@Override
\t\tpublic Bundle getSearchSessionInfo(long token) {
\t\t\treturn searchSessionInfo(token);
\t\t}

\t\t@Override
\t\tpublic long[] getResultPage(long token, int offset, int limit) {
''',
)
replace_once(
    service_path,
    '''\t\t@Override
\t\tpublic void clearSearch(long token) {
\t\t\tif (isCurrentToken(token)) {
\t\t\t\tworker.execute(NativeMemoryEngine::clearSearch);
\t\t\t}
\t\t}
''',
    '''\t\t@Override
\t\tpublic void clearSearch(long token) {
\t\t\tif (isCurrentToken(token)) {
\t\t\t\tclearSearchSession();
\t\t\t\tworker.execute(NativeMemoryEngine::clearSearch);
\t\t\t}
\t\t}
''',
)
replace_once(
    service_path,
    '''\t\t\tint result = NativeMemoryEngine.configureTarget(pid, pageSize, token, runs);
\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\tconfiguredToken = token;
\t\t\t\tconfiguredScope = scope;
\t\t\t}
''',
    '''\t\t\tlong previousToken = configuredToken;
\t\t\tint result = NativeMemoryEngine.configureTarget(pid, pageSize, token, runs);
\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\tif (previousToken != token) clearSearchSession();
\t\t\t\tconfiguredToken = token;
\t\t\t\tconfiguredScope = scope;
\t\t\t}
''',
)
replace_once(
    service_path,
    '''\tprivate boolean isCurrentToken(long token) {
''',
    '''\tprivate Bundle searchSessionInfo(long token) {
\t\tBundle bundle = new Bundle();
\t\tboolean current = isCurrentToken(token);
\t\tsynchronized (searchSessionLock) {
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
\t\t\t\t\tcurrent ? searchSessionStage : MemoryEngineContract.SEARCH_SESSION_EMPTY);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_MODE,
\t\t\t\t\tcurrent ? searchSessionMode : MemoryEngineContract.SEARCH_MODE_KNOWN);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
\t\t\t\t\tcurrent ? searchRequestedType : MemoryEngineContract.TYPE_AUTO);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_SCOPE,
\t\t\t\t\tcurrent ? searchSessionScope : MemoryEngineContract.SCOPE_JAVA_FAST);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH,
\t\t\t\t\tcurrent ? searchStageHistory.size() : 0);
\t\t}
\t\treturn bundle;
\t}

\tprivate void resetSearchSession(int stage, int mode, int requestedType, int scope) {
\t\tsynchronized (searchSessionLock) {
\t\t\tsearchStageHistory.clear();
\t\t\tsearchSessionStage = stage;
\t\t\tsearchSessionMode = mode;
\t\t\tsearchRequestedType = requestedType;
\t\t\tsearchSessionScope = scope;
\t\t}
\t}

\tprivate void advanceSearchSession(int nextStage) {
\t\tsynchronized (searchSessionLock) {
\t\t\tif (searchStageHistory.size() >= MemoryEngineContract.MAX_SEARCH_HISTORY) {
\t\t\t\tsearchStageHistory.removeFirst();
\t\t\t}
\t\t\tsearchStageHistory.addLast(searchSessionStage);
\t\t\tsearchSessionStage = nextStage;
\t\t}
\t}

\tprivate void undoSearchSession() {
\t\tsynchronized (searchSessionLock) {
\t\t\tif (!searchStageHistory.isEmpty()) searchSessionStage = searchStageHistory.removeLast();
\t\t}
\t}

\tprivate void clearSearchSession() {
\t\tsynchronized (searchSessionLock) {
\t\t\tsearchStageHistory.clear();
\t\t\tsearchSessionStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
\t\t\tsearchSessionMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
\t\t\tsearchRequestedType = MemoryEngineContract.TYPE_AUTO;
\t\t\tsearchSessionScope = MemoryEngineContract.SCOPE_JAVA_FAST;
\t\t}
\t}

\tprivate boolean isCurrentToken(long token) {
''',
)
replace_once(
    service_path,
    '''\tprivate void invalidateTarget() {
\t\tconfiguredToken = 0L;
\t\twatchLabels.clear();
''',
    '''\tprivate void invalidateTarget() {
\t\tconfiguredToken = 0L;
\t\tclearSearchSession();
\t\twatchLabels.clear();
''',
)

# -----------------------------------------------------------------------------
# Controller: consume authoritative state and harden grouped/Freeze actions.
# -----------------------------------------------------------------------------
controller_path = "app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryEditorController.kt"
replace_once(
    controller_path,
    '''        state = state.copy(
            sessionStage = MemorySessionStage.EMPTY,
            searchMode = MemorySearchMode.KNOWN,
            resultCount = 0,
''',
    '''        state = state.copy(
            sessionStage = MemorySessionStage.EMPTY,
            searchMode = MemorySearchMode.KNOWN,
            requestedType = MemoryEngineContract.TYPE_AUTO,
            searchScope = MemoryEngineContract.SCOPE_JAVA_FAST,
            canUndo = false,
            resultCount = 0,
''',
)
replace_once(
    controller_path,
    '''    override fun removeSelected(keep: Boolean) {
        val ids = selectedIds() ?: return
        operate {
            if (keep) keepCandidates(state.runtimeToken, ids)
            else removeCandidates(state.runtimeToken, ids)
        }
        clearSelection()
    }
''',
    '''    override fun removeSelected(keep: Boolean) {
        val ids = if (state.watchTab) {
            selectedIds()
        } else {
            val addresses = state.results.asSequence()
                .filter { it.id in state.selected }
                .mapTo(mutableSetOf()) { it.address }
            state.results.asSequence()
                .filter { it.address in addresses }
                .map { it.id }
                .distinct()
                .toList()
                .takeIf { it.isNotEmpty() }
                ?.toLongArray()
        } ?: return
        operate {
            if (keep) keepCandidates(state.runtimeToken, ids)
            else removeCandidates(state.runtimeToken, ids)
        }
    }
''',
)
replace_once(
    controller_path,
    '''    override fun clearFreezeSelected() {
        val ids = selectedIds() ?: return
        operate { clearFreeze(state.runtimeToken, ids) }
    }
''',
    '''    override fun clearFreezeSelected() {
        val ids = state.watches.asSequence()
            .filter { it.id in state.selected && it.freezeMode >= 0 }
            .map { it.id }
            .distinct()
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.toLongArray() ?: return
        operate { clearFreeze(state.runtimeToken, ids) }
    }
''',
)
replace_between(
    controller_path,
    '''    private fun reload(refreshAfterLoad: Boolean = false) = runIpc {
''',
    '''    private fun attachWatchMetadata(bundle: Bundle?): List<MemoryCandidateRow> {
''',
    '''    private fun reload(refreshAfterLoad: Boolean = false) = runIpc {
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
            MemoryEditorPageParser.parse(engine.getResultPage(token, safeOffset, PAGE_SIZE))
        } else emptyList()
        val watchRows = attachWatchMetadata(engine.getWatchPage(token))
        post {
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
                selected = state.selected.intersect((resultRows + watchRows).mapTo(mutableSetOf()) { it.id }),
            )
            if (refreshAfterLoad && sessionStage == MemorySessionStage.CANDIDATES) refresh()
        }
    }

''',
)
replace_once(
    controller_path,
    '''            searchMode = if (runtimeChanged) MemorySearchMode.KNOWN else state.searchMode,
            resultCount = if (runtimeChanged) 0L else state.resultCount,
''',
    '''            searchMode = if (runtimeChanged) MemorySearchMode.KNOWN else state.searchMode,
            requestedType = if (runtimeChanged) MemoryEngineContract.TYPE_AUTO else state.requestedType,
            searchScope = if (runtimeChanged) MemoryEngineContract.SCOPE_JAVA_FAST else state.searchScope,
            canUndo = if (runtimeChanged) false else state.canUndo,
            resultCount = if (runtimeChanged) 0L else state.resultCount,
''',
)
replace_once(
    controller_path,
    '''            searchMode = MemorySearchMode.KNOWN,
            resultCount = 0L,
''',
    '''            searchMode = MemorySearchMode.KNOWN,
            requestedType = MemoryEngineContract.TYPE_AUTO,
            searchScope = MemoryEngineContract.SCOPE_JAVA_FAST,
            canUndo = false,
            resultCount = 0L,
''',
)

# -----------------------------------------------------------------------------
# Compose workspace: deterministic compact candidate mode + audit hardening.
# -----------------------------------------------------------------------------
compose_path = "app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryEditorCompose.kt"
value = text(compose_path)
value = value.replace("import android.content.res.Configuration\n", "")
value = value.replace("import androidx.compose.ui.platform.LocalConfiguration\n", "")
write(compose_path, value)
replace_once(
    compose_path,
    '''            MemoryInputArea(modifier = Modifier.fillMaxSize()) {
                MemoryEditorContent(state, actions)
            }
''',
    '''            MemoryInputArea(modifier = Modifier.fillMaxSize(), active = state.visible) {
                MemoryEditorContent(state, actions)
            }
''',
)
replace_once(
    compose_path,
    '''    var advanced by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var freezeDialog by remember { mutableStateOf(false) }
    var detailRow by remember { mutableStateOf<MemoryCandidateRow?>(null) }
    var detailAliases by remember { mutableStateOf<List<MemoryCandidateRow>>(emptyList()) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var searchControlsExpanded by remember(landscape) { mutableStateOf(true) }

    LaunchedEffect(state.sessionStage, state.searchMode) {
        searchMode = state.searchMode
''',
    '''    var advanced by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var freezeDialog by remember { mutableStateOf(false) }
    var refineDialog by remember { mutableStateOf(false) }
    var detailRow by remember { mutableStateOf<MemoryCandidateRow?>(null) }
    var detailAliases by remember { mutableStateOf<List<MemoryCandidateRow>>(emptyList()) }

    LaunchedEffect(state.sessionStage, state.searchMode, state.requestedType, state.searchScope) {
        searchMode = state.searchMode
        type = state.requestedType
        scope = state.searchScope
''',
)
# Remove obsolete effect that asynchronously collapsed controls.
replace_once(
    compose_path,
    '''    LaunchedEffect(landscape, state.sessionStage, state.resultCount) {
        if (state.sessionStage == MemorySessionStage.CANDIDATES && state.resultCount > 0L) {
            searchControlsExpanded = false
        } else if (state.sessionStage != MemorySessionStage.CANDIDATES) {
            searchControlsExpanded = true
        }
    }

''',
    "",
)
replace_between(
    compose_path,
    '''\n\nif (!state.watchTab) {
''',
    '''\n\n        state.message?.let {
''',
    '''\n\n        if (!state.watchTab) {
            if (state.sessionStage == MemorySessionStage.CANDIDATES) {
                CompactRefineStrip(
                    resultCount = state.resultCount,
                    value = value,
                    onValue = { value = it },
                    secondValue = secondValue,
                    onSecondValue = { secondValue = it },
                    type = type,
                    predicate = predicate,
                    onPredicate = { predicate = it },
                    compare = compare,
                    busy = state.busy,
                    onExpand = { refineDialog = true },
                    actions = actions,
                )
            } else {
                SearchWorkspace(
                    state = state,
                    searchMode = searchMode,
                    onSearchMode = { searchMode = it },
                    value = value,
                    onValue = { value = it },
                    secondValue = secondValue,
                    onSecondValue = { secondValue = it },
                    type = type,
                    onType = { type = it },
                    predicate = predicate,
                    onPredicate = { predicate = it },
                    compare = compare,
                    onCompare = { compare = it },
                    scope = scope,
                    onScope = { scope = it },
                    advanced = advanced,
                    onAdvanced = { advanced = !advanced },
                    actions = actions,
                )
            }
        }
''',
)
replace_once(
    compose_path,
    '''    if (editDialog) {
''',
    '''    if (refineDialog) {
        RefineControlsDialog(
            resultCount = state.resultCount,
            type = type,
            predicate = predicate,
            onPredicate = { predicate = it },
            compare = compare,
            onCompare = { compare = it },
            value = value,
            onValue = { value = it },
            secondValue = secondValue,
            onSecondValue = { secondValue = it },
            busy = state.busy,
            onDismiss = { refineDialog = false },
            onStartOver = {
                refineDialog = false
                actions.startOver()
            },
            actions = actions,
        )
    }

    if (editDialog) {
''',
)
replace_once(
    compose_path,
    '''if (freezeDialog) {
    val freezeRows = selectedRows(state)
    val freezeTypes = freezeRows.map { it.type }.distinct()
    FreezeDialog(
        enabled = state.writeSupported,
        type = freezeTypes.singleOrNull() ?: MemoryEngineContract.TYPE_AUTO,
        initialValue = freezeRows.firstOrNull()?.let(MemoryEditorPageParser::value).orEmpty(),
''',
    '''if (freezeDialog) {
    val freezeRows = selectedRows(state)
    val freezeTypes = freezeRows.map { it.type }.distinct()
    FreezeDialog(
        enabled = state.writeSupported,
        types = freezeTypes,
        initialValue = freezeRows.firstOrNull()?.let(MemoryEditorPageParser::value).orEmpty(),
''',
)
# Compact action label should describe the actual operation and overflow needs semantics.
replace_once(
    compose_path,
    '''                Button(
                    onClick = { actions.nextScan(value, secondValue, predicate, compare) },
                    enabled = !busy && resultCount > 0L &&
                        refineInputValid(type, predicate, value, secondValue),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.memory_editor_search_action))
                }
                IconButton(onClick = onExpand) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
''',
    '''                Button(
                    onClick = { actions.nextScan(value, secondValue, predicate, compare) },
                    enabled = !busy && resultCount > 0L &&
                        refineInputValid(type, predicate, value, secondValue),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.memory_editor_next_scan))
                }
                val moreDescription = stringResource(R.string.memory_editor_more)
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier.semantics { contentDescription = moreDescription },
                ) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
''',
)
# Add deterministic full refine dialog before compact predicate menu.
replace_once(
    compose_path,
    '''@Composable
private fun CompactPredicateMenu(selected: Int, onChange: (Int) -> Unit) {
''',
    '''@Composable
private fun RefineControlsDialog(
    resultCount: Long,
    type: Int,
    predicate: Int,
    onPredicate: (Int) -> Unit,
    compare: Int,
    onCompare: (Int) -> Unit,
    value: String,
    onValue: (String) -> Unit,
    secondValue: String,
    onSecondValue: (String) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
    onStartOver: () -> Unit,
    actions: MemoryEditorActions,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_editor_refine_results)) },
        text = {
            MemoryInputArea(sideDockInLandscape = false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickRefinePredicates(predicate, onPredicate)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceMenu(predicate, REFINE_PREDICATES, { predicateName(it) }, onPredicate)
                        if (predicate >= MemoryEngineContract.PREDICATE_CHANGED) {
                            CompareMenu(compare, onCompare)
                        }
                    }
                    RefineValueFields(type, predicate, value, onValue, secondValue, onSecondValue)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    actions.nextScan(value, secondValue, predicate, compare)
                },
                enabled = !busy && resultCount > 0L &&
                    refineInputValid(type, predicate, value, secondValue),
            ) {
                Text(stringResource(R.string.memory_editor_next_scan))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onStartOver, enabled = !busy) {
                    Text(stringResource(R.string.memory_editor_start_over))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.memory_editor_done))
                }
            }
        },
    )
}

@Composable
private fun CompactPredicateMenu(selected: Int, onChange: (Int) -> Unit) {
''',
)
# Result aliases and Watch values must never grow a row unexpectedly.
replace_once(
    compose_path,
    '''                Text(
                    group.aliases.joinToString(" · ") { typeShortName(it.type) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
''',
    '''                Text(
                    group.aliases.joinToString(" · ") { typeShortName(it.type) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
''',
)
replace_once(
    compose_path,
    '''                Text(
                    MemoryEditorPageParser.value(row),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
''',
    '''                Text(
                    MemoryEditorPageParser.value(row),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 156.dp),
                )
''',
)
# Selection overflow accessibility.
replace_once(
    compose_path,
    '''            Box {
                IconButton(onClick = { more = true }) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
''',
    '''            Box {
                val moreDescription = stringResource(R.string.memory_editor_more)
                IconButton(
                    onClick = { more = true },
                    modifier = Modifier.semantics { contentDescription = moreDescription },
                ) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
''',
)
replace_once(
    compose_path,
    '''        ActionIconButton(
            R.drawable.ic_history,
            R.string.memory_editor_undo,
            actions::undo,
            enabled = !state.busy && !state.watchTab,
        )
''',
    '''        ActionIconButton(
            R.drawable.ic_history,
            R.string.memory_editor_undo,
            actions::undo,
            enabled = !state.busy && !state.watchTab && state.canUndo,
        )
''',
)
replace_once(
    compose_path,
    '''private fun FreezeDialog(
    enabled: Boolean,
    type: Int,
    initialValue: String,
    onDismiss: () -> Unit,
    onApply: (Int, String, String) -> Unit,
) {
    var mode by remember { mutableIntStateOf(MemoryEngineContract.FREEZE_LOCK) }
    var first by remember(initialValue) { mutableStateOf(initialValue) }
    var second by remember { mutableStateOf("") }
    val spec = MemoryInputSpec.forType(type)
''',
    '''private fun FreezeDialog(
    enabled: Boolean,
    types: List<Int>,
    initialValue: String,
    onDismiss: () -> Unit,
    onApply: (Int, String, String) -> Unit,
) {
    var mode by remember { mutableIntStateOf(MemoryEngineContract.FREEZE_LOCK) }
    var first by remember(initialValue, types) {
        mutableStateOf(initialValue.takeIf { memoryInputCompleteForTypes(it, types) }.orEmpty())
    }
    var second by remember { mutableStateOf("") }
    val inputSpec = MemoryInputSpec.forType(types.singleOrNull() ?: MemoryEngineContract.TYPE_AUTO)
''',
)
replace_once(
    compose_path,
    '''                        spec = spec,
                        label = stringResource(
                            if (mode == MemoryEngineContract.FREEZE_RANGE) R.string.memory_editor_min_value
''',
    '''                        spec = inputSpec,
                        label = stringResource(
                            if (mode == MemoryEngineContract.FREEZE_RANGE) R.string.memory_editor_min_value
''',
)
replace_once(
    compose_path,
    '''                            spec = spec,
                            label = stringResource(R.string.memory_editor_max_value),
''',
    '''                            spec = inputSpec,
                            label = stringResource(R.string.memory_editor_max_value),
''',
)
replace_once(
    compose_path,
    '''                enabled = enabled && spec.isComplete(first) &&
                    (mode != MemoryEngineContract.FREEZE_RANGE || spec.isComplete(second)),
''',
    '''                enabled = enabled && memoryInputCompleteForTypes(first, types) &&
                    (mode != MemoryEngineContract.FREEZE_RANGE ||
                        memoryInputCompleteForTypes(second, types)),
''',
)

# -----------------------------------------------------------------------------
# Inspector: clear contextual Nearby state on close/runtime change + cleanup.
# -----------------------------------------------------------------------------
inspector_path = "app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryInspectorCompose.kt"
value = text(inspector_path)
value = value.replace("import androidx.compose.foundation.layout.heightIn\n", "")
value = value.replace("import androidx.compose.material3.Button\n", "")
value = value.replace("import androidx.compose.material3.OutlinedTextField\n", "")
value = value.replace("import androidx.compose.runtime.Composable\n", "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\n")
write(inspector_path, value)
replace_once(
    inspector_path,
    '''    var nearbyAnchor by remember { mutableStateOf<MemoryNearbyAnchor?>(null) }

    MemoryEditorScreen(state = state, actions = actions)
''',
    '''    var nearbyAnchor by remember { mutableStateOf<MemoryNearbyAnchor?>(null) }
    LaunchedEffect(state.visible, state.runtimeToken) {
        if (!state.visible || state.runtimeToken == 0L) nearbyAnchor = null
    }

    MemoryEditorScreen(state = state, actions = actions)
''',
)

# -----------------------------------------------------------------------------
# Tests for the audit fixes.
# -----------------------------------------------------------------------------
input_test = "app/src/test/java/ru/playsoftware/j2meloader/memory/MemoryInputComposeTest.kt"
replace_once(
    input_test,
    '''    @Test
    fun signToggleTargetsExponentWhenCursorIsAfterExponent() {
''',
    '''    @Test
    fun mixedTypeValidationFailsClosedForNarrowCandidates() {
        assertTrue(memoryInputCompleteForTypes(
            "100",
            listOf(MemoryEngineContract.TYPE_BYTE, MemoryEngineContract.TYPE_INT),
        ))
        assertFalse(memoryInputCompleteForTypes(
            "200",
            listOf(MemoryEngineContract.TYPE_BYTE, MemoryEngineContract.TYPE_INT),
        ))
        assertFalse(memoryInputCompleteForTypes(
            "1.5",
            listOf(MemoryEngineContract.TYPE_INT, MemoryEngineContract.TYPE_DOUBLE),
        ))
    }

    @Test
    fun inputSessionDeactivatesOnlyItsCurrentField() {
        val session = MemoryInputSession()
        val first = Any()
        val second = Any()
        session.activate(first, "1", MemoryInputSpec.forType(MemoryEngineContract.TYPE_INT)) { }
        session.deactivate(second)
        assertTrue(session.active)
        session.deactivate(first)
        assertFalse(session.active)
    }

    @Test
    fun signToggleTargetsExponentWhenCursorIsAfterExponent() {
''',
)
editor_test = "app/src/test/java/ru/playsoftware/j2meloader/memory/MemoryEditorComposeTest.kt"
replace_once(
    editor_test,
    '''    @Test
    fun newSearchNeverForwardsARelativeRefinePredicate() {
''',
    '''    @Test
    fun engineSessionMetadataMapsWithoutCountHeuristics() {
        assertEquals(
            MemorySessionStage.CANDIDATES,
            memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_CANDIDATES),
        )
        assertEquals(
            MemorySessionStage.UNKNOWN_BASELINE,
            memorySessionStageFromEngine(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE),
        )
        assertEquals(
            MemorySearchMode.GROUP,
            memorySearchModeFromEngine(MemoryEngineContract.SEARCH_MODE_GROUP),
        )
        assertEquals(MemorySessionStage.EMPTY, memorySessionStageFromEngine(-1))
        assertEquals(MemorySearchMode.KNOWN, memorySearchModeFromEngine(-1))
    }

    @Test
    fun newSearchNeverForwardsARelativeRefinePredicate() {
''',
)
contract_test = "app/src/test/java/ru/playsoftware/j2meloader/memory/MemoryEngineContractTest.java"
replace_once(
    contract_test,
    '''\t\tassertEquals(8, MemoryEngineContract.MAX_GROUP_VALUES);
\t\tassertEquals(128, MemoryEngineContract.DEFAULT_INSPECT_RADIUS);
''',
    '''\t\tassertEquals(8, MemoryEngineContract.MAX_GROUP_VALUES);
\t\tassertEquals(8, MemoryEngineContract.MAX_SEARCH_HISTORY);
\t\tassertEquals(128, MemoryEngineContract.DEFAULT_INSPECT_RADIUS);
''',
)

print("Memory Editor stabilization patches applied successfully")
