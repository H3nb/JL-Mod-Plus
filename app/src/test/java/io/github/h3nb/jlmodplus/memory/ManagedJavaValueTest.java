/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ManagedJavaValueTest {
	@Test
	public void parsesAndFormatsEverySupportedPrimitiveWithoutWidening() {
		long[] bits = new long[1];
		assertTrue(ManagedJavaValue.parse("-7", MemoryEngineContract.TYPE_BYTE, bits));
		assertEquals("-7", ManagedJavaValue.format(MemoryEngineContract.TYPE_BYTE, bits[0]));
		assertTrue(ManagedJavaValue.parse("0x7f", MemoryEngineContract.TYPE_BYTE, bits));
		assertEquals("127", ManagedJavaValue.format(MemoryEngineContract.TYPE_BYTE, bits[0]));
		assertFalse(ManagedJavaValue.parse("0xff", MemoryEngineContract.TYPE_BYTE, bits));
		assertTrue(ManagedJavaValue.parse("65535", MemoryEngineContract.TYPE_CHAR, bits));
		assertEquals("65535", ManagedJavaValue.format(MemoryEngineContract.TYPE_CHAR, bits[0]));
		assertFalse(ManagedJavaValue.parse("256", MemoryEngineContract.TYPE_BYTE, bits));
		assertFalse(ManagedJavaValue.parse("2147483648", MemoryEngineContract.TYPE_INT, bits));
		assertTrue(ManagedJavaValue.parse("9007199254740993", MemoryEngineContract.TYPE_LONG, bits));
		assertEquals("9007199254740993", ManagedJavaValue.format(MemoryEngineContract.TYPE_LONG, bits[0]));
		assertTrue(ManagedJavaValue.parse("-0.0", MemoryEngineContract.TYPE_FLOAT, bits));
		assertEquals("-0.0", ManagedJavaValue.format(MemoryEngineContract.TYPE_FLOAT, bits[0]));
		assertTrue(ManagedJavaValue.parse("1.0e-300", MemoryEngineContract.TYPE_DOUBLE, bits));
		assertEquals("1.0E-300", ManagedJavaValue.format(MemoryEngineContract.TYPE_DOUBLE, bits[0]));
		assertFalse(ManagedJavaValue.parse("1.0f", MemoryEngineContract.TYPE_FLOAT, bits));
	}

	@Test
	public void knownPredicatesAreInclusiveAndSignedZeroIsNumericEqual() {
		int type = MemoryEngineContract.TYPE_INT;
		assertTrue(ManagedJavaValue.matchesKnown(type, MemoryEngineContract.PREDICATE_EQUAL,
				7L, 7L, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(type, MemoryEngineContract.PREDICATE_NOT_EQUAL,
				8L, 7L, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(type, MemoryEngineContract.PREDICATE_BETWEEN,
				7L, 7L, 9L));
		long negativeZero = Float.floatToRawIntBits(-0.0f) & 0xffffffffL;
		assertTrue(ManagedJavaValue.matchesKnown(MemoryEngineContract.TYPE_FLOAT,
				MemoryEngineContract.PREDICATE_EQUAL, negativeZero, 0L, 0L));
	}

	@Test
	public void knownOrderingPredicatesAndInvalidFloatingQueriesMatchContract() {
		int type = MemoryEngineContract.TYPE_INT;
		assertTrue(ManagedJavaValue.matchesKnown(type, MemoryEngineContract.PREDICATE_EQUAL,
				7L, 7L, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(type, MemoryEngineContract.PREDICATE_NOT_EQUAL,
				7L, 6L, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(type, MemoryEngineContract.PREDICATE_GREATER,
				7L, 6L, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(type, MemoryEngineContract.PREDICATE_LESS,
				7L, 8L, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(type,
				MemoryEngineContract.PREDICATE_GREATER_OR_EQUAL, 7L, 7L, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(type,
				MemoryEngineContract.PREDICATE_LESS_OR_EQUAL, 7L, 7L, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(type, MemoryEngineContract.PREDICATE_BETWEEN,
				7L, 7L, 7L));
		long[] bits = new long[1];
		assertFalse(ManagedJavaValue.parse("NaN", MemoryEngineContract.TYPE_DOUBLE, bits));
		assertFalse(ManagedJavaValue.parse("Infinity", MemoryEngineContract.TYPE_DOUBLE, bits));
		assertFalse(ManagedJavaValue.validKnownQuery(MemoryEngineContract.TYPE_INT,
				MemoryEngineContract.PREDICATE_BETWEEN, 8L, 7L));
		assertFalse(ManagedJavaValue.validKnownQuery(MemoryEngineContract.TYPE_INT,
				MemoryEngineContract.PREDICATE_EQUAL, 8L, 1L));
	}

	@Test
	public void relativePredicatesUseNumericFloatingRulesAndUnsignedLongMagnitude() {
		int floatType = MemoryEngineContract.TYPE_FLOAT;
		long one = Float.floatToRawIntBits(1.0f) & 0xffffffffL;
		long two = Float.floatToRawIntBits(2.0f) & 0xffffffffL;
		assertTrue(ManagedJavaValue.matchesRelative(floatType,
				MemoryEngineContract.PREDICATE_INCREASED_BY, two, one, one, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(floatType,
				MemoryEngineContract.PREDICATE_UNCHANGED, 0x80000000L, 0L, 0L, 0L));
		long nan = Float.floatToRawIntBits(Float.NaN) & 0xffffffffL;
		assertFalse(ManagedJavaValue.matchesRelative(floatType,
				MemoryEngineContract.PREDICATE_CHANGED, nan, one, 0L, 0L));

		long minimum = Long.MIN_VALUE;
		long maximum = Long.MAX_VALUE;
		assertTrue(ManagedJavaValue.matchesRelative(MemoryEngineContract.TYPE_LONG,
				MemoryEngineContract.PREDICATE_CHANGED_BY, maximum, minimum, -1L, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(MemoryEngineContract.TYPE_LONG,
				MemoryEngineContract.PREDICATE_INCREASED_BY, maximum, minimum, -1L, 0L));

		int intType = MemoryEngineContract.TYPE_INT;
		assertTrue(ManagedJavaValue.matchesRelative(intType, MemoryEngineContract.PREDICATE_CHANGED,
				7L, 5L, 0L, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(intType,
				MemoryEngineContract.PREDICATE_UNCHANGED, 5L, 5L, 0L, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(intType,
				MemoryEngineContract.PREDICATE_INCREASED, 7L, 5L, 0L, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(intType,
				MemoryEngineContract.PREDICATE_DECREASED, 3L, 5L, 0L, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(intType,
				MemoryEngineContract.PREDICATE_INCREASED_BY, 7L, 5L, 2L, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(intType,
				MemoryEngineContract.PREDICATE_DECREASED_BY, 3L, 5L, 2L, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(intType,
				MemoryEngineContract.PREDICATE_CHANGED_BY, 3L, 5L, 2L, 0L));
		assertTrue(ManagedJavaValue.matchesRelative(intType,
				MemoryEngineContract.PREDICATE_INCREASED_BY_RANGE, 8L, 5L, 2L, 3L));
		assertTrue(ManagedJavaValue.matchesRelative(intType,
				MemoryEngineContract.PREDICATE_DECREASED_BY_RANGE, 2L, 5L, 2L, 3L));
	}

	@Test
	public void relativeMagnitudeParserAcceptsFullUnsignedLongWidth() {
		long[] bits = new long[1];
		assertTrue(ManagedJavaValue.parseMagnitude("18446744073709551615",
				MemoryEngineContract.TYPE_LONG, bits));
		assertEquals(-1L, bits[0]);
		assertFalse(ManagedJavaValue.parseMagnitude("18446744073709551616",
				MemoryEngineContract.TYPE_LONG, bits));
		assertTrue(ManagedJavaValue.validRelativeQuery(MemoryEngineContract.TYPE_LONG,
				MemoryEngineContract.PREDICATE_CHANGED_BY, -1L, 0L));
		assertTrue(ManagedJavaValue.validRelativeQuery(MemoryEngineContract.TYPE_BYTE,
				MemoryEngineContract.PREDICATE_CHANGED_BY, 255L, 0L));
		assertFalse(ManagedJavaValue.parseMagnitude("1.0f",
				MemoryEngineContract.TYPE_FLOAT, bits));
	}
}
