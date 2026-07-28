/*
 * Copyright 2026 H3NB
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

package javax.microedition.shell.memory;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Rootless, logical-value search engine. It observes only values that pass
 * through generated game-code hooks; it never scans arbitrary process memory.
 */
public final class MemoryEditorRuntime {
	public enum ValueKind { INT, LONG, FLOAT, DOUBLE }

	public enum SearchMode {
		EXACT, NOT_EQUAL, LESS_THAN, GREATER_THAN, UNKNOWN, CHANGED, UNCHANGED,
		INCREASED, DECREASED, RANGE
	}

	public enum OperationStatus {
		SUCCESS, PARTIAL, CANCELLED, STALE_SESSION, NO_SESSION
	}

	public enum ErrorCode {
		INVALID_KIND, INVALID_MODE, INVALID_VALUE, INVALID_RANGE, INVALID_RESULT_PAGE
	}

	public static final class MemoryEditorException extends IllegalArgumentException {
		public final ErrorCode code;

		private MemoryEditorException(ErrorCode code, String message) {
			super(message);
			this.code = code;
		}

		private MemoryEditorException(ErrorCode code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}
	}

	public static final class Snapshot {
		public final long gameGeneration;
		public final long searchSessionId;
		public final int candidates;
		public final int frozen;
		public final int saved;
		public final long candidateBytes;
		public final long candidateByteBudget;
		public final ValueKind kind;
		public final SearchMode mode;
		public final boolean limitReached;
		public final boolean collecting;
		public final boolean undoAvailable;
		public final long intObservations;
		public final long longObservations;
		public final long floatObservations;
		public final long doubleObservations;
		public final long fieldObservations;
		public final long arrayObservations;
		public final long readObservations;
		public final long writeObservations;

		private Snapshot(long gameGeneration, long searchSessionId,
				int candidates, int frozen, int saved, long candidateBytes,
				long candidateByteBudget, ValueKind kind, SearchMode mode,
				boolean limitReached, boolean collecting, boolean undoAvailable,
				long intObservations, long longObservations, long floatObservations,
				long doubleObservations, long fieldObservations, long arrayObservations,
				long readObservations, long writeObservations) {
			this.gameGeneration = gameGeneration;
			this.searchSessionId = searchSessionId;
			this.candidates = candidates;
			this.frozen = frozen;
			this.saved = saved;
			this.candidateBytes = candidateBytes;
			this.candidateByteBudget = candidateByteBudget;
			this.kind = kind;
			this.mode = mode;
			this.limitReached = limitReached;
			this.collecting = collecting;
			this.undoAvailable = undoAvailable;
			this.intObservations = intObservations;
			this.longObservations = longObservations;
			this.floatObservations = floatObservations;
			this.doubleObservations = doubleObservations;
			this.fieldObservations = fieldObservations;
			this.arrayObservations = arrayObservations;
			this.readObservations = readObservations;
			this.writeObservations = writeObservations;
		}

		public long selectedObservations() {
			if (kind == null) {
				return 0;
			}
			return switch (kind) {
				case INT -> intObservations;
				case LONG -> longObservations;
				case FLOAT -> floatObservations;
				case DOUBLE -> doubleObservations;
			};
		}

		public long totalObservations() {
			return intObservations + longObservations + floatObservations
					+ doubleObservations;
		}
	}

	/** Immutable, session-scoped result exposed to the UI. */
	public static final class CandidateView {
		public final long id;
		public final String value;
		public final String storageType;
		public final String location;
		public final boolean frozen;
		public final boolean saved;
		public final boolean editable;

		private CandidateView(long id, String value, String storageType, String location,
				boolean frozen, boolean saved, boolean editable) {
			this.id = id;
			this.value = value;
			this.storageType = storageType;
			this.location = location;
			this.frozen = frozen;
			this.saved = saved;
			this.editable = editable;
		}
	}

	/** Detailed result for edit/freeze operations over selected candidates. */
	public static final class OperationResult {
		public final long searchSessionId;
		public final long operationId;
		public final OperationStatus status;
		public final int requested;
		public final int succeeded;
		public final int failed;

		private OperationResult(long searchSessionId, long operationId,
				OperationStatus status, int requested, int succeeded) {
			this.searchSessionId = searchSessionId;
			this.operationId = operationId;
			this.status = status;
			this.requested = requested;
			this.succeeded = succeeded;
			this.failed = requested - succeeded;
		}
	}

	public static final class OperationProgress {
		public final long searchSessionId;
		public final long operationId;
		public final int completed;
		public final int total;
		public final boolean cancellable;

		private OperationProgress(long searchSessionId, long operationId,
				int completed, int total, boolean cancellable) {
			this.searchSessionId = searchSessionId;
			this.operationId = operationId;
			this.completed = completed;
			this.total = total;
			this.cancellable = cancellable;
		}
	}

	private static final int MAX_CANDIDATES = 50_000;
	private static final long MAX_CANDIDATE_BYTES = 8L * 1024L * 1024L;
	private static final int MAX_SESSION_READ_RETRIES = 3;
	private static final int INSTANCE_FIELD_INDEX = -1;
	private static final int STATIC_FIELD_INDEX = -2;
	private static final String ARRAY_MEMBER = "#array";
	private static final Object SESSION_LOCK = new Object();
	private static final AtomicLong NEXT_GAME_GENERATION = new AtomicLong(1);
	private static final AtomicLong NEXT_SEARCH_SESSION_ID = new AtomicLong(1);
	private static final AtomicLong NEXT_OPERATION_ID = new AtomicLong(1);
	private static volatile long gameGeneration = 1;
	private static volatile Session session;

	private MemoryEditorRuntime() {
	}

	private static OperationResult noSessionResult() {
		return new OperationResult(0, 0, OperationStatus.NO_SESSION, 0, 0);
	}

	private static OperationResult staleSessionResult(long expectedSessionId) {
		return new OperationResult(expectedSessionId, 0,
				OperationStatus.STALE_SESSION, 0, 0);
	}

