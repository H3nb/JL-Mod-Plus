/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Deterministic gate for ResultAliasCursor + compact ordinary-result metadata alignment. */
@RunWith(AndroidJUnit4.class)
public class MemoryV2OrdinaryResultStoreTest {
	@Test
	public void typedAliasPaginationAndDeepSeekKeepCompactOrdinalStable() {
		// Initialize jlmem through its normal production owner before calling this test-only JNI probe.
		assertNotNull(NativeMemoryEngine.v2RevisionCatalogStats());

		long[] result = nativeProbe();
		assertNotNull(result);
		assertEquals(10, result.length);
		assertEquals(MemoryEngineContract.RESULT_OK, (int) result[0]);
		assertEquals("typed alias count", 7L, result[1]);
		assertEquals("unique address count", 3L, result[2]);
		assertEquals("compact ordinary record size", 40L, result[3]);
		assertTrue("retained accounting must include all compact records", result[4] >= 7L * 40L);
		assertEquals("two-row paging should cross four typed pages", 4L, result[5]);
		assertEquals("identity-valid bitset count", 4L, result[6]);
		assertTrue("ordered address/type fingerprint should be non-zero", result[7] != 0L);
		assertEquals("unique-address offset 1 must skip three typed aliases", 3L, result[8]);
		assertEquals("deep seek must begin at Short alias",
				MemoryEngineContract.TYPE_SHORT, (int) result[9]);
	}

	private static native long[] nativeProbe();
}
