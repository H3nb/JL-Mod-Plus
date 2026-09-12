/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryInputModelTest {
    @Test fun integerSpecsRejectWrongCharactersAndOutOfRangeValues() {
        val signed = MemoryInputSpec.forType(MemoryEngineContract.TYPE_BYTE)
        assertTrue(signed.acceptsPartial("-"))
        assertTrue(signed.acceptsPartial("-12"))
        assertFalse(signed.acceptsPartial("1.2"))
        assertTrue(signed.isComplete("127"))
        assertTrue(signed.isComplete("-128"))
        assertFalse(signed.isComplete("128"))

        val unsigned = MemoryInputSpec.forType(MemoryEngineContract.TYPE_CHAR)
        assertTrue(unsigned.acceptsPartial("65535"))
        assertFalse(unsigned.acceptsPartial("-1"))
        assertTrue(unsigned.isComplete("65535"))
        assertFalse(unsigned.isComplete("65536"))
    }

    @Test fun floatingSpecAllowsUsefulPartialScientificNotationOnly() {
        val spec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_DOUBLE)
        for (partial in listOf("-", ".", "-.", "1.", "1.5", "1.5E", "1.5E-", "1.5E-3")) {
            assertTrue(partial, spec.acceptsPartial(partial))
        }
        for (invalid in listOf("1..2", "E2", "1E2E3", "1A", "--1")) {
            assertFalse(invalid, spec.acceptsPartial(invalid))
        }
        assertFalse(spec.isComplete("1.5E-"))
        assertTrue(spec.isComplete("1.5E-3"))
    }

    @Test fun groupSearchSeparatorValidatesEachPartialTerm() {
        val spec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_INT)
        assertTrue(spec.acceptsPartial("1;"))
        assertTrue(spec.acceptsPartial("1;2"))
        assertTrue(spec.acceptsPartial("-1;2;-3"))
        assertFalse(spec.acceptsPartial(";1"))
        assertFalse(spec.acceptsPartial("1;;2"))
        assertFalse(spec.acceptsPartial("1;2.5"))
        assertFalse(spec.acceptsPartial("1;2;3;4;5;6;7;8;9"))
    }

    @Test fun groupSearchUsesPerTermWidthInsteadOfSingleValueWidth() {
        val byteSpec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_BYTE)
        assertTrue(byteSpec.acceptsPartial("1;2"))
        assertTrue(byteSpec.acceptsPartial("-12;127"))
        assertFalse(byteSpec.acceptsPartial("-129;1"))
    }

    @Test fun float32RejectsOverflowThatDoubleWouldAccept() {
        assertFalse(MemoryInputSpec.forType(MemoryEngineContract.TYPE_FLOAT).isComplete("1e39"))
        assertTrue(MemoryInputSpec.forType(MemoryEngineContract.TYPE_DOUBLE).isComplete("1e39"))
    }

    @Test fun relativeMagnitudeUsesUnsignedLongWidthAndRejectsNegativeValues() {
        val longSpec = MemoryInputSpec.relativeMagnitudeForType(MemoryEngineContract.TYPE_LONG)
        assertTrue(longSpec.isComplete("18446744073709551615"))
        assertFalse(longSpec.isComplete("18446744073709551616"))
        assertFalse(longSpec.acceptsPartial("-1"))

        val floatSpec = MemoryInputSpec.relativeMagnitudeForType(MemoryEngineContract.TYPE_FLOAT)
        assertFalse(floatSpec.isComplete("-1.0"))
        assertTrue(floatSpec.isComplete("1.0"))
        assertTrue(floatSpec.acceptsPartial("1e-3"))
    }
}
