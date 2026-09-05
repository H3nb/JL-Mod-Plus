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

/**
 * Device gate for the production Auto path after the fused first-scan cutover. The mutable native
 * probe is deliberately outside ART so changing 1 -> 2 cannot itself trigger a managed GC or move
 * the observed address. One little-endian aligned uint64_t simultaneously represents Byte, Short,
 * Char, Int and Long numeric values, exercising multi-plane COW membership at one raw address.
 */
@RunWith(AndroidJUnit4.class)
public class MemoryV2AutoAuthoritativeRefineTest {
	private static final int[] EXPECTED_INTEGER_ALIASES = {
			MemoryEngineContract.TYPE_INT,
			MemoryEngineContract.TYPE_LONG,
			MemoryEngineContract.TYPE_SHORT,
			MemoryEngineContract.TYPE_CHAR,
			MemoryEngineContract.TYPE_BYTE,
	};

	@Test
	public void autoRefineClearsBitmapCowAndUndoRestagesResultCursor() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);
		long[] originalProbe = NativeMemoryTarget.readProbe();
		assertNotNull(originalProbe);
		assertEquals(2, originalProbe.length);
		long probeAddress = originalProbe[0];
		long pageStart = probeAddress - Math.floorMod(probeAddress, (long) pageSize);
		long[] runs = {1L, 0L, pageStart, pageStart + pageSize};
		long token = 0x4A4C4155544F434FL;

		try {
			NativeMemoryTarget.writeProbe(1L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startKnown(
							MemoryEngineContract.TYPE_AUTO,
							MemoryEngineContract.PREDICATE_EQUAL,
							"1", ""));
			assertTrue(NativeMemoryEngine.v2KnownAuthoritativeRevision());

			Map<Integer, Long> firstIds = aliasesAt(probeAddress);
			assertExpectedIntegerAliases(firstIds);
			assertResultCursorOnly();

			NativeMemoryTarget.writeProbe(2L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineKnown(
							MemoryEngineContract.PREDICATE_EQUAL, "2", "", false));
			assertTrue(NativeMemoryEngine.v2KnownAuthoritativeRevision());
			Map<Integer, Long> secondIds = aliasesAt(probeAddress);
			assertExpectedIntegerAliases(secondIds);
			for (int type : EXPECTED_INTEGER_ALIASES) {
				assertEquals("CandidateId changed during ordinary Auto bitmap refine for type=" + type,
						firstIds.get(type), secondIds.get(type));
			}
			assertTrue("Auto refine did not retain an Undo revision",
					NativeMemoryEngine.historyDepth() > 0);
			assertResultCursorOnly();

			assertEquals(MemoryEngineContract.RESULT_OK, NativeMemoryEngine.undo());
			// Undo swaps the immutable SearchState. The first page must rebuild ResultStore metadata from
			// that committed revision, without reading target memory and without falling back to the old
			// Candidate pager even though the physical probe still contains 2.
			Map<Integer, Long> undoneIds = aliasesAt(probeAddress);
			assertExpectedIntegerAliases(undoneIds);
			for (int type : EXPECTED_INTEGER_ALIASES) {
				assertEquals("CandidateId changed after Undo restage for type=" + type,
						firstIds.get(type), undoneIds.get(type));
			}
			assertResultCursorOnly();
		} finally {
			NativeMemoryTarget.writeProbe(originalProbe[1]);
			NativeMemoryEngine.clearTarget();
		}
	}

	private static void assertExpectedIntegerAliases(Map<Integer, Long> aliases) {
		for (int type : EXPECTED_INTEGER_ALIASES) {
			assertTrue("probe address is missing Auto alias type=" + type,
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
				if (address == wantedAddress) {
					result.put(type, id);
				}
			}
			assertTrue(uniqueInPage > 0L && uniqueInPage <= limit);
			offset += uniqueInPage;
		}
		return result;
	}
}
