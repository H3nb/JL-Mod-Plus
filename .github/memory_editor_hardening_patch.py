from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(text.replace(old, new, 1))


engine_path = 'app/src/main/java/io/github/h3nb/jlmodplus/memory/ManagedJavaMemoryEngine.java'
p = Path(engine_path)
text = p.read_text()

old = 'Object valueObject = scan.queue.removeFirst();\n\t\t\tscan.visit(valueObject);'
new = 'Object valueObject = scan.dequeue();\n\t\t\tscan.visit(valueObject);'
if text.count(old) != 1:
    raise SystemExit(f'engine first dequeue match count={text.count(old)}')
text = text.replace(old, new, 1)

old = 'scan.visit(scan.queue.removeFirst());'
if text.count(old) != 3:
    raise SystemExit(f'engine compact dequeue match count={text.count(old)}')
text = text.replace(old, 'scan.visit(scan.dequeue());')

old = '''\t\tvoid enqueue(@Nullable Object value) {
\t\t\tif (value == null || queued.containsKey(value) || visited.containsKey(value)) return;
\t\t\tif (queue.size() >= limits.maxPending) {
\t\t\t\tthrow new ResourceLimitException("Managed traversal pending queue exceeds the resource limit");
\t\t\t}
\t\t\treserveTraversalBytes(TRAVERSAL_ENTRY_BYTES + QUEUE_REFERENCE_BYTES);
\t\t\tqueued.put(value, Boolean.TRUE);
\t\t\tqueue.addLast(value);
\t\t}

\t\tboolean checkpoint() {
\t\t\tif (!isOperationActive(token, operationEpoch) || visitCount > limits.maxVisited) return false;
\t\t\tif (!revision.withinStorageBudget(limits, transientStorageBytes)) {
\t\t\t\tthrow new ResourceLimitException("Managed traversal storage exceeds the resource limit");
\t\t\t}
\t\t\treturn true;
\t\t}
'''
new = '''\t\tvoid enqueue(@Nullable Object value) {
\t\t\tif (value == null || queued.containsKey(value) || visited.containsKey(value)) return;
\t\t\tif (queue.size() >= limits.maxPending) {
\t\t\t\tthrow new ResourceLimitException("Managed traversal pending queue exceeds the resource limit");
\t\t\t}
\t\t\treserveTraversalBytes(TRAVERSAL_ENTRY_BYTES + QUEUE_REFERENCE_BYTES);
\t\t\tqueued.put(value, Boolean.TRUE);
\t\t\tqueue.addLast(value);
\t\t}

\t\tObject dequeue() {
\t\t\tObject value = queue.removeFirst();
\t\t\treleaseTraversalBytes(QUEUE_REFERENCE_BYTES);
\t\t\treturn value;
\t\t}

\t\tboolean checkpoint() {
\t\t\tif (!isOperationActive(token, operationEpoch)) return false;
\t\t\tif (visitCount > limits.maxVisited) {
\t\t\t\tthrow new ResourceLimitException("Managed visited-owner limit exceeded");
\t\t\t}
\t\t\tif (!revision.withinStorageBudget(limits, transientStorageBytes)) {
\t\t\t\tthrow new ResourceLimitException("Managed traversal storage exceeds the resource limit");
\t\t\t}
\t\t\treturn true;
\t\t}
'''
if text.count(old) != 1:
    raise SystemExit(f'engine enqueue/checkpoint block match count={text.count(old)}')
text = text.replace(old, new, 1)

