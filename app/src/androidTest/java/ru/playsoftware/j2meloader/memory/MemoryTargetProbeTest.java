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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MemoryTargetProbeTest {
	private static final int MANAGED_PROBE_A = 0x5A17C0DE;
	private static final int MANAGED_PROBE_B = 0x31C0FFEE;
	private static volatile int managedProbe;

	@Test
	public void thoroughProbeReturnsAlignedCompleteResidentRuns() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);

		long[] runs = NativeMemoryTarget.collectResidentRuns(
				MemoryEngineContract.SCOPE_JAVA_THOROUGH, 4096);
		assertNotNull(runs);
		assertTrue(MemoryEngineContract.isCompleteRunList(runs));
		for (int index = 2; index < runs.length; index += 2) {
			assertTrue(runs[index] > 0L);
			assertTrue(runs[index + 1] > runs[index]);
			assertEquals(0L, runs[index] % pageSize);
			assertEquals(0L, runs[index + 1] % pageSize);
			if (index > 2) {
				assertTrue(runs[index] >= runs[index - 1]);
			}
		}
	}

	@Test
	public void readCapabilityProbeVerifiesExpectedBits() {
		long[] probe = NativeMemoryTarget.readProbe();
		assertNotNull(probe);
		assertEquals(2, probe.length);
		assertTrue(NativeMemoryEngine.canReadTarget(Process.myPid(), probe[0], probe[1]));
		assertTrue(!NativeMemoryEngine.canReadTarget(Process.myPid(), probe[0], probe[1] ^ 1L));
	}

	@Test
	public void nativeEngineFindsAndRefinesManagedArtValue() {
		int pageSize = NativeMemoryTarget.pageSize();
		long[] runs = NativeMemoryTarget.collectResidentRuns(
				MemoryEngineContract.SCOPE_JAVA_FAST, 4096);
		assertNotNull(runs);
		assertTrue(MemoryEngineContract.isCompleteRunList(runs));

		long token = 0x4A4C4D454D544553L;
		managedProbe = MANAGED_PROBE_A;
		try {
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(
							Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startKnown(
							MemoryEngineContract.TYPE_INT,
							MemoryEngineContract.PREDICATE_EQUAL,
							Integer.toString(MANAGED_PROBE_A), ""));
			assertTrue("managed ART probe was not inside the selected Java ranges",
					NativeMemoryEngine.resultCount() > 0L);

			managedProbe = MANAGED_PROBE_B;
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineKnown(
							MemoryEngineContract.PREDICATE_EQUAL,
							Integer.toString(MANAGED_PROBE_B), ""));
			assertTrue("managed ART probe did not survive direct refine",
					NativeMemoryEngine.resultCount() > 0L);
		} finally {
			managedProbe = 0;
			NativeMemoryEngine.clear();
		}
	}

	@Test
	public void runtimeTokenCannotBeClosedByAnOlderOwner() {
		long[] endedToken = {0L};
		MemoryRuntimeSession.Listener listener = token -> endedToken[0] = token;
		MemoryRuntimeSession.addListener(listener);
		try {
			long token = MemoryRuntimeSession.start();
			assertTrue(token != 0L);
			MemoryRuntimeSession.close(token ^ 1L);
			assertTrue(MemoryRuntimeSession.isActive(token));
			assertEquals(0L, endedToken[0]);
			MemoryRuntimeSession.close(token);
			assertEquals(0L, MemoryRuntimeSession.currentToken());
			assertEquals(token, endedToken[0]);
		} finally {
			MemoryRuntimeSession.close(MemoryRuntimeSession.currentToken());
			MemoryRuntimeSession.removeListener(listener);
		}
	}
}
