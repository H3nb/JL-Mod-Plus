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

import android.os.Process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression coverage for the expanded fragmented-ART resident-run contract. */
@RunWith(AndroidJUnit4.class)
public class MemoryResidentRunCeilingTest {
	@Test
	public void productionTargetConfigurationAcceptsTheFullRunContract() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);
		int runCount = MemoryEngineContract.MAX_RESIDENT_RUNS;
		long[] runs = new long[2 + runCount * 2];
		runs[0] = runCount;
		runs[1] = 0L;

		// Configuration validates shape/alignment/order only. Deliberately use sparse synthetic page
		// bounds so this test exercises the 16,384-run production boundary without requiring a device
		// to actually own thousands of VMAs. The scanner is never started against these ranges.
		long firstPage = 32L * pageSize;
		for (int index = 0; index < runCount; index++) {
			long start = firstPage + (long) index * 2L * pageSize;
			runs[2 + index * 2] = start;
			runs[3 + index * 2] = start + pageSize;
		}
		assertTrue(MemoryEngineContract.isCompleteRunList(runs));

		long token = 0x4A4C52554E31364BL;
		try {
			assertEquals("production native configuration regressed to the legacy 4,096-run ceiling",
					MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));
		} finally {
			NativeMemoryEngine.clearTarget();
		}
	}
}
