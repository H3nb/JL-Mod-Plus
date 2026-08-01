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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
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

	/** User-facing storage choice. ValueKind remains the bridge lane ABI. */
	public enum SearchType {
		AUTO, BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE
	}

	public enum SearchMode {
		EXACT, NOT_EQUAL, LESS_THAN, GREATER_THAN, UNKNOWN, CHANGED, UNCHANGED,
		INCREASED, DECREASED, RANGE
	}

	public enum OperationStatus {
		SUCCESS, PARTIAL, CANCELLED, STALE_SESSION, NO_SESSION, BUSY
	}

	public enum CandidateStatus {
		ACTIVE, READ_FAILED, WRITE_FAILED, READ_ONLY, COLLECTED
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
		public final SearchType searchType;
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
				long candidateByteBudget, ValueKind kind, SearchType searchType,
				SearchMode mode,
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
			this.searchType = searchType;
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
			if (searchType == SearchType.AUTO) {
				return totalObservations();
			}
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
		public final CandidateStatus status;

		private CandidateView(long id, String value, String storageType, String location,
				boolean frozen, boolean saved, boolean editable, CandidateStatus status) {
			this.id = id;
			this.value = value;
			this.storageType = storageType;
			this.location = location;
			this.frozen = frozen;
			this.saved = saved;
			this.editable = editable;
			this.status = status;
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
	private static final int AUTO_MIN_LANE_CANDIDATES = 256;
	private static final int MAX_SESSION_READ_RETRIES = 3;
	private static final int INSTANCE_FIELD_INDEX = -1;
	private static final int STATIC_FIELD_INDEX = -2;
	private static final String ARRAY_MEMBER = "#array";
	private static final Object SESSION_LOCK = new Object();
	private static final EnumSet<SearchType> AUTO_LANES = EnumSet.of(
			SearchType.BYTE, SearchType.SHORT, SearchType.INT, SearchType.LONG,
			SearchType.FLOAT, SearchType.DOUBLE);
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

	private static OperationResult busyResult(long expectedSessionId) {
		return new OperationResult(expectedSessionId, 0,
				OperationStatus.BUSY, 0, 0);
	}

	private static Snapshot emptySnapshot() {
		return new Snapshot(gameGeneration, 0, 0, 0, 0, 0,
				MAX_CANDIDATE_BYTES, null, null, null, false, false, false,
				0, 0, 0, 0, 0, 0, 0, 0);
	}

	private static int kindMask(ValueKind kind) {
		return 1 << kind.ordinal();
	}

	private static int kindMask(EnumSet<ValueKind> kinds) {
		int mask = 0;
		for (ValueKind kind : kinds) {
			mask |= kindMask(kind);
		}
		return mask;
	}

	private static ValueKind laneFor(SearchType type) {
		return switch (type) {
			case BOOLEAN, BYTE, CHAR, SHORT, INT -> ValueKind.INT;
			case LONG -> ValueKind.LONG;
			case FLOAT -> ValueKind.FLOAT;
			case DOUBLE -> ValueKind.DOUBLE;
			case AUTO -> null;
		};
	}

	private static EnumSet<ValueKind> activeKindsFor(SearchType type) {
		if (type == SearchType.AUTO) {
			return EnumSet.allOf(ValueKind.class);
		}
		return EnumSet.of(laneFor(type));
	}

	/** Caller must hold {@code active.lock}; no global lock is taken on the hook path. */
	private static void updateActiveKindsLocked(Session active) {
		if (active != session || active.closed) {
			return;
		}
		MemoryEditorBridge.setActiveKinds(
				active.collecting || !active.frozen.isEmpty()
						? kindMask(active.activeKinds) : 0);
	}

	public static long begin(ValueKind kind, SearchMode mode, String first, String second) {
		if (kind == null || mode == null) {
			throw new MemoryEditorException(
					kind == null ? ErrorCode.INVALID_KIND : ErrorCode.INVALID_MODE,
					"Search kind and mode are required");
		}
		return beginLegacy(kind, mode, first, second);
	}

	public static long begin(SearchType type, SearchMode mode, String first, String second) {
		if (type == null || mode == null) {
			throw new MemoryEditorException(
					type == null ? ErrorCode.INVALID_KIND : ErrorCode.INVALID_MODE,
					"Search type and mode are required");
		}
		validateInitialMode(mode);
		EnumMap<ValueKind, Criteria> criteria = parseCriteria(type, mode, first, second);
		EnumSet<ValueKind> activeKinds = activeKindsFor(type);
		synchronized (SESSION_LOCK) {
			closeSession(session);
			long sessionId = NEXT_SEARCH_SESSION_ID.getAndIncrement();
			session = new Session(gameGeneration, sessionId, laneFor(type), type,
					activeKinds, criteria, mode);
			MemoryEditorBridge.setActiveKinds(kindMask(activeKinds));
			return sessionId;
		}
	}

	private static long beginLegacy(ValueKind kind, SearchMode mode,
			String first, String second) {
		if (mode != SearchMode.EXACT && mode != SearchMode.NOT_EQUAL
				&& mode != SearchMode.LESS_THAN && mode != SearchMode.GREATER_THAN
				&& mode != SearchMode.UNKNOWN && mode != SearchMode.RANGE) {
			throw new MemoryEditorException(ErrorCode.INVALID_MODE,
					"Initial search mode is not supported");
		}
		Criteria criteria = parseCriteria(kind, mode, first, second);
		EnumMap<ValueKind, Criteria> criteriaByKind = new EnumMap<>(ValueKind.class);
		criteriaByKind.put(kind, criteria);
		synchronized (SESSION_LOCK) {
			closeSession(session);
			long sessionId = NEXT_SEARCH_SESSION_ID.getAndIncrement();
			session = new Session(gameGeneration, sessionId, kind, null,
					EnumSet.of(kind), criteriaByKind, mode);
			MemoryEditorBridge.setActiveKinds(kindMask(kind));
			return sessionId;
		}
	}

	private static void validateInitialMode(SearchMode mode) {
		if (mode != SearchMode.EXACT && mode != SearchMode.NOT_EQUAL
				&& mode != SearchMode.LESS_THAN && mode != SearchMode.GREATER_THAN
				&& mode != SearchMode.UNKNOWN && mode != SearchMode.RANGE) {
			throw new MemoryEditorException(ErrorCode.INVALID_MODE,
					"Initial search mode is not supported");
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
		EnumMap<ValueKind, Criteria> criteria = parseCriteriaForSession(
				active, mode, first, second);
		List<Candidate> original;
		long operationId;
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return staleSessionResult(expectedSessionId);
			}
			original = active.allCandidates();
			operationId = active.startOperation(original.size());
			if (operationId == 0) {
				return busyResult(expectedSessionId);
			}
			active.collecting = false;
			updateActiveKindsLocked(active);
		}
		RefineWork work = active.refineOutsideLock(mode, criteria, original);
		synchronized (active.lock) {
			if (active != session || active.closed) {
				active.finishOperation(operationId);
				return staleSessionResult(expectedSessionId);
			}
			if (work.status == OperationStatus.SUCCESS) {
				active.commitRefine(mode, criteria, original, work.retained,
						work.retainedValues);
			}
			active.finishOperation(operationId);
			updateActiveKindsLocked(active);
			int succeeded = work.status == OperationStatus.SUCCESS ? original.size() : 0;
			return new OperationResult(active.searchSessionId, operationId,
				work.status, original.size(), succeeded);
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
						active.kind, active.searchType,
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
		Value value = active.searchType == SearchType.AUTO
				? null
				: active.searchType == null
						? parse(active.kind, text)
						: parse(active.searchType, active.kind, text);
		List<Candidate> selected;
		long operationId;
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return staleSessionResult(expectedSessionId);
			}
			selected = active.select(candidateIds);
			operationId = active.startOperation(selected.size());
			if (operationId == 0) {
				return busyResult(expectedSessionId);
			}
			active.collecting = false;
			updateActiveKindsLocked(active);
		}
		List<Undo> batch = new ArrayList<>();
		int changed = 0;
		for (Candidate candidate : selected) {
			if (active.operationStatus() != OperationStatus.SUCCESS) {
				break;
			}
			Value candidateValue;
			try {
				candidateValue = value == null
						? parseForCandidate(active.searchType, candidate, text) : value;
			} catch (MemoryEditorException invalidForLane) {
				active.advanceOperation();
				continue;
			}
			Value oldValue = candidate.read();
			if (oldValue == null) {
				active.advanceOperation();
				continue;
			}
			synchronized (active.lock) {
				if (active != session || active.closed
						|| active.operationStatus() != OperationStatus.SUCCESS
						|| !active.isTracked(candidate)) {
					break;
				}
				Value oldFrozen = active.frozen.get(candidate);
				if (!candidate.apply(candidateValue)) {
					active.advanceOperation();
					continue;
				}
				batch.add(new Undo(candidate, oldValue, oldFrozen,
						active.saved.contains(candidate)));
				if (oldFrozen != null) {
					active.frozen.put(candidate, candidateValue);
				}
				candidate.baseline = candidateValue;
				changed++;
				active.advanceOperation();
			}
		}
		synchronized (active.lock) {
			if (active != session || active.closed) {
				active.finishOperation(operationId);
				return staleSessionResult(expectedSessionId);
			}
			active.addUndoBatch(batch);
			OperationStatus status = active.operationStatus();
			if (status == OperationStatus.SUCCESS && changed < selected.size()) {
				status = OperationStatus.PARTIAL;
			}
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
		Value value = active.searchType == SearchType.AUTO
				? null
				: active.searchType == null
						? parse(active.kind, text)
						: parse(active.searchType, active.kind, text);
		List<Candidate> selected;
		long operationId;
		synchronized (active.lock) {
			if (active != session || active.closed) {
				return staleSessionResult(expectedSessionId);
			}
			selected = active.select(candidateIds);
			operationId = active.startOperation(selected.size());
			if (operationId == 0) {
				return busyResult(expectedSessionId);
			}
			active.collecting = false;
			updateActiveKindsLocked(active);
		}
		int changed = 0;
		List<Undo> batch = new ArrayList<>();
		for (Candidate candidate : selected) {
			if (active.operationStatus() != OperationStatus.SUCCESS) {
				break;
			}
			Value candidateValue;
			try {
				candidateValue = value == null
						? parseForCandidate(active.searchType, candidate, text) : value;
			} catch (MemoryEditorException invalidForLane) {
				active.advanceOperation();
				continue;
			}
			Value oldValue = candidate.read();
			if (oldValue == null) {
				active.advanceOperation();
				continue;
			}
			synchronized (active.lock) {
				if (active != session || active.closed
						|| active.operationStatus() != OperationStatus.SUCCESS
						|| !active.isTracked(candidate)) {
					break;
				}
				Value oldFrozen = active.frozen.get(candidate);
				boolean oldSaved = active.saved.contains(candidate);
				if (candidate.apply(candidateValue)) {
					batch.add(new Undo(candidate, oldValue, oldFrozen, oldSaved));
					active.frozen.put(candidate, candidateValue);
					active.saved.add(candidate);
					candidate.baseline = candidateValue;
					changed++;
				}
				active.advanceOperation();
			}
		}
		synchronized (active.lock) {
			if (active != session || active.closed) {
				active.finishOperation(operationId);
				return staleSessionResult(expectedSessionId);
			}
			active.addUndoBatch(batch);
			OperationStatus status = active.operationStatus();
			if (status == OperationStatus.SUCCESS && changed < selected.size()) {
				status = OperationStatus.PARTIAL;
			}
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
			List<CandidatePage> page;
			synchronized (active.lock) {
				if (active != session || active.closed) {
					continue;
				}
				page = active.candidatePage(offset, limit, false);
			}
			List<CandidateView> result = materializePage(page);
			synchronized (active.lock) {
				if (active != session || active.closed) {
					continue;
				}
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
			List<CandidatePage> page;
			synchronized (active.lock) {
				if (active != session || active.closed) {
					continue;
				}
				page = active.candidatePage(offset, limit, true);
			}
			List<CandidateView> result = materializePage(page);
			synchronized (active.lock) {
				if (active != session || active.closed) {
					continue;
				}
				updateActiveKindsLocked(active);
				return result;
			}
		}
		return new ArrayList<>();
	}

	private static List<CandidateView> materializePage(List<CandidatePage> page) {
		List<CandidateView> result = new ArrayList<>(page.size());
		for (CandidatePage item : page) {
			Value current = item.candidate.read();
			if (current != null || item.candidate.status != CandidateStatus.COLLECTED) {
				result.add(item.candidate.toView(current, item.frozen, item.saved));
			}
		}
		return result;
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
			if (operationId == 0) {
				return busyResult(expectedSessionId);
			}
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
			active.pruneUndo();
			OperationStatus status = active.operationStatus();
			if (status == OperationStatus.SUCCESS && changed < selected.size()) {
				status = OperationStatus.PARTIAL;
			}
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
			if (operationId == 0) {
				return busyResult(expectedSessionId);
			}
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
			active.pruneUndo();
			OperationStatus status = active.operationStatus();
			if (status == OperationStatus.SUCCESS && changed < selected.size()) {
				status = OperationStatus.PARTIAL;
			}
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
		List<Undo> batch;
		synchronized (active.lock) {
			if (active != session || active.closed || active.undo.isEmpty()) {
				return false;
			}
			batch = active.undo.removeLast();
			active.undoByCandidate.clear();
		}
		boolean restored = true;
		for (int i = batch.size() - 1; i >= 0; i--) {
			Undo undo = batch.get(i);
			synchronized (active.lock) {
				if (active != session || active.closed) {
					return false;
				}
				if (!active.isTracked(undo.candidate)) {
					// A candidate may have been collected or removed after the edit.
					// Restore the rest of the one-shot batch instead of losing it.
					restored = false;
					continue;
				}
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
		}
		synchronized (active.lock) {
			if (active == session && !active.closed) {
				updateActiveKindsLocked(active);
			}
		}
		return restored;
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
			/*
			 * Teardown and candidate work use the same lock. This prevents a
			 * reset/new MIDlet from completing while an in-flight edit or hook is
			 * still mutating the old session. updateActiveKindsLocked deliberately
			 * does not acquire SESSION_LOCK, so the lock order remains unidirectional.
			 */
			synchronized (current.lock) {
				current.closed = true;
				current.cancelRequested.set(true);
				current.clearStateLocked();
			}
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
		if (!active.activeKinds.contains(kind)) {
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
			if (active != session || active.closed || !active.activeKinds.contains(kind)) {
				return bits;
			}
			active.purgeCollected();
			if (!active.collecting && active.frozen.isEmpty()) {
				return bits;
			}
			Candidate candidate = active.find(target, owner, member, index);
			if (candidate == null && active.collecting
					&& active.acceptInitial(target, owner, member, index, kind, value)) {
				candidate = active.add(target, owner, member, site, index, value);
			}
			if (candidate == null) {
				// A failed match is the normal hot path while collecting. Do not
				// reacquire SESSION_LOCK for every observed value; only publish the
				// gate transition when the candidate budget stopped collection.
				if (active.limitReached) {
					updateActiveKindsLocked(active);
				}
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
			validateFiniteBoundary(lower, mode);
			validateFiniteBoundary(upper, mode);
			if (lower.isNaN() || upper.isNaN()) {
				throw new MemoryEditorException(ErrorCode.INVALID_RANGE,
						"NaN cannot be used as a range boundary");
			}
			if (lower.compareTo(upper) > 0) {
				throw new MemoryEditorException(ErrorCode.INVALID_RANGE,
						"Range minimum must not be greater than maximum");
			}
		} else if (mode == SearchMode.LESS_THAN || mode == SearchMode.GREATER_THAN) {
			validateFiniteBoundary(lower, mode);
		}
		return new Criteria(lower, upper);
	}

	private static EnumMap<ValueKind, Criteria> parseCriteria(SearchType type,
			SearchMode mode, String first, String second) {
		EnumMap<ValueKind, Criteria> result = new EnumMap<>(ValueKind.class);
		if (type == SearchType.AUTO) {
			for (ValueKind kind : ValueKind.values()) {
				try {
					result.put(kind, parseAutoCriteria(kind, mode, first, second));
				} catch (MemoryEditorException ignored) {
					// A value may be valid for one numeric lane but overflow another.
				}
			}
			if (result.isEmpty()) {
				throw new MemoryEditorException(ErrorCode.INVALID_VALUE,
						"Value is not valid for any automatic numeric type");
			}
			return result;
		}
		ValueKind kind = laneFor(type);
		result.put(kind, parseCriteria(type, kind, mode, first, second));
		return result;
	}

	private static Criteria parseAutoCriteria(ValueKind kind, SearchMode mode,
			String first, String second) {
		Value lower = null;
		Value upper = null;
		if (mode == SearchMode.EXACT || mode == SearchMode.NOT_EQUAL
				|| mode == SearchMode.LESS_THAN || mode == SearchMode.GREATER_THAN
				|| mode == SearchMode.RANGE) {
			lower = parseAutoValue(kind, first);
		}
		if (mode == SearchMode.RANGE) {
			upper = parseAutoValue(kind, second);
			validateFiniteBoundary(lower, mode);
			validateFiniteBoundary(upper, mode);
			if (lower.compareTo(upper) > 0) {
				throw new MemoryEditorException(ErrorCode.INVALID_RANGE,
						"Range minimum must not be greater than maximum");
			}
		} else if (mode == SearchMode.LESS_THAN || mode == SearchMode.GREATER_THAN) {
			validateFiniteBoundary(lower, mode);
		}
		return new Criteria(lower, upper);
	}

	/**
	 * Auto accepts an integral decimal representation such as {@code 7.0} for
	 * integer storage lanes, while manual integer types remain strict.
	 */
	private static Value parseAutoValue(ValueKind kind, String text) {
		try {
			return parse(kind, text);
		} catch (MemoryEditorException strictFailure) {
			if (kind != ValueKind.INT && kind != ValueKind.LONG) {
				throw strictFailure;
			}
			if (text == null || text.trim().isEmpty()) {
				throw strictFailure;
			}
			try {
				BigInteger integral = new BigDecimal(text.trim()).toBigIntegerExact();
				if (kind == ValueKind.INT) {
					return Value.ofInt(integral.intValueExact());
				}
				return Value.ofLong(integral.longValueExact());
			} catch (NumberFormatException | ArithmeticException ignored) {
				throw strictFailure;
			}
		}
	}

	private static Criteria parseCriteria(SearchType type, ValueKind kind,
			SearchMode mode, String first, String second) {
		Value lower = null;
		Value upper = null;
		if (mode == SearchMode.EXACT || mode == SearchMode.NOT_EQUAL
				|| mode == SearchMode.LESS_THAN || mode == SearchMode.GREATER_THAN
				|| mode == SearchMode.RANGE) {
			lower = type == SearchType.BOOLEAN
					? parseBoolean(first) : parse(type, kind, first);
		}
		if (mode == SearchMode.RANGE) {
			upper = type == SearchType.BOOLEAN
					? parseBoolean(second) : parse(type, kind, second);
			validateFiniteBoundary(lower, mode);
			validateFiniteBoundary(upper, mode);
			if (lower.isNaN() || upper.isNaN()) {
				throw new MemoryEditorException(ErrorCode.INVALID_RANGE,
						"NaN cannot be used as a range boundary");
			}
			if (lower.compareTo(upper) > 0) {
				throw new MemoryEditorException(ErrorCode.INVALID_RANGE,
						"Range minimum must not be greater than maximum");
			}
		} else if (mode == SearchMode.LESS_THAN || mode == SearchMode.GREATER_THAN) {
			validateFiniteBoundary(lower, mode);
		}
		return new Criteria(lower, upper);
	}

	private static void validateFiniteBoundary(Value value, SearchMode mode) {
		if (value != null && (value.isNaN() || value.isInfinite())) {
			ErrorCode code = mode == SearchMode.RANGE
					? ErrorCode.INVALID_RANGE : ErrorCode.INVALID_VALUE;
			throw new MemoryEditorException(code,
					"Non-finite values are not valid for this comparison");
		}
	}

	private static Value parseBoolean(String text) {
		if (text == null || text.trim().isEmpty()) {
			throw new MemoryEditorException(ErrorCode.INVALID_VALUE,
					"Boolean value is required");
		}
		return switch (text.trim().toLowerCase(Locale.ROOT)) {
			case "true", "1" -> Value.ofInt(1);
			case "false", "0" -> Value.ofInt(0);
			default -> throw new MemoryEditorException(ErrorCode.INVALID_VALUE,
					"Boolean value must be true, false, 1, or 0");
		};
	}

	private static Value parse(SearchType type, ValueKind kind, String text) {
		if (type == SearchType.BOOLEAN) {
			return parseBoolean(text);
		}
		String value = text == null ? null : text.trim();
		if (value == null || value.isEmpty()) {
			throw new MemoryEditorException(ErrorCode.INVALID_VALUE, "Value is required");
		}
		try {
			return switch (type) {
				case BYTE -> boundedInt(value, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
				case SHORT -> boundedInt(value, Short.MIN_VALUE, Short.MAX_VALUE, "short");
				case CHAR -> boundedInt(value, Character.MIN_VALUE, Character.MAX_VALUE, "char");
				case INT -> Value.ofInt(Integer.decode(value));
				case LONG -> Value.ofLong(Long.decode(value));
				case FLOAT -> Value.ofFloat(Float.parseFloat(value));
				case DOUBLE -> Value.ofDouble(Double.parseDouble(value));
				case AUTO -> parse(kind, value);
				case BOOLEAN -> throw new AssertionError("Boolean is handled above");
			};
		} catch (NumberFormatException e) {
			throw new MemoryEditorException(ErrorCode.INVALID_VALUE,
					"Invalid " + type.name().toLowerCase(Locale.ROOT) + " value", e);
		}
	}

	private static Value boundedInt(String text, int minimum, int maximum,
			String typeName) {
		int parsed = Integer.decode(text);
		if (parsed < minimum || parsed > maximum) {
			throw new MemoryEditorException(ErrorCode.INVALID_VALUE,
					"Value is outside the " + typeName + " range");
		}
		return Value.ofInt(parsed);
	}

	private static Value parseForCandidate(SearchType searchType,
			Candidate candidate, String text) {
		if (searchType == SearchType.AUTO && candidate.autoLane != null) {
			return parseAutoLane(candidate.autoLane, candidate.baseline.kind, text);
		}
		return searchType == null
				? parse(candidate.baseline.kind, text)
				: parse(searchType, candidate.baseline.kind, text);
	}

	private static Value parseAutoLane(SearchType lane, ValueKind kind, String text) {
		if (lane == SearchType.BYTE || lane == SearchType.SHORT
				|| lane == SearchType.INT || lane == SearchType.LONG) {
			Value integer = parseAutoValue(kind, text);
			long value = integer.asLong();
			return switch (lane) {
				case BYTE -> boundedLong(value, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
				case SHORT -> boundedLong(value, Short.MIN_VALUE, Short.MAX_VALUE, "short");
				case INT -> boundedLong(value, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
				case LONG -> Value.ofLong(value);
				default -> throw new AssertionError("Not an integer Auto lane");
			};
		}
		return parse(lane, kind, text);
	}

	private static Value boundedLong(long value, long minimum, long maximum,
			String typeName) {
		if (value < minimum || value > maximum) {
			throw new MemoryEditorException(ErrorCode.INVALID_VALUE,
					"Value is outside the " + typeName + " range");
		}
		return Value.ofInt((int) value);
	}

	private static EnumMap<ValueKind, Criteria> parseCriteriaForSession(Session active,
			SearchMode mode, String first, String second) {
		if (active.searchType == null) {
			EnumMap<ValueKind, Criteria> result = new EnumMap<>(ValueKind.class);
			result.put(active.kind, parseCriteria(active.kind, mode, first, second));
			return result;
		}
		return parseCriteria(active.searchType, mode, first, second);
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
		private final SearchType searchType;
		private final EnumSet<ValueKind> activeKinds;
		private final EnumMap<ValueKind, Criteria> criteria;
		private SearchMode mode;
		private final Set<Candidate> candidates = new LinkedHashSet<>();
		private final Map<Integer, List<Candidate>> candidateBuckets = new HashMap<>();
		private final Map<Long, Candidate> candidatesById = new HashMap<>();
		private final Map<Candidate, Value> frozen = new HashMap<>();
		private final Set<Candidate> saved = new LinkedHashSet<>();
		private final EnumMap<SearchType, Integer> autoLaneCandidates =
				new EnumMap<>(SearchType.class);
		private final ArrayDeque<List<Undo>> undo = new ArrayDeque<>();
		private final Map<Candidate, Undo> undoByCandidate = new HashMap<>();
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
				SearchType searchType, EnumSet<ValueKind> activeKinds,
				EnumMap<ValueKind, Criteria> criteria, SearchMode mode) {
			this.gameGeneration = gameGeneration;
			this.searchSessionId = searchSessionId;
			this.kind = kind;
			this.searchType = searchType;
			this.activeKinds = EnumSet.copyOf(activeKinds);
			this.criteria = new EnumMap<>(criteria);
			this.mode = mode;
		}

		private long startOperation(int total) {
			if (activeOperationId.get() != 0) {
				return 0;
			}
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

		/** Caller must hold {@code lock}. */
		private void clearStateLocked() {
			candidates.clear();
			candidateBuckets.clear();
			candidatesById.clear();
			frozen.clear();
			saved.clear();
			undo.clear();
			undoByCandidate.clear();
			autoLaneCandidates.clear();
			candidateBytes = 0;
			limitReached = false;
			collecting = false;
			activeOperationId.set(0);
			operationCompleted.set(0);
			operationTotal.set(0);
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
			SearchType autoLane = searchType == SearchType.AUTO
					? autoLaneFor(target, owner, member, index) : null;
			if (searchType == SearchType.AUTO && autoLane != null
					&& !canAdmitAutoLane(autoLane, estimatedBytes)) {
				return null;
			}
			if (candidates.size() >= MAX_CANDIDATES
					|| candidateBytes + estimatedBytes > MAX_CANDIDATE_BYTES) {
				limitReached = true;
				collecting = false;
				return null;
			}
			Candidate candidate = new Candidate(nextCandidateId++, target, owner, member,
					site, index, value, autoLane, collectedTargets);
			candidateBytes += estimatedBytes;
			candidates.add(candidate);
			candidatesById.put(candidate.id, candidate);
			List<Candidate> bucket = candidateBuckets.get(candidate.locationHash);
			if (bucket == null) {
				bucket = new ArrayList<>();
				candidateBuckets.put(candidate.locationHash, bucket);
			}
			bucket.add(candidate);
			if (autoLane != null) {
				autoLaneCandidates.merge(autoLane, 1, Integer::sum);
			}
			return candidate;
		}

		private boolean canAdmitAutoLane(SearchType lane, long estimatedBytes) {
			long remainingByBytes = Math.max(0, MAX_CANDIDATE_BYTES - candidateBytes)
					/ Math.max(1L, estimatedBytes);
			int remaining = (int) Math.min(
					Math.min((long) MAX_CANDIDATES - candidates.size(), remainingByBytes),
					Integer.MAX_VALUE);
			int reservedForOtherLanes = 0;
			for (SearchType other : AUTO_LANES) {
				if (other == lane) {
					continue;
				}
				int count = autoLaneCandidates.getOrDefault(other, 0);
				reservedForOtherLanes += Math.max(0,
						AUTO_MIN_LANE_CANDIDATES - count);
			}
			if (remaining > reservedForOtherLanes) {
				return true;
			}
			return autoLaneCandidates.getOrDefault(lane, 0)
					< AUTO_MIN_LANE_CANDIDATES;
		}

		private SearchType autoLaneFor(Object target, Class<?> owner,
				String member, int index) {
			Class<?> storage = storageClass(target, owner, member, index);
			if (storage == byte.class) return SearchType.BYTE;
			if (storage == short.class) return SearchType.SHORT;
			if (storage == int.class) return SearchType.INT;
			if (storage == long.class) return SearchType.LONG;
			if (storage == float.class) return SearchType.FLOAT;
			if (storage == double.class) return SearchType.DOUBLE;
			return null;
		}

		private boolean acceptInitial(Object target, Class<?> owner, String member,
				int index, ValueKind observedKind, Value value) {
			if (!activeKinds.contains(observedKind)
					|| !acceptsStorage(target, owner, member, index, observedKind)) {
				return false;
			}
			Criteria initial = criteria.get(observedKind);
			if (initial == null) {
				return false;
			}
			return switch (mode) {
				case UNKNOWN -> true;
				case EXACT -> value.equals(initial.lower);
				case NOT_EQUAL -> !value.equals(initial.lower);
				case LESS_THAN -> value.compareTo(initial.lower) < 0;
				case GREATER_THAN -> value.compareTo(initial.lower) > 0;
				case RANGE -> value.compareTo(initial.lower) >= 0
						&& value.compareTo(initial.upper) <= 0;
				case CHANGED, UNCHANGED, INCREASED, DECREASED ->
						throw new IllegalStateException("Relational mode cannot collect a baseline");
			};
		}

		private boolean acceptsStorage(Object target, Class<?> owner, String member,
				int index, ValueKind observedKind) {
			if (searchType == null) {
				return true;
			}
			Class<?> storage = storageClass(target, owner, member, index);
			return switch (searchType) {
				case BOOLEAN -> storage == boolean.class;
				case BYTE -> storage == byte.class;
				case CHAR -> storage == char.class;
				case SHORT -> storage == short.class;
				case INT -> storage == int.class;
				case LONG -> storage == long.class;
				case FLOAT -> storage == float.class;
				case DOUBLE -> storage == double.class;
				case AUTO -> storage != null
						&& storage != boolean.class && storage != char.class;
			};
		}

		private Class<?> storageClass(Object target, Class<?> owner, String member, int index) {
			if (ARRAY_MEMBER.equals(member)) {
				return target == null || !target.getClass().isArray()
						? null : target.getClass().getComponentType();
			}
			Field field = findField(owner, member);
			return field == null ? null : field.getType();
		}

		private boolean supportsCollection() {
			return switch (mode) {
				case EXACT, NOT_EQUAL, LESS_THAN, GREATER_THAN, UNKNOWN, RANGE -> true;
				case CHANGED, UNCHANGED, INCREASED, DECREASED -> false;
			};
		}

		private RefineWork refineOutsideLock(SearchMode nextMode,
				EnumMap<ValueKind, Criteria> nextCriteria,
				List<Candidate> original) {
			List<Candidate> retained = new ArrayList<>(original.size());
			List<Value> retainedValues = new ArrayList<>(original.size());
			for (Candidate candidate : original) {
				OperationStatus status = operationStatus();
				if (status != OperationStatus.SUCCESS) {
					return new RefineWork(status, retained, retainedValues);
				}
				Value current = candidate.read();
				Criteria criteriaForCandidate = nextCriteria.get(candidate.baseline.kind);
				if (current != null && criteriaForCandidate != null
						&& matches(nextMode, candidate.baseline, current,
						criteriaForCandidate)) {
					retained.add(candidate);
					retainedValues.add(current);
				}
				advanceOperation();
			}
			return new RefineWork(OperationStatus.SUCCESS, retained, retainedValues);
		}

		private void commitRefine(SearchMode nextMode,
				EnumMap<ValueKind, Criteria> nextCriteria,
				List<Candidate> original, List<Candidate> retained,
				List<Value> retainedValues) {
			Set<Candidate> retainedSet = new LinkedHashSet<>(retained);
			for (Candidate candidate : original) {
				if (!retainedSet.contains(candidate)) {
					removeFromResults(candidate);
				}
			}
			pruneUndo();
			for (int i = 0; i < retained.size(); i++) {
				retained.get(i).baseline = retainedValues.get(i);
			}
			mode = nextMode;
			criteria.clear();
			criteria.putAll(nextCriteria);
		}

		private boolean matches(SearchMode nextMode, Value previous, Value current,
				Criteria nextCriteria) {
			return switch (nextMode) {
				case UNKNOWN -> true;
				case EXACT -> current.equals(nextCriteria.lower);
				case NOT_EQUAL -> !current.equals(nextCriteria.lower);
				case LESS_THAN -> current.compareTo(nextCriteria.lower) < 0;
				case GREATER_THAN -> current.compareTo(nextCriteria.lower) > 0;
				case RANGE -> current.compareTo(nextCriteria.lower) >= 0
						&& current.compareTo(nextCriteria.upper) <= 0;
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

		private boolean isTracked(Candidate candidate) {
			return candidatesById.get(candidate.id) == candidate;
		}

		/**
		 * Copies only candidate identity and flags while holding the session lock.
		 * Reflection is deliberately performed after the lock is released so a
		 * result page cannot pause a game thread that is entering a hook.
		 */
		private List<CandidatePage> candidatePage(int offset, int limit,
				boolean savedOnly) {
			purgeCollected();
			Set<Candidate> source = savedOnly ? saved : candidates;
			List<CandidatePage> result = new ArrayList<>(Math.min(limit, source.size()));
			int position = 0;
			for (Candidate candidate : source) {
				if (position++ < offset) {
					continue;
				}
				result.add(new CandidatePage(candidate, frozen.containsKey(candidate),
						savedOnly || saved.contains(candidate)));
				if (result.size() == limit) {
					break;
				}
			}
			return result;
		}

		private void addUndoBatch(List<Undo> batch) {
			undo.clear();
			undoByCandidate.clear();
			if (batch.isEmpty()) {
				return;
			}
			undo.addLast(batch);
			for (Undo item : batch) {
				undoByCandidate.put(item.candidate, item);
			}
		}

		private void purgeCollected() {
			TargetReference reference;
			while ((reference = (TargetReference) collectedTargets.poll()) != null) {
				remove(reference.candidate);
			}
			pruneUndo();
		}

		private void pruneUndo() {
			Iterator<List<Undo>> batches = undo.iterator();
			while (batches.hasNext()) {
				List<Undo> batch = batches.next();
				batch.removeIf(item -> undoByCandidate.get(item.candidate) != item);
				if (batch.isEmpty()) {
					batches.remove();
				}
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
				if (candidate.autoLane != null) {
					autoLaneCandidates.computeIfPresent(candidate.autoLane,
							(key, count) -> count > 1 ? count - 1 : null);
				}
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
			undoByCandidate.remove(candidate);
		}
	}

	private static final class CandidatePage {
		private final Candidate candidate;
		private final boolean frozen;
		private final boolean saved;

		private CandidatePage(Candidate candidate, boolean frozen, boolean saved) {
			this.candidate = candidate;
			this.frozen = frozen;
			this.saved = saved;
		}
	}

	private static final class RefineWork {
		private final OperationStatus status;
		private final List<Candidate> retained;
		private final List<Value> retainedValues;

		private RefineWork(OperationStatus status, List<Candidate> retained,
				List<Value> retainedValues) {
			this.status = status;
			this.retained = retained;
			this.retainedValues = retainedValues;
		}
	}

	private static final class Candidate {
		private final long id;
		private final TargetReference target;
		private final Class<?> owner;
		private final String member;
		private final SearchType autoLane;
		@SuppressWarnings("unused")
		private final long firstSite;
		private final int index;
		private final int locationHash;
		private final Field field;
		private volatile CandidateStatus status = CandidateStatus.ACTIVE;
		private Value baseline;

		private Candidate(long id, Object target, Class<?> owner, String member, long site,
				int index, Value value, SearchType autoLane,
				ReferenceQueue<Object> collectedTargets) {
			this.id = id;
			this.owner = owner;
			this.member = member;
			this.autoLane = autoLane;
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
				status = CandidateStatus.COLLECTED;
				return null;
			}
			try {
				Value result = field == null
						? readArray(object, index, baseline.kind)
						: readField(field, object, baseline.kind);
				if (result == null) {
					status = CandidateStatus.READ_FAILED;
				} else if (status != CandidateStatus.WRITE_FAILED
						&& status != CandidateStatus.READ_ONLY) {
					status = CandidateStatus.ACTIVE;
				}
				return result;
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				status = CandidateStatus.READ_FAILED;
				return null;
			}
		}

		private boolean apply(Value value) {
			Object object = liveTarget();
			if (target != null && object == null) {
				status = CandidateStatus.COLLECTED;
				return false;
			}
			if (!accepts(value, object)) {
				status = field != null && Modifier.isFinal(field.getModifiers())
						? CandidateStatus.READ_ONLY : CandidateStatus.WRITE_FAILED;
				return false;
			}
			try {
				boolean applied = field == null
						? setArray(object, index, value)
						: setField(field, object, value);
				status = applied ? CandidateStatus.ACTIVE : CandidateStatus.WRITE_FAILED;
				return applied;
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				status = CandidateStatus.WRITE_FAILED;
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
			boolean editable = isEditable(type);
			CandidateStatus viewStatus = editable ? status : CandidateStatus.READ_ONLY;
			return new CandidateView(id, current == null ? "?" : current.toDisplayString(),
					storageTypeName(type), locationName(object), frozen, saved, editable,
					viewStatus);
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
		private boolean isInfinite() {
			return kind == ValueKind.FLOAT ? Float.isInfinite(asFloat())
					: kind == ValueKind.DOUBLE && Double.isInfinite(asDouble());
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
