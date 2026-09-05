/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory;

import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Production gate for the Candidate -> compact ordinary result cutover. */
@RunWith(AndroidJUnit4.class)
public class MemoryV2CompactPromotionTest {
	@Test
	public void productionKnownIsCandidateFreeAndTrackedActionsStillWork() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);
		long[] originalProbe = NativeMemoryTarget.readProbe();
		assertNotNull(originalProbe);
		assertEquals(2, originalProbe.length);
		long probeAddress = originalProbe[0];
		long pageStart = probeAddress - Math.floorMod(probeAddress, (long) pageSize);
		long[] runs = {1L, 0L, pageStart, pageStart + pageSize};
		long token = 0x4A4C434F4D504143L;

		try {
			NativeMemoryTarget.writeProbe(1L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startKnown(
							MemoryEngineContract.TYPE_AUTO,
							MemoryEngineContract.PREDICATE_EQUAL, "1", ""));
			assertTrue(NativeMemoryEngine.v2KnownAuthoritativeRevision());
			assertCandidateFreeOwner("first scan");

			long[] page = NativeMemoryEngine.resultPage(0, 100);
			assertNotNull(page);
			long id = findIdAt(page, probeAddress, MemoryEngineContract.TYPE_INT);
			assertTrue("probe Int alias missing", id > 0L);
			long[] selected = {id};

			NativeMemoryTarget.writeProbe(2L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refresh(selected, false));
			page = NativeMemoryEngine.resultPage(0, 100);
			assertNotNull(page);
			assertEquals(2L, currentBitsForId(page, id));
			assertCandidateFreeOwner("presentation refresh");

			long[] inspection = NativeMemoryEngine.inspect(id, 16);
			assertNotNull(inspection);
			assertTrue(inspection.length >= 4);
			assertEquals(MemoryEngineContract.RESULT_OK, (int) inspection[0]);

			long[] aliases = NativeMemoryEngine.expandResultGroups(
					selected, MemoryEngineContract.TYPE_AUTO);
			assertNotNull(aliases);
			assertTrue("alias expansion lost the selected ResultId", containsRawId(aliases, id));

			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.pin(selected, true));
			assertTrue("Watch List did not receive promoted compact result",
					containsPageId(NativeMemoryEngine.watchPage(), id));
			assertCandidateFreeOwner("Watch promotion");

			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.edit(selected, "3"));
			page = NativeMemoryEngine.resultPage(0, 100);
			assertNotNull(page);
			assertEquals(3L, currentBitsForId(page, id));
			assertEquals(3L, NativeMemoryTarget.readProbe()[1]);
			assertCandidateFreeOwner("tracked edit");

			long beforeFilter = NativeMemoryEngine.resultCount();
			assertTrue(beforeFilter > 0L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.filter(selected, true));
			assertEquals("Keep filter should leave exactly one unique address", 1L,
					NativeMemoryEngine.resultCount());
			assertCandidateFreeOwner("filter");
			assertTrue(NativeMemoryEngine.historyDepth() > 0);

			assertEquals(MemoryEngineContract.RESULT_OK, NativeMemoryEngine.undo());
			assertEquals(beforeFilter, NativeMemoryEngine.resultCount());
			assertCandidateFreeOwner("Undo");

			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.pin(selected, false));
			assertTrue("Watch List removal left the candidate behind",
					!containsPageId(NativeMemoryEngine.watchPage(), id));
			assertCandidateFreeOwner("Watch removal");
		} finally {
			NativeMemoryTarget.writeProbe(originalProbe[1]);
			NativeMemoryEngine.clearTarget();
		}
	}

	private static void assertCandidateFreeOwner(String stage) {
		long[] stats = NativeMemoryEngine.v2CompactOwnerStats();
		assertNotNull(stage, stats);
		assertEquals(stage + " stats length", 8, stats.length);
		assertEquals(stage + " compact owner missing", 1L, stats[0]);
		assertTrue(stage + " has no ordinary typed results", stats[1] > 0L);
		assertEquals(stage + " still retains legacy Candidate ordinary rows", 0L, stats[2]);
		assertEquals(stage + " unique count diverged from resultCount",
				NativeMemoryEngine.resultCount(), stats[3]);
		assertTrue(stage + " compact revision retained-byte accounting is invalid", stats[4] > 0L);
	}

	private static long findIdAt(long[] page, long address, int type) {
		int count = Math.toIntExact(page[0]);
		for (int index = 0; index < count; index++) {
			int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
			if (page[base + 1] == address && page[base + 3] == type) return page[base];
		}
		return 0L;
	}

	private static long currentBitsForId(long[] page, long id) {
		int count = Math.toIntExact(page[0]);
		for (int index = 0; index < count; index++) {
			int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
			if (page[base] == id) return page[base + 8];
		}
		throw new AssertionError("ResultId " + id + " is not in page");
	}

	private static boolean containsPageId(long[] page, long id) {
		if (page == null || page.length == 0) return false;
		int count = Math.toIntExact(page[0]);
		for (int index = 0; index < count; index++) {
			int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
			if (page[base] == id) return true;
		}
		return false;
	}

	private static boolean containsRawId(long[] ids, long id) {
		for (long value : ids) if (value == id) return true;
		return false;
	}
}
