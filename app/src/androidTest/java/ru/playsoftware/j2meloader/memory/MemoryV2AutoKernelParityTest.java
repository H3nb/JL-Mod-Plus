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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Device differential gate for the fused multi-plane Auto ResultStore first-scan kernel. */
@RunWith(AndroidJUnit4.class)
public class MemoryV2AutoKernelParityTest {
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
	public void fusedAutoKernelMatchesCurrentProductionAutoForEveryKnownPredicate() {
		int pageSize = NativeMemoryTarget.pageSize();
		assertTrue(pageSize > 0);
		long[] probe = NativeMemoryTarget.readProbe();
		assertNotNull(probe);
		assertEquals(2, probe.length);
		long pageStart = probe[0] - Math.floorMod(probe[0], (long) pageSize);
		long[] runs = {1L, 0L, pageStart, pageStart + pageSize};
		long token = 0x4A4C56324155544FL;

		try {
			assertEquals(MemoryEngineContract.RESULT_OK,
					NativeMemoryEngine.configureTarget(Process.myPid(), pageSize, token, runs));
			for (int predicate : KNOWN_PREDICATES) {
				String first = "0";
				String second = predicate == MemoryEngineContract.PREDICATE_BETWEEN ? "1" : "";
				long[] firstBits = new long[EXPLICIT_TYPES.length];
				long[] secondBits = new long[EXPLICIT_TYPES.length];
				for (int index = 0; index < EXPLICIT_TYPES.length; index++) {
					long[] plan = NativeMemoryEngine.canonicalKnownPlan(
							EXPLICIT_TYPES[index], predicate, first, second);
					assertNotNull("canonical Auto component rejected type=" + EXPLICIT_TYPES[index]
							+ " predicate=" + predicate, plan);
					assertEquals(4, plan.length);
					firstBits[index] = plan[2];
					secondBits[index] = plan[3];
				}

				assertEquals("current production Auto failed predicate=" + predicate,
						MemoryEngineContract.RESULT_OK,
						NativeMemoryEngine.startKnown(
								MemoryEngineContract.TYPE_AUTO, predicate, first, second));
				// This test intentionally runs before the ownership cutover: production remains the
				// reference and the new multi-plane kernel is still independent diagnostics here.
				assertFalse(NativeMemoryEngine.v2KnownAuthoritativeRevision());
				long productionUnique = NativeMemoryEngine.resultCount();
				assertTrue(productionUnique >= 0L);
				long[] production = productionAutoSummary(productionUnique);

				long[] shadow = v2AutoKernelProbe(
						Process.myPid(), runs, predicate, EXPLICIT_TYPES,
						firstBits, secondBits);
				assertNotNull(shadow);
				assertEquals(7, shadow.length);
				assertEquals("fused Auto ResultStore kernel failed predicate=" + predicate,
						MemoryEngineContract.RESULT_OK, (int) shadow[0]);
				assertEquals("fused Auto kernel did not scan exactly the bounded resident page",
						pageSize, shadow[1]);
				assertEquals("typed alias count mismatch predicate=" + predicate,
						production[0], shadow[2]);
				assertEquals("unique address count mismatch predicate=" + predicate,
						productionUnique, shadow[3]);
				assertEquals("production page unique count disagreed with resultCount",
						productionUnique, production[1]);
				assertEquals("ordered typed-alias fingerprint mismatch predicate=" + predicate,
						production[2], shadow[4]);
				assertTrue("non-empty Auto result retained no ResultStore blocks",
						productionUnique == 0L || shadow[5] > 0L);
				assertTrue("ResultStore retained byte accounting is invalid", shadow[6] > 0L);

				NativeMemoryEngine.clearSearch();
			}
		} finally {
			NativeMemoryEngine.clearTarget();
		}
	}

	/** Returns [typedRows, uniqueAddresses, orderedTypedFingerprint]. */
	private static long[] productionAutoSummary(long expectedUnique) {
		long typedRows = 0L;
		long uniqueRows = 0L;
		long fingerprint = FNV_OFFSET_BASIS;
		if (expectedUnique == 0L) {
			long[] empty = NativeMemoryEngine.resultPage(0, 1);
			assertNotNull(empty);
			assertEquals(1, empty.length);
			assertEquals(0L, empty[0]);
			return new long[]{0L, 0L, fingerprint};
		}

		long offset = 0L;
		while (offset < expectedUnique) {
			assertTrue(offset <= Integer.MAX_VALUE);
			int limit = (int) Math.min(
					MemoryEngineContract.MAX_RESULT_PAGE_SIZE, expectedUnique - offset);
			long[] rows = NativeMemoryEngine.resultPage((int) offset, limit);
			assertNotNull(rows);
			assertTrue(rows.length > 0);
			int count = Math.toIntExact(rows[0]);
			assertTrue(count > 0);
			assertEquals(1 + count * MemoryEngineContract.RESULT_PAGE_STRIDE, rows.length);

			long previousAddress = 0L;
			long pageUnique = 0L;
			for (int index = 0; index < count; index++) {
				int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
				long address = rows[base + 1];
				int valueType = (int) rows[base + 3];
				assertTrue(address > 0L);
				int planeTag = fingerprintPlaneTag(valueType);
				assertTrue(planeTag != 0);
				if (index == 0 || address != previousAddress) {
					pageUnique++;
					previousAddress = address;
				}
				fingerprint ^= address;
				fingerprint *= FNV_PRIME;
				fingerprint ^= planeTag;
				fingerprint *= FNV_PRIME;
			}
			assertTrue(pageUnique > 0L && pageUnique <= limit);
			typedRows += count;
			uniqueRows += pageUnique;
			offset += pageUnique;
		}
		assertEquals(expectedUnique, uniqueRows);
		return new long[]{typedRows, uniqueRows, fingerprint};
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

	private static native long[] v2AutoKernelProbe(
			int pid, long[] runs, int predicate, int[] valueTypes,
			long[] firstBits, long[] secondBits);
}
