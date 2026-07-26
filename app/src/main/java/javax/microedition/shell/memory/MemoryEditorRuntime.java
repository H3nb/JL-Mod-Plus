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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Rootless, logical-value search engine. It observes only values that pass
 * through generated game-code hooks; it never scans arbitrary process memory.
 */
public final class MemoryEditorRuntime {
	public enum ValueKind { INT, LONG, FLOAT, DOUBLE }

	public enum SearchMode {
		EXACT, UNKNOWN, CHANGED, UNCHANGED, INCREASED, DECREASED, RANGE
	}

	public static final class Snapshot {
		public final int candidates;
		public final int frozen;
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

		private Snapshot(int candidates, int frozen, ValueKind kind, SearchMode mode,
				boolean limitReached, boolean collecting, boolean undoAvailable,
				long intObservations, long longObservations, long floatObservations,
				long doubleObservations, long fieldObservations, long arrayObservations,
				long readObservations, long writeObservations) {
			this.candidates = candidates;
			this.frozen = frozen;
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
		public final boolean editable;

		private CandidateView(long id, String value, String storageType, String location,
				boolean frozen, boolean editable) {
			this.id = id;
			this.value = value;
			this.storageType = storageType;
			this.location = location;
			this.frozen = frozen;
			this.editable = editable;
		}
	}

	/** Detailed result for edit/freeze operations over selected candidates. */
	public static final class OperationResult {
		public final int requested;
		public final int succeeded;
		public final int failed;

		private OperationResult(int requested, int succeeded) {
			this.requested = requested;
			this.succeeded = succeeded;
			this.failed = requested - succeeded;
		}
	}

	private static final int MAX_CANDIDATES = 50_000;
	private static final int MAX_UNDO_BATCHES = 3;
	private static final int INSTANCE_FIELD_INDEX = -1;
	private static final int STATIC_FIELD_INDEX = -2;
	private static final String ARRAY_MEMBER = "#array";
	private static final Object LOCK = new Object();
	private static volatile Session session;

	private MemoryEditorRuntime() {
	}

	public static void begin(ValueKind kind, SearchMode mode, String first, String second) {
		if (kind == null || mode == null) {
			throw new IllegalArgumentException("Search kind and mode are required");
		}
		if (mode != SearchMode.EXACT && mode != SearchMode.UNKNOWN
				&& mode != SearchMode.RANGE) {
			throw new IllegalArgumentException(
					"Initial search supports only exact, unknown, or range");
		}
		Criteria criteria = parseCriteria(kind, mode, first, second);
		synchronized (LOCK) {
			session = new Session(kind, mode, criteria.lower, criteria.upper);
		}
	}

	/**
	 * Stops accepting new locations while retaining the current candidates as
	 * the baseline for refinement.
	 */
	public static void finishCollection() {
		synchronized (LOCK) {
			if (session != null) {
				session.collecting = false;
			}
		}
	}

	public static void refine(SearchMode mode, String first, String second) {
		if (mode == null) {
			throw new IllegalArgumentException("Search mode is required");
		}
		synchronized (LOCK) {
			if (session == null) {
				return;
			}
			Criteria criteria = parseCriteria(session.kind, mode, first, second);
			session.refine(mode, criteria.lower, criteria.upper);
		}
	}

	public static Snapshot snapshot() {
		synchronized (LOCK) {
			if (session == null) {
				return new Snapshot(0, 0, null, null, false, false, false,
						0, 0, 0, 0, 0, 0, 0, 0);
			}
			session.purgeCollected();
			return new Snapshot(session.size(), session.frozen.size(), session.kind,
					session.mode, session.limitReached, session.collecting,
					!session.undo.isEmpty(),
					session.observations.get(ValueKind.INT.ordinal()),
					session.observations.get(ValueKind.LONG.ordinal()),
					session.observations.get(ValueKind.FLOAT.ordinal()),
					session.observations.get(ValueKind.DOUBLE.ordinal()),
					session.fieldObservations.get(), session.arrayObservations.get(),
					session.readObservations.get(), session.writeObservations.get());
		}
	}

	public static int editAll(String text) {
		return editCandidates(null, text).succeeded;
	}

