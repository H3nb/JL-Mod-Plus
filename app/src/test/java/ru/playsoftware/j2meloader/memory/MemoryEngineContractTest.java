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
}
