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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small LRU of candidate bindings that were actually materialized for UI use.
 *
 * Search results themselves remain native/compact. This cache exists only so a guarded mutation
 * can prove whether recovery changed a displayed CandidateId's raw address before allowing a
 * later retry. It is deliberately bounded and never becomes the authoritative search database.
 */
final class MemoryCandidateBindingCache {
	static final int COMPARE_STABLE = 0;
	static final int COMPARE_MOVED = 1;
	static final int COMPARE_UNKNOWN = 2;

	private static final int DEFAULT_LIMIT = 4096;
	private final int limit;
	private final LinkedHashMap<Long, Binding> bindings =
			new LinkedHashMap<>(128, 0.75f, true);

	MemoryCandidateBindingCache() {
		this(DEFAULT_LIMIT);
	}

	MemoryCandidateBindingCache(int limit) {
		if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
		this.limit = limit;
	}

	synchronized boolean recordPage(long[] rows, boolean watch, int resultPageOffset) {
		LinkedHashMap<Long, Binding> parsed = new LinkedHashMap<>();
		if (!collectPage(rows, watch, resultPageOffset, parsed)) return false;
		recordAllLocked(parsed);
		return true;
	}

	synchronized Map<Long, Binding> snapshot(long[] candidateIds) {
		if (candidateIds == null || candidateIds.length == 0) return null;
		LinkedHashMap<Long, Binding> result = new LinkedHashMap<>();
		for (long id : candidateIds) {
			Binding binding = bindings.get(id);
			if (binding == null) return null;
			result.put(id, binding);
		}
		return result;
	}

	synchronized int compareAndRecord(Map<Long, Binding> before,
	                                  Map<Long, Binding> after) {
		if (before == null || before.isEmpty() || after == null) {
			return COMPARE_UNKNOWN;
		}
		boolean moved = false;
		for (Map.Entry<Long, Binding> entry : before.entrySet()) {
			Binding current = after.get(entry.getKey());
			if (current == null) return COMPARE_UNKNOWN;
			Binding previous = entry.getValue();
			if (current.address != previous.address ||
					current.relocationCount > previous.relocationCount) {
				moved = true;
			}
		}
		recordAllLocked(after);
		return moved ? COMPARE_MOVED : COMPARE_STABLE;
	}

	synchronized void clear() {
		bindings.clear();
	}

	private void recordAllLocked(Map<Long, Binding> values) {
		for (Map.Entry<Long, Binding> entry : values.entrySet()) {
			bindings.put(entry.getKey(), entry.getValue());
		}
		while (bindings.size() > limit) {
			Iterator<Long> iterator = bindings.keySet().iterator();
			if (!iterator.hasNext()) break;
			iterator.next();
			iterator.remove();
		}
	}

	static boolean collectPage(long[] rows, boolean watch, int resultPageOffset,
	                           Map<Long, Binding> output) {
		int count = validatedPageCount(rows);
		if (count < 0 || output == null || resultPageOffset < 0) return false;
		for (int index = 0; index < count; index++) {
			int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
			long id = rows[base];
			long address = rows[base + 1];
			long rawRelocations = rows[base + 5];
			if (id <= 0L || address <= 0L || rawRelocations < 0L ||
					rawRelocations > Integer.MAX_VALUE) {
				return false;
			}
			output.put(id, new Binding(
					address, (int) rawRelocations, watch, resultPageOffset));
		}
		return true;
	}

	private static int validatedPageCount(long[] rows) {
		if (rows == null || rows.length == 0 || rows[0] < 0L ||
				rows[0] > (rows.length - 1L) / MemoryEngineContract.RESULT_PAGE_STRIDE) {
			return -1;
		}
		int count = (int) rows[0];
		return 1 + count * MemoryEngineContract.RESULT_PAGE_STRIDE == rows.length ? count : -1;
	}

	static final class Binding {
		final long address;
		final int relocationCount;
		final boolean watch;
		final int resultPageOffset;

		Binding(long address, int relocationCount, boolean watch, int resultPageOffset) {
			this.address = address;
			this.relocationCount = relocationCount;
			this.watch = watch;
			this.resultPageOffset = resultPageOffset;
		}
	}
}