	public static OperationResult editCandidates(long[] candidateIds, String text) {
		synchronized (LOCK) {
			if (session == null) {
				return new OperationResult(0, 0);
			}
			Value value = parse(session.kind, text);
			session.collecting = false;
			List<Undo> batch = new ArrayList<>();
			int changed = 0;
			List<Candidate> selected = session.select(candidateIds);
			for (Candidate candidate : selected) {
				Value oldValue = candidate.read();
				if (oldValue == null || !candidate.apply(value)) {
					continue;
				}
				Value oldFrozen = session.frozen.get(candidate);
				batch.add(new Undo(candidate, oldValue, oldFrozen));
				if (oldFrozen != null) {
					session.frozen.put(candidate, value);
				}
				candidate.baseline = value;
				changed++;
			}
			session.addUndoBatch(batch);
			return new OperationResult(selected.size(), changed);
		}
	}

	public static int freezeAll(String text) {
		return freezeCandidates(null, text).succeeded;
	}

	public static OperationResult freezeCandidates(long[] candidateIds, String text) {
		synchronized (LOCK) {
			if (session == null) {
				return new OperationResult(0, 0);
			}
			Value value = parse(session.kind, text);
			session.collecting = false;
			int changed = 0;
			List<Candidate> selected = session.select(candidateIds);
			for (Candidate candidate : selected) {
				if (candidate.apply(value)) {
					session.frozen.put(candidate, value);
					candidate.baseline = value;
					changed++;
				}
			}
			return new OperationResult(selected.size(), changed);
		}
	}

	public static List<CandidateView> results(int offset, int limit) {
		if (offset < 0) {
			throw new IllegalArgumentException("Result offset must not be negative");
		}
		if (limit < 1 || limit > 500) {
			throw new IllegalArgumentException("Result limit must be between 1 and 500");
		}
		synchronized (LOCK) {
			if (session == null) {
				return new ArrayList<>();
			}
			return session.results(offset, limit);
		}
	}

	public static OperationResult clearFreeze(long[] candidateIds) {
		synchronized (LOCK) {
			if (session == null) {
				return new OperationResult(0, 0);
			}
			List<Candidate> selected = session.select(candidateIds);
			int changed = 0;
			for (Candidate candidate : selected) {
				if (session.frozen.remove(candidate) != null) {
					changed++;
				}
			}
			return new OperationResult(selected.size(), changed);
		}
	}

	public static void clearFreeze() {
		synchronized (LOCK) {
			if (session != null) {
				session.frozen.clear();
			}
		}
	}

	public static boolean undo() {
		synchronized (LOCK) {
			if (session == null || session.undo.isEmpty()) {
				return false;
			}
			List<Undo> batch = session.undo.removeLast();
			boolean restored = true;
			for (int i = batch.size() - 1; i >= 0; i--) {
				Undo undo = batch.get(i);
				if (undo.candidate.apply(undo.oldValue)) {
					undo.candidate.baseline = undo.oldValue;
				} else {
					restored = false;
				}
				if (undo.oldFrozen == null) {
					session.frozen.remove(undo.candidate);
				} else {
					session.frozen.put(undo.candidate, undo.oldFrozen);
				}
			}
			return restored;
		}
	}

