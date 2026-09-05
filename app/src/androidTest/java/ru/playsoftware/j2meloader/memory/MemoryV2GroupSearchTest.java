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

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Device gate for candidate-free any-order (`:`) and in-order (`::`) Group Search. */
@RunWith(AndroidJUnit4.class)
public class MemoryV2GroupSearchTest {
	private static final int A = 324478056;
	private static final int B = 610800471;
	private static final int C = 271136839;
	private static final int D = 1432778632;
	private static final int[] FIXTURE = {A, B, C, D, -A, -B, -C, -D};
	private static final int[] INT_TYPES = {
			MemoryEngineContract.TYPE_INT,
			MemoryEngineContract.TYPE_INT,
			MemoryEngineContract.TYPE_INT,
	};

	@Test
	public void orderedSyntaxChangesRelationAndStaysCandidateFree() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);
		long[] original = NativeMemoryTarget.readGroupProbe();
		assertNotNull(original);
		assertEquals(10, original.length);
		assertEquals(8L, original[1]);
		long probeAddress = original[0];
		int[] restore = new int[8];
		for (int i = 0; i < restore.length; i++) restore[i] = (int) original[i + 2];

		long pageStart = probeAddress - Math.floorMod(probeAddress, (long) pageSize);
		long fixtureEnd = probeAddress + FIXTURE.length * (long) Integer.BYTES;
		long pageEnd = fixtureEnd + (pageSize - Math.floorMod(fixtureEnd, (long) pageSize)) % pageSize;
		if (pageEnd == pageStart) pageEnd += pageSize;
		long[] runs = {1L, 0L, pageStart, pageEnd};
		long token = 0x4A4C47524F555032L;

		try {
			assertTrue(NativeMemoryTarget.writeGroupProbe(FIXTURE));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));

			// Existing any-order semantics use a symmetric window around term 0. Therefore the
			// reversed C,B,A relation is valid even though B and A live at lower addresses.
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startGroup(
							INT_TYPES,
							new String[]{Integer.toString(C), Integer.toString(B), Integer.toString(A)},
							16));
			Set<Long> anyOrder = resultAddresses();
			assertTrue(anyOrder.contains(probeAddress));
			assertTrue(anyOrder.contains(probeAddress + 4L));
			assertTrue(anyOrder.contains(probeAddress + 8L));
			assertCompactCandidateFree();

			// Negative distance is the internal ABI encoding produced by `::16`. All terms must
			// now occur at strictly increasing addresses from the first-term anchor.
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startGroup(
							INT_TYPES,
							new String[]{Integer.toString(A), Integer.toString(B), Integer.toString(C)},
							-16));
			Set<Long> orderedForward = resultAddresses();
			assertTrue(orderedForward.contains(probeAddress));
			assertTrue(orderedForward.contains(probeAddress + 4L));
			assertTrue(orderedForward.contains(probeAddress + 8L));
			assertCompactCandidateFree();

			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startGroup(
							INT_TYPES,
							new String[]{Integer.toString(C), Integer.toString(B), Integer.toString(A)},
							-16));
			Set<Long> orderedReverse = resultAddresses();
			assertFalse("ordered reverse group unexpectedly retained the fixture anchor",
					orderedReverse.contains(probeAddress + 8L));
			assertCompactCandidateFree();
		} finally {
			NativeMemoryTarget.writeGroupProbe(restore);
			NativeMemoryEngine.clearTarget();
		}
	}

	private static void assertCompactCandidateFree() {
		long[] stats = NativeMemoryEngine.v2CompactOwnerStats();
		assertNotNull(stats);
		assertEquals(8, stats.length);
		assertEquals("Group Search did not publish compact authority", 1L, stats[0]);
		assertEquals("Group Search rebuilt the legacy Candidate database", 0L, stats[2]);
		assertEquals(NativeMemoryEngine.resultCount(), stats[3]);
	}

	private static Set<Long> resultAddresses() {
		Set<Long> addresses = new HashSet<>();
		long uniqueCount = NativeMemoryEngine.resultCount();
		long offset = 0L;
		while (offset < uniqueCount) {
			assertTrue(offset <= Integer.MAX_VALUE);
			int limit = (int) Math.min(
					MemoryEngineContract.MAX_RESULT_PAGE_SIZE, uniqueCount - offset);
			long[] page = NativeMemoryEngine.resultPage((int) offset, limit);
			assertNotNull(page);
			assertTrue(page.length > 0);
			int typedCount = Math.toIntExact(page[0]);
			assertTrue(typedCount > 0);
			assertEquals(1 + typedCount * MemoryEngineContract.RESULT_PAGE_STRIDE, page.length);
			long previous = Long.MIN_VALUE;
			int uniqueInPage = 0;
			for (int index = 0; index < typedCount; index++) {
				int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
				long address = page[base + 1];
				addresses.add(address);
				if (address != previous) {
					previous = address;
					uniqueInPage++;
				}
			}
			assertTrue(uniqueInPage > 0);
			offset += uniqueInPage;
		}
		return addresses;
	}
}
