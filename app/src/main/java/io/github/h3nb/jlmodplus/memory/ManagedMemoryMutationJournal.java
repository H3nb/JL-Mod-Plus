/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.h3nb.jlmodplus.memory;

import java.math.BigInteger;
import java.util.HashMap;

/**
 * Runtime-local, bounded explicit-edit history. IDs encode logical owner slots, not addresses.
 * Reject new mutation targets at capacity rather than evicting history and mistaking edits
 * without a journal entry for unedited candidates during Revert & Remove.
 */
final class ManagedMemoryMutationJournal {
	private static final int MAX_ENTRIES = 1024;
	private final HashMap<Long, Entry> lastEdit = new HashMap<>();

	static final class Entry {
		final int type;
		final long before;
		final long after;
		final boolean uncertain;

		Entry(int type, long before, long after, boolean uncertain) {
			this.type = type;
			this.before = before;
			this.after = after;
			this.uncertain = uncertain;
		}
	}

	void clear() { lastEdit.clear(); }
	Entry get(long id) { return lastEdit.get(id); }
	void remove(long id) { lastEdit.remove(id); }

	boolean canRecord(long id) { return lastEdit.containsKey(id) || lastEdit.size() < MAX_ENTRIES; }

	boolean canRecord(long[] ids, long[] values, int count) {
		int needed = 0;
		for (int index = 0; index < count; index++) {
			if (!lastEdit.containsKey(ids[index])) needed++;
		}
		return lastEdit.size() + needed <= MAX_ENTRIES;
	}

	void record(long id, int type, long before, long after) {
		if (before != after && canRecord(id)) {
			lastEdit.put(id, new Entry(type, before, after, false));
		}
	}

	void markUncertain(long id, int type) {
		if (canRecord(id)) lastEdit.put(id, new Entry(type, 0L, 0L, true));
	}

	static boolean isIntegerType(int type) {
		return type >= MemoryEngineContract.TYPE_BYTE && type <= MemoryEngineContract.TYPE_LONG;
	}

	static boolean sequence(String baseText, String stepText, int type, long[] output) {
		if (!isIntegerType(type) || !MemoryEngineContract.isBoundedInput(baseText)
				|| !MemoryEngineContract.isBoundedInput(stepText)) return false;
		long[] parsedBase = new long[1];
		if (!ManagedJavaValue.parse(baseText, type, parsedBase)) return false;
		try {
			BigInteger step = new BigInteger(stepText.trim());
			BigInteger base = BigInteger.valueOf(parsedBase[0]);
			for (int index = 0; index < output.length; index++) {
				BigInteger value = base.add(step.multiply(BigInteger.valueOf(index)));
				if (!ManagedJavaValue.parse(value.toString(), type, parsedBase)) return false;
				output[index] = parsedBase[0];
			}
			return true;
		} catch (NumberFormatException exception) {
			return false;
		}
	}
}