start = text.index('\tManagedOperationResult freezeTick(long token, long operationEpoch) {')
end = text.index('\n\tManagedOperationResult clearSearchResult(', start)
replacement = '''\tManagedOperationResult freezeTick(long token, long operationEpoch) {
\t\tWatchSnapshot snapshot;
\t\tsynchronized (stateLock) {
\t\t\tif (!isCurrentLocked(token)) return failureLocked(
\t\t\t\t\tMemoryEngineContract.RESULT_TARGET_LOST, "MIDlet runtime changed or ended");
\t\t\tsnapshot = watches.frozenSnapshot(owners);
\t\t}
\t\tint eligible = 0;
\t\tint written = 0;
\t\tint rejected = 0;
\t\tlong[] readback = new long[1];
\t\tfor (int index = 0; index < snapshot.count; index++) {
\t\t\tsynchronized (stateLock) {
\t\t\t\tif (!isOperationActive(token, operationEpoch)) return failureLocked(
\t\t\t\t\t\tMemoryEngineContract.RESULT_CANCELLED, "Managed Freeze tick was cancelled");
\t\t\t\tlong id = snapshot.ids[index];
\t\t\t\tint watchIndex = watches.indexOf(id);
\t\t\t\tif (watchIndex < 0 || !watches.freeze[watchIndex] || watches.freezePaused[watchIndex]) {
\t\t\t\t\tcontinue;
\t\t\t\t}
\t\t\t\tOwnerBucket owner = watches.owners[watchIndex];
\t\t\t\tint slot = watches.slots[watchIndex];
\t\t\t\tif (owner != snapshot.owners[index] || slot != snapshot.slots[index]) continue;
\t\t\t\teligible++;
\t\t\t\tObject strongOwner = owner == null ? null : owner.strongOwner();
\t\t\t\tif (owner == null || (owner.kind != KIND_STATIC_FIELD && strongOwner == null)) {
\t\t\t\t\twatches.freezePaused[watchIndex] = true;
\t\t\t\t\trejected++;
\t\t\t\t\tcontinue;
\t\t\t\t}
\t\t\t\ttry {
\t\t\t\t\tint type = valueTypeFor(owner, slot);
\t\t\t\t\tlong freezeValue = watches.freezeValues[watchIndex];
\t\t\t\t\twriteTyped(owner, slot, strongOwner, type, freezeValue);
\t\t\t\t\tif (readTyped(owner, slot, strongOwner, readback)
\t\t\t\t\t\t\t&& writeConfirmed(readback[0], freezeValue)) {
\t\t\t\t\t\twritten++;
\t\t\t\t\t\twatches.previous[watchIndex] = readback[0];
\t\t\t\t\t} else {
\t\t\t\t\t\twatches.freezePaused[watchIndex] = true;
\t\t\t\t\t\trejected++;
\t\t\t\t\t}
\t\t\t\t} catch (IllegalAccessException | RuntimeException | LinkageError error) {
\t\t\t\t\twatches.freezePaused[watchIndex] = true;
\t\t\t\t\trejected++;
\t\t\t\t}
\t\t\t}
\t\t}
\t\tint code = written == eligible ? MemoryEngineContract.RESULT_OK
\t\t\t\t: written > 0 ? MemoryEngineContract.RESULT_PARTIAL_WRITE
\t\t\t\t: eligible == 0 ? MemoryEngineContract.RESULT_OK
\t\t\t\t: MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
\t\treturn result(token, code,
\t\t\t\t"Managed Freeze Lock tick wrote " + written + " of " + eligible,
\t\t\t\teligible, written, rejected, 0);
\t}
'''
text = text[:start] + replacement + text[end:]

old = '''\tprivate Schema schemaFor(Class<?> type, ClassLoader loader) {
\t\tsynchronized (stateLock) {
\t\t\tSchema cached = schemaCache.get(type);
\t\t\tif (cached != null) return cached;
\t\t\tSchema schema = Schema.build(type, loader);
\t\t\tschemaCache.put(type, schema);
\t\t\treturn schema;
\t\t}
\t}
'''
new = '''\tprivate Schema schemaFor(Class<?> type, ClassLoader loader) {
\t\tsynchronized (stateLock) {
\t\t\tSchema cached = schemaCache.get(type);
\t\t\tif (cached != null) return cached;
\t\t}
\t\tSchema built = Schema.build(type, loader);
\t\tsynchronized (stateLock) {
\t\t\tSchema cached = schemaCache.get(type);
\t\t\tif (cached != null) return cached;
\t\t\tschemaCache.put(type, built);
\t\t\treturn built;
\t\t}
\t}
'''
if text.count(old) != 1:
    raise SystemExit(f'engine schemaFor match count={text.count(old)}')
text = text.replace(old, new, 1)
if text.count('"Managed Unknown traversal failed safely"') != 1:
    raise SystemExit('unexpected Unknown traversal message count')
text = text.replace('"Managed Unknown traversal failed safely"',
                    '"Managed graph traversal failed safely"', 1)
p.write_text(text)

value_path = 'app/src/main/java/io/github/h3nb/jlmodplus/memory/ManagedJavaValue.java'
replace_once(value_path,
    '\tprivate static final BigInteger UNSIGNED_LONG_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);\n',
    '\tprivate static final BigInteger UNSIGNED_LONG_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);\n\tprivate static final int MAX_INPUT_CHARS = 96;\n')

value_p = Path(value_path)
value_text = value_p.read_text()
old_guard = '\t\tif (text == null || output == null || output.length == 0) return false;\n\t\tString value = text.trim();'
count = value_text.count(old_guard)
if count != 2:
    raise SystemExit(f'expected two ManagedJavaValue parse guards, found {count}')
value_text = value_text.replace(old_guard,
    '\t\tif (text == null || text.length() > MAX_INPUT_CHARS || output == null || output.length == 0) return false;\n\t\tString value = text.trim();')
value_p.write_text(value_text)

auto_path = 'app/src/main/java/io/github/h3nb/jlmodplus/memory/ManagedAutoKnownQuery.java'
replace_once(auto_path,
    '        if (text == null) return null;\n        String value = text.trim();',
    '        if (text == null || text.length() > 96) return null;\n        String value = text.trim();')
