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

/**
 * Device-level differential coverage for the explicit-type ResultStore migration.
 *
 * <p>The test deliberately confines both engines to one stable resident page in this process.
 * This keeps every predicate bounded (including Byte/NotEqual) while still exercising the real
 * process_vm_readv path, authoritative production ResultStore scan, Candidate compatibility
 * materialization, cursor paging, and an independent v2 shadow scan on the device ABI. Full
 * Java-heap/GC movement remains covered separately by the managed ART tests and the PR physical
 * workload gate.</p>
 */
@RunWith(AndroidJUnit4.class)
public class MemoryV2KnownParityTest {
	private static final long FNV_OFFSET_BASIS = 1469598103934665603L;
	private static final long FNV_PRIME = 1099511628211L;

	private static final int[] EXPLICIT_TYPES = {
			MemoryEngineContract.TYPE_BYTE,
			MemoryEngineContract.TYPE_SHORT,
			MemoryEngineContract.TYPE_CHAR,
			MemoryEngineContract.TYPE_INT,
			MemoryEngineContract.TYPE_FLOAT,
			MemoryEngineContract.TYPE_LONG,
			MemoryEngineContract.TYPE_DOUBLE
	};

	private static final int[] KNOWN_PREDICATES = {
			MemoryEngineContract.PREDICATE_EQUAL,
			MemoryEngineContract.PREDICATE_NOT_EQUAL,
			MemoryEngineContract.PREDICATE_GREATER,
			MemoryEngineContract.PREDICATE_LESS,
			MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL,
			MemoryEngineContract.PREDICATE_LESS_OR_EQUAL,
			MemoryEngineContract.PREDICATE_BETWEEN
	};

