/*
 * Copyright 2026 JL-Mod Plus contributors
 *
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

package javax.microedition.shell;

import org.microemu.android.asm.MemoryEditorTransformMetadata;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.Hashtable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.rms.impl.RecordStoreImpl;
import javax.microedition.shell.custom.Timer;
import javax.microedition.shell.custom.TimerTask;

/**
 * Session-scoped, hookless-first Memory Editor backend.
 *
 * <p>The scanner only follows the active MIDlet, current LCDUI object, known queued callbacks,
 * application-owned thread subclasses, and explicit Vector/Hashtable/Timer bridges. It never
 * recursively reflects through host-owned implementation objects. The sparse lifecycle ledger is
 * evidence for static access only; opening this session does not change the converted artifact.</p>
 */
public final class MemoryEditorSession implements AutoCloseable {
    private static final int MAX_OBJECTS = 10_000;
    private static final int MAX_FIELDS = 50_000;
    private static final int MAX_CANDIDATES = 100_000;
    private static final int MAX_ARRAY_ELEMENTS = 250_000;
    private static final int MAX_DEPTH = 32;
    private static final long MAX_SCAN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final ScheduledExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextCandidateId = new AtomicLong(1L);
    private final Map<String, MemoryEditorTransformMetadata.ClassEntry> classesByRuntimeName;
    private final Map<Long, Candidate> frozen = new ConcurrentHashMap<>();
    private final AtomicLong scanGeneration = new AtomicLong();
    private volatile EvidencePolicy evidencePolicy = EvidencePolicy.PASSIVE;
    private volatile MIDlet rootMidlet;
    private volatile ClassLoader applicationLoader;
    private volatile MemoryProbe.Ledger probeLedger;
    private volatile List<Candidate> candidates = Collections.emptyList();
    private volatile ScheduledFuture<?> freezeTask;

