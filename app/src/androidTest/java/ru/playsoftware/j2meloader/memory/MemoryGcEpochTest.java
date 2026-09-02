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
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MemoryGcEpochTest {
	@Test
	public void gcCountParserFailsClosed() {
		assertEquals(MemoryEngineContract.GC_COUNT_UNKNOWN,
				MemoryTargetBridgeService.parseGcCount(null));
		assertEquals(MemoryEngineContract.GC_COUNT_UNKNOWN,
				MemoryTargetBridgeService.parseGcCount(""));
		assertEquals(MemoryEngineContract.GC_COUNT_UNKNOWN,
				MemoryTargetBridgeService.parseGcCount("-1"));
		assertEquals(MemoryEngineContract.GC_COUNT_UNKNOWN,
				MemoryTargetBridgeService.parseGcCount("not-a-number"));
		assertEquals(0L, MemoryTargetBridgeService.parseGcCount("0"));
		assertEquals(1234L, MemoryTargetBridgeService.parseGcCount("1234"));
	}

	@Test
	public void runtimeGcCountIsNonNegativeOrUnknown() {
		long gcCount = MemoryTargetBridgeService.readGcCount();
		assertTrue(gcCount == MemoryEngineContract.GC_COUNT_UNKNOWN || gcCount >= 0L);
	}
}
