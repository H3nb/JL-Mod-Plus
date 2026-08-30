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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MemoryEngineContractTest {
	@Test
	public void completeRunListRequiresExactUntruncatedShape() {
		assertTrue(MemoryEngineContract.isCompleteRunList(
				new long[]{2L, 0L, 0x1000L, 0x2000L, 0x3000L, 0x5000L}));
		assertFalse(MemoryEngineContract.isCompleteRunList(
				new long[]{2L, 1L, 0x1000L, 0x2000L, 0x3000L, 0x5000L}));
		assertFalse(MemoryEngineContract.isCompleteRunList(
				new long[]{2L, 0L, 0x1000L, 0x2000L}));
		assertFalse(MemoryEngineContract.isCompleteRunList(
				new long[]{1L, 0L, 0x1000L, 0x2000L, 0x3000L, 0x4000L}));
	}

	@Test
	public void contractRejectsUnknownEnums() {
		assertTrue(MemoryEngineContract.isScope(MemoryEngineContract.SCOPE_JAVA_FAST));
		assertTrue(MemoryEngineContract.isValueType(MemoryEngineContract.TYPE_AUTO));
		assertTrue(MemoryEngineContract.isCandidateType(MemoryEngineContract.TYPE_DOUBLE));
		assertFalse(MemoryEngineContract.isScope(-1));
		assertFalse(MemoryEngineContract.isValueType(8));
		assertFalse(MemoryEngineContract.isCandidateType(MemoryEngineContract.TYPE_AUTO));
	}

	@Test
	public void candidatePagesAndWriteLimitsRemainBounded() {
		assertEquals(9, MemoryEngineContract.RESULT_PAGE_STRIDE);
		assertEquals(100, MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertEquals(32, MemoryEngineContract.MAX_MULTI_WRITE);
		assertEquals(32, MemoryEngineContract.MAX_FREEZE_RECORDS);
		assertEquals(8, MemoryEngineContract.MAX_GROUP_VALUES);
		assertEquals(8, MemoryEngineContract.MAX_SEARCH_HISTORY);
		assertEquals(128, MemoryEngineContract.DEFAULT_INSPECT_RADIUS);
		assertEquals(256, MemoryEngineContract.MAX_INSPECT_RADIUS);
		assertEquals(256, MemoryEngineContract.DEFAULT_NEARBY_RADIUS);
		assertEquals(4096, MemoryEngineContract.MAX_NEARBY_RADIUS);
		assertEquals(520, MemoryEngineContract.MAX_INSPECT_BYTES);
		assertTrue(MemoryEngineContract.isInspectRadius(128));
		assertFalse(MemoryEngineContract.isInspectRadius(0));
		assertFalse(MemoryEngineContract.isInspectRadius(257));
		assertTrue(MemoryEngineContract.isNearbyRadius(256));
		assertFalse(MemoryEngineContract.isNearbyRadius(0));
		assertFalse(MemoryEngineContract.isNearbyRadius(4097));
	}
}
