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

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Device gate for candidate-free Unknown materialization, relative COW refine, and Undo. */
@RunWith(AndroidJUnit4.class)
public class MemoryV2RelativeAuthoritativeTest {
	private static final int[] EXPECTED_ALIASES = {
			MemoryEngineContract.TYPE_INT,
			MemoryEngineContract.TYPE_FLOAT,
			MemoryEngineContract.TYPE_LONG,
			MemoryEngineContract.TYPE_DOUBLE,
			MemoryEngineContract.TYPE_SHORT,
			MemoryEngineContract.TYPE_CHAR,
			MemoryEngineContract.TYPE_BYTE,
	};

	@Test
	public void unknownIncreasedMaterializesCompactThenRefinesCowAndUndoes() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);
		long[] originalProbe = NativeMemoryTarget.readProbe();
		assertNotNull(originalProbe);
		assertEquals(2, originalProbe.length);
		long probeAddress = originalProbe[0];
		long pageStart = probeAddress - Math.floorMod(probeAddress, (long) pageSize);
		long[] runs = {1L, 0L, pageStart, pageStart + pageSize};
		long token = 0x4A4C52454C56324CL;

		try {
			NativeMemoryTarget.writeProbe(1L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startUnknown(MemoryEngineContract.TYPE_AUTO));

			NativeMemoryTarget.writeProbe(2L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineRelative(
							MemoryEngineContract.PREDICATE_INCREASED,
							MemoryEngineContract.COMPARE_PREVIOUS,
							"", ""));
			assertCompactCandidateFree("first Unknown relative materialization");
			Map<Integer, Long> firstIds = aliasesAt(probeAddress);
			assertExpectedAliases(firstIds);
			assertResultCursorOnly();

			NativeMemoryTarget.writeProbe(3L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineRelative(
							MemoryEngineContract.PREDICATE_INCREASED,
							MemoryEngineContract.COMPARE_PREVIOUS,
							"", ""));
			assertCompactCandidateFree("relative COW refine");
			Map<Integer, Long> secondIds = aliasesAt(probeAddress);
			assertExpectedAliases(secondIds);
			for (int type : EXPECTED_ALIASES) {
				assertEquals("ResultId changed during relative COW refine for type=" + type,
						firstIds.get(type), secondIds.get(type));
			}
			assertTrue("relative COW refine did not retain Undo history",
					NativeMemoryEngine.historyDepth() > 0);
			assertResultCursorOnly();

			assertEquals(MemoryEngineContract.RESULT_OK, NativeMemoryEngine.undo());
			assertCompactCandidateFree("relative Undo");
			Map<Integer, Long> undoneIds = aliasesAt(probeAddress);
			assertExpectedAliases(undoneIds);
			for (int type : EXPECTED_ALIASES) {
				assertEquals("ResultId changed after compact relative Undo for type=" + type,
						firstIds.get(type), undoneIds.get(type));
			}
			assertResultCursorOnly();
		} finally {
			NativeMemoryTarget.writeProbe(originalProbe[1]);
			NativeMemoryEngine.clearTarget();
		}
	}

	private static void assertCompactCandidateFree(String label) {
		assertTrue(label + " lost ResultStore authority",
				NativeMemoryEngine.v2KnownAuthoritativeRevision());
		long[] stats = NativeMemoryEngine.v2CompactOwnerStats();
		assertNotNull(stats);
		assertEquals(8, stats.length);
		assertEquals(label + " has no compact owner", 1L, stats[0]);
		assertTrue(label + " produced no ordinary rows", stats[1] > 0L);
		assertEquals(label + " rebuilt legacy Candidate ordinary rows", 0L, stats[2]);
		assertEquals(NativeMemoryEngine.resultCount(), stats[3]);
	}

	private static void assertExpectedAliases(Map<Integer, Long> aliases) {
		for (int type : EXPECTED_ALIASES) {
			assertTrue("probe address is missing relative Auto alias type=" + type,
					aliases.containsKey(type));
			assertTrue(aliases.get(type) > 0L);
		}
	}

	private static void assertResultCursorOnly() {
		long[] stats = NativeMemoryEngine.v2KnownPagingStats();
		assertEquals(4, stats.length);
		assertTrue("ResultStore stage is not active", stats[0] != 0L);
		assertTrue("ResultCursor did not serve a page", stats[2] > 0L);
		assertEquals("page unexpectedly fell back to Candidate paging", 0L, stats[3]);
	}

	private static Map<Integer, Long> aliasesAt(long wantedAddress) {
		Map<Integer, Long> result = new HashMap<>();
		long expectedUnique = NativeMemoryEngine.resultCount();
		long offset = 0L;
		while (offset < expectedUnique) {
			assertTrue(offset <= Integer.MAX_VALUE);
			int limit = (int) Math.min(
					MemoryEngineContract.MAX_RESULT_PAGE_SIZE, expectedUnique - offset);
			long[] rows = NativeMemoryEngine.resultPage((int) offset, limit);
			assertNotNull(rows);
			assertTrue(rows.length > 0);
			int count = Math.toIntExact(rows[0]);
			assertTrue(count > 0);
			assertEquals(1 + count * MemoryEngineContract.RESULT_PAGE_STRIDE, rows.length);

			long previousAddress = Long.MIN_VALUE;
			long uniqueInPage = 0L;
			for (int index = 0; index < count; index++) {
				int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
				long id = rows[base];
				long address = rows[base + 1];
				int type = (int) rows[base + 3];
				if (address != previousAddress) {
					previousAddress = address;
					uniqueInPage++;
				}
				if (address == wantedAddress) result.put(type, id);
			}
			assertTrue(uniqueInPage > 0L && uniqueInPage <= limit);
			offset += uniqueInPage;
		}
		return result;
	}
}