    MemoryEditorSession(
            MIDlet rootMidlet,
            ClassLoader applicationLoader,
            MemoryEditorTransformMetadata metadata,
            MemoryProbe.Ledger probeLedger) {
        if (rootMidlet == null) throw new NullPointerException("rootMidlet");
        this.rootMidlet = rootMidlet;
        this.applicationLoader = applicationLoader;
        this.probeLedger = probeLedger;
        Map<String, MemoryEditorTransformMetadata.ClassEntry> entries = new HashMap<>();
        if (metadata != null) {
            for (MemoryEditorTransformMetadata.ClassEntry entry : metadata.getClasses()) {
                entries.put(entry.getRuntimeInternalName(), entry);
            }
        }
        classesByRuntimeName = Collections.unmodifiableMap(entries);
        worker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "MemoryEditorWorker");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public enum ValueKind {
        BOOLEAN, CHAR, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE;

        static ValueKind fromPrimitive(Class<?> type) {
            if (type == boolean.class) return BOOLEAN;
            if (type == char.class) return CHAR;
            if (type == byte.class) return BYTE;
            if (type == short.class) return SHORT;
            if (type == int.class) return INT;
            if (type == long.class) return LONG;
            if (type == float.class) return FLOAT;
            if (type == double.class) return DOUBLE;
            return null;
        }

        static ValueKind fromWrapper(Object value) {
            if (value instanceof Boolean) return BOOLEAN;
            if (value instanceof Character) return CHAR;
            if (value instanceof Byte) return BYTE;
            if (value instanceof Short) return SHORT;
            if (value instanceof Integer) return INT;
            if (value instanceof Long) return LONG;
            if (value instanceof Float) return FLOAT;
            if (value instanceof Double) return DOUBLE;
            return null;
        }

        public String displayName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum SearchMode {
        ALL, EXACT, CHANGED, UNCHANGED, INCREASED, DECREASED, RANGE
    }

    public enum EvidencePolicy {
        OFF, PASSIVE, ADAPTIVE
    }

    /** Returns the root policy used by the next discovery scan. */
    public EvidencePolicy getEvidencePolicy() {
        return evidencePolicy;
    }

    /**
     * Selects whether discovery may include application-owned live threads. The default is
     * passive so opening the editor never asks the VM for a global thread snapshot.
     */
    public void setEvidencePolicy(EvidencePolicy policy) {
        if (policy == null) throw new NullPointerException("policy");
        evidencePolicy = policy;
    }

    public static final class Query {
        private final SearchMode mode;
        private final String first;
        private final String second;

        private Query(SearchMode mode, String first, String second) {
            this.mode = mode;
            this.first = first;
            this.second = second;
        }

        public static Query all() {
            return new Query(SearchMode.ALL, null, null);
        }

        public static Query exact(String value) {
            return new Query(SearchMode.EXACT, requireValue(value), null);
        }

        public static Query changed() {
            return new Query(SearchMode.CHANGED, null, null);
        }

        public static Query unchanged() {
            return new Query(SearchMode.UNCHANGED, null, null);
        }

        public static Query increased() {
            return new Query(SearchMode.INCREASED, null, null);
        }

        public static Query decreased() {
            return new Query(SearchMode.DECREASED, null, null);
        }

        public static Query range(String minimum, String maximum) {
            return new Query(SearchMode.RANGE, requireValue(minimum), requireValue(maximum));
        }

        public SearchMode getMode() {
            return mode;
        }

        public String getFirst() {
            return first;
        }

        public String getSecond() {
            return second;
        }

        private boolean isDelta() {
            return mode != SearchMode.ALL && mode != SearchMode.EXACT;
        }

        private boolean matches(Sample current, Sample previous) {
            if (current == null) return false;
            try {
                switch (mode) {
                    case ALL:
                        return true;
                    case EXACT:
                        return current.same(Sample.parse(current.kind, first));
                    case CHANGED:
                        return previous != null && !current.same(previous);
                    case UNCHANGED:
                        return previous != null && current.same(previous);
                    case INCREASED:
                        return previous != null && current.compare(previous) > 0;
                    case DECREASED:
                        return previous != null && current.compare(previous) < 0;
                    case RANGE:
                        Sample minimum = Sample.parse(current.kind, first);
                        Sample maximum = Sample.parse(current.kind, second);
                        return current.compare(minimum) >= 0 && current.compare(maximum) <= 0;
                    default:
                        return false;
                }
            } catch (IllegalArgumentException error) {
                return false;
            }
        }

        private static String requireValue(String value) {
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("value");
            return value.trim();
        }
    }

    public interface Listener {
        void onScanFinished(ScanResult result);

        default void onWriteFinished(WriteResult result) {
        }
    }

    public static final class ScanResult {
        private final List<Candidate> candidates;
        private final boolean coverageIncomplete;
        private final boolean cancelled;
        private final int scannedObjects;
        private final int scannedFields;
        private final List<String> diagnostics;

        private ScanResult(
                List<Candidate> candidates,
                boolean coverageIncomplete,
                boolean cancelled,
                int scannedObjects,
                int scannedFields,
                List<String> diagnostics) {
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.coverageIncomplete = coverageIncomplete;
            this.cancelled = cancelled;
            this.scannedObjects = scannedObjects;
            this.scannedFields = scannedFields;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public List<Candidate> getCandidates() {
            return candidates;
        }

        public boolean isCoverageIncomplete() {
            return coverageIncomplete;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public int getScannedObjects() {
            return scannedObjects;
        }

        public int getScannedFields() {
            return scannedFields;
        }

        public List<String> getDiagnostics() {
            return diagnostics;
        }
    }

    public static final class WriteResult {
        private final long candidateId;
        private final boolean success;
        private final boolean stale;
        private final String message;
        private final String value;

        private WriteResult(long candidateId, boolean success, boolean stale, String message,
                String value) {
            this.candidateId = candidateId;
            this.success = success;
            this.stale = stale;
            this.message = message;
            this.value = value;
        }

        public long getCandidateId() {
            return candidateId;
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isStale() {
            return stale;
        }

        public String getMessage() {
            return message;
        }

        public String getValue() {
            return value;
        }
    }

    public static final class Candidate {
        private final long id;
        private final Location location;
        private volatile Sample lastSample;
        private volatile boolean stale;

        private Candidate(long id, Location location, Sample sample) {
            this.id = id;
            this.location = location;
            this.lastSample = sample;
        }

        public long getId() {
            return id;
        }

        public String getPath() {
            return location.path();
        }

        public ValueKind getKind() {
            return location.kind();
        }

        public String getTypeName() {
            return location.kind().displayName();
        }

        public String getValue() {
            if (location.isRegion()) {
                return "[" + location.regionLength() + " elements]";
            }
            Sample sample = lastSample;
            return sample == null ? "<stale>" : sample.format();
        }

        public boolean isRegion() {
            return location.isRegion();
        }

        public int getRegionLength() {
            return location.regionLength();
        }

        /** Reads one element of a primitive-array region without materializing the whole region. */
        public String readRegionElement(int index) {
            if (!location.isRegion()) throw new IllegalStateException("not a region");
            Sample sample = location.readElement(index);
            return sample == null ? null : sample.format();
        }

        public boolean isReadOnly() {
            return location.readOnly();
        }

        public boolean isStale() {
            return stale;
        }
    }

    /** Starts a bounded scan on the session's single worker. */
    public void scanAsync(Query query, Listener listener) {
        if (query == null) throw new NullPointerException("query");
        if (closed.get()) {
            if (listener != null) listener.onScanFinished(cancelledResult());
            return;
        }
        worker.execute(() -> {
            ScanResult result = scanNow(query);
            if (!closed.get() && !result.isCancelled()) {
                candidates = result.getCandidates();
            }
            if (listener != null) listener.onScanFinished(result);
        });
    }

    /** Synchronous entry point for focused tests and host-controller calls. */
    public synchronized ScanResult scanNow(Query query) {
        if (query == null) throw new NullPointerException("query");
        if (closed.get()) return cancelledResult();
        if (evidencePolicy == EvidencePolicy.OFF) {
            return new ScanResult(Collections.emptyList(), false, false, 0, 0,
                    Collections.singletonList("POLICY_OFF"));
        }
        List<Candidate> previous = candidates;
        boolean discover = previous.isEmpty() && !query.isDelta();
        if (previous.isEmpty() && query.isDelta()) {
            return new ScanResult(
                    Collections.emptyList(), false, false, 0, 0,
                    Collections.singletonList("INITIAL_SCAN_REQUIRED"));
        }

        Scanner scanner = discover ? new Scanner(query) : null;
        List<Candidate> result = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int objects = 0;
        int fields = 0;
        boolean incomplete = false;
        if (discover) {
            List<Location> locations = scanner.discover();
            objects = scanner.scannedObjects;
            fields = scanner.scannedFields;
            incomplete = scanner.incomplete;
            diagnostics.addAll(scanner.diagnostics);
            for (Location location : locations) {
                if (closed.get() || Thread.currentThread().isInterrupted()) {
                    return cancelledResult();
                }
                try {
                    Sample current = location.read();
                    if (current != null && query.matches(current, null)) {
                        result.add(new Candidate(nextCandidateId.getAndIncrement(), location, current));
                    }
                } catch (RuntimeException error) {
                    diagnostics.add("READ_SKIPPED:" + location.path());
                }
            }
        } else {
            for (Candidate candidate : previous) {
                if (closed.get() || Thread.currentThread().isInterrupted()) return cancelledResult();
                try {
                    Sample before = candidate.lastSample;
                    Sample current = candidate.location.read();
                    if (current == null) {
                        candidate.stale = true;
                        continue;
                    }
                    candidate.stale = false;
                    candidate.lastSample = current;
                    if (query.matches(current, before)) result.add(candidate);
                } catch (RuntimeException error) {
                    candidate.stale = true;
                }
            }
            objects = previous.size();
        }
        scanGeneration.incrementAndGet();
        ScanResult scanResult = new ScanResult(result, incomplete, false, objects, fields, diagnostics);
        candidates = scanResult.getCandidates();
        return scanResult;
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }

    /** Starts a new search epoch without tearing down the live session or its roots. */
    public synchronized void resetSearch() {
        candidates = Collections.emptyList();
        scanGeneration.incrementAndGet();
    }

    public void writeAsync(long candidateId, String text, Listener listener) {
        if (closed.get()) {
            if (listener != null) listener.onWriteFinished(
                    new WriteResult(candidateId, false, true, "SESSION_CLOSED", null));
            return;
        }
        worker.execute(() -> {
            WriteResult result = writeNow(candidateId, text);
            if (listener != null) listener.onWriteFinished(result);
        });
    }

    public synchronized WriteResult writeNow(long candidateId, String text) {
        if (closed.get()) return new WriteResult(candidateId, false, true, "SESSION_CLOSED", null);
        Candidate candidate = findCandidate(candidateId);
        if (candidate == null) return new WriteResult(candidateId, false, true, "STALE_CANDIDATE", null);
        if (candidate.isRegion()) {
            return new WriteResult(candidateId, false, false, "REGION_INDEX_REQUIRED", candidate.getValue());
        }
        if (candidate.isReadOnly()) return new WriteResult(candidateId, false, false, "READ_ONLY", candidate.getValue());
        try {
            Sample current = candidate.location.read();
            if (current == null) {
                candidate.stale = true;
                return new WriteResult(candidateId, false, true, "STALE_LOCATION", null);
            }
            Sample replacement = Sample.parse(candidate.location.kind(), text);
            if (!candidate.location.write(replacement)) {
                return new WriteResult(candidateId, false, false, "READ_ONLY", current.format());
            }
            Sample readBack = candidate.location.read();
            if (readBack == null || !readBack.same(replacement)) {
                candidate.stale = true;
                return new WriteResult(candidateId, false, true, "READBACK_MISMATCH", readBack == null ? null : readBack.format());
            }
            candidate.lastSample = readBack;
            candidate.stale = false;
            return new WriteResult(candidateId, true, false, "OK", readBack.format());
        } catch (IllegalArgumentException error) {
            return new WriteResult(candidateId, false, false, "INVALID_VALUE", candidate.getValue());
        } catch (RuntimeException error) {
            candidate.stale = true;
            return new WriteResult(candidateId, false, true, "WRITE_FAILED", null);
        }
    }

    /** Writes one element in a primitive-array region after validating the read-back value. */
    public synchronized WriteResult writeRegionElementNow(long candidateId, int index, String text) {
        if (closed.get()) return new WriteResult(candidateId, false, true, "SESSION_CLOSED", null);
        Candidate candidate = findCandidate(candidateId);
        if (candidate == null || !candidate.isRegion()) {
            return new WriteResult(candidateId, false, true, "STALE_REGION", null);
        }
        if (index < 0 || index >= candidate.getRegionLength()) {
            return new WriteResult(candidateId, false, false, "INVALID_INDEX", null);
        }
        try {
            Sample replacement = Sample.parse(candidate.location.kind(), text);
            if (!candidate.location.writeElement(index, replacement)) {
                return new WriteResult(candidateId, false, false, "READ_ONLY", null);
            }
            Sample readBack = candidate.location.readElement(index);
            if (readBack == null || !readBack.same(replacement)) {
                return new WriteResult(candidateId, false, true, "READBACK_MISMATCH",
                        readBack == null ? null : readBack.format());
            }
            return new WriteResult(candidateId, true, false, "OK", readBack.format());
        } catch (IllegalArgumentException error) {
            return new WriteResult(candidateId, false, false, "INVALID_VALUE", null);
        } catch (RuntimeException error) {
            return new WriteResult(candidateId, false, true, "WRITE_FAILED", null);
        }
    }

    public boolean freeze(long candidateId) {
        if (closed.get()) return false;
        Candidate candidate = findCandidate(candidateId);
        if (candidate == null || candidate.isReadOnly() || candidate.isStale()) return false;
        try {
            Sample sample = candidate.location.read();
            if (sample == null) return false;
            frozen.put(candidateId, candidate);
            ensureFreezeTask();
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public void unfreeze(long candidateId) {
        frozen.remove(candidateId);
        if (frozen.isEmpty()) {
            ScheduledFuture<?> task = freezeTask;
            if (task != null) task.cancel(false);
            freezeTask = null;
        }
    }

    public boolean isFrozen(long candidateId) {
        return frozen.containsKey(candidateId);
    }

    private synchronized void ensureFreezeTask() {
        if (freezeTask != null && !freezeTask.isCancelled()) return;
        freezeTask = worker.scheduleWithFixedDelay(this::applyFreezes, 100L, 100L, TimeUnit.MILLISECONDS);
    }

    private void applyFreezes() {
        for (Candidate candidate : frozen.values()) {
            if (closed.get()) return;
            try {
                Sample sample = candidate.lastSample;
                if (sample != null && !candidate.location.read().same(sample)) {
                    candidate.location.write(sample);
                }
            } catch (RuntimeException error) {
                candidate.stale = true;
                frozen.remove(candidate.id);
            }
        }
    }

    private Candidate findCandidate(long id) {
        for (Candidate candidate : candidates) {
            if (candidate.id == id) return candidate;
        }
        return null;
    }

    private ScanResult cancelledResult() {
        return new ScanResult(Collections.emptyList(), false, true, 0, 0,
                Collections.singletonList("CANCELLED"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        frozen.clear();
        ScheduledFuture<?> task = freezeTask;
        if (task != null) task.cancel(true);
        freezeTask = null;
        candidates = Collections.emptyList();
        rootMidlet = null;
        applicationLoader = null;
        probeLedger = null;
        worker.shutdownNow();
    }

    public boolean isClosed() {
        return closed.get();
    }

    private final class Scanner {
        private final Query query;
        private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        private final List<Location> locations = new ArrayList<>();
        private final Set<String> diagnosticsSeen = new HashSet<>();
        private final Set<String> staticFieldsSeen = new HashSet<>();
        private final List<String> diagnostics = new ArrayList<>();
        private final long deadlineNanos = System.nanoTime() + MAX_SCAN_NANOS;
        private boolean incomplete;
        private int scannedObjects;
        private int scannedFields;

        private Scanner(Query query) {
            this.query = query;
        }

        private List<Location> discover() {
            MIDlet midlet = rootMidlet;
            if (midlet == null) {
                addDiagnostic("MIDLET_ROOT_UNAVAILABLE");
                return locations;
            }
            visit(midlet, "$midlet", 0);
            try {
                Display display = Display.peekDisplay();
                Object displayable = display == null ? null : display.getCurrent();
                if (displayable != null) visit(displayable, "$display", 0);
            } catch (RuntimeException error) {
                addDiagnostic("DISPLAY_ROOT_UNAVAILABLE");
            }
            try {
                for (Object target : Display.snapshotQueuedRunnableTargets()) {
                    visit(target, "$event", 0);
                }
            } catch (RuntimeException error) {
                addDiagnostic("EVENT_ROOT_UNAVAILABLE");
            }
            visitRmsStores();
            if (queryAdaptiveThreads()) {
                try {
                    for (Thread thread : Thread.getAllStackTraces().keySet()) {
                        if (thread != Thread.currentThread()
                                && thread.getClass().getClassLoader() == applicationLoader) {
                            visit(thread, "$thread:" + thread.getName(), 0);
                        }
                    }
                } catch (SecurityException error) {
                    addDiagnostic("THREAD_ROOT_DENIED");
                }
            }
            return locations;
        }

        private void visitRmsStores() {
            try {
                for (RecordStoreImpl store : MemoryEditorRmsGate.snapshotOpenStores()) {
                    if (stopped()) return;
                    String storeName;
                    try {
                        storeName = store.getName();
                    } catch (RecordStoreException | RuntimeException error) {
                        addDiagnostic("RMS_STORE_UNAVAILABLE");
                        continue;
                    }
                    RecordEnumeration enumeration = null;
                    try {
                        enumeration = store.enumerateRecords(null, null, false);
                        while (enumeration.hasNextElement() && !stopped()) {
                            int recordId = enumeration.nextRecordId();
                            int size = store.getRecordSize(recordId);
                            if (size <= 0) continue;
                            RmsRecordLocation region = new RmsRecordLocation(
                                    store, recordId, storeName + ".record[" + recordId + "]", size);
                            if (query.getMode() == SearchMode.ALL) {
                                addLocation(region);
                            } else {
                                int limit = Math.min(size, MAX_ARRAY_ELEMENTS);
                                if (size > limit) {
                                    incomplete = true;
                                    addDiagnostic("RMS_RECORD_BUDGET:" + storeName + "." + recordId);
                                }
                                for (int index = 0; index < limit && !stopped(); index++) {
                                    Sample sample = region.readElement(index);
                                    if (query.matches(sample, null)) {
                                        addLocation(new RmsByteLocation(
                                                store, recordId, index, storeName + ".record["
                                                        + recordId + "][" + index + "]"));
                                    }
                                }
                            }
                        }
                    } catch (RecordStoreException | RuntimeException error) {
                        addDiagnostic("RMS_SCAN_FAILED:" + storeName);
                    } finally {
                        if (enumeration != null) {
                            try {
                                enumeration.destroy();
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                }
            } catch (RuntimeException error) {
                addDiagnostic("RMS_ROOT_UNAVAILABLE");
            }
        }

        private boolean queryAdaptiveThreads() {
            return evidencePolicy == EvidencePolicy.ADAPTIVE;
        }

        private void visit(Object object, String path, int depth) {
            if (object == null || stopped() || depth > MAX_DEPTH) return;
            if (isScalarObject(object)) return;
            Class<?> type = object.getClass();
            if (visited.put(object, Boolean.TRUE) != null) return;
            scannedObjects++;
            if (type.isArray()) {
                visitArray(object, path, depth);
                return;
            }
            if (object instanceof Vector<?>) {
                visitVector((Vector<?>) object, path, depth);
                return;
            }
            if (object instanceof Hashtable<?, ?>) {
                visitHashtable((Hashtable<?, ?>) object, path, depth);
                return;
            }
            if (object instanceof Timer) {
                visitTimer((Timer) object, path, depth);
                return;
            }
            if (type.getClassLoader() != applicationLoader) return;
            visitFields(object, path, depth);
        }

        private void visitArray(Object array, String path, int depth) {
            Class<?> component = array.getClass().getComponentType();
            int length = Array.getLength(array);
            int limit = Math.min(length, MAX_ARRAY_ELEMENTS);
            if (length > limit) {
                incomplete = true;
                addDiagnostic("ARRAY_BUDGET:" + path);
            }
            if (component.isPrimitive()) {
                ValueKind kind = ValueKind.fromPrimitive(component);
                if (kind == null) return;
                if (length == 0) return;
                if (query.getMode() == SearchMode.ALL) {
                    addLocation(new ArrayRegionLocation(array, kind, path, length));
                } else {
                    for (int index = 0; index < limit && !stopped(); index++) {
                        Sample sample = Sample.from(Array.get(array, index), kind);
                        if (query.matches(sample, null)) {
                            addLocation(new ArrayLocation(array, index, kind, path + "[" + index + "]"));
                        }
                    }
                }
                return;
            }
            for (int index = 0; index < limit && !stopped(); index++) {
                Object value = Array.get(array, index);
                visitReference(value, new ArrayLocation(array, index,
                        ValueKind.fromWrapper(value), path + "[" + index + "]"),
                        path + "[" + index + "]", depth + 1);
            }
        }

        private void visitVector(Vector<?> vector, String path, int depth) {
            int size;
            try {
                size = vector.size();
            } catch (RuntimeException error) {
                addDiagnostic("VECTOR_READ_FAILED:" + path);
                return;
            }
            int limit = Math.min(size, MAX_ARRAY_ELEMENTS);
            if (size > limit) {
                incomplete = true;
                addDiagnostic("VECTOR_BUDGET:" + path);
            }
            for (int index = 0; index < limit && !stopped(); index++) {
                try {
                    Object value = vector.elementAt(index);
                    Location location = new VectorLocation(vector, index,
                            ValueKind.fromWrapper(value), path + "[" + index + "]");
                    visitReference(value, location, path + "[" + index + "]", depth + 1);
                } catch (RuntimeException error) {
                    addDiagnostic("VECTOR_ELEMENT_FAILED:" + path);
                }
            }
        }

        private void visitHashtable(Hashtable<?, ?> table, String path, int depth) {
            int count = 0;
            try {
                Enumeration<?> keys = table.keys();
                while (keys.hasMoreElements() && !stopped()) {
                    Object key = keys.nextElement();
                    if (++count > MAX_ARRAY_ELEMENTS) {
                        incomplete = true;
                        addDiagnostic("HASHTABLE_BUDGET:" + path);
                        break;
                    }
                    if (!isSafeHashtableKey(key)) {
                        addDiagnostic("HASHTABLE_KEY_SKIPPED:" + path);
                        continue;
                    }
                    Object value = table.get(key);
                    Location location = new HashtableLocation(table, key,
                            ValueKind.fromWrapper(value), path + "[" + String.valueOf(key) + "]");
                    visitReference(value, location, path + "[" + String.valueOf(key) + "]", depth + 1);
                }
            } catch (RuntimeException error) {
                addDiagnostic("HASHTABLE_READ_FAILED:" + path);
            }
        }

        private void visitTimer(Timer timer, String path, int depth) {
            try {
                TimerTask[] tasks = timer.snapshotScheduledTasks();
                for (int index = 0; index < tasks.length && !stopped(); index++) {
                    visit(tasks[index], path + ".task[" + index + "]", depth + 1);
                }
            } catch (RuntimeException error) {
                addDiagnostic("TIMER_SNAPSHOT_FAILED:" + path);
            }
        }

        private void visitFields(Object object, String path, int depth) {
            for (Class<?> type = object.getClass();
                    type != null && type.getClassLoader() == applicationLoader && !stopped();
                    type = type.getSuperclass()) {
                MemoryEditorTransformMetadata.ClassEntry entry = classEntry(type);
                if (entry == null) {
                    addDiagnostic("CLASS_UNBOUND:" + type.getName());
                    continue;
                }
                Field[] declared;
                try {
                    declared = type.getDeclaredFields();
                } catch (RuntimeException error) {
                    addDiagnostic("FIELDS_UNAVAILABLE:" + type.getName());
                    continue;
                }
                for (Field field : declared) {
                    if (field.isSynthetic() || stopped()) continue;
                    scannedFields++;
                    if (scannedFields > MAX_FIELDS) {
                        incomplete = true;
                        addDiagnostic("FIELD_BUDGET");
                        return;
                    }
                    if (!boundField(entry, field)) continue;
                    boolean isStatic = Modifier.isStatic(field.getModifiers());
                    if (isStatic && !staticFieldsSeen.add(type.getName() + "." + field.getName())) {
                        continue;
                    }
                    if (isStatic && !staticAccessAllowed(entry)) {
                        addDiagnostic("STATIC_INIT_UNKNOWN:" + type.getName());
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object owner = isStatic ? null : object;
                        Object value = field.get(owner);
                        String fieldPath = path + "." + field.getName();
                        ValueKind primitiveKind = ValueKind.fromPrimitive(field.getType());
                        if (primitiveKind != null) {
                            addLocation(new FieldLocation(owner, field, primitiveKind, fieldPath));
                        } else {
                            Location boxedLocation = new FieldLocation(owner, field,
                                    ValueKind.fromWrapper(value), fieldPath);
                            visitReference(value, boxedLocation, fieldPath, depth + 1);
                        }
                    } catch (RuntimeException | IllegalAccessException error) {
                        addDiagnostic("FIELD_READ_FAILED:" + type.getName() + "." + field.getName());
                    }
                }
            }
        }

        private void visitReference(Object value, Location location, String path, int depth) {
            ValueKind wrapperKind = ValueKind.fromWrapper(value);
            if (wrapperKind != null && location != null) {
                location = location.withKind(wrapperKind);
                addLocation(location);
                return;
            }
            if (value != null) visit(value, path, depth);
        }

        private void addLocation(Location location) {
            if (location == null || location.kind() == null) return;
            if (locations.size() >= MAX_CANDIDATES) {
                incomplete = true;
                addDiagnostic("CANDIDATE_BUDGET");
                return;
            }
            locations.add(location);
        }

        private boolean staticAccessAllowed(MemoryEditorTransformMetadata.ClassEntry entry) {
            MemoryProbe.Ledger ledger = probeLedger;
            return ledger != null && entry.hasSourceClinit()
                    && ledger.wasObserved(entry.getSourceClassId());
        }

        private boolean boundField(MemoryEditorTransformMetadata.ClassEntry entry, Field field) {
            String descriptor = descriptor(field.getType());
            for (MemoryEditorTransformMetadata.FieldEntry metadataField : entry.getFields()) {
                if (field.getName().equals(metadataField.getName())
                        && descriptor.equals(metadataField.getRuntimeDescriptor())) return true;
            }
            addDiagnostic("FIELD_UNBOUND:" + entry.getRuntimeInternalName() + "." + field.getName());
            return false;
        }

        private MemoryEditorTransformMetadata.ClassEntry classEntry(Class<?> type) {
            return classesByRuntimeName.get(internalName(type));
        }

        private boolean isSafeHashtableKey(Object key) {
            return key instanceof String || key instanceof Integer || key instanceof Long
                    || key instanceof Short || key instanceof Byte || key instanceof Character
                    || key instanceof Boolean;
        }

        private boolean stopped() {
            if (closed.get() || Thread.currentThread().isInterrupted()) return true;
            if (System.nanoTime() >= deadlineNanos) {
                incomplete = true;
                addDiagnostic("TIME_BUDGET");
                return true;
            }
            if (scannedObjects >= MAX_OBJECTS) {
                incomplete = true;
                addDiagnostic("OBJECT_BUDGET");
                return true;
            }
            return false;
        }

        private void addDiagnostic(String value) {
            if (diagnosticsSeen.add(value) && diagnostics.size() < 64) diagnostics.add(value);
        }
    }

    private interface Location {
        String path();

        ValueKind kind();

        boolean readOnly();

        Sample read();

        boolean write(Sample sample);

        Location withKind(ValueKind kind);

        default boolean isRegion() {
            return false;
        }

        default int regionLength() {
            return 0;
        }

        default Sample readElement(int index) {
            throw new IllegalStateException("not a region");
        }

        default boolean writeElement(int index, Sample sample) {
            return false;
        }
    }

    private static final class FieldLocation implements Location {
        private final Object owner;
        private final Field field;
        private final ValueKind kind;
        private final String path;

        private FieldLocation(Object owner, Field field, ValueKind kind, String path) {
            this.owner = owner;
            this.field = field;
            this.kind = kind;
            this.path = path;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public ValueKind kind() {
            return kind;
        }

        @Override
        public boolean readOnly() {
            return Modifier.isFinal(field.getModifiers());
        }

        @Override
        public Sample read() {
            try {
                return Sample.from(field.get(owner), kind);
            } catch (IllegalAccessException | RuntimeException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public boolean write(Sample sample) {
            if (readOnly()) return false;
            try {
                field.set(owner, sample.boxed());
                return true;
            } catch (IllegalAccessException | RuntimeException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public Location withKind(ValueKind nextKind) {
            return new FieldLocation(owner, field, nextKind, path);
        }
    }

    private static final class ArrayLocation implements Location {
        private final Object array;
        private final int index;
        private final ValueKind kind;
        private final String path;

        private ArrayLocation(Object array, int index, ValueKind kind, String path) {
            this.array = array;
            this.index = index;
            this.kind = kind;
            this.path = path;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public ValueKind kind() {
            return kind;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Sample read() {
            return Sample.from(Array.get(array, index), kind);
        }

        @Override
        public boolean write(Sample sample) {
            Array.set(array, index, sample.boxed());
            return true;
        }

        @Override
        public Location withKind(ValueKind nextKind) {
            return new ArrayLocation(array, index, nextKind, path);
        }
    }

    /** Compact location for a primitive array; scalar locations are created only for matches. */
    private static final class ArrayRegionLocation implements Location {
        private final Object array;
        private final ValueKind kind;
        private final String path;
        private final int length;

        private ArrayRegionLocation(Object array, ValueKind kind, String path, int length) {
            this.array = array;
            this.kind = kind;
            this.path = path + "[0.." + length + ")";
            this.length = length;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public ValueKind kind() {
            return kind;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Sample read() {
            return readElement(0);
        }

        @Override
        public boolean write(Sample sample) {
            return false;
        }

        @Override
        public Location withKind(ValueKind nextKind) {
            return new ArrayRegionLocation(array, nextKind, path, length);
        }

        @Override
        public boolean isRegion() {
            return true;
        }

        @Override
        public int regionLength() {
            return length;
        }

        @Override
        public Sample readElement(int index) {
            if (index < 0 || index >= length) throw new IndexOutOfBoundsException();
            return Sample.from(Array.get(array, index), kind);
        }

        @Override
        public boolean writeElement(int index, Sample sample) {
            if (index < 0 || index >= length || sample == null) return false;
            Array.set(array, index, sample.boxed());
            return true;
        }
    }

    /** Compact RMS record region backed by RecordStore's normal semantic API. */
    private static final class RmsRecordLocation implements Location {
        private final RecordStoreImpl store;
        private final int recordId;
        private final ValueKind kind = ValueKind.BYTE;
        private final String path;
        private final int length;

        private RmsRecordLocation(RecordStoreImpl store, int recordId, String path, int length) {
            this.store = store;
            this.recordId = recordId;
            this.path = path + "[0.." + length + ")";
            this.length = length;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public ValueKind kind() {
            return kind;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Sample read() {
            return readElement(0);
        }

        @Override
        public boolean write(Sample sample) {
            return false;
        }

        @Override
        public Location withKind(ValueKind nextKind) {
            return new RmsRecordLocation(store, recordId, path, length);
        }

        @Override
        public boolean isRegion() {
            return true;
        }

        @Override
        public int regionLength() {
            return length;
        }

        @Override
        public Sample readElement(int index) {
            if (index < 0 || index >= length) throw new IndexOutOfBoundsException();
            try {
                byte[] data = store.getRecord(recordId);
                return data == null ? null : Sample.from(data[index], kind);
            } catch (RecordStoreException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public boolean writeElement(int index, Sample sample) {
            if (index < 0 || index >= length || sample == null) return false;
            try {
                byte[] data = store.getRecord(recordId);
                if (data == null || index >= data.length) return false;
                data[index] = ((Number) sample.boxed()).byteValue();
                store.setRecord(recordId, data, 0, data.length);
                return true;
            } catch (RecordStoreException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    /** Scalar RMS byte location used only when a search query matches a particular byte. */
    private static final class RmsByteLocation implements Location {
        private final RecordStoreImpl store;
        private final int recordId;
        private final int index;
        private final String path;

        private RmsByteLocation(RecordStoreImpl store, int recordId, int index, String path) {
            this.store = store;
            this.recordId = recordId;
            this.index = index;
            this.path = path;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public ValueKind kind() {
            return ValueKind.BYTE;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Sample read() {
            try {
                byte[] data = store.getRecord(recordId);
                return data == null || index >= data.length ? null : Sample.from(data[index], kind());
            } catch (RecordStoreException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public boolean write(Sample sample) {
            try {
                byte[] data = store.getRecord(recordId);
                if (data == null || index >= data.length) return false;
                data[index] = ((Number) sample.boxed()).byteValue();
                store.setRecord(recordId, data, 0, data.length);
                return true;
            } catch (RecordStoreException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public Location withKind(ValueKind nextKind) {
            return this;
        }
    }

    private static final class VectorLocation implements Location {
        private final Vector<?> vector;
        private final int index;
        private final ValueKind kind;
        private final String path;

        private VectorLocation(Vector<?> vector, int index, ValueKind kind, String path) {
            this.vector = vector;
            this.index = index;
            this.kind = kind;
            this.path = path;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public ValueKind kind() {
            return kind;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Sample read() {
            return Sample.from(vector.elementAt(index), kind);
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean write(Sample sample) {
            ((Vector<Object>) vector).setElementAt(sample.boxed(), index);
            return true;
        }

        @Override
        public Location withKind(ValueKind nextKind) {
            return new VectorLocation(vector, index, nextKind, path);
        }
    }

    private static final class HashtableLocation implements Location {
        private final Hashtable<?, ?> table;
        private final Object key;
        private final ValueKind kind;
        private final String path;

        private HashtableLocation(Hashtable<?, ?> table, Object key, ValueKind kind, String path) {
            this.table = table;
            this.key = key;
            this.kind = kind;
            this.path = path;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public ValueKind kind() {
            return kind;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Sample read() {
            return Sample.from(table.get(key), kind);
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean write(Sample sample) {
            ((Hashtable<Object, Object>) table).put(key, sample.boxed());
            return true;
        }

        @Override
        public Location withKind(ValueKind nextKind) {
            return new HashtableLocation(table, key, nextKind, path);
        }
    }

    private static final class Sample {
        private final ValueKind kind;
        private final long raw;

        private Sample(ValueKind kind, long raw) {
            this.kind = kind;
            this.raw = raw;
        }

        private static Sample from(Object value, ValueKind kind) {
            if (value == null || kind == null) return null;
            switch (kind) {
                case BOOLEAN:
                    return new Sample(kind, ((Boolean) value) ? 1L : 0L);
                case CHAR:
                    return new Sample(kind, ((Character) value).charValue());
                case BYTE:
                    return new Sample(kind, ((Number) value).byteValue());
                case SHORT:
                    return new Sample(kind, ((Number) value).shortValue());
                case INT:
                    return new Sample(kind, ((Number) value).intValue());
                case LONG:
                    return new Sample(kind, ((Number) value).longValue());
                case FLOAT:
                    return new Sample(kind, Float.floatToRawIntBits(((Number) value).floatValue()));
                case DOUBLE:
                    return new Sample(kind, Double.doubleToRawLongBits(((Number) value).doubleValue()));
                default:
                    return null;
            }
        }

        private static Sample parse(ValueKind kind, String text) {
            if (kind == null || text == null) throw new IllegalArgumentException("value");
            String value = text.trim();
            switch (kind) {
                case BOOLEAN:
                    if ("true".equalsIgnoreCase(value)) return new Sample(kind, 1L);
                    if ("false".equalsIgnoreCase(value)) return new Sample(kind, 0L);
                    throw new IllegalArgumentException("boolean");
                case CHAR:
                    if (value.length() == 1) return new Sample(kind, value.charAt(0));
                    if (value.startsWith("U+") || value.startsWith("u+")) {
                        return new Sample(kind, checkedIntegral(Long.parseLong(value.substring(2), 16),
                                Character.MIN_VALUE, Character.MAX_VALUE));
                    }
                    return new Sample(kind, checkedIntegral(Long.decode(value),
                            Character.MIN_VALUE, Character.MAX_VALUE));
                case BYTE:
                    return new Sample(kind, checkedIntegral(Long.decode(value), Byte.MIN_VALUE, Byte.MAX_VALUE));
                case SHORT:
                    return new Sample(kind, checkedIntegral(Long.decode(value), Short.MIN_VALUE, Short.MAX_VALUE));
                case INT:
                    return new Sample(kind, checkedIntegral(Long.decode(value), Integer.MIN_VALUE, Integer.MAX_VALUE));
                case LONG:
                    return new Sample(kind, Long.decode(value));
                case FLOAT:
                    return new Sample(kind, Float.floatToRawIntBits(Float.parseFloat(value)));
                case DOUBLE:
                    return new Sample(kind, Double.doubleToRawLongBits(Double.parseDouble(value)));
                default:
                    throw new IllegalArgumentException("kind");
            }
        }

        private static long checkedIntegral(long value, long minimum, long maximum) {
            if (value < minimum || value > maximum) throw new IllegalArgumentException("range");
            return value;
        }

        private static ValueKind kindOf(Class<?> type) {
            if (type == boolean.class) return ValueKind.BOOLEAN;
            if (type == char.class) return ValueKind.CHAR;
            if (type == byte.class) return ValueKind.BYTE;
            if (type == short.class) return ValueKind.SHORT;
            if (type == int.class) return ValueKind.INT;
            if (type == long.class) return ValueKind.LONG;
            if (type == float.class) return ValueKind.FLOAT;
            if (type == double.class) return ValueKind.DOUBLE;
            return null;
        }

        private static Object wrapper(ValueKind kind, long raw) {
            switch (kind) {
                case BOOLEAN: return raw != 0L;
                case CHAR: return Character.valueOf((char) raw);
                case BYTE: return Byte.valueOf((byte) raw);
                case SHORT: return Short.valueOf((short) raw);
                case INT: return Integer.valueOf((int) raw);
                case LONG: return Long.valueOf(raw);
                case FLOAT: return Float.valueOf(Float.intBitsToFloat((int) raw));
                case DOUBLE: return Double.valueOf(Double.longBitsToDouble(raw));
                default: throw new IllegalArgumentException("kind");
            }
        }

        private Object boxed() {
            return wrapper(kind, raw);
        }

        private boolean same(Sample other) {
            return other != null && kind == other.kind && raw == other.raw;
        }

        private int compare(Sample other) {
            if (other == null || kind != other.kind) throw new IllegalArgumentException("kind");
            switch (kind) {
                case FLOAT:
                    return Float.compare(Float.intBitsToFloat((int) raw),
                            Float.intBitsToFloat((int) other.raw));
                case DOUBLE:
                    return Double.compare(Double.longBitsToDouble(raw),
                            Double.longBitsToDouble(other.raw));
                default:
                    return Long.compare(raw, other.raw);
            }
        }

        private String format() {
            switch (kind) {
                case BOOLEAN: return raw != 0L ? "true" : "false";
                case CHAR: return "U+" + String.format(java.util.Locale.ROOT, "%04X", (int) raw);
                case FLOAT: return Float.toString(Float.intBitsToFloat((int) raw));
                case DOUBLE: return Double.toString(Double.longBitsToDouble(raw));
                default: return Long.toString(raw);
            }
        }
    }

    private static ValueKind primitiveKind(Class<?> type) {
        return Sample.kindOf(type);
    }

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String descriptor(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == int.class) return "I";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            if (type == double.class) return "D";
        }
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + internalName(type) + ";";
    }

    private static boolean isScalarObject(Object object) {
        return object instanceof String || object instanceof Number || object instanceof Boolean
                || object instanceof Character || object instanceof Class<?> || object.getClass().isEnum();
    }

}
