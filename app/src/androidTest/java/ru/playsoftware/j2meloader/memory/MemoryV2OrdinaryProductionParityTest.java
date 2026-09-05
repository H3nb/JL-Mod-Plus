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

/** Production gate proving Known and Unknown-relative ordinary results are Candidate-free. */
@RunWith(AndroidJUnit4.class)
public class MemoryV2OrdinaryProductionParityTest {
	@Test
	public void knownAndUnknownRelativeUseCompactOwnerWithoutCandidateMirror() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);
		long[] originalProbe = NativeMemoryTarget.readProbe();
		assertNotNull(originalProbe);
		assertEquals(2, originalProbe.length);
		long probeAddress = originalProbe[0];
		long pageStart = probeAddress - Math.floorMod(probeAddress, (long) pageSize);
		long[] runs = {1L, 0L, pageStart, pageStart + pageSize};
		long token = 0x4A4C4F5244494E41L;

		try {
			NativeMemoryTarget.writeProbe(1L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startKnown(
							MemoryEngineContract.TYPE_AUTO,
							MemoryEngineContract.PREDICATE_EQUAL, "1", ""));
			assertCompactOwner("Auto Known");

			NativeMemoryEngine.clearSearch();
			NativeMemoryTarget.writeProbe(1L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startUnknown(MemoryEngineContract.TYPE_AUTO));
			NativeMemoryTarget.writeProbe(2L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineRelative(
							MemoryEngineContract.PREDICATE_INCREASED,
							MemoryEngineContract.COMPARE_PREVIOUS, "", ""));
			assertCompactOwner("Unknown relative");
		} finally {
			NativeMemoryTarget.writeProbe(originalProbe[1]);
			NativeMemoryEngine.clearTarget();
		}
	}

	private static void assertCompactOwner(String label) {
		assertTrue(label + " revision must remain ResultStore-authoritative",
				NativeMemoryEngine.v2KnownAuthoritativeRevision());
		long[] stats = NativeMemoryEngine.v2CompactOwnerStats();
		assertNotNull(stats);
		assertEquals(8, stats.length);
		assertEquals(label + " compact owner missing", 1L, stats[0]);
		assertTrue(label + " should produce at least one typed result", stats[1] > 0L);
		assertEquals(label + " still retains the Candidate ordinary database", 0L, stats[2]);
		assertEquals(label + " unique count diverged",
				NativeMemoryEngine.resultCount(), stats[3]);
		assertTrue(label + " compact revision retained accounting is invalid", stats[4] > 0L);
	}
}