	private static Snapshot emptySnapshot() {
		return new Snapshot(gameGeneration, 0, 0, 0, 0, 0,
				MAX_CANDIDATE_BYTES, null, null, false, false, false,
				0, 0, 0, 0, 0, 0, 0, 0);
	}

	private static int kindMask(ValueKind kind) {
		return 1 << kind.ordinal();
	}

	/** Caller must hold {@code active.lock}. */
	private static void updateActiveKindsLocked(Session active) {
		synchronized (SESSION_LOCK) {
			if (active != session || active.closed) {
				return;
			}
			MemoryEditorBridge.setActiveKinds(
					active.collecting || !active.frozen.isEmpty()
							? kindMask(active.kind) : 0);
		}
	}

	public static long begin(ValueKind kind, SearchMode mode, String first, String second) {
		if (kind == null || mode == null) {
			throw new MemoryEditorException(
					kind == null ? ErrorCode.INVALID_KIND : ErrorCode.INVALID_MODE,
					"Search kind and mode are required");
		}
		if (mode != SearchMode.EXACT && mode != SearchMode.NOT_EQUAL
				&& mode != SearchMode.LESS_THAN && mode != SearchMode.GREATER_THAN
				&& mode != SearchMode.UNKNOWN && mode != SearchMode.RANGE) {
			throw new MemoryEditorException(ErrorCode.INVALID_MODE,
					"Initial search mode is not supported");
		}
		Criteria criteria = parseCriteria(kind, mode, first, second);
		synchronized (SESSION_LOCK) {
			closeSession(session);
			long sessionId = NEXT_SEARCH_SESSION_ID.getAndIncrement();
			session = new Session(gameGeneration, sessionId, kind, mode,
					criteria.lower, criteria.upper);
			MemoryEditorBridge.setActiveKinds(kindMask(kind));
			return sessionId;
		}
	}

	/**
	 * Stops accepting new locations while retaining the current candidates as
	 * the baseline for refinement.
	 */
	public static void finishCollection() {
		Session active = session;
		finishCollection(active == null ? 0 : active.searchSessionId);
	}

