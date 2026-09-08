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

package ru.playsoftware.j2meloader.memory;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.shell.MemoryDiscoveryBridge;

/**
 * Target-process managed Java graph backend for the supported primitive planes.
 *
 * <p>This class deliberately contains no native-memory calls. Candidate identity is a logical
 * owner handle plus a cached schema slot or primitive-array index. Only the owner is weakly retained;
 * the graph's temporary identity map is released when a scan finishes.</p>
 */
final class ManagedJavaMemoryEngine {
	static final int KIND_OBJECT_FIELD = 0;
	static final int KIND_ARRAY = 1;
	static final int KIND_STATIC_FIELD = 2;

	private static final int CHECK_INTERVAL = 256;
	private static final int DEFAULT_MAX_VISITED = 250_000;
	private static final int DEFAULT_MAX_PENDING = 100_000;
	private static final int DEFAULT_MAX_OWNERS = 100_000;
	private static final int DEFAULT_MAX_CLASSES = 4_096;
	private static final int DEFAULT_MAX_CANDIDATES = 2_000_000;
	private static final int DEFAULT_MAX_CONTAINER_ITEMS = 4_096;
	private static final int DEFAULT_MAX_ARRAY_ELEMENTS = 2_000_000;
	private static final long DEFAULT_MAX_RESULT_STORAGE_BYTES = 192L * 1024L * 1024L;
	private static final int MAX_SEARCH_HISTORY = MemoryEngineContract.MAX_SEARCH_HISTORY;
	private static final long RESULT_SLOT_BYTES = Integer.BYTES + 2L * Long.BYTES;
	private static final int INITIAL_OWNER_CAPACITY = 16;
	private static final int FIRST_SUPPORTED_TYPE = MemoryEngineContract.TYPE_BYTE;
	private static final int LAST_SUPPORTED_TYPE = MemoryEngineContract.TYPE_DOUBLE;
	private static final long STAGED_OWNER_BYTES = 64L;
	private static final long FINISHED_BUCKET_BYTES = 64L;
	private static final long TRAVERSAL_ENTRY_BYTES = 64L;
	private static final long QUEUE_REFERENCE_BYTES = Long.BYTES;
	private static final long SNAPSHOT_REFERENCE_BYTES = Long.BYTES;

	private final Object stateLock = new Object();
	private final AtomicLong controlEpoch = new AtomicLong();
	private final Limits limits;
	private final Map<Long, OwnerBucket> owners = new HashMap<>();
	private final Map<Class<?>, Schema> schemaCache = new HashMap<>();
	private final WatchStore watches = new WatchStore();
	private final ArrayDeque<SearchState> searchHistory = new ArrayDeque<>();
	private long activeToken;
	private ClassLoader activeLoader;
	private long nextOwnerHandle = 1L;
	private long nextRevision = 1L;
	private Revision committed;
	/** The original Unknown-baseline size retained for diagnostics and session metadata. */
	private long baselineCount;
	private int searchStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
	private int searchMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
	private int requestedType = MemoryEngineContract.TYPE_AUTO;
	private String lastMessage;

	ManagedJavaMemoryEngine() {
		this(Limits.defaults());
	}

	ManagedJavaMemoryEngine(Limits limits) {
		this.limits = limits == null ? Limits.defaults() : limits;
	}

	private static long checkedAdd(long left, long right) {
		if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
		return left + right;
	}

	private static long checkedMultiply(long left, long right) {
		if (left < 0L || right < 0L || (left != 0L && right > Long.MAX_VALUE / left)) {
			return Long.MAX_VALUE;
		}
		return left * right;
	}

	private static int initialCapacity(int maxCandidates) {
		return Math.min(INITIAL_OWNER_CAPACITY, Math.max(0, maxCandidates));
	}

	private static int nextCapacity(int current, int max) {
		if (max <= current) return current;
		long doubled = current <= 0 ? 1L : (long) current * 2L;
		return (int) Math.min((long) max, doubled);
	}

	ManagedCapabilities capabilities(long token) {
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
		long revision;
		long count;
		long baseline;
		int watchCount;
		int freezeCount;
		synchronized (stateLock) {
			if (snapshot.loader() != null && snapshot.token() == token) {
				activateRuntimeLocked(token, snapshot.loader());
			}
			revision = committed == null ? 0L : committed.id;
			count = visibleResultCountLocked();
			baseline = baselineCountLocked();
			watchCount = watches.count;
			freezeCount = watches.freezeCount();
		}
		return new ManagedCapabilities(
				snapshot.isAvailable(),
				snapshot.isAvailable(),
				controlEpoch.get(),
				revision,
				count,
				baseline,
				watchCount,
				freezeCount,
				searchHistory.size(),
				snapshot.failure());
	}

	ManagedSession session(long token) {
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
			synchronized (stateLock) {
			long revision = committed == null ? 0L : committed.id;
			long count = visibleResultCountLocked();
			long baseline = baselineCountLocked();
			return new ManagedSession(
					snapshot.isAvailable(),
					searchStage,
					searchMode,
					requestedType,
					revision,
					count,
					baseline,
					watches.count,
					watches.freezeCount(),
					searchHistory.size(),
					lastMessage != null ? lastMessage : snapshot.failure());
		}
	}

	ManagedOperationResult startExactInt(long token, int value, long operationEpoch) {
		return startExact(token, MemoryEngineContract.TYPE_INT,
				MemoryEngineContract.PREDICATE_EQUAL, value, 0L, operationEpoch);
	}

