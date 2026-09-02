/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class MemoryCandidateBindingCacheTest {
	@Test
	public void recordsEveryTypedAliasFromDisplayedResultPage() {
		MemoryCandidateBindingCache cache = new MemoryCandidateBindingCache();
		long[] rows = page(
				row(11L, 0x1000L, 4, 0),
				row(12L, 0x1000L, 6, 0),
				row(13L, 0x2000L, 4, 0));

		assertTrue(cache.recordPage(rows, false, 300));
		Map<Long, MemoryCandidateBindingCache.Binding> snapshot =
				cache.snapshot(new long[]{11L, 12L, 13L});

		assertNotNull(snapshot);
		assertEquals(3, snapshot.size());
		assertEquals(300, snapshot.get(12L).resultPageOffset);
		assertEquals(0x1000L, snapshot.get(12L).address);
	}

	@Test
	public void detectsAddressChangeEvenWithoutGcEvidence() {
		MemoryCandidateBindingCache cache = new MemoryCandidateBindingCache();
		cache.recordPage(page(row(21L, 0x1000L, 4, 0)), false, 0);
		Map<Long, MemoryCandidateBindingCache.Binding> before =
				cache.snapshot(new long[]{21L});
		LinkedHashMap<Long, MemoryCandidateBindingCache.Binding> after = new LinkedHashMap<>();
		MemoryCandidateBindingCache.collectPage(
				page(row(21L, 0x5000L, 4, 1)), false, 0, after);

		assertEquals(MemoryCandidateBindingCache.COMPARE_MOVED,
				cache.compareAndRecord(before, after));
		assertEquals(0x5000L, cache.snapshot(new long[]{21L}).get(21L).address);
	}

	@Test
	public void relocationCounterIncreaseAlsoForcesRetry() {
		MemoryCandidateBindingCache cache = new MemoryCandidateBindingCache();
		cache.recordPage(page(row(31L, 0x3000L, 4, 1)), true, 0);
		Map<Long, MemoryCandidateBindingCache.Binding> before =
				cache.snapshot(new long[]{31L});
		LinkedHashMap<Long, MemoryCandidateBindingCache.Binding> after = new LinkedHashMap<>();
		MemoryCandidateBindingCache.collectPage(
				page(row(31L, 0x3000L, 4, 2)), true, 0, after);

		assertEquals(MemoryCandidateBindingCache.COMPARE_MOVED,
				cache.compareAndRecord(before, after));
	}

	@Test
	public void missingBeforeOrAfterBindingFailsClosed() {
		MemoryCandidateBindingCache cache = new MemoryCandidateBindingCache(1);
		cache.recordPage(page(row(41L, 0x4000L, 4, 0)), false, 0);
		cache.recordPage(page(row(42L, 0x5000L, 4, 0)), false, 100);
		assertNull(cache.snapshot(new long[]{41L}));

		Map<Long, MemoryCandidateBindingCache.Binding> before =
				cache.snapshot(new long[]{42L});
		assertEquals(MemoryCandidateBindingCache.COMPARE_UNKNOWN,
				cache.compareAndRecord(before, new LinkedHashMap<>()));
	}

	private static long[] row(long id, long address, int type, int relocations) {
		return new long[]{id, address, address, type, 0, relocations, 1, 1, 1};
	}

	private static long[] page(long[]... rows) {
		long[] output = new long[1 + rows.length * MemoryEngineContract.RESULT_PAGE_STRIDE];
		output[0] = rows.length;
		for (int index = 0; index < rows.length; index++) {
			System.arraycopy(rows[index], 0, output,
					1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE,
					MemoryEngineContract.RESULT_PAGE_STRIDE);
		}
		return output;
	}
}