	@Test
	public void explicitKnownAllPredicatesAreAuthoritativeAndMatchShadow() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);
		long[] probe = NativeMemoryTarget.readProbe();
		assertNotNull(probe);
		assertEquals(2, probe.length);

		long pageStart = probe[0] - Math.floorMod(probe[0], (long) pageSize);
		long[] runs = {1L, 0L, pageStart, pageStart + pageSize};
		long token = 0x4A4C563250415249L;
		try {
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));

			for (int valueType : EXPLICIT_TYPES) {
				for (int predicate : KNOWN_PREDICATES) {
					String first = "0";
					String second = predicate == MemoryEngineContract.PREDICATE_BETWEEN
							? "1" : "";
					assertPredicateParity(valueType, predicate, first, second);
					NativeMemoryEngine.clearSearch();
					long[] cleared = NativeMemoryEngine.v2KnownPagingStats();
					assertEquals(5, cleared.length);
					assertEquals("clearSearch retained a production v2 paging revision",
							0L, cleared[0]);
					assertEquals("clearSearch retained authoritative ownership",
							0L, cleared[4]);
				}
			}
		} finally {
			NativeMemoryEngine.clearTarget();
		}
	}

	@Test
	public void successfulRefineDemotesFirstScanOwnershipButFailurePreservesCurrentRevision() {
		int pageSize = NativeMemoryTarget.pageSize();
		long[] probe = NativeMemoryTarget.readProbe();
		assertNotNull(probe);
		assertEquals(2, probe.length);
		long pageStart = probe[0] - Math.floorMod(probe[0], (long) pageSize);
		long[] runs = {1L, 0L, pageStart, pageStart + pageSize};
		long token = 0x4A4C56324F574E52L;
		String value = Long.toString(probe[1]);
		try {
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.startKnown(
							MemoryEngineContract.TYPE_LONG,
							MemoryEngineContract.PREDICATE_EQUAL, value, ""));
			long[] first = NativeMemoryEngine.v2KnownPagingStats();
			assertEquals(5, first.length);
			assertEquals(1L, first[0]);
			assertEquals(1L, first[4]);

			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.refineKnown(
							MemoryEngineContract.PREDICATE_EQUAL, value, ""));
			long[] refined = NativeMemoryEngine.v2KnownPagingStats();
			assertEquals(5, refined.length);
			assertEquals("successful Candidate refine did not publish a verified ResultStore mirror",
					1L, refined[0]);
			assertEquals("bitmap refine is not authoritative yet",
					0L, refined[4]);

			long generation = refined[1];
			assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST,
					NativeMemoryEngine.refineKnown(999, value, ""));
			long[] afterFailure = NativeMemoryEngine.v2KnownPagingStats();
			assertEquals("failed refine discarded the previously committed paging revision",
					generation, afterFailure[1]);
			assertEquals(refined[0], afterFailure[0]);
			assertEquals(refined[4], afterFailure[4]);
		} finally {
			NativeMemoryEngine.clearTarget();
		}
	}

	private static void assertPredicateParity(int valueType, int predicate,
	                                          String first, String second) {
		long[] plan = NativeMemoryEngine.canonicalKnownPlan(
				valueType, predicate, first, second);
		assertNotNull("canonical Known plan was rejected for type=" + valueType
				+ " predicate=" + predicate, plan);
		assertEquals(4, plan.length);
		assertEquals(valueType, plan[0]);
		assertEquals(predicate, plan[1]);

		assertEquals("authoritative ResultStore Known search failed for type=" + valueType
				+ " predicate=" + predicate,
				MemoryEngineContract.RESULT_OK,
				NativeMemoryEngine.startKnown(valueType, predicate, first, second));
		long[] stageBeforePaging = NativeMemoryEngine.v2KnownPagingStats();
		assertEquals(5, stageBeforePaging.length);
		assertEquals("bounded explicit Known result did not publish ResultStore paging",
				1L, stageBeforePaging[0]);
		assertTrue("ResultStore revision did not publish a positive generation",
				stageBeforePaging[1] > 0L);
		assertEquals(0L, stageBeforePaging[2]);
		assertEquals(0L, stageBeforePaging[3]);
		assertEquals("explicit Known first scan was not marked ResultStore-authoritative",
				1L, stageBeforePaging[4]);

		long productionCount = NativeMemoryEngine.resultCount();
		assertTrue(productionCount >= 0L);
		long productionFingerprint = productionFingerprint(valueType, productionCount);
		long[] stageAfterPaging = NativeMemoryEngine.v2KnownPagingStats();
		assertEquals("pagination unexpectedly replaced the ResultStore revision",
				stageBeforePaging[1], stageAfterPaging[1]);
		assertEquals("authoritative first-scan paging unexpectedly fell back to Candidate",
				0L, stageAfterPaging[3]);
		assertTrue("authoritative ResultStore was never used to serve a result page",
				stageAfterPaging[2] > 0L);
		assertEquals(1L, stageAfterPaging[4]);

		long[] shadow = NativeMemoryEngine.v2ShadowKnown(
				valueType, predicate, plan[2], plan[3]);
		assertNotNull("independent v2 shadow returned null for type=" + valueType
				+ " predicate=" + predicate, shadow);
		assertEquals(9, shadow.length);
		assertEquals("independent v2 shadow failed for type=" + valueType
				+ " predicate=" + predicate,
				MemoryEngineContract.RESULT_OK, (int) shadow[0]);
		assertEquals("typed result count mismatch for type=" + valueType
				+ " predicate=" + predicate, productionCount, shadow[2]);
		assertEquals("unique result count mismatch for type=" + valueType
				+ " predicate=" + predicate, productionCount, shadow[3]);
		assertEquals("ordered address fingerprint mismatch for type=" + valueType
				+ " predicate=" + predicate, productionFingerprint, shadow[6]);
	}

	private static long productionFingerprint(int valueType, long expectedCount) {
		long fingerprint = FNV_OFFSET_BASIS;
		long offset = 0L;
		int planeTag = fingerprintPlaneTag(valueType);
		assertTrue(planeTag != 0);
		if (expectedCount == 0L) {
			long[] empty = NativeMemoryEngine.resultPage(0, 1);
			assertNotNull(empty);
			assertEquals(1, empty.length);
			assertEquals(0L, empty[0]);
			return fingerprint;
		}
		while (offset < expectedCount) {
			assertTrue("production parity result exceeds offset IPC range",
					offset <= Integer.MAX_VALUE);
			int limit = (int) Math.min(
					MemoryEngineContract.MAX_RESULT_PAGE_SIZE, expectedCount - offset);
			long[] rows = NativeMemoryEngine.resultPage((int) offset, limit);
			assertNotNull(rows);
			assertTrue(rows.length > 0);
			int count = Math.toIntExact(rows[0]);
			assertTrue(count > 0 && count <= limit);
			assertEquals(1 + count * MemoryEngineContract.RESULT_PAGE_STRIDE, rows.length);
			for (int index = 0; index < count; index++) {
				int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
				long address = rows[base + 1];
				assertTrue(address > 0L);
				assertEquals(valueType, (int) rows[base + 3]);
				fingerprint ^= address;
				fingerprint *= FNV_PRIME;
				fingerprint ^= planeTag;
				fingerprint *= FNV_PRIME;
			}
			offset += count;
		}
		assertEquals(expectedCount, offset);
		return fingerprint;
	}

	private static int fingerprintPlaneTag(int valueType) {
		return switch (valueType) {
			case MemoryEngineContract.TYPE_BYTE -> 1;
			case MemoryEngineContract.TYPE_SHORT -> 2;
			case MemoryEngineContract.TYPE_CHAR -> 3;
			case MemoryEngineContract.TYPE_INT -> 4;
			case MemoryEngineContract.TYPE_FLOAT -> 5;
			case MemoryEngineContract.TYPE_LONG -> 6;
			case MemoryEngineContract.TYPE_DOUBLE -> 7;
			default -> 0;
		};
	}
}
