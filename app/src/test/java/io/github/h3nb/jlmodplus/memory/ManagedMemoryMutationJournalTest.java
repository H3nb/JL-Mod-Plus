/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ManagedMemoryMutationJournalTest {
	@Test
	public void sequenceHandlesNegativeStepsAndRejectsBoundaryOverflow() {
		long[] values = new long[3];
		assertTrue(ManagedMemoryMutationJournal.sequence("100", "-2",
				MemoryEngineContract.TYPE_INT, values));
		assertEquals(100, values[0]);
		assertEquals(98, values[1]);
		assertEquals(96, values[2]);
		assertFalse(ManagedMemoryMutationJournal.sequence("2147483647", "1",
				MemoryEngineContract.TYPE_INT, values));
		assertFalse(ManagedMemoryMutationJournal.sequence("0", "-1",
				MemoryEngineContract.TYPE_CHAR, values));
		assertFalse(ManagedMemoryMutationJournal.sequence("9223372036854775807", "1",
				MemoryEngineContract.TYPE_LONG, values));
	}

	@Test
	public void sequenceDoesNotAcceptFloatingAndUnboundedInput() {
		assertFalse(ManagedMemoryMutationJournal.sequence("1", "1",
				MemoryEngineContract.TYPE_FLOAT, new long[2]));
		assertFalse(ManagedMemoryMutationJournal.sequence("1", "not-a-number",
				MemoryEngineContract.TYPE_INT, new long[2]));
	}

	@Test
	public void repeatedEditsKeepLastBeforeAndAfterWithoutKeepingTargetsAlive() {
		ManagedMemoryMutationJournal journal = new ManagedMemoryMutationJournal();
		long id = 1234L;
		journal.record(id, MemoryEngineContract.TYPE_INT, 7L, 800L);
		journal.record(id, MemoryEngineContract.TYPE_INT, 120L, 900L);
		journal.record(id, MemoryEngineContract.TYPE_INT, 900L, 900L);
		ManagedMemoryMutationJournal.Entry last = journal.get(id);
		assertNotNull(last);
		assertEquals(120L, last.before);
		assertEquals(900L, last.after);
		journal.remove(id);
		assertTrue(journal.canRecord(id));
		journal.clear();
		assertEquals(null, journal.get(id));
	}

	@Test
	public void unconfirmedWriteRetainsAnExplicitUnsafeMarker() {
		ManagedMemoryMutationJournal journal = new ManagedMemoryMutationJournal();
		journal.record(10L, MemoryEngineContract.TYPE_INT, 7L, 99L);
		journal.markUncertain(10L, MemoryEngineContract.TYPE_INT);
		assertTrue(journal.get(10L).uncertain);
		journal.record(10L, MemoryEngineContract.TYPE_INT, 88L, 90L);
		assertFalse(journal.get(10L).uncertain);
		assertEquals(88L, journal.get(10L).before);
	}

	@Test
	public void confirmedRestoreRemainsTrackedUntilRemovalCommits() {
		ManagedMemoryMutationJournal journal = new ManagedMemoryMutationJournal();
		long id = ManagedJavaMemoryIds.encode(ManagedJavaMemoryEngine.KIND_ARRAY, 3, 1);
		journal.record(id, MemoryEngineContract.TYPE_INT, 11, 100);
		ManagedMemoryMutationJournal.Entry original = journal.get(id);
		journal.markRestored(id, original);
		assertTrue(journal.get(id).restored);
		assertEquals(11, journal.get(id).before);
		assertEquals(100, journal.get(id).after);

		// Obsolete owner handles must not consume the bounded history indefinitely.
		journal.pruneCandidates(value -> ManagedJavaMemoryIds.ownerHandle(value) == 4L);
		assertEquals(null, journal.get(id));
	}

	@Test
	public void candidatePruningRemovesSlotsWithinAnOtherwiseRetainedOwner() {
		ManagedMemoryMutationJournal journal = new ManagedMemoryMutationJournal();
		long first = ManagedJavaMemoryIds.encode(ManagedJavaMemoryEngine.KIND_ARRAY, 3, 0);
		long second = ManagedJavaMemoryIds.encode(ManagedJavaMemoryEngine.KIND_ARRAY, 3, 1);
		journal.record(first, MemoryEngineContract.TYPE_INT, 7, 12);
		journal.record(second, MemoryEngineContract.TYPE_INT, 7, 13);
		journal.pruneCandidates(id -> id == first);
		assertNotNull(journal.get(first));
		assertEquals(null, journal.get(second));
	}

	@Test
	public void capacityRejectsNewTargetsRatherThanDiscardingHistory() {
		ManagedMemoryMutationJournal journal = new ManagedMemoryMutationJournal();
		for (int index = 0; index < 1024; index++) {
			journal.record(index, MemoryEngineContract.TYPE_INT, 0, 1);
		}
		assertFalse(journal.canRecord(1025L));
		assertTrue(journal.canRecord(11L));
		assertFalse(journal.canRecord(new long[]{11L, 1025L}, 2));
	}
}
