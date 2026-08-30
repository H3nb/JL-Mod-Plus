from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one marker, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


native_java = "app/src/main/java/ru/playsoftware/j2meloader/memory/NativeMemoryEngine.java"
replace_once(
    native_java,
    "\tstatic native long resultCount();\n\n\tstatic native long[] resultPage(int offset, int limit);\n",
    "\tstatic native long resultCount();\n\n\tstatic native int historyDepth();\n\n\tstatic native long[] resultPage(int offset, int limit);\n",
)

native_cpp = "app/src/main/cpp/memory/memory_engine.cpp"
replace_once(
    native_cpp,
    '''extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPage(
''',
    '''extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_historyDepth(JNIEnv *,
                                                                       jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gHistory.size() > static_cast<size_t>(std::numeric_limits<jint>::max())
                   ? std::numeric_limits<jint>::max()
                   : static_cast<jint>(gHistory.size());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPage(
''',
)

service = "app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryEngineService.java"
replace_once(
    service,
    '''\t\t@Override
\t\tpublic long removeCandidates(long token, long[] candidateIds) {
\t\t\treturn enqueue(token, false, 0,
\t\t\t\t\t() -> NativeMemoryEngine.filter(candidateIds, false));
\t\t}

\t\t@Override
\t\tpublic long keepCandidates(long token, long[] candidateIds) {
\t\t\treturn enqueue(token, false, 0,
\t\t\t\t\t() -> NativeMemoryEngine.filter(candidateIds, true));
\t\t}
''',
    '''\t\t@Override
\t\tpublic long removeCandidates(long token, long[] candidateIds) {
\t\t\treturn enqueue(token, false, 0, () -> {
\t\t\t\tint result = NativeMemoryEngine.filter(candidateIds, false);
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tadvanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
\t\t\t\t}
\t\t\t\treturn result;
\t\t\t});
\t\t}

\t\t@Override
\t\tpublic long keepCandidates(long token, long[] candidateIds) {
\t\t\treturn enqueue(token, false, 0, () -> {
\t\t\t\tint result = NativeMemoryEngine.filter(candidateIds, true);
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) {
\t\t\t\t\tadvanceSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES);
\t\t\t\t}
\t\t\t\treturn result;
\t\t\t});
\t\t}
''',
)
replace_once(
    service,
    '''\t\t@Override
\t\tpublic long undoSearch(long token) {
\t\t\treturn enqueue(token, false, 0, () -> {
\t\t\t\tint result = NativeMemoryEngine.undo();
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) undoSearchSession();
\t\t\t\treturn result;
\t\t\t});
\t\t}
''',
    '''\t\t@Override
\t\tpublic long undoSearch(long token) {
\t\t\treturn enqueue(token, false, 0, () -> {
\t\t\t\tsynchronizeSearchHistoryDepth(NativeMemoryEngine.historyDepth());
\t\t\t\tint result = NativeMemoryEngine.undo();
\t\t\t\tif (result == MemoryEngineContract.RESULT_OK) undoSearchSession();
\t\t\t\treturn result;
\t\t\t});
\t\t}
''',
)
replace_once(
    service,
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
''',
    '''\tprivate Bundle searchSessionInfo(long token) {
\t\tBundle bundle = new Bundle();
\t\tboolean current = isCurrentToken(token);
\t\tint nativeHistoryDepth = current ? NativeMemoryEngine.historyDepth() : 0;
\t\tsynchronized (searchSessionLock) {
\t\t\tif (current) trimSearchHistoryLocked(nativeHistoryDepth);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
\t\t\t\t\tcurrent ? searchSessionStage : MemoryEngineContract.SEARCH_SESSION_EMPTY);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_MODE,
\t\t\t\t\tcurrent ? searchSessionMode : MemoryEngineContract.SEARCH_MODE_KNOWN);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
\t\t\t\t\tcurrent ? searchRequestedType : MemoryEngineContract.TYPE_AUTO);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_SCOPE,
\t\t\t\t\tcurrent ? searchSessionScope : MemoryEngineContract.SCOPE_JAVA_FAST);
\t\t\tbundle.putInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH,
\t\t\t\t\tcurrent ? Math.min(nativeHistoryDepth, searchStageHistory.size()) : 0);
\t\t}
\t\treturn bundle;
\t}
''',
)
replace_once(
    service,
    '''\tprivate void advanceSearchSession(int nextStage) {
\t\tsynchronized (searchSessionLock) {
\t\t\tif (searchStageHistory.size() >= MemoryEngineContract.MAX_SEARCH_HISTORY) {
\t\t\t\tsearchStageHistory.removeFirst();
\t\t\t}
\t\t\tsearchStageHistory.addLast(searchSessionStage);
\t\t\tsearchSessionStage = nextStage;
\t\t}
\t}

\tprivate void undoSearchSession() {
''',
    '''\tprivate void advanceSearchSession(int nextStage) {
\t\tint nativeHistoryDepth = NativeMemoryEngine.historyDepth();
\t\tsynchronized (searchSessionLock) {
\t\t\tsearchStageHistory.addLast(searchSessionStage);
\t\t\ttrimSearchHistoryLocked(nativeHistoryDepth);
\t\t\tsearchSessionStage = nextStage;
\t\t}
\t}

\tprivate void synchronizeSearchHistoryDepth(int nativeDepth) {
\t\tsynchronized (searchSessionLock) {
\t\t\ttrimSearchHistoryLocked(nativeDepth);
\t\t}
\t}

\tprivate void trimSearchHistoryLocked(int nativeDepth) {
\t\tint boundedDepth = Math.max(0, Math.min(nativeDepth,
\t\t\t\tMemoryEngineContract.MAX_SEARCH_HISTORY));
\t\twhile (searchStageHistory.size() > boundedDepth) {
\t\t\tsearchStageHistory.removeFirst();
\t\t}
\t}

\tprivate void undoSearchSession() {
''',
)

print("Memory Editor native/service Undo history synchronization patch applied")
