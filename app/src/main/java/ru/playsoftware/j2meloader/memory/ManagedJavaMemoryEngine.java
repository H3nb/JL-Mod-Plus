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
 * Target-process managed Java backend for the bounded Int vertical slice.
 *
 * <p>This class deliberately contains no native-memory calls. Candidate identity is a logical
 * owner handle plus a cached schema slot or int-array index. Only the owner is weakly retained;
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

	private final Object stateLock = new Object();
	private final AtomicLong controlEpoch = new AtomicLong();
	private final Limits limits;
	private final Map<Long, OwnerBucket> owners = new HashMap<>();
	private final Map<Class<?>, Schema> schemaCache = new HashMap<>();
	private final WatchStore watches = new WatchStore();
	private long activeToken;
	private ClassLoader activeLoader;
	private long nextOwnerHandle = 1L;
	private long nextRevision = 1L;
	private Revision committed;
	private int searchStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
	private int requestedType = MemoryEngineContract.TYPE_AUTO;
	private String lastMessage;

	ManagedJavaMemoryEngine() {
		this(Limits.defaults());
	}

	ManagedJavaMemoryEngine(Limits limits) {
		this.limits = limits == null ? Limits.defaults() : limits;
	}

	ManagedCapabilities capabilities(long token) {
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
		long revision;
		long count;
		int watchCount;
		int freezeCount;
		synchronized (stateLock) {
			if (snapshot.loader() != null && snapshot.token() == token) {
				activateRuntimeLocked(token, snapshot.loader());
			}
			revision = committed == null ? 0L : committed.id;
			count = committed == null ? 0L : committed.count;
			watchCount = watches.count;
			freezeCount = watches.freezeCount();
		}
		return new ManagedCapabilities(
				snapshot.isAvailable(),
				snapshot.isAvailable(),
				controlEpoch.get(),
				revision,
				count,
				watchCount,
				freezeCount,
				snapshot.failure());
	}

	ManagedSession session(long token) {
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
		synchronized (stateLock) {
			long revision = committed == null ? 0L : committed.id;
			long count = committed == null ? 0L : committed.count;
			return new ManagedSession(
					snapshot.isAvailable(),
					searchStage,
					requestedType,
					revision,
					count,
					watches.count,
					watches.freezeCount(),
					lastMessage != null ? lastMessage : snapshot.failure());
		}
	}

	ManagedOperationResult startExactInt(long token, int value, long operationEpoch) {
		MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(token);
		if (!snapshot.isAvailable()) {
			return failure(token, MemoryEngineContract.RESULT_UNSUPPORTED,
					managedFailure(snapshot, "Managed Java discovery is unavailable"));
		}
		if (!beginOperation(token, operationEpoch, snapshot.loader())) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed search was cancelled before it started");
		}

		ScanContext scan = new ScanContext(token, operationEpoch, snapshot, value);
		try {
			scan.enqueue(snapshot.root());
			Class<?>[] classes = snapshot.classes();
			if (classes.length > limits.maxClasses) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed class snapshot exceeds the resource limit");
			}
			for (Class<?> type : classes) {
				if (!scan.scanStaticClass(type)) return scan.failure();
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
			Revision revision = built.finish(allocateRevisionId());
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, revision, scan.diagnosticMessage(),
					MemoryEngineContract.TYPE_INT);
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

	ManagedOperationResult refineInt(long token, long expectedRevision, int predicate,
	                                int compareTarget, int value, long operationEpoch) {
		if (predicate != MemoryEngineContract.PREDICATE_EQUAL
				&& predicate != MemoryEngineContract.PREDICATE_CHANGED
				&& predicate != MemoryEngineContract.PREDICATE_UNCHANGED) {
			return failure(token, MemoryEngineContract.RESULT_UNSUPPORTED,
					"Managed refine supports Equal, Changed, and Unchanged only");
		}
		if (predicate != MemoryEngineContract.PREDICATE_EQUAL
				&& compareTarget != MemoryEngineContract.COMPARE_PREVIOUS
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

		RevisionBuilder next = new RevisionBuilder();
		long visited = 0L;
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
					int[] current = new int[1];
					if (!readInt(bucket.owner, bucket.slots[index], strongOwner, current)) {
						continue;
					}
					boolean matches;
					if (predicate == MemoryEngineContract.PREDICATE_EQUAL) {
						matches = current[0] == value;
					} else {
						int baseline = compareTarget == MemoryEngineContract.COMPARE_INITIAL
								? bucket.initial[index] : bucket.previous[index];
						boolean changed = current[0] != baseline;
						matches = predicate == MemoryEngineContract.PREDICATE_CHANGED
								? changed : !changed;
					}
					if (matches) {
						if (!next.add(bucket.owner, bucket.slots[index], bucket.initial[index], current[0], limits)) {
							throw new ResourceLimitException("Managed result storage exceeds the resource limit");
						}
					}
				}
			}
			Revision revision = next.finish(allocateRevisionId());
			if (revision == null) {
				return failure(token, MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"Managed revision identifier space is exhausted");
			}
			return commitSearch(token, operationEpoch, revision, null, MemoryEngineContract.TYPE_INT);
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
			if (!isCurrentLocked(token) || committed == null || committed.id != expectedRevision) {
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
		for (BucketView bucket : revision.buckets) {
			Object strongOwner = bucket.owner.strongOwner();
			for (int index = 0; index < bucket.slots.length; index++) {
				if (ordinal++ < start) continue;
				if (output >= outputCount) return page;
				int slot = bucket.slots[index];
				page.ids[output] = ManagedJavaMemoryIds.encode(bucket.owner.kind,
						bucket.owner.handle, slot);
				page.values[output] = readValueText(bucket.owner, slot, strongOwner, page.states, output);
				page.addresses[output] = labelFor(bucket.owner, slot);
				page.aliasMasks[output] = 1 << MemoryEngineContract.TYPE_INT;
				page.types[output] = MemoryEngineContract.TYPE_INT;
				page.relocations[output] = 0;
				output++;
			}
		}
		return page;
	}

	ManagedPage watchPage(long token) {
		WatchSnapshot snapshot;
		synchronized (stateLock) {
			if (!isCurrentLocked(token)) return ManagedPage.empty(0L);
			snapshot = watches.snapshot(owners);
		}
		ManagedPage page = ManagedPage.allocateWatch(snapshot.count, snapshot.revision);
		for (int index = 0; index < snapshot.count; index++) {
			OwnerBucket owner = snapshot.owners[index];
			Object strongOwner = owner == null ? null : owner.strongOwner();
			int slot = snapshot.slots[index];
			page.ids[index] = snapshot.ids[index];
			page.values[index] = readValueText(owner, slot, strongOwner, page.states, index);
			page.initialValues[index] = Integer.toString(snapshot.initial[index]);
			page.previousValues[index] = Integer.toString(snapshot.previous[index]);
			page.addresses[index] = owner == null ? "managed#LOST" : labelFor(owner, slot);
			page.types[index] = MemoryEngineContract.TYPE_INT;
			page.relocations[index] = 0;
			page.labels[index] = snapshot.labels[index] == null ? "" : snapshot.labels[index];
			page.freezeModes[index] = snapshot.freeze[index] ? MemoryEngineContract.FREEZE_LOCK : -1;
			page.freezePaused[index] = snapshot.freezePaused[index]
					|| page.states[index] == MemoryEngineContract.CANDIDATE_LOST;
			page.backends[index] = MemoryEngineContract.BACKEND_MANAGED;
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
		for (int index = 0; index < batch.count; index++) {
			int[] value = new int[1];
			if (!readInt(batch.owners[index], batch.slots[index], batch.strongOwners[index], value)) {
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
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_MULTI_WRITE) {
			return failure(token, MemoryEngineContract.RESULT_SAFETY_LIMIT,
					"Managed edits are limited to 32 rows");
		}
		ResolvedBatch batch = resolveBatch(token, expectedRevision, ids, allowWatchOnly);
		if (batch.error != null) return batch.error;
		ManagedOperationResult validation = revalidateBatch(token, expectedRevision, batch,
				allowWatchOnly, operationEpoch);
		if (validation != null) return validation;
		if (!isOperationActive(token, operationEpoch)) {
			return failure(token, MemoryEngineContract.RESULT_CANCELLED,
					"Managed edit was cancelled before writing");
		}
		int attempted = 0;
		int written = 0;
		int skipped = 0;
		int unconfirmed = 0;
		for (int index = 0; index < batch.count; index++) {
			if (!isOperationActive(token, operationEpoch)) break;
			Object owner = batch.strongOwners[index];
			if (batch.owners[index].kind != KIND_STATIC_FIELD && owner == null) {
				skipped++;
				continue;
			}
			attempted++;
			try {
				writeInt(batch.owners[index], batch.slots[index], owner, replacement);
				int[] readback = new int[1];
				if (readInt(batch.owners[index], batch.slots[index], owner, readback)
						&& readback[0] == replacement) {
					written++;
					if (batch.watch[index]) updateWatchPrevious(ids[index], readback[0]);
				} else {
					unconfirmed++;
				}
			} catch (IllegalAccessException | RuntimeException | LinkageError error) {
				skipped++;
			}
		}
		int code;
		if (written == ids.length) code = MemoryEngineContract.RESULT_OK;
		else if (written > 0) code = MemoryEngineContract.RESULT_PARTIAL_WRITE;
		else code = MemoryEngineContract.RESULT_IDENTITY_UNSAFE;
		String message = "Managed edit attempted " + attempted + ", wrote " + written
				+ ", skipped " + skipped + ", unconfirmed " + unconfirmed;
		return result(token, code, message, attempted, written, skipped, unconfirmed);
	}

	ManagedOperationResult addWatch(long token, long expectedRevision, @Nullable long[] ids,
	                               long operationEpoch) {
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_WATCH_RECORDS) {
			return failure(token, MemoryEngineContract.RESULT_SAFETY_LIMIT,
					"Managed Watch List requests are bounded to 128 rows");
		}
		long[] unique = uniqueIds(ids);
		ResolvedBatch batch = resolveBatch(token, expectedRevision, unique, false);
		if (batch.error != null) return batch.error;
		int additional = 0;
		synchronized (stateLock) {
			for (long id : unique) if (watches.indexOf(id) < 0) additional++;
			if (watches.count + additional > MemoryEngineContract.MAX_WATCH_RECORDS) {
				return failureLocked(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
						"The global Watch List limit is 128 rows");
			}
		}
		int[] currentValues = new int[unique.length];
		for (int index = 0; index < unique.length; index++) {
			int[] read = new int[1];
			if (!readInt(batch.owners[index], batch.slots[index], batch.strongOwners[index], read)) {
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
				false, operationEpoch);
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
		if (ids == null || ids.length == 0 || ids.length > MemoryEngineContract.MAX_FREEZE_RECORDS) {
			return failure(token, MemoryEngineContract.RESULT_SAFETY_LIMIT,
					"Managed Freeze Lock is limited to 32 rows");
		}
		long[] unique = uniqueIds(ids);
		ResolvedBatch batch = resolveBatch(token, expectedRevision, unique, allowWatchOnly);
		if (batch.error != null) return batch.error;
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
		int[] currentValues = new int[unique.length];
		for (int index = 0; index < unique.length; index++) {
			int[] read = new int[1];
			if (!readInt(batch.owners[index], batch.slots[index], batch.strongOwners[index], read)) {
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
				watches.freezeValues[watchIndex] = replacement;
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
				writeInt(owner, snapshot.slots[index], strongOwner, snapshot.freezeValues[index]);
				int[] readback = new int[1];
				if (readInt(owner, snapshot.slots[index], strongOwner, readback)
						&& readback[0] == snapshot.freezeValues[index]) {
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
		advanceControlEpoch(operationEpoch);
		synchronized (stateLock) {
			if (token == activeToken) {
				committed = null;
				searchStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
				requestedType = MemoryEngineContract.TYPE_AUTO;
				lastMessage = null;
				cleanupOwnersLocked();
			}
		}
	}

	void cancel(long token, long operationEpoch) {
		if (token != 0L) advanceControlEpoch(operationEpoch);
	}

	void runtimeClosed(long token) {
		controlEpoch.incrementAndGet();
		synchronized (stateLock) {
			if (token == 0L || activeToken == token) {
				activeToken = 0L;
				activeLoader = null;
				committed = null;
				searchStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
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
		searchStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
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

	private ManagedOperationResult commitSearch(long token, long operationEpoch, Revision revision,
	                                           @Nullable String diagnostic, int type) {
		synchronized (stateLock) {
			if (!isOperationActive(token, operationEpoch)) return failureLocked(
					MemoryEngineContract.RESULT_CANCELLED,
					"Managed search was cancelled before commit");
			committed = revision;
			searchStage = MemoryEngineContract.SEARCH_SESSION_CANDIDATES;
			requestedType = type;
			lastMessage = diagnostic;
			cleanupOwnersLocked();
			return successLocked(diagnostic);
		}
	}

	private long allocateRevisionId() {
		synchronized (stateLock) {
			if (nextRevision == Long.MAX_VALUE) return 0L;
			return nextRevision++;
		}
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
		synchronized (stateLock) {
			return resultLocked(code, message, attempted, written, skipped, unconfirmed);
		}
	}

	private ManagedOperationResult resultLocked(int code, @Nullable String message, int attempted,
	                                           int written, int skipped, int unconfirmed) {
		long revision = committed == null ? 0L : committed.id;
		long count = committed == null ? 0L : committed.count;
		return new ManagedOperationResult(code, revision, count, message, attempted, written,
				skipped, unconfirmed, watches.count, watches.freezeCount());
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

	private boolean readInt(OwnerBucket owner, int slot, @Nullable Object strongOwner, int[] output) {
		try {
			if (owner == null || slot < 0 || output == null || output.length == 0) return false;
			if (owner.kind == KIND_ARRAY) {
				if (!(strongOwner instanceof int[]) || slot >= ((int[]) strongOwner).length) return false;
				output[0] = ((int[]) strongOwner)[slot];
				return true;
			}
		FieldSlot field = owner.schema.fieldAt(slot);
		if (field == null || !field.intField || field.finalField || field.kindStatic !=
					(owner.kind == KIND_STATIC_FIELD) ||
					(owner.kind == KIND_OBJECT_FIELD && strongOwner == null)) return false;
			output[0] = field.field.getInt(owner.kind == KIND_STATIC_FIELD ? null : strongOwner);
			return true;
		} catch (IllegalAccessException | RuntimeException | LinkageError error) {
			return false;
		}
	}

	private void writeInt(OwnerBucket owner, int slot, @Nullable Object strongOwner, int value)
			throws IllegalAccessException {
		if (owner.kind == KIND_ARRAY) {
			if (!(strongOwner instanceof int[]) || slot < 0 || slot >= ((int[]) strongOwner).length) {
				throw new IllegalAccessException("int[] owner is unavailable");
			}
			((int[]) strongOwner)[slot] = value;
			return;
		}
		FieldSlot field = owner.schema.fieldAt(slot);
		if (field == null || !field.intField || field.finalField || field.kindStatic !=
				(owner.kind == KIND_STATIC_FIELD)) throw new IllegalAccessException("field is not editable");
		field.field.setInt(owner.kind == KIND_STATIC_FIELD ? null : strongOwner, value);
	}

	private String readValueText(OwnerBucket owner, int slot, @Nullable Object strongOwner,
	                             int[] states, int output) {
		int[] value = new int[1];
		if (readInt(owner, slot, strongOwner, value)) {
			states[output] = MemoryEngineContract.CANDIDATE_STABLE;
			return Integer.toString(value[0]);
		}
		states[output] = MemoryEngineContract.CANDIDATE_LOST;
		return "LOST";
	}

	private String labelFor(OwnerBucket owner, int slot) {
		if (owner == null) return "managed#LOST";
		if (owner.kind == KIND_ARRAY) return "int[]#" + owner.handle + "[" + slot + "]";
		FieldSlot field = owner.schema.fieldAt(slot);
		if (field == null) return "managed#" + owner.handle + "[" + slot + "]";
		if (owner.kind == KIND_STATIC_FIELD) return field.declaringClass.getName() + "."
				+ field.field.getName() + " [static]";
		return field.declaringClass.getName() + "#" + owner.handle + "." + field.field.getName();
	}

	private void updateWatchPrevious(long id, int value) {
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

		Limits(int maxVisited, int maxPending, int maxOwners, int maxCandidates,
		       int maxContainerItems, int maxArrayElements) {
			this.maxVisited = maxVisited;
			this.maxPending = maxPending;
			this.maxOwners = maxOwners;
			this.maxClasses = DEFAULT_MAX_CLASSES;
			this.maxCandidates = maxCandidates;
			this.maxContainerItems = maxContainerItems;
			this.maxArrayElements = maxArrayElements;
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
		final int watchCount;
		final int freezeCount;
		final String message;

		ManagedCapabilities(boolean supported, boolean writeSupported, long controlEpoch,
		                    long revision, long resultCount,
		                    int watchCount, int freezeCount, String message) {
			this.supported = supported;
			this.writeSupported = writeSupported;
			this.controlEpoch = controlEpoch;
			this.revision = revision;
			this.resultCount = resultCount;
			this.watchCount = watchCount;
			this.freezeCount = freezeCount;
			this.message = message;
		}
	}

	static final class ManagedSession {
		final boolean supported;
		final int stage;
		final int requestedType;
		final long revision;
		final long resultCount;
		final int watchCount;
		final int freezeCount;
		final String message;

		ManagedSession(boolean supported, int stage, int requestedType, long revision,
		              long resultCount, int watchCount, int freezeCount, String message) {
			this.supported = supported;
			this.stage = stage;
			this.requestedType = requestedType;
			this.revision = revision;
			this.resultCount = resultCount;
			this.watchCount = watchCount;
			this.freezeCount = freezeCount;
			this.message = message;
		}
	}

	static final class ManagedOperationResult {
		final int code;
		final long revision;
		final long resultCount;
		final String message;
		final int attempted;
		final int written;
		final int skipped;
		final int unconfirmed;
		final int watchCount;
		final int freezeCount;

		ManagedOperationResult(int code, long revision, long resultCount, String message,
		                       int attempted, int written, int skipped, int unconfirmed,
		                       int watchCount, int freezeCount) {
			this.code = code;
			this.revision = revision;
			this.resultCount = resultCount;
			this.message = message;
			this.attempted = attempted;
			this.written = written;
			this.skipped = skipped;
			this.unconfirmed = unconfirmed;
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
		final int[] backends;

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
			backends = watch ? new int[count] : null;
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

	private final class ScanContext {
		final long token;
		final long operationEpoch;
		final MemoryDiscoveryBridge.Snapshot snapshot;
		final int wantedValue;
		final ArrayDeque<Object> queue = new ArrayDeque<>();
		final IdentityHashMap<Object, Boolean> queued = new IdentityHashMap<>();
		final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
		final IdentityHashMap<Object, OwnerBucket> objectOwners = new IdentityHashMap<>();
		final RevisionBuilder revision = new RevisionBuilder();
		long visitCount;
		int skippedFields;
		int concurrentFailures;
		int stoppedContainers;
		ManagedOperationResult failure;

		ScanContext(long token, long operationEpoch, MemoryDiscoveryBridge.Snapshot snapshot,
		            int wantedValue) {
			this.token = token;
			this.operationEpoch = operationEpoch;
			this.snapshot = snapshot;
			this.wantedValue = wantedValue;
		}

		void enqueue(@Nullable Object value) {
			if (value == null || queued.containsKey(value) || visited.containsKey(value)) return;
			if (queue.size() >= limits.maxPending) {
				throw new ResourceLimitException("Managed traversal pending queue exceeds the resource limit");
			}
			queued.put(value, Boolean.TRUE);
			queue.addLast(value);
		}

		boolean checkpoint() {
			return isOperationActive(token, operationEpoch) && visitCount <= limits.maxVisited;
		}

		void visit(Object value) {
			if (value == null || visited.put(value, Boolean.TRUE) != null) return;
			if (++visitCount > limits.maxVisited) {
				throw new ResourceLimitException("Managed visited-owner limit exceeded");
			}
			Class<?> type = value.getClass();
			if (type == int[].class) {
				scanIntArray((int[]) value);
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
			OwnerBucket owner = ownerForObject(value, KIND_OBJECT_FIELD, schema, objectOwners);
			for (FieldSlot field : schema.fields) {
				if (field.kindStatic) continue;
				try {
					if (field.intField) {
						int current = field.field.getInt(value);
						if (!field.finalField && current == wantedValue
								&& !revision.add(owner, field.slot, current, current, limits)) {
							throw new ResourceLimitException("Managed candidate storage exceeds the resource limit");
						}
						if (field.finalField) skippedFields++;
					} else if (field.referenceField) {
						enqueue(field.field.get(value));
					}
				} catch (ResourceLimitException limit) {
					throw limit;
				} catch (IllegalAccessException | RuntimeException | LinkageError error) {
					concurrentFailures++;
				}
			}
		}

		boolean scanStaticClass(Class<?> type) {
			if (type == null || type.getClassLoader() != snapshot.loader()) return true;
			Schema schema = schemaFor(type, snapshot.loader());
			OwnerBucket owner = null;
			for (FieldSlot field : schema.fields) {
				if (!field.kindStatic || field.declaringClass != type) continue;
				if (owner == null) owner = ownerForObject(type, KIND_STATIC_FIELD, schema, null);
				try {
					if (field.intField) {
						int current = field.field.getInt(null);
						if (!field.finalField && current == wantedValue
								&& !revision.add(owner, field.slot, current, current, limits)) {
							throw new ResourceLimitException("Managed candidate storage exceeds the resource limit");
						}
						if (field.finalField) skippedFields++;
					} else if (field.referenceField) {
						enqueue(field.field.get(null));
					}
				} catch (ResourceLimitException limit) {
					throw limit;
				} catch (IllegalAccessException | RuntimeException | LinkageError error) {
					concurrentFailures++;
				}
			}
			return true;
		}

		void scanIntArray(int[] array) {
			if (array.length > limits.maxArrayElements) {
				throw new ResourceLimitException("Managed int[] traversal exceeds the resource limit");
			}
			OwnerBucket owner = ownerForObject(array, KIND_ARRAY, null, objectOwners);
			for (int index = 0; index < array.length; index++) {
				if ((index & (CHECK_INTERVAL - 1)) == 0 && !checkpoint()) {
					throw new OperationCancelledException();
				}
				if (array[index] == wantedValue
						&& !revision.add(owner, index, wantedValue, wantedValue, limits)) {
					throw new ResourceLimitException("Managed candidate storage exceeds the resource limit");
				}
			}
		}

		void scanObjectArray(Object[] array) {
			if (array.length > limits.maxPending) {
				throw new ResourceLimitException("Managed Object[] traversal exceeds the pending limit");
			}
			for (Object child : array) enqueue(child);
		}

		void scanExactContainer(Object value, Class<?> type) {
			Object[] contents;
			synchronized (value) {
				if (type == Hashtable.class) {
					Hashtable<?, ?> table = (Hashtable<?, ?>) value;
					int size = table.size();
					if (size > limits.maxContainerItems || size > Integer.MAX_VALUE / 2) {
						throw new ResourceLimitException("Managed Hashtable snapshot exceeds the resource limit");
					}
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
					contents = new Object[size];
					for (int index = 0; index < size; index++) contents[index] = vector.elementAt(index);
				}
			}
			for (Object child : contents) enqueue(child);
		}

		ManagedOperationResult failure() {
			return failure == null ? ManagedJavaMemoryEngine.this.failure(token,
					MemoryEngineContract.RESULT_CANCELLED, "Managed scan was cancelled") : failure;
		}

		String diagnosticMessage() {
			if (skippedFields == 0 && concurrentFailures == 0 && stoppedContainers == 0) return null;
			return "Managed Int scan completed with skippedFields=" + skippedFields
					+ ", concurrentAccessFailures=" + concurrentFailures
					+ ", stoppedContainers=" + stoppedContainers;
		}
	}

	private static final class RevisionBuilder {
		final ArrayList<OwnerBucketBuilder> builders = new ArrayList<>();
		final Map<Long, OwnerBucketBuilder> byOwner = new HashMap<>();
		long candidateCount;

		OwnerBucketBuilder builderFor(OwnerBucket owner, int maxCandidates) {
			OwnerBucketBuilder builder = byOwner.get(owner.handle);
			if (builder == null) {
				builder = new OwnerBucketBuilder(owner, maxCandidates);
				byOwner.put(owner.handle, builder);
				builders.add(builder);
			}
			return builder;
		}

		boolean add(OwnerBucket owner, int slot, int initialValue, int previousValue,
		            Limits limits) {
			if (candidateCount >= limits.maxCandidates) return false;
			OwnerBucketBuilder builder = builderFor(owner, limits.maxCandidates);
			if (!builder.add(slot, initialValue, previousValue, limits)) return false;
			candidateCount++;
			return true;
		}

		Revision finish(long id) {
			if (id == 0L) return null;
			ArrayList<BucketView> views = new ArrayList<>(builders.size());
			long count = 0L;
			for (OwnerBucketBuilder builder : builders) {
				if (builder.size == 0) continue;
				int[] slots = builder.slots.toArray();
				int[] initial = builder.initial.toArray();
				int[] previous = builder.previous.toArray();
				views.add(new BucketView(builder.owner, slots, initial, previous));
				count += slots.length;
			}
			return new Revision(id, views.toArray(new BucketView[0]), count);
		}
	}

	private static final class OwnerBucketBuilder {
		final OwnerBucket owner;
		int size;
		final IntBuffer slots;
		final IntBuffer initial;
		final IntBuffer previous;

		OwnerBucketBuilder(OwnerBucket owner, int maxCandidates) {
			this.owner = owner;
			this.slots = new IntBuffer(maxCandidates);
			this.initial = new IntBuffer(maxCandidates);
			this.previous = new IntBuffer(maxCandidates);
		}

		boolean add(int slot, int initialValue, int previousValue, Limits limits) {
			if (size >= limits.maxCandidates) return false;
			if (!slots.add(slot, limits.maxCandidates)
					|| !initial.add(initialValue, limits.maxCandidates)
					|| !previous.add(previousValue, limits.maxCandidates)) return false;
			size++;
			return true;
		}
	}

	private static final class IntBuffer {
		private int[] values;
		private int size;

		IntBuffer(int max) {
			values = new int[Math.min(16, Math.max(0, max))];
		}

		boolean add(int value, int max) {
			if (size >= max) return false;
			if (size == values.length) {
				long doubled = Math.max(1L, (long) values.length * 2L);
				int next = (int) Math.min((long) max, doubled);
				if (next <= values.length) return false;
				values = Arrays.copyOf(values, next);
			}
			values[size++] = value;
			return true;
		}

		int[] toArray() {
			return Arrays.copyOf(values, size);
		}
	}

	private static final class Revision {
		final long id;
		final BucketView[] buckets;
		final long count;

		Revision(long id, BucketView[] buckets, long count) {
			this.id = id;
			this.buckets = buckets;
			this.count = count;
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

	private static final class BucketView {
		final OwnerBucket owner;
		final int[] slots;
		final int[] initial;
		final int[] previous;

		BucketView(OwnerBucket owner, int[] slots, int[] initial, int[] previous) {
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

		OwnerBucket(long handle, int kind, Object owner, Schema schema) {
			this.handle = handle;
			this.kind = kind;
			this.staticClass = kind == KIND_STATIC_FIELD ? (Class<?>) owner : null;
			this.weakOwner = kind == KIND_STATIC_FIELD ? null : new WeakReference<>(owner);
			this.schema = schema;
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
					boolean intField = fieldType == int.class;
					boolean referenceField = !fieldType.isPrimitive();
					if (!intField && !referenceField) continue;
					try {
						field.setAccessible(true);
					} catch (RuntimeException | LinkageError error) {
						continue;
					}
					fields.add(new FieldSlot(fields.size(), field, intField, referenceField,
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
		final boolean intField;
		final boolean referenceField;
		final boolean kindStatic;
		final boolean finalField;

		FieldSlot(int slot, Field field, boolean intField, boolean referenceField,
		          boolean kindStatic, boolean finalField) {
			this.slot = slot;
			this.field = field;
			this.declaringClass = field.getDeclaringClass();
			this.intField = intField;
			this.referenceField = referenceField;
			this.kindStatic = kindStatic;
			this.finalField = finalField;
		}
	}

	private static final class WatchStore {
		final long[] ids = new long[MemoryEngineContract.MAX_WATCH_RECORDS];
		final OwnerBucket[] owners = new OwnerBucket[MemoryEngineContract.MAX_WATCH_RECORDS];
		final int[] slots = new int[MemoryEngineContract.MAX_WATCH_RECORDS];
		final int[] initial = new int[MemoryEngineContract.MAX_WATCH_RECORDS];
		final int[] previous = new int[MemoryEngineContract.MAX_WATCH_RECORDS];
		final String[] labels = new String[MemoryEngineContract.MAX_WATCH_RECORDS];
		final boolean[] freeze = new boolean[MemoryEngineContract.MAX_WATCH_RECORDS];
		final int[] freezeValues = new int[MemoryEngineContract.MAX_WATCH_RECORDS];
		final boolean[] freezePaused = new boolean[MemoryEngineContract.MAX_WATCH_RECORDS];
		int count;

		int indexOf(long id) {
			for (int index = 0; index < count; index++) if (ids[index] == id) return index;
			return -1;
		}

		void append(long id, OwnerBucket owner, int slot, int value, String label) {
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
		final int[] initial;
		final int[] previous;
		final String[] labels;
		final boolean[] freeze;
		final int[] freezeValues;
		final boolean[] freezePaused;
		long revision;

		WatchSnapshot(int count) {
			this.count = count;
			ids = new long[count];
			owners = new OwnerBucket[count];
			slots = new int[count];
			initial = new int[count];
			previous = new int[count];
			labels = new String[count];
			freeze = new boolean[count];
			freezeValues = new int[count];
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