	public static void clear() {
		synchronized (LOCK) {
			session = null;
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
		Session active = session;
		if (active == null) {
			return bits;
		}
		active.noteObservation(kind, ARRAY_MEMBER.equals(member), write);
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
		synchronized (LOCK) {
			active = session;
			if (active == null || active.kind != kind) {
				return bits;
			}
			active.purgeCollected();
			Candidate candidate = active.find(target, owner, member, index);
			if (candidate == null && active.collecting && active.acceptInitial(value)) {
				candidate = active.add(target, owner, member, site, index, value);
			}
			if (candidate == null) {
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
		if (mode == SearchMode.EXACT || mode == SearchMode.RANGE) {
			lower = parse(kind, first);
		}
		if (mode == SearchMode.RANGE) {
			upper = parse(kind, second);
			if (lower.isNaN() || upper.isNaN()) {
				throw new IllegalArgumentException("NaN cannot be used as a range boundary");
			}
			if (lower.compareTo(upper) > 0) {
				throw new IllegalArgumentException(
						"Range minimum must not be greater than maximum");
			}
		}
		return new Criteria(lower, upper);
	}

	private static Value parse(ValueKind kind, String text) {
		if (text == null || text.trim().isEmpty()) {
			throw new IllegalArgumentException("Value is required");
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
			throw new IllegalArgumentException(
					"Invalid " + kind.name().toLowerCase(Locale.ROOT) + " value", e);
		}
	}

	private static int locationHash(Object target, Class<?> owner, String member, int index) {
		int result = 31 * System.identityHashCode(target) + System.identityHashCode(owner);
		result = 31 * result + member.hashCode();
		return 31 * result + index;
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
		private final ValueKind kind;
		private SearchMode mode;
		private Value lower;
		private Value upper;
		private final Set<Candidate> candidates = new LinkedHashSet<>();
		private final Map<Integer, List<Candidate>> candidateBuckets = new HashMap<>();
		private final Map<Long, Candidate> candidatesById = new HashMap<>();
		private final Map<Candidate, Value> frozen = new HashMap<>();
		private final ArrayDeque<List<Undo>> undo = new ArrayDeque<>();
		private final ReferenceQueue<Object> collectedTargets = new ReferenceQueue<>();
		private final AtomicLongArray observations =
				new AtomicLongArray(ValueKind.values().length);
		private final AtomicLong fieldObservations = new AtomicLong();
		private final AtomicLong arrayObservations = new AtomicLong();
		private final AtomicLong readObservations = new AtomicLong();
		private final AtomicLong writeObservations = new AtomicLong();
		private boolean limitReached;
		private boolean collecting = true;
		private long nextCandidateId = 1;

		private Session(ValueKind kind, SearchMode mode, Value lower, Value upper) {
			this.kind = kind;
			this.mode = mode;
			this.lower = lower;
			this.upper = upper;
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
			if (candidates.size() >= MAX_CANDIDATES) {
				limitReached = true;
				collecting = false;
				return null;
			}
			Candidate candidate = new Candidate(nextCandidateId++, target, owner, member,
					site, index, value, collectedTargets);
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
				case RANGE -> value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
				case CHANGED, UNCHANGED, INCREASED, DECREASED ->
						throw new IllegalStateException("Relational mode cannot collect a baseline");
			};
		}

		private void refine(SearchMode nextMode, Value nextLower, Value nextUpper) {
			purgeCollected();
			List<Candidate> retained = new ArrayList<>(candidates.size());
			for (Candidate candidate : new ArrayList<>(candidates)) {
				Value current = candidate.read();
				if (current != null && matches(nextMode, candidate.baseline, current,
						nextLower, nextUpper)) {
					candidate.baseline = current;
					retained.add(candidate);
				} else {
					remove(candidate);
				}
			}
			// Preserve deterministic observation order after removals.
			candidates.clear();
			candidates.addAll(retained);
			mode = nextMode;
			lower = nextLower;
			upper = nextUpper;
			collecting = false;
		}

		private boolean matches(SearchMode nextMode, Value previous, Value current,
				Value nextLower, Value nextUpper) {
			return switch (nextMode) {
				case UNKNOWN -> true;
				case EXACT -> current.equals(nextLower);
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
					result.add(candidate.toView(current, frozen.containsKey(candidate)));
				}
				if (result.size() == limit) {
					break;
				}
			}
			return result;
		}

		private void addUndoBatch(List<Undo> batch) {
			if (batch.isEmpty()) {
				return;
			}
			undo.addLast(batch);
			while (undo.size() > MAX_UNDO_BATCHES) {
				undo.removeFirst();
			}
		}

		private void purgeCollected() {
			TargetReference reference;
			while ((reference = (TargetReference) collectedTargets.poll()) != null) {
				remove(reference.candidate);
			}
		}

		private void remove(Candidate candidate) {
			candidates.remove(candidate);
			candidatesById.remove(candidate.id);
			List<Candidate> bucket = candidateBuckets.get(candidate.locationHash);
			if (bucket != null) {
				bucket.remove(candidate);
				if (bucket.isEmpty()) {
					candidateBuckets.remove(candidate.locationHash);
				}
			}
			frozen.remove(candidate);
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

		private CandidateView toView(Value current, boolean frozen) {
			Object object = liveTarget();
			Class<?> type = storageClass(object);
			return new CandidateView(id, current.toDisplayString(), storageTypeName(type),
					locationName(object), frozen, isEditable(type));
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

		private Undo(Candidate candidate, Value oldValue, Value oldFrozen) {
			this.candidate = candidate;
			this.oldValue = oldValue;
			this.oldFrozen = oldFrozen;
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