	public static boolean finishCollection(long expectedSessionId) {
		Session active = session;
		if (active == null || active.searchSessionId != expectedSessionId) {
			return false;
		}
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return false;
			}
			active.collecting = false;
			updateActiveKindsLocked(active);
			return true;
		}
	}

	/**
	 * Resumes accepting new locations for the current initial-search criteria.
	 * Existing candidates and their baselines are retained.
	 */
	public static boolean resumeCollection(long expectedSessionId) {
		Session active = session;
		if (active == null || active.searchSessionId != expectedSessionId) {
			return false;
		}
		synchronized (active.lock) {
			if (active != session || active.closed || active.limitReached
					|| !active.supportsCollection()) {
				return false;
			}
			active.collecting = true;
			updateActiveKindsLocked(active);
			return true;
		}
	}

	public static OperationResult refine(SearchMode mode, String first, String second) {
		Session active = session;
		return refine(active == null ? 0 : active.searchSessionId, mode, first, second);
	}

	public static OperationResult refine(long expectedSessionId,
			SearchMode mode, String first, String second) {
		if (mode == null) {
			throw new MemoryEditorException(ErrorCode.INVALID_MODE, "Search mode is required");
		}
		Session active = session;
		if (active == null) {
			return noSessionResult();
		}
		if (active.searchSessionId != expectedSessionId) {
			return staleSessionResult(expectedSessionId);
		}
		Criteria criteria = parseCriteria(active.kind, mode, first, second);
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return staleSessionResult(expectedSessionId);
			}
			int requested = active.size();
			long operationId = active.startOperation(requested);
			OperationStatus status = active.refine(mode, criteria.lower, criteria.upper);
			int succeeded = status == OperationStatus.SUCCESS ? requested : 0;
			active.finishOperation(operationId);
			updateActiveKindsLocked(active);
			return new OperationResult(active.searchSessionId, operationId, status,
					requested, succeeded);
		}
	}

	public static Snapshot snapshot() {
		for (int attempt = 0; attempt < MAX_SESSION_READ_RETRIES; attempt++) {
			Session active = session;
			if (active == null) {
				return emptySnapshot();
			}
			synchronized (active.lock) {
				if (active != session || active.closed) {
					continue;
				}
				active.purgeCollected();
				updateActiveKindsLocked(active);
				return new Snapshot(active.gameGeneration, active.searchSessionId,
						active.size(), active.frozen.size(), active.saved.size(),
						active.candidateBytes, MAX_CANDIDATE_BYTES,
						active.kind,
						active.mode, active.limitReached, active.collecting,
						!active.undo.isEmpty(),
						active.observations.get(ValueKind.INT.ordinal()),
						active.observations.get(ValueKind.LONG.ordinal()),
						active.observations.get(ValueKind.FLOAT.ordinal()),
						active.observations.get(ValueKind.DOUBLE.ordinal()),
						active.fieldObservations.get(), active.arrayObservations.get(),
						active.readObservations.get(), active.writeObservations.get());
			}
		}
		return emptySnapshot();
	}

	public static OperationProgress operationProgress() {
		Session active = session;
		if (active == null) {
			return new OperationProgress(0, 0, 0, 0, false);
		}
		long operationId = active.activeOperationId.get();
		return new OperationProgress(
				active.searchSessionId,
				operationId,
				(int) Math.min(Integer.MAX_VALUE, active.operationCompleted.get()),
				(int) Math.min(Integer.MAX_VALUE, active.operationTotal.get()),
				operationId != 0);
	}

	public static boolean cancelOperation(long expectedSessionId, long operationId) {
		Session active = session;
		if (active == null || active.searchSessionId != expectedSessionId
				|| active.activeOperationId.get() != operationId) {
			return false;
		}
		active.cancelRequested.set(true);
		return true;
	}

	public static int editAll(String text) {
		return editCandidates(null, text).succeeded;
	}

	public static OperationResult editCandidates(long[] candidateIds, String text) {
		Session active = session;
		return editCandidates(active == null ? 0 : active.searchSessionId, candidateIds, text);
	}

	public static OperationResult editCandidates(long expectedSessionId,
			long[] candidateIds, String text) {
		Session active = session;
		if (active == null) {
			return noSessionResult();
		}
		if (active.searchSessionId != expectedSessionId) {
			return staleSessionResult(expectedSessionId);
		}
		Value value = parse(active.kind, text);
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return staleSessionResult(expectedSessionId);
			}
			active.collecting = false;
			List<Undo> batch = new ArrayList<>();
			int changed = 0;
			List<Candidate> selected = active.select(candidateIds);
			long operationId = active.startOperation(selected.size());
			for (Candidate candidate : selected) {
				if (active.operationStatus() != OperationStatus.SUCCESS) {
					break;
				}
				Value oldValue = candidate.read();
				if (oldValue == null || !candidate.apply(value)) {
					active.advanceOperation();
					continue;
				}
				Value oldFrozen = active.frozen.get(candidate);
				batch.add(new Undo(candidate, oldValue, oldFrozen,
						active.saved.contains(candidate)));
				if (oldFrozen != null) {
					active.frozen.put(candidate, value);
				}
				candidate.baseline = value;
				changed++;
				active.advanceOperation();
			}
			active.addUndoBatch(batch);
			OperationStatus status = active.operationStatus();
			active.finishOperation(operationId);
			updateActiveKindsLocked(active);
			return new OperationResult(active.searchSessionId, operationId, status,
					selected.size(), changed);
		}
	}

	public static int freezeAll(String text) {
		return freezeCandidates(null, text).succeeded;
	}

	public static OperationResult freezeCandidates(long[] candidateIds, String text) {
		Session active = session;
		return freezeCandidates(active == null ? 0 : active.searchSessionId,
				candidateIds, text);
	}

	public static OperationResult freezeCandidates(long expectedSessionId,
			long[] candidateIds, String text) {
		Session active = session;
		if (active == null) {
			return noSessionResult();
		}
		if (active.searchSessionId != expectedSessionId) {
			return staleSessionResult(expectedSessionId);
		}
		Value value = parse(active.kind, text);
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return staleSessionResult(expectedSessionId);
			}
			active.collecting = false;
			int changed = 0;
			List<Undo> batch = new ArrayList<>();
			List<Candidate> selected = active.select(candidateIds);
			long operationId = active.startOperation(selected.size());
			for (Candidate candidate : selected) {
				if (active.operationStatus() != OperationStatus.SUCCESS) {
					break;
				}
				Value oldValue = candidate.read();
				if (oldValue == null) {
					active.advanceOperation();
					continue;
				}
				Value oldFrozen = active.frozen.get(candidate);
				boolean oldSaved = active.saved.contains(candidate);
				if (candidate.apply(value)) {
					batch.add(new Undo(candidate, oldValue, oldFrozen, oldSaved));
					active.frozen.put(candidate, value);
					active.saved.add(candidate);
					candidate.baseline = value;
					changed++;
				}
				active.advanceOperation();
			}
			active.addUndoBatch(batch);
			OperationStatus status = active.operationStatus();
			active.finishOperation(operationId);
			updateActiveKindsLocked(active);
			return new OperationResult(active.searchSessionId, operationId, status,
					selected.size(), changed);
		}
	}

	public static List<CandidateView> results(int offset, int limit) {
		if (offset < 0) {
			throw new MemoryEditorException(ErrorCode.INVALID_RESULT_PAGE,
					"Result offset must not be negative");
		}
		if (limit < 1 || limit > 500) {
			throw new MemoryEditorException(ErrorCode.INVALID_RESULT_PAGE,
					"Result limit must be between 1 and 500");
		}
		for (int attempt = 0; attempt < MAX_SESSION_READ_RETRIES; attempt++) {
			Session active = session;
			if (active == null) {
				return new ArrayList<>();
			}
			synchronized (active.lock) {
				if (active != session || active.closed) {
					continue;
				}
				List<CandidateView> result = active.results(offset, limit);
				updateActiveKindsLocked(active);
				return result;
			}
		}
		return new ArrayList<>();
	}

	public static List<CandidateView> savedResults(int offset, int limit) {
		if (offset < 0) {
			throw new MemoryEditorException(ErrorCode.INVALID_RESULT_PAGE,
					"Result offset must not be negative");
		}
		if (limit < 1 || limit > 500) {
			throw new MemoryEditorException(ErrorCode.INVALID_RESULT_PAGE,
					"Result limit must be between 1 and 500");
		}
		for (int attempt = 0; attempt < MAX_SESSION_READ_RETRIES; attempt++) {
			Session active = session;
			if (active == null) {
				return new ArrayList<>();
			}
			synchronized (active.lock) {
				if (active != session || active.closed) {
					continue;
				}
				List<CandidateView> result = active.savedResults(offset, limit);
				updateActiveKindsLocked(active);
				return result;
			}
		}
		return new ArrayList<>();
	}

	public static OperationResult clearFreeze(long[] candidateIds) {
		Session active = session;
		return clearFreeze(active == null ? 0 : active.searchSessionId, candidateIds);
	}

	public static OperationResult clearFreeze(long expectedSessionId, long[] candidateIds) {
		Session active = session;
		if (active == null) {
			return noSessionResult();
		}
		if (active.searchSessionId != expectedSessionId) {
			return staleSessionResult(expectedSessionId);
		}
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return staleSessionResult(expectedSessionId);
			}
			List<Candidate> selected = active.select(candidateIds);
			long operationId = active.startOperation(selected.size());
			int changed = 0;
			for (Candidate candidate : selected) {
				if (active.operationStatus() != OperationStatus.SUCCESS) {
					break;
				}
				if (active.frozen.remove(candidate) != null) {
					changed++;
				}
				active.advanceOperation();
			}
			OperationStatus status = active.operationStatus();
			active.finishOperation(operationId);
			updateActiveKindsLocked(active);
			return new OperationResult(active.searchSessionId, operationId,
					status, selected.size(), changed);
		}
	}

	public static OperationResult deleteSaved(long[] candidateIds) {
		Session active = session;
		return deleteSaved(active == null ? 0 : active.searchSessionId, candidateIds);
	}

	public static OperationResult deleteSaved(long expectedSessionId, long[] candidateIds) {
		Session active = session;
		if (active == null) {
			return noSessionResult();
		}
		if (active.searchSessionId != expectedSessionId) {
			return staleSessionResult(expectedSessionId);
		}
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return staleSessionResult(expectedSessionId);
			}
			List<Candidate> selected = active.select(candidateIds);
			long operationId = active.startOperation(selected.size());
			int changed = 0;
			for (Candidate candidate : selected) {
				if (active.operationStatus() != OperationStatus.SUCCESS) {
					break;
				}
				active.frozen.remove(candidate);
				if (active.saved.remove(candidate)) {
					changed++;
					if (!active.candidates.contains(candidate)) {
						active.remove(candidate);
					}
				}
				active.advanceOperation();
			}
			OperationStatus status = active.operationStatus();
			active.finishOperation(operationId);
			updateActiveKindsLocked(active);
			return new OperationResult(active.searchSessionId, operationId,
					status, selected.size(), changed);
		}
	}

	public static void clearFreeze() {
		Session active = session;
		if (active != null) {
			synchronized (active.lock) {
				if (active == session && !active.closed) {
					active.frozen.clear();
					updateActiveKindsLocked(active);
				}
			}
		}
	}

	public static boolean undo() {
		Session active = session;
		if (active == null) {
			return false;
		}
		synchronized (active.lock) {
			if (active != session || active.closed || active.undo.isEmpty()) {
				return false;
			}
			List<Undo> batch = active.undo.removeLast();
			boolean restored = true;
			for (int i = batch.size() - 1; i >= 0; i--) {
				Undo undo = batch.get(i);
				if (undo.candidate.apply(undo.oldValue)) {
					undo.candidate.baseline = undo.oldValue;
				} else {
					restored = false;
				}
				if (undo.oldFrozen == null) {
					active.frozen.remove(undo.candidate);
				} else {
					active.frozen.put(undo.candidate, undo.oldFrozen);
				}
				if (undo.oldSaved) {
					active.saved.add(undo.candidate);
				} else {
					active.saved.remove(undo.candidate);
				}
			}
			updateActiveKindsLocked(active);
			return restored;
		}
	}

	public static void resetSearch() {
		synchronized (SESSION_LOCK) {
			closeSession(session);
			session = null;
		}
	}

	public static void clear() {
		resetSearch();
	}

	public static long beginGame() {
		synchronized (SESSION_LOCK) {
			closeSession(session);
			session = null;
			gameGeneration = NEXT_GAME_GENERATION.incrementAndGet();
			return gameGeneration;
		}
	}

	public static void endGame() {
		synchronized (SESSION_LOCK) {
			closeSession(session);
			session = null;
			gameGeneration = NEXT_GAME_GENERATION.incrementAndGet();
		}
	}

	private static void closeSession(Session current) {
		MemoryEditorBridge.setActiveKinds(0);
		if (current != null) {
			current.closed = true;
			current.cancelRequested.set(true);
		}
	}

	public static int onReadInt(Object target, Class<?> owner, String member,
			long site, int index, int value) {
		return (int) observeBits(ValueKind.INT, false, target, owner, member, site,
				index, value);
	}

	public static int onWriteInt(Object target, Class<?> owner, String member,
			long site, int index, int value) {
		return (int) observeBits(ValueKind.INT, true, target, owner, member, site,
				index, value);
	}

	public static long onReadLong(Object target, Class<?> owner, String member,
			long site, int index, long value) {
		return observeBits(ValueKind.LONG, false, target, owner, member, site, index,
				value);
	}

	public static long onWriteLong(Object target, Class<?> owner, String member,
			long site, int index, long value) {
		return observeBits(ValueKind.LONG, true, target, owner, member, site, index,
				value);
	}

	public static float onReadFloat(Object target, Class<?> owner, String member,
			long site, int index, float value) {
		return Float.intBitsToFloat((int) observeBits(ValueKind.FLOAT, false,
				target, owner, member, site, index, Float.floatToRawIntBits(value)));
	}

	public static float onWriteFloat(Object target, Class<?> owner, String member,
			long site, int index, float value) {
		return Float.intBitsToFloat((int) observeBits(ValueKind.FLOAT, true,
				target, owner, member, site, index, Float.floatToRawIntBits(value)));
	}

	public static double onReadDouble(Object target, Class<?> owner, String member,
			long site, int index, double value) {
		return Double.longBitsToDouble(observeBits(ValueKind.DOUBLE, false,
				target, owner, member, site, index, Double.doubleToRawLongBits(value)));
	}

	public static double onWriteDouble(Object target, Class<?> owner, String member,
			long site, int index, double value) {
		return Double.longBitsToDouble(observeBits(ValueKind.DOUBLE, true,
				target, owner, member, site, index, Double.doubleToRawLongBits(value)));
	}

	private static long observeBits(ValueKind kind, boolean write, Object target,
			Class<?> owner, String member, long site, int index, long bits) {
		if (!MemoryEditorBridge.isKindActive(kind.ordinal() + 1)) {
			return bits;
		}
		Session active = session;
		if (active == null) {
			return bits;
		}
		if (active.collecting) {
			active.noteObservation(kind, ARRAY_MEMBER.equals(member), write);
		}
		if (active.kind != kind) {
			return bits;
		}
		Value value = new Value(kind, bits);
		// A null instance target represents a field access which will throw. It
		// must not be confused with a valid static field candidate.
		if (target == null && index == INSTANCE_FIELD_INDEX) {
			return bits;
		}
		if (ARRAY_MEMBER.equals(member)
				&& (target == null || !target.getClass().isArray()
				|| index < 0 || index >= Array.getLength(target))) {
			return bits;
		}
		synchronized (active.lock) {
			if (active != session || active.closed || active.kind != kind) {
				return bits;
			}
			active.purgeCollected();
			if (!active.collecting && active.frozen.isEmpty()) {
				updateActiveKindsLocked(active);
				return bits;
			}
			Candidate candidate = active.find(target, owner, member, index);
			if (candidate == null && active.collecting && active.acceptInitial(value)) {
				candidate = active.add(target, owner, member, site, index, value);
			}
			if (candidate == null) {
				updateActiveKindsLocked(active);
				return bits;
			}
			Value frozen = active.frozen.get(candidate);
			return frozen == null ? bits : frozen.bits;
		}
	}

	private static Criteria parseCriteria(ValueKind kind, SearchMode mode,
			String first, String second) {
		Value lower = null;
		Value upper = null;
		if (mode == SearchMode.EXACT || mode == SearchMode.NOT_EQUAL
				|| mode == SearchMode.LESS_THAN || mode == SearchMode.GREATER_THAN
				|| mode == SearchMode.RANGE) {
			lower = parse(kind, first);
		}
		if (mode == SearchMode.RANGE) {
			upper = parse(kind, second);
			if (lower.isNaN() || upper.isNaN()) {
				throw new MemoryEditorException(ErrorCode.INVALID_RANGE,
						"NaN cannot be used as a range boundary");
			}
			if (lower.compareTo(upper) > 0) {
				throw new MemoryEditorException(ErrorCode.INVALID_RANGE,
						"Range minimum must not be greater than maximum");
			}
		}
		return new Criteria(lower, upper);
	}

	private static Value parse(ValueKind kind, String text) {
		if (text == null || text.trim().isEmpty()) {
			throw new MemoryEditorException(ErrorCode.INVALID_VALUE, "Value is required");
		}
		String value = text.trim();
		try {
			return switch (kind) {
				case INT -> Value.ofInt(Integer.decode(value));
				case LONG -> Value.ofLong(Long.decode(value));
				case FLOAT -> Value.ofFloat(Float.parseFloat(value));
				case DOUBLE -> Value.ofDouble(Double.parseDouble(value));
			};
		} catch (NumberFormatException e) {
			throw new MemoryEditorException(ErrorCode.INVALID_VALUE,
					"Invalid " + kind.name().toLowerCase(Locale.ROOT) + " value", e);
		}
	}

	private static int locationHash(Object target, Class<?> owner, String member, int index) {
		int result = 31 * System.identityHashCode(target) + System.identityHashCode(owner);
		result = 31 * result + member.hashCode();
		return 31 * result + index;
	}

	private static long estimatedCandidateBytes(String member) {
		return 192L + (long) member.length() * 2L;
	}

	private static final class Criteria {
		private final Value lower;
		private final Value upper;

		private Criteria(Value lower, Value upper) {
			this.lower = lower;
			this.upper = upper;
		}
	}

	private static final class Session {
		private final Object lock = new Object();
		private final long gameGeneration;
		private final long searchSessionId;
		private final ValueKind kind;
		private SearchMode mode;
		private Value lower;
		private Value upper;
		private final Set<Candidate> candidates = new LinkedHashSet<>();
		private final Map<Integer, List<Candidate>> candidateBuckets = new HashMap<>();
		private final Map<Long, Candidate> candidatesById = new HashMap<>();
		private final Map<Candidate, Value> frozen = new HashMap<>();
		private final Set<Candidate> saved = new LinkedHashSet<>();
		private final ArrayDeque<List<Undo>> undo = new ArrayDeque<>();
		private final ReferenceQueue<Object> collectedTargets = new ReferenceQueue<>();
		private final AtomicLongArray observations =
				new AtomicLongArray(ValueKind.values().length);
		private final AtomicLong fieldObservations = new AtomicLong();
		private final AtomicLong arrayObservations = new AtomicLong();
		private final AtomicLong readObservations = new AtomicLong();
		private final AtomicLong writeObservations = new AtomicLong();
		private final AtomicLong activeOperationId = new AtomicLong();
		private final AtomicLong operationCompleted = new AtomicLong();
		private final AtomicLong operationTotal = new AtomicLong();
		private final AtomicBoolean cancelRequested = new AtomicBoolean();
		private boolean limitReached;
		private volatile boolean collecting = true;
		private volatile boolean closed;
		private long nextCandidateId = 1;
		private long candidateBytes;

		private Session(long gameGeneration, long searchSessionId, ValueKind kind,
				SearchMode mode, Value lower, Value upper) {
			this.gameGeneration = gameGeneration;
			this.searchSessionId = searchSessionId;
			this.kind = kind;
			this.mode = mode;
			this.lower = lower;
			this.upper = upper;
		}

		private long startOperation(int total) {
			long operationId = NEXT_OPERATION_ID.getAndIncrement();
			cancelRequested.set(false);
			operationCompleted.set(0);
			operationTotal.set(total);
			activeOperationId.set(operationId);
			return operationId;
		}

		private void advanceOperation() {
			operationCompleted.incrementAndGet();
		}

		private OperationStatus operationStatus() {
			if (closed) {
				return OperationStatus.STALE_SESSION;
			}
			if (cancelRequested.get()) {
				return OperationStatus.CANCELLED;
			}
			return OperationStatus.SUCCESS;
		}

		private void finishOperation(long operationId) {
			activeOperationId.compareAndSet(operationId, 0);
			operationCompleted.set(0);
			operationTotal.set(0);
			cancelRequested.set(false);
		}

		private void noteObservation(ValueKind observedKind, boolean array, boolean write) {
			observations.incrementAndGet(observedKind.ordinal());
			(array ? arrayObservations : fieldObservations).incrementAndGet();
			(write ? writeObservations : readObservations).incrementAndGet();
		}

		private Candidate find(Object target, Class<?> owner, String member, int index) {
			List<Candidate> bucket = candidateBuckets.get(
					locationHash(target, owner, member, index));
			if (bucket == null) {
				return null;
			}
			for (Candidate candidate : bucket) {
				if (candidate.matches(target, owner, member, index)) {
					return candidate;
				}
			}
			return null;
		}

		private Candidate add(Object target, Class<?> owner, String member, long site,
				int index, Value value) {
			long estimatedBytes = estimatedCandidateBytes(member);
			if (candidates.size() >= MAX_CANDIDATES
					|| candidateBytes + estimatedBytes > MAX_CANDIDATE_BYTES) {
				limitReached = true;
				collecting = false;
				return null;
			}
			Candidate candidate = new Candidate(nextCandidateId++, target, owner, member,
					site, index, value, collectedTargets);
			candidateBytes += estimatedBytes;
			candidates.add(candidate);
			candidatesById.put(candidate.id, candidate);
			List<Candidate> bucket = candidateBuckets.get(candidate.locationHash);
			if (bucket == null) {
				bucket = new ArrayList<>();
				candidateBuckets.put(candidate.locationHash, bucket);
			}
			bucket.add(candidate);
			return candidate;
		}

		private boolean acceptInitial(Value value) {
			return switch (mode) {
				case UNKNOWN -> true;
				case EXACT -> value.equals(lower);
				case NOT_EQUAL -> !value.equals(lower);
				case LESS_THAN -> value.compareTo(lower) < 0;
				case GREATER_THAN -> value.compareTo(lower) > 0;
				case RANGE -> value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
				case CHANGED, UNCHANGED, INCREASED, DECREASED ->
						throw new IllegalStateException("Relational mode cannot collect a baseline");
			};
		}

		private boolean supportsCollection() {
			return switch (mode) {
				case EXACT, NOT_EQUAL, LESS_THAN, GREATER_THAN, UNKNOWN, RANGE -> true;
				case CHANGED, UNCHANGED, INCREASED, DECREASED -> false;
			};
		}

		private OperationStatus refine(SearchMode nextMode, Value nextLower, Value nextUpper) {
			purgeCollected();
			List<Candidate> original = new ArrayList<>(candidates);
			List<Candidate> retained = new ArrayList<>(original.size());
			List<Value> retainedValues = new ArrayList<>(original.size());
			for (Candidate candidate : original) {
				OperationStatus status = operationStatus();
				if (status != OperationStatus.SUCCESS) {
					return status;
				}
				Value current = candidate.read();
				if (current != null && matches(nextMode, candidate.baseline, current,
						nextLower, nextUpper)) {
					retained.add(candidate);
					retainedValues.add(current);
				}
				advanceOperation();
			}
			Set<Candidate> retainedSet = new LinkedHashSet<>(retained);
			for (Candidate candidate : original) {
				if (!retainedSet.contains(candidate)) {
					removeFromResults(candidate);
				}
			}
			for (int i = 0; i < retained.size(); i++) {
				retained.get(i).baseline = retainedValues.get(i);
			}
			mode = nextMode;
			lower = nextLower;
			upper = nextUpper;
			collecting = false;
			return OperationStatus.SUCCESS;
		}

		private boolean matches(SearchMode nextMode, Value previous, Value current,
				Value nextLower, Value nextUpper) {
			return switch (nextMode) {
				case UNKNOWN -> true;
				case EXACT -> current.equals(nextLower);
				case NOT_EQUAL -> !current.equals(nextLower);
				case LESS_THAN -> current.compareTo(nextLower) < 0;
				case GREATER_THAN -> current.compareTo(nextLower) > 0;
				case RANGE -> current.compareTo(nextLower) >= 0
						&& current.compareTo(nextUpper) <= 0;
				case CHANGED -> !current.equals(previous);
				case UNCHANGED -> current.equals(previous);
				case INCREASED -> current.compareTo(previous) > 0;
				case DECREASED -> current.compareTo(previous) < 0;
			};
		}

		private int size() {
			return candidates.size();
		}

		private List<Candidate> allCandidates() {
			purgeCollected();
			return new ArrayList<>(candidates);
		}

		private List<Candidate> select(long[] ids) {
			purgeCollected();
			if (ids == null) {
				return new ArrayList<>(candidates);
			}
			List<Candidate> selected = new ArrayList<>(ids.length);
			Set<Long> seen = new LinkedHashSet<>();
			for (long id : ids) {
				if (!seen.add(id)) {
					continue;
				}
				Candidate candidate = candidatesById.get(id);
				if (candidate != null) {
					selected.add(candidate);
				}
			}
			return selected;
		}

		private List<CandidateView> results(int offset, int limit) {
			purgeCollected();
			List<CandidateView> result = new ArrayList<>(Math.min(limit, candidates.size()));
			int position = 0;
			for (Candidate candidate : candidates) {
				if (position++ < offset) {
					continue;
				}
				Value current = candidate.read();
				if (current != null) {
					result.add(candidate.toView(current, frozen.containsKey(candidate),
							saved.contains(candidate)));
				}
				if (result.size() == limit) {
					break;
				}
			}
			return result;
		}

		private List<CandidateView> savedResults(int offset, int limit) {
			purgeCollected();
			List<CandidateView> result = new ArrayList<>(Math.min(limit, saved.size()));
			int position = 0;
			for (Candidate candidate : saved) {
				if (position++ < offset) {
					continue;
				}
				Value current = candidate.read();
				if (current != null) {
					result.add(candidate.toView(current, frozen.containsKey(candidate), true));
				}
				if (result.size() == limit) {
					break;
				}
			}
			return result;
		}

		private void addUndoBatch(List<Undo> batch) {
			undo.clear();
			if (batch.isEmpty()) {
				return;
			}
			undo.addLast(batch);
		}

		private void purgeCollected() {
			TargetReference reference;
			while ((reference = (TargetReference) collectedTargets.poll()) != null) {
				remove(reference.candidate);
			}
		}

		private void removeFromResults(Candidate candidate) {
			candidates.remove(candidate);
			if (!saved.contains(candidate)) {
				remove(candidate);
			}
		}

		private void remove(Candidate candidate) {
			boolean tracked = candidatesById.remove(candidate.id) != null;
			candidates.remove(candidate);
			if (tracked) {
				candidateBytes = Math.max(0,
						candidateBytes - estimatedCandidateBytes(candidate.member));
			}
			List<Candidate> bucket = candidateBuckets.get(candidate.locationHash);
			if (bucket != null) {
				bucket.remove(candidate);
				if (bucket.isEmpty()) {
					candidateBuckets.remove(candidate.locationHash);
				}
			}
			frozen.remove(candidate);
			saved.remove(candidate);
			Iterator<List<Undo>> batches = undo.iterator();
			while (batches.hasNext()) {
				List<Undo> batch = batches.next();
				Iterator<Undo> items = batch.iterator();
				while (items.hasNext()) {
					if (items.next().candidate == candidate) {
						items.remove();
					}
				}
				if (batch.isEmpty()) {
					batches.remove();
				}
			}
		}
	}

	private static final class Candidate {
		private final long id;
		private final TargetReference target;
		private final Class<?> owner;
		private final String member;
		@SuppressWarnings("unused")
		private final long firstSite;
		private final int index;
		private final int locationHash;
		private final Field field;
		private Value baseline;

		private Candidate(long id, Object target, Class<?> owner, String member, long site,
				int index, Value value, ReferenceQueue<Object> collectedTargets) {
			this.id = id;
			this.owner = owner;
			this.member = member;
			this.firstSite = site;
			this.index = index;
			this.locationHash = locationHash(target, owner, member, index);
			this.target = target == null ? null
					: new TargetReference(target, collectedTargets, this);
			this.field = ARRAY_MEMBER.equals(member) ? null : findField(owner, member);
			if (field != null) {
				try {
					field.setAccessible(true);
				} catch (RuntimeException ignored) {
					// Read/edit will report failure by returning null/false.
				}
			}
			this.baseline = value;
		}

		private boolean matches(Object otherTarget, Class<?> otherOwner, String otherMember,
				int otherIndex) {
			return index == otherIndex && owner == otherOwner && member.equals(otherMember)
					&& (target == null ? otherTarget == null : target.get() == otherTarget);
		}

		private Object liveTarget() {
			return target == null ? null : target.get();
		}

		private Value read() {
			Object object = liveTarget();
			if (target != null && object == null) {
				return null;
			}
			try {
				return field == null
						? readArray(object, index, baseline.kind)
						: readField(field, object, baseline.kind);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				return null;
			}
		}

		private boolean apply(Value value) {
			Object object = liveTarget();
			if (target != null && object == null) {
				return false;
			}
			if (!accepts(value, object)) {
				return false;
			}
			try {
				return field == null
						? setArray(object, index, value)
						: setField(field, object, value);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				return false;
			}
		}

		private boolean accepts(Value value, Object object) {
			Class<?> type = storageClass(object);
			if (type == null || (field != null && Modifier.isFinal(field.getModifiers()))) {
				return false;
			}
			if (value.kind != ValueKind.INT) {
				return primitiveDescriptor(type) == descriptorFor(value.kind);
			}
			long integer = value.asLong();
			if (type == boolean.class) return integer == 0 || integer == 1;
			if (type == byte.class) return integer >= Byte.MIN_VALUE && integer <= Byte.MAX_VALUE;
			if (type == char.class) return integer >= Character.MIN_VALUE
					&& integer <= Character.MAX_VALUE;
			if (type == short.class) return integer >= Short.MIN_VALUE
					&& integer <= Short.MAX_VALUE;
			return type == int.class;
		}

		private CandidateView toView(Value current, boolean frozen, boolean saved) {
			Object object = liveTarget();
			Class<?> type = storageClass(object);
			return new CandidateView(id, current.toDisplayString(), storageTypeName(type),
					locationName(object), frozen, saved, isEditable(type));
		}

		private Class<?> storageClass(Object object) {
			if (field != null) {
				return field.getType();
			}
			return object != null && object.getClass().isArray()
					? object.getClass().getComponentType() : null;
		}

		private boolean isEditable(Class<?> type) {
			return type != null && (field == null || !Modifier.isFinal(field.getModifiers()));
		}

		private String locationName(Object object) {
			if (field != null) {
				String prefix = Modifier.isStatic(field.getModifiers()) ? "static " : "";
				return "#" + id + " · " + prefix + field.getDeclaringClass().getName()
						+ "." + field.getName();
			}
			String component = object == null ? "unknown"
					: storageTypeName(object.getClass().getComponentType());
			return "#" + id + " · " + component + "[][" + index + "]";
		}
	}

	private static final class TargetReference extends WeakReference<Object> {
		private final Candidate candidate;

		private TargetReference(Object referent, ReferenceQueue<Object> queue,
				Candidate candidate) {
			super(referent, queue);
			this.candidate = candidate;
		}
	}

	private static final class Undo {
		private final Candidate candidate;
		private final Value oldValue;
		private final Value oldFrozen;
		private final boolean oldSaved;

		private Undo(Candidate candidate, Value oldValue, Value oldFrozen,
				boolean oldSaved) {
			this.candidate = candidate;
			this.oldValue = oldValue;
			this.oldFrozen = oldFrozen;
			this.oldSaved = oldSaved;
		}
	}

	private static Field findField(Class<?> type, String memberKey) {
		if (type == null || memberKey == null || memberKey.length() < 2) {
			return null;
		}
		String name = memberKey.substring(0, memberKey.length() - 1);
		char descriptor = memberKey.charAt(memberKey.length() - 1);
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				if (primitiveDescriptor(field.getType()) == descriptor) {
					return field;
				}
			} catch (NoSuchFieldException | SecurityException ignored) {
			}
		}
		return null;
	}

	private static char primitiveDescriptor(Class<?> type) {
		if (type == boolean.class) return 'Z';
		if (type == byte.class) return 'B';
		if (type == char.class) return 'C';
		if (type == short.class) return 'S';
		if (type == int.class) return 'I';
		if (type == long.class) return 'J';
		if (type == float.class) return 'F';
		if (type == double.class) return 'D';
		return 0;
	}

	private static char descriptorFor(ValueKind kind) {
		return switch (kind) {
			case INT -> 'I';
			case LONG -> 'J';
			case FLOAT -> 'F';
			case DOUBLE -> 'D';
		};
	}

	private static String storageTypeName(Class<?> type) {
		if (type == null) return "unknown";
		if (type == boolean.class) return "boolean";
		if (type == byte.class) return "byte";
		if (type == char.class) return "char";
		if (type == short.class) return "short";
		if (type == int.class) return "int";
		if (type == long.class) return "long";
		if (type == float.class) return "float";
		if (type == double.class) return "double";
		return type.getSimpleName();
	}

	private static Value readField(Field field, Object target, ValueKind kind)
			throws IllegalAccessException {
		Class<?> type = field.getType();
		if (kind == ValueKind.INT) {
			if (type == boolean.class) return Value.ofInt(field.getBoolean(target) ? 1 : 0);
			if (type == byte.class) return Value.ofInt(field.getByte(target));
			if (type == char.class) return Value.ofInt(field.getChar(target));
			if (type == short.class) return Value.ofInt(field.getShort(target));
			if (type == int.class) return Value.ofInt(field.getInt(target));
		} else if (kind == ValueKind.LONG && type == long.class) {
			return Value.ofLong(field.getLong(target));
		} else if (kind == ValueKind.FLOAT && type == float.class) {
			return Value.ofFloat(field.getFloat(target));
		} else if (kind == ValueKind.DOUBLE && type == double.class) {
			return Value.ofDouble(field.getDouble(target));
		}
		return null;
	}

	private static Value readArray(Object array, int index, ValueKind kind) {
		Class<?> type = array.getClass().getComponentType();
		if (kind == ValueKind.INT) {
			if (type == boolean.class) return Value.ofInt(Array.getBoolean(array, index) ? 1 : 0);
			if (type == byte.class) return Value.ofInt(Array.getByte(array, index));
			if (type == char.class) return Value.ofInt(Array.getChar(array, index));
			if (type == short.class) return Value.ofInt(Array.getShort(array, index));
			if (type == int.class) return Value.ofInt(Array.getInt(array, index));
		} else if (kind == ValueKind.LONG && type == long.class) {
			return Value.ofLong(Array.getLong(array, index));
		} else if (kind == ValueKind.FLOAT && type == float.class) {
			return Value.ofFloat(Array.getFloat(array, index));
		} else if (kind == ValueKind.DOUBLE && type == double.class) {
			return Value.ofDouble(Array.getDouble(array, index));
		}
		return null;
	}

	private static boolean setField(Field field, Object target, Value value)
			throws IllegalAccessException {
		Class<?> type = field.getType();
		if (value.kind == ValueKind.INT) {
			if (type == boolean.class) field.setBoolean(target, value.asLong() != 0);
			else if (type == byte.class) field.setByte(target, (byte) value.asLong());
			else if (type == char.class) field.setChar(target, (char) value.asLong());
			else if (type == short.class) field.setShort(target, (short) value.asLong());
			else if (type == int.class) field.setInt(target, (int) value.asLong());
			else return false;
		} else if (value.kind == ValueKind.LONG && type == long.class) {
			field.setLong(target, value.asLong());
		} else if (value.kind == ValueKind.FLOAT && type == float.class) {
			field.setFloat(target, value.asFloat());
		} else if (value.kind == ValueKind.DOUBLE && type == double.class) {
			field.setDouble(target, value.asDouble());
		} else {
			return false;
		}
		return true;
	}

	private static boolean setArray(Object array, int index, Value value) {
		Class<?> type = array.getClass().getComponentType();
		if (value.kind == ValueKind.INT) {
			if (type == boolean.class) Array.setBoolean(array, index, value.asLong() != 0);
			else if (type == byte.class) Array.setByte(array, index, (byte) value.asLong());
			else if (type == char.class) Array.setChar(array, index, (char) value.asLong());
			else if (type == short.class) Array.setShort(array, index, (short) value.asLong());
			else if (type == int.class) Array.setInt(array, index, (int) value.asLong());
			else return false;
		} else if (value.kind == ValueKind.LONG && type == long.class) {
			Array.setLong(array, index, value.asLong());
		} else if (value.kind == ValueKind.FLOAT && type == float.class) {
			Array.setFloat(array, index, value.asFloat());
		} else if (value.kind == ValueKind.DOUBLE && type == double.class) {
			Array.setDouble(array, index, value.asDouble());
		} else {
			return false;
		}
		return true;
	}

	private static final class Value {
		private final ValueKind kind;
		private final long bits;

		private Value(ValueKind kind, long bits) {
			this.kind = kind;
			this.bits = bits;
		}

		private static Value ofInt(int value) { return new Value(ValueKind.INT, value); }
		private static Value ofLong(long value) { return new Value(ValueKind.LONG, value); }
		private static Value ofFloat(float value) {
			return new Value(ValueKind.FLOAT, Float.floatToIntBits(value));
		}
		private static Value ofDouble(double value) {
			return new Value(ValueKind.DOUBLE, Double.doubleToLongBits(value));
		}
		private long asLong() { return bits; }
		private float asFloat() { return Float.intBitsToFloat((int) bits); }
		private double asDouble() { return Double.longBitsToDouble(bits); }
		private String toDisplayString() {
			return switch (kind) {
				case INT -> Integer.toString((int) bits);
				case LONG -> Long.toString(bits);
				case FLOAT -> Float.toString(asFloat());
				case DOUBLE -> Double.toString(asDouble());
			};
		}
		private boolean isNaN() {
			return kind == ValueKind.FLOAT ? Float.isNaN(asFloat())
					: kind == ValueKind.DOUBLE && Double.isNaN(asDouble());
		}

		private int compareTo(Value other) {
			if (kind == ValueKind.FLOAT) return Float.compare(asFloat(), other.asFloat());
			if (kind == ValueKind.DOUBLE) return Double.compare(asDouble(), other.asDouble());
			return Long.compare(bits, other.bits);
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Value value && kind == value.kind && bits == value.bits;
		}

		@Override
		public int hashCode() {
			return 31 * kind.hashCode() + Long.hashCode(bits);
		}
	}
}