	ManagedOperationResult startExact(long token, int type, int predicate, long first, long second,
	                                long operationEpoch) {
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
		if (!snapshot.isAvailable()) {
			return failure(token, MemoryEngineContract.RESULT_UNSUPPORTED,
					managedFailure(snapshot, "Managed Java discovery is unavailable"));
		}
		if (!beginOperation(token, operationEpoch, snapshot.loader())) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed search was cancelled before it started");
		}
		QueryPlan plan = QueryPlan.fromKnownBits(type, predicate, first, second);
		if (plan == null) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed search received an invalid typed query");
		}

		long retainedRevisionBytes;
		synchronized (stateLock) {
			retainedRevisionBytes = retainedSearchStorageBytesLocked();
		}
		ScanContext scan = new ScanContext(token, operationEpoch, snapshot, plan,
				retainedRevisionBytes);
		try {
			scan.enqueue(snapshot.root());
			Class<?>[] classes = snapshot.classes();
			if (classes.length > limits.maxClasses) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed class snapshot exceeds the resource limit");
			}
			long classSnapshotBytes = checkedMultiply(classes.length, SNAPSHOT_REFERENCE_BYTES);
			scan.reserveTraversalBytes(classSnapshotBytes);
			try {
				for (Class<?> classType : classes) {
					if (!scan.scanStaticClass(classType)) return scan.failure();
				}
			} finally {
				scan.releaseTraversalBytes(classSnapshotBytes);
			}
			while (!scan.queue.isEmpty()) {
				if (!scan.checkpoint()) return scan.failure();
				Object valueObject = scan.queue.removeFirst();
				scan.visit(valueObject);
			}
			RevisionBuilder built = scan.revision;
			if (built.candidateCount > limits.maxCandidates) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed result storage exceeds the 2,000,000-row limit");
			}
			Revision revision = built.finish(allocateRevisionId(), limits,
					scan.transientStorageBytes);
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, 0L, revision, scan.diagnosticMessage(), type,
					MemoryEngineContract.SEARCH_MODE_KNOWN, false);
		} catch (OperationCancelledException cancelled) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed search was cancelled");
		} catch (ResourceLimitException limit) {
			return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT, limit.getMessage());
		} catch (RuntimeException | LinkageError error) {
			return failure(token, MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed graph traversal failed safely");
		}
	}

	ManagedOperationResult startExact(long token, int type, int predicate, @Nullable String first,
	                                @Nullable String second, long operationEpoch) {
		QueryPlan plan = QueryPlan.fromKnownText(type, predicate, first, second);
		if (plan == null) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed search value is outside the selected primitive type");
		}
		return startExact(token, plan, operationEpoch);
	}

	private ManagedOperationResult startExact(long token, QueryPlan plan, long operationEpoch) {
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
		if (!snapshot.isAvailable()) {
			return failure(token, MemoryEngineContract.RESULT_UNSUPPORTED,
					managedFailure(snapshot, "Managed Java discovery is unavailable"));
		}
		if (!beginOperation(token, operationEpoch, snapshot.loader())) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed search was cancelled before it started");
		}
		long retainedRevisionBytes;
		synchronized (stateLock) {
			retainedRevisionBytes = retainedSearchStorageBytesLocked();
		}
		ScanContext scan = new ScanContext(token, operationEpoch, snapshot, plan,
				retainedRevisionBytes);
		try {
			scan.enqueue(snapshot.root());
			Class<?>[] classes = snapshot.classes();
			if (classes.length > limits.maxClasses) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed class snapshot exceeds the resource limit");
			}
			long classSnapshotBytes = checkedMultiply(classes.length, SNAPSHOT_REFERENCE_BYTES);
			scan.reserveTraversalBytes(classSnapshotBytes);
			try {
				for (Class<?> classType : classes) {
					if (!scan.scanStaticClass(classType)) return scan.failure();
				}
			} finally {
				scan.releaseTraversalBytes(classSnapshotBytes);
			}
			while (!scan.queue.isEmpty()) {
				if (!scan.checkpoint()) return scan.failure();
				scan.visit(scan.queue.removeFirst());
			}
			Revision revision = scan.revision.finish(allocateRevisionId(), limits,
					scan.transientStorageBytes);
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, 0L, revision, scan.diagnosticMessage(),
					plan.selector, MemoryEngineContract.SEARCH_MODE_KNOWN, false);
		} catch (OperationCancelledException cancelled) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed search was cancelled");
		} catch (ResourceLimitException limit) {
			return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT, limit.getMessage());
		} catch (RuntimeException | LinkageError error) {
			return failure(token, MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed graph traversal failed safely");
		}
	}

	/** Captures a typed baseline for an Unknown search without inventing a raw snapshot. */
	ManagedOperationResult startUnknown(long token, int type, long operationEpoch) {
		if (!MemoryEngineContract.isValueType(type)) {
			return failure(token, MemoryEngineContract.RESULT_UNSUPPORTED,
					"Managed Unknown search received an unsupported type selector");
		}
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
		if (!snapshot.isAvailable()) {
			return failure(token, MemoryEngineContract.RESULT_UNSUPPORTED,
					managedFailure(snapshot, "Managed Java discovery is unavailable"));
		}
		if (!beginOperation(token, operationEpoch, snapshot.loader())) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed Unknown search was cancelled before it started");
		}
		long retainedRevisionBytes;
		synchronized (stateLock) {
			retainedRevisionBytes = retainedSearchStorageBytesLocked();
		}
		ScanContext scan = new ScanContext(token, operationEpoch, snapshot,
				QueryPlan.unknown(type), retainedRevisionBytes, true);
		try {
			scan.enqueue(snapshot.root());
			Class<?>[] classes = snapshot.classes();
			if (classes.length > limits.maxClasses) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed class snapshot exceeds the resource limit");
			}
			long classSnapshotBytes = checkedMultiply(classes.length, SNAPSHOT_REFERENCE_BYTES);
			scan.reserveTraversalBytes(classSnapshotBytes);
			try {
				for (Class<?> classType : classes) {
					if (!scan.scanStaticClass(classType)) return scan.failure();
				}
			} finally {
				scan.releaseTraversalBytes(classSnapshotBytes);
			}
			while (!scan.queue.isEmpty()) {
				if (!scan.checkpoint()) return scan.failure();
				scan.visit(scan.queue.removeFirst());
			}
			Revision revision = scan.revision.finish(allocateRevisionId(), limits,
					scan.transientStorageBytes);
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, 0L, revision, scan.diagnosticMessage(), type,
					MemoryEngineContract.SEARCH_MODE_UNKNOWN, true);
		} catch (OperationCancelledException cancelled) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed Unknown search was cancelled");
		} catch (ResourceLimitException limit) {
			return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT, limit.getMessage());
		} catch (RuntimeException | LinkageError error) {
			return failure(token, MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed graph traversal failed safely");
		}
	}

	/** Starts the Managed same-owner group search for one concrete type and 2–8 values. */
	ManagedOperationResult startGroup(long token, int type, @Nullable String[] values,
	                                long operationEpoch) {
		GroupPlan plan = GroupPlan.fromText(type, values);
		if (plan == null) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Group search requires 2 to 8 values of one concrete primitive type");
		}
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
		if (!snapshot.isAvailable()) {
			return failure(token, MemoryEngineContract.RESULT_UNSUPPORTED,
					managedFailure(snapshot, "Managed Java discovery is unavailable"));
		}
		if (!beginOperation(token, operationEpoch, snapshot.loader())) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed Group search was cancelled before it started");
		}
		long retainedRevisionBytes;
		synchronized (stateLock) {
			retainedRevisionBytes = retainedSearchStorageBytesLocked();
		}
		ScanContext scan = new ScanContext(token, operationEpoch, snapshot, plan,
				retainedRevisionBytes);
		try {
			scan.enqueue(snapshot.root());
			Class<?>[] classes = snapshot.classes();
			if (classes.length > limits.maxClasses) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed class snapshot exceeds the resource limit");
			}
			long classSnapshotBytes = checkedMultiply(classes.length, SNAPSHOT_REFERENCE_BYTES);
			scan.reserveTraversalBytes(classSnapshotBytes);
			try {
				for (Class<?> classType : classes) {
					if (!scan.scanStaticClass(classType)) return scan.failure();
				}
			} finally {
				scan.releaseTraversalBytes(classSnapshotBytes);
			}
			while (!scan.queue.isEmpty()) {
				if (!scan.checkpoint()) return scan.failure();
				scan.visit(scan.queue.removeFirst());
			}
			Revision revision = scan.revision.finish(allocateRevisionId(), limits,
					scan.transientStorageBytes);
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, 0L, revision, scan.diagnosticMessage(),
					type, MemoryEngineContract.SEARCH_MODE_GROUP, false);
		} catch (OperationCancelledException cancelled) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed Group search was cancelled");
		} catch (ResourceLimitException limit) {
			return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT, limit.getMessage());
		} catch (RuntimeException | LinkageError error) {
			return failure(token, MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed Group traversal failed safely");
		}
	}

	ManagedOperationResult refineInt(long token, long expectedRevision, int predicate,
	                                int compareTarget, int value, long operationEpoch) {
		return refine(token, expectedRevision, MemoryEngineContract.TYPE_INT, predicate,
				compareTarget, value, 0L, operationEpoch);
	}

	ManagedOperationResult refine(long token, long expectedRevision, int type, int predicate,
	                              int compareTarget, long first, long second,
	                              long operationEpoch) {
		boolean relative = predicate >= MemoryEngineContract.PREDICATE_CHANGED;
		QueryPlan plan = relative
				? QueryPlan.fromRelativeBits(type, predicate, first, second)
				: QueryPlan.fromKnownBits(type, predicate, first, second);
		if (plan == null) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					relative ? "Managed relative refine received an invalid typed query"
							: "Managed known refine received an invalid typed query");
		}
		if (compareTarget != MemoryEngineContract.COMPARE_PREVIOUS
				&& compareTarget != MemoryEngineContract.COMPARE_INITIAL) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed refine received an invalid baseline selector");
		}
		Revision old;
		synchronized (stateLock) {
			if (!isCurrentLocked(token) || committed == null || committed.id != expectedRevision) {
				return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed result page is stale; refresh the search");
			}
			old = committed;
		}
		if (!isOperationActive(token, operationEpoch)) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed refine was cancelled");
		}

		RevisionBuilder next = new RevisionBuilder(retainedSearchStorageBytes());
		long visited = 0L;
		long[] current = new long[1];
		try {
			for (BucketView bucket : old.buckets) {
				Object strongOwner = bucket.owner.strongOwner();
				if (bucket.owner.kind != KIND_STATIC_FIELD && strongOwner == null) {
					continue;
				}
				for (int index = 0; index < bucket.slots.length; index++) {
					if ((visited++ & (CHECK_INTERVAL - 1)) == 0L
							&& !isOperationActive(token, operationEpoch)) {
						return failure(token, MemoryEngineContract.RESULT_CANCELLED,
								"Managed refine was cancelled");
					}
					int candidateType = valueTypeFor(bucket.owner, bucket.slots[index]);
					// Eligibility is decided from the logical schema before reading the live value.
					// A concrete refine therefore cannot reinterpret another primitive plane.
					if (!plan.accepts(candidateType)
							|| !readTyped(bucket.owner, bucket.slots[index], strongOwner, current)) {
						continue;
					}
					long baseline = compareTarget == MemoryEngineContract.COMPARE_INITIAL
							? bucket.initial[index] : bucket.previous[index];
					boolean matches = relative
							? plan.matchesRelative(candidateType, predicate, current[0], baseline)
							: plan.matchesKnown(candidateType, current[0]);
					if (matches) {
						if (!next.add(bucket.owner, bucket.slots[index], bucket.initial[index], current[0], limits)) {
							throw new ResourceLimitException("Managed result storage exceeds the resource limit");
						}
					}
				}
			}
			Revision revision = next.finish(allocateRevisionId(), limits, 0L);
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, expectedRevision, revision, null,
					type,
					searchMode, false);
		} catch (ResourceLimitException limit) {
			return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT, limit.getMessage());
		} catch (RuntimeException | LinkageError error) {
			return failure(token, MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed refine failed safely");
		}
	}

	ManagedOperationResult refine(long token, long expectedRevision, int type, int predicate,
	                              int compareTarget, @Nullable String first,
	                              @Nullable String second, long operationEpoch) {
		boolean relative = predicate >= MemoryEngineContract.PREDICATE_CHANGED;
		QueryPlan plan = relative
				? QueryPlan.fromRelativeText(type, predicate, first, second)
				: QueryPlan.fromKnownText(type, predicate, first, second);
		if (plan == null) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed refine value is outside the selected primitive type");
		}
		return refineWithPlan(token, expectedRevision, plan, predicate, compareTarget,
				operationEpoch);
	}

	private ManagedOperationResult refineWithPlan(long token, long expectedRevision, QueryPlan plan,
	                                            int predicate, int compareTarget,
	                                            long operationEpoch) {
		if (compareTarget != MemoryEngineContract.COMPARE_PREVIOUS
				&& compareTarget != MemoryEngineContract.COMPARE_INITIAL) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed refine received an invalid baseline selector");
		}
		Revision old;
		synchronized (stateLock) {
			if (!isCurrentLocked(token) || committed == null || committed.id != expectedRevision) {
				return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed result page is stale; refresh the search");
			}
			old = committed;
		}
		if (!isOperationActive(token, operationEpoch)) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed refine was cancelled");
		}
		RevisionBuilder next = new RevisionBuilder(retainedSearchStorageBytes());
		long visited = 0L;
		long[] current = new long[1];
		try {
			for (BucketView bucket : old.buckets) {
				Object strongOwner = bucket.owner.strongOwner();
				if (bucket.owner.kind != KIND_STATIC_FIELD && strongOwner == null) continue;
				for (int index = 0; index < bucket.slots.length; index++) {
					if ((visited++ & (CHECK_INTERVAL - 1)) == 0L
							&& !isOperationActive(token, operationEpoch)) {
						return failure(token, MemoryEngineContract.RESULT_CANCELLED,
								"Managed refine was cancelled");
					}
					int candidateType = valueTypeFor(bucket.owner, bucket.slots[index]);
					if (!plan.accepts(candidateType)
							|| !readTyped(bucket.owner, bucket.slots[index], strongOwner, current)) continue;
					long baseline = compareTarget == MemoryEngineContract.COMPARE_INITIAL
							? bucket.initial[index] : bucket.previous[index];
					boolean matches = predicate >= MemoryEngineContract.PREDICATE_CHANGED
							? plan.matchesRelative(candidateType, predicate, current[0], baseline)
							: plan.matchesKnown(candidateType, current[0]);
					if (matches && !next.add(bucket.owner, bucket.slots[index],
							bucket.initial[index], current[0], limits)) {
						throw new ResourceLimitException("Managed result storage exceeds the resource limit");
					}
				}
			}
			Revision revision = next.finish(allocateRevisionId(), limits, 0L);
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, expectedRevision, revision, null,
					plan.selector, searchMode, false);
		} catch (ResourceLimitException limit) {
			return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT, limit.getMessage());
		} catch (RuntimeException | LinkageError error) {
			return failure(token, MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed refine failed safely");
		}
	}

	ManagedPage resultPage(long token, long expectedRevision, int offset, int limit) {
		if (offset < 0 || limit <= 0 || limit > MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
			return ManagedPage.empty(expectedRevision);
		}
		Revision revision;
		synchronized (stateLock) {
			if (!isCurrentLocked(token) || committed == null || committed.id != expectedRevision
					|| (searchStage != MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE
					&& searchStage != MemoryEngineContract.SEARCH_SESSION_CANDIDATES)) {
				return ManagedPage.empty(expectedRevision);
			}
			revision = committed;
		}
		long start = offset;
		if (start >= revision.count) return ManagedPage.empty(revision.id);
		int outputCount = (int) Math.min((long) limit, revision.count - start);
		ManagedPage page = ManagedPage.allocate(outputCount, revision.id);
		long ordinal = 0L;
		int output = 0;
		long[] value = new long[1];
		for (BucketView bucket : revision.buckets) {
			Object strongOwner = bucket.owner.strongOwner();
			for (int index = 0; index < bucket.slots.length; index++) {
				if (ordinal++ < start) continue;
				if (output >= outputCount) return page;
				int slot = bucket.slots[index];
				int type = valueTypeFor(bucket.owner, slot);
				page.ids[output] = ManagedJavaMemoryIds.encode(bucket.owner.kind,
						bucket.owner.handle, slot);
				page.values[output] = readValueText(bucket.owner, slot, strongOwner, type,
						page.states, output, value);
				page.addresses[output] = labelFor(bucket.owner, slot);
				page.aliasMasks[output] = 1 << type;
				page.types[output] = type;
				page.relocations[output] = 0;
				output++;
			}
		}
		return page;
	}

	/** Bounded logical sibling read for a managed candidate; no physical address is fabricated. */
	ManagedInspection inspect(long token, long expectedRevision, long anchorId, int radius) {
		return inspect(token, expectedRevision, anchorId, radius, true);
	}

	/** Bounded logical sibling read with explicit Results-versus-Watch provenance. */
	ManagedInspection inspect(long token, long expectedRevision, long anchorId, int radius,
	                         boolean allowWatchOnly) {
		if (!ManagedJavaMemoryIds.hasValidNamespace(anchorId)
				|| !MemoryEngineContract.isInspectRadius(radius)) {
			return ManagedInspection.failure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Inspector requires a valid logical candidate and bounded radius");
		}
		ResolvedBatch anchor = resolveBatch(token, expectedRevision, new long[]{anchorId}, allowWatchOnly);
		if (anchor.error != null) {
			return ManagedInspection.failure(anchor.error.code, anchor.error.message);
		}
		OwnerBucket owner = anchor.owners[0];
		Object strongOwner = anchor.strongOwners[0];
		int anchorSlot = anchor.slots[0];
		// Modified: logical windows use the result-row budget, not the raw byte budget.
		radius = Math.min(radius, (MemoryEngineContract.MAX_RESULT_PAGE_SIZE - 1) / 2);
		ArrayList<Integer> slots = new ArrayList<>();
		if (owner.kind == KIND_ARRAY) {
			if (strongOwner == null) return ManagedInspection.failure(
					MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "The managed Inspector owner was collected");
			int length;
			try {
				length = Array.getLength(strongOwner);
			} catch (RuntimeException error) {
				return ManagedInspection.failure(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed Inspector array is unavailable");
			}
			int start = Math.max(0, anchorSlot - radius);
			int end = (int) Math.min(length - 1L, (long) anchorSlot + radius);
			for (int slot = start; slot <= end; slot++) slots.add(slot);
		} else {
			int start = Math.max(0, anchorSlot - radius);
			int end = Math.min(owner.schema.fields.length - 1, anchorSlot + radius);
			for (int slot = start; slot <= end; slot++) {
				FieldSlot field = owner.schema.fieldAt(slot);
				boolean belongsToOwner = owner.kind == KIND_STATIC_FIELD
						? field != null && field.declaringClass == owner.staticClass
						&& field.kindStatic
						: field != null && !field.kindStatic;
				if (belongsToOwner && field.primitiveField
						&& ManagedJavaValue.isSupportedType(field.valueType)) {
					slots.add(slot);
				}
			}
		}
		if (slots.isEmpty()) {
			return ManagedInspection.failure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Inspector found no logical primitive siblings");
		}
		long[] ids = new long[slots.size()];
		String[] values = new String[slots.size()];
		String[] initial = new String[slots.size()];
		String[] previous = new String[slots.size()];
		int[] types = new int[slots.size()];
		int[] states = new int[slots.size()];
		int[] offsets = new int[slots.size()];
		long[] expectedBits = new long[slots.size()];
		String[] labels = new String[slots.size()];
		boolean[] editable = new boolean[slots.size()];
		long[] read = new long[1];
		Revision revision;
		synchronized (stateLock) {
			revision = committed;
		}
		for (int index = 0; index < slots.size(); index++) {
			int slot = slots.get(index);
			int type = valueTypeFor(owner, slot);
			ids[index] = ManagedJavaMemoryIds.encode(owner.kind, owner.handle, slot);
			types[index] = type;
			offsets[index] = slot - anchorSlot;
			labels[index] = labelFor(owner, slot);
			if (owner.kind == KIND_ARRAY) {
				editable[index] = true;
			} else {
				FieldSlot field = owner.schema.fieldAt(slot);
				editable[index] = field != null && !field.finalField;
			}
			if (!readInspectable(owner, slot, strongOwner, read)) {
				states[index] = MemoryEngineContract.CANDIDATE_LOST;
				editable[index] = false;
				values[index] = "LOST";
				initial[index] = "LOST";
				previous[index] = "LOST";
				continue;
			}
			states[index] = MemoryEngineContract.CANDIDATE_STABLE;
			expectedBits[index] = read[0];
			values[index] = ManagedJavaValue.format(type, read[0]);
			long[] baseline = baselineFor(revision, owner, slot, read[0]);
			initial[index] = ManagedJavaValue.format(type, baseline[0]);
			previous[index] = ManagedJavaValue.format(type, baseline[1]);
		}
		return new ManagedInspection(MemoryEngineContract.RESULT_OK,
				revision == null ? expectedRevision : revision.id, ids, values, initial, previous,
				types, states, offsets, expectedBits, labels, editable,
				"managed-logical-owner=" + owner.handle + ":slot=" + anchorSlot, null);
	}

	ManagedOperationResult editInspector(long token, long expectedRevision, long anchorId,
	                                    int relativeOffset, int valueType, long expectedBits,
	                                    @Nullable String replacement, long operationEpoch) {
		return editInspector(token, expectedRevision, anchorId, true, relativeOffset, valueType,
				expectedBits, replacement, operationEpoch);
	}

	ManagedOperationResult editInspector(long token, long expectedRevision, long anchorId,
	                                    boolean allowWatchOnly, int relativeOffset, int valueType,
	                                    long expectedBits, @Nullable String replacement,
	                                    long operationEpoch) {
		if (!ManagedJavaMemoryIds.hasValidNamespace(anchorId)
				|| !MemoryEngineContract.isCandidateType(valueType)
				|| Math.abs((long) relativeOffset) > MemoryEngineContract.MAX_INSPECT_RADIUS) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Inspector edit request is outside its bounded logical contract");
		}
		ResolvedBatch anchor = resolveBatch(token, expectedRevision, new long[]{anchorId}, allowWatchOnly);
		if (anchor.error != null) return anchor.error;
		OwnerBucket owner = anchor.owners[0];
		int targetSlot = anchor.slots[0] + relativeOffset;
		if (targetSlot < 0 || (owner.kind == KIND_ARRAY
				? anchor.strongOwners[0] == null || targetSlot >= Array.getLength(anchor.strongOwners[0])
				: targetSlot >= owner.schema.fields.length)) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Inspector sibling is outside the logical owner");
		}
		if (owner.kind == KIND_STATIC_FIELD) {
			FieldSlot targetField = owner.schema.fieldAt(targetSlot);
			if (targetField == null || !targetField.kindStatic
					|| targetField.declaringClass != owner.staticClass) {
				return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
						"Managed Inspector sibling is outside the static owner class");
			}
		} else if (owner.kind == KIND_OBJECT_FIELD) {
			FieldSlot targetField = owner.schema.fieldAt(targetSlot);
			if (targetField == null || targetField.kindStatic) {
				return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
						"Managed Inspector sibling is not an instance field");
			}
		}
		int actualType = valueTypeFor(owner, targetSlot);
		if (actualType != valueType) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Inspector sibling type no longer matches the snapshot");
		}
		if (owner.kind != KIND_ARRAY && owner.schema.fieldAt(targetSlot).finalField) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Inspector final fields are read-only");
		}
		long[] parsed = new long[1];
		if (!ManagedJavaValue.parse(replacement, valueType, parsed)) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Inspector replacement is outside the selected primitive type");
		}
		long[] current = new long[1];
		Object strongOwner = anchor.strongOwners[0];
		if (!readInspectable(owner, targetSlot, strongOwner, current)
				|| !writeConfirmed(current[0], expectedBits)) {
			return resultDetailed(token, MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
					"Managed Inspector optimistic readback anchor changed before write", 0, 0,
					0, 0, 0, 0, 0);
		}
		ManagedOperationResult validation = revalidateBatch(token, expectedRevision, anchor,
				allowWatchOnly, operationEpoch);
		if (validation != null) return validation;
		if (!isOperationActive(token, operationEpoch)) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed Inspector edit was cancelled before writing");
		}
		try {
			writeTyped(owner, targetSlot, strongOwner, valueType, parsed[0]);
			if (!readTyped(owner, targetSlot, strongOwner, current)
					|| !writeConfirmed(current[0], parsed[0])) {
				return resultDetailed(token, MemoryEngineContract.RESULT_PARTIAL_WRITE,
						"Managed Inspector write was not confirmed", 1, 0, 0, 1, 0, 0, 0);
			}
			return resultDetailed(token, MemoryEngineContract.RESULT_OK,
					"Managed Inspector write confirmed", 1, 1, 0, 0, 0, 0, 0);
		} catch (IllegalAccessException | RuntimeException | LinkageError error) {
			return resultDetailed(token, MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
					"Managed Inspector write was rejected before confirmation", 1, 0, 1, 0, 0, 1, 0);
		}
	}

	/** Revision-aware Keep/Remove over managed candidate IDs after a refine. */
	ManagedOperationResult filter(long token, long expectedRevision, @Nullable long[] ids,
	                              boolean keep, long operationEpoch) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed candidate filtering requires a bounded non-empty id list");
		}
		long[] selected = uniqueIds(ids);
		Revision old;
		HashSet<Long> selectedSet = new HashSet<>(selected.length * 2);
		synchronized (stateLock) {
			if (!isCurrentLocked(token) || committed == null || committed.id != expectedRevision
					|| searchStage != MemoryEngineContract.SEARCH_SESSION_CANDIDATES) {
				return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed candidate revision is stale or not filterable");
			}
			for (long id : selected) {
				if (!ManagedJavaMemoryIds.hasValidNamespace(id)
						|| !committed.contains(ManagedJavaMemoryIds.ownerHandle(id),
						ManagedJavaMemoryIds.kind(id), ManagedJavaMemoryIds.slot(id))) {
					return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
							"The filter contains an id outside the committed managed revision");
				}
				selectedSet.add(id);
			}
			old = committed;
		}
		RevisionBuilder next = new RevisionBuilder(retainedSearchStorageBytes());
		long visited = 0L;
		try {
			for (BucketView bucket : old.buckets) {
				for (int index = 0; index < bucket.slots.length; index++) {
					if ((visited++ & (CHECK_INTERVAL - 1)) == 0L
							&& !isOperationActive(token, operationEpoch)) {
						return failure(token, MemoryEngineContract.RESULT_CANCELLED,
								"Managed filtering was cancelled");
					}
					long id = ManagedJavaMemoryIds.encode(bucket.owner.kind,
							bucket.owner.handle, bucket.slots[index]);
					boolean selectedRow = selectedSet.contains(id);
					if ((keep && !selectedRow) || (!keep && selectedRow)) continue;
					if (!next.add(bucket.owner, bucket.slots[index], bucket.initial[index],
							bucket.previous[index], limits)) {
						throw new ResourceLimitException("Managed candidate storage exceeds the resource limit");
					}
				}
			}
			Revision revision = next.finish(allocateRevisionId(), limits, 0L);
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, expectedRevision, revision, null,
					requestedType, searchMode, false);
		} catch (ResourceLimitException limit) {
			return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT, limit.getMessage());
		} catch (RuntimeException | LinkageError error) {
			return failure(token, MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed candidate filtering failed safely");
		}
	}

	ManagedPage watchPage(long token) {
		WatchSnapshot snapshot;
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return ManagedPage.empty(0L);
			snapshot = watches.snapshot(owners);
		}
		ManagedPage page = ManagedPage.allocateWatch(snapshot.count, snapshot.revision);
		long[] value = new long[1];
		for (int index = 0; index < snapshot.count; index++) {
			OwnerBucket owner = snapshot.owners[index];
			Object strongOwner = owner == null ? null : owner.strongOwner();
			int slot = snapshot.slots[index];
			int type = valueTypeFor(owner, slot);
			page.ids[index] = snapshot.ids[index];
			page.values[index] = readValueText(owner, slot, strongOwner, type, page.states, index, value);
			page.initialValues[index] = ManagedJavaValue.format(type, snapshot.initial[index]);
			page.previousValues[index] = ManagedJavaValue.format(type, snapshot.previous[index]);
			page.addresses[index] = owner == null ? "managed#LOST" : labelFor(owner, slot);
			page.types[index] = type;
			page.relocations[index] = 0;
			page.labels[index] = snapshot.labels[index] == null ? "" : snapshot.labels[index];
			page.freezeModes[index] = snapshot.freeze[index] ? MemoryEngineContract.FREEZE_LOCK : -1;
			page.freezePaused[index] = snapshot.freezePaused[index]
					|| page.states[index] == MemoryEngineContract.CANDIDATE_LOST;
			if (page.freezePaused[index] && snapshot.freeze[index]) markFreezePaused(snapshot.ids[index]);
		}
		return page;
	}

	ManagedOperationResult refresh(long token, @Nullable long[] ids, long expectedRevision,
	                              long operationEpoch) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed refresh requires a bounded id list");
		}
		ResolvedBatch batch = resolveBatch(token, expectedRevision, ids, true);
		if (batch.error != null) return batch.error;
		long[] value = new long[1];
		for (int index = 0; index < batch.count; index++) {
			if (!readTyped(batch.owners[index], batch.slots[index], batch.strongOwners[index], value)) {
				if (batch.watch[index]) markFreezePausedIfNeeded(ids[index]);
				continue;
			}
			if (batch.watch[index]) updateWatchPrevious(ids[index], value[0]);
		}
		return success(token, null);
	}

	ManagedOperationResult edit(long token, long expectedRevision, @Nullable long[] ids,
	                           int replacement, long operationEpoch) {
		// The package-level overload is retained for characterization tests and older callers. A
		// zero revision is meaningful only for an explicit Watch-only route; it is not a wildcard
		// for selecting from the current search revision.
		return edit(token, expectedRevision, ids, replacement, expectedRevision <= 0L,
				operationEpoch);
	}

	ManagedOperationResult edit(long token, long expectedRevision, @Nullable long[] ids,
	                           int replacement, boolean allowWatchOnly, long operationEpoch) {
		return editTyped(token, expectedRevision, ids, Integer.toString(replacement),
				allowWatchOnly, operationEpoch);
	}

	ManagedOperationResult editTyped(long token, long expectedRevision, @Nullable long[] ids,
	                                @Nullable String replacementValue, boolean allowWatchOnly,
	                                long operationEpoch) {
		return editTyped(token, expectedRevision, ids, MemoryEngineContract.TYPE_AUTO,
				replacementValue, allowWatchOnly, operationEpoch);
	}

	/**
	 * Edits the union of the selected rows that have the declared concrete type. Rows of another
	 * type are intentionally skipped; a batch is not narrowed by an intersection of all row types.
	 */
	ManagedOperationResult editTyped(long token, long expectedRevision, @Nullable long[] ids,
	                                int declaredType, @Nullable String replacementValue,
	                                boolean allowWatchOnly, long operationEpoch) {
		if (!MemoryEngineContract.isValueType(declaredType)) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed edit received an unsupported declared primitive type");
		}
		int maxTargets = allowWatchOnly ? MemoryEngineContract.MAX_WATCH_RECORDS
				: MemoryEngineContract.MAX_RESULT_PAGE_SIZE;
		if (ids == null || ids.length == 0 || ids.length > maxTargets) {
			return failure(token, MemoryEngineContract.RESULT_SAFETY_LIMIT,
					allowWatchOnly ? "Managed Watch edits are limited to 128 rows"
							: "Managed edits are limited to 100 visible result rows");
		}
		long[] unique = uniqueIds(ids);
		ResolvedBatch batch = resolveBatch(token, expectedRevision, unique, allowWatchOnly);
		if (batch.error != null) return batch.error;
		int[] selectedIndexes = new int[batch.count];
		int selectedCount = 0;
		int skippedByType = 0;
		for (int index = 0; index < batch.count; index++) {
			int actualType = valueTypeFor(batch.owners[index], batch.slots[index]);
			if (allowWatchOnly && declaredType != MemoryEngineContract.TYPE_AUTO
					&& actualType != declaredType) {
				return failure(token, MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"A retained Watch target no longer matches the declared primitive type");
			}
			if (declaredType == MemoryEngineContract.TYPE_AUTO || actualType == declaredType) {
				selectedIndexes[selectedCount++] = index;
			} else {
				skippedByType++;
			}
		}
		ResolvedBatch writableBatch = selectBatch(batch, selectedIndexes, selectedCount);
		if (writableBatch.count == 0) {
			return resultDetailed(token, MemoryEngineContract.RESULT_OK,
					"Managed edit skipped " + skippedByType + " row(s) by declared type",
					0, 0, 0, 0, 0, 0, skippedByType);
		}
		long[] replacements = new long[writableBatch.count];
		long[] parsed = new long[1];
		for (int index = 0; index < writableBatch.count; index++) {
			int type = valueTypeFor(writableBatch.owners[index], writableBatch.slots[index]);
			if (!ManagedJavaValue.parse(replacementValue, type, parsed)) {
				return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
						"Managed edit value is outside the selected primitive type");
			}
			replacements[index] = parsed[0];
		}
		ManagedOperationResult validation = revalidateBatch(token, expectedRevision, writableBatch,
				allowWatchOnly, operationEpoch);
		if (validation != null) return validation;
		if (!isOperationActive(token, operationEpoch)) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed edit was cancelled before writing");
		}
		int attempted = 0;
		int written = 0;
		int rejectedBeforeWrite = 0;
		int unconfirmed = 0;
		long[] readback = new long[1];
		for (int chunkStart = 0; chunkStart < writableBatch.count; chunkStart += MemoryEngineContract.MAX_MULTI_WRITE) {
			if (!isOperationActive(token, operationEpoch)) break;
			ManagedOperationResult chunkValidation = revalidateBatch(token, expectedRevision, writableBatch,
					allowWatchOnly, operationEpoch);
			if (chunkValidation != null) {
				if (written == 0 && attempted == 0 && rejectedBeforeWrite == 0 && unconfirmed == 0) {
					return chunkValidation;
				}
				break;
			}
			int chunkEnd = Math.min(writableBatch.count, chunkStart + MemoryEngineContract.MAX_MULTI_WRITE);
			for (int index = chunkStart; index < chunkEnd; index++) {
				if (!isOperationActive(token, operationEpoch)) break;
				Object owner = writableBatch.strongOwners[index];
				if (writableBatch.owners[index].kind != KIND_STATIC_FIELD && owner == null) {
					rejectedBeforeWrite++;
					continue;
				}
				attempted++;
				try {
					int type = valueTypeFor(writableBatch.owners[index], writableBatch.slots[index]);
					writeTyped(writableBatch.owners[index], writableBatch.slots[index], owner, type,
							replacements[index]);
					if (readTyped(writableBatch.owners[index], writableBatch.slots[index], owner, readback)
							&& writeConfirmed(readback[0], replacements[index])) {
						written++;
						if (writableBatch.watch[index]) updateWatchPrevious(writableBatch.ids[index], readback[0]);
					} else {
						unconfirmed++;
					}
				} catch (IllegalAccessException | RuntimeException | LinkageError error) {
					rejectedBeforeWrite++;
				}
			}
		}
		int code;
		int notAttempted = writableBatch.count - written - unconfirmed - rejectedBeforeWrite;
		if (written == writableBatch.count) code = MemoryEngineContract.RESULT_OK;
		else if (written > 0) code = MemoryEngineContract.RESULT_PARTIAL_WRITE;
		else if (attempted > 0 || rejectedBeforeWrite > 0 || unconfirmed > 0) {
			code = MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		} else if (notAttempted > 0) {
			code = MemoryEngineContract.RESULT_CANCELLED;
		} else {
			code = MemoryEngineContract.RESULT_OK;
		}
		String message = "Managed edit attempted " + attempted + ", confirmed " + written
				+ ", unconfirmed " + unconfirmed + ", rejected before write "
				+ rejectedBeforeWrite + ", not attempted " + notAttempted
				+ ", skipped by type " + skippedByType;
		return resultDetailed(token, code, message, attempted, written, rejectedBeforeWrite,
				unconfirmed, notAttempted, rejectedBeforeWrite, skippedByType);
	}

	/* Kept as a single detailed constructor so counter fields cannot overlap by arithmetic. */
	private ManagedOperationResult resultDetailed(long token, int code, @Nullable String message,
	                                             int attempted, int written, int skipped,
	                                             int unconfirmed, int notAttempted,
	                                             int rejectedBeforeWrite, int skippedByType) {
		synchronized (stateLock) {
			long revision = committed == null ? 0L : committed.id;
			return new ManagedOperationResult(code, revision, visibleResultCountLocked(),
					baselineCount, message, attempted, written, skipped, unconfirmed, notAttempted,
					rejectedBeforeWrite, skippedByType, watches.count, watches.freezeCount());
		}
	}

	ManagedOperationResult addWatch(long token, long expectedRevision, @Nullable long[] ids,
	                               long operationEpoch) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_WATCH_RECORDS) {
			return failure(token, MemoryEngineContract.RESULT_SAFETY_LIMIT,
					"Managed Watch List requests are bounded to 128 rows");
		}
		long[] unique = uniqueIds(ids);
		// Adding a baseline row to Watch is allowed before the first Unknown refine. The
		// baseline is read-only as a search result, but Watch is the explicit way to retain
		// that logical owner for later refresh/edit operations.
		ResolvedBatch batch = resolveBatch(token, expectedRevision, unique, true);
		if (batch.error != null) return batch.error;
		int additional = 0;
		synchronized (stateLock) {
			for (long id : unique) if (watches.indexOf(id) < 0) additional++;
			if (watches.count + additional > MemoryEngineContract.MAX_WATCH_RECORDS) {
				return failureLocked(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"The global Watch List limit is 128 rows");
			}
		}
		long[] currentValues = new long[unique.length];
		long[] read = new long[1];
		for (int index = 0; index < unique.length; index++) {
			if (!readTyped(batch.owners[index], batch.slots[index], batch.strongOwners[index], read)) {
				return failure(token, MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"A managed owner was collected before it could be watched");
			}
			currentValues[index] = read[0];
		}
		if (!isOperationActive(token, operationEpoch)) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed Watch request was cancelled");
		}
		synchronized (stateLock) {
			ManagedOperationResult validation = revalidateBatchLocked(token, expectedRevision, batch,
				true, operationEpoch);
			if (validation != null) return validation;
			int additionalAtCommit = 0;
			for (long id : unique) if (watches.indexOf(id) < 0) additionalAtCommit++;
			if (watches.count + additionalAtCommit > MemoryEngineContract.MAX_WATCH_RECORDS) {
				return failureLocked(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"The global Watch List limit is 128 rows");
			}
			for (int index = 0; index < unique.length; index++) {
				if (watches.indexOf(unique[index]) >= 0) continue;
				watches.append(unique[index], batch.owners[index], batch.slots[index],
						currentValues[index], "");
			}
			cleanupOwnersLocked();
		}
		return success(token, null);
	}

	ManagedOperationResult removeWatch(long token, @Nullable long[] ids, long operationEpoch) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_WATCH_RECORDS) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Watch removal requires a bounded id list");
		}
		long[] unique = uniqueIds(ids);
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return failureLocked(
					MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet runtime changed or ended");
			for (long id : unique) {
				if (!ManagedJavaMemoryIds.hasValidNamespace(id) || watches.indexOf(id) < 0) {
					return failureLocked(MemoryEngineContract.RESULT_INVALID_REQUEST,
							"The managed Watch row is not a member of the Watch List");
				}
			}
			if (!isOperationActive(token, operationEpoch)) return failureLocked(
					MemoryEngineContract.RESULT_CANCELLED, "Managed Watch removal was cancelled");
			for (long id : unique) watches.remove(id);
			cleanupOwnersLocked();
		}
		return success(token, null);
	}

	ManagedOperationResult setWatchLabel(long token, long id, @Nullable String label,
	                                    long operationEpoch) {
		if (label == null || label.length() > 64) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Watch labels are limited to 64 characters");
		}
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return failureLocked(
					MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet runtime changed or ended");
			int index = watches.indexOf(id);
			if (!ManagedJavaMemoryIds.hasValidNamespace(id) || index < 0) {
				return failureLocked(MemoryEngineContract.RESULT_INVALID_REQUEST,
						"The managed Watch row is not a member of the Watch List");
			}
			if (!isOperationActive(token, operationEpoch)) return failureLocked(
					MemoryEngineContract.RESULT_CANCELLED, "Managed label update was cancelled");
			watches.labels[index] = label.trim();
		}
		return success(token, null);
	}

	ManagedOperationResult setFreezeLock(long token, long expectedRevision, @Nullable long[] ids,
	                                    int replacement, long operationEpoch) {
		return setFreezeLock(token, expectedRevision, ids, replacement, expectedRevision <= 0L,
				operationEpoch);
	}

	ManagedOperationResult setFreezeLock(long token, long expectedRevision, @Nullable long[] ids,
	                                    int replacement, boolean allowWatchOnly,
	                                    long operationEpoch) {
		return setFreezeLockTyped(token, expectedRevision, ids, Integer.toString(replacement),
				allowWatchOnly, operationEpoch);
	}

	ManagedOperationResult setFreezeLockTyped(long token, long expectedRevision,
	                                         @Nullable long[] ids, @Nullable String replacementValue,
	                                         boolean allowWatchOnly, long operationEpoch) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_FREEZE_RECORDS) {
			return failure(token, MemoryEngineContract.RESULT_SAFETY_LIMIT,
					"Managed Freeze Lock is limited to 32 rows");
		}
		long[] unique = uniqueIds(ids);
		ResolvedBatch batch = resolveBatch(token, expectedRevision, unique, allowWatchOnly);
		if (batch.error != null) return batch.error;
		long[] replacementValues = new long[unique.length];
		long[] parsed = new long[1];
		for (int index = 0; index < unique.length; index++) {
			int type = valueTypeFor(batch.owners[index], batch.slots[index]);
			if (!ManagedJavaValue.parse(replacementValue, type, parsed)) {
				return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
						"Managed Freeze Lock value is outside the selected primitive type");
			}
			replacementValues[index] = parsed[0];
		}
		int additionalWatches = 0;
		int additionalFreezes = 0;
		synchronized (stateLock) {
			for (long id : unique) {
				int watchIndex = watches.indexOf(id);
				if (watchIndex < 0) {
					additionalWatches++;
					// A new Freeze Lock row is also a new frozen record. Count both global
					// budgets before doing any target-side reads.
					additionalFreezes++;
				}
				else if (!watches.freeze[watchIndex]) additionalFreezes++;
			}
			if (watches.count + additionalWatches > MemoryEngineContract.MAX_WATCH_RECORDS
					|| watches.freezeCount() + additionalFreezes > MemoryEngineContract.MAX_FREEZE_RECORDS) {
				return failureLocked(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"The global Watch/Freeze limit would be exceeded");
			}
		}
		long[] currentValues = new long[unique.length];
		long[] read = new long[1];
		for (int index = 0; index < unique.length; index++) {
			if (!readTyped(batch.owners[index], batch.slots[index], batch.strongOwners[index], read)) {
				return failure(token, MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"A managed owner was collected before Freeze Lock could start");
			}
			currentValues[index] = read[0];
		}
		if (!isOperationActive(token, operationEpoch)) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed Freeze Lock was cancelled");
		}
		synchronized (stateLock) {
			ManagedOperationResult validation = revalidateBatchLocked(token, expectedRevision, batch,
				allowWatchOnly, operationEpoch);
			if (validation != null) return validation;
			int watchesToAdd = 0;
			int freezesToAdd = 0;
			for (long id : unique) {
				int watchIndex = watches.indexOf(id);
				if (watchIndex < 0) {
					watchesToAdd++;
					freezesToAdd++;
				} else if (!watches.freeze[watchIndex]) {
					freezesToAdd++;
				}
			}
			if (watches.count + watchesToAdd > MemoryEngineContract.MAX_WATCH_RECORDS
					|| watches.freezeCount() + freezesToAdd > MemoryEngineContract.MAX_FREEZE_RECORDS) {
				return failureLocked(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"The global Watch/Freeze limit would be exceeded");
			}
			for (int index = 0; index < unique.length; index++) {
				int watchIndex = watches.indexOf(unique[index]);
				if (watchIndex < 0) {
					watches.append(unique[index], batch.owners[index], batch.slots[index],
							currentValues[index], "");
					watchIndex = watches.count - 1;
				}
				watches.freeze[watchIndex] = true;
				watches.freezeValues[watchIndex] = replacementValues[index];
				watches.freezePaused[watchIndex] = false;
			}
			cleanupOwnersLocked();
		}
		return success(token, null);
	}

	ManagedOperationResult clearFreeze(long token, @Nullable long[] ids, long operationEpoch) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_FREEZE_RECORDS) {
			return failure(token, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Freeze removal requires a bounded id list");
		}
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return failureLocked(
					MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet runtime changed or ended");
			for (long id : ids) {
				int index = watches.indexOf(id);
				if (index < 0 || !watches.freeze[index]) return failureLocked(
						MemoryEngineContract.RESULT_INVALID_REQUEST,
						"The managed row is not frozen");
			}
			if (!isOperationActive(token, operationEpoch)) return failureLocked(
					MemoryEngineContract.RESULT_CANCELLED, "Managed Freeze removal was cancelled");
			for (long id : ids) {
				int index = watches.indexOf(id);
				watches.freeze[index] = false;
				watches.freezePaused[index] = false;
			}
		}
		return success(token, null);
	}

	ManagedOperationResult freezeTick(long token, long operationEpoch) {
		WatchSnapshot snapshot;
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return failureLocked(
					MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet runtime changed or ended");
			snapshot = watches.frozenSnapshot(owners);
		}
		int written = 0;
		long[] readback = new long[1];
		for (int index = 0; index < snapshot.count; index++) {
			if (!isOperationActive(token, operationEpoch)) return failure(token,
					MemoryEngineContract.RESULT_CANCELLED, "Managed Freeze tick was cancelled");
			OwnerBucket owner = snapshot.owners[index];
			Object strongOwner = owner == null ? null : owner.strongOwner();
			if (owner == null || (owner.kind != KIND_STATIC_FIELD && strongOwner == null)) {
				markFreezePaused(snapshot.ids[index]);
				continue;
			}
			try {
				int type = valueTypeFor(owner, snapshot.slots[index]);
				writeTyped(owner, snapshot.slots[index], strongOwner, type,
						snapshot.freezeValues[index]);
				if (readTyped(owner, snapshot.slots[index], strongOwner, readback)
						&& writeConfirmed(readback[0], snapshot.freezeValues[index])) {
					written++;
					updateWatchPrevious(snapshot.ids[index], readback[0]);
				} else {
					markFreezePaused(snapshot.ids[index]);
				}
			} catch (IllegalAccessException | RuntimeException | LinkageError error) {
				markFreezePaused(snapshot.ids[index]);
			}
		}
		int code = written == snapshot.count ? MemoryEngineContract.RESULT_OK
				: written > 0 ? MemoryEngineContract.RESULT_PARTIAL_WRITE
				: snapshot.count == 0 ? MemoryEngineContract.RESULT_OK
				: MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		return result(token, code,
				"Managed Freeze Lock tick wrote " + written + " of " + snapshot.count,
				snapshot.count, written, snapshot.count - written, 0);
	}

	void clearSearch(long token, long operationEpoch) {
		clearSearchResult(token, 0L, operationEpoch);
	}

	ManagedOperationResult clearSearchResult(long token, long expectedRevision, long operationEpoch) {
		advanceControlEpoch(operationEpoch);
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return failureLocked(
					MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet runtime changed or ended");
			if (expectedRevision > 0L && (committed == null || committed.id != expectedRevision)) {
				return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed search revision changed before it could be cleared");
			}
			committed = null;
			searchHistory.clear();
			baselineCount = 0L;
			searchStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
			searchMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
			requestedType = MemoryEngineContract.TYPE_AUTO;
			lastMessage = null;
			cleanupOwnersLocked();
			return successLocked(null);
		}
	}

	void cancel(long token, long operationEpoch) {
		if (token != 0L) advanceControlEpoch(operationEpoch);
	}

	/** Restores the previous search payload without scanning or writing the guest runtime. */
	ManagedOperationResult undo(long token, long expectedRevision, long operationEpoch) {
		advanceControlEpoch(operationEpoch);
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return failureLocked(
					MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet runtime changed or ended");
			if (!isOperationActive(token, operationEpoch)) return failureLocked(
					MemoryEngineContract.RESULT_CANCELLED, "Managed Undo was cancelled");
			long currentRevision = committed == null ? 0L : committed.id;
			if (expectedRevision <= 0L || currentRevision != expectedRevision) {
				return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed search revision changed before Undo could commit");
			}
			if (searchHistory.isEmpty()) return failureLocked(
					MemoryEngineContract.RESULT_NO_SESSION, "No managed search history is available");
			long publicationId = allocateRevisionId();
			if (publicationId == 0L) return failureLocked(
					MemoryEngineContract.RESULT_RESOURCE_LIMIT,
					"Managed revision identifier space is exhausted");
			SearchState restored = searchHistory.removeLast();
			committed = restored.revision.withId(publicationId);
			searchStage = restored.stage;
			searchMode = restored.mode;
			requestedType = restored.requestedType;
			baselineCount = restored.baselineCount;
			lastMessage = "Managed search restored from history";
			cleanupOwnersLocked();
			return successLocked(lastMessage);
		}
	}

	void runtimeClosed(long token) {
		controlEpoch.incrementAndGet();
		synchronized (stateLock) {
			if (token == 0L || activeToken == token) {
				activeToken = 0L;
				activeLoader = null;
				committed = null;
				searchHistory.clear();
				baselineCount = 0L;
				searchStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
				searchMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
				requestedType = MemoryEngineContract.TYPE_AUTO;
				lastMessage = null;
				watches.clear();
				owners.clear();
				schemaCache.clear();
			}
		}
	}

	private boolean beginOperation(long token, long operationEpoch, ClassLoader loader) {
		synchronized (stateLock) {
			if (activeToken != token || activeLoader != loader) {
				if (activeToken != 0L && activeToken != token) {
					return false;
				}
				activateRuntimeLocked(token, loader);
			}
		}
		advanceControlEpoch(operationEpoch);
		return isOperationActive(token, operationEpoch);
	}

	private void advanceControlEpoch(long requestedEpoch) {
		if (requestedEpoch < 0L) return;
		while (true) {
			long current = controlEpoch.get();
			if (current >= requestedEpoch || controlEpoch.compareAndSet(current, requestedEpoch)) {
				return;
			}
		}
	}

	private void activateRuntimeLocked(long token, ClassLoader loader) {
		if (activeToken == token && activeLoader == loader) return;
		activeToken = token;
		activeLoader = loader;
		committed = null;
		searchHistory.clear();
		baselineCount = 0L;
		searchStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
		searchMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
		requestedType = MemoryEngineContract.TYPE_AUTO;
		lastMessage = null;
		watches.clear();
		owners.clear();
		schemaCache.clear();
		nextOwnerHandle = 1L;
		nextRevision = 1L;
	}

	private boolean isOperationActive(long token, long operationEpoch) {
		return token != 0L && activeToken == token && controlEpoch.get() == operationEpoch
				&& MemoryDiscoveryBridge.isActive(token);
	}

	private boolean isCurrentLocked(long token) {
		return token != 0L && activeToken == token && MemoryDiscoveryBridge.isActive(token);
	}

	private ManagedOperationResult commitSearch(long token, long operationEpoch,
	                                           long expectedRevision, Revision revision,
	                                           @Nullable String diagnostic, int type, int mode,
	                                           boolean baselineCapture) {
		synchronized (stateLock) {
			if (!isOperationActive(token, operationEpoch)) return failureLocked(
					MemoryEngineContract.RESULT_CANCELLED,
					"Managed search was cancelled before commit");
			long currentRevision = committed == null ? 0L : committed.id;
			if (!revisionCanCommit(expectedRevision, currentRevision)) {
				return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed search revision changed before refine could commit");
			}
			if (expectedRevision > 0L && committed != null) {
				searchHistory.addLast(new SearchState(committed, searchStage, searchMode,
						requestedType, baselineCount));
				while (searchHistory.size() > MAX_SEARCH_HISTORY) searchHistory.removeFirst();
			} else if (expectedRevision == 0L) {
				// A successful New Search starts a new lineage. Failed New Search never reaches here.
				searchHistory.clear();
			}
			committed = revision;
			baselineCount = baselineCapture ? revision.count
					: expectedRevision > 0L ? baselineCount : 0L;
			searchStage = baselineCapture ? MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE
					: MemoryEngineContract.SEARCH_SESSION_CANDIDATES;
			searchMode = mode;
			requestedType = type;
			lastMessage = diagnostic;
			cleanupOwnersLocked();
			return successLocked(diagnostic);
		}
	}

	static boolean revisionCanCommit(long expectedRevision, long currentRevision) {
		return expectedRevision <= 0L || expectedRevision == currentRevision;
	}

	static boolean writeConfirmed(long readbackBits, long requestedBits) {
		return readbackBits == requestedBits;
	}

	private long allocateRevisionId() {
		synchronized (stateLock) {
			if (nextRevision == Long.MAX_VALUE) return 0L;
			return nextRevision++;
		}
	}

	private long visibleResultCountLocked() {
		return committed == null || (searchStage != MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE
				&& searchStage != MemoryEngineContract.SEARCH_SESSION_CANDIDATES)
				? 0L : committed.count;
	}

	private long baselineCountLocked() {
		return baselineCount;
	}

	private long retainedSearchStorageBytes() {
		synchronized (stateLock) {
			return retainedSearchStorageBytesLocked();
		}
	}

	private long retainedSearchStorageBytesLocked() {
		long total = committed == null ? 0L : committed.storageBytes();
		for (SearchState state : searchHistory) {
			total = checkedAdd(total, state.revision.storageBytes());
		}
		return total;
	}

	private ManagedOperationResult failure(long token, int code, @Nullable String message) {
		synchronized (stateLock) {
			cleanupOwnersLocked();
			return resultLocked(code, message, 0, 0, 0, 0);
		}
	}

	private ManagedOperationResult failureLocked(int code, @Nullable String message) {
		cleanupOwnersLocked();
		return resultLocked(code, message, 0, 0, 0, 0);
	}

	private ManagedOperationResult success(long token, @Nullable String message) {
		synchronized (stateLock) {
			return successLocked(message);
		}
	}

	private ManagedOperationResult successLocked(@Nullable String message) {
		return resultLocked(MemoryEngineContract.RESULT_OK, message, 0, 0, 0, 0);
	}

	private ManagedOperationResult result(long token, int code, @Nullable String message,
	                                      int attempted, int written, int skipped, int unconfirmed) {
		return result(token, code, message, attempted, written, skipped, unconfirmed, 0);
	}

	private ManagedOperationResult result(long token, int code, @Nullable String message,
	                                      int attempted, int written, int skipped, int unconfirmed,
	                                      int notAttempted) {
		synchronized (stateLock) {
			return resultLocked(code, message, attempted, written, skipped, unconfirmed,
					notAttempted);
		}
	}

	private ManagedOperationResult resultLocked(int code, @Nullable String message, int attempted,
	                                           int written, int skipped, int unconfirmed) {
		return resultLocked(code, message, attempted, written, skipped, unconfirmed, 0);
	}

	private ManagedOperationResult resultLocked(int code, @Nullable String message, int attempted,
	                                           int written, int skipped, int unconfirmed,
	                                           int notAttempted) {
		long revision = committed == null ? 0L : committed.id;
		long count = visibleResultCountLocked();
		return new ManagedOperationResult(code, revision, count, baselineCount, message,
				attempted, written, skipped, unconfirmed, notAttempted, skipped, 0,
				watches.count, watches.freezeCount());
	}

	private static String managedFailure(MemoryDiscoveryBridge.Snapshot snapshot, String fallback) {
		return snapshot.failure() == null || snapshot.failure().isBlank()
				? fallback : fallback + ": " + snapshot.failure();
	}

	private ResolvedBatch resolveBatch(long token, long expectedRevision, long[] ids,
	                                  boolean allowWatchOnly) {
		OwnerBucket[] resolvedOwners = new OwnerBucket[ids.length];
		int[] slots = new int[ids.length];
		Object[] strongOwners = new Object[ids.length];
		boolean[] watch = new boolean[ids.length];
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return ResolvedBatch.error(
					failureLocked(MemoryEngineContract.RESULT_TARGET_LOST,
							"MIDlet runtime changed or ended"));
			if (searchStage == MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE
					&& expectedRevision > 0L && committed != null
					&& committed.id == expectedRevision && !allowWatchOnly) {
				return ResolvedBatch.error(failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"Unknown baseline result rows are not editable until refinement"));
			}
			for (int index = 0; index < ids.length; index++) {
				long id = ids[index];
				if (!ManagedJavaMemoryIds.hasValidNamespace(id)) return ResolvedBatch.error(
						failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
								"The request contains a non-managed or malformed logical id"));
				int watchIndex = watches.indexOf(id);
				boolean memberOfWatch = watchIndex >= 0;
				boolean memberOfRevision = expectedRevision > 0L && committed != null
						&& committed.id == expectedRevision
						&& committed.contains(ManagedJavaMemoryIds.ownerHandle(id),
								ManagedJavaMemoryIds.kind(id), ManagedJavaMemoryIds.slot(id));
				if (!memberOfWatch && !memberOfRevision) return ResolvedBatch.error(
						failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
								"The logical id is not a member of the committed managed revision"));
				if (!allowWatchOnly && memberOfWatch && !memberOfRevision) return ResolvedBatch.error(
						failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
								"The logical id is not a current search result"));
				OwnerBucket owner = owners.get(ManagedJavaMemoryIds.ownerHandle(id));
				if (owner == null || owner.kind != ManagedJavaMemoryIds.kind(id)) return ResolvedBatch.error(
						failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
								"The logical id owner is no longer registered"));
				resolvedOwners[index] = owner;
				slots[index] = ManagedJavaMemoryIds.slot(id);
				watch[index] = memberOfWatch;
			}
		}
		for (int index = 0; index < ids.length; index++) {
			strongOwners[index] = resolvedOwners[index].strongOwner();
			if (resolvedOwners[index].kind != KIND_STATIC_FIELD && strongOwners[index] == null) {
				// A collected owner is a valid LOST identity; callers decide whether that is a
				// successful refresh or a failed mutation. Never rebind it.
			}
		}
		return new ResolvedBatch(ids, ids.length, resolvedOwners, slots, strongOwners, watch, null);
	}

	/**
	 * Revalidates a resolved batch immediately before a mutation. Reflection reads intentionally
	 * happen outside stateLock, so the owner identity, selected revision, and cancellation epoch
	 * are checked again at the commit boundary instead of trusting an earlier lookup.
	 */
	@Nullable
	private ManagedOperationResult revalidateBatch(long token, long expectedRevision,
	                                               ResolvedBatch batch, boolean allowWatchOnly,
	                                               long operationEpoch) {
		synchronized (stateLock) {
			return revalidateBatchLocked(token, expectedRevision, batch, allowWatchOnly,
					operationEpoch);
		}
	}

	@Nullable
	private ManagedOperationResult revalidateBatchLocked(long token, long expectedRevision,
	                                                     ResolvedBatch batch,
	                                                     boolean allowWatchOnly,
	                                                     long operationEpoch) {
		if (!isCurrentLocked(token)) return failureLocked(
				MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet runtime changed or ended");
		if (!isOperationActive(token, operationEpoch)) return failureLocked(
				MemoryEngineContract.RESULT_CANCELLED,
				"Managed mutation was cancelled before its commit boundary");
		for (int index = 0; index < batch.count; index++) {
			long id = batch.ids[index];
			int watchIndex = watches.indexOf(id);
			boolean memberOfWatch = watchIndex >= 0;
			boolean memberOfRevision = expectedRevision > 0L && committed != null
					&& committed.id == expectedRevision
					&& committed.contains(ManagedJavaMemoryIds.ownerHandle(id),
							ManagedJavaMemoryIds.kind(id), ManagedJavaMemoryIds.slot(id));
			if (!memberOfWatch && !memberOfRevision) return failureLocked(
					MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
					"The logical id is no longer a member of the selected managed state");
			if (!allowWatchOnly && memberOfWatch && !memberOfRevision) return failureLocked(
					MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
					"The logical id is no longer a current search result");
			OwnerBucket owner = owners.get(ManagedJavaMemoryIds.ownerHandle(id));
			if (owner != batch.owners[index] || owner.kind != ManagedJavaMemoryIds.kind(id)
					|| ManagedJavaMemoryIds.slot(id) != batch.slots[index]) {
				return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed owner changed before the mutation could commit");
			}
			if (owner.kind != KIND_STATIC_FIELD && owner.strongOwner() != batch.strongOwners[index]) {
				return failureLocked(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						"The managed owner was collected or rebound before the mutation could commit");
			}
		}
		return null;
	}

	private long[] uniqueIds(long[] ids) {
		long[] unique = new long[ids.length];
		int count = 0;
		for (long id : ids) {
			boolean exists = false;
			for (int index = 0; index < count; index++) {
				if (unique[index] == id) {
					exists = true;
					break;
				}
			}
			if (!exists) unique[count++] = id;
		}
		return Arrays.copyOf(unique, count);
	}

	private static ResolvedBatch selectBatch(ResolvedBatch source, int[] indexes, int count) {
		long[] ids = new long[count];
		OwnerBucket[] owners = new OwnerBucket[count];
		int[] slots = new int[count];
		Object[] strongOwners = new Object[count];
		boolean[] watch = new boolean[count];
		for (int output = 0; output < count; output++) {
			int input = indexes[output];
			ids[output] = source.ids[input];
			owners[output] = source.owners[input];
			slots[output] = source.slots[input];
			strongOwners[output] = source.strongOwners[input];
			watch[output] = source.watch[input];
		}
		return new ResolvedBatch(ids, count, owners, slots, strongOwners, watch, null);
	}

	private boolean readTyped(OwnerBucket owner, int slot, @Nullable Object strongOwner,
	                         long[] output) {
		try {
			if (owner == null || slot < 0 || output == null || output.length == 0) return false;
			int type = valueTypeFor(owner, slot);
			if (!ManagedJavaValue.isSupportedType(type)) return false;
			if (owner.kind == KIND_ARRAY) {
				if (strongOwner == null || slot >= Array.getLength(strongOwner)) return false;
				output[0] = ManagedJavaValue.readArrayElement(strongOwner, slot, type);
				return true;
			}
			FieldSlot field = owner.schema.fieldAt(slot);
			if (field == null || field.valueType != type || field.finalField
					|| field.kindStatic != (owner.kind == KIND_STATIC_FIELD)
					|| (owner.kind == KIND_OBJECT_FIELD && strongOwner == null)) return false;
			output[0] = ManagedJavaValue.readField(field.field,
					owner.kind == KIND_STATIC_FIELD ? null : strongOwner, type);
			return true;
		} catch (IllegalAccessException | RuntimeException | LinkageError error) {
			return false;
		}
	}

	/** Inspector reads may include final primitive siblings; mutation still rejects final fields. */
	private boolean readInspectable(OwnerBucket owner, int slot, @Nullable Object strongOwner,
	                               long[] output) {
		try {
			if (owner == null || slot < 0 || output == null || output.length == 0) return false;
			int type = valueTypeFor(owner, slot);
			if (!ManagedJavaValue.isSupportedType(type)) return false;
			if (owner.kind == KIND_ARRAY) {
				if (strongOwner == null || slot >= Array.getLength(strongOwner)) return false;
				output[0] = ManagedJavaValue.readArrayElement(strongOwner, slot, type);
				return true;
			}
			FieldSlot field = owner.schema.fieldAt(slot);
			if (field == null || field.valueType != type
					|| field.kindStatic != (owner.kind == KIND_STATIC_FIELD)
					|| (owner.kind == KIND_STATIC_FIELD && field.declaringClass != owner.staticClass)
					|| (owner.kind == KIND_OBJECT_FIELD && strongOwner == null)) return false;
			output[0] = ManagedJavaValue.readField(field.field,
					owner.kind == KIND_STATIC_FIELD ? null : strongOwner, type);
			return true;
		} catch (IllegalAccessException | RuntimeException | LinkageError error) {
			return false;
		}
	}

	private static long[] baselineFor(@Nullable Revision revision, OwnerBucket owner, int slot,
	                                 long current) {
		if (revision != null) {
			for (BucketView bucket : revision.buckets) {
				if (bucket.owner != owner) continue;
				for (int index = 0; index < bucket.slots.length; index++) {
					if (bucket.slots[index] == slot) {
						return new long[]{bucket.initial[index], bucket.previous[index]};
					}
				}
			}
		}
		return new long[]{current, current};
	}

	private void writeTyped(OwnerBucket owner, int slot, @Nullable Object strongOwner, int type,
	                       long value) throws IllegalAccessException {
		if (!ManagedJavaValue.isSupportedType(type)) throw new IllegalAccessException("unsupported type");
		if (owner.kind == KIND_ARRAY) {
			if (strongOwner == null || slot < 0 || slot >= Array.getLength(strongOwner)) {
				throw new IllegalAccessException("primitive array owner is unavailable");
			}
			ManagedJavaValue.writeArrayElement(strongOwner, slot, type, value);
			return;
		}
		FieldSlot field = owner.schema.fieldAt(slot);
		if (field == null || field.valueType != type || field.finalField || field.kindStatic !=
				(owner.kind == KIND_STATIC_FIELD)) throw new IllegalAccessException("field is not editable");
		ManagedJavaValue.writeField(field.field,
				owner.kind == KIND_STATIC_FIELD ? null : strongOwner, type, value);
	}

	private String readValueText(OwnerBucket owner, int slot, @Nullable Object strongOwner, int type,
	                             int[] states, int output, long[] value) {
		if (readTyped(owner, slot, strongOwner, value)) {
			states[output] = MemoryEngineContract.CANDIDATE_STABLE;
			return ManagedJavaValue.format(type, value[0]);
		}
		states[output] = MemoryEngineContract.CANDIDATE_LOST;
		return "LOST";
	}

	private String labelFor(OwnerBucket owner, int slot) {
		if (owner == null) return "managed#LOST";
		if (owner.kind == KIND_ARRAY) return arrayTypeName(valueTypeFor(owner, slot)) + "[]#"
				+ owner.handle + "[" + slot + "]";
		FieldSlot field = owner.schema.fieldAt(slot);
		if (field == null) return "managed#" + owner.handle + "[" + slot + "]";
		if (owner.kind == KIND_STATIC_FIELD) return field.declaringClass.getName() + "."
				+ field.field.getName() + " [static]";
		return field.declaringClass.getName() + "#" + owner.handle + "." + field.field.getName();
	}

	private static int valueTypeFor(OwnerBucket owner, int slot) {
		if (owner == null) return MemoryEngineContract.TYPE_INT;
		if (owner.kind == KIND_ARRAY) return owner.arrayType;
		FieldSlot field = owner.schema == null ? null : owner.schema.fieldAt(slot);
		return field == null ? MemoryEngineContract.TYPE_AUTO : field.valueType;
	}

	private static String arrayTypeName(int type) {
		switch (type) {
			case MemoryEngineContract.TYPE_BYTE: return "byte";
			case MemoryEngineContract.TYPE_SHORT: return "short";
			case MemoryEngineContract.TYPE_CHAR: return "char";
			case MemoryEngineContract.TYPE_INT: return "int";
			case MemoryEngineContract.TYPE_LONG: return "long";
			case MemoryEngineContract.TYPE_FLOAT: return "float";
			case MemoryEngineContract.TYPE_DOUBLE: return "double";
			default: return "primitive";
		}
	}

	private void updateWatchPrevious(long id, long value) {
		synchronized (stateLock) {
			int index = watches.indexOf(id);
			if (index >= 0) watches.previous[index] = value;
		}
	}

	private void markFreezePausedIfNeeded(long id) {
		synchronized (stateLock) {
			int index = watches.indexOf(id);
			if (index >= 0 && watches.freeze[index]) watches.freezePaused[index] = true;
		}
	}

	private void markFreezePaused(long id) {
		markFreezePausedIfNeeded(id);
	}

	private Schema schemaFor(Class<?> type, ClassLoader loader) {
		synchronized (stateLock) {
			Schema cached = schemaCache.get(type);
			if (cached != null) return cached;
			Schema schema = Schema.build(type, loader);
			schemaCache.put(type, schema);
			return schema;
		}
	}

	private OwnerBucket ownerForObject(Object object, int kind, Schema schema,
	                                IdentityHashMap<Object, OwnerBucket> temporary) {
		OwnerBucket owner = temporary == null ? null : temporary.get(object);
		if (owner != null) return owner;
		synchronized (stateLock) {
			for (int index = 0; index < watches.count; index++) {
				long id = watches.ids[index];
				OwnerBucket watched = owners.get(ManagedJavaMemoryIds.ownerHandle(id));
				if (watched == null || watched.kind != kind) continue;
				if (kind == KIND_STATIC_FIELD) {
					if (watched.staticClass == object) {
						owner = watched;
						break;
					}
				} else if (watched.weakOwner.get() == object) {
					owner = watched;
					break;
				}
			}
			if (owner == null) {
				if (owners.size() >= limits.maxOwners || nextOwnerHandle > ManagedJavaMemoryIds.OWNER_MAX) {
					throw new ResourceLimitException("Managed owner storage exceeds the resource limit");
				}
				long handle = nextOwnerHandle++;
				owner = kind == KIND_STATIC_FIELD
						? new OwnerBucket(handle, kind, (Class<?>) object, schema)
						: new OwnerBucket(handle, kind, object, schema);
				owners.put(handle, owner);
			}
		}
		if (temporary != null) temporary.put(object, owner);
		return owner;
	}

	private void cleanupOwnersLocked() {
		HashSet<Long> retained = new HashSet<>();
		if (committed != null) {
			for (BucketView bucket : committed.buckets) retained.add(bucket.owner.handle);
		}
		for (SearchState state : searchHistory) {
			for (BucketView bucket : state.revision.buckets) retained.add(bucket.owner.handle);
		}
		for (int index = 0; index < watches.count; index++) {
			retained.add(ManagedJavaMemoryIds.ownerHandle(watches.ids[index]));
		}
		Iterator<Long> handles = owners.keySet().iterator();
		while (handles.hasNext()) {
			if (!retained.contains(handles.next())) handles.remove();
		}
	}

	static final class Limits {
		final int maxVisited;
		final int maxPending;
		final int maxOwners;
		final int maxClasses;
		final int maxCandidates;
		final int maxContainerItems;
		final int maxArrayElements;
		final long maxResultStorageBytes;

		Limits(int maxVisited, int maxPending, int maxOwners, int maxCandidates,
		       int maxContainerItems, int maxArrayElements) {
			this(maxVisited, maxPending, maxOwners, maxCandidates, maxContainerItems,
					maxArrayElements, DEFAULT_MAX_RESULT_STORAGE_BYTES);
		}

		Limits(int maxVisited, int maxPending, int maxOwners, int maxCandidates,
		       int maxContainerItems, int maxArrayElements, long maxResultStorageBytes) {
			this.maxVisited = maxVisited;
			this.maxPending = maxPending;
			this.maxOwners = maxOwners;
			this.maxClasses = DEFAULT_MAX_CLASSES;
			this.maxCandidates = maxCandidates;
			this.maxContainerItems = maxContainerItems;
			this.maxArrayElements = maxArrayElements;
			this.maxResultStorageBytes = Math.max(0L, maxResultStorageBytes);
		}

		static Limits defaults() {
			return new Limits(DEFAULT_MAX_VISITED, DEFAULT_MAX_PENDING, DEFAULT_MAX_OWNERS,
					DEFAULT_MAX_CANDIDATES, DEFAULT_MAX_CONTAINER_ITEMS, DEFAULT_MAX_ARRAY_ELEMENTS);
		}
	}

	static final class ManagedCapabilities {
		final boolean supported;
		final boolean writeSupported;
		final long controlEpoch;
		final long revision;
		final long resultCount;
		final long baselineCount;
		final int watchCount;
		final int freezeCount;
		final int historyDepth;
		final String message;

		ManagedCapabilities(boolean supported, boolean writeSupported, long controlEpoch,
		                    long revision, long resultCount, long baselineCount,
		                    int watchCount, int freezeCount, int historyDepth, String message) {
			this.supported = supported;
			this.writeSupported = writeSupported;
			this.controlEpoch = controlEpoch;
			this.revision = revision;
			this.resultCount = resultCount;
			this.baselineCount = baselineCount;
			this.watchCount = watchCount;
			this.freezeCount = freezeCount;
			this.historyDepth = historyDepth;
			this.message = message;
		}
	}

	static final class ManagedSession {
		final boolean supported;
		final int stage;
		final int mode;
		final int requestedType;
		final long revision;
		final long resultCount;
		final long baselineCount;
		final int watchCount;
		final int freezeCount;
		final int historyDepth;
		final String message;

		ManagedSession(boolean supported, int stage, int mode, int requestedType, long revision,
		              long resultCount, long baselineCount, int watchCount, int freezeCount,
		              int historyDepth, String message) {
			this.supported = supported;
			this.stage = stage;
			this.mode = mode;
			this.requestedType = requestedType;
			this.revision = revision;
			this.resultCount = resultCount;
			this.baselineCount = baselineCount;
			this.watchCount = watchCount;
			this.freezeCount = freezeCount;
			this.historyDepth = historyDepth;
			this.message = message;
		}
	}

	static final class ManagedOperationResult {
		final int code;
		final long revision;
		final long resultCount;
		final long baselineCount;
		final String message;
		final int attempted;
		final int written;
		final int skipped;
		final int unconfirmed;
		final int notAttempted;
		final int rejectedBeforeWrite;
		final int skippedByType;
		final int watchCount;
		final int freezeCount;

		ManagedOperationResult(int code, long revision, long resultCount, long baselineCount,
		                       String message,
		                       int attempted, int written, int skipped, int unconfirmed,
		                       int notAttempted, int rejectedBeforeWrite, int skippedByType,
		                       int watchCount, int freezeCount) {
			this.code = code;
			this.revision = revision;
			this.resultCount = resultCount;
			this.baselineCount = baselineCount;
			this.message = message;
			this.attempted = attempted;
			this.written = written;
			this.skipped = skipped;
			this.unconfirmed = unconfirmed;
			this.notAttempted = notAttempted;
			this.rejectedBeforeWrite = rejectedBeforeWrite;
			this.skippedByType = skippedByType;
			this.watchCount = watchCount;
			this.freezeCount = freezeCount;
		}
	}

	static final class ManagedPage {
		final long revision;
		final long[] ids;
		final String[] values;
		final String[] initialValues;
		final String[] previousValues;
		final String[] addresses;
		final String[] labels;
		final int[] aliasMasks;
		final int[] types;
		final int[] states;
		final int[] relocations;
		final int[] freezeModes;
		final boolean[] freezePaused;

		private ManagedPage(int count, long revision, boolean watch) {
			this.revision = revision;
			ids = new long[count];
			values = new String[count];
			addresses = new String[count];
			aliasMasks = new int[count];
			types = new int[count];
			states = new int[count];
			relocations = new int[count];
			initialValues = watch ? new String[count] : null;
			previousValues = watch ? new String[count] : null;
			labels = watch ? new String[count] : null;
			freezeModes = watch ? new int[count] : null;
			freezePaused = watch ? new boolean[count] : null;
		}

		static ManagedPage allocate(int count, long revision) {
			return new ManagedPage(count, revision, false);
		}

		static ManagedPage allocateWatch(int count, long revision) {
			return new ManagedPage(count, revision, true);
		}

		static ManagedPage empty(long revision) {
			return new ManagedPage(0, revision, false);
		}
	}

	static final class ManagedInspection {
		final int code;
		final long revision;
		final long[] ids;
		final String[] values;
		final String[] initialValues;
		final String[] previousValues;
		final int[] types;
		final int[] states;
		final int[] relativeOffsets;
		final long[] expectedBits;
		final String[] labels;
		final boolean[] editable;
		final String provenance;
		final String message;

		ManagedInspection(int code, long revision, long[] ids, String[] values,
		                String[] initialValues, String[] previousValues, int[] types, int[] states,
		                int[] relativeOffsets, long[] expectedBits, String[] labels,
		                boolean[] editable, String provenance, String message) {
			this.code = code;
			this.revision = revision;
			this.ids = ids;
			this.values = values;
			this.initialValues = initialValues;
			this.previousValues = previousValues;
			this.types = types;
			this.states = states;
			this.relativeOffsets = relativeOffsets;
			this.expectedBits = expectedBits;
			this.labels = labels;
			this.editable = editable;
			this.provenance = provenance;
			this.message = message;
		}

		static ManagedInspection failure(int code, @Nullable String message) {
			return new ManagedInspection(code, 0L, new long[0], new String[0], new String[0],
					new String[0], new int[0], new int[0], new int[0], new long[0], new String[0],
					new boolean[0], "", message);
		}
	}

	/** A parsed operation query. Auto is a selector over independent, exact primitive planes. */
	private static final class QueryPlan {
		final int selector;
		final int predicate;
		final boolean relative;
		final boolean[] valid = new boolean[LAST_SUPPORTED_TYPE + 1];
		final long[] first = new long[LAST_SUPPORTED_TYPE + 1];
		final long[] second = new long[LAST_SUPPORTED_TYPE + 1];

		private QueryPlan(int selector, int predicate, boolean relative) {
			this.selector = selector;
			this.predicate = predicate;
			this.relative = relative;
		}

		static QueryPlan unknown(int selector) {
			if (!MemoryEngineContract.isValueType(selector)) return null;
			QueryPlan plan = new QueryPlan(selector, MemoryEngineContract.PREDICATE_EQUAL, false);
			if (selector == MemoryEngineContract.TYPE_AUTO) {
				for (int type = FIRST_SUPPORTED_TYPE; type <= LAST_SUPPORTED_TYPE; type++) {
					plan.valid[type] = true;
				}
			} else {
				plan.valid[selector] = true;
			}
			return plan;
		}

		static QueryPlan fromKnownBits(int selector, int predicate, long first, long second) {
			if (!MemoryEngineContract.isValueType(selector)
					|| predicate < MemoryEngineContract.PREDICATE_EQUAL
					|| predicate > MemoryEngineContract.PREDICATE_BETWEEN) return null;
			QueryPlan plan = new QueryPlan(selector, predicate, false);
			for (int type = FIRST_SUPPORTED_TYPE; type <= LAST_SUPPORTED_TYPE; type++) {
				if (selector != MemoryEngineContract.TYPE_AUTO && selector != type) continue;
				if (ManagedJavaValue.validKnownQuery(type, predicate, first, second)) {
					plan.valid[type] = true;
					plan.first[type] = first;
					plan.second[type] = second;
				}
			}
			return hasValidType(plan) ? plan : null;
		}

		static QueryPlan fromKnownText(int selector, int predicate, @Nullable String firstText,
		                              @Nullable String secondText) {
			if (!MemoryEngineContract.isValueType(selector)
					|| predicate < MemoryEngineContract.PREDICATE_EQUAL
					|| predicate > MemoryEngineContract.PREDICATE_BETWEEN) return null;
			if (predicate != MemoryEngineContract.PREDICATE_BETWEEN
					&& !isBlank(secondText)) return null;
			QueryPlan plan = new QueryPlan(selector, predicate, false);
			long[] firstBits = new long[1];
			long[] secondBits = new long[1];
			for (int type = FIRST_SUPPORTED_TYPE; type <= LAST_SUPPORTED_TYPE; type++) {
				if (selector != MemoryEngineContract.TYPE_AUTO && selector != type) continue;
				if (!ManagedJavaValue.parse(firstText, type, firstBits)) continue;
				if (predicate == MemoryEngineContract.PREDICATE_BETWEEN
						&& !ManagedJavaValue.parse(secondText, type, secondBits)) continue;
				long first = firstBits[0];
				long second = predicate == MemoryEngineContract.PREDICATE_BETWEEN ? secondBits[0] : 0L;
				if (ManagedJavaValue.validKnownQuery(type, predicate, first, second)) {
					plan.valid[type] = true;
					plan.first[type] = first;
					plan.second[type] = second;
				}
			}
			return hasValidType(plan) ? plan : null;
		}

		static QueryPlan fromRelativeBits(int selector, int predicate, long first, long second) {
			if (!MemoryEngineContract.isValueType(selector)
					|| predicate < MemoryEngineContract.PREDICATE_CHANGED
					|| predicate > MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE) return null;
			QueryPlan plan = new QueryPlan(selector, predicate, true);
			for (int type = FIRST_SUPPORTED_TYPE; type <= LAST_SUPPORTED_TYPE; type++) {
				if (selector != MemoryEngineContract.TYPE_AUTO && selector != type) continue;
				if (ManagedJavaValue.validRelativeQuery(type, predicate, first, second)) {
					plan.valid[type] = true;
					plan.first[type] = first;
					plan.second[type] = second;
				}
			}
			return hasValidType(plan) ? plan : null;
		}

		static QueryPlan fromRelativeText(int selector, int predicate, @Nullable String firstText,
		                                 @Nullable String secondText) {
			if (!MemoryEngineContract.isValueType(selector)
					|| predicate < MemoryEngineContract.PREDICATE_CHANGED
					|| predicate > MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE) return null;
			boolean magnitude = predicate >= MemoryEngineContract.PREDICATE_INCREASED_BY;
			boolean range = predicate == MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE
					|| predicate == MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE;
			if (!magnitude && (!isBlank(firstText) || !isBlank(secondText))) return null;
			if (magnitude && (!range && !isBlank(secondText) || range && isBlank(secondText))) return null;
			QueryPlan plan = new QueryPlan(selector, predicate, true);
			long[] firstBits = new long[1];
			long[] secondBits = new long[1];
			for (int type = FIRST_SUPPORTED_TYPE; type <= LAST_SUPPORTED_TYPE; type++) {
				if (selector != MemoryEngineContract.TYPE_AUTO && selector != type) continue;
				long first = 0L;
				long second = 0L;
				if (magnitude && !ManagedJavaValue.parseMagnitude(firstText, type, firstBits)) continue;
				if (range && !ManagedJavaValue.parseMagnitude(secondText, type, secondBits)) continue;
				if (magnitude) first = firstBits[0];
				if (range) second = secondBits[0];
				if (ManagedJavaValue.validRelativeQuery(type, predicate, first, second)) {
					plan.valid[type] = true;
					plan.first[type] = first;
					plan.second[type] = second;
				}
			}
			return hasValidType(plan) ? plan : null;
		}

		boolean accepts(int type) {
			return type >= FIRST_SUPPORTED_TYPE && type <= LAST_SUPPORTED_TYPE && valid[type];
		}

		boolean matchesKnown(int type, long current) {
			return accepts(type) && ManagedJavaValue.matchesKnown(type, predicate, current,
					first[type], second[type]);
		}

		boolean matchesRelative(int type, int actualPredicate, long current, long reference) {
			return accepts(type) && ManagedJavaValue.matchesRelative(type, actualPredicate, current,
					reference, first[type], second[type]);
		}

		private static boolean hasValidType(QueryPlan plan) {
			for (int type = FIRST_SUPPORTED_TYPE; type <= LAST_SUPPORTED_TYPE; type++) {
				if (plan.valid[type]) return true;
			}
			return false;
		}

		private static boolean isBlank(@Nullable String value) {
			return value == null || value.trim().isEmpty();
		}
	}

	/** Parsed, concrete same-owner group query. */
	private static final class GroupPlan {
		final int type;
		final long[] values;

		private GroupPlan(int type, long[] values) {
			this.type = type;
			this.values = values;
		}

		static GroupPlan fromText(int type, @Nullable String[] texts) {
			if (!MemoryEngineContract.isCandidateType(type) || texts == null
					|| texts.length < 2 || texts.length > MemoryEngineContract.MAX_GROUP_VALUES) {
				return null;
			}
			long[] values = new long[texts.length];
			long[] parsed = new long[1];
			for (int index = 0; index < texts.length; index++) {
				if (texts[index] == null || texts[index].trim().isEmpty()
						|| !ManagedJavaValue.parse(texts[index], type, parsed)) return null;
				values[index] = parsed[0];
			}
			return new GroupPlan(type, values);
		}
	}

	/** Bounded greedy matcher; same-type equality makes first-unmatched deterministic. */
	private static final class GroupMatcher {
		final GroupPlan plan;
		final int[] slots;
		final long[] observed;
		final boolean[] matched;
		int matchedCount;

		GroupMatcher(GroupPlan plan) {
			this.plan = plan;
			slots = new int[plan.values.length];
			observed = new long[plan.values.length];
			matched = new boolean[plan.values.length];
		}

		void reset() {
			Arrays.fill(matched, false);
			matchedCount = 0;
		}

		void match(int slot, int type, long bits) {
			if (type != plan.type || complete()) return;
			for (int index = 0; index < plan.values.length; index++) {
				if (matched[index] || !ManagedJavaValue.matchesKnown(type,
						MemoryEngineContract.PREDICATE_EQUAL, bits, plan.values[index], 0L)) continue;
				matched[index] = true;
				slots[index] = slot;
				observed[index] = bits;
				matchedCount++;
				return;
			}
		}

		boolean complete() {
			return matchedCount == plan.values.length;
		}

		int[] sortedSlots() {
			int[] result = Arrays.copyOf(slots, slots.length);
			for (int left = 1; left < result.length; left++) {
				int slot = result[left];
				int right = left - 1;
				while (right >= 0 && result[right] > slot) {
					result[right + 1] = result[right--];
				}
				result[right + 1] = slot;
			}
			return result;
		}

		long[] sortedObserved() {
			int[] order = new int[slots.length];
			for (int index = 0; index < order.length; index++) order[index] = index;
			for (int left = 1; left < order.length; left++) {
				int value = order[left];
				int right = left - 1;
				while (right >= 0 && slots[order[right]] > slots[value]) order[right + 1] = order[right--];
				order[right + 1] = value;
			}
			long[] result = new long[observed.length];
			for (int index = 0; index < order.length; index++) result[index] = observed[order[index]];
			return result;
		}
	}

	private final class ScanContext {
		final long token;
		final long operationEpoch;
		final MemoryDiscoveryBridge.Snapshot snapshot;
		final QueryPlan plan;
		final GroupPlan groupPlan;
		final boolean captureAllValues;
		final ArrayDeque<Object> queue = new ArrayDeque<>();
		final IdentityHashMap<Object, Boolean> queued = new IdentityHashMap<>();
		final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
		final IdentityHashMap<Object, OwnerBucket> objectOwners = new IdentityHashMap<>();
		final RevisionBuilder revision;
		final GroupMatcher groupMatcher;
		final long[] value = new long[1];
		long transientStorageBytes;
		long visitCount;
		int skippedFields;
		int concurrentFailures;
		int stoppedContainers;
		ManagedOperationResult failure;

		ScanContext(long token, long operationEpoch, MemoryDiscoveryBridge.Snapshot snapshot,
		            QueryPlan plan, long retainedRevisionBytes) {
			this(token, operationEpoch, snapshot, plan, null, retainedRevisionBytes, false);
		}

		ScanContext(long token, long operationEpoch, MemoryDiscoveryBridge.Snapshot snapshot,
		            GroupPlan groupPlan, long retainedRevisionBytes) {
			this(token, operationEpoch, snapshot, null, groupPlan, retainedRevisionBytes, false);
		}

		ScanContext(long token, long operationEpoch, MemoryDiscoveryBridge.Snapshot snapshot,
		            QueryPlan plan, long retainedRevisionBytes, boolean captureAllValues) {
			this(token, operationEpoch, snapshot, plan, null, retainedRevisionBytes, captureAllValues);
		}

		ScanContext(long token, long operationEpoch, MemoryDiscoveryBridge.Snapshot snapshot,
		            QueryPlan plan, GroupPlan groupPlan, long retainedRevisionBytes,
		            boolean captureAllValues) {
			this.token = token;
			this.operationEpoch = operationEpoch;
			this.snapshot = snapshot;
			this.plan = plan;
			this.groupPlan = groupPlan;
			this.captureAllValues = captureAllValues;
			this.revision = new RevisionBuilder(retainedRevisionBytes);
			this.groupMatcher = groupPlan == null ? null : new GroupMatcher(groupPlan);
		}

		void enqueue(@Nullable Object value) {
			if (value == null || queued.containsKey(value) || visited.containsKey(value)) return;
			if (queue.size() >= limits.maxPending) {
				throw new ResourceLimitException("Managed traversal pending queue exceeds the resource limit");
			}
			reserveTraversalBytes(TRAVERSAL_ENTRY_BYTES + QUEUE_REFERENCE_BYTES);
			queued.put(value, Boolean.TRUE);
			queue.addLast(value);
		}

		boolean checkpoint() {
			if (!isOperationActive(token, operationEpoch) || visitCount > limits.maxVisited) return false;
			if (!revision.withinStorageBudget(limits, transientStorageBytes)) {
				throw new ResourceLimitException("Managed traversal storage exceeds the resource limit");
			}
			return true;
		}

		void visit(Object value) {
			if (value == null || visited.containsKey(value)) return;
			reserveTraversalBytes(TRAVERSAL_ENTRY_BYTES);
			visited.put(value, Boolean.TRUE);
			if (++visitCount > limits.maxVisited) {
				throw new ResourceLimitException("Managed visited-owner limit exceeded");
			}
			Class<?> type = value.getClass();
			int arrayType = ManagedJavaValue.typeForClass(type.getComponentType());
			if (ManagedJavaValue.isSupportedType(arrayType)) {
				scanPrimitiveArray(value, arrayType);
				return;
			}
			if (value instanceof Object[]) {
				scanObjectArray((Object[]) value);
				return;
			}
			if (type == Stack.class || type == Vector.class || type == Hashtable.class) {
				scanExactContainer(value, type);
				return;
			}
			if (Vector.class.isAssignableFrom(type) || Hashtable.class.isAssignableFrom(type)) {
				stoppedContainers++;
				return;
			}
			if (type.getClassLoader() != snapshot.loader()) return;
			Schema schema = schemaFor(type, snapshot.loader());
			OwnerBucket owner = null;
			if (groupMatcher != null) groupMatcher.reset();
			for (FieldSlot field : schema.fields) {
				if (field.kindStatic) continue;
				try {
					if (!isOperationActive(token, operationEpoch)) {
						throw new OperationCancelledException();
					}
					if (field.primitiveField) {
						long current = ManagedJavaValue.readField(field.field, value, field.valueType);
						if (groupMatcher != null) {
							if (!field.finalField) groupMatcher.match(field.slot, field.valueType, current);
						} else if (plan.accepts(field.valueType) && !field.finalField
								&& (captureAllValues || plan.matchesKnown(field.valueType,
										current))) {
							if (owner == null) owner = ownerForObject(value, KIND_OBJECT_FIELD,
									schema, objectOwners);
							if (!revision.add(owner, field.slot, current, current, limits,
									transientStorageBytes)) {
								throw new ResourceLimitException(
										"Managed candidate storage exceeds the resource limit");
							}
						}
						if (field.finalField) skippedFields++;
					} else if (field.referenceField) {
						enqueue(field.field.get(value));
					}
				} catch (ResourceLimitException limit) {
					throw limit;
				} catch (OperationCancelledException cancelled) {
					throw cancelled;
				} catch (IllegalAccessException | RuntimeException | LinkageError error) {
					concurrentFailures++;
				}
			}
			if (groupMatcher != null && groupMatcher.complete()) publishGroup(value, KIND_OBJECT_FIELD,
					schema, objectOwners);
		}

		boolean scanStaticClass(Class<?> type) {
			if (type == null || type.getClassLoader() != snapshot.loader()) return true;
			Schema schema = schemaFor(type, snapshot.loader());
			OwnerBucket owner = null;
			if (groupMatcher != null) groupMatcher.reset();
			for (FieldSlot field : schema.fields) {
				if (!field.kindStatic || field.declaringClass != type) continue;
				try {
					if (!isOperationActive(token, operationEpoch)) {
						throw new OperationCancelledException();
					}
					if (field.primitiveField) {
						long current = ManagedJavaValue.readField(field.field, null, field.valueType);
						if (groupMatcher != null) {
							if (!field.finalField) groupMatcher.match(field.slot, field.valueType, current);
						} else if (plan.accepts(field.valueType) && !field.finalField
								&& (captureAllValues || plan.matchesKnown(field.valueType,
										current))) {
							if (owner == null) owner = ownerForObject(type, KIND_STATIC_FIELD,
									schema, null);
							if (!revision.add(owner, field.slot, current, current, limits,
									transientStorageBytes)) {
								throw new ResourceLimitException(
										"Managed candidate storage exceeds the resource limit");
							}
						}
						if (field.finalField) skippedFields++;
					} else if (field.referenceField) {
						enqueue(field.field.get(null));
					}
				} catch (ResourceLimitException limit) {
					throw limit;
				} catch (OperationCancelledException cancelled) {
					throw cancelled;
				} catch (IllegalAccessException | RuntimeException | LinkageError error) {
					concurrentFailures++;
				}
			}
			if (groupMatcher != null && groupMatcher.complete()) publishGroup(type,
					KIND_STATIC_FIELD, schema, null);
			return true;
		}

		void scanPrimitiveArray(Object array, int arrayType) {
			if (groupMatcher == null && !plan.accepts(arrayType)) return;
			int length = Array.getLength(array);
			if (length > limits.maxArrayElements) {
				throw new ResourceLimitException("Managed primitive array traversal exceeds the resource limit");
			}
			OwnerBucket owner = null;
			if (groupMatcher != null) groupMatcher.reset();
			for (int index = 0; index < length; index++) {
				if ((index & (CHECK_INTERVAL - 1)) == 0 && !checkpoint()) {
					throw new OperationCancelledException();
				}
				value[0] = ManagedJavaValue.readArrayElement(array, index, arrayType);
				if (groupMatcher != null) {
					groupMatcher.match(index, arrayType, value[0]);
					if (groupMatcher.complete()) break;
				} else if (captureAllValues || plan.matchesKnown(arrayType, value[0])) {
					if (owner == null) owner = ownerForObject(array, KIND_ARRAY, null, objectOwners);
					if (!revision.add(owner, index, value[0], value[0], limits,
							transientStorageBytes)) {
						throw new ResourceLimitException(
								"Managed candidate storage exceeds the resource limit");
					}
				}
			}
			if (groupMatcher != null && groupMatcher.complete()) publishGroup(array,
					KIND_ARRAY, null, objectOwners);
		}

		private void publishGroup(Object ownerObject, int kind, @Nullable Schema schema,
		                         @Nullable IdentityHashMap<Object, OwnerBucket> temporary) {
			OwnerBucket owner = ownerForObject(ownerObject, kind, schema, temporary);
			int[] slots = groupMatcher.sortedSlots();
			long[] observed = groupMatcher.sortedObserved();
			for (int index = 0; index < slots.length; index++) {
				if (!revision.add(owner, slots[index], observed[index], observed[index], limits,
						transientStorageBytes)) {
					throw new ResourceLimitException("Managed Group result storage exceeds the resource limit");
				}
			}
		}

		void scanObjectArray(Object[] array) {
			if (array.length > limits.maxPending) {
				throw new ResourceLimitException("Managed Object[] traversal exceeds the pending limit");
			}
			for (int index = 0; index < array.length; index++) {
				if ((index & (CHECK_INTERVAL - 1)) == 0 && !isOperationActive(token, operationEpoch)) {
					throw new OperationCancelledException();
				}
				enqueue(array[index]);
			}
		}

		void scanExactContainer(Object value, Class<?> type) {
			Object[] contents;
			long snapshotBytes;
			synchronized (value) {
				if (type == Hashtable.class) {
					Hashtable<?, ?> table = (Hashtable<?, ?>) value;
					int size = table.size();
					if (size > limits.maxContainerItems || size > Integer.MAX_VALUE / 2) {
						throw new ResourceLimitException("Managed Hashtable snapshot exceeds the resource limit");
					}
					snapshotBytes = checkedMultiply((long) size * 2L, SNAPSHOT_REFERENCE_BYTES);
					reserveTraversalBytes(snapshotBytes);
					contents = new Object[size * 2];
					Enumeration<?> keys = table.keys();
					int index = 0;
					while (keys.hasMoreElements() && index < size) contents[index++] = keys.nextElement();
					Enumeration<?> values = table.elements();
					index = size;
					while (values.hasMoreElements() && index < contents.length) contents[index++] = values.nextElement();
				} else {
					Vector<?> vector = (Vector<?>) value;
					int size = vector.size();
					if (size > limits.maxContainerItems) {
						throw new ResourceLimitException("Managed Vector snapshot exceeds the resource limit");
					}
					snapshotBytes = checkedMultiply(size, SNAPSHOT_REFERENCE_BYTES);
					reserveTraversalBytes(snapshotBytes);
					contents = new Object[size];
					for (int index = 0; index < size; index++) contents[index] = vector.elementAt(index);
				}
			}
			try {
				for (int index = 0; index < contents.length; index++) {
					if ((index & (CHECK_INTERVAL - 1)) == 0
							&& !isOperationActive(token, operationEpoch)) {
						throw new OperationCancelledException();
					}
					enqueue(contents[index]);
				}
			} finally {
				releaseTraversalBytes(snapshotBytes);
			}
		}

		void reserveTraversalBytes(long bytes) {
			if (bytes < 0L) throw new ResourceLimitException("Managed traversal storage overflowed");
			long next = checkedAdd(transientStorageBytes, bytes);
			if (!revision.withinStorageBudget(limits, next)) {
				throw new ResourceLimitException("Managed traversal storage exceeds the resource limit");
			}
			transientStorageBytes = next;
		}

		void releaseTraversalBytes(long bytes) {
			transientStorageBytes = Math.max(0L, transientStorageBytes - Math.max(0L, bytes));
		}

		ManagedOperationResult failure() {
			return failure == null ? ManagedJavaMemoryEngine.this.failure(token,
					MemoryEngineContract.RESULT_CANCELLED, "Managed scan was cancelled") : failure;
		}

		String diagnosticMessage() {
			if (skippedFields == 0 && concurrentFailures == 0 && stoppedContainers == 0) return null;
			return "Managed primitive scan completed with skippedFields=" + skippedFields
					+ ", concurrentAccessFailures=" + concurrentFailures
					+ ", stoppedContainers=" + stoppedContainers;
		}
	}

	private static final class RevisionBuilder {
		final ArrayList<OwnerBucketBuilder> builders = new ArrayList<>();
		final Map<Long, OwnerBucketBuilder> byOwner = new HashMap<>();
		final long retainedRevisionBytes;
		long candidateCount;

		RevisionBuilder() {
			this(0L);
		}

		RevisionBuilder(long retainedRevisionBytes) {
			this.retainedRevisionBytes = Math.max(0L, retainedRevisionBytes);
		}

		OwnerBucketBuilder builderFor(OwnerBucket owner, Limits limits, long extraBytes) {
			OwnerBucketBuilder builder = byOwner.get(owner.handle);
			if (builder == null) {
				int initialCapacity = initialCapacity(limits.maxCandidates);
				long newStorage = OwnerBucketBuilder.storageFor(initialCapacity);
				if (!canAddStorage(limits, extraBytes, newStorage)) return null;
				builder = new OwnerBucketBuilder(owner, initialCapacity);
				byOwner.put(owner.handle, builder);
				builders.add(builder);
			}
			return builder;
		}

		boolean add(OwnerBucket owner, int slot, long initialValue, long previousValue,
		            Limits limits) {
			return add(owner, slot, initialValue, previousValue, limits, 0L);
		}

		boolean add(OwnerBucket owner, int slot, long initialValue, long previousValue,
		            Limits limits, long extraBytes) {
			if (candidateCount >= limits.maxCandidates) return false;
			OwnerBucketBuilder builder = byOwner.get(owner.handle);
			if (builder == null) builder = builderFor(owner, limits, extraBytes);
			if (builder == null) return false;
			long growth = builder.additionalStorageForNext(limits.maxCandidates);
			if (!canAddStorage(limits, extraBytes, growth)) return false;
			if (!builder.add(slot, initialValue, previousValue, limits)) return false;
			candidateCount++;
			return true;
		}

		boolean withinStorageBudget(Limits limits, long extraBytes) {
			return canAddStorage(limits, extraBytes, 0L);
		}

		long storageBytes() {
			long total = retainedRevisionBytes;
			for (OwnerBucketBuilder builder : builders) {
				total = checkedAdd(total, builder.storageBytes());
			}
			return total;
		}

		Revision finish(long id, Limits limits, long extraBytes) {
			if (id == 0L) return null;
			long finishedStorageBytes = 0L;
			int nonEmptyBuckets = 0;
			for (OwnerBucketBuilder builder : builders) {
				if (builder.size == 0) continue;
				nonEmptyBuckets++;
				finishedStorageBytes = checkedAdd(finishedStorageBytes,
						checkedAdd(checkedMultiply(builder.size, RESULT_SLOT_BYTES),
								FINISHED_BUCKET_BYTES));
			}
			if (!canAddStorage(limits, extraBytes, finishedStorageBytes)) {
				throw new ResourceLimitException("Managed result storage exceeds the byte budget");
			}
			ArrayList<BucketView> views = new ArrayList<>(builders.size());
			long count = 0L;
			for (OwnerBucketBuilder builder : builders) {
				if (builder.size == 0) continue;
				int[] slots = builder.slots.toArray(builder.size);
				long[] initial = builder.initial.toArray(builder.size);
				long[] previous = builder.previous.toArray(builder.size);
				views.add(new BucketView(builder.owner, slots, initial, previous));
				count += slots.length;
			}
			if (nonEmptyBuckets == 0) finishedStorageBytes = 0L;
			return new Revision(id, views.toArray(new BucketView[0]), count,
					finishedStorageBytes);
		}

		private boolean canAddStorage(Limits limits, long extraBytes, long additionalBytes) {
			if (extraBytes < 0L || additionalBytes < 0L) return false;
			long required = checkedAdd(storageBytes(), checkedAdd(extraBytes, additionalBytes));
			return required <= limits.maxResultStorageBytes;
		}
	}

	private static final class OwnerBucketBuilder {
		final OwnerBucket owner;
		int size;
		final IntBuffer slots;
		final LongBuffer initial;
		final LongBuffer previous;

		OwnerBucketBuilder(OwnerBucket owner, int initialCapacity) {
			this.owner = owner;
			this.slots = new IntBuffer(initialCapacity);
			this.initial = new LongBuffer(initialCapacity);
			this.previous = new LongBuffer(initialCapacity);
		}

		boolean add(int slot, long initialValue, long previousValue, Limits limits) {
			if (size >= limits.maxCandidates) return false;
			if (size >= slots.capacity()) {
				int next = nextCapacity(slots.capacity(), limits.maxCandidates);
				if (next <= slots.capacity()) return false;
				slots.growTo(next);
				initial.growTo(next);
				previous.growTo(next);
			}
			slots.set(size, slot);
			initial.set(size, initialValue);
			previous.set(size, previousValue);
			size++;
			return true;
		}

		long additionalStorageForNext(int maxCandidates) {
			if (size >= maxCandidates) return 0L;
			if (size < slots.capacity()) return 0L;
			int next = nextCapacity(slots.capacity(), maxCandidates);
			if (next <= slots.capacity()) return Long.MAX_VALUE;
			return checkedMultiply((long) next - slots.capacity(), RESULT_SLOT_BYTES);
		}

		long storageBytes() {
			return checkedAdd(STAGED_OWNER_BYTES,
					checkedMultiply(slots.capacity(), RESULT_SLOT_BYTES));
		}

		static long storageFor(int capacity) {
			return checkedAdd(STAGED_OWNER_BYTES, checkedMultiply(capacity, RESULT_SLOT_BYTES));
		}
	}

	private static final class IntBuffer {
		private int[] values;

		IntBuffer(int capacity) {
			values = new int[Math.max(0, capacity)];
		}

		int capacity() {
			return values.length;
		}

		void growTo(int capacity) {
			if (capacity > values.length) values = Arrays.copyOf(values, capacity);
		}

		void set(int index, int value) {
			values[index] = value;
		}

		int[] toArray(int count) {
			return Arrays.copyOf(values, count);
		}
	}

	private static final class LongBuffer {
		private long[] values;

		LongBuffer(int capacity) {
			values = new long[Math.max(0, capacity)];
		}

		int capacity() {
			return values.length;
		}

		void growTo(int capacity) {
			if (capacity > values.length) values = Arrays.copyOf(values, capacity);
		}

		void set(int index, long value) {
			values[index] = value;
		}

		long[] toArray(int count) {
			return Arrays.copyOf(values, count);
		}
	}

	private static final class Revision {
		final long id;
		final BucketView[] buckets;
		final long count;
		final long storageBytes;

		Revision(long id, BucketView[] buckets, long count, long storageBytes) {
			this.id = id;
			this.buckets = buckets;
			this.count = count;
			this.storageBytes = storageBytes;
		}

		long storageBytes() {
			return storageBytes;
		}

		Revision withId(long publicationId) {
			return new Revision(publicationId, buckets, count, storageBytes);
		}

		boolean contains(long ownerHandle, int kind, int slot) {
			for (BucketView bucket : buckets) {
				if (bucket.owner.handle == ownerHandle && bucket.owner.kind == kind) {
					return bucket.contains(slot);
				}
			}
			return false;
		}
	}

	private static final class SearchState {
		final Revision revision;
		final int stage;
		final int mode;
		final int requestedType;
		final long baselineCount;

		SearchState(Revision revision, int stage, int mode, int requestedType,
		            long baselineCount) {
			this.revision = revision;
			this.stage = stage;
			this.mode = mode;
			this.requestedType = requestedType;
			this.baselineCount = baselineCount;
		}
	}

	private static final class BucketView {
		final OwnerBucket owner;
		final int[] slots;
		final long[] initial;
		final long[] previous;

		BucketView(OwnerBucket owner, int[] slots, long[] initial, long[] previous) {
			this.owner = owner;
			this.slots = slots;
			this.initial = initial;
			this.previous = previous;
		}

		boolean contains(int slot) {
			int low = 0;
			int high = slots.length - 1;
			while (low <= high) {
				int middle = (low + high) >>> 1;
				int candidate = slots[middle];
				if (candidate < slot) low = middle + 1;
				else if (candidate > slot) high = middle - 1;
				else return true;
			}
			// Reflection order is not specified, so field slots are not guaranteed sorted. The
			// common array path is sorted; fall back to a bounded linear check for schemas.
			for (int candidate : slots) if (candidate == slot) return true;
			return false;
		}
	}

	private static final class OwnerBucket {
		final long handle;
		final int kind;
		final Class<?> staticClass;
		final WeakReference<Object> weakOwner;
		final Schema schema;
		final int arrayType;

		OwnerBucket(long handle, int kind, Object owner, Schema schema) {
			this.handle = handle;
			this.kind = kind;
			this.staticClass = kind == KIND_STATIC_FIELD ? (Class<?>) owner : null;
			this.weakOwner = kind == KIND_STATIC_FIELD ? null : new WeakReference<>(owner);
			this.schema = schema;
			this.arrayType = kind == KIND_ARRAY
					? ManagedJavaValue.typeForClass(owner.getClass().getComponentType())
					: MemoryEngineContract.TYPE_AUTO;
		}

		Object strongOwner() {
			return kind == KIND_STATIC_FIELD ? staticClass : weakOwner.get();
		}
	}

	private static final class Schema {
		final FieldSlot[] fields;

		private Schema(FieldSlot[] fields) {
			this.fields = fields;
		}

		static Schema build(Class<?> actual, ClassLoader loader) {
			ArrayList<FieldSlot> fields = new ArrayList<>();
			Class<?> current = actual;
			while (current != null && current.getClassLoader() == loader) {
				Field[] declared;
				try {
					declared = current.getDeclaredFields();
				} catch (RuntimeException | LinkageError error) {
					break;
				}
				for (Field field : declared) {
					Class<?> fieldType = field.getType();
					int valueType = ManagedJavaValue.typeForClass(fieldType);
					boolean primitiveField = ManagedJavaValue.isSupportedType(valueType);
					boolean referenceField = !fieldType.isPrimitive();
					if (!primitiveField && !referenceField) continue;
					try {
						field.setAccessible(true);
					} catch (RuntimeException | LinkageError error) {
						continue;
					}
					fields.add(new FieldSlot(fields.size(), field, valueType, primitiveField, referenceField,
							Modifier.isStatic(field.getModifiers()),
							Modifier.isFinal(field.getModifiers())));
				}
				current = current.getSuperclass();
			}
			return new Schema(fields.toArray(new FieldSlot[0]));
		}

		FieldSlot fieldAt(int slot) {
			return slot >= 0 && slot < fields.length ? fields[slot] : null;
		}
	}

	private static final class FieldSlot {
		final int slot;
		final Field field;
		final Class<?> declaringClass;
		final int valueType;
		final boolean primitiveField;
		final boolean referenceField;
		final boolean kindStatic;
		final boolean finalField;

		FieldSlot(int slot, Field field, int valueType, boolean primitiveField, boolean referenceField,
		          boolean kindStatic, boolean finalField) {
			this.slot = slot;
			this.field = field;
			this.declaringClass = field.getDeclaringClass();
			this.valueType = valueType;
			this.primitiveField = primitiveField;
			this.referenceField = referenceField;
			this.kindStatic = kindStatic;
			this.finalField = finalField;
		}
	}

	private static final class WatchStore {
		final long[] ids = new long[MemoryEngineContract.MAX_WATCH_RECORDS];
		final OwnerBucket[] owners = new OwnerBucket[MemoryEngineContract.MAX_WATCH_RECORDS];
		final int[] slots = new int[MemoryEngineContract.MAX_WATCH_RECORDS];
		final long[] initial = new long[MemoryEngineContract.MAX_WATCH_RECORDS];
		final long[] previous = new long[MemoryEngineContract.MAX_WATCH_RECORDS];
		final String[] labels = new String[MemoryEngineContract.MAX_WATCH_RECORDS];
		final boolean[] freeze = new boolean[MemoryEngineContract.MAX_WATCH_RECORDS];
		final long[] freezeValues = new long[MemoryEngineContract.MAX_WATCH_RECORDS];
		final boolean[] freezePaused = new boolean[MemoryEngineContract.MAX_WATCH_RECORDS];
		int count;

		int indexOf(long id) {
			for (int index = 0; index < count; index++) if (ids[index] == id) return index;
			return -1;
		}

		void append(long id, OwnerBucket owner, int slot, long value, String label) {
			ids[count] = id;
			owners[count] = owner;
			slots[count] = slot;
			initial[count] = value;
			previous[count] = value;
			labels[count] = label;
			freeze[count] = false;
			freezePaused[count] = false;
			count++;
		}

		void remove(long id) {
			int index = indexOf(id);
			if (index < 0) return;
			int last = count - 1;
			if (index != last) {
				ids[index] = ids[last];
				owners[index] = owners[last];
				slots[index] = slots[last];
				initial[index] = initial[last];
				previous[index] = previous[last];
				labels[index] = labels[last];
				freeze[index] = freeze[last];
				freezeValues[index] = freezeValues[last];
				freezePaused[index] = freezePaused[last];
			}
			ids[last] = 0L;
			owners[last] = null;
			labels[last] = null;
			freeze[last] = false;
			freezePaused[last] = false;
			count = last;
		}

		int freezeCount() {
			int result = 0;
			for (int index = 0; index < count; index++) if (freeze[index]) result++;
			return result;
		}

		WatchSnapshot snapshot(Map<Long, OwnerBucket> ownerMap) {
			WatchSnapshot result = new WatchSnapshot(count);
			for (int index = 0; index < count; index++) {
				result.ids[index] = ids[index];
				result.owners[index] = ownerMap.get(ManagedJavaMemoryIds.ownerHandle(ids[index]));
				result.slots[index] = slots[index];
				result.initial[index] = initial[index];
				result.previous[index] = previous[index];
				result.labels[index] = labels[index];
				result.freeze[index] = freeze[index];
				result.freezeValues[index] = freezeValues[index];
				result.freezePaused[index] = freezePaused[index];
			}
			result.revision = 0L;
			return result;
		}

		WatchSnapshot frozenSnapshot(Map<Long, OwnerBucket> ownerMap) {
			int activeCount = 0;
			for (int index = 0; index < count; index++) {
				if (freeze[index] && !freezePaused[index]) activeCount++;
			}
			WatchSnapshot result = new WatchSnapshot(activeCount);
			int output = 0;
			for (int index = 0; index < count; index++) {
				if (!freeze[index] || freezePaused[index]) continue;
				result.ids[output] = ids[index];
				result.owners[output] = ownerMap.get(ManagedJavaMemoryIds.ownerHandle(ids[index]));
				result.slots[output] = slots[index];
				result.freezeValues[output] = freezeValues[index];
				output++;
			}
			return result;
		}

		void clear() {
			Arrays.fill(ids, 0L);
			Arrays.fill(owners, null);
			Arrays.fill(labels, null);
			Arrays.fill(freeze, false);
			Arrays.fill(freezePaused, false);
			count = 0;
		}
	}

	private static final class WatchSnapshot {
		final int count;
		final long[] ids;
		final OwnerBucket[] owners;
		final int[] slots;
		final long[] initial;
		final long[] previous;
		final String[] labels;
		final boolean[] freeze;
		final long[] freezeValues;
		final boolean[] freezePaused;
		long revision;

		WatchSnapshot(int count) {
			this.count = count;
			ids = new long[count];
			owners = new OwnerBucket[count];
			slots = new int[count];
			initial = new long[count];
			previous = new long[count];
			labels = new String[count];
			freeze = new boolean[count];
			freezeValues = new long[count];
			freezePaused = new boolean[count];
		}
	}

	private static final class ResolvedBatch {
		final long[] ids;
		final int count;
		final OwnerBucket[] owners;
		final int[] slots;
		final Object[] strongOwners;
		final boolean[] watch;
		final ManagedOperationResult error;

		ResolvedBatch(long[] ids, int count, OwnerBucket[] owners, int[] slots, Object[] strongOwners,
		             boolean[] watch, ManagedOperationResult error) {
			this.ids = ids;
			this.count = count;
			this.owners = owners;
			this.slots = slots;
			this.strongOwners = strongOwners;
			this.watch = watch;
			this.error = error;
		}

		static ResolvedBatch error(ManagedOperationResult error) {
			return new ResolvedBatch(null, 0, null, null, null, null, error);
		}
	}

	private static final class ResourceLimitException extends RuntimeException {
		ResourceLimitException(String message) {
			super(message);
		}
	}

	private static final class OperationCancelledException extends RuntimeException {
	}
}
