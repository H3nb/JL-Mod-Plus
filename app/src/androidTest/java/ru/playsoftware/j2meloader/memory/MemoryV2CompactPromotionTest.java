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

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Differential gate for the Candidate -> compact ordinary result cutover. The compact revision is
 * staged from the already-authoritative production ResultStore, then page/presentation/tracked
 * actions are exercised without changing normal application routing.
 */
@RunWith(AndroidJUnit4.class)
public class MemoryV2CompactPromotionTest {
	@Test
	public void compactPageRefreshInspectWatchAndEditMatchLegacyContract() {
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
			assertTrue("compact revision could not be staged",
					NativeMemoryEngine.stageV2CompactRevision());
			assertTrue(NativeMemoryEngine.hasCurrentV2CompactRevision());

			long[] legacyPage = NativeMemoryEngine.resultPage(0, 100);
			long[] compactPage = NativeMemoryEngine.resultPageV2Compact(0, 100);
			assertNotNull(legacyPage);
			assertNotNull(compactPage);
			assertArrayEquals("initial compact page diverged", legacyPage, compactPage);

			long id = findIdAt(compactPage, probeAddress, MemoryEngineContract.TYPE_INT);
			assertTrue("probe Int alias missing", id > 0L);
			long[] selected = {id};

			NativeMemoryTarget.writeProbe(2L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refreshV2Compact(selected, false));
			compactPage = NativeMemoryEngine.resultPageV2Compact(0, 100);
			legacyPage = NativeMemoryEngine.resultPage(0, 100);
			assertArrayEquals("presentation overlay diverged", legacyPage, compactPage);
			assertEquals(2L, currentBitsForId(compactPage, id));

			long[] compactInspect = NativeMemoryEngine.inspectV2Compact(id, 16);
			long[] legacyInspect = NativeMemoryEngine.inspect(id, 16);
			assertNotNull(compactInspect);
			assertNotNull(legacyInspect);
			assertArrayEquals("Inspector contract diverged", legacyInspect, compactInspect);

			long[] compactAliases =
					NativeMemoryEngine.expandResultGroupsV2Compact(
							selected, MemoryEngineContract.TYPE_AUTO);
			long[] legacyAliases =
					NativeMemoryEngine.expandResultGroups(selected, MemoryEngineContract.TYPE_AUTO);
			assertNotNull(compactAliases);
			assertNotNull(legacyAliases);
			Arrays.sort(compactAliases);
			Arrays.sort(legacyAliases);
			assertArrayEquals("alias expansion diverged", legacyAliases, compactAliases);

			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.pinV2Compact(selected, true));
			assertTrue("Watch List did not receive promoted compact result",
					containsId(NativeMemoryEngine.watchPage(), id));
			assertTrue("compact revision was not rebound after Watch mutation",
					NativeMemoryEngine.hasCurrentV2CompactRevision());
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.pinV2Compact(selected, false));
			assertTrue("Watch List removal left the candidate behind",
					!containsId(NativeMemoryEngine.watchPage(), id));

			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.editV2Compact(selected, "3"));
			compactPage = NativeMemoryEngine.resultPageV2Compact(0, 100);
			assertNotNull(compactPage);
			assertEquals("compact edit did not publish tracked current value",
					3L, currentBitsForId(compactPage, id));
			assertEquals("native probe did not receive compact edit",
					3L, NativeMemoryTarget.readProbe()[1]);
		} finally {
			NativeMemoryTarget.writeProbe(originalProbe[1]);
			NativeMemoryEngine.clearTarget();
		}
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

	private static boolean containsId(long[] page, long id) {
		if (page == null || page.length == 0) return false;
		int count = Math.toIntExact(page[0]);
		for (int index = 0; index < count; index++) {
			int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
			if (page[base] == id) return true;
		}
		return false;
	}
}
