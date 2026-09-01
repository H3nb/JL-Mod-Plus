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
import android.os.SystemClock;

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
	private static volatile int managedProbe01, managedProbe02, managedProbe03, managedProbe04;
	private static volatile int managedProbe05, managedProbe06, managedProbe07, managedProbe08;
	private static volatile int managedProbe09, managedProbe10, managedProbe11, managedProbe12;
	private static volatile int managedProbe13, managedProbe14, managedProbe15, managedProbe16;
	private static volatile int managedProbe17, managedProbe18, managedProbe19, managedProbe20;

	private static void setManagedProbeSet(int value) {
		managedProbe01 = value;
		managedProbe02 = value;
		managedProbe03 = value;
		managedProbe04 = value;
		managedProbe05 = value;
		managedProbe06 = value;
		managedProbe07 = value;
		managedProbe08 = value;
		managedProbe09 = value;
		managedProbe10 = value;
		managedProbe11 = value;
		managedProbe12 = value;
		managedProbe13 = value;
		managedProbe14 = value;
		managedProbe15 = value;
		managedProbe16 = value;
		managedProbe17 = value;
		managedProbe18 = value;
		managedProbe19 = value;
		managedProbe20 = value;
	}

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
			NativeMemoryEngine.clearTarget();
		}
	}

	@Test
	public void autoKnownScanFindsOrderedManagedArtCandidates() {
		int pageSize = NativeMemoryTarget.pageSize();
		long[] runs = NativeMemoryTarget.collectResidentRuns(
				MemoryEngineContract.SCOPE_JAVA_FAST, 4096);
		assertNotNull(runs);
		assertTrue(MemoryEngineContract.isCompleteRunList(runs));

		long token = 0x4A4C4155544F5445L;
		managedProbe = MANAGED_PROBE_A;
		try {
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(
							Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startKnown(
							MemoryEngineContract.TYPE_AUTO,
							MemoryEngineContract.PREDICATE_EQUAL,
							Integer.toString(MANAGED_PROBE_A), ""));
			assertTrue("Auto scan did not find the managed probe",
					NativeMemoryEngine.resultCount() > 0L);

			long[] page = NativeMemoryEngine.resultPage(0, 100);
			assertNotNull(page);
			int count = Math.toIntExact(page[0]);
			assertTrue(count > 0);
			long previousAddress = -1L;
			for (int index = 0; index < count; index++) {
				int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
				long address = page[base + 1];
				int type = (int) page[base + 3];
				assertTrue(address > 0L);
				assertTrue(MemoryEngineContract.isCandidateType(type));
				assertTrue(address >= previousAddress);
				previousAddress = address;
			}
		} finally {
			managedProbe = 0;
			NativeMemoryEngine.clearTarget();
		}
	}

	@Test
	public void autoUnknownRefineFindsChangedManagedArtCandidate() {
		int pageSize = NativeMemoryTarget.pageSize();
		long[] runs = NativeMemoryTarget.collectResidentRuns(
				MemoryEngineContract.SCOPE_JAVA_FAST, 4096);
		assertNotNull(runs);
		assertTrue(MemoryEngineContract.isCompleteRunList(runs));

		long token = 0x4A4C4155544F554EL;
		managedProbe = MANAGED_PROBE_A;
		try {
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(
							Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startUnknown(MemoryEngineContract.TYPE_AUTO));

			managedProbe = MANAGED_PROBE_B;
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineRelative(
							MemoryEngineContract.PREDICATE_CHANGED,
							MemoryEngineContract.COMPARE_PREVIOUS, "", ""));
			assertTrue("Auto Unknown refine did not find changed values",
					NativeMemoryEngine.resultCount() > 0L);
		} finally {
			managedProbe = 0;
			NativeMemoryEngine.clearTarget();
		}
	}

	@Test
	public void passiveRefreshDoesNotReplaceNextScanBaseline() {
		int pageSize = NativeMemoryTarget.pageSize();
		long[] runs = NativeMemoryTarget.collectResidentRuns(
				MemoryEngineContract.SCOPE_JAVA_FAST, 4096);
		assertNotNull(runs);
		assertTrue(MemoryEngineContract.isCompleteRunList(runs));

		long token = 0x4A4C4C4956455445L;
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

			long[] page = NativeMemoryEngine.resultPage(0, 100);
			assertNotNull(page);
			int count = Math.toIntExact(page[0]);
			assertTrue(count > 0);
			long[] ids = new long[count];
			for (int index = 0; index < count; index++) {
				ids[index] = page[1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE];
			}

			managedProbe = MANAGED_PROBE_B;
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refresh(ids, false));
			managedProbe = MANAGED_PROBE_A;
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineRelative(
							MemoryEngineContract.PREDICATE_UNCHANGED,
							MemoryEngineContract.COMPARE_PREVIOUS, "", ""));
			assertTrue("passive live refresh replaced the scan baseline",
					NativeMemoryEngine.resultCount() > 0L);
		} finally {
			managedProbe = 0;
			NativeMemoryEngine.clearTarget();
		}
	}

	@Test
	public void smallCandidateRefineDoesNotRescanTheWholeHeap() {
		int pageSize = NativeMemoryTarget.pageSize();
		long[] runs = NativeMemoryTarget.collectResidentRuns(
				MemoryEngineContract.SCOPE_JAVA_FAST, 4096);
		assertNotNull(runs);
		assertTrue(MemoryEngineContract.isCompleteRunList(runs));

		long token = 0x4A4C464153545245L;
		setManagedProbeSet(MANAGED_PROBE_A);
		try {
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(
							Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startKnown(
							MemoryEngineContract.TYPE_INT,
							MemoryEngineContract.PREDICATE_EQUAL,
							Integer.toString(MANAGED_PROBE_A), ""));
			long candidateCount = NativeMemoryEngine.resultCount();
			assertTrue("managed probe set was not found", candidateCount >= 20L);
			assertTrue("probe value unexpectedly selected the large-set refine path",
					candidateCount <= 4096L);

			setManagedProbeSet(MANAGED_PROBE_B);
			long started = SystemClock.elapsedRealtimeNanos();
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineKnown(
							MemoryEngineContract.PREDICATE_EQUAL,
							Integer.toString(MANAGED_PROBE_B), ""));
			long elapsedMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L;
			System.out.println("Memory refine candidates=" + candidateCount
					+ " elapsedMs=" + elapsedMillis);
			assertTrue("20-value refine took " + elapsedMillis + " ms",
					elapsedMillis < 1_000L);
			assertTrue(NativeMemoryEngine.resultCount() >= 20L);
		} finally {
			setManagedProbeSet(0);
			NativeMemoryEngine.clearTarget();
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
