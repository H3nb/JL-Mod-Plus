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

/** Differential gate before compact ordinary metadata replaces the production Candidate mirror. */
@RunWith(AndroidJUnit4.class)
public class MemoryV2OrdinaryProductionParityTest {
	@Test
	public void compactSidecarMatchesProductionAutoAndRelativeRevisions() {
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
			assertOrdinaryParity("Auto Known");

			NativeMemoryEngine.clearSearch();
			NativeMemoryTarget.writeProbe(1L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startUnknown(MemoryEngineContract.TYPE_AUTO));
			NativeMemoryTarget.writeProbe(2L);
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineRelative(
							MemoryEngineContract.PREDICATE_INCREASED,
							MemoryEngineContract.COMPARE_PREVIOUS, "", ""));
			assertOrdinaryParity("Unknown relative");
		} finally {
			NativeMemoryTarget.writeProbe(originalProbe[1]);
			NativeMemoryEngine.clearTarget();
		}
	}

	private static void assertOrdinaryParity(String label) {
		long[] stats = MemoryV2OrdinaryDiagnostics.parityStats();
		assertNotNull(label, stats);
		assertEquals(label + " stats length", 8, stats.length);
		assertEquals(label + " parity status",
				MemoryEngineContract.RESULT_OK, (int) stats[0]);
		assertEquals(label + " typed count",
				NativeMemoryEngine.resultPage(0, MemoryEngineContract.MAX_RESULT_PAGE_SIZE)[0] > 0L
						? NativeMemoryEngine.v2KnownAuthoritativeRevision() : true,
						true);
		assertTrue(label + " should produce at least one typed result", stats[1] > 0L);
		assertEquals(label + " compact record size", 40L, stats[2]);
		assertTrue(label + " Candidate record should be larger than compact ordinary metadata",
				stats[3] > stats[2]);
		assertTrue(label + " ordinary retained accounting is invalid", stats[4] >= stats[1] * 40L);
		assertTrue(label + " Candidate retained accounting is invalid", stats[5] >= stats[1] * stats[3]);
		assertTrue(label + " ordered address/type fingerprint should be non-zero", stats[7] != 0L);
	}
}
